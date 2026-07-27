package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.ActivationManager;

public class SubPageActivity {

    private static AlertDialog sSubDialog;
    private static Activity sParentAct;
    private static String sTitle;
    private static int sPageId;
    private static int sThemeFeaturePageId = 0;
    private static final java.util.Stack<Integer> sNavStack = new java.util.Stack<>();

    private static boolean sIsThemeSubPage;

    public static void setThemeFeaturePageId(int id) {
        sThemeFeaturePageId = id;
    }

    public static void open(Activity parentAct, String title, int pageId) {
        openInternal(parentAct, title, pageId, true);
    }

    public static void openFromMain(Activity parentAct, String title, int pageId) {
        sNavStack.clear();
        openInternal(parentAct, title, pageId, false);
    }

    private static void openInternal(Activity parentAct, String title, int pageId, boolean pushCurrent) {
        if (pushCurrent && sPageId != 0) sNavStack.push(sPageId);
        sParentAct = parentAct;
        sTitle = title;
        sPageId = pageId;
        sIsThemeSubPage = false;
        show(parentAct, title, pageId);
    }

    public static void reloadThemePage() {
        if (sParentAct != null) {
            dismissSub();
            show(sParentAct, sTitle, sPageId);
        }
    }

    private static void show(Activity parentAct, String title, int pageId) {
        MainActivity.dismissDialog();
        dismissSub();

        float d = parentAct.getResources().getDisplayMetrics().density;
        Context ctx = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        root.addView(MainActivity.makeTitleBar(ctx, title, true, () -> goBack(parentAct)));

        View body = createPageBody(ctx, parentAct, pageId);
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(true);
        android.widget.LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        sv.setLayoutParams(svLp);
        sv.addView(body);
        root.addView(sv);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setView(root);
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        sSubDialog = dlg;

        // 系统返回键或点击对话框外关闭时，回到主页面
        dlg.setOnCancelListener(dialog -> goBack(parentAct));
        dlg.setOnDismissListener(dialog -> {
            if (sSubDialog == dlg) sSubDialog = null;
        });

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.90),
                        (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.82));
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    private static void goBack(Activity parentAct) {
        dismissSub();
        if (!sNavStack.isEmpty()) {
            int prevPageId = sNavStack.pop();
            openInternal(parentAct, "返回", prevPageId, false);
        } else {
            MainActivity.open(parentAct);
        }
    }

    private static void dismissSub() {
        if (sSubDialog != null && sSubDialog.isShowing()) {
            try { sSubDialog.dismiss(); } catch (Throwable ignored) {}
        }
        sSubDialog = null;
    }

    private static View createPageBody(Context ctx, Activity parentAct, int pageId) {
        // 激活门控: 功能页面必须先激活
        // 排除: 个人中心(99)、管理员工具(98)、主题子页(20)
        if (pageId != 98 && pageId != 99 && pageId != 20) {
            String wxid = ModuleConfig.getCurrentWxid();
            if (wxid == null) wxid = MainActivity.getUserWxid();
            boolean isAdmin = !ActivationManager.isTestMode() && wxid != null && ActivationManager.isAdmin(wxid);
            if (!isAdmin && !ActivationManager.isActivated()) {
                return createActivationGate(ctx, parentAct);
            }

            // 已激活但无对应功能权限：弹窗提示
            if (!isAdmin && !ActivationManager.isFeaturePageAllowed(pageId)) {
                return createPermissionDenied(ctx, parentAct);
            }
        }

        switch (pageId) {
            case 1:  // 聊天功能
                return ChatPageView.create(ctx, parentAct);
            case 2:  // 主题美化
                return ThemePageView.create(ctx, parentAct);
            case 3:  // 联系人和群聊
                return ContactGroupPageView.create(ctx, parentAct);
            case 6:  // 定时消息助手
                return SchedulerPageView.create(ctx, parentAct);
            case 8:  // TTS语音播报
                return TTSPageView.create(ctx, parentAct);
            case 9:  // 红包转账
                return RedPacketPageView.create(ctx, parentAct);
            case 10: // 朋友圈增强
                return SnsPageView.create(ctx, parentAct);
            case 11: // 隐私安全
                return PrivacyPageView.create(ctx, parentAct);
            case 12: // 数据备份
                return DataToolsPageView.create(ctx, parentAct);
            case 13: // 通讯录更新日志
                return ContactChangeLogPageView.create(ctx, parentAct);
            case 14: // 定时消息群发
                return ScheduleMsgPageView.create(ctx, parentAct);
            case 91: // 红包转账 > 自动秒抢红包
                return RedPacketConfigView.create(ctx, parentAct);
            case 92: // 红包转账 > 自动收款
                return AutoCollectConfigView.create(ctx, parentAct);
            case 99: // 个人中心
                return ProfilePageView.create(ctx, parentAct);
            case 98: // 管理员工具
                return AdminPageView.create(ctx, parentAct);
            case 20: // 主题美化 > 具体功能配置
                return ThemePageView.createFeatureConfigPage(ctx, parentAct, sThemeFeaturePageId);
            default:
                return makePlaceholder(ctx, parentAct);
        }
    }

    private static View createActivationGate(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding((int)(20 * d), (int)(60 * d), (int)(20 * d), (int)(20 * d));

        TextView lock = new TextView(ctx);
        lock.setText(new String(Character.toChars(0x1F512)));
        lock.setTextSize(52);
        lock.setGravity(Gravity.CENTER);
        body.addView(lock);

        TextView title = new TextView(ctx);
        title.setText("请先激活模块");
        title.setTextSize(17);
        title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, (int)(16 * d), 0, (int)(6 * d));
        body.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText("输入激活码以解锁全部功能");
        hint.setTextSize(13);
        hint.setTextColor(AppColors.text2());
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, (int)(24 * d));
        body.addView(hint);

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setBackgroundColor(AppColors.whiteCard());
        inputRow.setPadding((int)(14 * d), (int)(10 * d), (int)(14 * d), (int)(10 * d));

        EditText codeInput = new EditText(ctx);
        codeInput.setHint("请输入激活码 (LS-开头)");
        codeInput.setTextSize(14);
        codeInput.setTextColor(AppColors.text1());
        codeInput.setHintTextColor(AppColors.text2());
        codeInput.setSingleLine(true);
        codeInput.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        codeInput.setBackgroundColor(AppColors.card());
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        inputLp.setMargins(0, 0, (int)(10 * d), 0);
        codeInput.setLayoutParams(inputLp);
        inputRow.addView(codeInput);

        TextView btn = new TextView(ctx);
        btn.setText("验证激活");
        btn.setTextSize(13);
        btn.setTextColor(AppColors.whiteCard());
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding((int)(16 * d), (int)(10 * d), (int)(16 * d), (int)(10 * d));

        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setCornerRadius((int)(6 * d));
        btnBg.setColor(AppColors.accent());
        btn.setBackground(btnBg);

        btn.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(ctx, "请输入激活码", Toast.LENGTH_SHORT).show();
                return;
            }
            String wxid = MainActivity.getUserWxid();
            if (wxid == null || wxid.isEmpty()) {
                Toast.makeText(ctx, "无法获取微信ID", Toast.LENGTH_SHORT).show();
                return;
            }
            ActivationManager.ValidationResult result = ActivationManager.validate(code, wxid);
            if (result.valid) {
                ContextManager.getPrefs().edit()
                    .putString("ls_act_time", String.valueOf(System.currentTimeMillis()))
                    .commit();
                ActivationManager.saveActivation(ctx, code, wxid,
                    result.levelIndex, result.expireHours, result.featureMask);
                Toast.makeText(ctx, "激活成功: " + result.levelName, Toast.LENGTH_SHORT).show();
                SubPageActivity.open(parentAct, sTitle, sPageId);
            } else {
                Toast.makeText(ctx, "激活码无效或不匹配当前微信", Toast.LENGTH_SHORT).show();
            }
        });
        inputRow.addView(btn);

        body.addView(inputRow);

        return body;
    }

    private static View createPermissionDenied(Context ctx, Activity parentAct) {
        float d = parentAct.getResources().getDisplayMetrics().density;

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding((int)(20 * d), (int)(60 * d), (int)(20 * d), (int)(60 * d));

        TextView icon = new TextView(ctx);
        icon.setText("\uD83D\uDD12");
        icon.setTextSize(48);
        icon.setGravity(Gravity.CENTER);
        body.addView(icon);

        TextView title = new TextView(ctx);
        title.setText("无使用权限");
        title.setTextSize(18);
        title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, (int)(16 * d), 0, (int)(8 * d));
        body.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText("当前激活码未授权此功能\n请联系管理员升级授权范围");
        hint.setTextSize(13);
        hint.setTextColor(AppColors.text2());
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, (int)(16 * d));
        body.addView(hint);

        return body;
    }

    private static View makePlaceholder(Context ctx, Activity parentAct) {
        float d = parentAct.getResources().getDisplayMetrics().density;

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding((int)(20 * d), (int)(40 * d), (int)(20 * d), (int)(40 * d));

        TextView placeholder = new TextView(ctx);
        placeholder.setText("功能开发中...");
        placeholder.setTextSize(15);
        placeholder.setTextColor(AppColors.text2());
        placeholder.setGravity(Gravity.CENTER);
        body.addView(placeholder);

        return body;
    }
}
