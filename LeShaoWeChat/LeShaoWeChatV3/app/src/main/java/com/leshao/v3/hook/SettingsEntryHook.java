package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.ContactPickerFragment;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.model.Contact;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.ScheduledTask;

import java.util.LinkedHashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SettingsEntryHook {

    private static final String TAG = "SettingsEntryHook";

    // ===== V21 ThemeEngine exact colors (light mode) =====
    private static final int CLR_BG       = 0xFFF4F0FF;
    private static final int CLR_CARD     = 0xB8FFFFFF;
    private static final int CLR_BORDER   = 0xFFE0D0F0;
    private static final int CLR_ACCENT   = 0xFFFF4298;
    private static final int CLR_ACCENT2  = 0xFFB848E0;
    private static final int CLR_TEXT     = 0xFF281838;
    private static final int CLR_TEXT2    = 0xFF786890;
    private static final int CLR_GREEN    = 0xFF00C088;
    private static final int CLR_RED      = 0xFFFF3860;
    private static final int CLR_SW_TRK   = 0xFFE8D8F0;
    private static final int CLR_SW_THM   = 0xFFC0A0D8;
    private static final int CLR_BTN_BD   = 0xFFD0B8E8;
    private static final int CLR_WHITE    = 0xFFFFFFFF;
    private static final int CLR_DIV      = 0xFFE8DCF0;

    private static final int[] CANDY_COLORS = {
        0xFFFFE8F4, 0xFFFCD4EE, 0xFFF0D4FF, 0xFFD4E6FF, 0xFFD8F4EE, 0xFFFFE4F4
    };

    private static boolean backPressHooked = false;
    private static Activity panelActivity;
    private static AlertDialog panelDialog;

    private static volatile boolean hooksRegistered = false;
    private static final int SETTINGS_LAYOUT_ID = 2131497752;
    private static Handler sHandler;

    public SettingsEntryHook() {}

    public static void hook(ClassLoader wechatCL) {
        if (!hooksRegistered) {
            hooksRegistered = true;
            XposedBridge.log("LeShaoV3: EntryHook.hook() called");
            sHandler = new Handler(Looper.getMainLooper());
            hookBackPressed();
            hookLayoutInflater();
            LogWriter.log(TAG, "INIT: hooks registered ok");
        }
    }

    // ========== V21 ThemeEngine drawable factories ==========

    private static GradientDrawable createGlassBg(Context ctx, int rad) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setOrientation(GradientDrawable.Orientation.TL_BR);
        gd.setColors(CANDY_COLORS);
        gd.setCornerRadius(dpf(d, rad));
        gd.setStroke((int)(1*d), CLR_BORDER);
        return gd;
    }

    private static GradientDrawable createPrimaryBtnBg(Context ctx, int rad) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CLR_ACCENT2, CLR_ACCENT});
        gd.setCornerRadius(dpf(d, rad));
        return gd;
    }

    private static GradientDrawable createOutlineBtnBg(Context ctx, int rad) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dpf(d, rad));
        gd.setColor(Color.TRANSPARENT);
        gd.setStroke((int)(2*d), CLR_BORDER);
        return gd;
    }

    private static GradientDrawable createDangerBtnBg(Context ctx, int rad) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CLR_RED, 0xCC000000 | (CLR_RED & 0x00FFFFFF)});
        gd.setCornerRadius(dpf(d, rad));
        return gd;
    }

    private static GradientDrawable createInputBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x18000000 | (CLR_TEXT & 0x00FFFFFF));
        gd.setCornerRadius(dpf(d, 12));
        gd.setStroke((int)(1*d), CLR_BORDER);
        return gd;
    }

    private static GradientDrawable createCardBg(Context ctx, int rad) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CLR_CARD);
        gd.setCornerRadius(dpf(d, rad));
        gd.setStroke((int)(1*d), CLR_BORDER);
        return gd;
    }

    // ========== V21 ThemeEngine style methods ==========

    private static void styleSwitch(android.widget.Switch sw, boolean checked, Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable track = new GradientDrawable();
        track.setOrientation(GradientDrawable.Orientation.TL_BR);
        if (checked) {
            track.setColors(new int[]{CLR_ACCENT, CLR_ACCENT2, CLR_ACCENT, CLR_ACCENT2, CLR_ACCENT});
            track.setStroke((int)(2*d), CLR_ACCENT);
        } else {
            track.setColor(CLR_SW_TRK);
            track.setStroke((int)(2*d), CLR_BORDER);
        }
        float rT = dpf(d, 10), rB = dpf(d, 18);
        try { track.setCornerRadii(new float[]{rT, rT, rT, rT, rB, rB, rB, rB}); }
        catch (Throwable e) { track.setCornerRadius(dpf(d, 14)); }
        sw.setTrackDrawable(track);
        sw.setThumbTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{CLR_ACCENT, CLR_SW_THM}));
    }

    private static void styleInput(EditText et) {
        if (et == null) return;
        Context ctx = et.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        et.setHintTextColor(CLR_TEXT2);
        et.setTextColor(CLR_TEXT);
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setBackgroundDrawable(createInputBg(ctx));
        et.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
    }

    private static Button createBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setClickable(true);
        b.setFocusable(true);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private static void styleButton(Button btn) {
        if (btn == null) return;
        float d = btn.getContext().getResources().getDisplayMetrics().density;
        btn.setAllCaps(false);
        btn.setTextColor(CLR_WHITE);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CLR_ACCENT2, CLR_ACCENT});
        bg.setCornerRadius(dpf(d, 14));
        btn.setBackground(bg);
        btn.setPadding((int)(12*d), (int)(8*d), (int)(12*d), (int)(8*d));
    }

    // ========== Utility ==========

    private static float dpf(float density, int dp) { return density * dp; }

    private static int dpC(Context ctx, int dp) {
        return (int)(dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ========== LayoutInflater hook (V21-style entry card) ==========

    private static void hookLayoutInflater() {
        try {
            XposedHelpers.findAndHookMethod(
                android.view.LayoutInflater.class,
                "inflate", int.class, ViewGroup.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if ((int) param.args[0] != SETTINGS_LAYOUT_ID) return;
                        View original = (View) param.getResult();
                        if (original == null) return;
                        try {
                            ViewGroup parent = (ViewGroup) param.args[1];
                            Context ctx = parent != null ? parent.getContext() : original.getContext();
                            View card = buildSettingsCard(ctx);
                            LinearLayout wrapper = new LinearLayout(ctx);
                            wrapper.setOrientation(LinearLayout.VERTICAL);
                            wrapper.addView(card, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                            original.setPadding(original.getPaddingLeft(), 0,
                                    original.getPaddingRight(), original.getPaddingBottom());
                            wrapper.addView(original, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
                            param.setResult(wrapper);
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "CARD: wrap failed: " + e.getMessage());
                        }
                    }
                });
        } catch (Throwable t) {
            LogWriter.log(TAG, "CARD: inflate hook FAILED: " + t.getMessage());
        }
    }

    private static View buildSettingsCard(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        card.setBackground(createPrimaryBtnBg(ctx, 12));
        card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        View.OnClickListener listener = v -> openSettingsFromContext(ctx);
        card.setOnClickListener(listener);

        TextView title = new TextView(ctx);
        title.setText("乐少助手 V3");
        title.setTextSize(15);
        title.setTextColor(CLR_WHITE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.gravity = Gravity.CENTER_VERTICAL;
        title.setLayoutParams(tlp);
        card.addView(title);

        Button btn = createBtn(ctx, "进入");
        btn.setTextSize(12);
        btn.setTextColor(CLR_WHITE);
        btn.setBackground(createOutlineBtnBg(ctx, 8));
        btn.setPadding((int)(10*d), (int)(5*d), (int)(10*d), (int)(5*d));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins((int)(6*d), 0, 0, 0);
        btn.setLayoutParams(blp);
        btn.setOnClickListener(listener);
        card.addView(btn);

        return card;
    }

    private static void openSettingsFromContext(Context ctx) {
        try {
            Activity act = null;
            while (ctx != null) {
                if (ctx instanceof Activity) { act = (Activity) ctx; break; }
                if (ctx instanceof android.content.ContextWrapper) {
                    ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
                } else break;
            }
            if (act == null) return;
            showMainPanel(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "CARD: open failed: " + t.getMessage());
        }
    }

    // ========== Back press hook ==========

    private static void hookBackPressed() {
        if (backPressHooked) return;
        backPressHooked = true;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onBackPressed",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Activity act = (Activity) param.thisObject;
                            if (panelDialog != null && panelDialog.isShowing() && act == panelActivity) {
                                panelDialog.dismiss();
                                panelDialog = null;
                                panelActivity = null;
                                param.setResult(null);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookBackPressed FAILED: " + t.getMessage());
        }
    }

    // ===== Contact tab button helpers =====

    private static TextView makeContactTabBtn(Activity act, String text, boolean selected) {
        float d = act.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setAllCaps(false);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpC(act, 10), dpC(act, 5), dpC(act, 10), dpC(act, 5));
        tv.setTextColor(selected ? CLR_WHITE : CLR_TEXT);
        tv.setBackground(selected ? createPrimaryBtnBg(act, 8) : createOutlineBtnBg(act, 8));
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dpC(act, 4), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static void highlightTab(TextView selected, TextView... others) {
        float d = selected.getContext().getResources().getDisplayMetrics().density;
        selected.setBackground(createPrimaryBtnBg(selected.getContext(), 8));
        selected.setTextColor(CLR_WHITE);
        for (TextView other : others) {
            other.setBackground(createOutlineBtnBg(other.getContext(), 8));
            other.setTextColor(CLR_TEXT);
        }
    }

    // ================================================================
    // V21 ModuleUI.showMainPanel exact structure:
    // ScrollView > LinearLayout (GlassBg) > title, subtitle, content, buttons
    // ================================================================

    private static void showMainPanel(Activity act) {
        try {
            if (panelDialog != null && panelDialog.isShowing()) {
                panelDialog.dismiss();
            }
            panelDialog = null;
            panelActivity = act;

            Context ctx = act;
            float d = act.getResources().getDisplayMetrics().density;
            int p = dpC(ctx, 16);

            ScrollView sv = new ScrollView(ctx);
            sv.setFillViewport(true);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(p, p, p, p);
            root.setBackground(createGlassBg(ctx, 14));

            TextView title = new TextView(ctx);
            title.setText("乐少助手 V3");
            title.setTextSize(20);
            title.setTextColor(CLR_ACCENT);
            title.setTypeface(null, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dpC(ctx, 8), 0, dpC(ctx, 4));
            root.addView(title);

            TextView ver = new TextView(ctx);
            ver.setText("微信多功能增强模块");
            ver.setTextSize(11);
            ver.setTextColor(CLR_TEXT2);
            ver.setGravity(Gravity.CENTER);
            ver.setPadding(0, 0, 0, dpC(ctx, 10));
            root.addView(ver);

            buildAllContent(ctx, root);

            LinearLayout btns = new LinearLayout(ctx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER);
            btns.setPadding(0, dpC(ctx, 12), 0, dpC(ctx, 4));

            SharedPreferences prefs = ContextManager.getPrefs();
            final ModuleConfig cfg = ModuleConfig.load(prefs);

            Button btnSave = createBtn(ctx, "保存配置");
            styleButton(btnSave);
            btnSave.setOnClickListener(v -> { cfg.save(prefs); Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show(); });
            btns.addView(btnSave);
            btns.addView(spacerH(ctx, 8));

            Button btnStop = createBtn(ctx, "停止");
            styleButton(btnStop);
            btnStop.setBackground(createDangerBtnBg(ctx, 8));
            btnStop.setOnClickListener(v -> { panelDialog.dismiss(); panelDialog = null; });
            btns.addView(btnStop);
            btns.addView(spacerH(ctx, 8));

            Button btnSys = createBtn(ctx, "设置");
            styleButton(btnSys);
            btns.addView(btnSys);
            btns.addView(spacerH(ctx, 8));

            Button btnClose = createBtn(ctx, "关闭");
            styleButton(btnClose);
            btnClose.setOnClickListener(v -> { panelDialog.dismiss(); panelDialog = null; });
            btns.addView(btnClose);

            root.addView(btns);
            sv.addView(root);

            AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            b.setView(sv);
            b.setCancelable(true);
            final AlertDialog dlg = b.create();
            panelDialog = dlg;
            panelActivity = act;

            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.94),
                            (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.88));
                w.setGravity(Gravity.CENTER);
            }
            dlg.show();
            LogWriter.log(TAG, "PANEL: dialog opened");
        } catch (Throwable t) {
            LogWriter.log(TAG, "PANEL: error: " + t.getMessage());
        }
    }

    private static View spacerH(Context ctx, int wDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dpC(ctx, wDp), 0));
        return v;
    }

    // ================================================================
    // V21-style all content in one vertical layout (no tabs)
    // ================================================================

    private static void buildAllContent(Context ctx, LinearLayout root) {
        addSection(ctx, root, "常用功能");
        buildSwitchesSection(ctx, root);

        addSection(ctx, root, "联系人");
        buildContactSection(ctx, root);

        addSection(ctx, root, "播报设置");
        buildTtsSection(ctx, root);

        addSection(ctx, root, "叮咚助手");
        buildDingDongSection(ctx, root);

        addSection(ctx, root, "群管理");
        buildGroupGuardSection(ctx, root);

        addSection(ctx, root, "AI 助手");
        buildAISection(ctx, root);

        addSection(ctx, root, "定时任务");
        buildSchedulerSection(ctx, root);

        addSection(ctx, root, "数据统计");
        buildStatsSection(ctx, root);
    }

    // ================================================================
    // V21 ModuleUI helpers: addSection, sw, ed, btn
    // ================================================================

    private static void addSection(Context ctx, LinearLayout r, String t) {
        LinearLayout h = new LinearLayout(ctx);
        h.setPadding(dpC(ctx, 12), dpC(ctx, 6), dpC(ctx, 12), dpC(ctx, 6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dpC(ctx, 8));
        g.setColor(CLR_CARD);
        h.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dpC(ctx, 8);
        h.setLayoutParams(lp);
        TextView tv = new TextView(ctx);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTextColor(CLR_ACCENT);
        tv.setTypeface(null, Typeface.BOLD);
        h.addView(tv);
        r.addView(h);
    }

    private interface SwitchCB { void onChange(boolean v); }

    private static void sw(Context ctx, LinearLayout p, String l, boolean c, final SwitchCB cb) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dpC(ctx, 16), dpC(ctx, 12), dpC(ctx, 16), dpC(ctx, 12));
        TextView tv = new TextView(ctx);
        tv.setText(l);
        tv.setTextSize(12);
        tv.setTextColor(CLR_TEXT);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(tv);
        Switch s = new Switch(ctx);
        s.setChecked(c);
        styleSwitch(s, c, ctx);
        s.setOnCheckedChangeListener((btn, v) -> {
            styleSwitch(s, v, ctx);
            if (cb != null) cb.onChange(v);
        });
        r.addView(s);
        p.addView(r);
    }

    private interface EditCB { void onChange(String s); }

    private static void ed(Context ctx, LinearLayout p, String l, String v, final EditCB cb) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dpC(ctx, 2), 0, dpC(ctx, 2));
        TextView tv = new TextView(ctx);
        tv.setText(l + ": ");
        tv.setTextSize(10);
        tv.setTextColor(CLR_TEXT2);
        r.addView(tv);
        final EditText e = new EditText(ctx);
        e.setText(v != null ? v : "");
        styleInput(e);
        e.setTextSize(11);
        e.setLayoutParams(new LinearLayout.LayoutParams(0, dpC(ctx, 28), 1));
        e.setOnFocusChangeListener((vv, h) -> {
            if (!h && cb != null) cb.onChange(e.getText().toString());
        });
        r.addView(e);
        p.addView(r);
    }

    private static void btn(Context ctx, LinearLayout p, String l, String bt, final View.OnClickListener li) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dpC(ctx, 4), 0, dpC(ctx, 4));
        TextView tv = new TextView(ctx);
        tv.setText(l);
        tv.setTextSize(12);
        tv.setTextColor(CLR_TEXT);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(tv);
        if (bt != null && !bt.isEmpty()) {
            Button b = createBtn(ctx, bt);
            b.setTextSize(10);
            b.setTextColor(CLR_TEXT);
            b.setBackground(createOutlineBtnBg(ctx, 4));
            b.setPadding(dpC(ctx, 12), dpC(ctx, 2), dpC(ctx, 12), dpC(ctx, 2));
            if (li != null) b.setOnClickListener(li);
            r.addView(b);
        }
        p.addView(r);
    }

    private static View makeDivider(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dpC(ctx, 1)));
        v.setBackgroundColor(CLR_DIV);
        return v;
    }

    // ================================================================
    // Function sections (V3 logic, V21 UI patterns)
    // ================================================================

    private static void buildSwitchesSection(Context ctx, LinearLayout root) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        sw(ctx, root, "总开关", cfg.masterSwitch,
            v -> { cfg.masterSwitch = v; cfg.save(prefs); });
        sw(ctx, root, "防撤回", cfg.antiRecall,
            v -> { cfg.antiRecall = v; cfg.save(prefs); });
        sw(ctx, root, "红包助手", cfg.redPacketGrab,
            v -> { cfg.redPacketGrab = v; cfg.save(prefs); });
        sw(ctx, root, "自动通过好友", cfg.autoAcceptFriend,
            v -> { cfg.autoAcceptFriend = v; cfg.save(prefs); });
        ed(ctx, root, "欢迎语", cfg.welcomeMsg,
            s -> { cfg.welcomeMsg = s; cfg.save(prefs); });
    }

    private static void buildTtsSection(Context ctx, LinearLayout root) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        sw(ctx, root, "总开关", cfg.masterSwitch,
            v -> { cfg.masterSwitch = v; cfg.save(prefs); });
        sw(ctx, root, "免打扰", cfg.quietEnabled,
            v -> { cfg.quietEnabled = v; cfg.save(prefs); });

        final String[] engines = {"系统", "配音阁", "五声"};
        btn(ctx, root, "引擎: " + engines[cfg.ttsEngine.equals("peiyin") ? 1 : cfg.ttsEngine.equals("wusound") ? 2 : 0],
            "切换", v -> {
                int cur = cfg.ttsEngine.equals("peiyin") ? 1 : cfg.ttsEngine.equals("wusound") ? 2 : 0;
                int next = (cur + 1) % 3;
                cfg.ttsEngine = next == 1 ? "peiyin" : next == 2 ? "wusound" : "system";
                cfg.save(prefs);
            });

        sw(ctx, root, "文字", cfg.announceText,
            v -> { prefs.edit().putBoolean("ls_announce_text", v).apply(); });
        sw(ctx, root, "图片", cfg.announceImage,
            v -> { prefs.edit().putBoolean("ls_announce_image", v).apply(); });
        sw(ctx, root, "视频", cfg.announceVideo,
            v -> { prefs.edit().putBoolean("ls_announce_video", v).apply(); });
        sw(ctx, root, "红包", cfg.announceRedBag,
            v -> { prefs.edit().putBoolean("ls_announce_redbag", v).apply(); });
        sw(ctx, root, "转账", cfg.announceTransfer,
            v -> { prefs.edit().putBoolean("ls_announce_transfer", v).apply(); });
        sw(ctx, root, "名片", cfg.announceCard,
            v -> { prefs.edit().putBoolean("ls_announce_card", v).apply(); });
    }

    private static void buildDingDongSection(Context ctx, LinearLayout root) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        sw(ctx, root, "启用叮咚", cfg.dianGeEnabled,
            v -> { cfg.dianGeEnabled = v; cfg.save(prefs); });

        TextView info = new TextView(ctx);
        info.setTextSize(11);
        info.setTextColor(CLR_TEXT2);
        info.setPadding(dpC(ctx, 16), dpC(ctx, 4), dpC(ctx, 16), dpC(ctx, 2));
        info.setText("点歌 歌名 | 天气 城市名 | 笑话 | 金句");
        root.addView(info);
    }

    private static void buildGroupGuardSection(Context ctx, LinearLayout root) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        sw(ctx, root, "入群欢迎", cfg.welcomeEnabled,
            v -> { cfg.welcomeEnabled = v; cfg.save(prefs); });
        ed(ctx, root, "欢迎语", cfg.welcomeMsg,
            s -> { cfg.welcomeMsg = s; cfg.save(prefs); });
        sw(ctx, root, "自动踢人", cfg.autoKickEnabled,
            v -> { cfg.autoKickEnabled = v; cfg.save(prefs); });
        ed(ctx, root, "违规阈值", String.valueOf(cfg.kickThreshold),
            s -> { try { cfg.kickThreshold = Integer.parseInt(s); cfg.save(prefs); } catch (Throwable ignored) {} });

        btn(ctx, root, "广告关键词 (" + cfg.adKeywords.size() + "个)", "管理", v -> {
            showKeywordsDialog(ctx);
        });

        btn(ctx, root, "黑名单 (" + cfg.blacklistWxids.size() + "个)", "管理", v -> {
            showBlacklistDialog(ctx);
        });
    }

    private static void buildAISection(Context ctx, LinearLayout root) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        sw(ctx, root, "DeepSeek 对话", cfg.deepseekEnabled,
            v -> { cfg.deepseekEnabled = v; cfg.save(prefs); });
        ed(ctx, root, "API Key", cfg.deepseekApiKey,
            s -> { cfg.deepseekApiKey = s; cfg.save(prefs); });
        ed(ctx, root, "模型", cfg.deepseekModel,
            s -> { cfg.deepseekModel = s; cfg.save(prefs); });
        sw(ctx, root, "AI 图片生成", cfg.imageGenEnabled,
            v -> { cfg.imageGenEnabled = v; cfg.save(prefs); });
        ed(ctx, root, "火山API Key", cfg.arkApiKey,
            s -> { cfg.arkApiKey = s; cfg.save(prefs); });

        TextView info = new TextView(ctx);
        info.setTextSize(11);
        info.setTextColor(CLR_TEXT2);
        info.setPadding(dpC(ctx, 16), dpC(ctx, 4), dpC(ctx, 16), dpC(ctx, 2));
        info.setText("群聊 @机器人 提问 | 私聊发送 AI+内容");
        root.addView(info);
    }

    private static void buildSchedulerSection(Context ctx, LinearLayout root) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        if (cfg.scheduledTasks.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无定时任务");
            empty.setTextSize(12);
            empty.setTextColor(CLR_TEXT2);
            empty.setPadding(dpC(ctx, 16), dpC(ctx, 6), dpC(ctx, 16), dpC(ctx, 6));
            root.addView(empty);
        } else {
            for (int i = 0; i < cfg.scheduledTasks.size(); i++) {
                ScheduledTask t = cfg.scheduledTasks.get(i);
                TextView info = new TextView(ctx);
                info.setText(String.format("%02d:%02d -> %s: %s", t.hour, t.minute, t.targetWxid, t.content));
                info.setTextSize(11);
                info.setTextColor(CLR_TEXT);
                info.setPadding(dpC(ctx, 16), dpC(ctx, 3), dpC(ctx, 16), dpC(ctx, 3));
                root.addView(info);
            }
        }

        btn(ctx, root, "添加任务", "打开", v -> {
            showSchedulerDialog(ctx);
        });
    }

    private static void buildStatsSection(Context ctx, LinearLayout root) {
        TextView fInfo = new TextView(ctx);
        fInfo.setTextSize(12);
        fInfo.setTextColor(CLR_TEXT);
        fInfo.setPadding(dpC(ctx, 16), dpC(ctx, 6), dpC(ctx, 16), dpC(ctx, 2));
        fInfo.setText("好友数: " + ContactRepository.getFriends().size() + "    群聊数: " + ContactRepository.getGroups().size());
        root.addView(fInfo);

        java.util.List<String> recalls = com.leshao.v3.service.StatsCollector.getRecallRecords();
        if (!recalls.isEmpty()) {
            int start = Math.max(0, recalls.size() - 5);
            for (int i = start; i < recalls.size(); i++) {
                TextView tv = new TextView(ctx);
                tv.setText(recalls.get(i));
                tv.setTextSize(11);
                tv.setTextColor(CLR_TEXT2);
                tv.setPadding(dpC(ctx, 16), dpC(ctx, 2), dpC(ctx, 16), dpC(ctx, 2));
                root.addView(tv);
            }
        }
    }

    // ================================================================
    // Contact section (V3 logic with V21 contact tab sub-tabs)
    // ================================================================

    private static void buildContactSection(final Context ctx, LinearLayout root) {
        final Activity act = (Activity) ctx;
        float d = ctx.getResources().getDisplayMetrics().density;
        final int PAGE_SIZE = 50;

        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dpC(ctx, 16), dpC(ctx, 4), dpC(ctx, 16), dpC(ctx, 4));

        TextView tabFriend = makeContactTabBtn(act, "好友", true);
        TextView tabGroup = makeContactTabBtn(act, "群聊", false);
        TextView tabMember = makeContactTabBtn(act, "群成员", false);
        tabBar.addView(tabFriend);
        tabBar.addView(tabGroup);
        tabBar.addView(tabMember);
        root.addView(tabBar);

        final java.util.List<Contact> emptyList = new java.util.ArrayList<>();
        final ContactPickerFragment.ContactAdapter adapter = new ContactPickerFragment.ContactAdapter(emptyList);

        final java.util.Set<String> selectedWxids = new LinkedHashSet<>();
        adapter.setOnItemClickListener((contact, position) -> {
            if (selectedWxids.contains(contact.wxid)) {
                selectedWxids.remove(contact.wxid);
            } else {
                selectedWxids.add(contact.wxid);
            }
            adapter.notifyItemChanged(position);
        });

        final Handler handler = sHandler != null ? sHandler : new Handler(Looper.getMainLooper());
        final int[] currentTab = {0};
        final int[] currentPage = {0, 0, 0};
        final java.util.List<Contact>[] sourceData = new java.util.List[]{null, null, null};

        final Runnable loadMore = new Runnable() {
            @Override
            public void run() {
                int t = currentTab[0];
                int p = currentPage[t];
                java.util.List<Contact> src = sourceData[t];
                if (src == null || src.isEmpty()) return;
                int start = p * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, src.size());
                if (start >= src.size()) return;
                java.util.List<Contact> page = new java.util.ArrayList<>(src.subList(start, end));
                java.util.List<Contact> current = adapter.getData();
                if (current != null) page.addAll(0, current);
                adapter.updateData(page);
                currentPage[t] = p + 1;
            }
        };

        final Runnable switchTab = new Runnable() {
            @Override
            public void run() {
                int t = currentTab[0];
                currentPage[0] = currentPage[1] = currentPage[2] = 0;
                java.util.List<Contact> src = sourceData[t];
                if (src != null && !src.isEmpty()) {
                    int end = Math.min(PAGE_SIZE, src.size());
                    adapter.updateData(new java.util.ArrayList<>(src.subList(0, end)));
                    currentPage[t] = 1;
                } else {
                    adapter.updateData(emptyList);
                }
            }
        };

        tabFriend.setOnClickListener(v -> {
            highlightTab(tabFriend, tabGroup, tabMember);
            currentTab[0] = 0; switchTab.run();
        });
        tabGroup.setOnClickListener(v -> {
            highlightTab(tabGroup, tabFriend, tabMember);
            currentTab[0] = 1; switchTab.run();
        });
        tabMember.setOnClickListener(v -> {
            highlightTab(tabMember, tabFriend, tabGroup);
            currentTab[0] = 2; switchTab.run();
        });

        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutParams(new LinearLayout.LayoutParams(-1, dpC(ctx, 220)));
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        rv.setAdapter(adapter);
        rv.setNestedScrollingEnabled(false);
        root.addView(rv);

        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setPadding(dpC(ctx, 16), dpC(ctx, 6), dpC(ctx, 16), dpC(ctx, 6));

        Button loadMoreBtn = createBtn(ctx, "加载更多");
        loadMoreBtn.setTextSize(11);
        loadMoreBtn.setTextColor(CLR_TEXT);
        loadMoreBtn.setBackground(createOutlineBtnBg(ctx, 8));
        loadMoreBtn.setPadding(dpC(ctx, 12), dpC(ctx, 4), dpC(ctx, 12), dpC(ctx, 4));
        loadMoreBtn.setOnClickListener(v -> loadMore.run());
        actionRow.addView(loadMoreBtn);
        actionRow.addView(spacerH(ctx, 8));

        Button clearBtn = createBtn(ctx, "清空");
        clearBtn.setTextSize(11);
        clearBtn.setTextColor(CLR_TEXT);
        clearBtn.setBackground(createOutlineBtnBg(ctx, 8));
        clearBtn.setPadding(dpC(ctx, 12), dpC(ctx, 4), dpC(ctx, 12), dpC(ctx, 4));
        clearBtn.setOnClickListener(v -> { selectedWxids.clear(); adapter.notifyDataSetChanged(); });
        actionRow.addView(clearBtn);
        actionRow.addView(spacerH(ctx, 8));

        Button batchBtn = createBtn(ctx, "批量操作");
        batchBtn.setTextSize(11);
        batchBtn.setTextColor(CLR_WHITE);
        batchBtn.setBackground(createPrimaryBtnBg(ctx, 8));
        batchBtn.setPadding(dpC(ctx, 12), dpC(ctx, 4), dpC(ctx, 12), dpC(ctx, 4));
        batchBtn.setOnClickListener(v -> {
            if (selectedWxids.isEmpty()) {
                Toast.makeText(ctx, "请先选择联系人", Toast.LENGTH_SHORT).show();
                return;
            }
            LogWriter.log(TAG, "BATCH: " + selectedWxids.size() + " contacts");
        });
        actionRow.addView(batchBtn);

        root.addView(actionRow);

        new Thread(() -> {
            ContactRepository.loadContacts();
            sourceData[0] = ContactRepository.getFriends();
            sourceData[1] = ContactRepository.getGroups();
            sourceData[2] = ContactRepository.getFriends();
            handler.post(() -> {
                LogWriter.log(TAG, "CONTACT: f=" + (sourceData[0] != null ? sourceData[0].size() : 0)
                    + " g=" + (sourceData[1] != null ? sourceData[1].size() : 0));
                switchTab.run();
            });
        }).start();
    }

    // ================================================================
    // Sub-dialogs (keywords, blacklist, scheduler)
    // ================================================================

    private static void showKeywordsDialog(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpC(ctx, 14), dpC(ctx, 10), dpC(ctx, 14), dpC(ctx, 10));
        root.setBackground(new ColorDrawable(CLR_BG));

        TextView tv = new TextView(ctx);
        tv.setText("广告关键词");
        tv.setTextSize(16);
        tv.setTextColor(CLR_ACCENT);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dpC(ctx, 8));
        root.addView(tv);

        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText kwInput = new EditText(ctx);
        kwInput.setHint("输入关键词");
        styleInput(kwInput);
        kwInput.setTextSize(12);
        kwInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        addRow.addView(kwInput);

        final LinearLayout kwList = new LinearLayout(ctx);
        kwList.setOrientation(LinearLayout.VERTICAL);

        Button addBtn = createBtn(ctx, "添加");
        styleButton(addBtn);
        addBtn.setTextSize(11);
        addRow.addView(addBtn);
        root.addView(addRow);
        root.addView(spacerV(ctx, 6));
        root.addView(kwList);

        refreshKwViews(ctx, kwList, cfg);

        addBtn.setOnClickListener(v -> {
            String kw = kwInput.getText().toString().trim();
            if (!kw.isEmpty()) {
                cfg.adKeywords.add(kw);
                cfg.save(prefs);
                refreshKwViews(ctx, kwList, cfg);
                kwInput.setText("");
            }
        });

        root.addView(spacerV(ctx, 8));
        Button closeBtn = createBtn(ctx, "关闭");
        styleButton(closeBtn);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        closeBtn.setOnClickListener(v2 -> {
            if (dlg != null && dlg.isShowing()) dlg.dismiss();
        });
        root.addView(closeBtn);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(CLR_BG));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.9), -2);
        }
        dlg.show();
    }

    private static void refreshKwViews(Context ctx, LinearLayout container, ModuleConfig cfg) {
        container.removeAllViews();
        for (String kw : new java.util.ArrayList<>(cfg.adKeywords)) {
            final String kwf = kw;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpC(ctx, 3), 0, dpC(ctx, 3));

            TextView tv = new TextView(ctx);
            tv.setText(kwf);
            tv.setTextSize(12);
            tv.setTextColor(CLR_TEXT);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(tv);

            GradientDrawable delBg = new GradientDrawable();
            delBg.setCornerRadius(dpC(ctx, 4));
            delBg.setColor(CLR_RED);
            TextView del = new TextView(ctx);
            del.setText("X");
            del.setTextSize(10);
            del.setTextColor(CLR_WHITE);
            del.setBackground(delBg);
            del.setGravity(Gravity.CENTER);
            del.setPadding(dpC(ctx, 6), dpC(ctx, 2), dpC(ctx, 6), dpC(ctx, 2));
            del.setOnClickListener(v2 -> {
                cfg.adKeywords.remove(kwf);
                refreshKwViews(ctx, container, cfg);
            });
            row.addView(del);
            container.addView(row);
        }
    }

    private static void showBlacklistDialog(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpC(ctx, 14), dpC(ctx, 10), dpC(ctx, 14), dpC(ctx, 10));
        root.setBackground(new ColorDrawable(CLR_BG));

        TextView tv = new TextView(ctx);
        tv.setText("黑名单 wxid (每行一个)");
        tv.setTextSize(16);
        tv.setTextColor(CLR_ACCENT);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dpC(ctx, 8));
        root.addView(tv);

        StringBuilder blText = new StringBuilder();
        for (String w : cfg.blacklistWxids) blText.append(w).append("\n");
        final EditText blEdit = new EditText(ctx);
        blEdit.setText(blText.toString());
        blEdit.setTextSize(12);
        blEdit.setTextColor(CLR_TEXT);
        blEdit.setMinLines(4);
        blEdit.setGravity(Gravity.TOP);
        blEdit.setBackgroundDrawable(createInputBg(ctx));
        blEdit.setPadding(dpC(ctx, 10), dpC(ctx, 8), dpC(ctx, 10), dpC(ctx, 8));
        root.addView(blEdit);

        root.addView(spacerV(ctx, 8));
        Button saveBtn = createBtn(ctx, "保存");
        styleButton(saveBtn);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        saveBtn.setOnClickListener(v2 -> {
            cfg.blacklistWxids.clear();
            for (String line : blEdit.getText().toString().split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) cfg.blacklistWxids.add(t);
            }
            cfg.save(prefs);
            if (dlg != null && dlg.isShowing()) dlg.dismiss();
        });
        root.addView(saveBtn);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(CLR_BG));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.9), -2);
        }
        dlg.show();
    }

    private static void showSchedulerDialog(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpC(ctx, 14), dpC(ctx, 10), dpC(ctx, 14), dpC(ctx, 10));
        root.setBackground(new ColorDrawable(CLR_BG));

        TextView tv = new TextView(ctx);
        tv.setText("添加定时任务");
        tv.setTextSize(16);
        tv.setTextColor(CLR_ACCENT);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dpC(ctx, 8));
        root.addView(tv);

        final EditText wxidEdit = new EditText(ctx);
        wxidEdit.setHint("目标 wxid");
        styleInput(wxidEdit);
        wxidEdit.setTextSize(12);
        root.addView(wxidEdit);
        root.addView(spacerV(ctx, 4));

        final EditText contentEdit = new EditText(ctx);
        contentEdit.setHint("发送内容");
        styleInput(contentEdit);
        contentEdit.setTextSize(12);
        root.addView(contentEdit);
        root.addView(spacerV(ctx, 4));

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText hourEdit = new EditText(ctx);
        hourEdit.setHint("小时(0-23)");
        styleInput(hourEdit);
        hourEdit.setTextSize(12);
        hourEdit.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        timeRow.addView(hourEdit);
        timeRow.addView(spacerH(ctx, 4));
        final EditText minEdit = new EditText(ctx);
        minEdit.setHint("分钟(0-59)");
        styleInput(minEdit);
        minEdit.setTextSize(12);
        minEdit.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        timeRow.addView(minEdit);
        root.addView(timeRow);
        root.addView(spacerV(ctx, 8));

        Button addBtn = createBtn(ctx, "添加任务");
        styleButton(addBtn);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        addBtn.setOnClickListener(v2 -> {
            try {
                String wxid = wxidEdit.getText().toString().trim();
                String content = contentEdit.getText().toString().trim();
                int h = Integer.parseInt(hourEdit.getText().toString().trim());
                int m = Integer.parseInt(minEdit.getText().toString().trim());
                if (!wxid.isEmpty() && !content.isEmpty()) {
                    ScheduledTask t = new ScheduledTask(null, wxid, content, h, m, 127, true);
                    cfg.scheduledTasks.add(t);
                    cfg.save(prefs);
                    wxidEdit.setText(""); contentEdit.setText(""); hourEdit.setText(""); minEdit.setText("");
                    if (dlg != null && dlg.isShowing()) dlg.dismiss();
                }
            } catch (Throwable ignored) {}
        });
        root.addView(addBtn);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(CLR_BG));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.9), -2);
        }
        dlg.show();
    }

    private static View spacerV(Context ctx, int hDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dpC(ctx, hDp)));
        return v;
    }
}
