package com.local.voiceinput;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 把 APK 内置的 Vosk 模型解压到私有目录（Vosk 需要真实文件路径，不能用 assets 流）。 */
public final class ModelInstaller {

    private static final String TAG = "ModelInstaller";
    private static final String ASSET_DIR = "model";
    private static final String MARKER = ".extracted";

    private ModelInstaller() { }

    public static synchronized File ensureModel(Context ctx) {
        File root = new File(ctx.getFilesDir(), "vosk-model");
        File marker = new File(root, MARKER);
        if (marker.exists()) {
            return root;
        }
        try {
            if (root.exists()) deleteTree(root);
            if (!root.mkdirs()) { /* 已存在则忽略 */ }
            long t0 = System.currentTimeMillis();
            copyAssetDir(ctx.getAssets(), ASSET_DIR, root);
            marker.createNewFile();
            Log.i(TAG, "model extracted in " + (System.currentTimeMillis() - t0) + "ms -> " + root);
            return root;
        } catch (Throwable t) {
            Log.e(TAG, "extract failed", t);
            return null;
        }
    }

    private static void copyAssetDir(AssetManager am, String assetPath, File dest) throws IOException {
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(am, assetPath, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("mkdirs failed: " + dest);
        }
        for (String child : children) {
            copyAssetDir(am, assetPath + "/" + child, new File(dest, child));
        }
    }

    private static void copyFile(AssetManager am, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
