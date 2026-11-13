package com.local.voiceinput;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 语音录入界面。
 *
 * SmartTube（以及大多数传统应用）的麦克风按钮并不绑定 SpeechRecognizer，而是
 * startActivityForResult 一个 action = android.speech.action.RECOGNIZE_SPEECH 的界面，
 * 期望该界面把识别结果放进 EXTRA_RESULTS 回来。原厂固件里没有这类界面（没装
 * Google 语音搜索），点麦克风自然毫无反应。
 *
 * 本 Activity 就是补上这个缺口：显示录入界面 -> 走系统 SpeechRecognizer
 * （即本应用的 LocalRecognitionService，音频来自 root 中继的 PDM 麦克风）
 * -> setResult(EXTRA_RESULTS) -> finish。
 */
public class VoiceSearchActivity extends Activity {

    private static final String TAG = "VoskSearch";
    private static final int MAX_RETRY = 1;          // 没听清时自动重试次数
    private static final long RETRY_DELAY_MS = 500L;

    private TextView titleView;
    private TextView bodyView;
    private TextView hintView;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private String lang = "zh-CN";
    private int attempt;
    private boolean finished;
    private String lastPartial = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        if (it != null) {
            String l = it.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
            if (l != null && !l.isEmpty()) lang = l;
            String prompt = it.getStringExtra(RecognizerIntent.EXTRA_PROMPT);
            if (prompt == null || prompt.isEmpty()) prompt = "请说出要搜索的内容";
            lastPartial = prompt;
        }
        Log.i(TAG, "started from " + (it == null ? "?" : it.getAction()) + " lang=" + lang);

        buildUi();
        main.postDelayed(this::startListening, 250);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (48 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        titleView = new TextView(this);
        titleView.setText("正在聆听…");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView);

        bodyView = new TextView(this);
        bodyView.setText("请对着电视说出搜索内容");
        bodyView.setTextColor(0xFFAAAAAA);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(bodyView);

        hintView = new TextView(this);
        hintView.setText("说完停顿一下会自动结束 · 按返回键取消");
        hintView.setTextColor(0xFF666666);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hintView.setGravity(Gravity.CENTER);
        root.addView(hintView);

        addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            main.post(() -> {
                titleView.setText("正在聆听…");
                bodyView.setText(lastPartial);
            });
        }

        @Override
        public void onBeginningOfSpeech() {
            main.post(() -> titleView.setText("听到了，请继续说…"));
        }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() {
            main.post(() -> titleView.setText("正在识别…"));
        }

        @Override
        public void onError(int error) {
            Log.w(TAG, "recognizer error=" + error + " attempt=" + attempt);
            if (isRetryable(error) && attempt < MAX_RETRY) {
                attempt++;
                main.post(() -> {
                    titleView.setText("没听清，请再说一次…");
                    bodyView.setText("");
                    main.postDelayed(VoiceSearchActivity.this::startListening, RETRY_DELAY_MS);
                });
                return;
            }
            main.post(() -> showFailure(error));
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> list = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
                main.post(() -> showFailure(SpeechRecognizer.ERROR_NO_MATCH));
                return;
            }
            deliver(list);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults == null ? null
                    : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return;
            String text = list.get(0);
            lastPartial = text;
            main.post(() -> {
                titleView.setText("听到：");
                bodyView.setText(text);
            });
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    };

    private static boolean isRetryable(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
    }

    private void startListening() {
        if (finished) return;

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            titleView.setText("没有可用的语音识别服务");
            bodyView.setText("请确认 com.local.voiceinput 已安装并设为默认识别服务");
            hintView.setText("按返回键关闭");
            return;
        }

        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            recognizer.startListening(i);
            Log.i(TAG, "startListening lang=" + lang);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            showFailure(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    private void deliver(ArrayList<String> list) {
        if (finished) return;
        finished = true;
        Intent out = new Intent();
        out.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, list);
        setResult(RESULT_OK, out);
        Log.i(TAG, "deliver: " + list.get(0));
        main.post(() -> {
            titleView.setText("识别结果：");
            bodyView.setText(list.get(0));
            hintView.setText("");
        });
        main.postDelayed(this::finish, 300);
    }

    private void showFailure(int error) {
        if (finished) return;
        finished = true;
        String msg;
        if (error == SpeechRecognizer.ERROR_NO_MATCH) {
            msg = "没有识别出内容，请再说一次";
        } else if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            msg = "没听到声音（采集可能未启动）";
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            msg = "缺少录音权限";
        } else if (error == SpeechRecognizer.ERROR_AUDIO) {
            msg = "音频采集出错";
        } else {
            msg = "识别失败（" + error + "）";
        }
        Log.w(TAG, "failure: " + msg);
        titleView.setText(msg);
        bodyView.setText("");
        hintView.setText("按返回键关闭");
        destroyRecognizer();
        setResult(RESULT_CANCELED);
        main.postDelayed(this::finish, 2200);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelByUser();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void cancelByUser() {
        Log.i(TAG, "cancel by user");
        finished = true;
        destroyRecognizer();
        setResult(RESULT_CANCELED);
        finish();
    }

    private void destroyRecognizer() {
        SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r == null) return;
        try { r.cancel(); } catch (Throwable ignored) { }
        try { r.destroy(); } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!finished) {
            // 被别的界面压住时别再占用音频通道
            destroyRecognizer();
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }
}
