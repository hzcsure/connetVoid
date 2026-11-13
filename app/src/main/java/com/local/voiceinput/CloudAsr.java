package com.local.voiceinput;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 硅基流动在线识别（OpenAI 风格的 multipart 上传）。
 *
 * 同一份音频同时发给两个模型，谁先给出可信结果就用谁：
 *   - SenseVoiceSmall  准确率高、静音返回空串不乱出字，但排队延迟 0.6~7 秒不稳定；
 *   - TeleSpeechASR    延迟稳定在 1 秒，但静音段偶尔幻觉（会把噪声听成词）。
 *
 * 策略：SenseVoice 回来了优先用它；它太慢时用 TeleSpeech 把延迟兜住。
 * 任何异常都抛 IOException，由 LocalRecognitionService 决定是否回退本地 Vosk。
 */
final class CloudAsr {

    static final String ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions";
    static final String MODEL_SENSE = "FunAudioLLM/SenseVoiceSmall";
    static final String MODEL_TELE = "TeleAI/TeleSpeechASR";
    /** Key 放在 /sdcard/voice/asr.key：重装 APK 不会丢，也不用重新编译。 */
    static final String KEY_FILE = "/sdcard/voice/asr.key";

    private static final String TAG = "VoskCloud";
    /** TeleSpeech 先回来后，再多等 SenseVoice 这么久拿更准的结果。 */
    private static final long SENSE_GRACE_MS = 700;

    private CloudAsr() { }

    /** 优先读运行时 Key 文件，其次读编译期注入的值（本地调试用）。 */
    static String apiKey() {
        try {
            File f = new File(KEY_FILE);
            if (f.canRead()) {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"));
                try {
                    String line = r.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (line.startsWith("sk-") && line.length() > 20) return line;
                    }
                } finally {
                    r.close();
                }
            }
        } catch (Throwable ignored) { }
        String baked = BuildConfig.ASR_KEY;
        if (baked != null && baked.startsWith("sk-") && baked.length() > 20) return baked;
        return null;
    }

    static boolean available() {
        return apiKey() != null;
    }

    /** Key 只露前 8 位，避免日志泄密。 */
    static String keyHint() {
        String k = apiKey();
        if (k == null) return "未配置";
        return k.substring(0, Math.min(8, k.length())) + "…";
    }

    /**
     * 双模型竞速。返回识别文本（可能为空串表示确实没说话）；
     * 两个都没在预算内给出结果时抛 IOException。
     */
    static String transcribeSmart(short[] pcm, int offset, int count, int budgetMs) throws IOException {
        String key = apiKey();
        if (key == null) throw new IOException("没有 API Key");

        byte[] wav = wav(pcm, offset, count);
        final Call sense = new Call(MODEL_SENSE, wav, key);
        final Call tele = new Call(MODEL_TELE, wav, key);

        long t0 = System.currentTimeMillis();
        start(sense, budgetMs + 8000);
        start(tele, budgetMs + 8000);

        long deadline = t0 + budgetMs;
        while (true) {
            // 准确的那个已经给出结果（含空串=确实没说话），直接采用
            if (sense.done && sense.text != null) break;
            // 快但不那么准的那个回来了，稍等一会儿看准确的那个能不能赶上
            if (tele.done && tele.text != null
                    && System.currentTimeMillis() > tele.doneAt + SENSE_GRACE_MS) break;
            if (System.currentTimeMillis() >= deadline) break;
            sleep(40);
        }
        long cost = System.currentTimeMillis() - t0;
        Log.i(TAG, "race " + cost + "ms sense=" + state(sense) + " tele=" + state(tele));

        if (sense.done && sense.text != null) return sense.text;
        if (tele.done && tele.text != null) return tele.text;
        Throwable e = sense.error != null ? sense.error : tele.error;
        throw new IOException(e != null ? String.valueOf(e.getMessage()) : "两个模型都没返回");
    }

    private static String state(Call c) {
        if (!c.done) return "未完成";
        if (c.error != null) return "失败";
        String t = c.text == null ? "" : c.text;
        return "'" + (t.length() > 12 ? t.substring(0, 12) + "…" : t) + "'";
    }

    private static void start(final Call c, final int timeoutMs) {
        Thread t = new Thread(() -> c.run(timeoutMs), "cloud-" + c.model);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** 一次单模型请求。 */
    private static final class Call {
        final String model;
        final byte[] wav;
        final String key;
        volatile String text;
        volatile Throwable error;
        volatile boolean done;
        volatile long doneAt;

        Call(String model, byte[] wav, String key) {
            this.model = model;
            this.wav = wav;
            this.key = key;
        }

        void run(int timeoutMs) {
            try {
                text = post(wav, model, key, timeoutMs);
                Log.i(TAG, model + " -> '" + text + "'");
            } catch (Throwable t) {
                error = t;
                Log.w(TAG, model + " 失败: " + t.getMessage());
            } finally {
                doneAt = System.currentTimeMillis();
                done = true;
            }
        }
    }

    private static String post(byte[] wav, String model, String key, int timeoutMs) throws IOException {
        String boundary = "----tvvoice" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(Math.min(2500, timeoutMs));
            c.setReadTimeout(Math.max(1000, timeoutMs));
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setFixedLengthStreamingMode(head.length + wav.length + tail.length);
            c.setUseCaches(false);

            OutputStream out = c.getOutputStream();
            try {
                out.write(head);
                out.write(wav);
                out.write(tail);
                out.flush();
            } finally {
                out.close();
            }

            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + brief(body));
            }
            try {
                return new JSONObject(body).optString("text", "");
            } catch (Throwable t) {
                throw new IOException("响应不是 JSON: " + brief(body));
            }
        } finally {
            c.disconnect();
        }
    }

    /** 16bit 单声道 WAV 头 + 数据。 */
    private static byte[] wav(short[] pcm, int offset, int count) {
        int dataLen = count * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);      // PCM
        bb.putShort((short) 1);      // 单声道
        bb.putInt(16000);
        bb.putInt(16000 * 2);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put("data".getBytes());
        bb.putInt(dataLen);
        for (int i = 0; i < count; i++) {
            bb.putShort(pcm[offset + i]);
        }
        return bb.array();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
