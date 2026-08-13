package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.model.LabelInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在微信聊天列表页注入分组标签按钮行（标题栏下方、聊天列表上方）
 * 策略：找到 android.R.id.content 的唯一子 View（WeChat主布局的LinearLayout），
 * 把标签栏作为第二个子View插入（标题栏之后、聊天列表之前）
 */
public class ChatGroupTabBar {

    private static final String TAG = "ChatGroupTabBar";
    private static final String TAB_BAR_TAG = "LS_GROUP_TABS";

    public static void hook(ClassLoader cl) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl);

            XposedBridge.hookAllMethods(launcherUI, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            log("onResume 触发");
                            Activity act = (Activity) param.thisObject;
                            injectTabBar(act, cl);
                        } catch (Throwable e) {
                            log("injectTabBar error: " + e.getMessage());
                        }
                    }
                });
            log("LauncherUI.onResume Hook 已安装");
        } catch (Throwable e) {
            log("hook 失败: " + e.getMessage());
        }
    }

    private static void injectTabBar(Activity act, ClassLoader cl) {
        try {
            ViewGroup content = act.findViewById(android.R.id.content);
            if (content == null) { log("content is null"); return; }
            if (content.getChildCount() == 0) { log("content 无子View"); return; }

            View mainLayout = content.getChildAt(0);
            if (!(mainLayout instanceof ViewGroup)) { log("mainLayout 不是 ViewGroup"); return; }
            ViewGroup root = (ViewGroup) mainLayout;

            View existing = root.findViewWithTag(TAB_BAR_TAG);
            if (existing != null) root.removeView(existing);

            List<LabelInfo> labels = ChatGroupHook.getAllLabels();
            if (labels.isEmpty()) { log("没有标签"); return; }

            boolean darkMode = isDarkMode(act);
            int dp8 = dp(act, 8);
            int dp10 = dp(act, 10);
            int btnH = dp(act, 32);

            LinearLayout tabBar = new LinearLayout(act);
            tabBar.setTag(TAB_BAR_TAG);
            tabBar.setOrientation(LinearLayout.HORIZONTAL);
            tabBar.setGravity(Gravity.CENTER_VERTICAL);
            tabBar.setPadding(dp(act, 6), dp(act, 2), dp(act, 6), dp(act, 2));
            tabBar.setBackgroundColor(darkMode ? 0xFF1A1A1A : 0xFFEDEDED);

            for (LabelInfo label : labels) {
                TextView btn = new TextView(act);
                btn.setText(label.labelName + " " + label.contacts.size());
                btn.setTextSize(12);
                btn.setTextColor(darkMode ? 0xFFB0B0B0 : 0xFF333333);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(dp8, 0, dp8, 0);
                btn.setClickable(true);

                int bgColor = darkMode ? 0xFF2C2C2C : 0xFFFFFFFF;
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(bgColor);
                bg.setCornerRadius(dp(act, 6));
                btn.setBackground(bg);

                btn.setOnClickListener(v -> showLabelDialog(act, label));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, btnH
                );
                lp.setMargins(0, 0, dp10, 0);
                btn.setLayoutParams(lp);
                tabBar.addView(btn);
            }

            root.addView(tabBar, 1);
            log("标签栏已注入: " + labels.size() + " tags, root=" + root.getClass().getSimpleName() + " children=" + root.getChildCount());
        } catch (Throwable e) {
            log("injectTabBar inner: " + e.getMessage());
        }
    }

    private static void showLabelDialog(Activity act, LabelInfo label) {
        List<String> contacts = ChatGroupHook.getContactsByLabelId(label.labelId);
        StringBuilder sb = new StringBuilder("共 " + contacts.size() + " 人");
        int max = Math.min(contacts.size(), 8);
        for (int i = 0; i < max; i++) sb.append("\n").append(contacts.get(i));
        if (contacts.size() > 8) sb.append("\n...");

        new AlertDialog.Builder(act)
            .setTitle(label.labelName)
            .setMessage(sb.toString())
            .setPositiveButton("管理分组", (d, w) -> {
                try {
                    android.content.Intent intent = new android.content.Intent(act,
                        com.leshao.v3.ui.SubPageActivity.class);
                    intent.putExtra("title", "聊天分组管理");
                    intent.putExtra("pageId", 14);
                    act.startActivity(intent);
                } catch (Throwable e) {
                    log("启动SubPageActivity失败: " + e.getMessage());
                    showRenameDialog(act, label);
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private static void showRenameDialog(Activity act, LabelInfo label) {
        final android.widget.EditText input = new android.widget.EditText(act);
        input.setText(label.labelName);
        new AlertDialog.Builder(act)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定", (d, w) -> {
                String n = input.getText().toString().trim();
                if (!n.isEmpty()) ChatGroupHook.renameLabel(String.valueOf(label.labelId), n);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public static void refresh(Activity act, ClassLoader cl) {
        if (act == null) return;
        injectTabBar(act, cl);
    }

    private static boolean isDarkMode(Context ctx) {
        try {
            return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {}
        return false;
    }

    private static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void log(String msg) {
        LogWriter.log(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
