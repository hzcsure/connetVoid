package com.local.voiceinput;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 以标准 android.speech.RecognitionService 身份运行。
 * SmartTube 点击麦克风 -> 系统绑定本服务 -> 取音频 -> Vosk 离线识别 -> 回传文本。
 *
 * 音频来源有两种，优先级从高到低：
 *   1. root 中继（RootRelaySource）：这台电视的系统 HAL 不给 app 真麦克风数据，
 *      只能由 root 从 PDM 通道采集后经 /sdcard/voice 中继过来；
 *   2. AudioRecord：其它设备上的常规路径，本机大概率只能拿到全零。
 */
public class LocalRecognitionService extends RecognitionService {

    private static final String TAG = "VoskRec";
    private static final int SAMPLE_RATE = 16000;
    private static final long END_SILENCE_MS = 1800L;   // 静音多久判定"说完了"
    private static final long MIN_SPEECH_MS = 600L;     // 至少录这么久才允许因静音结束
    private static final long MAX_TOTAL_MS = 20000L;    // 单次识别上限
    private static final float VOICE_MIN_RMS = 60f;     // 绝对下限（实测底噪 ~25）
    private static final float VOICE_RATIO = 2.5f;      // 相对底噪的倍数
    private static final float GAIN = 4.0f;             // PDM 电平偏低，送入 Vosk 前放大

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Model model;
    private volatile boolean listening;
    private Thread worker;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        String lang = recognizerIntent == null ? "?" :
                recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        Log.i(TAG, "onStartListening lang=" + lang);
        stopWorker();
        listening = true;
        worker = new Thread(() -> recognize(listener), "vosk-recognize");
        worker.start();
    }

    @Override
    protected void onStopListening(Callback listener) {
        Log.i(TAG, "onStopListening");
        listening = false;
    }

    @Override
    protected void onCancel(Callback listener) {
        Log.i(TAG, "onCancel");
        listening = false;
    }

    @Override
    public void onDestroy() {
        stopWorker();
        Model m = model;
        model = null;
        if (m != null) {
            try { m.close(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    private void stopWorker() {
        listening = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            try { t.join(600); } catch (InterruptedException ignored) { }
        }
    }

    private void recognize(final Callback listener) {
        File dir = ModelInstaller.ensureModel(this);
        if (dir == null) {
            Log.e(TAG, "model unavailable");
            fail(listener, SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        PcmSource src = null;
        Recognizer rec = null;
        try {
            Model m = model;
            if (m == null) {
                Log.i(TAG, "loading model: " + dir);
                long t0 = System.currentTimeMillis();
                m = new Model(dir.getAbsolutePath());
                model = m;
                Log.i(TAG, "model loaded in " + (System.currentTimeMillis() - t0) + "ms");
            }
            rec = new Recognizer(m, SAMPLE_RATE);

            src = openSource(listener);
            if (src == null) return;

            main.post(() -> {
                try { listener.readyForSpeech(null); } catch (Throwable ignored) { }
                try { listener.beginningOfSpeech(); } catch (Throwable ignored) { }
            });

            byte[] buf = new byte[3200]; // ~100ms @16k/16bit/mono
            long start = System.currentTimeMillis();
            long lastVoice = start;
            long lastPartial = 0L;
            boolean anyVoice = false;
            int totalRead = 0;
            float peakRms = 0f;
            float baseline = 0f;
            long zeroReads = 0;

            while (listening) {
                int n = src.read(buf, buf.length);
                if (n <= 0) {
                    zeroReads++;
                    if (zeroReads == 40) {
                        // 连续 2 秒拿不到任何数据：多半是中继没跑起来
                        Log.e(TAG, "audio source produced nothing for 2s");
                        fail(listener, SpeechRecognizer.ERROR_AUDIO);
                        return;
                    }
                    continue;
                }
                zeroReads = 0;
                totalRead += n;

                float rms = rms(buf, n);
                if (baseline <= 0f) baseline = rms;
                if (rms < baseline * 2f) baseline = baseline * 0.9f + rms * 0.1f;
                float threshold = Math.max(VOICE_MIN_RMS, baseline * VOICE_RATIO);

                if (rms > peakRms) peakRms = rms;
                if (rms > threshold) {
                    lastVoice = System.currentTimeMillis();
                    anyVoice = true;
                }

                applyGain(buf, n, GAIN);

                if (rec.acceptWaveForm(buf, n)) {
                    String text = extract(rec.getResult(), "text");
                    if (text != null && !text.isEmpty()) {
                        Log.i(TAG, "result: " + text + " peakRms=" + peakRms);
                        finish(listener, text);
                        return;
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastPartial > 350) {
                        lastPartial = now;
                        String p = extract(rec.getPartialResult(), "partial");
                        if (p != null && !p.isEmpty()) publishPartial(listener, p);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - start > MAX_TOTAL_MS) {
                    Log.i(TAG, "max duration reached, peakRms=" + peakRms);
                    break;
                }
                if (now - lastVoice > END_SILENCE_MS && now - start > MIN_SPEECH_MS) {
                    Log.i(TAG, "silence timeout, peakRms=" + peakRms);
                    break;
                }
            }

            String text = extract(rec.getFinalResult(), "text");
            Log.i(TAG, "final: '" + (text == null ? "" : text) + "' bytes=" + totalRead
                    + " peakRms=" + peakRms + " baseline=" + baseline);
            if (text != null && !text.isEmpty()) {
                finish(listener, text);
            } else if (!anyVoice) {
                Log.w(TAG, "no voice detected, peakRms=" + peakRms + " threshold="
                        + Math.max(VOICE_MIN_RMS, baseline * VOICE_RATIO));
                fail(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            } else {
                fail(listener, SpeechRecognizer.ERROR_NO_MATCH);
            }
        } catch (Throwable t) {
            Log.e(TAG, "recognize failed", t);
            fail(listener, SpeechRecognizer.ERROR_CLIENT);
        } finally {
            listening = false;
            if (src != null) {
                try { src.close(); } catch (Throwable ignored) { }
            }
            if (rec != null) {
                try { rec.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 优先用 root 中继；不可用时退回 AudioRecord。 */
    private PcmSource openSource(final Callback listener) {
        if (RootRelaySource.available()) {
            RootRelaySource relay = new RootRelaySource();
            if (relay.open()) {
                Log.i(TAG, "using root relay, segments=" + RootRelaySource.segmentCount());
                return relay;
            }
            Log.w(TAG, "relay present but open failed");
        }
        return openAudioRecord(listener);
    }

    private PcmSource openAudioRecord(final Callback listener) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO denied");
            fail(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return null;
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, 16384);

        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        AudioRecord record = null;
        for (int s : sources) {
            AudioRecord candidate = new AudioRecord(s, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                record = candidate;
                Log.i(TAG, "AudioRecord initialized, source=" + s);
                break;
            }
            Log.w(TAG, "AudioRecord init failed for source=" + s);
            candidate.release();
        }
        if (record == null) {
            Log.e(TAG, "no AudioRecord available");
            fail(listener, SpeechRecognizer.ERROR_AUDIO);
            return null;
        }

        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "startRecording failed");
            record.release();
            fail(listener, SpeechRecognizer.ERROR_AUDIO);
            return null;
        }

        final AudioRecord rec = record;
        return new PcmSource() {
            @Override
            public int read(byte[] out, int maxBytes) {
                return rec.read(out, 0, maxBytes);
            }

            @Override
            public void close() {
                try { rec.stop(); } catch (Throwable ignored) { }
                try { rec.release(); } catch (Throwable ignored) { }
            }
        };
    }

    private void finish(final Callback listener, final String text) {
        listening = false;
        final Bundle b = new Bundle();
        ArrayList<String> list = new ArrayList<>(Collections.singletonList(text));
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
        b.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[]{1.0f});
        main.post(() -> {
            try { listener.endOfSpeech(); } catch (Throwable ignored) { }
            try { listener.results(b); } catch (Throwable t) { Log.e(TAG, "results() failed", t); }
        });
    }

    private void publishPartial(final Callback listener, final String text) {
        final Bundle b = new Bundle();
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                new ArrayList<>(Collections.singletonList(text)));
        main.post(() -> {
            try { listener.partialResults(b); } catch (Throwable ignored) { }
        });
    }

    private void fail(final Callback listener, final int code) {
        listening = false;
        main.post(() -> {
            try { listener.error(code); } catch (Throwable ignored) { }
        });
    }

    private static float rms(byte[] data, int len) {
        int count = len / 2;
        if (count == 0) return 0f;
        long sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            sum += (long) s * s;
        }
        return (float) Math.sqrt((double) sum / count);
    }

    private static void applyGain(byte[] data, int len, float gain) {
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            int v = (int) (s * gain);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            data[i] = (byte) (v & 0xff);
            data[i + 1] = (byte) ((v >> 8) & 0xff);
        }
    }

    private static String extract(String json, String key) {
        if (json == null) return null;
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Throwable t) {
            return null;
        }
    }
}
