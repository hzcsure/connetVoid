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
 * SmartTube 点击麦克风 -> 系统绑定本服务 -> 取音频 -> 识别 -> 回传文本。
 *
 * 识别策略（v1.4 起）：**在线优先，本地兜底**
 *   1. 先说一句"人话"给硅基流动 SenseVoice（0.6~0.9 秒返回，准确率远高于本地小模型）；
 *   2. 网络异常 / 超时 / 没有 Key 时，自动回退本地 Vosk 离线识别，保证断网也能用。
 *
 * 音频来源有两种，优先级从高到低：
 *   1. root 中继（RootRelaySource）：这台电视的系统 HAL 不给 app 真麦克风数据，
 *      只能由 root 从 PDM 通道采集后经 /sdcard/voice 中继过来；
 *   2. AudioRecord：其它设备上的常规路径，本机只能拿到全零。
 */
public class LocalRecognitionService extends RecognitionService {

    private static final String TAG = "VoskRec";
    private static final int SAMPLE_RATE = 16000;        // 上传/识别统一用 16k
    private static final long END_SILENCE_MS = 1500L;    // 说完后静音多久判定结束（云端快，可更短）
    private static final long WAIT_FIRST_MS = 10000L;    // 还没开口时最多等多久
    private static final long MIN_SPEECH_MS = 600L;      // 至少录这么久才允许因静音结束
    private static final long MAX_TOTAL_MS = 20000L;     // 单次识别上限
    private static final int MAX_SAMPLES = 20 * SAMPLE_RATE;
    private static final int PAD_SAMPLES = SAMPLE_RATE / 2;   // 前后各留 0.5 秒余量
    /**
     * 云端最多等这么久，超时就回退本地。
     * 实测硅基流动的排队延迟波动很大（同一接口 0.6s ~ 21s），
     * 语音搜索不能让人干等，所以卡一个上限，宁可给本地结果。
     */
    private static final int CLOUD_BUDGET_MS = 3500;
    private static final int CLOUD_READ_TIMEOUT_MS = 9000;   // HTTP 自身超时，比预算宽，便于晚到也记账

    // 本地兜底路径（Vosk）用的参数：这条麦克风电平很低，必须放大到正常录音电平，
    // 并压掉 100-300Hz 的隆隆声（实测占能量 44%）。
    private static final float VOICE_MIN_RMS = 45f;
    private static final float VOICE_RATIO = 2.5f;
    private static final float TARGET_RMS = 5000f;
    private static final float MIN_GAIN = 1.0f;
    private static final float MAX_GAIN = 40.0f;
    private static final float SPEECH_FLOOR = 80f;
    private static final float GATE_LOW = 0.60f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object modelLock = new Object();
    private volatile Model model;
    private volatile boolean listening;
    private int sourceRate = SAMPLE_RATE;
    private Thread worker;
    private volatile Thread warmer;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        String lang = recognizerIntent == null ? "?" :
                recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        Log.i(TAG, "onStartListening lang=" + lang + " cloud=" + CloudAsr.keyHint());
        stopWorker();
        listening = true;
        warmUp();   // 趁用户还在说话，后台把本地模型加载好，兜底时几乎零等待
        worker = new Thread(() -> recognize(listener), "voice-recognize");
        worker.start();
    }

    /** 后台预热本地模型（解压 + 加载约 3 秒）。失败无所谓，只在回退时用到。 */
    private void warmUp() {
        if (model != null) return;
        Thread t = warmer;
        if (t != null && t.isAlive()) return;
        Thread w = new Thread(() -> {
            try {
                File dir = ModelInstaller.ensureModel(this);
                if (dir == null) return;
                synchronized (modelLock) {
                    if (model != null) return;
                    long t0 = System.currentTimeMillis();
                    model = new Model(dir.getAbsolutePath());
                    Log.i(TAG, "model preloaded in " + (System.currentTimeMillis() - t0) + "ms");
                }
            } catch (Throwable e) {
                Log.w(TAG, "model preload failed: " + e);
            }
        }, "vosk-warmup");
        w.setDaemon(true);
        warmer = w;
        w.start();
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
        PcmSource src = null;
        PcmDump rawDump = null;
        try {
            src = openSource(listener);
            if (src == null) return;

            final int rate = sourceRate;
            // 48k -> 16k：先低通再每 3 个取 1 个，避免混叠
            final int factor = (rate % SAMPLE_RATE == 0) ? rate / SAMPLE_RATE : 1;
            final Decimator dec = factor > 1 ? new Decimator(factor, rate) : null;

            main.post(() -> {
                try { listener.readyForSpeech(null); } catch (Throwable ignored) { }
                try { listener.beginningOfSpeech(); } catch (Throwable ignored) { }
            });

            short[] pcm = new short[MAX_SAMPLES];
            int count = 0;
            int firstVoiceAt = -1;
            int lastVoiceAt = -1;

            byte[] buf = new byte[(rate / 10) * 2];  // 每块约 100ms 单声道 16bit
            long start = System.currentTimeMillis();
            long lastVoice = start;
            boolean anyVoice = false;
            float peakRms = 0f;
            float baseline = 0f;
            long zeroReads = 0;
            rawDump = PcmDump.open(this, "dbg_raw.pcm");

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
                if (rawDump != null) rawDump.write(buf, n);

                float rms = rms(buf, n);
                if (baseline <= 0f) baseline = rms;
                if (rms < baseline * 2f) baseline = baseline * 0.9f + rms * 0.1f;
                float threshold = Math.max(VOICE_MIN_RMS, baseline * VOICE_RATIO);
                boolean voiced = rms > threshold;

                if (rms > peakRms) peakRms = rms;
                if (voiced) {
                    lastVoice = System.currentTimeMillis();
                    anyVoice = true;
                    if (firstVoiceAt < 0) firstVoiceAt = count;
                }

                int room = MAX_SAMPLES - count;
                // 一个 100ms 块最多产出 1600 个样本，留够余量再写，避免越界
                if (room >= 2048) {
                    int added = (dec != null)
                            ? dec.process(buf, n, pcm, count)
                            : bytesToShorts(buf, n, pcm, count);
                    if (added > room) added = room;
                    count += added;
                    if (voiced) lastVoiceAt = count;
                }

                long now = System.currentTimeMillis();
                if (now - start > MAX_TOTAL_MS || count >= MAX_SAMPLES) {
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

            listening = false;
            Log.i(TAG, "capture done: " + count + " samples (" + (count / 16) + "ms)"
                    + " anyVoice=" + anyVoice + " peakRms=" + peakRms + " baseline=" + baseline);

            if (!anyVoice || count < SAMPLE_RATE / 4) {
                Log.w(TAG, "no voice detected, peakRms=" + peakRms);
                fail(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                return;
            }

            int s = Math.max(0, firstVoiceAt - PAD_SAMPLES);
            int e = Math.min(count, (lastVoiceAt > 0 ? lastVoiceAt : count) + PAD_SAMPLES);
            int len = Math.max(0, e - s);
            Log.i(TAG, "trim " + s + ".." + e + " => " + (len / 16) + "ms");

            // ---- 第一步：在线识别（带预算，超时就放弃） ----
            final short[] clip = pcm;
            final int clipStart = s, clipLen = len;
            final String[] cloudText = new String[1];
            final Throwable[] cloudErr = new Throwable[1];
            final boolean[] cloudDone = new boolean[1];
            Thread cloudThread = null;

            if (CloudAsr.available()) {
                cloudThread = new Thread(() -> {
                    try {
                        long t0 = System.currentTimeMillis();
                        cloudText[0] = CloudAsr.transcribe(clip, clipStart, clipLen, CLOUD_READ_TIMEOUT_MS);
                        Log.i(TAG, "cloud ok in " + (System.currentTimeMillis() - t0)
                                + "ms: '" + cloudText[0] + "'");
                    } catch (Throwable err) {
                        cloudErr[0] = err;
                        Log.w(TAG, "cloud failed: " + err.getMessage());
                    } finally {
                        synchronized (cloudDone) {
                            cloudDone[0] = true;
                            cloudDone.notifyAll();
                        }
                    }
                }, "cloud-asr");
                cloudThread.setDaemon(true);
                cloudThread.start();

                synchronized (cloudDone) {
                    long deadline = System.currentTimeMillis() + CLOUD_BUDGET_MS;
                    while (!cloudDone[0]) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) break;
                        try {
                            cloudDone.wait(left);
                        } catch (InterruptedException ignored) { }
                    }
                }
            } else {
                Log.i(TAG, "no api key, 直接走本地 Vosk");
            }

            if (cloudText[0] != null) {
                // 云端明确返回（空串表示确实没说话），不再重复跑本地
                String clean = tidy(cloudText[0]);
                if (clean.isEmpty()) {
                    Log.w(TAG, "cloud returned empty -> 没听到内容");
                    fail(listener, SpeechRecognizer.ERROR_NO_MATCH);
                } else {
                    finish(listener, clean);
                }
                return;
            }
            if (cloudErr[0] != null) {
                Log.w(TAG, "回退本地 Vosk（云端异常）");
            } else if (cloudThread != null) {
                Log.w(TAG, "云端超过 " + CLOUD_BUDGET_MS + "ms 未返回，回退本地 Vosk");
            }

            // ---- 第二步：本地 Vosk 兜底；云端已在跑时不再刷中间结果，避免闪字 ----
            String local = runLocal(pcm, s, len, cloudThread == null ? listener : null);
            if (local != null && !local.isEmpty()) {
                finish(listener, local);
            } else {
                Log.w(TAG, "local fallback also empty");
                fail(listener, SpeechRecognizer.ERROR_NO_MATCH);
            }
        } catch (Throwable t) {
            Log.e(TAG, "recognize failed", t);
            fail(listener, SpeechRecognizer.ERROR_CLIENT);
        } finally {
            listening = false;
            if (rawDump != null) rawDump.close();
            if (src != null) {
                try { src.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 本地 Vosk 兜底：对已采好的 16k PCM 做高通 + 自适应增益，再喂识别器。 */
    private String runLocal(short[] pcm, int start, int len, final Callback listener) {
        Recognizer rec = null;
        try {
            File dir = ModelInstaller.ensureModel(this);
            if (dir == null) {
                Log.e(TAG, "model unavailable, cannot fallback");
                return null;
            }
            Model m;
            synchronized (modelLock) {
                m = model;
                if (m == null) {
                    Log.i(TAG, "loading model: " + dir);
                    long t0 = System.currentTimeMillis();
                    m = new Model(dir.getAbsolutePath());
                    model = m;
                    Log.i(TAG, "model loaded in " + (System.currentTimeMillis() - t0) + "ms");
                }
            }
            rec = new Recognizer(m, SAMPLE_RATE);

            Biquad hpf1 = new Biquad(300f, SAMPLE_RATE);
            Biquad hpf2 = new Biquad(300f, SAMPLE_RATE);
            StringBuilder collected = new StringBuilder();
            String lastPartial = "";

            int block = SAMPLE_RATE / 10;
            byte[] blk = new byte[block * 2];
            float speechRms = 0f;
            float gain = 8f;
            float gate = 1f;
            float baseline = 0f;
            long lastPub = 0;

            for (int off = 0; off < len; off += block) {
                int n = Math.min(block, len - off);
                for (int i = 0; i < n; i++) {
                    short v = pcm[start + off + i];
                    blk[i * 2] = (byte) (v & 0xff);
                    blk[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
                }
                int bytes = n * 2;
                float rms = rms(blk, bytes);
                if (baseline <= 0f) baseline = rms;
                if (rms < baseline * 2f) baseline = baseline * 0.9f + rms * 0.1f;
                boolean voiced = rms > Math.max(VOICE_MIN_RMS, baseline * VOICE_RATIO);

                float level = Math.max(speechRms, SPEECH_FLOOR);
                speechRms = Math.max(speechRms * 0.85f, Math.min(rms, 8000f));
                float want = TARGET_RMS / Math.max(level, SPEECH_FLOOR);
                want = Math.min(Math.max(want, MIN_GAIN), MAX_GAIN);
                if (want > gain) gain = want;
                else gain = gain * 0.9f + want * 0.1f;
                gate = gate * 0.7f + (voiced ? 1f : GATE_LOW) * 0.3f;

                hpf1.process(blk, bytes);
                hpf2.process(blk, bytes);
                applyGain(blk, bytes, gain * gate);

                if (rec.acceptWaveForm(blk, bytes)) {
                    String t = extract(rec.getResult(), "text");
                    if (t != null && !t.isEmpty()) {
                        collected.append(' ').append(t);
                        Log.i(TAG, "local segment: " + t + " -> " + tidy(collected.toString()));
                        publishPartial(listener, tidy(collected.toString()));
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastPub > 400) {
                        lastPub = now;
                        String p = extract(rec.getPartialResult(), "partial");
                        if (p != null && !p.isEmpty()) {
                            lastPartial = p;
                            publishPartial(listener, tidy(collected + " " + p));
                        }
                    }
                }
            }

            String tail = extract(rec.getFinalResult(), "text");
            if (tail != null && !tail.isEmpty()) collected.append(' ').append(tail);
            String text = tidy(collected.toString());
            if (text.isEmpty() && !lastPartial.isEmpty()) text = tidy(lastPartial);
            Log.i(TAG, "local final: '" + text + "'");
            return text;
        } catch (Throwable t) {
            Log.e(TAG, "local fallback failed", t);
            return null;
        } finally {
            if (rec != null) {
                try { rec.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 把实际采集的 PCM 落盘，便于事后核对音质（存在 app 外部私有目录）。 */
    static final class PcmDump {
        private final java.io.FileOutputStream out;
        private int written;
        private static final int LIMIT = 48000 * 2 * 30;

        private PcmDump(java.io.File f) throws java.io.IOException {
            out = new java.io.FileOutputStream(f, false);
        }

        static PcmDump open(android.content.Context ctx, String name) {
            try {
                java.io.File dir = ctx.getExternalFilesDir(null);
                if (dir == null) return null;
                return new PcmDump(new java.io.File(dir, name));
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
        if (listener == null) return;
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

    private static int bytesToShorts(byte[] data, int len, short[] dst, int pos) {
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            dst[pos + i] = (short) ((data[i * 2] & 0xff) | (data[i * 2 + 1] << 8));
        }
        return n;
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

    /** 整理识别文本：合并空白；中文结果把字间空格去掉。 */
    static String tidy(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        boolean cjk = false;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) >= 0x4E00 && t.charAt(i) <= 0x9FFF) { cjk = true; break; }
        }
        return cjk ? t.replace(" ", "") : t;
    }

    /**
     * 降采样：先做 4 阶低通（7kHz）再每 factor 个取 1 个。
     * 直接抽样会把 8k 以上的能量折回到语音带里变成噪声。
     */
    static final class Decimator {
        private final int factor;
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;
        private int phase;

        Decimator(int factor, int rate) {
            this.factor = factor;
            double fc = 7000.0;
            double w0 = 2 * Math.PI * fc / rate;
            double cw = Math.cos(w0), sw = Math.sin(w0);
            double alpha = sw / (2 * 0.707);
            double a0 = 1 + alpha;
            b0 = (float) ((1 - cw) / 2 / a0);
            b1 = (float) ((1 - cw) / a0);
            b2 = b0;
            a1 = (float) (-2 * cw / a0);
            a2 = (float) ((1 - alpha) / a0);
        }

        int process(byte[] data, int len, short[] dst, int pos) {
            int w = 0;
            for (int i = 0; i + 1 < len; i += 2) {
                float x = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x;
                y2 = y1; y1 = y;
                if (phase == 0) {
                    int v = (int) y;
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    dst[pos + w++] = (short) v;
                }
                if (++phase >= factor) phase = 0;
            }
            return w;
        }
    }

    /** 二阶高通（RBJ），去掉低频隆隆声，不吃 CPU。 */
    static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        Biquad(float fc, int rate) {
            double w0 = 2 * Math.PI * fc / rate;
            double cw = Math.cos(w0), sw = Math.sin(w0);
            double alpha = sw / (2 * 0.707);
            double a0 = 1 + alpha;
            b0 = (float) ((1 + cw) / 2 / a0);
            b1 = (float) (-(1 + cw) / a0);
            b2 = b0;
            a1 = (float) (-2 * cw / a0);
            a2 = (float) ((1 - alpha) / a0);
        }

        void process(byte[] data, int len) {
            for (int i = 0; i + 1 < len; i += 2) {
                float x = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x;
                y2 = y1; y1 = y;
                int v = (int) y;
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                data[i] = (byte) (v & 0xff);
                data[i + 1] = (byte) ((v >> 8) & 0xff);
            }
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
