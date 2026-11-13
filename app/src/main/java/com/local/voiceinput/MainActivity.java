package com.local.voiceinput;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** 诊断界面：确认权限、模型、以及系统是否能发现本服务。 */
public class MainActivity extends Activity {

    private static final String TAG = "MainDiag";
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_VOICE = 1002;

    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean modelReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("本地语音识别服务");
        title.setTextSize(22f);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        Button grant = new Button(this);
        grant.setText("1. 授予麦克风权限");
        grant.setOnClickListener(v -> requestAudioPermission());
        root.addView(grant);

        Button prep = new Button(this);
        prep.setText("2. 解压识别模型");
        prep.setOnClickListener(v -> prepareModel());
        root.addView(prep);

        Button test = new Button(this);
        test.setText("3. 自检：录 5 秒并识别");
        test.setOnClickListener(v -> selfTest());
        root.addView(test);

        Button voice = new Button(this);
        voice.setText("4. 打开语音录入界面（等同 SmartTube 点麦克风）");
        voice.setOnClickListener(v -> launchVoiceSearch());
        root.addView(voice);

        setContentView(root);

        refreshStatus();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission();
        }
    }

    private void requestAudioPermission() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (need.isEmpty()) {
            refreshStatus();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshStatus();
    }

    private void prepareModel() {
        status.setText("正在解压模型…");
        new Thread(() -> {
            final File dir = ModelInstaller.ensureModel(this);
            modelReady = dir != null;
            main.post(this::refreshStatus);
        }).start();
    }

    private void selfTest() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("系统仍未发现识别服务，请先确认已安装并设置了默认识别服务");
            return;
        }
        status.setText("开始录音，请说话…");
        SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                main.post(() -> status.setText("正在听…请说一句话"));
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                main.post(() -> status.setText("识别失败，错误码：" + error
                        + "\n（6=一直没听到声音，7=听到但没识别出，3=音频错误，9=无权限）"));
                sr.destroy();
            }
            @Override public void onResults(Bundle results) {
                java.util.ArrayList<String> list =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = (list == null || list.isEmpty()) ? "" : list.get(0);
                main.post(() -> status.setText("识别成功：" + text));
                sr.destroy();
            }
            @Override public void onPartialResults(Bundle partialResults) {
                java.util.ArrayList<String> list =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    main.post(() -> status.setText("听到：" + list.get(0)));
                }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        android.content.Intent i = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        sr.startListening(i);
    }

    /** 模拟 SmartTube：走 android.speech.action.RECOGNIZE_SPEECH 这一路。 */
    private void launchVoiceSearch() {
        android.content.Intent i = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说出要搜索的内容");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            status.setText("没有界面应答 RECOGNIZE_SPEECH（本机异常）");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_VOICE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> list =
                    data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            String text = (list == null || list.isEmpty()) ? "" : list.get(0);
            status.setText("语音录入返回：" + text);
        } else {
            status.setText("语音录入被取消或失败");
        }
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();

        boolean perm = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        sb.append("麦克风权限：").append(perm ? "已授予" : "未授予").append('\n');

        File dir = new File(getFilesDir(), "vosk-model");
        modelReady = new File(dir, ".extracted").exists();
        sb.append("识别模型：").append(modelReady ? "就绪" : "未解压").append('\n');

        boolean available = SpeechRecognizer.isRecognitionAvailable(this);
        sb.append("系统可发现本服务：").append(available ? "是" : "否").append('\n');

        int handlers = getPackageManager().queryIntentActivities(
                new android.content.Intent(
                        android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).size();
        sb.append("应答 RECOGNIZE_SPEECH 的界面：").append(handlers).append(" 个")
                .append(handlers > 0 ? "（点麦克风会有录入界面）" : "（点麦克风不会有反应）")
                .append('\n');

        int segs = RootRelaySource.segmentCount();
        long size = RootRelaySource.newestSize();
        boolean relay = RootRelaySource.available();
        sb.append("root 中继：").append(relay ? "有数据" : "无（未启动采集）")
                .append("  段数=").append(segs)
                .append("  最新=").append(size / 1024).append("KB").append('\n');
        sb.append("录音来源：").append(relay ? "root 中继（PDM 麦克风）" : "系统 AudioRecord")
                .append('\n');

        sb.append("服务类：").append(LocalRecognitionService.class.getName());
        status.setText(sb.toString());

        Log.i(TAG, sb.toString().replace('\n', ' '));
    }
}
