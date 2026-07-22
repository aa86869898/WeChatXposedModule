package com.leshao.v3.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ThemeHook {

    private static final String TAG = "ThemeHook";

    private static volatile boolean sMasterEnabled  = false;
    private static volatile boolean sActionBarOn    = true;
    private static volatile boolean sPageBgOn       = true;
    private static volatile boolean sChatBgOn       = true;
    private static volatile boolean sConvListOn     = true;
    private static volatile boolean sBubbleOn       = true;
    private static volatile boolean sTextColorOn    = true;
    private static volatile boolean sHooksInstalled = false;
    private static volatile Activity sForegroundActivity;
    private static volatile String  sBubbleImagePath;
    private static volatile Bitmap  sBubbleBitmap;

    private static int sActionBarBg     = 0xFF2D2D2D;
    private static int sActionBarTitle  = 0xFFFFFFFF;
    private static int sPageBg          = 0xFFF5F5F5;
    private static int sChatBg          = 0xFFEDEDED;
    private static int sBubbleSelfBg    = 0xFF95EC69;
    private static int sBubbleOtherBg   = 0xFFFFFFFF;
    private static int sBubbleSelfText  = 0xFF000000;
    private static int sBubbleOtherText = 0xFF000000;
    private static int sTabBg           = 0xFFF7F7F7;
    private static int sTabSelected     = 0xFFFF4298;
    private static int sTabUnselected   = 0xFF999999;
    private static int sTextPrimary     = 0xFF191919;
    private static int sTextSecondary   = 0xFF888888;

    public static void setMasterEnabled(boolean v) { sMasterEnabled = v; }
    public static void setActionBarOn(boolean v)  { sActionBarOn  = v; }
    public static void setPageBgOn(boolean v)     { sPageBgOn     = v; }
    public static void setChatBgOn(boolean v)     { sChatBgOn     = v; }
    public static void setConvListOn(boolean v)   { sConvListOn   = v; }
    public static void setBubbleOn(boolean v)     { sBubbleOn     = v; }
    public static void setTextColorOn(boolean v)  { sTextColorOn  = v; }
    public static boolean isMasterEnabled() { return sMasterEnabled; }

    private static void refreshAllStates() {
        SharedPreferences p = ContextManager.getPrefs();
        if (p == null) return;
        sMasterEnabled = p.getBoolean("ls_theme_enabled", false);
        sActionBarOn   = p.getBoolean("ls_theme_actionbar", true);
        sPageBgOn      = p.getBoolean("ls_theme_pagebg", true);
        sChatBgOn      = p.getBoolean("ls_theme_chatbg", true);
        sConvListOn    = p.getBoolean("ls_theme_convlist", true);
        sBubbleOn      = p.getBoolean("ls_theme_bubble", true);
        sTextColorOn   = p.getBoolean("ls_theme_textcolor", true);
        loadColors();
    }

    public static void loadColors() {
        SharedPreferences p = ContextManager.getPrefs();
        if (p == null) return;
        sActionBarBg    = p.getInt("ls_tc_actionbar_bg",       0xFF2D2D2D);
        sActionBarTitle = p.getInt("ls_tc_actionbar_title",    0xFFFFFFFF);
        sPageBg         = p.getInt("ls_tc_page_bg",            0xFFF5F5F5);
        sChatBg         = p.getInt("ls_tc_chat_bg",            0xFFEDEDED);
        sBubbleSelfBg   = p.getInt("ls_tc_bubble_self_bg",     0xFF95EC69);
        sBubbleOtherBg  = p.getInt("ls_tc_bubble_other_bg",    0xFFFFFFFF);
        sBubbleSelfText = p.getInt("ls_tc_bubble_self_text",   0xFF000000);
        sBubbleOtherText= p.getInt("ls_tc_bubble_other_text",  0xFF000000);
        sTabBg          = p.getInt("ls_tc_tab_bg",             0xFFF7F7F7);
        sTabSelected    = p.getInt("ls_tc_tab_selected",       0xFFFF4298);
        sTabUnselected  = p.getInt("ls_tc_tab_unselected",     0xFF999999);
        sTextPrimary    = p.getInt("ls_tc_text_primary",       0xFF191919);
        sTextSecondary  = p.getInt("ls_tc_text_secondary",     0xFF888888);
        sBubbleImagePath = p.getString("ls_bubble_image", null);
        sBubbleBitmap = null;
        LogWriter.log(TAG, "loadColors done");
    }

    public static void applyMonetPalette(int[] palette) {
        if (palette == null || palette.length < 15) return;
        SharedPreferences p = ContextManager.getPrefs();
        if (p == null) return;
        p.edit()
            .putInt("ls_tc_actionbar_bg",       palette[1])
            .putInt("ls_tc_actionbar_title",    palette[14])
            .putInt("ls_tc_page_bg",            palette[5])
            .putInt("ls_tc_chat_bg",            palette[7])
            .putInt("ls_tc_bubble_self_bg",     palette[0])
            .putInt("ls_tc_bubble_other_bg",    palette[14])
            .putInt("ls_tc_bubble_self_text",   palette[12])
            .putInt("ls_tc_bubble_other_text",  palette[13])
            .putInt("ls_tc_tab_bg",             palette[6])
            .putInt("ls_tc_tab_selected",       palette[0])
            .putInt("ls_tc_tab_unselected",     palette[13])
            .putInt("ls_tc_text_primary",       palette[12])
            .putInt("ls_tc_text_secondary",     palette[13])
            .apply();
        loadColors();
    }

    public static void setBubbleImage(String path) {
        sBubbleImagePath = path;
        sBubbleBitmap = null;
        if (path != null && !path.isEmpty()) {
            try { sBubbleBitmap = BitmapFactory.decodeFile(path); }
            catch (Throwable t) { LogWriter.log(TAG, "bubble img err: " + t.getMessage()); }
        }
        SharedPreferences p = ContextManager.getPrefs();
        if (p != null) p.edit().putString("ls_bubble_image", path != null ? path : "").apply();
    }

    public static void hook() {
        if (!ContextManager.isReady()) { LogWriter.log(TAG, "not ready"); return; }
        if (sHooksInstalled) return;
        sHooksInstalled = true;
        refreshAllStates();

        LogWriter.log(TAG, "state: m=" + sMasterEnabled + " bar=" + sActionBarOn
            + " page=" + sPageBgOn + " chatBg=" + sChatBgOn
            + " bubble=" + sBubbleOn + " tab=" + sConvListOn + " text=" + sTextColorOn);

        ClassLoader cl = ContextManager.getClassLoader();
        methodA_Resources(cl);
        methodB_ActivityBg(cl);
        methodC_ActionBar(cl);
        methodD_MainTab(cl);
        methodE_ConvList(cl);
        methodF_DyeTemplate(cl);
        methodH_TextColor(cl);
        methodX_AddViewFallback(cl);
        methodG_ChatUI_Scan(cl);
        trackForegroundActivity();
        hookGalleryResult();

        LogWriter.log(TAG, "all hooks installed");
    }

    private static void trackForegroundActivity() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        sForegroundActivity = (Activity) param.thisObject;
                    }
                });
            XposedBridge.hookAllMethods(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sForegroundActivity = (Activity) param.thisObject;
                    }
                });
            XposedBridge.hookAllMethods(Activity.class, "onPause",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sForegroundActivity == param.thisObject)
                            sForegroundActivity = null;
                    }
                });
        } catch (Throwable ignored) {}
    }

    private static void methodA_Resources(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Resources.class, "getColor",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sMasterEnabled || !sConvListOn) return;
                        int orig = (int) param.getResult();
                        if (orig == 0xFF07C160) param.setResult(sTabSelected);
                    }
                });
            LogWriter.log(TAG, "[A] ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[A] err: " + t.getMessage()); }
    }

    private static void methodB_ActivityBg(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sMasterEnabled || !sPageBgOn) return;
                        Activity act = (Activity) param.thisObject;
                        if (!act.getClass().getName().startsWith("com.tencent.mm")) return;
                        if (isChattingUI(act)) return;
                        applyPageBg(act);
                    }
                });
            LogWriter.log(TAG, "[B] ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[B] err: " + t.getMessage()); }
    }

    private static void applyPageBg(Activity act) {
        Handler h = new Handler(Looper.getMainLooper());
        Runnable task = () -> {
            try {
                View content = act.findViewById(android.R.id.content);
                if (content instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) content;
                    if (vg.getChildCount() > 0)
                        vg.getChildAt(0).setBackgroundColor(sPageBg);
                }
            } catch (Throwable ignored) {}
        };
        h.postDelayed(task, 30);
        h.postDelayed(task, 200);
    }

    private static void methodC_ActionBar(ClassLoader cl) {
        try {
            Class<?> ga = XposedHelpers.findClass("com.tencent.mm.ui.ga", cl);
            XposedBridge.hookAllMethods(ga, "E0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sMasterEnabled || !sActionBarOn) return;
                    if (param.args.length > 0 && param.args[0] instanceof Integer)
                        param.args[0] = sActionBarTitle;
                }
            });
            LogWriter.log(TAG, "[C] ga.E0 title ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[C] ga.E0 err: " + t.getMessage()); }

        try {
            Class<?> mmActivity = XposedHelpers.findClass(
                "com.tencent.mm.ui.MMActivity", cl);
            XposedBridge.hookAllMethods(mmActivity, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sMasterEnabled || !sActionBarOn) return;
                    Activity act = (Activity) param.thisObject;
                    Handler h = new Handler(Looper.getMainLooper());
                    h.postDelayed(() -> themeActionBarByDimension(act), 60);
                    h.postDelayed(() -> themeActionBarByDimension(act), 300);
                }
            });
            LogWriter.log(TAG, "[C] dim-scan ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[C] dim-scan err: " + t.getMessage()); }
    }

    private static void themeActionBarByDimension(Activity act) {
        try {
            View decor = act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decor;
            int screenW = root.getWidth();
            if (screenW <= 0) return;
            float d = act.getResources().getDisplayMetrics().density;
            int minH = (int)(44 * d);
            int maxH = (int)(72 * d);

            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (!(child instanceof ViewGroup)) continue;
                ViewGroup vg = (ViewGroup) child;
                if (vg.getWidth() < screenW * 0.9f) continue;
                int h = vg.getHeight();
                if (h < minH || h > maxH) continue;
                vg.setBackgroundColor(sActionBarBg);
                setAllTextColors(vg, sActionBarTitle);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private static void setAllTextColors(ViewGroup vg, int color) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) ((TextView) c).setTextColor(color);
            else if (c instanceof ViewGroup) setAllTextColors((ViewGroup) c, color);
        }
    }

    private static void methodD_MainTab(ClassLoader cl) {
        try {
            Class<?> lUI = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl);
            XposedBridge.hookAllMethods(lUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sMasterEnabled || !sConvListOn) return;
                    Activity act = (Activity) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { colorByName(act.getWindow().getDecorView(), "tablayout|bottomtab|mmtab|maintab", sTabBg); }
                        catch (Throwable ignored) {}
                    }, 300);
                }
            });
            LogWriter.log(TAG, "[D] ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[D] err: " + t.getMessage()); }
    }

    private static void methodE_ConvList(ClassLoader cl) {
        String[] names = {"com.tencent.mm.ui.conversation.MainUI",
            "com.tencent.mm.ui.conversation.ConversationUI",
            "com.tencent.mm.ui.conversation.NewMainUI"};
        boolean ok = false;
        for (String name : names) {
            try {
                Class<?> c = XposedHelpers.findClass(name, cl);
                XposedBridge.hookAllMethods(c, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sMasterEnabled || !sConvListOn) return;
                        Activity act = (Activity) param.thisObject;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try { colorByName(act.getWindow().getDecorView(), "recycler", sPageBg); }
                            catch (Throwable ignored) {}
                        }, 120);
                    }
                });
                LogWriter.log(TAG, "[E] " + name + " ok");
                ok = true; break;
            } catch (Throwable t) { LogWriter.log(TAG, "[E] " + name + " err: " + t.getMessage()); }
        }
        if (!ok) LogWriter.log(TAG, "[E] no conv UI found");
    }

    private static void methodF_DyeTemplate(ClassLoader cl) {
        try {
            Class<?> dye = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.viewitems.ChattingItemDyeingTemplate", cl);

            XposedBridge.hookAllMethods(dye, "l0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "[F] l0 FIRED master=" + sMasterEnabled + " bubble=" + sBubbleOn);
                    if (!sMasterEnabled || !sBubbleOn) return;
                    applyDyeColors(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(dye, "n0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sMasterEnabled || !sBubbleOn) return;
                    applyDyeColors(param.thisObject);
                }
            });

            try {
                XposedBridge.hookAllMethods(dye, "p0", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!sMasterEnabled || !sBubbleOn) return;
                        applyDyeColors(param.thisObject);
                    }
                });
                LogWriter.log(TAG, "[F] p0 extra hook ok");
            } catch (Throwable ignored) {}

            LogWriter.log(TAG, "[F] DyeTemplate l0+n0 ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[F] DyeTemplate err: " + t.getMessage()); }
    }

    private static void applyDyeColors(Object template) {
        try {
            if (sBubbleBitmap != null && !sBubbleBitmap.isRecycled()) {
                try {
                    XposedHelpers.setObjectField(template, "G",
                        new BitmapDrawable(sBubbleBitmap));
                } catch (Throwable ignored) {}
            }
            try {
                XposedHelpers.setIntField(template, "O1", sBubbleSelfBg);
            } catch (Throwable t) {
                LogWriter.log(TAG, "[F] setIntField O1 err: " + t.getMessage());
            }
            try {
                XposedHelpers.setIntField(template, "P1", sBubbleOtherBg);
            } catch (Throwable t) {
                LogWriter.log(TAG, "[F] setIntField P1 err: " + t.getMessage());
            }
            try {
                XposedHelpers.setIntField(template, "M1", sBubbleSelfText);
            } catch (Throwable t) {
                LogWriter.log(TAG, "[F] setIntField M1 err: " + t.getMessage());
            }
            try {
                XposedHelpers.setIntField(template, "N1", sBubbleOtherText);
            } catch (Throwable t) {
                LogWriter.log(TAG, "[F] setIntField N1 err: " + t.getMessage());
            }
        } catch (Throwable ignored) {}
    }

    private static void methodH_TextColor(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setTextColor",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!sMasterEnabled || !sTextColorOn) return;
                        if (!isInThemableContext()) return;
                        if (param.args.length == 0) return;
                        int color = (int) param.args[0];
                        if (color == 0xFF000000 || color == Color.BLACK || isNearBlack(color))
                            param.args[0] = sTextPrimary;
                    }
                });
            LogWriter.log(TAG, "[H] ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[H] err: " + t.getMessage()); }
    }

    private static void methodX_AddViewFallback(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(ViewGroup.class, "addView",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sMasterEnabled || !sBubbleOn) return;
                        View child = (View) param.args[0];
                        if (child == null) return;
                        ViewGroup parent = (ViewGroup) param.thisObject;
                        String pn = parent.getClass().getName().toLowerCase();
                        if (!pn.contains("recycler") && !pn.contains("list")) return;
                        if (!isInMMContext()) return;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try { scanBubbles(child); }
                            catch (Throwable ignored) {}
                        }, 100);
                    }
                });
            LogWriter.log(TAG, "[X] addView scan ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[X] err: " + t.getMessage()); }
    }

    private static void methodG_ChatUI_Scan(ClassLoader cl) {
        try {
            Class<?> chatUI = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chatUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    LogWriter.log(TAG, "[G] ChattingUI resume"
                        + " master=" + sMasterEnabled + " chatBg=" + sChatBgOn + " bubble=" + sBubbleOn);
                    if (!sMasterEnabled) return;
                    Handler h = new Handler(Looper.getMainLooper());
                    h.postDelayed(() -> applyChatTheme(act), 200);
                    h.postDelayed(() -> applyChatTheme(act), 600);
                    h.postDelayed(() -> applyChatTheme(act), 1200);
                }
            });
            LogWriter.log(TAG, "[G] ChattingUI ok");
        } catch (Throwable t) { LogWriter.log(TAG, "[G] ChattingUI err: " + t.getMessage()); }
    }

    private static void applyChatTheme(Activity act) {
        try {
            View root = act.getWindow().getDecorView();
            if (sChatBgOn) {
                colorByName(root, "recycler", sChatBg);
            }
            if (sBubbleOn) {
                scanBubbles(root);
            }
        } catch (Throwable t) { LogWriter.log(TAG, "[G] scan err: " + t.getMessage()); }
    }

    private static void scanBubbles(View v) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) v;
        if (isBubble(vg)) {
            boolean self = isSelfBubble(vg);
            int bg = self ? sBubbleSelfBg : sBubbleOtherBg;
            int text = self ? sBubbleSelfText : sBubbleOtherText;
            setBgKeepShape(vg, bg);
            colorTexts(vg, text);
            return;
        }
        for (int i = 0; i < vg.getChildCount(); i++)
            scanBubbles(vg.getChildAt(i));
    }

    private static boolean isBubble(ViewGroup vg) {
        String cn = vg.getClass().getName().toLowerCase();
        if (cn.contains("recycler") || cn.contains("list") || cn.contains("adapter")) return false;
        if (vg.getChildCount() == 0) return false;
        View parent = (View) vg.getParent();
        if (parent != null && parent.getWidth() > 0) {
            float ratio = (float) vg.getWidth() / (float) parent.getWidth();
            if (ratio > 0.12f && ratio < 0.88f && hasTV(vg)) return true;
        }
        Drawable bg = vg.getBackground();
        if (bg instanceof GradientDrawable) {
            float[] rad = radii((GradientDrawable) bg);
            if (rad != null) for (float rr : rad) if (rr > 0) return true;
        }
        return false;
    }

    private static boolean isSelfBubble(ViewGroup vg) {
        View parent = (View) vg.getParent();
        if (parent == null || parent.getWidth() <= 0 || vg.getWidth() <= 0) {
            return vg.getLeft() > 0;
        }
        float centerX = vg.getLeft() + vg.getWidth() / 2f;
        return centerX > parent.getWidth() * 0.5f;
    }

    private static void setBgKeepShape(ViewGroup vg, int color) {
        if (sBubbleBitmap != null && !sBubbleBitmap.isRecycled()) {
            vg.setBackground(new BitmapDrawable(vg.getResources(), sBubbleBitmap));
            return;
        }
        Drawable old = vg.getBackground();
        if (old instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) old.mutate();
            gd.setColor(color);
            vg.setBackground(gd);
        } else if (old == null || old instanceof android.graphics.drawable.ColorDrawable) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dp(vg, 8));
            gd.setColor(color);
            vg.setBackground(gd);
        }
    }

    private static void colorTexts(ViewGroup vg, int color) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) ((TextView) c).setTextColor(color);
            else if (c instanceof ViewGroup) colorTexts((ViewGroup) c, color);
        }
    }

    private static void hookGalleryResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                int.class, int.class, android.content.Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int reqCode = (int) param.args[0];
                        int resCode = (int) param.args[1];
                        android.content.Intent data = (android.content.Intent) param.args[2];
                        if (reqCode != 9001 || resCode != Activity.RESULT_OK || data == null) return;
                        try {
                            Uri uri = data.getData();
                            if (uri == null) return;
                            Activity act = (Activity) param.thisObject;
                            File dest = new File(act.getFilesDir(), "leshao_bubble_img.png");
                            InputStream is = act.getContentResolver().openInputStream(uri);
                            if (is != null) {
                                FileOutputStream fos = new FileOutputStream(dest);
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                                fos.close(); is.close();
                                setBubbleImage(dest.getAbsolutePath());
                                LogWriter.log(TAG, "gallery ok: " + dest.getAbsolutePath());
                            }
                        } catch (Throwable t) { LogWriter.log(TAG, "gallery err: " + t.getMessage()); }
                    }
                });
        } catch (Throwable t) { LogWriter.log(TAG, "gallery hook err: " + t.getMessage()); }
    }

    private static boolean isChattingUI(Activity act) {
        String name = act.getClass().getName().toLowerCase();
        return name.contains("chattingui") || name.contains("chatting");
    }

    private static boolean isInThemableContext() {
        if (sForegroundActivity == null) return false;
        String name = sForegroundActivity.getClass().getName().toLowerCase();
        if (!name.startsWith("com.tencent.mm")) return false;
        if (name.contains("setting") || name.contains("address") || name.contains("contact")
            || name.contains("discover") || name.contains("search")
            || name.contains("brand") || name.contains("biz")) return false;
        return true;
    }

    private static boolean isInMMContext() {
        if (sForegroundActivity == null) return false;
        String name = sForegroundActivity.getClass().getName().toLowerCase();
        if (!name.startsWith("com.tencent.mm")) return false;
        return name.contains("chatting") || name.contains("mainui")
            || name.contains("conversation") || name.contains("launcher");
    }

    private static void colorByName(View v, String pattern, int color) {
        if (v.getClass().getName().toLowerCase().matches(".*(" + pattern + ").*"))
            v.setBackgroundColor(color);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                colorByName(vg.getChildAt(i), pattern, color);
        }
    }

    private static boolean isNearBlack(int c) {
        return Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40 && Color.alpha(c) > 200;
    }

    private static boolean hasTV(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++)
            if (vg.getChildAt(i) instanceof TextView) return true;
        return false;
    }

    private static float[] radii(GradientDrawable gd) {
        try {
            java.lang.reflect.Field f = GradientDrawable.class.getDeclaredField("mGradientState");
            f.setAccessible(true);
            Object s = f.get(gd);
            java.lang.reflect.Field rf = s.getClass().getDeclaredField("mRadiusArray");
            rf.setAccessible(true);
            return (float[]) rf.get(s);
        } catch (Throwable e) { return null; }
    }

    private static int dp(View v, int dp) {
        return (int) (dp * v.getContext().getResources().getDisplayMetrics().density + 0.5f);
    }
}
