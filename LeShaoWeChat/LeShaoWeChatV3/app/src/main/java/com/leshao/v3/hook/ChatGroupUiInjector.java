package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ShadowLabelStore;
import com.leshao.v3.hook.model.LabelInfo;
import com.leshao.v3.ui.AppColors;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;
import java.lang.ref.WeakReference;

public class ChatGroupUiInjector {

    private static final String TAG = "ChatGroupUiInjector";
    private static final String BAR_TAG = "CHAT_LABEL_TAG_BAR";

    private static volatile LinearLayout sTagContainer;
    private static volatile int sSelectedLabelId = -1;
    private static volatile WeakReference<Activity> sCurrentActivity;
    private static volatile List<LabelInfo> sLabels;
    private static volatile long sLastLabelRefresh;
    private static volatile ClassLoader sClassLoader;
    private static volatile java.util.Map<Integer, TextView> sChipTvById =
        new java.util.LinkedHashMap<>();

    public static void hook(final ClassLoader cl) {
        logBoth("hook() start");
        sClassLoader = cl;
        ClassLoader tkCL = VersionCompat.findTinkerClassLoader(cl);
        if (tkCL != null) {
            logBoth("hook: using Tinker ClassLoader");
        }
        final ClassLoader effectiveCL = tkCL != null ? tkCL : cl;
        try { installTagFilterBar(effectiveCL); } catch (Throwable e) { logBoth("tag err: " + e.getMessage()); }
        logBoth("hook() done");
    }

    private static boolean sHeaderAdded = false;
    private static int sInjectRetryCount = 0;

    private static View sTagBarView;

    private static void installTagFilterBar(final ClassLoader cl) {
        // Hook s5.h to capture the ConversationListView reference (fallback)
        try {
            Class<?> s5 = XposedHelpers.findClass("com.tencent.mm.ui.conversation.s5", cl);
            XposedBridge.hookAllMethods(s5, "h", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    logBoth("s5.h FIRED");
                    try {
                        Object convList = XposedHelpers.getObjectField(p.thisObject, "f");
                        if (convList == null) return;
                        View view = (View) convList;
                        sCurrentActivity = new WeakReference<>((Activity) view.getContext());
                        sConvListView = view;
                    } catch (Throwable e) {
                        logBoth("s5.h err: " + e.getMessage());
                    }
                }
            });
            logBoth("s5.h hooked");
        } catch (Throwable e) { logBoth("s5: " + e.getMessage()); }

        // Primary: hook Activity.onResume to detect LauncherUI (reliable, unlike s5.h/MainUI.onResume)
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!"com.tencent.mm.ui.LauncherUI".equals(param.thisObject.getClass().getName())) return;
                        logBoth("Activity.onResume -> LauncherUI detected");
                        final Activity activity = (Activity) param.thisObject;
                        sCurrentActivity = new WeakReference<>(activity);
                        // Try to find adapter from MainUI Fragment (8.0.78: this.w = jo5.l0 会话列表适配器)
                        try {
                            Object mainUI = XposedHelpers.callMethod(activity, "getSupportFragmentManager");
                            if (mainUI != null) {
                                Object frag = XposedHelpers.callMethod(mainUI, "findFragmentByTag", "com.tencent.mm.ui.conversation.MainUI");
                                if (frag == null) {
                                    java.util.List frags = (java.util.List) XposedHelpers.callMethod(mainUI, "getFragments");
                                    if (frags != null) {
                                        for (Object f : frags) {
                                            if (f != null && "com.tencent.mm.ui.conversation.MainUI".equals(f.getClass().getName())) {
                                                frag = f;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (frag != null) {
                                    try {
                                        Object adapter = XposedHelpers.getObjectField(frag, "w");
                                        logBoth("MainUI.w(jo5.l0)=" + (adapter != null ? adapter.getClass().getName() : "null"));
                                        if (adapter != null) {
                                            ConversationFilter.install(cl, adapter, null);
                                        }
                                    } catch (Throwable e) {
                                        logBoth("MainUI.w err: " + e.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            logBoth("find MainUI fragment err: " + e.getMessage());
                        }
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                injectHeaderToConversationList();
                            } catch (Throwable e) {
                                logBoth("injectHeader err: " + e.getMessage());
                            }
                        });
                    } catch (Throwable e) {
                        logBoth("Activity.onResume cb err: " + e.getMessage());
                    }
                }
            });
            logBoth("Activity.onResume hook installed (LauncherUI)");
        } catch (Throwable e) { logBoth("Activity.onResume hook failed: " + e.getMessage()); }

        // Fallback: hook MainUI.onResume (kept in case Activity.onResume fails)
        try {
            final Class<?> mainUIConv = XposedHelpers.findClass("com.tencent.mm.ui.conversation.MainUI", cl);
            XC_MethodHook mainUIHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String fragClsName = param.thisObject.getClass().getName();
                    if (!"com.tencent.mm.ui.conversation.MainUI".equals(fragClsName)) return;
                    logBoth("MainUI.onResume detected");
                     try {
                         sCurrentActivity = new WeakReference<>((Activity) XposedHelpers.callMethod(param.thisObject, "getActivity"));
                    } catch (Throwable ignored) {}
                    try {
                        Object adapter = XposedHelpers.getObjectField(param.thisObject, "w");
                        if (adapter != null) {
                            ConversationFilter.install(cl, adapter, null);
                        }
                    } catch (Throwable ignored) {}
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            injectHeaderToConversationList();
                        } catch (Throwable e) {
                            logBoth("injectHeader err: " + e.getMessage());
                        }
                    });
                }
            };
            XposedBridge.hookAllMethods(mainUIConv, "onResume", mainUIHook);
            logBoth("MainUI.onResume hook installed (fallback)");
        } catch (Throwable e) {
            logBoth("MainUI Fragment hook failed: " + e.getMessage());
        }

        // 文档核心注入点：MainUI.w0(Bundle) after -> this.o(u5).addHeaderView(按钮栏)；缓存 this.w(jo5.l0)
        try {
            final Class<?> mainUIConv = XposedHelpers.findClass("com.tencent.mm.ui.conversation.MainUI", cl);
            XposedBridge.hookAllMethods(mainUIConv, "w0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object thisObj = param.thisObject;
                        // 缓存 this.w = jo5.l0 会话列表适配器/控制器
                        try {
                            Object w = XposedHelpers.getObjectField(thisObj, "w");
                            logBoth("MainUI.w0 this.w=" + (w != null ? w.getClass().getName() : "null"));
                            if (w != null) ConversationFilter.install(cl, w, null);
                        } catch (Throwable e) {
                            logBoth("MainUI.w0 this.w err: " + e.getMessage());
                        }
                        // 通过 this.o(u5) 提前缓存列表，交由 injectHeaderToConversationList 统一注入，
                        // 避免此处与 onResume 各 addHeaderView 一次造成重复标签条(空白/残留)
                        try {
                            Object o = XposedHelpers.getObjectField(thisObj, "o");
                            logBoth("MainUI.w0 this.o(u5)=" + (o != null ? o.getClass().getName() : "null"));
                            if (o instanceof View) {
                                View list = (View) o;
                                if (isConversationList(list)) {
                                    sConvListView = list;
                                    logBoth("MainUI.w0 cached convList=" + list.getClass().getName());
                                }
                            }
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try {
                                    injectHeaderToConversationList();
                                } catch (Throwable e) {
                                    logBoth("MainUI.w0 inject err: " + e.getMessage());
                                }
                            });
                        } catch (Throwable e) {
                            logBoth("MainUI.w0 cb err: " + e.getMessage());
                        }
                    } catch (Throwable e) {
                        logBoth("MainUI.w0 cb err: " + e.getMessage());
                    }
                }
            });
            logBoth("MainUI.w0(Bundle) hook installed");
        } catch (Throwable e) {
            logBoth("MainUI.w0 hook failed: " + e.getMessage());
        }
    }

    private static View sConvListView;
    private static volatile View sHeaderAttachedTo;

    /** v998: 读取"聊天分组标签栏"开关(默认开启)。 */
    public static boolean isTagBarEnabled() {
        try {
            android.content.SharedPreferences sp = com.leshao.v3.ContextManager.getPrefs();
            if (sp != null) return sp.getBoolean("ls_chat_group_enabled", true);
        } catch (Throwable ignored) {}
        return true;
    }

    /** v998: 开关关闭时隐藏(并折叠占位)标签栏，开启时恢复。 */
    private static void applyTagBarVisibility(boolean enabled) {
        if (sTagBarView == null) return;
        try {
            sTagBarView.setVisibility(enabled ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams lp = sTagBarView.getLayoutParams();
            if (lp != null) {
                lp.height = enabled ? dp(52, sTagBarView.getContext()) : 0;
                sTagBarView.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    /** v998: 分组标签栏开关变化时立即生效。 */
    public static void onEnabledChanged() {
        if (isTagBarEnabled()) {
            injectHeaderToConversationList();
        } else {
            applyTagBarVisibility(false);
            try {
                sSelectedLabelId = -1;
                ConversationFilter.clearFilter();
            } catch (Throwable ignored) {}
        }
    }

    private static void injectHeaderToConversationList() {
        logBoth("injectHeader start");
        try {
            // v998: 关闭分组标签栏后, 聊天列表顶部不再显示分组栏
            if (!isTagBarEnabled()) {
                applyTagBarVisibility(false);
                return;
            }
            applyTagBarVisibility(true);
            View convList = sConvListView;
            // 缓存引用可能因 Activity 重建（如切换暗色模式）而失效，重新从 DecorView 定位
            boolean stale = convList == null || !convList.isAttachedToWindow();
            if (stale) {
                Activity activity = sCurrentActivity != null ? sCurrentActivity.get() : null;
                if (activity == null) return;
                View root = activity.getWindow().getDecorView();
                convList = findConversationListView(root);
                if (convList != null) {
                    sConvListView = convList;
                    logBoth("relocated convList: " + convList.getClass().getName());
                }
            }

            if (convList == null) {
                logBoth("injectHeader: convList not found, will retry");
                if (sInjectRetryCount < 3) {
                    sInjectRetryCount++;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        injectHeaderToConversationList();
                    }, 500 * sInjectRetryCount);
                }
                return;
            }

            // 尽早安装 ConversationFilter，从 ListView 的 adapter 获取
            if (sClassLoader != null) {
                try {
                    Object adapter = XposedHelpers.callMethod(convList, "getAdapter");
                    if (adapter != null) {
                        ConversationFilter.install(sClassLoader, adapter, convList);
                        logBoth("ConvFilter installed from convList adapter");
                    }
                } catch (Throwable ignored) {}
            }

            // 已附加到同一个 ListView：只刷新，避免重复 addHeaderView 造成多行标签
            if (sHeaderAdded && sHeaderAttachedTo == convList) {
                refreshTagChips();
                return;
            }

            // ListView 已重建（header 附着到旧实例）：丢弃旧 View，重建
            if (sTagBarView != null) {
                sTagBarView = null;
                sTagContainer = null;
                sHeaderAdded = false;
                sHeaderAttachedTo = null;
            }

            // Try addHeaderView via reflection (for AbsListView subclasses)
            if (tryAddHeaderView(convList)) {
                sHeaderAdded = true;
                sInjectRetryCount = 0;
                sHeaderAttachedTo = convList;
                refreshTagChips();
                logBoth("addHeaderView success");
                return;
            }

            // Fallback: try RecyclerView -> add as sibling
            if (convList.getClass().getName().contains("RecyclerView")) {
                injectAboveRecyclerView(convList);
                sHeaderAdded = true;
                sHeaderAttachedTo = convList;
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
        if (ChatGroupHook.isReady()) refreshAll();
    }

    private static boolean isConversationList(View v) {
        try {
            return v.getClass().getName().contains("ConversationListView");
        } catch (Throwable ignored) { return false; }
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
        // v3.0.123: 标签栏背景跟随动态主色容器（Material You），
        // 不再使用 surfaceContainerHighest 的硬编码暖灰（动态配色下呈粉色，与主题脱节）。
        hsv.setBackgroundColor(AppColors.primaryContainer());
        LinearLayout ll = new LinearLayout(ctx);
        ll.setClipChildren(false);
        ll.setClipToPadding(false);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setPadding(dp(8, ctx), dp(2, ctx), dp(8, ctx), dp(2, ctx));
        hsv.addView(ll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sTagContainer = ll;
        return hsv;
    }

    /** Refresh label list from module cache only (no WeChat labels in top bar) */
    public static void refreshLabelList() {
        long start = System.currentTimeMillis();
        // 只渲染模块自身的分组标签(内置+模块创建)，微信通讯录用户标签不进入顶部标签栏
        try { sLabels = ChatGroupHook.getModuleLabels(); }
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
        sChipTvById.clear();
        sTagContainer.addView(makeChip(ctx, "\u5168\u90E8", -1, curSelection == -1));
        // v998: 默认分组按钮固定顺序：全部、群聊、好友、服务
        sTagContainer.addView(makeChip(ctx, ChatGroupHook.LABEL_NAME_GROUP, ChatGroupHook.LABEL_ID_GROUP, curSelection == ChatGroupHook.LABEL_ID_GROUP));
        sTagContainer.addView(makeChip(ctx, ChatGroupHook.LABEL_NAME_FRIEND, ChatGroupHook.LABEL_ID_FRIEND, curSelection == ChatGroupHook.LABEL_ID_FRIEND));
        sTagContainer.addView(makeChip(ctx, ChatGroupHook.LABEL_NAME_SERVICE, ChatGroupHook.LABEL_ID_SERVICE, curSelection == ChatGroupHook.LABEL_ID_SERVICE));
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
            boolean firstUser = true;
            for (LabelInfo l : labels) {
                // 内置标签已固定渲染，跳过
                if (l.labelId == ChatGroupHook.LABEL_ID_GROUP || l.labelId == ChatGroupHook.LABEL_ID_FRIEND || l.labelId == ChatGroupHook.LABEL_ID_SERVICE) continue;
                if (firstUser) { sTagContainer.addView(spacer(ctx)); firstUser = false; }
                sTagContainer.addView(makeChip(ctx, l.labelName, l.labelId, curSelection == l.labelId));
            }
        }
        // v998: 移除标签栏右侧"＋"新增按钮，仅保留分组筛选按钮
        centerChips(ctx);
    }

    /** 单选选中态变化时只重绘 chip 视觉，不重建整行（避免水平滚动/居中导致的跳动） */
    private static void refreshTagChipsSoft() {
        if (sTagContainer == null) return;
        Context ctx = sTagContainer.getContext();
        if (ctx == null) return;
        try {
            int n = sTagContainer.getChildCount();
            for (int i = 0; i < n; i++) {
                View child = sTagContainer.getChildAt(i);
                if (child == null || !(child instanceof FrameLayout)) continue;
                Object tag = child.getTag();
                if (!(tag instanceof Integer)) continue;
                int id = (Integer) tag;
                TextView tv = sChipTvById.get(id);
                if (tv != null) styleChipVisual(ctx, tv, id == sSelectedLabelId);
            }
        } catch (Throwable ignored) {}
    }

    private static void styleChipVisual(Context ctx, TextView tv, boolean sel) {
        try {
            boolean dark = isDarkMode(ctx);
            if (sel) {
                com.leshao.v3.ui.FlowingGradientDrawable bg = new com.leshao.v3.ui.FlowingGradientDrawable(
                    AppColors.gradientStart(), AppColors.gradientMid(), AppColors.gradientEnd());
                bg.setCornerRadii(new float[]{dp(20, ctx), dp(20, ctx), dp(20, ctx), dp(20, ctx)});
                bg.setStroke(dp(1, ctx), 0xB3FFFFFF);
                tv.setBackground(bg);
                tv.setTextColor(Color.WHITE);
                tv.setShadowLayer(dp(4, ctx), 0, 0, (AppColors.primary() & 0x00FFFFFF) | 0x40000000);
            } else {
                // v1148 去渐变：主色浅底 + 主色描边
                int accent = AppColors.primary();
                GradientDrawable bg = new GradientDrawable();
                bg.setColor((dark ? 0x26 : 0x1A) << 24 | (accent & 0x00FFFFFF));
                bg.setCornerRadius(dp(20, ctx));
                bg.setStroke(dp(1, ctx), accent);
                tv.setBackground(bg);
                tv.setTextColor(AppColors.onSurfaceVariant());
            }
        } catch (Throwable ignored) {}
    }

    /** 标签居中：总宽不足时用 padding 居中，超出时左对齐可滑动 */
    private static void centerChips(final Context ctx) {
        if (sTagContainer == null) return;
        final int basePad = dp(8, ctx);
        final int vPad = dp(2, ctx);
        sTagContainer.setPadding(basePad, vPad, basePad, vPad);
        View parent = (View) sTagContainer.getParent();
        if (parent == null) return;
        if (parent.getWidth() > 0) {
            centerChipsNow(ctx);
        } else {
            parent.post(() -> centerChipsNow(ctx));
        }
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
            int vPad = dp(2, ctx);
            if (totalW < barW) {
                int extra = (barW - totalW) / 2;
                if (extra < dp(8, ctx)) extra = dp(8, ctx);
                sTagContainer.setPadding(extra, vPad, extra, vPad);
            } else {
                sTagContainer.setPadding(dp(8, ctx), vPad, dp(8, ctx), vPad);
            }
        } catch (Throwable ignored) {}
    }

    /** Full refresh: load labels + re-render chips */
    public static void refreshAll() {
        refreshLabelList();
        ConversationFilter.scanUnreadCounts();
        refreshTagChips();
    }

    private static boolean isDarkMode(Context ctx) {
        if (ctx == null) return false;
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
        sChipTvById.put(id, tv);
        fl.setTag(id);
        styleChipVisual(ctx, tv, sel);
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

        View.OnClickListener chipClick = v -> {
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
                        logBoth("ConvFilter applyFilter returned");
                        // 滚动由 ConversationFilter 中 lookForSelectablePosition hook 自动处理
                    }
                }
                refreshTagChipsSoft();
                logChipStateAfter(id);
            } catch (Throwable e) {
                logBoth("chip click err: " + e.getMessage());
            }
        };
        tv.setOnClickListener(chipClick);
        fl.setClickable(true);
        fl.setFocusable(true);
        fl.setOnClickListener(chipClick);
        // v998: 移除标签长按"管理/删除"入口，仅保留分组筛选
        return fl;
    }

    /** 点击标签后打印列表/标签行状态，用于定位"跳动" */
    private static void logChipStateAfter(int id) {
        try {
            View list = sConvListView;
            if (list != null && list.isAttachedToWindow()) {
                int fp = -1, cnt = -1;
                int scrollX = sTagContainer != null ? sTagContainer.getScrollX() : -1;
                android.widget.AbsListView lv = (android.widget.AbsListView) list;
                fp = lv.getFirstVisiblePosition();
                cnt = lv.getCount();
                int padL = sTagContainer != null ? sTagContainer.getPaddingLeft() : -1;
                logBoth("chip state after id=" + id + " sel=" + sSelectedLabelId
                    + " fp=" + fp + " count=" + cnt + " tagScrollX=" + scrollX + " padL=" + padL);
            }
        } catch (Throwable ignored) {}
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
        tv.setTextColor(AppColors.onPrimary());
        tv.setPadding(dp(10, ctx), dp(7, ctx), dp(10, ctx), dp(7, ctx));
        tv.setGravity(Gravity.CENTER); tv.setTypeface(null, Typeface.BOLD);
        tv.setMinWidth(dp(39, ctx));
        com.leshao.v3.ui.FlowingGradientDrawable bg = new com.leshao.v3.ui.FlowingGradientDrawable(
            AppColors.primary(), AppColors.primary(), AppColors.primary());
        bg.setCornerRadii(new float[]{dp(16, ctx), dp(16, ctx), dp(16, ctx), dp(16, ctx)});
        bg.setStroke(dp(1, ctx), AppColors.onPrimary());
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
        if (id == ChatGroupHook.LABEL_ID_GROUP) return ChatGroupHook.LABEL_NAME_GROUP;
        if (id == ChatGroupHook.LABEL_ID_FRIEND) return ChatGroupHook.LABEL_NAME_FRIEND;
        if (id == ChatGroupHook.LABEL_ID_SERVICE) return ChatGroupHook.LABEL_NAME_SERVICE;
        List<LabelInfo> labels = ChatGroupHook.getAllLabels();
        if (labels != null) {
            for (LabelInfo li : labels) {
                if (li.labelId == id) return li.labelName;
            }
        }
        return null;
    }


    private static void showCreateDialog(String username) {
        Activity ctx = sCurrentActivity != null ? sCurrentActivity.get() : null;
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


    private static final Handler sRefreshHandler = new Handler(Looper.getMainLooper());

    public static void refreshTagData() {
        sRefreshHandler.removeCallbacksAndMessages(null);
        sRefreshHandler.postDelayed(ChatGroupUiInjector::refreshAll, 100);
    }

    private static void toast(String msg) {
        Activity ctx = sCurrentActivity != null ? sCurrentActivity.get() : null;
        if (ctx == null) return;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    private static int dp(int dpi, Context ctx) {
        return (int) (dpi * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 统一模块配色 (浅色/暗色) ====================
    private static Context getCurrentActivityContext() {
        return sCurrentActivity != null ? sCurrentActivity.get() : null;
    }
    private static int themeTitle() { return isDarkMode(getCurrentActivityContext()) ? 0xFFE4E4E8 : 0xFF1D1D1F; }
    private static int themeBody()   { return isDarkMode(getCurrentActivityContext()) ? 0xFFB0B0B8 : 0xFF565659; }
    private static int themeNote()   { return isDarkMode(getCurrentActivityContext()) ? 0xFF707079 : 0xFF949499; }
    private static int themeAccent() { return AppColors.primary(); }
    private static int themeBg()     { return isDarkMode(getCurrentActivityContext()) ? 0xFF2A2A2E : 0xFFFFFFFF; }
    private static int themeCard()   { return isDarkMode(getCurrentActivityContext()) ? 0xFF1E1E22 : 0xFFF5F5F5; }

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

    private static void logBoth(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
        LogWriter.log(TAG, msg);
    }
}
