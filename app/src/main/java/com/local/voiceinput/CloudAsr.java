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
 * 硅基流动 SenseVoice 在线识别（OpenAI 风格的 multipart 上传）。
 *
 * 实测（本机 + 电视）：7 秒音频 0.56~0.87 秒返回，静音段返回空串不乱出字，
 * "周杰伦 稻香" 能一次识别正确（本地 Vosk 会听成"的偶像"）。
 *
 * 任何失败都抛 IOException，由 LocalRecognitionService 决定是否回退本地 Vosk。
 */
final class CloudAsr {

    static final String ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions";
    static final String MODEL = "FunAudioLLM/SenseVoiceSmall";
    /** Key 放在 /sdcard/voice/asr.key：重装 APK 不会丢，也不需要重新编译。 */
    static final String KEY_FILE = "/sdcard/voice/asr.key";

    private static final String TAG = "VoskCloud";

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
     * 上传 [offset, offset+count) 的 16k 单声道 PCM，返回识别文本（可能为空串）。
     */
    static String transcribe(short[] pcm, int offset, int count, int timeoutMs) throws IOException {
        String key = apiKey();
        if (key == null) throw new IOException("没有 API Key");

        byte[] wav = wav(pcm, offset, count);
        String boundary = "----tvvoice" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + MODEL + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(Math.min(3000, timeoutMs));
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

    static void logConfig() {
        Log.i(TAG, "cloud asr key=" + keyHint());
    }
}
