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
    private static final long END_SILENCE_MS = 1800L;   // 说完后静音多久判定结束
    private static final long WAIT_FIRST_MS = 10000L;   // 还没开口时最多等多久
    private static final long MIN_SPEECH_MS = 600L;     // 至少录这么久才允许因静音结束
    private static final long MAX_TOTAL_MS = 20000L;    // 单次识别上限
    private static final float VOICE_MIN_RMS = 45f;     // 绝对下限（实测底噪 ~14，说话 ~120 以上）
    private static final float VOICE_RATIO = 2.5f;      // 相对底噪的倍数
    // PDM 麦克风电平很低（说话 RMS 约 250，满量程 32768，约 -42dBFS），
    // Vosk 的声学模型按正常录音电平训练，直接喂低电平几乎必然识别失败，
    // 因此按语音电平自适应放大到 -20dBFS 左右。
    private static final float TARGET_RMS = 3200f;
    private static final float MIN_GAIN = 1.0f;
    private static final float MAX_GAIN = 40.0f;
    private static final float SPEECH_FLOOR = 80f;      // 电平估计下限，避免把静音放大成噪声

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Model model;
    private volatile boolean listening;
    private int sourceRate = SAMPLE_RATE;
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
        PcmDump dump = null;
        try {
            Model m = model;
            if (m == null) {
                Log.i(TAG, "loading model: " + dir);
                long t0 = System.currentTimeMillis();
                m = new Model(dir.getAbsolutePath());
                model = m;
                Log.i(TAG, "model loaded in " + (System.currentTimeMillis() - t0) + "ms");
            }
            src = openSource(listener);
            if (src == null) return;

            // 中继是 48k，AudioRecord 是 16k，识别器要跟着实际音源走
            rec = new Recognizer(m, sourceRate);
            Log.i(TAG, "recognizer sampleRate=" + sourceRate);

            main.post(() -> {
                try { listener.readyForSpeech(null); } catch (Throwable ignored) { }
                try { listener.beginningOfSpeech(); } catch (Throwable ignored) { }
            });

            byte[] buf = new byte[(sourceRate / 10) * 2]; // 每块约 100ms 单声道 16bit
            long start = System.currentTimeMillis();
            long lastVoice = start;
            long lastPartial = 0L;
            boolean anyVoice = false;
            int totalRead = 0;
            float peakRms = 0f;
            float baseline = 0f;
            long zeroReads = 0;
            float speechRms = 0f;      // 平滑后的语音电平估计，用于算增益
            float gain = 8f;
            String lastText = "";
            dump = PcmDump.open(this);

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

                // 自适应增益：跟住语音电平，噪声段不会把音量推到爆
                float level = Math.max(speechRms, SPEECH_FLOOR);
                speechRms = Math.max(speechRms * 0.85f, Math.min(rms, 8000f));
                float want = TARGET_RMS / Math.max(level, SPEECH_FLOOR);
                want = Math.min(Math.max(want, MIN_GAIN), MAX_GAIN);
                gain = gain * 0.5f + want * 0.5f;
                applyGain(buf, n, gain);
                if (dump != null) dump.write(buf, n);

                if (rec.acceptWaveForm(buf, n)) {
                    String text = extract(rec.getResult(), "text");
                    if (text != null && !text.isEmpty()) {
                        if (!anyVoice) {
                            // 自适应增益会把底噪放大，Vosk 偶尔会对着噪声吐出字，
                            // 没检测到人声时直接丢弃，避免"没人说话也搜出东西"
                            Log.i(TAG, "ignore result without voice: " + text);
                        } else {
                            Log.i(TAG, "result: " + text + " peakRms=" + peakRms
                                    + " gain=" + gain + " bytes=" + totalRead);
                            finish(listener, text);
                            return;
                        }
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastPartial > 350) {
                        lastPartial = now;
                        String p = extract(rec.getPartialResult(), "partial");
                        if (p != null && !p.isEmpty()) {
                            lastText = p;
                            publishPartial(listener, p);
                        }
                    }
                }

                long now = System.currentTimeMillis();
                if (now - start > MAX_TOTAL_MS) {
                    Log.i(TAG, "max duration reached, peakRms=" + peakRms);
                    break;
                }
                // 还没开口：给足反应时间；已开口：按静音间隔收尾
                long patience = anyVoice ? END_SILENCE_MS : WAIT_FIRST_MS;
                if (now - lastVoice > patience && now - start > MIN_SPEECH_MS) {
                    Log.i(TAG, "silence timeout, anyVoice=" + anyVoice + " peakRms=" + peakRms);
                    break;
                }
            }

            String text = extract(rec.getFinalResult(), "text");
            Log.i(TAG, "final: '" + (text == null ? "" : text) + "' bytes=" + totalRead
                    + " peakRms=" + peakRms + " baseline=" + baseline + " gain=" + gain);
            if (text == null || text.isEmpty()) {
                // 收尾结果为空时，用最后一条非空中间结果兜底
                if (!lastText.isEmpty()) {
                    Log.i(TAG, "fallback to last partial: " + lastText);
                    text = lastText;
                }
            }
            if (text != null && !text.isEmpty() && anyVoice) {
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
            if (dump != null) dump.close();
            if (src != null) {
                try { src.close(); } catch (Throwable ignored) { }
            }
            if (rec != null) {
                try { rec.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 把实际送进 Vosk 的 PCM 落盘，便于事后核对音质（存在 app 外部私有目录）。 */
    static final class PcmDump {
        private final java.io.FileOutputStream out;
        private int written;
        private static final int LIMIT = 48000 * 2 * 30; // 最多 30 秒（按 48k 单声道算）

        private PcmDump(java.io.File f) throws java.io.IOException {
            out = new java.io.FileOutputStream(f, false);
        }

        static PcmDump open(android.content.Context ctx) {
            try {
                java.io.File dir = ctx.getExternalFilesDir(null);
                if (dir == null) return null;
                return new PcmDump(new java.io.File(dir, "dbg_last.pcm"));
            } catch (Throwable t) {
                return null;
            }
        }

        void write(byte[] b, int len) {
            if (written >= LIMIT) return;
            int use = Math.min(len, LIMIT - written);
            try {
                out.write(b, 0, use);
                written += use;
            } catch (Throwable ignored) { }
        }

        void close() {
            try { out.close(); } catch (Throwable ignored) { }
        }
    }

    /** 优先用 root 中继；不可用时退回 AudioRecord。 */
    private PcmSource openSource(final Callback listener) {
        sourceRate = SAMPLE_RATE;
        if (RootRelaySource.available()) {
            RootRelaySource relay = new RootRelaySource();
            if (relay.open()) {
                sourceRate = RootRelaySource.RATE;
                Log.i(TAG, "using root relay, segments=" + RootRelaySource.segmentCount()
                        + " rate=" + sourceRate);
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
