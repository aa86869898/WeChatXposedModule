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
        try { installContextMenuHooks(effectiveCL); } catch (Throwable e) { logBoth("menu err: " + e.getMessage()); }
        try { installConvNativeMenuInjection(effectiveCL); } catch (Throwable e) { logBoth("convMenu err: " + e.getMessage()); }
        logBoth("hook() done");
    }

    private static boolean sHeaderAdded = false;
    private static int sInjectRetryCount = 0;
    private static volatile String sPendingUsername;
    private static volatile String sPendingContextUsername;

    private static volatile long sConvMenuArmTs = 0;
    private static volatile String sConvMenuUser;
    private static final long CONV_MENU_WINDOW_MS = 3000;
    private static volatile boolean sConvPopupHooksInstalled = false;
    private static final java.util.Set<android.view.View> sInjectedPopups =
        java.util.Collections.synchronizedSet(new java.util.HashSet<android.view.View>());
    private static final String[] CONV_MENU_MARKERS = new String[]{
        "置顶聊天", "取消置顶", "标为未读", "标为已读", "删除该聊天", "不显示该聊天"
    };
    private static View sTagBarView;
    private static final int CTX_MENU_BASE = 0x7F030000;
    private static final int CTX_MENU_MORE = CTX_MENU_BASE + 999999;
    private static final int CTX_MENU_NEW = CTX_MENU_BASE + 999998;

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
                        // Try to find adapter from MainUI Fragment
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
                                        Object adapter = XposedHelpers.getObjectField(frag, "v");
                                        logBoth("MainUI.v=" + (adapter != null ? adapter.getClass().getName() : "null"));
                                        if (adapter != null) {
                                            ConversationFilter.install(cl, adapter, null);
                                        }
                                    } catch (Throwable e) {
                                        logBoth("MainUI.v err: " + e.getMessage());
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
                        Object adapter = XposedHelpers.getObjectField(param.thisObject, "v");
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
    }

    private static View sConvListView;
    private static volatile View sHeaderAttachedTo;

    private static void injectHeaderToConversationList() {
        logBoth("injectHeader start");
        try {
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
        // 内置虚拟标签固定顺序：好友、群聊、服务
        sTagContainer.addView(makeChip(ctx, ChatGroupHook.LABEL_NAME_FRIEND, ChatGroupHook.LABEL_ID_FRIEND, curSelection == ChatGroupHook.LABEL_ID_FRIEND));
        sTagContainer.addView(makeChip(ctx, ChatGroupHook.LABEL_NAME_GROUP, ChatGroupHook.LABEL_ID_GROUP, curSelection == ChatGroupHook.LABEL_ID_GROUP));
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
        sTagContainer.addView(spacer(ctx));
        sTagContainer.addView(makeAddBtn(ctx));
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
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFFFF6B8A, 0xFFA855F7, 0xFF38BDF8});
                bg.setCornerRadius(dp(20, ctx));
                bg.setStroke(dp(1, ctx), 0xB3FFFFFF);
                tv.setBackground(bg);
                tv.setTextColor(Color.WHITE);
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
        if (id > 0) tv.setOnLongClickListener(v -> { showLabelManage(ctx, id, text, fl); return true; });
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

    private static void installContextMenuHooks(ClassLoader cl) {
        tryInstallContextItemSelectedHook(cl);
        tryInstallR3Hook(cl);
        tryInstallSetTagHook(cl);
        tryInstallLauncherUIContextMenuHook(cl);
        tryInstallAdapterContextMenuHook(cl);
        tryInstallActivityCtxMenuFallback();
        // Defer DexKit-dependent menu class discovery until scan completes
        com.leshao.v3.hook.DexKitHelper.addPostScanCallback(() -> {
            hookMenuClassByString(cl, "menuG4", "置顶聊天", "标为未读");
            hookClassBroadForDiagnosis(cl, "kc5.g4", "menuG4");
            hookClassBroadForDiagnosis(cl, "kc5.h4", "menuItemH4");
        });
        // Defer DexKit-dependent menu hook until scan completes
        final ClassLoader fCl = cl;
        com.leshao.v3.hook.DexKitHelper.addPostScanCallback(() -> tryInstallMenuItemGetItemHook(fCl));
        logBoth("native context-menu hooks installed");
    }

    /** 用 DexKit 字符串搜索动态发现菜单类 */
    private static void hookMenuClassByString(ClassLoader cl, String tag, String... keywords) {
        for (String kw : keywords) {
            List<String> candidates = DexKitHelper.findClassesByString(cl, kw);
            for (String cn : candidates) {
                if (cn.contains("menu") || cn.contains("Menu") || cn.contains("kc5")) {
                    logBoth(tag + " DexKit found: " + cn + " via keyword=" + kw);
                }
            }
        }
    }

    // ================= 微信原生会话长按菜单注入（不依赖 ContextMenu 框架） =================
    // 背景：8.0.49 会话列表长按弹的是微信自定义弹窗，标准 android ContextMenu/OptionsMenu
    // 链路全部不触发。这里改为两层：长按会话时打"臂"标记并记下用户名；随后全局捕获
    // PopupWindow.show，若命中"臂"窗口即把分组管理菜单项追加进弹窗内容（保留微信原生菜单）。

    private static void installConvNativeMenuInjection(final ClassLoader cl) {
        hookConvListViewLongPressArm(cl);
        hookConvPopupShowForInjection(cl);
        hookConvLongPressImplsGated(cl);
        logBoth("convNativeMenuInjection installed");
    }

    /** 会话列表触摸长按检测：只打臂标记（arm）+记用户名，不弹任何模块对话框，不消费事件。
     *  微信自己的长按菜单照常弹出。 */
    private static void hookConvListViewLongPressArm(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.tencent.mm.ui.conversation.ConversationListView", cl);
            java.lang.reflect.Method m = null;
            Class<?> cur = cls;
            while (cur != null && cur != android.view.View.class) {
                try { m = cur.getDeclaredMethod("onTouchEvent", android.view.MotionEvent.class); break; }
                catch (NoSuchMethodException e) { cur = cur.getSuperclass(); }
            }
            if (m == null) { logBoth("convArm onTouchEvent not found"); return; }
            m.setAccessible(true);
            final Class<?> convCls = cls;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                private long downTime;
                private float downX, downY;
                private long lastDownLog;
                private final Handler h = new Handler(Looper.getMainLooper());
                private final Runnable armRunnable = new Runnable() {
                    @Override public void run() {
                        try {
                            if (downTime <= 0) return;
                            long t = downTime; downTime = 0;
                            armConvListAt(t, (int) downX, (int) downY, (AbsListView) mThis);
                        } catch (Throwable ignored) {}
                    }
                };
                private AbsListView mThis;
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject == null) return;
                        if (!(p.args[0] instanceof android.view.MotionEvent)) return;
                        Object self = p.thisObject;
                        boolean isConv = convCls.isAssignableFrom(self.getClass());
                        if (!isConv) return;
                        android.view.MotionEvent ev = (android.view.MotionEvent) p.args[0];
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            long now = System.currentTimeMillis();
                            if (now - lastDownLog > 2000) {
                                lastDownLog = now;
                                logBoth("convTouch DOWN cls=" + self.getClass().getName());
                            }
                            downTime = System.currentTimeMillis();
                            downX = ev.getX(); downY = ev.getY();
                            mThis = (AbsListView) p.thisObject;
                            h.removeCallbacks(armRunnable);
                            h.postDelayed(armRunnable, 450);
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            if (Math.abs(ev.getX() - downX) > 60 || Math.abs(ev.getY() - downY) > 60) {
                                h.removeCallbacks(armRunnable);
                                downTime = 0;
                            }
                        } else if (action == android.view.MotionEvent.ACTION_UP
                                   || action == android.view.MotionEvent.ACTION_CANCEL) {
                            h.removeCallbacks(armRunnable);
                            long dur = System.currentTimeMillis() - downTime;
                            if (dur >= 450 && Math.abs(ev.getX() - downX) < 60 && Math.abs(ev.getY() - downY) < 60) {
                                armConvListAt(downTime, (int) downX, (int) downY, (AbsListView) p.thisObject);
                            }
                            downTime = 0;
                        }
                    } catch (Throwable ignored) {}
                }
            });
            logBoth("convArm onTouchEvent hooked: " + m.getDeclaringClass().getName());
        } catch (Throwable e) {
            logBoth("convArm hook err: " + e.getMessage());
        }
    }

    /** 长按落点换算会话并打臂标记 */
    private static void armConvListAt(long downTime, int x, int y, AbsListView lv) {
        try {
            int pos = lv.pointToPosition(x, y);
            if (pos == AdapterView.INVALID_POSITION) { logBoth("convArm INVALID_POSITION"); return; }
            View child = lv.getChildAt(pos - lv.getFirstVisiblePosition());
            String username = child != null ? extractUsernameFromView(child) : null;
            if (TextUtils.isEmpty(username)) username = extractUsernameFromList(lv, pos);
            if (TextUtils.isEmpty(username)) { logBoth("convArm no user pos=" + pos); return; }
            armConvMenu(username);
            logBoth("convArm armed user=" + username + " pos=" + pos);
        } catch (Throwable e) {
            logBoth("convArm err: " + e.getMessage());
        }
    }

    /** 打臂：记录最近长按的会话用户，供随后弹出的原生菜单注入使用 */
    private static void armConvMenu(String username) {
        if (TextUtils.isEmpty(username)) return;
        sConvMenuUser = username;
        sPendingUsername = username;
        sPendingContextUsername = username;
        sConvMenuArmTs = System.currentTimeMillis();
    }

    /** 门控实现类钩：命中会话列表 OnItemLongClickListener 时打臂（不弹窗） */
    private static void hookConvLongPressImplsGated(ClassLoader cl) {
        final List<String> impls = DexKitHelper.getConvLongPressImpls();
        logBoth("convLPImpls count=" + impls.size());
        if (impls.isEmpty()) {
            DexKitHelper.addPostScanCallback(() -> hookConvLongPressImplsGated(cl));
            return;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    AdapterView<?> parent = null;
                    View view = null;
                    int position = -1;
                    if (p.args.length >= 2) {
                        if (p.args[0] instanceof AdapterView) parent = (AdapterView<?>) p.args[0];
                        if (p.args[1] instanceof View) view = (View) p.args[1];
                    }
                    if (p.args.length >= 3 && p.args[2] instanceof Integer) position = (Integer) p.args[2];
                    if (parent == null
                        || !"com.tencent.mm.ui.conversation.ConversationListView".equals(parent.getClass().getName())) return;
                    String username = view != null ? extractUsernameFromView(view) : null;
                    if (TextUtils.isEmpty(username) && position >= 0) username = extractUsernameFromList(parent, position);
                    if (!TextUtils.isEmpty(username)) armConvMenu(username);
                    logBoth("convLPImpl gated user=" + username);
                } catch (Throwable ignored) {}
            }
        };
        int ok = 0;
        for (String cn : impls) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, cl);
                XposedBridge.hookAllMethods(c, "onItemLongClick", hook);
                ok++;
            } catch (Throwable ignored) {}
        }
        logBoth("convLPImpl gated hooks installed=" + ok + "/" + impls.size());
    }

    /** 全局捕获 PopupWindow 显示：命中臂窗口即注入"分组管理"行 */
    private static void hookConvPopupShowForInjection(ClassLoader cl) {
        if (sConvPopupHooksInstalled) return;
        sConvPopupHooksInstalled = true;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof android.widget.PopupWindow) {
                        injectConvMenuIntoPopup((android.widget.PopupWindow) p.thisObject);
                    }
                } catch (Throwable e) {
                    logBoth("convPopup hook err: " + e.getMessage());
                }
            }
        };
        try { XposedBridge.hookAllMethods(android.widget.PopupWindow.class, "showAtLocation", hook).size();
            logBoth("PopupWindow.showAtLocation hooked"); } catch (Throwable e) { logBoth("showAtLocation hook err: " + e.getMessage()); }
        try { XposedBridge.hookAllMethods(android.widget.PopupWindow.class, "showAsDropDown", hook).size();
            logBoth("PopupWindow.showAsDropDown hooked"); } catch (Throwable e) { logBoth("showAsDropDown hook err: " + e.getMessage()); }
    }

    /** 把"分组管理"菜单行追加进刚弹出的微信原生菜单内容（若该弹窗是会话长按菜单） */
    private static void injectConvMenuIntoPopup(android.widget.PopupWindow pw) {
        if (pw == null) return;
        final View content;
        try { content = pw.getContentView(); } catch (Throwable e) { return; }
        injectConvMenuIntoView(content, new Runnable() {
            @Override public void run() {
                try { pw.dismiss(); } catch (Throwable ignored) {}
            }
        }, "convPopup");
    }

    /** 在任意"刚出现的菜单窗口内容"上执行注入（PopupWindow 内容 / Dialog decor 均可复用） */
    private static void injectConvMenuIntoView(final View content, final Runnable dismisser, final String tag) {
        if (content == null) return;
        long now = System.currentTimeMillis();
        if (sConvMenuArmTs == 0 || now - sConvMenuArmTs > CONV_MENU_WINDOW_MS) return;
        String user = sConvMenuUser;
        if (TextUtils.isEmpty(user)) return;
        final String contentCls = content.getClass().getName();
        if (sInjectedPopups.contains(content)) {
            logBoth(tag + " already injected " + contentCls);
            return;
        }
        tryInjectConvMenuInto(content, dismisser, tag, 0);
    }

    private static void tryInjectConvMenuInto(final View content, final Runnable dismisser,
                                              final String tag, final int attempt) {
        try {
            if (content == null) return;
            String user = sConvMenuUser;
            final String contentCls = content.getClass().getName();
            if (sInjectedPopups.contains(content)) { logBoth(tag + " already injected " + contentCls); return; }
            ViewGroup container = findMenuListContainer(content);
            if (container == null) {
                if (attempt < 2 && content.isShown()) {
                    logBoth(tag + " no container retry content=" + contentCls + " user=" + user + " try=" + attempt);
                    content.postDelayed(() -> tryInjectConvMenuInto(content, dismisser, tag, attempt + 1), 90);
                } else {
                    logBoth(tag + " no container content=" + contentCls + " user=" + user);
                }
                return;
            }
            sInjectedPopups.add(content);
            sConvMenuArmTs = 0;
            logBoth(tag + " inject content=" + contentCls
                + " container=" + container.getClass().getName() + " children=" + container.getChildCount() + " user=" + user);
            final String fUser = user;
            final Context ctx = container.getContext();
            boolean dark = isDarkMode(ctx);
            TextView row = new TextView(ctx);
            row.setText("分组管理");
            row.setTextSize(16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setSingleLine(true);
            row.setTextColor(dark ? 0xFFE4E4E8 : 0xFF1D1D1F);
            int rowH = dp(48, ctx);
            // 对齐同级行高，尽量贴近原生观感
            for (int i = 0; i < container.getChildCount(); i++) {
                View sib = container.getChildAt(i);
                if (sib.getLayoutParams() != null && sib.getLayoutParams().height > 0) {
                    rowH = sib.getLayoutParams().height;
                    break;
                }
            }
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH);
            row.setLayoutParams(lp);
            row.setPadding(dp(16, ctx), 0, dp(16, ctx), 0);
            row.setOnClickListener(v -> {
                try {
                    sConvMenuArmTs = 0;
                    if (dismisser != null) { try { dismisser.run(); } catch (Throwable ignored) {} }
                    String target = sConvMenuUser;
                    if (TextUtils.isEmpty(target)) target = sPendingContextUsername;
                    if (TextUtils.isEmpty(target)) target = sPendingUsername;
                    if (TextUtils.isEmpty(target)) return;
                    Activity act = sCurrentActivity != null ? sCurrentActivity.get() : null;
                    if ((act == null || act.isFinishing()) && ctx instanceof Activity) act = (Activity) ctx;
                    if (act == null) return;
                    sCurrentActivity = new WeakReference<>(act);
                    sPendingUsername = target;
                    sPendingContextUsername = target;
                    logBoth(tag + " item clicked user=" + target);
                    showFullGroupDialogForUsername(target);
                } catch (Throwable e) {
                    logBoth(tag + " click err: " + e.getMessage());
                }
            });
            container.addView(row);
            try { content.requestLayout(); content.invalidate(); } catch (Throwable ignored) {}
            logBoth(tag + " injected OK user=" + fUser);
        } catch (Throwable e) {
            logBoth(tag + " inject err: " + e.getMessage());
        }
    }

    /** 找到装菜单行的竖向容器：其直接子视图中有 ≥2 个"短文本行"即视为菜单列表。
     *  找不到短文本行时再退化为"直接子视图含微信菜单标记文案"的容器。 */
    private static ViewGroup findMenuListContainer(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup top = (ViewGroup) root;
        ViewGroup direct = findDirectRowContainer(top, 0);
        if (direct != null) return direct;
        if (containsMarkerText(root)) {
            ViewGroup vg = (ViewGroup) root;
            // 递归向深找包含标记行文本的容器
            java.util.ArrayDeque<ViewGroup> q = new java.util.ArrayDeque<>();
            q.add(vg);
            ViewGroup deepest = null;
            while (!q.isEmpty()) {
                ViewGroup cur = q.poll();
                if (hasDirectMarkerRow(cur)) deepest = cur;
                for (int i = 0; i < cur.getChildCount(); i++) {
                    View c = cur.getChildAt(i);
                    if (c instanceof ViewGroup) q.add((ViewGroup) c);
                }
            }
            return deepest;
        }
        return null;
    }

    private static ViewGroup findDirectRowContainer(ViewGroup vg, int depth) {
        if (depth > 3) return null;
        if (countRowLikeChildren(vg) >= 2) return vg;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof ViewGroup) {
                ViewGroup r = findDirectRowContainer((ViewGroup) c, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int countRowLikeChildren(ViewGroup vg) {
        int n = 0;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence t = ((TextView) c).getText();
                if (t != null && t.length() > 0 && t.length() <= 32) n++;
            } else if (c instanceof ViewGroup) {
                if (containsShortText((ViewGroup) c)) n++;
            }
        }
        return n;
    }

    private static boolean containsShortText(ViewGroup vg) {
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
        q.add(vg);
        int depth = 0;
        while (!q.isEmpty() && depth < 4) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                View v = q.poll();
                if (v instanceof TextView) {
                    CharSequence t = ((TextView) v).getText();
                    if (t != null && t.length() > 0 && t.length() <= 32) return true;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int j = 0; j < g.getChildCount(); j++) q.add(g.getChildAt(j));
                }
            }
            depth++;
        }
        return false;
    }

    private static boolean hasDirectMarkerRow(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            String t = viewText(c);
            if (t != null && containsMarker(t)) return true;
            if (c instanceof ViewGroup && containsMarkerText((ViewGroup) c)) return true;
        }
        return false;
    }

    private static boolean containsMarkerText(View v) {
        if (!(v instanceof ViewGroup)) return false;
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
        q.add(v);
        while (!q.isEmpty()) {
            View cur = q.poll();
            String t = viewText(cur);
            if (t != null && containsMarker(t)) return true;
            if (cur instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) cur;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return false;
    }

    private static String viewText(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return t == null ? null : t.toString();
        }
        return null;
    }

    private static boolean containsMarker(String text) {
        if (text == null) return false;
        for (String m : CONV_MENU_MARKERS) {
            if (text.contains(m)) return true;
        }
        return false;
    }

    /** 兜底方案：hook AbsListView.onLongPress —— item 长按在框架内的中央分发点，
     *  无论是否注册 OnItemLongClickListener 都会走到（onLongPress → listener 或 child.performLongClick）。
     *  在会话列表长按 item 时提取用户名并弹出分组管理对话框。
     *  v878 用 getDeclaredMethod+hookMethod 安装失败（反射查找抛 NoSuchMethodError），
     *  v879 改用 hookAllMethods 按名挂载，并放宽实例校验为类名校验（防列表重建后引用失效）。 */
    private static void tryInstallAbsListViewOnLongPressHook(ClassLoader cl) {
        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject == null
                        || !"com.tencent.mm.ui.conversation.ConversationListView".equals(p.thisObject.getClass().getName())) return;
                    View v = null;
                    int position = -1;
                    if (p.args.length >= 2 && p.args[0] instanceof View) v = (View) p.args[0];
                    if (p.args.length >= 3 && p.args[1] instanceof Integer) position = (Integer) p.args[1];
                    String username = extractUsernameFromView(v);
                    if (TextUtils.isEmpty(username) && position >= 0) {
                        username = extractUsernameFromList((AdapterView<?>) p.thisObject, position);
                    }
                    if (TextUtils.isEmpty(username)) {
                        LogWriter.log(TAG, "onLongPress: no username, v="
                            + (v != null ? v.getClass().getSimpleName() : "null") + " pos=" + position);
                        return;
                    }
                    LogWriter.log(TAG, "onLongPress user=" + username + " pos=" + position);
                    triggerGroupDialog(username, v, p.thisObject);
                } catch (Throwable ignored) {}
            }
        };
        try {
            int n = XposedBridge.hookAllMethods(android.widget.AbsListView.class, "onLongPress", hook).size();
            LogWriter.log(TAG, "AbsListView.onLongPress hookAllMethods hooked=" + n);
        } catch (Throwable e) {
            LogWriter.log(TAG, "onLongPress hookAllMethods err: " + e.getMessage());
        }
        // 子类可能重写 onLongPress：按名挂会话列表类及其父类链
        try {
            Class<?> cls = XposedHelpers.findClass("com.tencent.mm.ui.conversation.ConversationListView", cl);
            Class<?> cur = cls;
            while (cur != null && cur != android.widget.AbsListView.class) {
                int n = -1;
                try {
                    n = XposedBridge.hookAllMethods(cur, "onLongPress", hook).size();
                } catch (Throwable ignored) {}
                if (n > 0) {
                    LogWriter.log(TAG, "onLongPress override hooked: " + cur.getName() + " (" + n + ")");
                    break;
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    /** 去重触发器：同一长按被多层 hook 命中时只弹一次分组对话框 */
    private static volatile long sLastGroupDialogTs = 0;

    private static void triggerGroupDialog(String username, View v, Object parent) {
        long now = System.currentTimeMillis();
        if (now - sLastGroupDialogTs < 1200) return;
        sLastGroupDialogTs = now;
        sPendingContextUsername = username;
        sPendingUsername = username;
        try {
            if (v != null) sCurrentActivity = new WeakReference<>((Activity) v.getContext());
            else if (parent instanceof View) sCurrentActivity = new WeakReference<>((Activity) ((View) parent).getContext());
        } catch (Throwable ignored) {}
        showFullGroupDialogForUsername(username);
    }

    /** 判断 v 是否位于会话列表内（按类名向上遍历，避免 sConvListView 实例过期） */
    private static boolean inConversationList(View v) {
        View cur = v;
        while (cur != null) {
            if ("com.tencent.mm.ui.conversation.ConversationListView".equals(cur.getClass().getName())) return true;
            Object parent = cur.getParent();
            if (!(parent instanceof View)) return false;
            cur = (View) parent;
        }
        return false;
    }

    /** 长按第二层：hook View.performLongClick。
     *  DexKit 证明 8.0.49 会话列表未注册 OnItemLongClickListener（convLongPress=null.null），
     *  因此 onLongPress 无监听时会落到 item 的 performLongClick，这里直接兜住。 */
    private static void tryInstallViewLongClickHook() {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "performLongClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.thisObject;
                        if (v == null || !inConversationList(v)) return;
                        String username = extractUsernameFromView(v);
                        if (TextUtils.isEmpty(username)) return;
                        LogWriter.log(TAG, "viewLongClick user=" + username);
                        triggerGroupDialog(username, v, v.getParent());
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "View.performLongClick hook installed");
        } catch (Throwable e) {
            LogWriter.log(TAG, "performLongClick hook err: " + e.getMessage());
        }
    }

    /** 长按第三层（最稳兜底）：hook ConversationListView.onTouchEvent，
     *  按下≥500ms 且无明显位移即视为长按，与微信菜单机制完全解耦。 */
    private static volatile boolean sTouchDetectorHooked = false;

    private static void installConvListLongPressDetector(ClassLoader cl) {
        if (sTouchDetectorHooked) return;
        try {
            Class<?> cls = XposedHelpers.findClass("com.tencent.mm.ui.conversation.ConversationListView", cl);
            java.lang.reflect.Method m = null;
            Class<?> cur = cls;
            while (cur != null && cur != android.view.View.class) {
                try {
                    m = cur.getDeclaredMethod("onTouchEvent", android.view.MotionEvent.class);
                    break;
                } catch (NoSuchMethodException e) {
                    cur = cur.getSuperclass();
                }
            }
            if (m == null) { LogWriter.log(TAG, "convList onTouchEvent not found"); return; }
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                private long downTime;
                private float downX, downY;
                private AbsListView mList;
                private final Handler lpHandler = new Handler(Looper.getMainLooper());
                private final Runnable lpCheck = new Runnable() {
                    @Override public void run() {
                        try {
                            if (downTime <= 0 || mList == null) return;
                            long dur = System.currentTimeMillis() - downTime;
                            LogWriter.log(TAG, "longpress: timer FIRE dur=" + dur + " dx=" + Math.round(downX) + " dy=" + Math.round(downY));
                            if (dur >= 400) {
                                // 长按已成立：微信可能消费 UP/CANCEL，这里不依赖 UP 直接触发
                                long t = downTime;
                                downTime = 0;
                                handleConvListLongPress(mList, (int) downX, (int) downY);
                            }
                        } catch (Throwable ignored) {}
                    }
                };
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject == null
                            || !"com.tencent.mm.ui.conversation.ConversationListView".equals(p.thisObject.getClass().getName())) return;
                        if (!(p.args[0] instanceof android.view.MotionEvent)) return;
                        android.view.MotionEvent ev = (android.view.MotionEvent) p.args[0];
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            downTime = System.currentTimeMillis();
                            downX = ev.getX();
                            downY = ev.getY();
                            mList = (AbsListView) p.thisObject;
                            lpHandler.removeCallbacks(lpCheck);
                            lpHandler.postDelayed(lpCheck, 600);
                            LogWriter.log(TAG, "longpress: DOWN x=" + Math.round(downX) + " y=" + Math.round(downY) + " 600ms timer set");
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            if (Math.abs(ev.getX() - downX) > 60 || Math.abs(ev.getY() - downY) > 60) {
                                lpHandler.removeCallbacks(lpCheck);
                                LogWriter.log(TAG, "longpress: MOVE canceled dx=" + Math.round(ev.getX() - downX) + " dy=" + Math.round(ev.getY() - downY));
                            }
                        } else if (action == android.view.MotionEvent.ACTION_UP) {
                            lpHandler.removeCallbacks(lpCheck);
                            long dur = System.currentTimeMillis() - downTime;
                            float dx = ev.getX() - downX, dy = ev.getY() - downY;
                            LogWriter.log(TAG, "longpress: UP dur=" + dur + " dx=" + Math.round(dx) + " dy=" + Math.round(dy));
                            if (dur >= 500 && Math.abs(dx) < 60 && Math.abs(dy) < 60) {
                                handleConvListLongPress((AbsListView) p.thisObject, (int) downX, (int) downY);
                            }
                        } else if (action == android.view.MotionEvent.ACTION_CANCEL) {
                            lpHandler.removeCallbacks(lpCheck);
                            long dur = System.currentTimeMillis() - downTime;
                            if (dur >= 500 && Math.abs(ev.getX() - downX) < 60 && Math.abs(ev.getY() - downY) < 60) {
                                handleConvListLongPress((AbsListView) p.thisObject, (int) downX, (int) downY);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            sTouchDetectorHooked = true;
            LogWriter.log(TAG, "convList onTouchEvent long-press detector hooked: " + m.getDeclaringClass().getName());
        } catch (Throwable e) {
            LogWriter.log(TAG, "convList onTouchEvent hook err: " + e.getMessage());
        }
    }

    private static void handleConvListLongPress(AbsListView lv, int x, int y) {
        try {
            int pos = lv.pointToPosition(x, y);
            if (pos == AdapterView.INVALID_POSITION) {
                LogWriter.log(TAG, "viewLongPress: INVALID_POSITION");
                return;
            }
            View child = lv.getChildAt(pos - lv.getFirstVisiblePosition());
            String username = child != null ? extractUsernameFromView(child) : null;
            if (TextUtils.isEmpty(username)) username = extractUsernameFromList(lv, pos);
            if (TextUtils.isEmpty(username)) {
                LogWriter.log(TAG, "viewLongPress: no username pos=" + pos);
                return;
            }
            LogWriter.log(TAG, "viewLongPress user=" + username + " pos=" + pos);
            triggerGroupDialog(username, child, lv);
        } catch (Throwable e) {
            LogWriter.log(TAG, "viewLongPress err: " + e.getMessage());
        }
    }

    /** 判断 v 是否为 root 的后代视图（含自身） */
    private static boolean isDescendantOf(View v, View root) {
        if (v == root) return true;
        View cur = v;
        while (cur != null) {
            if (cur == root) return true;
            Object parent = cur.getParent();
            if (!(parent instanceof View)) return false;
            cur = (View) parent;
        }
        return false;
    }

    /** 全包 hook OnItemLongClickListener 实现类(由 DexKit 运行时发现，8.0.49 为 f4/i/kb/o3/p9/q0/r3)。
     *  直接钩各实现类的 onItemLongClick：先取用户名，长按即可管理分组。 */
    private static void tryInstallLongPressImplHooks(final ClassLoader cl) {
        final List<String> impls = DexKitHelper.getConvLongPressImpls();
        LogWriter.log(TAG, "longPressImpls count=" + impls.size() + " -> " + impls);
        if (impls.isEmpty()) {
            DexKitHelper.addPostScanCallback(() -> tryInstallLongPressImplHooks(cl));
            return;
        }
        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    AdapterView<?> parent = null;
                    View view = null;
                    int position = -1;
                    if (p.args.length >= 2) {
                        if (p.args[0] instanceof AdapterView) parent = (AdapterView<?>) p.args[0];
                        if (p.args[1] instanceof View) view = (View) p.args[1];
                    }
                    if (p.args.length >= 3 && p.args[2] instanceof Integer) position = (Integer) p.args[2];
                    String username = extractUsernameFromView(view);
                    if (TextUtils.isEmpty(username) && parent != null) {
                        username = extractUsernameFromList(parent, position);
                    }
                    LogWriter.log(TAG, "longPressImpl user=" + username + " cls=" + p.thisObject.getClass().getName());
                    if (!TextUtils.isEmpty(username)) {
                        sPendingContextUsername = username;
                        sPendingUsername = username;
                    }
                } catch (Throwable ignored) {}
            }
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View view = null;
                    AdapterView<?> parent = null;
                    int position = -1;
                    if (p.args.length >= 2) {
                        if (p.args[0] instanceof AdapterView) parent = (AdapterView<?>) p.args[0];
                        if (p.args[1] instanceof View) view = (View) p.args[1];
                    }
                    if (p.args.length >= 3 && p.args[2] instanceof Integer) position = (Integer) p.args[2];
                    String username = sPendingContextUsername;
                    if (TextUtils.isEmpty(username)) {
                        username = extractUsernameFromView(view);
                        if (TextUtils.isEmpty(username) && parent != null) username = extractUsernameFromList(parent, position);
                    }
                    if (!TextUtils.isEmpty(username)) {
                        try { sCurrentActivity = new WeakReference<>((Activity) view.getContext()); } catch (Throwable ignored) {}
                        LogWriter.log(TAG, "longPressImpl -> showGroupDialog user=" + username);
                        showFullGroupDialogForUsername(username);
                    }
                } catch (Throwable ignored) {}
            }
        };
        int installed = 0;
        for (String cn : impls) {
            try {
                Class<?> cls = XposedHelpers.findClass(cn, cl);
                XposedBridge.hookAllMethods(cls, "onItemLongClick", hook);
                installed++;
            } catch (Throwable e) {
                LogWriter.log(TAG, "longPressImpl hook fail " + cn + ": " + e.getMessage());
            }
        }
        LogWriter.log(TAG, "longPressImpl hooks installed=" + installed + "/" + impls.size());
    }

    private static volatile long sLastCtxClickTime = 0;
    private static volatile int sLastCtxClickId = Integer.MIN_VALUE;

    /** 微信 8.0.76 点击分发：getItem(index) 是点击特征调用（渲染阶段只调 getTitle/size，不调 getItem）。
     *  在此捕获并直接执行分组动作，微信后续按 itemId 静默忽略即可。 */
    private static void tryInstallMenuItemGetItemHook(ClassLoader cl) {
        // 8.0.78: kc5.g4 已混淆，动态搜索实现 Menu 接口的类
        String[] menuClassCandidates = {"kc5.g4", "kc5.h4", "fh5.w0", "com.tencent.mm.ui.menu.g4", "com.tencent.mm.ui.menu.h4"};
        Class<?> cls = null;
        for (String candidate : menuClassCandidates) {
            try {
                cls = XposedHelpers.findClass(candidate, cl);
                break;
            } catch (Throwable ignored) {}
        }
        // 兜底: 搜索包含 "menu" 或 "Menu" 的类
        if (cls == null) {
            List<String> candidates = DexKitHelper.findClassesByString(cl, "menu");
            for (String cn : candidates) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if ("getItem".equals(m.getName()) && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == int.class) {
                            cls = c;
                            break;
                        }
                    }
                    if (cls != null) break;
                } catch (Throwable ignored) {}
            }
        }
        if (cls == null) {
            logBoth("ctxMenu getItem: no menu class found");
            return;
        }
        final Class<?> menuCls = cls;
        try {
            XposedBridge.hookAllMethods(menuCls, "getItem", new XC_MethodHook() {
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
            logBoth("ctxMenu getItem hooked: " + menuCls.getName());
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
            } catch (Throwable e) {
                if (!(e.getCause() instanceof ClassNotFoundException)) {
                    logBoth("MenuItemImpl.invoke " + cn + ": " + e.getMessage());
                }
            }
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
    private static void hookMenuClassDynamically(Menu menu) {
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
        // 8.0.78: fh5.w0 已混淆，动态搜索
        String[] adapterCandidates = {"fh5.w0", "com.tencent.mm.ui.conversation.fh5"};
        Class<?> adapterClass = null;
        for (String candidate : adapterCandidates) {
            try {
                adapterClass = XposedHelpers.findClass(candidate, cl);
                break;
            } catch (Throwable ignored) {}
        }
        if (adapterClass == null) {
            logBoth("adapter ctxMenu: adapter class not found (8.0.78 renamed)");
            return;
        }
        try {
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

    /** 兜底：包装 ConversationListView 的 OnItemLongClickListener，长按时直接弹出分组对话框
     *  (不依赖微信 ContextMenu 框架，微信 8.0.49 可能使用自定义弹窗) */
    private static final java.util.Set<Object> sLongClickWrapped = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static void tryInstallItemLongClick() {
        try {
            XposedBridge.hookAllMethods(android.widget.AbsListView.class,
                    "setOnItemLongClickListener",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.thisObject != sConvListView) return;
                                if (sLongClickWrapped.contains(p.thisObject)) return;
                                Object original = p.args[0];
                                if (original == null) return;
                                final AdapterView.OnItemLongClickListener orig =
                                        (AdapterView.OnItemLongClickListener) original;
                                AdapterView.OnItemLongClickListener wrapper =
                                        new AdapterView.OnItemLongClickListener() {
                                    @Override
                                    public boolean onItemLongClick(AdapterView<?> parent, View view,
                                                                   int position, long id) {
                                        boolean consumed = false;
                                        try { consumed = orig.onItemLongClick(parent, view, position, id); }
                                        catch (Throwable ignored) {}
                                        try {
                                            String username = extractUsernameFromView(view);
                                            if (TextUtils.isEmpty(username)) {
                                                username = extractUsernameFromList(parent, position);
                                            }
                                            if (!TextUtils.isEmpty(username)) {
                                                logBoth("itemLongClick user=" + username);
                                                 sCurrentActivity = new WeakReference<>((Activity) view.getContext());
                                                showFullGroupDialogForUsername(username);
                                            }
                                        } catch (Throwable ignored) {}
                                        return consumed;
                                    }
                                };
                                sLongClickWrapped.add(p.thisObject);
                                XposedHelpers.callMethod(p.thisObject, "setOnItemLongClickListener", wrapper);
                                logBoth("itemLongClick wrapper installed");
                            } catch (Throwable ignored) {}
                        }
                    });
            logBoth("itemLongClick hook installed");
        } catch (Throwable e) { logBoth("itemLongClick: " + e.getMessage()); }
    }

    private static String extractUsernameFromList(AdapterView<?> parent, int position) {
        try {
            Object adapter = parent.getAdapter();
            if (adapter == null) return null;
            int headerCount = 0;
            try {
                Object h = XposedHelpers.getObjectField(adapter, "mHeaderViewInfos");
                if (h instanceof List) headerCount = ((List<?>) h).size();
            } catch (Throwable ignored) {}
            Object item = null;
            if (adapter instanceof android.widget.ListAdapter) {
                item = ((android.widget.ListAdapter) adapter).getItem(position - headerCount);
            }
            if (item == null) return null;
            Object d = XposedHelpers.getObjectField(item, "d");
            if (d != null) return (String) XposedHelpers.callMethod(d, "i1");
        } catch (Throwable ignored) {}
        return null;
    }

    /** Fallback: hook Activity.onCreateContextMenu to catch LauncherUI long-press menus */
    private static void tryInstallActivityCtxMenuFallback() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreateContextMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!"com.tencent.mm.ui.LauncherUI".equals(p.thisObject.getClass().getName())) return;
                        ContextMenu menu = (ContextMenu) p.args[0];
                        View view = (View) p.args[1];
                        String username = sPendingUsername;
                        if (TextUtils.isEmpty(username)) username = extractUsernameFromView(view);
                        if (!TextUtils.isEmpty(username)) {
                            logBoth("Activity ctxMenu fallback user=" + username);
                            addGroupMenuItems(menu, username);
                        }
                    } catch (Throwable e) { logBoth("Activity ctxMenu fallback err: " + e.getMessage()); }
                }
            });
            logBoth("Activity ctxMenu fallback hooked");
        } catch (Throwable e) { logBoth("Activity ctxMenu fallback: " + e.getMessage()); }
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
        if (menu instanceof Menu) {
            addGroupMenuItemsInternal((Menu) menu, username);
            return;
        }
        addGroupMenuItemsInternal(menu, username);
    }

    private static void addGroupMenuItemsInternal(Menu menu, String username) {
        logBoth("addMenu start user=" + username + " menu=" + (menu != null ? menu.getClass().getName() : "null"));
        if (TextUtils.isEmpty(username)) { logBoth("addMenu return: empty user"); return; }
        final Context ctx = sCurrentActivity != null ? sCurrentActivity.get() : null;
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
        Activity ctx = sCurrentActivity != null ? sCurrentActivity.get() : null;
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

    private static boolean isBuiltInLabel(int labelId) {
        return labelId == ChatGroupHook.LABEL_ID_GROUP || labelId == ChatGroupHook.LABEL_ID_FRIEND || labelId == ChatGroupHook.LABEL_ID_SERVICE;
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
    private static int themeAccent() { return isDarkMode(getCurrentActivityContext()) ? 0xFFC084FC : 0xFFA855F7; }
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
