package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ShadowLabelStore;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.hook.model.LabelInfo;
import com.leshao.v3.ui.AvatarHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;

public class ChatGroupUiInjector {

    private static final String TAG = "ChatGroupUiInjector";
    private static final String BAR_TAG = "CHAT_LABEL_TAG_BAR";

    private static volatile LinearLayout sTagContainer;
    private static volatile int sSelectedLabelId = -1;
    private static volatile Activity sCurrentActivity;
    private static volatile List<LabelInfo> sLabels;
    private static volatile long sLastLabelRefresh;

    public static void hook(final ClassLoader cl) {
        logBoth("hook() start");
        try { installTagFilterBar(cl); } catch (Throwable e) { logBoth("tag err: " + e.getMessage()); }
        try { installContextMenuHooks(cl); } catch (Throwable e) { logBoth("menu err: " + e.getMessage()); }
        logBoth("hook() done");
    }

    private static boolean sHeaderAdded = false;
    private static volatile String sPendingUsername;
    private static volatile String sPendingContextUsername;
    private static View sTagBarView;
    private static final int CTX_MENU_BASE = 0x7F030000;
    private static final int CTX_MENU_MORE = CTX_MENU_BASE + 999999;
    private static final int CTX_MENU_NEW = CTX_MENU_BASE + 999998;

    private static void installTagFilterBar(final ClassLoader cl) {
        // Hook s5.h to capture the ConversationListView reference
        try {
            Class<?> s5 = XposedHelpers.findClass("com.tencent.mm.ui.conversation.s5", cl);
            XposedBridge.hookAllMethods(s5, "h", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object convList = XposedHelpers.getObjectField(p.thisObject, "f");
                        if (convList == null) return;
                        View view = (View) convList;
                        sCurrentActivity = (Activity) view.getContext();
                        sConvListView = view;
                    } catch (Throwable e) {
                        logBoth("s5.h err: " + e.getMessage());
                    }
                }
            });
            logBoth("s5.h hooked");
        } catch (Throwable e) { logBoth("s5: " + e.getMessage()); }

        // Hook MainUI (conversation fragment) onResume via reflection+method hook
        try {
            Class<?> mainUIConv = XposedHelpers.findClass("com.tencent.mm.ui.conversation.MainUI", cl);
            java.lang.reflect.Method onResumeMethod = mainUIConv.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(onResumeMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        sCurrentActivity = (Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                    } catch (Throwable ignored) {}
                    try {
                        Object adapter = XposedHelpers.getObjectField(param.thisObject, "v");
                        logBoth("MainUI.v=" + (adapter != null ? adapter.getClass().getName() : "null"));
                        if (adapter != null) {
                            logBoth("ConvFilter call install");
                            try {
                                ConversationFilter.install(cl, adapter);
                                logBoth("ConvFilter install returned");
                            } catch (Throwable t) {
                                logBoth("ConvFilter install threw: " + t);
                            }
                        }
                    } catch (Throwable e) {
                        logBoth("MainUI.v err: " + e.getMessage());
                    }
                    new Handler(Looper.getMainLooper()).post(() -> {
                        injectHeaderToConversationList();
                    });
                }
            });
            logBoth("MainUI.conversation.onResume hooked via hookMethod");
        } catch (Throwable e) {
            logBoth("MainUI.conversation not found: " + e.getMessage());
            try {
                Class<?> launcherUI = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl);
                java.lang.reflect.Method lrMethod = launcherUI.getDeclaredMethod("onResume");
                XposedBridge.hookMethod(lrMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        sCurrentActivity = (Activity) p.thisObject;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            injectHeaderToConversationList();
                        });
                    }
                });
                logBoth("LauncherUI.onResume hooked via hookMethod");
            } catch (Throwable e2) {
                logBoth("LauncherUI onResume hook failed: " + e2.getMessage());
            }
        }
    }

    private static View sConvListView;

    private static void injectHeaderToConversationList() {
        try {
            if (sHeaderAdded && sTagBarView != null && sTagBarView.getParent() != null) {
                if (ChatGroupHook.isReady()) refreshAll();
                return;
            }

            // getParent() null means old ListView was destroyed — recreate
            if (sTagBarView != null && sTagBarView.getParent() == null) {
                sTagBarView = null;
                sTagContainer = null;
                sHeaderAdded = false;
            }

            // Try to find ConversationListView via s5 capture first
            View convList = sConvListView;
            if (convList == null) {
                // Fallback: traverse from Activity DecorView
                if (sCurrentActivity == null) return;
                View root = sCurrentActivity.getWindow().getDecorView();
                convList = findConversationListView(root);
                if (convList != null) {
                    sConvListView = convList;
                    logBoth("found convList via traverse: " + convList.getClass().getName());
                }
            }

            if (convList == null) {
                logBoth("injectHeader: convList not found");
                return;
            }

            // Try addHeaderView via reflection (for AbsListView subclasses)
            if (tryAddHeaderView(convList)) {
                sHeaderAdded = true;
                if (ChatGroupHook.isReady()) refreshAll();
                logBoth("addHeaderView success");
                return;
            }

            // Fallback: try RecyclerView -> add as sibling
            if (convList.getClass().getName().contains("RecyclerView")) {
                injectAboveRecyclerView(convList);
                sHeaderAdded = true;
                logBoth("injectAboveRecyclerView done");
                return;
            }

            logBoth("injectHeader: unsupported list type: " + convList.getClass().getName());
        } catch (Throwable e) {
            logBoth("injectHeader err: " + e.getMessage());
        }
    }

    /** Try calling addHeaderView() via reflection — works for AbsListView subclasses */
    private static boolean tryAddHeaderView(View listView) {
        try {
            if (sTagBarView == null) {
                sTagBarView = buildTagBarView(listView.getContext());
            }
            Class<?> cls = listView.getClass();
            while (cls != null) {
                try {
                    java.lang.reflect.Method m = cls.getDeclaredMethod("addHeaderView",
                        View.class, Object.class, boolean.class);
                    m.setAccessible(true);
                    m.invoke(listView, sTagBarView, null, false);
                    logBoth("addHeaderView called on " + cls.getName());
                    return true;
                } catch (NoSuchMethodException e) {
                    cls = cls.getSuperclass();
                }
            }
        } catch (Throwable e) {
            logBoth("addHeaderView failed: " + e.getMessage());
        }
        return false;
    }

    /** Fallback: insert bar as sibling above RecyclerView in parent */
    private static void injectAboveRecyclerView(View recyclerView) {
        ViewGroup parent = (ViewGroup) recyclerView.getParent();
        if (parent == null) return;
        if (sTagBarView == null) {
            sTagBarView = buildTagBarView(recyclerView.getContext());
        }
        int idx = parent.indexOfChild(recyclerView);
        parent.addView(sTagBarView, idx,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48, recyclerView.getContext())));
        if (sTagContainer == null) buildTagBarView(recyclerView.getContext());
        if (ChatGroupHook.isReady()) refreshAll();
    }

    /** Traverse view tree to find ConversationListView */
    private static View findConversationListView(View root) {
        if (root.getClass().getName().contains("ConversationListView")) return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findConversationListView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ================================================================
    // Tag filter bar
    // ================================================================

    private static View buildTagBarView(Context ctx) {
        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        wrapper.setLayoutParams(new AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52, ctx)));
        wrapper.addView(buildBar(ctx), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        return wrapper;
    }

    private static View buildBar(Context ctx) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setTag(BAR_TAG);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        boolean dark = isDarkMode(ctx);
        hsv.setBackgroundColor(dark ? Color.parseColor("#1E1E1E") : Color.parseColor("#F5F5F5"));
        LinearLayout ll = new LinearLayout(ctx);
        ll.setClipChildren(false);
        ll.setClipToPadding(false);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setPadding(dp(8, ctx), dp(2, ctx), dp(8, ctx), dp(2, ctx));
        FrameLayout.LayoutParams llLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llLp.gravity = Gravity.CENTER_HORIZONTAL;
        hsv.addView(ll, llLp);
        sTagContainer = ll;
        return hsv;
    }

    /** Refresh label list from WeChat + cache (called on resume and after mutations) */
    public static void refreshLabelList() {
        long start = System.currentTimeMillis();
        try { sLabels = ChatGroupHook.getAllLabelsLightweight(); }
        catch (Throwable e) { logBoth("refreshLabel: " + e.getMessage()); }
        sLastLabelRefresh = System.currentTimeMillis();
        long dur = sLastLabelRefresh - start;
        if (dur > 100) logBoth("refreshLabel took " + dur + "ms, count=" + (sLabels != null ? sLabels.size() : 0));
    }

    /** Render tag chips using cached sLabels only (no WeChat query) */
    public static void refreshTagChips() {
        if (sTagContainer == null) return;
        final Context ctx = sTagContainer.getContext();
        if (ctx == null) return;
        if (sLabels == null) refreshLabelList();
        int curSelection = sSelectedLabelId;
        sTagContainer.removeAllViews();
        sTagContainer.addView(makeChip(ctx, "\u5168\u90E8", -1, curSelection == -1));
        List<LabelInfo> labels = sLabels;
        if (labels != null && !labels.isEmpty()) {
            // apply saved sort order
            List<Integer> order = ShadowLabelStore.getLabelOrder();
            if (!order.isEmpty()) {
                Map<Integer, LabelInfo> idMap = new LinkedHashMap<>();
                for (LabelInfo l : labels) idMap.put(l.labelId, l);
                List<LabelInfo> sorted = new ArrayList<>();
                for (int id : order) {
                    LabelInfo li = idMap.remove(id);
                    if (li != null) sorted.add(li);
                }
                sorted.addAll(idMap.values()); // append new unsorted ones
                labels = sorted;
            }
            sTagContainer.addView(spacer(ctx));
            for (LabelInfo l : labels) {
                sTagContainer.addView(makeChip(ctx, l.labelName, l.labelId, curSelection == l.labelId));
            }
        }
        sTagContainer.addView(spacer(ctx));
        sTagContainer.addView(makeAddBtn(ctx));
        centerChips(ctx);
    }

    /** Center chips within the HorizontalScrollView once layout is complete */
    private static void centerChips(final Context ctx) {
        if (sTagContainer == null) return;
        final int basePad = dp(8, ctx);
        final int vPad = dp(2, ctx);
        sTagContainer.setPadding(basePad, vPad, basePad, vPad);
        sTagContainer.postDelayed(() -> centerChipsNow(ctx), 150);
    }

    private static void centerChipsNow(Context ctx) {
        try {
            if (sTagContainer == null) return;
            View parent = (View) sTagContainer.getParent();
            if (parent == null || parent.getWidth() <= 0) return;
            int barW = parent.getWidth();
            int totalW = 0;
            int childCount = sTagContainer.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = sTagContainer.getChildAt(i);
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) child.getLayoutParams();
                totalW += child.getMeasuredWidth() + clp.leftMargin + clp.rightMargin;
            }
            logBoth("centerChips barW=" + barW + " totalW=" + totalW);
        } catch (Throwable ignored) {}
    }

    /** Full refresh: load labels + re-render chips */
    public static void refreshAll() {
        refreshLabelList();
        ConversationFilter.scanUnreadCounts();
        refreshTagChips();
    }

    private static boolean isDarkMode(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static View makeChip(Context ctx, String text, int id, boolean sel) {
        FrameLayout fl = new FrameLayout(ctx);
        fl.setClipChildren(false);
        fl.setClipToPadding(false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(dp(4, ctx), 0, dp(4, ctx), 0);
        fl.setLayoutParams(flp);

        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(15);
        tv.setPadding(dp(15, ctx), dp(7, ctx), dp(15, ctx), dp(7, ctx));
        tv.setGravity(Gravity.CENTER); tv.setSingleLine(true);
        tv.setMinWidth(dp(50, ctx));
        boolean dark = isDarkMode(ctx);
        if (sel) {
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF6B8A, 0xFFA855F7, 0xFF38BDF8});
            bg.setCornerRadius(dp(20, ctx));
            bg.setStroke(dp(1, ctx), 0xB3FFFFFF);
            tv.setBackground(bg);
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setShadowLayer(dp(4, ctx), 0, 0, 0x40A855F7);
        } else {
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                dark ? new int[]{0x26FF6B8A, 0x26A855F7, 0x2638BDF8}
                     : new int[]{0x1AFF6B8A, 0x1AA855F7, 0x1A38BDF8});
            bg.setCornerRadius(dp(20, ctx));
            bg.setStroke(dp(1, ctx), dark ? Color.parseColor("#C084FC") : Color.parseColor("#A855F7"));
            tv.setBackground(bg);
            tv.setTextColor(dark ? Color.parseColor("#C8C8CE") : Color.parseColor("#555555"));
        }
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tvLp.topMargin = dp(13, ctx);
        fl.addView(tv, tvLp);

        // badge: 浮在按钮右上角上方，顶部留 1dp，底部与按钮之间留 1dp 间隙
        int unread = (id != -1) ? ConversationFilter.getUnreadForLabel(id) : 0;
        if (unread > 0) {
            TextView badge = new TextView(ctx);
            String badgeText = unread > 99 ? "99+" : String.valueOf(unread);
            badge.setText(badgeText);
            badge.setTextSize(8);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setSingleLine(true);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.RED);
            badgeBg.setCornerRadius(dp(5, ctx));
            badge.setBackground(badgeBg);
            int bw = unread > 9 ? dp(18, ctx) : dp(12, ctx);
            badge.setMinWidth(bw);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(11, ctx));
            blp.gravity = Gravity.TOP | Gravity.END;
            blp.topMargin = dp(1, ctx);
            blp.rightMargin = dp(1, ctx);
            fl.addView(badge, blp);
        }

        tv.setOnClickListener(v -> {
            try {
                logBoth("chip click id=" + id + " selected=" + sSelectedLabelId);
                if (sSelectedLabelId == id) {
                    sSelectedLabelId = -1;
                    ConversationFilter.clearFilter();
                } else {
                    sSelectedLabelId = id;
                    String name = getLabelNameById(id);
                    if ("\u5168\u90E8".equals(name)) {
                        ConversationFilter.clearFilter();
                    } else {
                        logBoth("ConvFilter call applyFilter " + id);
                        ConversationFilter.applyFilter(id, name);
                        logBoth("ConvFilter applyFilter returned");
                    }
                }
                refreshTagChips();
            } catch (Throwable e) {
                logBoth("chip click err: " + e.getMessage());
            }
        });
        if (id > 0) tv.setOnLongClickListener(v -> { showLabelManage(ctx, id, text, fl); return true; });
        return fl;
    }

    private static View makeAddBtn(Context ctx) {
        FrameLayout fl = new FrameLayout(ctx);
        fl.setClipChildren(false);
        fl.setClipToPadding(false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(dp(4, ctx), 0, dp(4, ctx), 0);
        fl.setLayoutParams(flp);

        TextView tv = new TextView(ctx);
        tv.setText("\uFF0B"); tv.setTextSize(15);
        boolean dark = isDarkMode(ctx);
        tv.setTextColor(dark ? Color.parseColor("#C084FC") : Color.parseColor("#A855F7"));
        tv.setPadding(dp(10, ctx), dp(7, ctx), dp(10, ctx), dp(7, ctx));
        tv.setGravity(Gravity.CENTER); tv.setTypeface(null, Typeface.BOLD);
        tv.setMinWidth(dp(39, ctx));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            dark ? new int[]{0x33FF6B8A, 0x33A855F7, 0x3338BDF8}
                 : new int[]{0x26FF6B8A, 0x26A855F7, 0x2638BDF8});
        bg.setCornerRadius(dp(16, ctx));
        bg.setStroke(dp(1, ctx), dark ? Color.parseColor("#C084FC") : Color.parseColor("#A855F7"));
        tv.setBackground(bg);
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tvLp.topMargin = dp(13, ctx);
        fl.addView(tv, tvLp);
        tv.setOnClickListener(v -> showCreateDialog(null));
        return fl;
    }

    private static View spacer(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(1, ctx), dp(36, ctx)));
        return v;
    }

    private static String getLabelNameById(int id) {
        if (id == -1) return "\u5168\u90E8";
        if (id == 10000) return "\u7FA4\u804A";
        if (id == 10001) return "\u597D\u53CB";
        if (id == 10002) return "\u670D\u52A1\u53F7";
        List<LabelInfo> labels = ChatGroupHook.getAllLabels();
        if (labels != null) {
            for (LabelInfo li : labels) {
                if (li.labelId == id) return li.labelName;
            }
        }
        return null;
    }

    private static void installContextMenuHooks(ClassLoader cl) {
        tryInstallContextItemSelectedHook(cl);
        tryInstallR3Hook(cl);
        tryInstallSetTagHook(cl);
        tryInstallLauncherUIContextMenuHook(cl);
        tryInstallAdapterContextMenuHook(cl);
        hookClassBroadForDiagnosis(cl, "kc5.g4", "menuG4");
        hookClassBroadForDiagnosis(cl, "kc5.h4", "menuItemH4");
        tryInstallMenuItemGetItemHook(cl);
    }

    private static volatile long sLastCtxClickTime = 0;
    private static volatile int sLastCtxClickId = Integer.MIN_VALUE;

    /** 微信 8.0.76 点击分发：getItem(index) 是点击特征调用（渲染阶段只调 getTitle/size，不调 getItem）。
     *  在此捕获并直接执行分组动作，微信后续按 itemId 静默忽略即可。 */
    private static void tryInstallMenuItemGetItemHook(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("kc5.g4", cl);
            XposedBridge.hookAllMethods(cls, "getItem", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object r = p.getResult();
                        if (!(r instanceof MenuItem)) return;
                        MenuItem item = (MenuItem) r;
                        int id = item.getItemId();
                        if (!isCtxMenuId(id)) return;
                        long now = System.currentTimeMillis();
                        if (now - sLastCtxClickTime < 400 && sLastCtxClickId == id) return;
                        sLastCtxClickTime = now;
                        sLastCtxClickId = id;
                        String username = sPendingContextUsername;
                        logBoth("ctxMenu getItem clicked id=" + id + " user=" + username);
                        if (TextUtils.isEmpty(username)) return;
                        applyCtxItemAction(id, username);
                    } catch (Throwable e) { logBoth("ctxMenu getItem err: " + e.getMessage()); }
                }
            });
            logBoth("ctxMenu getItem hooked");
        } catch (Throwable e) { logBoth("ctxMenu getItem: " + e.getMessage()); }
    }

    /** 全量方法诊断 hook：dump 签名 + 捕获点击调用（仅日志，不处理） */
    private static void hookClassBroadForDiagnosis(ClassLoader cl, String className, String label) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            Class<?> c = cls;
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    final String mn = m.getName();
                    Class<?>[] pts = m.getParameterTypes();
                    StringBuilder sig = new StringBuilder();
                    for (int i = 0; i < pts.length; i++) { if (i > 0) sig.append(","); sig.append(pts[i].getSimpleName()); }
                    logBoth("diagSig " + label + "." + mn + "(" + sig + ")");
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                StringBuilder sb = new StringBuilder("diagCall ").append(label).append(".").append(mn).append("(");
                                for (int i = 0; i < p.args.length && i < 5; i++) {
                                    if (i > 0) sb.append(",");
                                    Object a = p.args[i];
                                    if (a instanceof MenuItem) sb.append("MI:").append(((MenuItem) a).getItemId());
                                    else if (a instanceof Integer) sb.append("i:").append(a);
                                    else if (a instanceof View) sb.append("V:").append(a.getClass().getSimpleName());
                                    else sb.append(a == null ? "null" : a.getClass().getSimpleName());
                                }
                                sb.append(")");
                                logBoth(sb.toString());
                            } catch (Throwable ignored) {}
                        }
                    });
                }
                c = c.getSuperclass();
            }
            logBoth("diag " + label + " hooked: " + cls.getName());
        } catch (Throwable e) { logBoth("diag " + label + " find: " + e.getMessage()); }
    }

    private static void tryInstallContextItemSelectedHook(ClassLoader cl) {
        logBoth("ctxItemSelected: start");
        hookCtxItemSelected(Activity.class, "Activity");
        try { hookCtxItemSelected(XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl), "LauncherUI"); }
        catch (Throwable e) { logBoth("ctxItemSelected LauncherUI find: " + e.getMessage()); }
        try { hookCtxItemSelected(XposedHelpers.findClass("com.tencent.mm.ui.conversation.r3", cl), "r3"); }
        catch (Throwable e) { logBoth("ctxItemSelected r3 find: " + e.getMessage()); }
        try { hookCtxItemSelected(XposedHelpers.findClass("com.tencent.mm.ui.conversation.MainUI", cl), "MainUI"); }
        catch (Throwable e) { logBoth("ctxItemSelected MainUI find: " + e.getMessage()); }
        hookCtxItemSelected(AbsListView.class, "AbsListView");
        tryInstallMenuItemInvokeHook(cl);
    }

    /** Hook MenuItemImpl.invoke() — 标准菜单点击统一入口，覆盖 androidx 与 framework 两条实现 */
    private static void tryInstallMenuItemInvokeHook(ClassLoader cl) {
        for (String cn : new String[]{
                "androidx.appcompat.view.menu.MenuItemImpl",
                "android.support.v7.view.menu.MenuItemImpl",
                "com.android.internal.view.menu.MenuItemImpl"}) {
            try {
                Class<?> cls = XposedHelpers.findClass(cn, cl);
                XposedBridge.hookAllMethods(cls, "invoke", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object mi = p.thisObject;
                            int id = (Integer) XposedHelpers.callMethod(mi, "getItemId");
                            logBoth("MenuItemImpl.invoke id=" + id + " cls=" + mi.getClass().getName());
                            if (isCtxMenuId(id) && handleCtxItemById(id, p)) return;
                        } catch (Throwable e) { logBoth("MenuItemImpl.invoke err: " + e.getMessage()); }
                    }
                });
                logBoth("MenuItemImpl.invoke hooked: " + cn);
            } catch (Throwable e) { logBoth("MenuItemImpl.invoke " + cn + ": " + e.getMessage()); }
        }
    }

    private static boolean isCtxMenuId(int itemId) {
        return itemId >= CTX_MENU_BASE && itemId <= CTX_MENU_BASE + 1000000;
    }

    /** 依据 itemId 直接处理（供 MenuItemImpl.invoke / 菜单类动态 hook 复用），无 MenuItem 对象路径 */
    private static boolean handleCtxItemById(int itemId, MethodHookParam p) {
        String username = sPendingContextUsername;
        logBoth("ctxItemById handle id=" + itemId + " user=" + username);
        if (TextUtils.isEmpty(username)) return false;
        applyCtxItemAction(itemId, username);
        p.setResult(true);
        return true;
    }

    /** 纯动作：执行菜单项对应的分组加/删/新建/更多，不做 setResult */
    private static void applyCtxItemAction(int itemId, String username) {
        int code = itemId - CTX_MENU_BASE;
        if (code == 999998) { showCreateDialog(username); return; }
        if (code == 999999) { showFullGroupDialogForUsername(username); return; }
        int labelId = code;
        int[] cur = ChatGroupHook.getContactLabelIds(username);
        boolean has = false;
        for (int id : cur) if (id == labelId) { has = true; break; }
        if (has) {
            ChatGroupHook.removeLabelFromContact(username, labelId);
            toast("\u5DF2\u4ECE\u300C" + getLabelNameById(labelId) + "\u300D\u79FB\u9664");
        } else {
            ChatGroupHook.addLabelToContact(username, labelId);
            toast("\u5DF2\u6DFB\u52A0\u5230\u300C" + getLabelNameById(labelId) + "\u300D");
        }
        refreshAll();
    }

    private static void hookCtxItemSelected(Class<?> cls, String tag) {
        try {
            XposedBridge.hookAllMethods(cls, "onContextItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        MenuItem item = (MenuItem) p.args[0];
                        logBoth("ctxItem hook[" + tag + "] item=" + (item != null ? item.getItemId() : "null")
                            + " this=" + p.thisObject.getClass().getName());
                        handleCtxItem(item, p);
                    } catch (Throwable e) { logBoth("ctxItem hook[" + tag + "] err: " + e.getMessage()); }
                }
            });
            logBoth("ctxItemSelected hooked: " + tag);
        } catch (Throwable e) { logBoth("ctxItemSelected " + tag + ": " + e.getMessage()); }
    }

    private static boolean handleCtxItem(MenuItem item, MethodHookParam p) {
        if (item == null) return false;
        int itemId = item.getItemId();
        if (!isCtxMenuId(itemId)) return false;
        String username = sPendingContextUsername;
        logBoth("ctxItem handle id=" + itemId + " user=" + username);
        if (TextUtils.isEmpty(username)) return false;
        int code = itemId - CTX_MENU_BASE;
        if (code == 999998) {
            showCreateDialog(username);
            p.setResult(true);
            return true;
        }
        if (code == 999999) {
            showFullGroupDialogForUsername(username);
            p.setResult(true);
            return true;
        }
        int labelId = code;
        int[] cur = ChatGroupHook.getContactLabelIds(username);
        boolean has = false;
        for (int id : cur) if (id == labelId) { has = true; break; }
        if (has) {
            ChatGroupHook.removeLabelFromContact(username, labelId);
            toast("\u5DF2\u4ECE\u300C" + getLabelNameById(labelId) + "\u300D\u79FB\u9664");
        } else {
            ChatGroupHook.addLabelToContact(username, labelId);
            toast("\u5DF2\u6DFB\u52A0\u5230\u300C" + getLabelNameById(labelId) + "\u300D");
        }
        refreshAll();
        p.setResult(true);
        return true;
    }

    private static final Set<String> sHookedMenuClasses = new HashSet<>();

    /** 动态 hook 微信自定义 ContextMenu 运行时类：捕获一切含 MenuItem 参数的点击分发方法 */
    private static void hookMenuClassDynamically(ContextMenu menu) {
        try {
            Class<?> cls = menu.getClass();
            while (cls != null && cls != Object.class) {
                String cn = cls.getName();
                if (sHookedMenuClasses.contains(cn)) { cls = cls.getSuperclass(); continue; }
                sHookedMenuClasses.add(cn);
                logBoth("menuHook scan class=" + cn + " super=" + (cls.getSuperclass() != null ? cls.getSuperclass().getName() : "null"));
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    boolean hasMenuItem = false;
                    for (Class<?> pt : pts) if (MenuItem.class.isAssignableFrom(pt)) { hasMenuItem = true; break; }
                    if (!hasMenuItem) continue;
                    final String mn = m.getName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                MenuItem item = null;
                                for (Object a : p.args) if (a instanceof MenuItem) { item = (MenuItem) a; break; }
                                int id = item != null ? item.getItemId() : -1;
                                logBoth("menuClick cls=" + cn + " m=" + mn + " id=" + id);
                                if (item != null && isCtxMenuId(id)) handleCtxItem(item, p);
                            } catch (Throwable e) { logBoth("menuClick err: " + e.getMessage()); }
                        }
                    });
                    logBoth("menuHook " + cn + "." + mn + " installed");
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable e) {
            logBoth("hookMenuClassDynamically err: " + e.getMessage());
        }
    }

    private static void tryInstallAdapterContextMenuHook(ClassLoader cl) {
        try {
            Class<?> adapterClass = XposedHelpers.findClass("fh5.w0", cl);
            XposedBridge.hookAllMethods(adapterClass, "onCreateContextMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        ContextMenu menu = (ContextMenu) p.args[0];
                        View v = (View) p.args[1];
                        String username = extractUsernameFromView(v);
                        logBoth("adapter ctxMenu username=" + username);
                        if (!TextUtils.isEmpty(username)) addGroupMenuItems(menu, username);
                    } catch (Throwable e) { logBoth("adapter ctxMenu err: " + e.getMessage()); }
                }
            });
            logBoth("adapter ctxMenu hooked");
        } catch (Throwable e) { logBoth("adapter ctxMenu: " + e.getMessage()); }
    }

    private static void tryInstallR3Hook(ClassLoader cl) {
        try {
            Class<?> r3 = XposedHelpers.findClass("com.tencent.mm.ui.conversation.r3", cl);
            XposedBridge.hookAllMethods(r3, "onCreateContextMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        String username = (String) XposedHelpers.getObjectField(p.thisObject, "g");
                        logBoth("r3 ctxMenu username=" + username);
                        addGroupMenuItems((ContextMenu) p.args[0], username);
                    } catch (Throwable e) { logBoth("r3 err: " + e.getMessage()); }
                }
            });
            logBoth("r3 hooked");
        } catch (Throwable e) { logBoth("r3: " + e.getMessage()); }
        // Try alternative class names for WeChat 8.0.56
        for (String cn : new String[]{"q3", "s3", "t3", "g3"}) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.tencent.mm.ui.conversation." + cn, cl);
                XposedBridge.hookAllMethods(cls, "onCreateContextMenu", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String username = "?";
                            try { username = (String) XposedHelpers.getObjectField(p.thisObject, "g"); } catch (Throwable ignored) {}
                            try { username = (String) XposedHelpers.getObjectField(p.thisObject, "h"); } catch (Throwable ignored) {}
                            logBoth(cn + " ctxMenu username=" + username);
                            addGroupMenuItems((ContextMenu) p.args[0], username);
                        } catch (Throwable e) { logBoth(cn + " err: " + e.getMessage()); }
                    }
                });
                logBoth(cn + " ctxMenu hooked");
            } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void tryInstallSetTagHook(ClassLoader cl) {
        try {
            Class<?> itemView = XposedHelpers.findClass(
                "com.tencent.mm.ui.conversation.ConversationFolderItemView", cl);
            XposedBridge.hookAllMethods(itemView, "setTag", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object tag = p.args[0];
                        if (tag instanceof String && ((String) tag).length() > 3)
                            sPendingUsername = (String) tag;
                    } catch (Throwable ignored) {}
                }
            });
            logBoth("setTag hooked");
        } catch (Throwable e) { logBoth("setTag: " + e.getMessage()); }
    }

    private static void tryInstallLauncherUIContextMenuHook(ClassLoader cl) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl);
            XposedBridge.hookAllMethods(launcherUI, "onCreateContextMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        ContextMenu menu = (ContextMenu) p.args[0];
                        View view = (View) p.args[1];
                        String username = sPendingUsername;
                        if (TextUtils.isEmpty(username)) username = extractUsernameFromView(view);
                        if (!TextUtils.isEmpty(username)) addGroupMenuItems(menu, username);
                    } catch (Throwable e) { logBoth("ctxMenu: " + e.getMessage()); }
                }
            });
            logBoth("LauncherUI.ctxMenu hooked");
        } catch (Throwable e) { logBoth("ctxMenu: " + e.getMessage()); }
    }

    private static String extractUsernameFromView(View view) {
        if (view == null) return null;
        try {
            Object tag = view.getTag();
            if (tag instanceof String && ((String) tag).length() > 3) return (String) tag;
            if (view instanceof ViewGroup)
                for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                    Object ct = ((ViewGroup) view).getChildAt(i).getTag();
                    if (ct instanceof String && ((String) ct).length() > 3) return (String) ct;
                }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void addGroupMenuItems(ContextMenu menu, String username) {
        logBoth("addMenu start user=" + username + " menu=" + (menu != null ? menu.getClass().getName() : "null"));
        if (TextUtils.isEmpty(username)) { logBoth("addMenu return: empty user"); return; }
        final Context ctx = sCurrentActivity;
        if (ctx == null) { logBoth("addMenu return: sCurrentActivity null"); return; }
        sPendingContextUsername = username;
        hookMenuClassDynamically(menu);
        int[] curLabels = ChatGroupHook.getContactLabelIds(username);
        Set<Integer> curSet = new HashSet<>();
        for (int id : curLabels) curSet.add(id);
        List<LabelInfo> all = ChatGroupHook.getAllLabels();
        logBoth("addMenu labels=" + all.size() + " cur=" + curLabels.length);
        int base = CTX_MENU_BASE;
        if (all.isEmpty()) {
            MenuItem mi = menu.add(Menu.NONE, CTX_MENU_NEW, Menu.NONE, "\u25B8 \u6DFB\u52A0\u5230\u5206\u7EC4 (\u65E0\u5206\u7EC4)");
            mi.setOnMenuItemClickListener(item -> { logBoth("MENUCLICK new-empty user=" + username); showCreateDialog(username); return true; });
            return;
        }
        int max = Math.min(all.size(), 8);
        for (int i = 0; i < max; i++) {
            final LabelInfo label = all.get(i);
            final boolean has = curSet.contains(label.labelId);
            final int itemId = base + label.labelId;
            MenuItem mi = menu.add(Menu.NONE, itemId, Menu.NONE, (has ? "\u2611 " : "\u2610 ") + label.labelName);
            logBoth("addMenu item id=" + itemId + " name=" + label.labelName + " has=" + has + " miClass=" + (mi != null ? mi.getClass().getName() : "null"));
            mi.setOnMenuItemClickListener(item -> {
                logBoth("MENUCLICK label=" + label.labelName + " has=" + has + " user=" + username);
                if (has) { ChatGroupHook.removeLabelFromContact(username, label.labelId); toast("\u5DF2\u4ECE\u300C" + label.labelName + "\u300D\u79FB\u9664"); }
                else { ChatGroupHook.addLabelToContact(username, label.labelId); toast("\u5DF2\u6DFB\u52A0\u5230\u300C" + label.labelName + "\u300D"); }
                refreshAll(); return true;
            });
        }
        if (all.size() > max) {
            MenuItem mi = menu.add(Menu.NONE, CTX_MENU_MORE, Menu.NONE, "\u66F4\u591A\u5206\u7EC4...");
            mi.setOnMenuItemClickListener(item -> { logBoth("MENUCLICK more user=" + username); showFullGroupDialogForUsername(username); return true; });
        }
        MenuItem mi = menu.add(Menu.NONE, CTX_MENU_NEW, Menu.NONE, "\uFF0B \u65B0\u5EFA\u5206\u7EC4");
        mi.setOnMenuItemClickListener(item -> { logBoth("MENUCLICK new user=" + username); showCreateDialog(username); return true; });
        logBoth("addMenu done added=" + max);
    }

    private static void showFullGroupDialogForUsername(String username) {
        Set<Integer> curSet = new HashSet<>();
        for (int id : ChatGroupHook.getContactLabelIds(username)) curSet.add(id);
        showFullGroupDialog(username, curSet, ChatGroupHook.getAllLabels());
    }

    private static void showFullGroupDialog(String username, Set<Integer> curSet, List<LabelInfo> all) {
        Activity ctx = sCurrentActivity;
        if (ctx == null) return;
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) { names[i] = all.get(i).labelName; checked[i] = curSet.contains(all.get(i).labelId); }
        showThemedMultiChoice(ctx, "\u7BA1\u7406\u5206\u7EC4", names, checked,
            (dialog, which, isC) -> {
                LabelInfo label = all.get(which);
                if (isC) ChatGroupHook.addLabelToContact(username, label.labelId);
                else ChatGroupHook.removeLabelFromContact(username, label.labelId);
            },
            "\u5B8C\u6210", null,
            "\uFF0B \u65B0\u5EFA", (d, w) -> showCreateDialog(username));
    }

    private static void showCreateDialog(String username) {
        Activity ctx = sCurrentActivity;
        if (ctx == null) return;
        EditText input = themedEdit(ctx, "\u8F93\u5165\u65B0\u5206\u7EC4\u540D\u79F0");
        showThemed(new AlertDialog.Builder(ctx).setTitle("\u65B0\u5EFA\u5206\u7EC4").setView(input)
            .setPositiveButton("\u521B\u5EFA", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    LabelInfo created = ChatGroupHook.createLabel(name);
                    if (created != null) { if (username != null) ChatGroupHook.addLabelToContact(username, created.labelId); refreshAll(); toast("\u5DF2\u521B\u5EFA\u300C" + name + "\u300D"); }
                    else toast("\u521B\u5EFA\u5931\u8D25");
                }
            }).setNegativeButton("\u53D6\u6D88", null));
    }

    private static boolean isBuiltInLabel(int labelId) {
        return labelId == 10000 || labelId == 10001 || labelId == 10002;
    }

    private static void showLabelManage(Context ctx, int labelId, String labelName, View anchor) {
        int cc = ChatGroupHook.getContactsByLabelId(labelId).size();
        boolean builtIn = isBuiltInLabel(labelId);
        String[] items;
        if (builtIn) {
            items = new String[]{
                "\u67E5\u770B (" + cc + "\u4EBA)",
                "\u6539\u540D",
                "\u6DFB\u52A0",
                "\u6392\u5E8F"
            };
        } else {
            items = new String[]{
                "\u67E5\u770B (" + cc + "\u4EBA)",
                "\u6539\u540D",
                "\u6DFB\u52A0",
                "\u5220\u9664\u5206\u7EC4",
                "\u5408\u5E76\u5230\u5176\u4ED6\u5206\u7EC4",
                "\u6392\u5E8F"
            };
        }
        showAnchorMenu(ctx, anchor, "\u7BA1\u7406: " + labelName, items, (d, which) -> {
            if (builtIn) {
                switch (which) {
                    case 0: showLabelContacts(ctx, labelId, labelName); break;
                    case 1: showLabelRename(ctx, labelId, labelName); break;
                    case 2: showAddContactsToLabel(ctx, labelId, labelName); break;
                    case 3: showSortLabels(ctx); break;
                }
            } else {
                switch (which) {
                    case 0: showLabelContacts(ctx, labelId, labelName); break;
                    case 1: showLabelRename(ctx, labelId, labelName); break;
                    case 2: showAddContactsToLabel(ctx, labelId, labelName); break;
                    case 3: showThemed(new AlertDialog.Builder(ctx).setTitle("\u786E\u8BA4\u5220\u9664")
                        .setMessage("\u5220\u9664\u300C" + labelName + "\u300D\uFF1F\n\u8054\u7CFB\u4EBA\u5173\u8054\u5C06\u88AB\u6E05\u9664\u3002")
                        .setPositiveButton("\u5220\u9664", (dd, ww) -> { boolean ok = ChatGroupHook.deleteLabel(String.valueOf(labelId)); refreshAll(); toast(ok ? "\u5DF2\u5220\u9664" : "\u5220\u9664\u5931\u8D25"); }).setNegativeButton("\u53D6\u6D88", null)); break;
                    case 4: List<LabelInfo> labels = ChatGroupHook.getAllLabels();
                        String[] ns = new String[labels.size()];
                        for (int i = 0; i < labels.size(); i++) ns[i] = labels.get(i).labelName;
                        showThemedItems(ctx, "\u5408\u5E76\u5230...", ns, (dd, i2) -> {
                            if (labels.get(i2).labelId != labelId) { BatchOperator.mergeLabels(labelId, labels.get(i2).labelId); refreshAll(); }
                        }); break;
                    case 5: showSortLabels(ctx); break;
                }
            }
        });
    }

    private static void showLabelContacts(Context ctx, int labelId, String labelName) {
        List<String> cs = ChatGroupHook.getContactsByLabelId(labelId);
        final List<String> users = new ArrayList<>();
        int cap = Math.min(cs.size(), 50);
        for (int i = 0; i < cap; i++) users.add(cs.get(i));
        final boolean dark = isDarkMode(ctx);
        final int avSize = dp(40, ctx);
        ListView lv = new ListView(ctx);
        lv.setDivider(null);
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return users.size(); }
            @Override public Object getItem(int i) { return users.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row;
                if (convert instanceof LinearLayout) {
                    row = (LinearLayout) convert;
                } else {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(16, ctx), dp(5, ctx), dp(16, ctx), dp(5, ctx));
                    FrameLayout av = new FrameLayout(ctx);
                    av.setLayoutParams(new LinearLayout.LayoutParams(avSize, avSize));
                    TextView initial = new TextView(ctx);
                    initial.setGravity(Gravity.CENTER);
                    initial.setTextSize(15);
                    initial.setTextColor(Color.WHITE);
                    initial.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    av.addView(initial);
                    ImageView iv = new ImageView(ctx);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    av.addView(iv);
                    row.addView(av);
                    TextView tv = new TextView(ctx);
                    tv.setTextSize(15);
                    tv.setTextColor(dark ? Color.parseColor("#E4E4E8") : Color.parseColor("#1D1D1F"));
                    tv.setPadding(dp(12, ctx), 0, 0, 0);
                    row.addView(tv);
                }
                String u = users.get(i);
                FrameLayout av = (FrameLayout) row.getChildAt(0);
                TextView initial = (TextView) av.getChildAt(0);
                ImageView iv = (ImageView) av.getChildAt(1);
                TextView tv = (TextView) row.getChildAt(1);
                String nick = resolveDisplayName(u);
                if (nick == null) nick = "";
                String init = (nick.length() > 0) ? nick.substring(0, 1) : "?";
                initial.setText(init);
                initial.setBackground(makeAvatarBg(u, avSize));
                iv.setImageDrawable(null);
                tv.setText(nick);
                loadAvatarAsync(ctx, iv, u, avSize);
                final String uname = u;
                final String unick = nick;
                row.setOnLongClickListener(v -> {
                    showThemed(new AlertDialog.Builder(ctx)
                        .setTitle("\u79FB\u9664\u8054\u7CFB\u4EBA")
                        .setMessage("\u5C06\u300C" + unick + "\u300D\u4ECE\u300C" + labelName + "\u300D\u4E2D\u79FB\u9664\uFF1F")
                        .setPositiveButton("\u79FB\u9664", (dd, ww) -> {
                            boolean ok = ChatGroupHook.removeLabelFromContact(uname, labelId);
                            toast(ok ? "\u5DF2\u4ECE\u300C" + labelName + "\u300D\u79FB\u9664" : "\u79FB\u9664\u5931\u8D25");
                            users.remove(uname);
                            ((BaseAdapter) lv.getAdapter()).notifyDataSetChanged();
                            refreshAll();
                        })
                        .setNegativeButton("\u53D6\u6D88", null));
                    return true;
                });
                return row;
            }
        });
        String title = cs.size() > 50 ? (labelName + "\uFF08\u5171 " + cs.size() + " \u4EBA\uFF09") : labelName;
        showThemed(new AlertDialog.Builder(ctx).setTitle(title).setView(lv).setPositiveButton("\u5173\u95ED", null));
    }

    private static GradientDrawable makeAvatarBg(String u, int size) {
        int[] candy = {0xFFFF6B8A, 0xFFA855F7, 0xFF38BDF8, 0xFFF59E0B, 0xFF10B981, 0xFF6366F1};
        int idx = Math.abs(u == null ? 0 : u.hashCode()) % candy.length;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(candy[idx]);
        gd.setShape(GradientDrawable.OVAL);
        return gd;
    }

    private static void loadAvatarAsync(Context ctx, ImageView iv, String username, int size) {
        new Thread(() -> {
            try {
                Bitmap bm = AvatarHelper.loadAvatar(username, size);
                if (bm != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bm, size, size, true);
                    iv.post(() -> iv.setImageBitmap(scaled));
                }
            } catch (Throwable ignored) {}
        }, "leshao-avatar").start();
    }

    private static String resolveDisplayName(String u) {
        if (u == null || u.isEmpty()) return u;
        ContactCard card = ContactRepository.findByUsername(u);
        if (card != null && card.nickname != null && !card.nickname.isEmpty()) {
            return card.nickname;
        }
        return u;
    }

    private static void showLabelRename(Context ctx, int labelId, String labelName) {
        EditText et = themedEdit(ctx, labelName); et.setText(labelName);
        showThemed(new AlertDialog.Builder(ctx).setTitle("\u91CD\u547D\u540D").setView(et)
            .setPositiveButton("\u786E\u5B9A", (dd, ww) -> {
                String n = et.getText().toString().trim();
                if (!TextUtils.isEmpty(n)) { boolean ok = ChatGroupHook.renameLabel(String.valueOf(labelId), n); refreshAll(); toast(ok ? "\u5DF2\u91CD\u547D\u540D" : "\u91CD\u547D\u540D\u5931\u8D25"); }
            }).setNegativeButton("\u53D6\u6D88", null));
    }

    private static void showAddContactsToLabel(Context ctx, int labelId, String labelName) {
        EditText et = themedEdit(ctx, "\u8F93\u5165\u7528\u6237\u540D\uFF0C\u591A\u4E2A\u7528\u9017\u53F7\u5206\u9694\uFF0C\u5982 wxid_xxx");
        et.setMinLines(3);
        showThemed(new AlertDialog.Builder(ctx).setTitle("\u6DFB\u52A0\u8054\u7CFB\u4EBA\u5230\u300C" + labelName + "\u300D")
            .setView(et)
            .setPositiveButton("\u6DFB\u52A0", (dd, ww) -> {
                String input = et.getText().toString().trim();
                if (TextUtils.isEmpty(input)) return;
                String[] usernames = input.split("[,;\\s\\n]+");
                int added = 0;
                for (String u : usernames) {
                    if (u.isEmpty()) continue;
                    if (ChatGroupHook.addLabelToContact(u.trim(), labelId)) added++;
                }
                refreshAll();
                toast("\u5DF2\u6DFB\u52A0 " + added + "/" + usernames.length + " \u4EBA");
            }).setNegativeButton("\u53D6\u6D88", null));
    }

    private static void showSortLabels(Context ctx) {
        List<LabelInfo> labels = ChatGroupHook.getAllLabels();
        if (labels == null || labels.isEmpty()) { toast("\u6CA1\u6709\u53EF\u6392\u5E8F\u7684\u5206\u7EC4"); return; }
        // apply saved order
        List<Integer> order = ShadowLabelStore.getLabelOrder();
        Map<Integer, LabelInfo> idMap = new LinkedHashMap<>();
        for (LabelInfo l : labels) idMap.put(l.labelId, l);
        List<LabelInfo> sorted = new ArrayList<>();
        for (int id : order) {
            LabelInfo li = idMap.remove(id);
            if (li != null) sorted.add(li);
        }
        sorted.addAll(idMap.values());

        String[] names = new String[sorted.size()];
        int[] labelIds = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) { names[i] = sorted.get(i).labelName; labelIds[i] = sorted.get(i).labelId; }

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16, ctx), dp(8, ctx), dp(16, ctx), dp(8, ctx));

        List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4, ctx), dp(8, ctx), dp(4, ctx), dp(8, ctx));
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView upBtn = new TextView(ctx);
            upBtn.setText("\u25B2");
            upBtn.setTextSize(16);
            upBtn.setPadding(dp(12, ctx), dp(4, ctx), dp(12, ctx), dp(4, ctx));
            upBtn.setTextColor(idx == 0 ? Color.GRAY : themeAccent());
            upBtn.setOnClickListener(v -> {
                if (idx > 0) {
                    String tmpN = names[idx]; names[idx] = names[idx - 1]; names[idx - 1] = tmpN;
                    int tmpId = labelIds[idx]; labelIds[idx] = labelIds[idx - 1]; labelIds[idx - 1] = tmpId;
                    rebuildSortRows(ctx, container, names, labelIds);
                }
            });
            row.addView(upBtn);

            TextView downBtn = new TextView(ctx);
            downBtn.setText("\u25BC");
            downBtn.setTextSize(16);
            downBtn.setPadding(dp(12, ctx), dp(4, ctx), dp(12, ctx), dp(4, ctx));
            downBtn.setTextColor(idx == names.length - 1 ? Color.GRAY : themeAccent());
            downBtn.setOnClickListener(v -> {
                if (idx < names.length - 1) {
                    String tmpN = names[idx]; names[idx] = names[idx + 1]; names[idx + 1] = tmpN;
                    int tmpId = labelIds[idx]; labelIds[idx] = labelIds[idx + 1]; labelIds[idx + 1] = tmpId;
                    rebuildSortRows(ctx, container, names, labelIds);
                }
            });
            row.addView(downBtn);

            TextView nameTv = new TextView(ctx);
            nameTv.setText(names[i]);
            nameTv.setTextSize(15);
            nameTv.setTextColor(themeTitle());
            nameTv.setPadding(dp(12, ctx), 0, 0, 0);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nameTv.setLayoutParams(nlp);
            row.addView(nameTv);
            rows.add(nameTv);
            container.addView(row);
        }

        AlertDialog dlg = showThemed(new AlertDialog.Builder(ctx)
            .setTitle("\u6392\u5E8F\u5206\u7EC4 (\u70B9\u51FB\u7BAD\u5934\u8C03\u6574\u987A\u5E8F)")
            .setView(container)
            .setPositiveButton("\u2705 \u4FDD\u5B58", (dd, ww) -> {
                List<Integer> newOrder = new ArrayList<>();
                for (int id : labelIds) newOrder.add(id);
                ShadowLabelStore.saveLabelOrder(newOrder);
                refreshAll();
                toast("\u6392\u5E8F\u5DF2\u4FDD\u5B58");
            })
            .setNegativeButton("\u53D6\u6D88", null));
    }

    private static void rebuildSortRows(Context ctx, LinearLayout container, String[] names, int[] labelIds) {
        container.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4, ctx), dp(8, ctx), dp(4, ctx), dp(8, ctx));
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView upBtn = new TextView(ctx);
            upBtn.setText("\u25B2");
            upBtn.setTextSize(16);
            upBtn.setPadding(dp(12, ctx), dp(4, ctx), dp(12, ctx), dp(4, ctx));
            upBtn.setTextColor(idx == 0 ? Color.GRAY : themeAccent());
            upBtn.setOnClickListener(v -> {
                if (idx > 0) {
                    String tmpN = names[idx]; names[idx] = names[idx - 1]; names[idx - 1] = tmpN;
                    int tmpId = labelIds[idx]; labelIds[idx] = labelIds[idx - 1]; labelIds[idx - 1] = tmpId;
                    rebuildSortRows(ctx, container, names, labelIds);
                }
            });
            row.addView(upBtn);

            TextView downBtn = new TextView(ctx);
            downBtn.setText("\u25BC");
            downBtn.setTextSize(16);
            downBtn.setPadding(dp(12, ctx), dp(4, ctx), dp(12, ctx), dp(4, ctx));
            downBtn.setTextColor(idx == names.length - 1 ? Color.GRAY : themeAccent());
            downBtn.setOnClickListener(v -> {
                if (idx < names.length - 1) {
                    String tmpN = names[idx]; names[idx] = names[idx + 1]; names[idx + 1] = tmpN;
                    int tmpId = labelIds[idx]; labelIds[idx] = labelIds[idx + 1]; labelIds[idx + 1] = tmpId;
                    rebuildSortRows(ctx, container, names, labelIds);
                }
            });
            row.addView(downBtn);

            TextView nameTv = new TextView(ctx);
            nameTv.setText(names[i]);
            nameTv.setTextSize(15);
            nameTv.setTextColor(themeTitle());
            nameTv.setPadding(dp(12, ctx), 0, 0, 0);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nameTv.setLayoutParams(nlp);
            row.addView(nameTv);
            container.addView(row);
        }
    }

    private static final Handler sRefreshHandler = new Handler(Looper.getMainLooper());

    public static void refreshTagData() {
        sRefreshHandler.removeCallbacksAndMessages(null);
        sRefreshHandler.postDelayed(ChatGroupUiInjector::refreshAll, 100);
    }

    private static void toast(String msg) {
        Activity ctx = sCurrentActivity;
        if (ctx == null) return;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    private static int dp(int dpi, Context ctx) {
        return (int) (dpi * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 统一模块配色 (浅色/暗色) ====================
    private static int themeTitle() { return isDarkMode(sCurrentActivity) ? 0xFFE4E4E8 : 0xFF1D1D1F; }
    private static int themeBody()   { return isDarkMode(sCurrentActivity) ? 0xFFB0B0B8 : 0xFF565659; }
    private static int themeNote()   { return isDarkMode(sCurrentActivity) ? 0xFF707079 : 0xFF949499; }
    private static int themeAccent() { return isDarkMode(sCurrentActivity) ? 0xFFC084FC : 0xFFA855F7; }
    private static int themeBg()     { return isDarkMode(sCurrentActivity) ? 0xFF2A2A2E : 0xFFFFFFFF; }
    private static int themeCard()   { return isDarkMode(sCurrentActivity) ? 0xFF1E1E22 : 0xFFF5F5F5; }

    private static void themeDialog(AlertDialog d) {
        try {
            Context ctx = d.getContext();
            Window w = d.getWindow();
            if (w != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(themeBg());
                bg.setCornerRadius(dp(18, ctx));
                w.setBackgroundDrawable(bg);
            }
            TextView title = null;
            try {
                int titleId = ctx.getResources().getIdentifier("alertTitle", "id", "android");
                if (titleId != 0) title = d.findViewById(titleId);
            } catch (Throwable ignored) {}
            if (title != null) title.setTextColor(themeTitle());
            TextView msg = d.findViewById(android.R.id.message);
            if (msg != null) msg.setTextColor(themeBody());
            Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
            Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (pos != null) pos.setTextColor(themeAccent());
            if (neg != null) neg.setTextColor(themeBody());
            if (neu != null) neu.setTextColor(themeAccent());
        } catch (Throwable ignored) {}
    }

    private static AlertDialog showThemed(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        d.show();
        themeDialog(d);
        return d;
    }

    private static EditText themedEdit(Context ctx, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(themeNote());
        et.setTextColor(themeTitle());
        et.setPadding(40, 30, 40, 30);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(themeCard());
        bg.setCornerRadius(dp(10, ctx));
        et.setBackground(bg);
        return et;
    }

    private static AlertDialog showThemedItems(Context ctx, String title, String[] items, DialogInterface.OnClickListener listener) {
        final int textColor = themeTitle();
        ListView lv = new ListView(ctx);
        lv.setDivider(null);
        lv.setPadding(0, dp(4, ctx), 0, dp(4, ctx));
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.length; }
            @Override public Object getItem(int i) { return items[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                TextView tv;
                if (cv instanceof TextView) tv = (TextView) cv;
                else {
                    tv = new TextView(ctx);
                    tv.setTextSize(16);
                    tv.setPadding(dp(20, ctx), dp(14, ctx), dp(20, ctx), dp(14, ctx));
                }
                tv.setText(items[i]);
                tv.setTextColor(textColor);
                return tv;
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> { if (listener != null) listener.onClick(null, pos); });
        return showThemed(new AlertDialog.Builder(ctx).setTitle(title).setView(lv));
    }

    private static volatile PopupWindow sAnchorPopup;

    private static void dismissAnchorPopup() {
        try { if (sAnchorPopup != null && sAnchorPopup.isShowing()) sAnchorPopup.dismiss(); } catch (Throwable ignored) {}
        sAnchorPopup = null;
    }

    /** 锚定到按钮下方的主题化菜单（PopupWindow，宽度自适应文字，去掉右侧空白） */
    private static void showAnchorMenu(Context ctx, View anchor, String title, String[] items, DialogInterface.OnClickListener listener) {
        dismissAnchorPopup();
        final boolean dark = isDarkMode(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(10, ctx), dp(10, ctx), dp(10, ctx), dp(10, ctx));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(themeBg());
        bg.setCornerRadius(dp(14, ctx));
        bg.setStroke(dp(1, ctx), dark ? Color.parseColor("#3A3A3E") : Color.parseColor("#E5E5EA"));
        container.setBackground(bg);

        if (title != null) {
            TextView tt = new TextView(ctx);
            tt.setText(title);
            tt.setTextSize(13);
            tt.setTextColor(themeNote());
            tt.setPadding(dp(14, ctx), dp(8, ctx), dp(14, ctx), dp(6, ctx));
            container.addView(tt);
        }
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView it = new TextView(ctx);
            it.setText(items[i]);
            it.setTextSize(15);
            it.setTextColor(themeTitle());
            it.setGravity(Gravity.CENTER_VERTICAL);
            it.setPadding(dp(14, ctx), dp(12, ctx), dp(14, ctx), dp(12, ctx));
            it.setOnClickListener(v -> {
                dismissAnchorPopup();
                if (listener != null) listener.onClick(null, idx);
            });
            container.addView(it);
        }

        final PopupWindow pw = new PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        pw.setOnDismissListener(() -> sAnchorPopup = null);
        sAnchorPopup = pw;
        try {
            pw.showAsDropDown(anchor, 0, dp(4, ctx));
        } catch (Throwable e) {
            pw.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        }
    }

    private static AlertDialog showThemedMultiChoice(Context ctx, String title, String[] items, boolean[] checked,
            DialogInterface.OnMultiChoiceClickListener listener, String positiveText, DialogInterface.OnClickListener positive,
            String neutralText, DialogInterface.OnClickListener neutral) {
        final int textColor = themeTitle();
        final boolean[] chk = checked;
        ListView lv = new ListView(ctx);
        lv.setDivider(null);
        lv.setPadding(0, dp(4, ctx), 0, dp(4, ctx));
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return items.length; }
            @Override public Object getItem(int i) { return items[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                TextView tv;
                if (cv instanceof TextView) tv = (TextView) cv;
                else {
                    tv = new TextView(ctx);
                    tv.setTextSize(16);
                    tv.setPadding(dp(20, ctx), dp(14, ctx), dp(20, ctx), dp(14, ctx));
                }
                tv.setText((chk[i] ? "\u2611 " : "\u2610 ") + items[i]);
                tv.setTextColor(textColor);
                return tv;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            chk[pos] = !chk[pos];
            if (listener != null) listener.onClick(null, pos, chk[pos]);
            adapter.notifyDataSetChanged();
        });
        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle(title).setView(lv);
        if (positiveText != null) b.setPositiveButton(positiveText, positive);
        if (neutralText != null) b.setNeutralButton(neutralText, neutral);
        return showThemed(b);
    }

    private static void logBoth(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
        LogWriter.log(TAG, msg);
    }
}
