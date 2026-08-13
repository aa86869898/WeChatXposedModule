package com.leshao.v3.ai;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class AiFeature {
    public enum Action { POLISH, EMOTION, KEYWORD, CUSTOM }

    public static void process(Activity act, Action action, String extra) {
        if (!AiConfig.masterEnabled()) return;
        String input = getInputText(act);
        if (input == null || input.trim().isEmpty()) {
            Toast.makeText(act, "输入框为空", Toast.LENGTH_SHORT).show();
            return;
        }
        switch (action) {
            case POLISH:
                if (!AiConfig.polishEnabled()) { Toast.makeText(act, "润色功能未开启", Toast.LENGTH_SHORT).show(); return; }
                run(act, String.format(AiConfig.promptPolish(), extra == null ? "自然" : extra) + input, true);
                break;
            case EMOTION:
                if (!AiConfig.emotionEnabled()) { Toast.makeText(act, "情绪分析未开启", Toast.LENGTH_SHORT).show(); return; }
                run(act, AiConfig.promptEmotion() + input, false);
                break;
            case KEYWORD:
                if (!AiConfig.keywordEnabled()) { Toast.makeText(act, "关键词提取未开启", Toast.LENGTH_SHORT).show(); return; }
                run(act, AiConfig.promptKeyword() + input, false);
                break;
            case CUSTOM:
                if (!AiConfig.customPromptEnabled() || extra == null) {
                    Toast.makeText(act, "自定义 Prompt 未配置", Toast.LENGTH_SHORT).show();
                    return;
                }
                run(act, extra + "\n" + input, false);
                break;
        }
    }

    private static void run(Activity act, String prompt, boolean replaceInput) {
        Toast.makeText(act, "AI 处理中…", Toast.LENGTH_SHORT).show();
        List<AiClient.ChatMessage> req = new ArrayList<>();
        req.add(new AiClient.ChatMessage("user", prompt));
        AiClient.chatAsync(null, req, new AiClient.Callback() {
            @Override public void onResult(String text) {
                ChatHooks.MAIN.post(() -> {
                    if (replaceInput) ChatHooks.fillInput(act, text);
                    else showResultDialog(act, text);
                });
            }
            @Override public void onError(String msg) {
                ChatHooks.MAIN.post(() -> Toast.makeText(act, "失败: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void showResultDialog(Activity act, String text) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
        b.setTitle("AI 结果");
        b.setMessage(text);
        b.setPositiveButton("复制", (d, w) -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ai", text));
            Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    private static String getInputText(Activity act) {
        try {
            String txt = ChatHooks.readInputText(act);
            if (txt == null) {
                com.leshao.v3.LogWriter.log("AiFeature", "getInputText: 未找到输入框");
                return null;
            }
            com.leshao.v3.LogWriter.log("AiFeature", "getInputText: 长度=" + txt.length());
            return txt;
        } catch (Throwable t) { return null; }
    }
}
