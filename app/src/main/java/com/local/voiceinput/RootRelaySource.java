package com.local.voiceinput;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 从 root 中继采集出来的分段音频里读取麦克风数据。
 *
 * 背景：这台电视（mulan / Android 9）的 audio HAL 被换成了白名单版，
 * 任何 app 走 AudioRecord 都只会拿到全零。真实麦克风是 PDM 数字麦，
 * 只有 root 通过 tinycap 打开 pcm3c 才拿得到，且只在 DSP（小爱）运行时供电。
 *
 * 中继约定：
 *   /sdcard/voice/seg_<序号>.wav   16kHz / 16bit / 立体声，声道 1 是麦克风
 * 读取方式：跟最新的分段文件尾部（从"当前时刻"开始），文件增长就读新增部分，
 * 分段轮换时自动切到下一段，全程只输出单声道 16k PCM。
 */
public class RootRelaySource implements PcmSource {

    private static final String TAG = "RootRelay";
    public static final File DIR = new File("/sdcard/voice");
    private static final int HEADER = 44;      // WAV 头
    private static final int FRAME = 4;        // 2 声道 × 16bit
    private static final int MIC_OFFSET = 2;   // 声道 1（右）是麦克风

    private FileInputStream in;
    private File current;
    private byte[] carry = new byte[0];

    /** 中继目录里是否已经有可用的分段文件。 */
    public static boolean available() {
        return newest() != null;
    }

    /** 返回中继分段的目录，供诊断界面显示。 */
    public static int segmentCount() {
        File[] fs = list();
        return fs == null ? 0 : fs.length;
    }

    public static long newestSize() {
        File f = newest();
        return f == null ? 0 : f.length();
    }

    private static File[] list() {
        File[] fs = DIR.listFiles((d, name) -> name.startsWith("seg_") && name.endsWith(".wav"));
        return fs;
    }

    private static long indexOf(File f) {
        String n = f.getName();
        try {
            return Long.parseLong(n.substring(4, n.length() - 4));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static File newest() {
        File[] fs = list();
        if (fs == null || fs.length == 0) return null;
        File best = null;
        long bi = -1;
        for (File f : fs) {
            long i = indexOf(f);
            if (i > bi && f.length() > 0) { bi = i; best = f; }
        }
        return best;
    }

    /** 打开最新分段并定位到文件末尾：只识别"按下麦克风之后"说的话。 */
    public boolean open() {
        File f = newest();
        if (f == null) {
            Log.w(TAG, "no segment under " + DIR);
            return false;
        }
        try {
            in = new FileInputStream(f);
            current = f;
            long len = f.length();
            long skipped = 0;
            while (skipped < len) {
                long s = in.skip(len - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            Log.i(TAG, "opened " + f.getName() + " from offset " + skipped);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "open failed: " + t, t);
            close();
            return false;
        }
    }

    private void switchTo(File f) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) { }
        try {
            in = new FileInputStream(f);
            current = f;
            carry = new byte[0];
            long skipped = 0;
            while (skipped < HEADER) {
                long s = in.skip(HEADER - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            Log.i(TAG, "switched to " + f.getName());
        } catch (Throwable t) {
            Log.e(TAG, "switch failed: " + t, t);
            in = null;
            current = null;
        }
    }

    @Override
    public int read(byte[] out, int maxBytes) {
        long deadline = System.currentTimeMillis() + 500;
        int maxFrames = Math.max(1, maxBytes / 2);

        while (System.currentTimeMillis() < deadline) {
            if (in == null && !open()) {
                sleep(60);
                continue;
            }

            byte[] raw = new byte[Math.min(maxFrames * FRAME + FRAME, 16384)];
            int n;
            try {
                n = in.read(raw);
            } catch (IOException e) {
                Log.w(TAG, "read failed: " + e);
                n = -1;
            }

            if (n > 0) {
                byte[] data;
                if (carry.length > 0) {
                    data = new byte[carry.length + n];
                    System.arraycopy(carry, 0, data, 0, carry.length);
                    System.arraycopy(raw, 0, data, carry.length, n);
                } else {
                    data = Arrays.copyOf(raw, n);
                }

                int frames = data.length / FRAME;
                int use = Math.min(frames, maxFrames);
                for (int i = 0; i < use; i++) {
                    int p = i * FRAME + MIC_OFFSET;
                    out[i * 2] = data[p];
                    out[i * 2 + 1] = data[p + 1];
                }
                int usedBytes = use * FRAME;
                carry = usedBytes < data.length
                        ? Arrays.copyOfRange(data, usedBytes, data.length)
                        : new byte[0];
                if (use > 0) return use * 2;
                continue;
            }

            // 当前分段暂时没有新数据：看是否有更新的分段
            File nf = newest();
            if (nf != null && current != null && indexOf(nf) > indexOf(current)) {
                switchTo(nf);
                continue;
            }
            sleep(20);
        }
        return 0;
    }

    @Override
    public void close() {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) { }
        in = null;
        current = null;
        carry = new byte[0];
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }
}
