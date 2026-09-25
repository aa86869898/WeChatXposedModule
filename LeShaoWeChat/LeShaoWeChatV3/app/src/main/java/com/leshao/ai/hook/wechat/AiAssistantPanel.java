package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import android.widget.SeekBar;
import android.widget.Switch;
import android.window.OnBackInvokedDispatcher;
import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.config.AppConfig;
import com.leshao.ai.config.ConversationConfig;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;
import com.leshao.v3.ui.TTSPageView;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.ui.widgets.AiIconDrawable;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.SectionHeader;
import com.leshao.v3.ui.widgets.SegmentedControl;
import com.leshao.v3.ui.widgets.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 助手弹窗(v967): 微信会话页 ⋮ 菜单点击后在微信进程内展示。
 *
 * <p>PopupWindow 展示(v962 起, AlertDialog 在微信 3180 不可靠)。首页放全部功能开关与
 * 配置入口(模型提供商 / AI 核心参数配置), 不再有「完整设置」二级总入口;
 * TTS 开关语义: 开=AI 回复转成语音消息发出, 关=直接发文本。</p>
 *
 * <p>v967 关键修复: 首页 PopupWindow 高度改为固定 85% 屏高 + 中部 ScrollView weight=1,
 * 解决 WRAP_CONTENT 内容超高时底部按钮被屏幕裁剪、点击不到的问题。</p>
 */
public final class AiAssistantPanel {

    private static final String TAG = "LeshaoAI.AiAssistantPanel";
    /** SeekBar 进度 → 温度(0..200 → 0.0..2.0) */
    private static final double TEMP_SCALE = 100.0 / 2.0;
    /** 当前打开的弹窗, 用于面板间切换时先关旧窗 */
    private static volatile PopupWindow sPopup;
    /** v1059: 当前弹窗的尺寸约束, 供内容异步加载完成后重新「收紧」窗口高度 */
    private static int sPopupPanelW;
    private static int sPopupMaxH;
    private static int sPopupX;
    private static int sPopupAvailTop;
    private static int sPopupAvailBottom;
    /** v985: 模板编辑草稿, 跨「选择音色」子页面保留未保存改动(新建模板时无正式名可持久化) */
    private static ConversationConfig.Entry sTemplateDraft;
    /** v996: 「AI回复个性化配置」列表当前 tab(0=全部 1=群聊 2=联系人), 返回时保持。 */
    private static volatile int sConvListTab = 0;

    private AiAssistantPanel() {
    }

    // ==================== 弹窗基础设施(v962 PopupWindow) ====================

    /** 取弹窗锚点: 优先 decorView, 其次 contentView; 均不可用返回 null */
    private static View resolveAnchor(Activity activity) {
        if (activity == null || activity.isFinishing()) return null;
        try {
            View decor = activity.getWindow().peekDecorView();
            if (decor != null) return decor;
        } catch (Throwable ignored) {}
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content != null) return content;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 关闭当前弹窗(静默) */
    private static void dismissCurrent() {
        try {
            PopupWindow pw = sPopup;
            sPopup = null;
            if (pw != null && pw.isShowing()) pw.dismiss();
        } catch (Throwable ignored) {}
    }

    /**
     * 以 PopupWindow 居中展示面板。构建/展示全链路 try-catch + LogWriter 日志,
     * 任何阶段失败只记日志不抛泡到菜单点击(避免"点了没反应"且无迹可查)。
     *
     * @param heightPx 弹窗高度(像素), <=0 表示 WRAP_CONTENT
     */
    private static void showPopup(Activity activity, View root, int heightPx, String scene) {
        showPopup(activity, root, heightPx, scene, null);
    }

    /**
     * 以 PopupWindow 居中展示面板。构建/展示全链路 try-catch + LogWriter 日志,
     * 任何阶段失败只记日志不抛泡到菜单点击(避免"点了没反应"且无迹可查)。
     *
     * @param heightPx 弹窗高度(像素), <=0 表示 WRAP_CONTENT
     * @param backAction 系统返回键动作; null 时非主页面板返回 = 回 AI 助手首页,
     *                   主页面板返回 = 仅关闭
     */
    private static void showPopup(Activity activity, View root, int heightPx, String scene,
                                  final Runnable backAction) {
        try {
            LogWriter.log(TAG, "showPopup: scene=" + scene + " h=" + heightPx);
            View anchor = resolveAnchor(activity);
            if (anchor == null) {
                LogWriter.log(TAG, "showPopup FAILED: anchor null (activity="
                        + (activity == null ? "null" : activity.getClass().getName()) + ")");
                toastQuiet(activity, "当前页面无法显示弹窗");
                return;
            }
            dismissCurrent();

            android.util.DisplayMetrics dm = anchor.getResources().getDisplayMetrics();
            android.content.Context actx = anchor.getContext();
            // v1047: 弹窗最大宽度占屏 92%
            int panelW = (int) (dm.widthPixels * 0.92f);

            // v985: 可用显示区改为以「物理屏 - 真实系统栏内边距」为准。此前依赖
            // getWindowVisibleDisplayFrame, 在 ColorOS 上会返回比物理屏更大的 frame, 且
            // navigation_bar_height 取不到时回退 0, 导致弹窗居中后底部按钮落到屏幕/手势区
            // 之外被裁掉。现在内边距优先取根窗口 WindowInsets, 并有 48dp 兜底。
            int screenH = dm.heightPixels;
            int insetTop = InsetsUtil.topInset(actx, anchor);
            int insetBottom = InsetsUtil.bottomInset(actx, anchor);
            int availTop = Math.max(0, insetTop);
            int availBottom = screenH - Math.max(0, insetBottom);
            if (availBottom - availTop < dp(actx, 240)) {
                availTop = dp(actx, 24);
                availBottom = screenH - dp(actx, 48);
            }
            int availH = Math.max(dp(actx, 240), availBottom - availTop);
            int margin = dp(actx, 12);
            int maxPanelH = Math.max(dp(actx, 200), availH - margin * 2);

            // v985: 面板高度改为「确定值」并让根视图 MATCH_PARENT 填满。
            // 内容不超限时按内容高度(紧凑); 超限时压缩滚动区后取 maxPanelH,
            // 由于高度确定, 底部按钮恒定落在可用区内, 不再随内容被推出屏幕。
            boolean wrap = heightPx <= 0;
            int panelH;
            try {
                // v1059: 先按自然高度(UNSPECIFIED)测量, 才能发现内容是否超过可用高度。
                // 若用 AT_MOST 测量, 测量值会被直接截断为 maxPanelH, 溢出量恒为 0,
                // 下方「压缩滚动区」的逻辑永不触发, 结果内容溢出窗口、底部按钮被裁掉。
                final int unboundSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                final int widthSpec = View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY);
                root.measure(widthSpec, unboundSpec);
                int measured = root.getMeasuredHeight();
                if (measured > maxPanelH) {
                    CappedScrollView sv = findCappedScroll(root);
                    if (sv != null) {
                        int cur = sv.getMaxHeight();
                        int newCap = Math.max(dp(actx, 100),
                                (cur > 0 ? cur : maxPanelH) - (measured - maxPanelH));
                        sv.setMaxHeight(newCap);
                        root.measure(widthSpec, unboundSpec);
                        measured = root.getMeasuredHeight();
                    }
                }
                if (measured > maxPanelH) {
                    // 兜底: 滚动区仍不足以吸收, 或本就无可压缩滚动区(如外层 ScrollView),
                    // 以 maxPanelH 作为确定高度, 由外层滚动区保证内容可达。
                    measured = maxPanelH;
                }
                if (!wrap) {
                    panelH = Math.min(heightPx, maxPanelH);
                } else {
                    // 留 2dp 余量吸收 dp→px 取整误差, 避免底部按钮下沿被裁 1~2px。
                    int slack = measured < maxPanelH ? dp(actx, 2) : 0;
                    panelH = Math.min(Math.max(dp(actx, 120), measured + slack), maxPanelH);
                }
            } catch (Throwable t) {
                panelH = wrap ? maxPanelH : Math.min(heightPx, maxPanelH);
            }

            try {
                root.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable ignored) {}

            PopupWindow pw = new PopupWindow(root, panelW, panelH, true);
            pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            try { pw.setElevation(dp(actx, 8)); } catch (Throwable ignored) {}
            pw.setOutsideTouchable(true);
            // 高度已确定且底部按钮在可用区内, 键盘改为压缩滚动区自适应的 RESIZE。
            pw.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            sPopup = pw;
            sPopupPanelW = panelW;
            sPopupMaxH = maxPanelH;
            sPopupX = Math.max(0, (dm.widthPixels - panelW) / 2);
            sPopupAvailTop = availTop;
            sPopupAvailBottom = availBottom;
            try {
                int x = Math.max(0, (dm.widthPixels - panelW) / 2);
                int y = availTop + Math.max(0, (availH - panelH) / 2);
                // 兜底: 保证弹窗下沿不越过可用区下沿(否则底部按钮会被裁掉)
                if (y + panelH > availBottom) {
                    y = Math.max(availTop, availBottom - panelH);
                }
                pw.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, x, y);
                // v1015: 登记到全局返回栈；非主页面板的返回键 = 回到 AI 助手首页(上一层)
                // v1019: 三级窗口(convedit/tplpick/tpledit)可通过 backAction 返回各自父级
                final Runnable onBack;
                if (backAction != null) {
                    onBack = () -> {
                        dismissCurrent();
                        backAction.run();
                    };
                } else {
                    onBack = "main".equals(scene) ? null : () -> {
                        dismissCurrent();
                        show(activity);
                    };
                }
                com.leshao.v3.ui.UiBackStack.push(pw, pw::dismiss, onBack);
                registerOnBackCallback(root, onBack, scene);
                LogWriter.log(TAG, "showPopup OK: scene=" + scene + " panelH=" + panelH
                        + " availTop=" + availTop + " availBottom=" + availBottom
                        + " availH=" + availH + " y=" + y);
            } catch (Throwable t) {
                sPopup = null;
                LogWriter.log(TAG, "showPopup showAtLocation FAILED: scene=" + scene
                        + " err=" + android.util.Log.getStackTraceString(t));
                toastQuiet(activity, "弹窗显示失败");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "showPopup err: scene=" + scene + " err=" + t);
        }
    }

    /**
     * v1057: Android 13+ 预测性返回会走 {@link OnBackInvokedDispatcher}，不再经过
     * {@code PopupDecorView.dispatchKeyEvent}，使 {@link com.leshao.v3.ui.UiBackInstaller}
     * 的返回键 hook 失效（返回键直接关窗、不回到上一层）。此处为面板补登记
     * OnBackInvokedCallback，与 {@link com.leshao.v3.ui.UiBackStack} 语义一致：
     * 有 onBack 时先关当前窗再打开父级，无 onBack（首页）时仅关闭。
     */
    private static void registerOnBackCallback(View root, final Runnable onBack, final String scene) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            View top = root;
            ViewParent parent = top.getParent();
            while (parent instanceof View) {
                top = (View) parent;
                parent = top.getParent();
            }
            OnBackInvokedDispatcher dispatcher = top.findOnBackInvokedDispatcher();
            if (dispatcher == null) {
                LogWriter.log(TAG, "registerOnBackCallback: dispatcher null, scene=" + scene);
                return;
            }
            dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        LogWriter.log(TAG, "onBackInvoked: scene=" + scene);
                        if (onBack != null) {
                            try {
                                onBack.run();
                            } catch (Throwable ignored) {
                            }
                        } else {
                            dismissCurrent();
                        }
                    });
        } catch (Throwable t) {
            LogWriter.log(TAG, "registerOnBackCallback 失败: scene=" + scene + " err=" + t);
        }
    }

    /**
     * v1059: 内容异步加载完成后重新收紧弹窗高度(如配音魔方音色列表)。窗口高度按内容自然高度
     * 收缩(上限不变), 并重新居中, 避免列表很短时窗口底部留下大片空白。
     */
    private static void relayoutPopup() {
        final PopupWindow pw = sPopup;
        if (pw == null || !pw.isShowing()) return;
        try {
            final View content = pw.getContentView();
            final Context ctx = content != null ? content.getContext() : null;
            if (content == null || ctx == null || sPopupPanelW <= 0) return;
            final int maxH = sPopupMaxH > 0 ? sPopupMaxH : pw.getHeight();
            final int widthSpec = View.MeasureSpec.makeMeasureSpec(sPopupPanelW, View.MeasureSpec.EXACTLY);
            final int unboundSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            content.measure(widthSpec, unboundSpec);
            int measured = content.getMeasuredHeight();
            if (measured > maxH) {
                CappedScrollView sv = findCappedScroll(content);
                if (sv != null) {
                    int cur = sv.getMaxHeight();
                    int newCap = Math.max(dp(ctx, 100),
                            (cur > 0 ? cur : maxH) - (measured - maxH));
                    sv.setMaxHeight(newCap);
                    content.measure(widthSpec, unboundSpec);
                    measured = content.getMeasuredHeight();
                }
            }
            if (measured > maxH) measured = maxH;
            int slack = measured < maxH ? dp(ctx, 2) : 0;
            int h = Math.min(Math.max(dp(ctx, 120), measured + slack), maxH);
            int availH = Math.max(dp(ctx, 120), sPopupAvailBottom - sPopupAvailTop);
            int y = sPopupAvailTop + Math.max(0, (availH - h) / 2);
            if (y + h > sPopupAvailBottom) y = Math.max(sPopupAvailTop, sPopupAvailBottom - h);
            pw.update(sPopupX, y, sPopupPanelW, h);
            LogWriter.log(TAG, "relayoutPopup: h=" + h + " y=" + y);
        } catch (Throwable t) {
            LogWriter.log(TAG, "relayoutPopup err: " + t);
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 状态栏高度(px) —— 统一走 InsetsUtil。 */
    private static int systemInsetTop(Context ctx) {
        return InsetsUtil.statusBarHeight(ctx);
    }

    /** 底部系统栏(导航栏/手势条)高度(px) —— 统一走 InsetsUtil。 */
    private static int systemInsetBottom(Context ctx, View anchor) {
        return InsetsUtil.bottomInset(ctx, anchor);
    }

    /** 把模型提供商面板的填写内容持久化(接口地址/密钥/模型/温度)。 */
    private static boolean persistProvider(AppConfig config, EditText etBaseUrl, EditText etApiKey,
                                           EditText etModel, SeekBar seekTemp) {
        if (config == null) return false;
        try {
            config.setBaseUrl(str(etBaseUrl).trim());
            config.setApiKey(com.leshao.ai.api.ApiUrl.normalizeKey(str(etApiKey)));
            config.setModel(str(etModel).trim());
            // v1019: 记录已使用的模型到历史
            String m = str(etModel).trim();
            if (!m.isEmpty()) config.recordModel(m);
            if (seekTemp != null) {
                config.setTemperature(seekTemp.getProgress() / TEMP_SCALE);
            }
            return config.save();
        } catch (Throwable t) {
            LogWriter.log(TAG, "persistProvider err: " + t);
            return false;
        }
    }

    private static TextView fieldLabel(Context ctx, String text) {
        return M3Page.fieldLabel(ctx, text);
    }

    private static LinearLayout newRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.dialogBg(ctx));
        // v968: 左右边距收紧(原 20dp), 让右侧开关等尾部控件更贴边不局促
        // v971: 底部内边距加大, 让底部按钮不贴边
        // v974: 底部内边距 16 -> 18dp, 底部栏按钮距对话窗下沿再加 2dp
        // v978: 底部内边距 18 -> 21dp, 底部按钮下沿再多留 3dp(窗口整体高 3dp)
        root.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 10), dp(ctx, 12));
        InsetsUtil.clipRounded(root);
        return root;
    }

    /**
     * v974: AI 助手专用菜单行 —— 左内边距收窄, 让左侧图标对齐到对话窗左边 18dp
     * (root 左内边距 16dp + 行左内边距 2dp); 右内边距收窄, 让右侧开关/箭头对齐到右边 18dp
     * (root 右内边距 12dp + 行右内边距 6dp)。上下内边距保持 SettingRow 默认值。
     */
    private static SettingRow newRow(Context ctx, String icon, String title, String sub) {
        SettingRow row = new SettingRow(ctx, icon, title, sub);
        try {
            row.setPadding(dp(ctx, 2), row.getPaddingTop(), dp(ctx, 6), row.getPaddingBottom());
        } catch (Throwable ignored) {}
        return row;
    }

    /** v1064: 彩色圆底自绘图标行(参见 AiIconDrawable)。 */
    private static SettingRow newRow(Context ctx, int glyph, String title, String sub) {
        SettingRow row = SettingRow.withIconDrawable(ctx, AiIconDrawable.of(glyph), title, sub);
        try {
            row.setPadding(dp(ctx, 2), row.getPaddingTop(), dp(ctx, 6), row.getPaddingBottom());
        } catch (Throwable ignored) {}
        return row;
    }

    /** v974: AI 助手专用分组标题 —— 左对齐到 18dp(root 16 + 2), 右内边距保持原值。 */
    private static SectionHeader newSection(Context ctx, String title, String sub) {
        SectionHeader h = new SectionHeader(ctx, title, sub);
        try {
            h.setPadding(dp(ctx, 2), h.getPaddingTop(), h.getPaddingRight(), h.getPaddingBottom());
        } catch (Throwable ignored) {}
        return h;
    }

    private static TextView newTitle(Context ctx, String text) {
        return M3Page.title(ctx, text);
    }

    /** 内容超出上限才可滚动、否则按内容收缩的 ScrollView(v1047: 支持 min/max 硬性高度约束)。 */
    private static final class CappedScrollView extends ScrollView {
        private int mMaxHeight;
        private int mMinHeight;

        CappedScrollView(Context c) {
            super(c);
        }

        void setMaxHeight(int h) {
            mMaxHeight = h;
        }

        int getMaxHeight() {
            return mMaxHeight;
        }

        void setMinHeight2(int h) {
            mMinHeight = h;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int cap = mMaxHeight;
            int mode = MeasureSpec.getMode(heightSpec);
            // v969: 同时遵守父容器(弹窗可见区/键盘弹出后的可用高度)给出的上限,
            // 避免固定 0.6 屏高在可用空间变小时撑破弹窗、底部按钮被裁掉。
            if (mode != MeasureSpec.UNSPECIFIED && cap > 0) {
                cap = Math.min(cap, MeasureSpec.getSize(heightSpec));
            }
            if (cap > 0) {
                heightSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
            // 硬性高度下限: 内容再少也不能缩到 min 以下(避免滚动容器塌陷, 结合上方 AT_MOST 上限
            // 实现 Compose heightIn(min, max) 语义: 高度恒落在 [min, max], 超出 max 仅在内部滚动).
            if (mMinHeight > 0) {
                int measured = getMeasuredHeight();
                if (measured < mMinHeight) {
                    setMeasuredDimension(getMeasuredWidth(), mMinHeight);
                }
            }
        }
    }

    /** 弹窗可滚动内容区: 高度按内容收缩, 上限 maxHeightPx(超出才滚动) */
    private static ScrollView newScroll(LinearLayout root, LinearLayout list, int maxHeightPx) {
        return newScroll(root, list, maxHeightPx, 0);
    }

    /** 弹窗可滚动内容区: 高度按内容收缩, 硬性落在 [minHeightPx, maxHeightPx], 超出 max 仅内部滚动。 */
    private static ScrollView newScroll(LinearLayout root, LinearLayout list,
                                        int maxHeightPx, int minHeightPx) {
        CappedScrollView scroll = new CappedScrollView(root.getContext());
        scroll.setMaxHeight(maxHeightPx);
        if (minHeightPx > 0) scroll.setMinHeight2(minHeightPx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);
        return scroll;
    }

    /** 深度优先查找面板内的收缩滚动区(用于按溢出量动态降低其高度上限)。 */
    private static CappedScrollView findCappedScroll(View v) {
        if (v == null) return null;
        if (v instanceof CappedScrollView) return (CappedScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                CappedScrollView r = findCappedScroll(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 底部两按钮行(等宽): 左 / 右 */
    private static LinearLayout newBtnRow2(Context ctx, ModernButton left, ModernButton right) {
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpLeft.setMargins(0, dp(ctx, 16), dp(ctx, 4), 0);
        LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpRight.setMargins(dp(ctx, 4), dp(ctx, 16), 0, 0);
        left.setLayoutParams(lpLeft);
        right.setLayoutParams(lpRight);
        btnRow.addView(left);
        btnRow.addView(right);
        return btnRow;
    }

    /** 底部三按钮行(等宽) */
    private static LinearLayout newBtnRow(Context ctx, ModernButton left, ModernButton mid, ModernButton right) {
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpSide = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpSide.setMargins(0, dp(ctx, 16), 0, 0);
        LinearLayout.LayoutParams lpMid = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpMid.setMargins(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), 0);
        left.setLayoutParams(lpSide);
        mid.setLayoutParams(lpMid);
        right.setLayoutParams(lpSide);
        btnRow.addView(left);
        btnRow.addView(mid);
        btnRow.addView(right);
        return btnRow;
    }

    // ==================== 主弹窗(功能开关 + 配置入口) ====================

    public static void show(Activity activity) {
        LogWriter.log(TAG, "show: enter activity="
                + (activity == null ? "null" : activity.getClass().getName()));
        Log.i(TAG, "show: enter");
        if (activity == null || activity.isFinishing()) {
            LogWriter.log(TAG, "show skipped: activity null/finishing");
            toastQuiet(activity, "当前页面已关闭");
            return;
        }
        final Context ctx = activity;

        AppConfig cfg = null;
        try {
            cfg = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "AIBotCore.config err: " + t);
        }
        if (cfg == null) {
            // 自愈: installCore 因时序未执行时, 由面板兜底完成核心初始化
            LogWriter.log(TAG, "show: config null, 尝试惰性初始化 AI 核心");
            try {
                AIBotCore.ensureInit(ctx);
            } catch (Throwable t) {
                LogWriter.log(TAG, "show: 惰性初始化 AIBotCore 失败: " + t);
            }
            try {
                ConfigBridge.syncFromProvider(ctx);
            } catch (Throwable t) {
                LogWriter.log(TAG, "show: 配置同步失败: " + t);
            }
            try {
                AIBotCore.reload();
            } catch (Throwable ignored) {
            }
            try {
                cfg = AIBotCore.config();
            } catch (Throwable t) {
                LogWriter.log(TAG, "show: 重新读取 config 失败: " + t);
            }
            LogWriter.log(TAG, "show: 惰性初始化后 config=" + (cfg == null ? "null" : "ok"));
        }
        final AppConfig config = cfg;

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "AI 助手"));

        if (config == null) {
            LogWriter.log(TAG, "show: config null, 仅展示提示");
            root.addView(M3Page.note(ctx, "AI 核心尚未初始化, 请稍后重试。"));
        } else {
            LinearLayout list = new LinearLayout(ctx);
            newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.60f));

            // ---- 1. 功能开关 ----
            list.addView(newSection(ctx, "功能开关", "修改后即时生效"));
            list.addView(newRow(ctx, AiIconDrawable.G_SPARK, "AI 助手", "总开关,关闭后全部 AI 能力停用")
                    .switchOn(config.isEnabled(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: AI助手总开关 -> " + checked);
                        persist(ctx, config, c -> c.setEnabled(checked), "AI助手已" + (checked ? "开启" : "关闭"));
                    }));
            list.addView(newRow(ctx, AiIconDrawable.G_VOICE, "语音消息发送", "开=AI回复转语音消息发出; 关=直接发文本")
                    .switchOn(config.isTtsEnabled(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 语音消息发送 -> " + checked);
                        persist(ctx, config, c -> c.setTtsEnabled(checked), "语音消息发送已" + (checked ? "开启" : "关闭"));
                    }));

            // ---- 2. AI回复个性化配置 ----
            // v996: 群聊/联系人二合一, 纯入口(无行内开关); 仅「已配置且启用」的会话触发 AI。
            // 原「群聊/私聊自动回复」「仅被@时回复」全局开关已被会话级个性化配置取代。
            list.addView(newSection(ctx, "AI回复个性化配置", "仅已配置且启用的会话触发 AI"));
            list.addView(newRow(ctx, AiIconDrawable.G_SLIDERS, "AI回复个性化配置",
                    overrideSub() + " · 点整行进入配置").arrow(() -> {
                LogWriter.log(TAG, "click: 进入AI回复个性化配置");
                dismissCurrent();
                showConversationList(activity);
            }));

            // ---- 3. 模型与参数(点击进入配置) ----
            list.addView(newSection(ctx, "模型与参数", "服务商接入与核心参数"));
            final String providerSub = providerLabel(providerTypeOf(config.getProviderType()))
                    + (TextUtils.isEmpty(config.getModel()) ? "" : " · " + config.getModel());
            list.addView(newRow(ctx, AiIconDrawable.G_CLOUD, "模型提供商", providerSub)
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: 模型提供商");
                        dismissCurrent();
                        showProviderConfig(activity);
                    }));
            final String coreSub = (TextUtils.isEmpty(config.getBotName()) ? "未命名" : config.getBotName())
                    + " · 记忆 " + config.getMaxHistoryMessages() + " 条";
            list.addView(newRow(ctx, AiIconDrawable.G_CHIP, "AI 核心参数配置", coreSub)
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: AI核心参数配置");
                        dismissCurrent();
                        showCoreConfig(activity);
                    }));
            list.addView(newRow(ctx, AiIconDrawable.G_LAYERS, "模板配置", templateSub())
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: 模板配置");
                        dismissCurrent();
                        showTemplateList(activity);
                    }));
        }

        // ---- 底部栏: 关闭 ----
        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_PRIMARY);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click: 关闭");
            dismissCurrent();
        });

        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpClose.setMargins(0, dp(ctx, 16), 0, 0);
        btnClose.setLayoutParams(lpClose);
        root.addView(btnClose);
        // v968: WRAP_CONTENT 高度, 面板按内容收缩, 底部栏紧贴内容
        showPopup(activity, root, 0, "main");
    }

    // ==================== 二级: 模型提供商 ====================

    private static void showProviderConfig(final Activity activity) {
        LogWriter.log(TAG, "showProviderConfig: enter");
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;

        final AppConfig config;
        try {
            config = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "showProviderConfig config err: " + t);
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        if (config == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "模型提供商"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.60f));

        // ---- 服务商 ----
        list.addView(newSection(ctx, "服务商", "API 协议类型"));
        final SettingRow[] providerRows = new SettingRow[ProviderType.values().length];
        ProviderType[] types = ProviderType.values();
        for (int i = 0; i < types.length; i++) {
            final ProviderType pt = types[i];
            boolean sel = providerMatches(pt, config.getProviderType());
            SettingRow row = newRow(ctx, AiIconDrawable.G_CLOUD, providerLabel(pt), sel ? "当前使用" : "点击选择");
            providerRows[i] = row;
            row.arrow(() -> {
                LogWriter.log(TAG, "click: 服务商 -> " + pt.name());
                try {
                    config.setProviderType(pt.name().toLowerCase(Locale.US));
                    for (int j = 0; j < providerRows.length; j++) {
                        providerRows[j].setSub(j == pt.ordinal() ? "当前使用" : "点击选择");
                    }
                    toastQuiet(ctx, "已选择 " + providerLabel(pt));
                } catch (Throwable t) {
                    LogWriter.log(TAG, "setProviderType err: " + t);
                }
            });
            list.addView(row);
        }

        // ---- 接口 ----
        list.addView(newSection(ctx, "接口", "服务商提供的接入信息"));
        // 温度 SeekBar 在下方创建, 用 holder 让上方按钮的持久化回调能拿到它
        final SeekBar[] tempRef = new SeekBar[1];
        final TextView lbUrl = fieldLabel(ctx, "接口地址");
        list.addView(lbUrl);
        final EditText etBaseUrl = M3Page.input(ctx, "如 https://api.deepseek.com");
        M3Page.trimEdgesOnInput(etBaseUrl);
        etBaseUrl.setText(safe(config.getBaseUrl()));
        list.addView(etBaseUrl);
        final TextView lbKey = fieldLabel(ctx, "Api Key密钥");
        list.addView(lbKey);
        final EditText etApiKey = M3Page.input(ctx, "sk-...");
        M3Page.trimEdgesOnInput(etApiKey);
        etApiKey.setText(safe(config.getApiKey()));
        list.addView(etApiKey);
        final TextView lbModel = fieldLabel(ctx, "模型名称");
        list.addView(lbModel);

        // 模型名称 + 「获取模型」按钮同一行
        LinearLayout modelRow = new LinearLayout(ctx);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etModel = M3Page.input(ctx, "如 deepseek-chat");
        M3Page.trimEdgesOnInput(etModel);
        etModel.setText(safe(config.getModel()));
        etModel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modelRow.addView(etModel);
        final ModernButton btnFetch = new ModernButton(ctx, "获取模型", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpFetch = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpFetch.setMargins(dp(ctx, 8), 0, 0, 0);
        btnFetch.setLayoutParams(lpFetch);
        modelRow.addView(btnFetch);
        list.addView(modelRow);

        // 拉取到的模型列表(内联展示, 点击即填入模型名称)
        final LinearLayout modelResults = new LinearLayout(ctx);
        modelResults.setOrientation(LinearLayout.VERTICAL);
        list.addView(modelResults);

        btnFetch.onClick(() -> {
            final String base = str(etBaseUrl).trim();
            final String rawKey = str(etApiKey);
            final String key = com.leshao.ai.api.ApiUrl.normalizeKey(rawKey);
            final String ptype = config.getProviderType();
            LogWriter.log(TAG, "click(提供商): 获取模型 base=" + base
                    + " rawKeyLen=" + rawKey.length() + " cleanKeyLen=" + key.length()
                    + " key=" + com.leshao.ai.api.ApiUrl.mask(key));
            if (TextUtils.isEmpty(base)) {
                toastQuiet(ctx, "请先填写接口地址");
                return;
            }
            if (TextUtils.isEmpty(key)) {
                toastQuiet(ctx, "请先填写 Api Key 密钥");
                return;
            }
            if (rawKey.trim().length() != key.length()) {
                // 粘贴内容含空格/换行/引号/零宽字符等, 已自动清理
                toastQuiet(ctx, "密钥含多余字符，已自动清理后再试");
            }
            // 先把当前填写内容持久化, 避免后续丢失
            persistProvider(config, etBaseUrl, etApiKey, etModel, tempRef[0]);
            modelResults.removeAllViews();
            btnFetch.setText("获取中…");
            btnFetch.setEnabled(false);
            final Handler ui = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                List<String> models = null;
                String err = null;
                try {
                    models = com.leshao.ai.api.ModelCatalogClient.fetch(ptype, base, key);
                } catch (Throwable t) {
                    err = t.getMessage();
                    LogWriter.log(TAG, "fetchModels err: " + t);
                }
                final List<String> listFinal = models;
                final String errFinal = err;
                ui.post(() -> {
                    if (activity.isFinishing()) return;
                    btnFetch.setText("获取模型");
                    btnFetch.setEnabled(true);
                    modelResults.removeAllViews();
                    if (errFinal != null) {
                        toastQuiet(ctx, friendlyApiError(errFinal));
                        return;
                    }
                    if (listFinal == null || listFinal.isEmpty()) {
                        toastQuiet(ctx, "未获取到模型列表");
                        return;
                    }
                    int shown = 0;
                    for (String id : listFinal) {
                        if (shown >= 60) break;
                        final String modelId = id;
                        SettingRow row = newRow(ctx, AiIconDrawable.G_CHIP, modelId, "点击填入模型名称");
                        row.setOnClickListener(v -> {
                            etModel.setText(modelId);
                            modelResults.removeAllViews();
                            persistProvider(config, etBaseUrl, etApiKey, etModel, tempRef[0]);
                            toastQuiet(ctx, "已选择模型并保存: " + modelId);
                        });
                        modelResults.addView(row);
                        shown++;
                    }
                    toastQuiet(ctx, "共获取到 " + listFinal.size() + " 个模型");
                });
            }).start();
        });

        // ---- v1019: 历史已添加模型 ----
        final LinearLayout histList = new LinearLayout(ctx);
        histList.setOrientation(LinearLayout.VERTICAL);
        list.addView(newSection(ctx, "历史已添加模型", "点击选中即设为默认模型, 右侧可编辑/删除"));
        list.addView(histList);
        final Runnable[] renderHistory = new Runnable[1];
        renderHistory[0] = () -> {
            histList.removeAllViews();
            java.util.List<String> hist = config.getModelHistory();
            if (hist == null || hist.isEmpty()) {
                histList.addView(M3Page.note(ctx, "暂无历史记录"));
                return;
            }
            for (String mid : hist) {
                if (mid == null || mid.isEmpty()) continue;
                final String modelId = mid;
                final boolean isCur = modelId.equals(str(etModel).trim());
                SettingRow row = newRow(ctx, AiIconDrawable.G_CHIP, modelId,
                        isCur ? "当前模型" : "点击设为默认");
                row.setOnClickListener(v -> {
                    etModel.setText(modelId);
                    config.setModel(modelId);
                    config.recordModel(modelId);
                    config.save();
                    renderHistory[0].run();
                    toastQuiet(ctx, "已设为默认模型: " + modelId);
                });
                // 右侧编辑/删除
                row.setOnLongClickListener(v -> {
                    try {
                        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx)
                                .setTitle("历史模型")
                                .setItems(new String[]{"填入模型名称", "从历史删除"}, (dd, which) -> {
                                    if (which == 0) {
                                        etModel.setText(modelId);
                                        renderHistory[0].run();
                                    } else {
                                        config.removeModelHistory(modelId);
                                        config.save();
                                        renderHistory[0].run();
                                        toastQuiet(ctx, "已从历史删除: " + modelId);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .create();
                        dlg.setOnShowListener(d -> {
                            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                                    .setTextColor(AppColors.primary());
                        });
                        dlg.show();
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "history menu err: " + t);
                    }
                    return true;
                });
                histList.addView(row);
            }
        };
        renderHistory[0].run();
        // 模型/接口/密钥变更后刷新历史高亮
        etModel.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { renderHistory[0].run(); }
        });

        // ---- 生成参数(M3 卡片内嵌滑杆) ----
        list.addView(newSection(ctx, "生成参数", "温度越高回复越随机"));
        LinearLayout tempCard = M3Page.card(ctx);
        final TextView tvTemp = M3Page.note(ctx, "");
        tempCard.addView(tvTemp);
        final SeekBar seekTemp = M3Page.slider(ctx);
        seekTemp.setMax(200);
        tempRef[0] = seekTemp;
        tempCard.addView(seekTemp);
        list.addView(tempCard);
        int initProgress = Math.max(0, Math.min(200, (int) Math.round(config.getTemperature() * TEMP_SCALE)));
        seekTemp.setProgress(initProgress);
        updateTempLabel(tvTemp, initProgress);
        seekTemp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTempLabel(tvTemp, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // ---- 底部按钮 ----
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(提供商): 保存");
            boolean ok = persistProvider(config, etBaseUrl, etApiKey, etModel, seekTemp);
            LogWriter.log(TAG, "provider saved ok=" + ok);
            if (ok) reload();
            toastQuiet(ctx, ok ? "已保存" : "保存失败");
            dismissCurrent();
            show(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(提供商): 返回");
            dismissCurrent();
            show(activity);
        });

        root.addView(newBtnRow2(ctx, btnSave, btnClose));
        showPopup(activity, root, 0, "provider");
    }

    // ==================== 二级: AI 核心参数 ====================

    private static void showCoreConfig(final Activity activity) {
        LogWriter.log(TAG, "showCoreConfig: enter");
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;

        final AppConfig config;
        try {
            config = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "showCoreConfig config err: " + t);
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        if (config == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);

        // ---- 1. 头部区域(固定, 不参与滚动): 标题 + 身份 + 唤醒词 ----
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(newTitle(ctx, "AI 核心参数"));
        header.addView(newSection(ctx, "身份", "AI 对外展示的名字与唤醒词"));
        final EditText etBotName = M3Page.input(ctx, "AI 昵称, 如 小乐");
        etBotName.setText(safe(config.getBotName()));
        header.addView(etBotName);
        final EditText etWakeKeyword = M3Page.input(ctx, "唤醒词(多个用逗号分隔)");
        etWakeKeyword.setText(safe(config.getWakeKeyword()));
        header.addView(etWakeKeyword);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 2. 中间可滚动内容块(仅人设提示词): 高度按内容收缩, 上限 280dp ----
        // v1059: 去掉 120dp 硬性下限, 提示词较短时滚动区按内容收缩, 不再在上下留出大片空白;
        // 内容超出 280dp 时仅在容器内部滚动。
        LinearLayout body = new LinearLayout(ctx);
        newScroll(root, body, dp(ctx, 280));

        // v1048: 人设大文本放置于封顶滚动容器内. setMaxLines 让 EditText 高度按行自适应到底, 超过最大
        // 行数只在文本内部滚动, 不用 setMaxHeight 固定像素——后者在长文本时会残留大片空白显示区.
        body.addView(newSection(ctx, "人设提示词", "System Prompt, 决定 AI 的语气与身份"));
        final EditText etSystemPrompt = M3Page.input(ctx, "人设提示词");
        etSystemPrompt.setSingleLine(false);
        etSystemPrompt.setMinLines(4);
        etSystemPrompt.setMaxLines(8);
        etSystemPrompt.setGravity(Gravity.TOP);
        M3Page.enableVerticalScroll(etSystemPrompt);
        etSystemPrompt.setText(safe(config.getSystemPrompt()));
        body.addView(etSystemPrompt);

        // ---- 3. 上下文记忆(固定, 滚动容器外): 提示词区域 → 记忆输入框 → 按钮 ----
        root.addView(newSection(ctx, "上下文记忆", "带入对话的历史消息条数"));
        final EditText etMemory = M3Page.input(ctx, "记忆条数, 如 100");
        etMemory.setText(String.valueOf(config.getMaxHistoryMessages()));
        root.addView(etMemory, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 底部按钮 ----
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(核心参数): 保存");
            try {
                config.setBotName(str(etBotName));
                config.setWakeKeyword(str(etWakeKeyword));
                config.setSystemPrompt(str(etSystemPrompt));
                String mem = str(etMemory);
                if (!TextUtils.isEmpty(mem)) {
                    try {
                        config.setMaxHistoryMessages(Integer.parseInt(mem.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                boolean ok = config.save();
                LogWriter.log(TAG, "core saved ok=" + ok);
                reload();
                toastQuiet(ctx, "已保存");
            } catch (Throwable t) {
                LogWriter.log(TAG, "core save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            dismissCurrent();
            show(activity);
        });

        ModernButton btnReset = new ModernButton(ctx, "恢复默认", ModernButton.STYLE_GHOST);
        btnReset.onClick(() -> {
            LogWriter.log(TAG, "click(核心参数): 恢复默认");
            try {
                config.reset();
                config.save();
                reload();
                toastQuiet(ctx, "已恢复默认");
            } catch (Throwable t) {
                LogWriter.log(TAG, "reset err: " + t);
                toastQuiet(ctx, "恢复默认失败");
            }
            dismissCurrent();
            show(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(核心参数): 返回");
            dismissCurrent();
            show(activity);
        });

        root.addView(newBtnRow(ctx, btnSave, btnReset, btnClose));

        // v1049: 外层套一个可垂直滚动的 ScrollView 作为弹窗内容, 避免「头部+人设区+记忆+按钮」总高
        // 超过弹窗可用高度时把底部按钮挤出窗口、又无法上下滑动找回. 现在内容超高时整窗可滚动,
        // 底部按钮始终能通过滚到末尾看到.
        ScrollView outerScroll = new ScrollView(ctx);
        outerScroll.setFillViewport(false);
        outerScroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        outerScroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showPopup(activity, outerScroll, 0, "core");
    }

    // ==================== 三级: 按会话独立配置 ====================

    /** v996: 合并后的个性化配置概览(群 + 联系人)。 */
    private static String overrideSub() {
        try {
            ConversationConfig cc = AIBotCore.conversationConfig();
            if (cc == null) return "未配置";
            int g = cc.keysByType(true).size();
            int f = cc.keysByType(false).size();
            int n = g + f;
            return n == 0 ? "未配置, 点进可为群聊/联系人单独启用"
                    : "已配置 " + n + " 个会话(群 " + g + " · 人 " + f + ")";
        } catch (Throwable t) {
            return "未配置";
        }
    }

    private static String templateSub() {
        try {
            ConversationConfig cc = AIBotCore.conversationConfig();
            int n = cc == null ? 0 : cc.templateNames().size();
            return n == 0 ? "可快速套用到不同群 / 联系人" : "已有 " + n + " 个模板";
        } catch (Throwable t) {
            return "可快速套用到不同群 / 联系人";
        }
    }

    private static String label(String talker) {
        try {
            String n = ContactQuery.displayName(talker);
            if (n != null && !n.trim().isEmpty()) return n;
        } catch (Throwable ignored) {
        }
        return talker;
    }

    private static String entrySummary(ConversationConfig.Entry e) {
        if (e == null) return "继承全局";
        List<String> parts = new ArrayList<>();
        if (e.enabled != null) parts.add(e.enabled ? "AI:已启用" : "AI:已停用");
        if (e.autoReply != null) parts.add(e.autoReply ? "自动回复:开" : "自动回复:关");
        if (e.onlyWhenMentioned != null) parts.add(e.onlyWhenMentioned ? "仅@" : "全部消息");
        if (e.ttsEnabled != null) parts.add(e.ttsEnabled ? "语音" : "文本");
        if (!TextUtils.isEmpty(e.systemPrompt)) parts.add("自定义人设");
        if (!TextUtils.isEmpty(e.aiName)) parts.add("AI:" + e.aiName);
        if (!TextUtils.isEmpty(e.aiIdentity)) parts.add("自定义身份");
        if (e.memoryEnabled != null) parts.add(e.memoryEnabled ? "记忆:开" : "记忆:关");
        if (e.memoryLimit != null) parts.add("记忆:" + e.memoryLimit + "条");
        if (!TextUtils.isEmpty(e.model)) parts.add("模型:" + e.model);
        return parts.isEmpty() ? "继承全局" : TextUtils.join(" · ", parts);
    }

    private static Switch makeSwitch(Context ctx, boolean checked) {
        Switch sw = CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        float d = ctx.getResources().getDisplayMetrics().density;
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (AppColors.SWITCH_WIDTH_DP * d + 0.5f),
                (int) (AppColors.SWITCH_HEIGHT_DP * d + 0.5f)));
        return sw;
    }

    /** v985: 与全局相同则返回 null(不落库, 继续跟随全局); 不同才作为显式覆盖固化。 */
    private static Boolean explicitOrNull(boolean checked, boolean global) {
        return checked == global ? null : Boolean.valueOf(checked);
    }

    private static void addSwitchRow(LinearLayout list, Context ctx, Switch sw,
                                     String icon, String title, String sub) {
        settingRowWithSwitch(list, newRow(ctx, icon, title, sub), sw);
    }

    private static void addSwitchRow(LinearLayout list, Context ctx, Switch sw,
                                     int glyph, String title, String sub) {
        settingRowWithSwitch(list, newRow(ctx, glyph, title, sub), sw);
    }

    private static void settingRowWithSwitch(LinearLayout list, SettingRow row, Switch sw) {
        row.tail(sw);
        row.setOnClickListener(v -> {
            try { sw.toggle(); } catch (Throwable ignored) {}
        });
        list.addView(row);
    }

    /** v985: 会话/模板通用的「音色」区: 多选音色 + 多音色随机开关。 */
    private static void addVoiceSection(LinearLayout list, Context ctx, final Activity activity,
                                        final ConversationConfig.Entry entry, final Runnable persist,
                                        final Runnable onBack) {
        list.addView(newSection(ctx, "音色", "配音魔方音色, 可多选"));
        final Switch swRand = makeSwitch(ctx, entry.randomVoice != null && entry.randomVoice);
        list.addView(newRow(ctx, AiIconDrawable.G_EQ, "选择音色", voiceSummary(entry))
                .arrow(() -> {
                    dismissCurrent();
                    showVoicePicker(activity, entry, persist, onBack);
                }));
        addSwitchRow(list, ctx, swRand, AiIconDrawable.G_EQ, "多音色随机", "开=多选时每条随机取一个");
        swRand.setOnCheckedChangeListener((buttonView, isChecked) -> {
            entry.randomVoice = isChecked;
            if (persist != null) persist.run();
        });
    }

    private static String voiceSummary(ConversationConfig.Entry e) {
        if (e == null || e.voices == null || e.voices.isEmpty()) return "默认音色";
        if (e.voices.size() == 1) return "已选 1 个: " + e.voices.get(0);
        return "已选 " + e.voices.size() + " 个音色";
    }

    /** v985: 配音魔方音色多选页。选中结果写回 entry.voices 并触发 persist。 */
    private static void showVoicePicker(final Activity activity, final ConversationConfig.Entry entry,
                                        final Runnable persist, final Runnable onBack) {
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        // v985: 音色为异步拉取, 弹窗高度改为固定值, 避免拉取前按空内容测量导致列表被裁剪。
        final int popupH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.62f);
        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "选择音色"));
        final LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, Math.max(dp(ctx, 160), popupH - dp(ctx, 150)));

        final TextView loading = M3Page.note(ctx, "正在加载配音魔方音色...");
        list.addView(loading);

        final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
        if (entry.voices != null) selected.addAll(entry.voices);

        final Handler h = new Handler(Looper.getMainLooper());
        final String key = WmPrefs.getStr("tts_cube_key", "");
        new Thread(() -> {
            List<TTSPageView.VoiceItem> items;
            try {
                items = TTSPageView.fetchAllVoices(key);
            } catch (Throwable t) {
                LogWriter.log(TAG, "voices load err: " + t);
                items = new ArrayList<>();
            }
            final List<TTSPageView.VoiceItem> fi = items;
            h.post(() -> {
                if (activity.isFinishing()) return;
                list.removeAllViews();
                if (fi.isEmpty()) {
                    list.addView(M3Page.empty(ctx, "🎵",
                            "未获取到音色, 请先在「TTS 语音」页配置配音魔方 API Key"));
                    relayoutPopup();
                    return;
                }
                for (TTSPageView.VoiceItem vi : fi) {
                    String text = vi.displayName;
                    if (!TextUtils.isEmpty(vi.group)) text = vi.group + " · " + text;
                    final String voiceId = vi.voiceId;
                    list.addView(M3Page.checkRow(ctx, "🎵", text,
                            TextUtils.isEmpty(vi.actor) ? null : ("配音: " + vi.actor),
                            selected.contains(voiceId), on -> {
                                if (on) selected.add(voiceId);
                                else selected.remove(voiceId);
                            }));
                }
                relayoutPopup();
            });
        }).start();

        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            entry.voices = new ArrayList<>(selected);
            LogWriter.log(TAG, "click(音色): 保存 selected=" + entry.voices);
            if (persist != null) persist.run();
            toastQuiet(ctx, "已选 " + entry.voices.size() + " 个音色");
            dismissCurrent();
            if (onBack != null) onBack.run();
        });
        ModernButton btnClear = new ModernButton(ctx, "清空", ModernButton.STYLE_GHOST);
        btnClear.onClick(() -> {
            entry.voices = new ArrayList<>();
            LogWriter.log(TAG, "click(音色): 清空");
            if (persist != null) persist.run();
            dismissCurrent();
            if (onBack != null) onBack.run();
        });
        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(音色): 返回(未改动)");
            dismissCurrent();
            if (onBack != null) onBack.run();
        });

        root.addView(newBtnRow(ctx, btnSave, btnClear, btnClose));
        showPopup(activity, root, popupH, "voicepick");
    }

    private static void showConversationList(final Activity activity) {
        LogWriter.log(TAG, "showConversationList: enter tab=" + sConvListTab);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        if (AIBotCore.conversationConfig() == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        // v996: 先加载模块通讯录(全部群聊/联系人), 再构建合并后的「AI回复个性化配置」列表
        // v1016: 目标采集移至后台线程, 避免会话/联系人查询(含大量反射)阻塞主线程导致点击卡死
        com.leshao.v3.ContactRepository.loadAsync(() -> {
            if (activity.isFinishing()) return;
            final ConversationConfig cc = AIBotCore.conversationConfig();
            if (cc == null) return;
            new Thread(() -> {
                final List<String[]> groupTargets = collectTargets(true, cc);
                final List<String[]> friendTargets = collectTargets(false, cc);
                final List<String[]> allTargets = new ArrayList<>();
                allTargets.addAll(groupTargets);
                allTargets.addAll(friendTargets);
                sortTargets(allTargets, cc);
                sortTargets(groupTargets, cc);
                sortTargets(friendTargets, cc);
                if (activity.isFinishing()) return;
                activity.runOnUiThread(() -> buildConversationListUi(
                        activity, cc, groupTargets, friendTargets, allTargets));
            }, "leshao-ai-targets").start();
        });
    }

    /**
     * v996: 「AI回复个性化配置」—— 全部 / 群聊 / 联系人 三档 tab。
     * 顶部 tab 切换类型, 下方搜索栏按名称过滤, 列表点击进入独立配置。
     * 排序: 已配置(群/联系人)置顶, 其余按名称排序。
     * v1016: 数据采集已在外层完成, 此处仅负责 UI 构建(主线程)。
     */
    private static void buildConversationListUi(final Activity activity, final ConversationConfig cc,
                                                final List<String[]> groupTargets,
                                                final List<String[]> friendTargets,
                                                final List<String[]> allTargets) {
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "AI回复个性化配置"));

        // 顶部 tab: 全部(默认) / 群聊 / 联系人
        final int initial = (sConvListTab >= 0 && sConvListTab <= 2) ? sConvListTab : 0;
        SegmentedControl seg = new SegmentedControl(ctx,
                new String[]{"全部", "群聊", "联系人"}, initial);
        LinearLayout.LayoutParams lpSeg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSeg.setMargins(0, dp(ctx, 4), 0, 0);
        seg.setLayoutParams(lpSeg);
        root.addView(seg);

        // tab 下方搜索栏: 过滤群聊/联系人名称
        final EditText etSearch = M3Page.input(ctx, "搜索群聊 / 联系人");
        LinearLayout.LayoutParams lpSearch = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSearch.setMargins(0, dp(ctx, 10), 0, 0);
        etSearch.setLayoutParams(lpSearch);
        root.addView(etSearch);

        root.addView(M3Page.note(ctx,
                "仅「已配置且启用」的会话才触发 AI; 未配置的会话一律不响应。"));

        final LinearLayout body = new LinearLayout(ctx);
        newScroll(root, body, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.58f));

        final int[] curTab = {initial};
        final String[] query = {""};

        final Runnable render = () -> {
            body.removeAllViews();
            final int tab = curTab[0];
            List<String[]> all;
            if (tab == 1) {
                all = groupTargets;
            } else if (tab == 2) {
                all = friendTargets;
            } else {
                all = allTargets;
            }
            String q = query[0] == null ? "" : query[0].trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (String[] tgt : all) {
                if (tgt == null || tgt.length < 1 || tgt[0] == null) continue;
                final String talker = tgt[0];
                String name = (tgt.length > 1 && !TextUtils.isEmpty(tgt[1])) ? tgt[1] : talker;
                if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)
                        && !talker.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                final boolean g = (tgt.length > 2) ? "1".equals(tgt[2])
                        : ConversationConfig.isGroupTalker(talker);
                ConversationConfig.Entry e = cc.get(talker);
                boolean isCfg = e != null;
                boolean active = isCfg && e.isActive();
                String sub;
                if (!isCfg) {
                    sub = "未配置 · 点击启用 AI 服务";
                } else if (active) {
                    sub = "已启用 · " + entrySummary(e);
                } else {
                    sub = "已停用 · 点击进入可重新启用";
                }
                SettingRow row = newRow(ctx, g ? "👥" : "👤",
                        (active ? "✅ " : (isCfg ? "⏸ " : "")) + name, sub)
                        .avatar(talker)
                        .arrow(() -> {
                            LogWriter.log(TAG, "click: 个性化配置 " + talker);
                            // v1055: 先构建新弹窗(showPopup 内部再关旧窗), 任一环节异常都记录并回退到列表,
                            // 避免旧的 dismissCurrent 先关列表 + 后续异常被静默吞掉 = 面板全关回到聊天页。
                            try {
                                showConvEdit(activity, talker, g);
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "打开独立配置失败: "
                                        + android.util.Log.getStackTraceString(t));
                                toastQuiet(activity, "打开配置失败");
                                showConversationList(activity);
                            }
                        });
                body.addView(row);
                shown++;
            }
            if (shown == 0) {
                String kw = query[0] == null ? "" : query[0].trim();
                String what = tab == 1 ? "群聊" : (tab == 2 ? "联系人" : "群聊或联系人");
                if (!kw.isEmpty()) {
                    body.addView(M3Page.empty(ctx, "🔍", "未找到匹配「" + kw + "」的" + what));
                } else {
                    body.addView(M3Page.empty(ctx, "👥",
                            "未读取到" + what + ", 请确认已登录微信后重试。"));
                }
            }
        };

        seg.setOnSegmentChangedListener((idx, segLabel) -> {
            curTab[0] = idx;
            sConvListTab = idx;
            LogWriter.log(TAG, "tab(个性化配置): " + segLabel);
            render.run();
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void afterTextChanged(Editable s) {
                query[0] = s == null ? "" : s.toString();
                render.run();
            }
        });
        render.run();

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(个性化配置): 返回");
            dismissCurrent();
            show(activity);
        });
        root.addView(btnClose);

        showPopup(activity, root, 0, "convlist");
    }

    /**
     * v1018: 「AI回复个性化配置」列表改为以「聊天会话列表」为唯一数据源，
     * 与微信首页会话保持一致，不再枚举全部联系人与群聊（原 v1015/v1016 多源合并已移除）。
     * 已配置但不在当前会话列表中的目标仍会补入，方便查看/关闭。
     * 每项结构 {@code {talker, name, isGroup?"1":"0"}}。
     */
    private static List<String[]> collectTargets(final boolean isGroup, final ConversationConfig cc) {
        List<String[]> targets = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String grp = isGroup ? "1" : "0";
        // v1026: 数据源与「TTS 白名单」对齐 —— 直接枚举模块通讯录(ContactRepository)的
        // 全部群聊/联系人, 不再仅用微信首页会话列表(那样「联系人」tab 几乎为空)。
        try {
            List<com.leshao.v3.model.ContactCard> cards = isGroup
                    ? com.leshao.v3.ContactRepository.getGroups()
                    : com.leshao.v3.ContactRepository.getFriends();
            if (cards != null) {
                for (com.leshao.v3.model.ContactCard c : cards) {
                    if (c == null || TextUtils.isEmpty(c.username)) continue;
                    if (!seen.add(c.username)) continue;
                    String name = c.displayName();
                    targets.add(new String[]{c.username,
                            TextUtils.isEmpty(name) ? c.username : name, grp});
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collectTargets contacts err: " + t);
        }
        // 会话列表补充(可能含非好友/服务号等通讯录外的聊天目标)。
        try {
            List<String[]> sessions = ConversationQuery.listSessions();
            if (sessions != null) {
                for (String[] s : sessions) {
                    if (s == null || s.length < 1 || s[0] == null) continue;
                    if (ConversationConfig.isGroupTalker(s[0]) != isGroup) continue;
                    if (!seen.add(s[0])) continue;
                    String cand = (s.length > 1) ? s[1] : null;
                    targets.add(new String[]{s[0], bestName(s[0], cand), grp});
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collectTargets sessions err: " + t);
        }
        // 已配置但既不在通讯录也不在会话中的目标仍补入, 方便查看/关闭。
        final List<String> configured = cc.keysByType(isGroup);
        for (String talker : configured) {
            if (talker == null || !seen.add(talker)) continue;
            targets.add(new String[]{talker, bestName(talker, null), grp});
        }
        return targets;
    }

    /**
     * v1018: 解析显示名 —— 依次尝试会话名 / 进程内联系人存储 / 模块通讯录(DB)，
     * 均取不到时才回退 talker，避免列表只显示群聊 id/wxid。
     */
    private static String bestName(String talker, String candidate) {
        if (!TextUtils.isEmpty(candidate) && !candidate.equals(talker)) return candidate;
        String n = label(talker);
        if (!TextUtils.isEmpty(n) && !n.equals(talker)) return n;
        try {
            com.leshao.v3.model.ContactCard c = com.leshao.v3.ContactRepository.findByUsername(talker);
            if (c != null) {
                String d = c.displayName();
                if (!TextUtils.isEmpty(d)) return d;
            }
        } catch (Throwable ignored) {
        }
        return TextUtils.isEmpty(candidate) ? talker : candidate;
    }

    /** v996: 已配置(群/联系人)置顶, 其余按名称升序。 */
    private static void sortTargets(final List<String[]> targets, final ConversationConfig cc) {
        final java.util.Set<String> cfgSet = new java.util.HashSet<>(cc.keys());
        java.util.Collections.sort(targets, (a, b) -> {
            boolean ca = cfgSet.contains(a[0]);
            boolean cb = cfgSet.contains(b[0]);
            if (ca != cb) return ca ? -1 : 1;
            String na = (a.length > 1 && a[1] != null) ? a[1] : a[0];
            String nb = (b.length > 1 && b[1] != null) ? b[1] : b[0];
            return na.compareToIgnoreCase(nb);
        });
    }

    private static void showConvEdit(final Activity activity, final String talker,
                                     final boolean isGroup) {
        LogWriter.log(TAG, "showConvEdit: enter talker=" + talker + " group=" + isGroup);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        final AppConfig cfg = AIBotCore.config();
        if (cc == null || cfg == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        ConversationConfig.Entry e = cc.get(talker);
        if (e == null) e = new ConversationConfig.Entry();
        final ConversationConfig.Entry entry = e;

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, label(talker)));
        root.addView(M3Page.note(ctx, talker));

        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f));

        list.addView(newSection(ctx, "模板", "一键套用预设"));
        list.addView(newRow(ctx, AiIconDrawable.G_LAYERS, "套用模板", templateSub())
                .arrow(() -> {
                    dismissCurrent();
                    showTemplatePicker(activity, talker, isGroup);
                }));

        list.addView(newSection(ctx, "AI 服务", "仅已启用且已配置的会话触发 AI"));
        final Switch swEnabled = makeSwitch(ctx, entry.isActive());
        addSwitchRow(list, ctx, swEnabled, AiIconDrawable.G_SPARK, "启用 AI 服务", "关闭后本会话完全不触发 AI");

        list.addView(newSection(ctx, "独立开关", "保存后独立于全局"));
        Switch swOnly = null;
        if (isGroup) {
            boolean effOnly = entry.onlyWhenMentioned != null ? entry.onlyWhenMentioned : cfg.isOnlyWhenMentioned();
            swOnly = makeSwitch(ctx, effOnly);
            addSwitchRow(list, ctx, swOnly, AiIconDrawable.G_BELL, "仅被@时回复", "仅在被 @ 或命中唤醒词时回复");
        }
        boolean effTts = entry.ttsEnabled != null ? entry.ttsEnabled : cfg.isTtsEnabled();
        final Switch swTts = makeSwitch(ctx, effTts);
        addSwitchRow(list, ctx, swTts, AiIconDrawable.G_VOICE, "语音消息发送", "开=转语音发出; 关=发文本");
        final Switch swOnlyF = swOnly;

        addVoiceSection(list, ctx, activity, entry,
                () -> { cc.put(talker, entry); cc.save(); },
                () -> showConvEdit(activity, talker, isGroup));

        list.addView(newSection(ctx, "身份与名称", "各会话独立的 AI 称呼与身份描述, 留空继承全局"));
        final EditText etAiName = M3Page.input(ctx, "AI 名称 (留空 = 全局)");
        etAiName.setSingleLine(true);
        etAiName.setText(safe(entry.aiName));
        list.addView(etAiName);
        final EditText etAiIdentity = M3Page.input(ctx, "身份描述, 如: 你是我的私人助理 (可留空)");
        etAiIdentity.setSingleLine(false);
        etAiIdentity.setMinLines(2);
        etAiIdentity.setGravity(Gravity.TOP);
        etAiIdentity.setText(safe(entry.aiIdentity));
        list.addView(etAiIdentity);

        list.addView(newSection(ctx, "人设提示词", "留空表示继承全局"));
        final EditText etSys = M3Page.input(ctx, "人设提示词 (留空 = 全局)");
        etSys.setSingleLine(false);
        etSys.setMinLines(3);
        etSys.setGravity(Gravity.TOP);
        etSys.setText(safe(entry.systemPrompt));
        list.addView(etSys);

        // v1019: 会话级上下文记忆开关 + 条数
        final Switch swMemory = makeSwitch(ctx,
                entry.memoryEnabled != null ? entry.memoryEnabled.booleanValue() : true);
        addSwitchRow(list, ctx, swMemory, AiIconDrawable.G_DB, "上下文记忆", "关闭后本会话不携带历史记忆");
        final EditText etMemoryLimit = M3Page.input(ctx, "记忆条数 (留空 = 全局 " + safe(String.valueOf(cfg.getMaxHistoryMessages())) + ")");
        etMemoryLimit.setSingleLine(true);
        etMemoryLimit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMemoryLimit.setText(entry.memoryLimit == null ? "" : String.valueOf(entry.memoryLimit));
        list.addView(etMemoryLimit);

        final boolean groupFinal = isGroup;
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(独立配置): 保存 " + talker);
            try {
                ConversationConfig.Entry out = new ConversationConfig.Entry();
                // v996: enabled 是 AI 触发的会话级总闸, 始终显式落库。
                // 原「自动回复」开关已被 enabled 取代, 不再落库。
                out.enabled = Boolean.valueOf(swEnabled.isChecked());
                // v985: 与全局相同的开关不落库(写 null), 继续跟随全局; 只有显式不同的才固化,
                // 避免"打开配置时全局还是关 → 保存后永久固化 false"。
                if (groupFinal && swOnlyF != null) {
                    out.onlyWhenMentioned = explicitOrNull(swOnlyF.isChecked(), cfg.isOnlyWhenMentioned());
                }
                out.ttsEnabled = explicitOrNull(swTts.isChecked(), cfg.isTtsEnabled());
                out.systemPrompt = str(etSys).trim();
                out.aiName = str(etAiName).trim();
                out.aiIdentity = str(etAiIdentity).trim();
                // v1019: 会话级记忆开关/条数
                out.memoryEnabled = swMemory.isChecked() ? Boolean.TRUE : Boolean.FALSE;
                String ml = str(etMemoryLimit).trim();
                if (ml.isEmpty()) {
                    out.memoryLimit = null;
                } else {
                    try {
                        int v = Integer.parseInt(ml);
                        out.memoryLimit = v > 0 ? Integer.valueOf(v) : null;
                    } catch (NumberFormatException nfe) {
                        out.memoryLimit = null;
                    }
                }
                out.voices = entry.voices == null ? null : new ArrayList<>(entry.voices);
                out.randomVoice = entry.randomVoice;
                cc.put(talker, out);
                cc.save();
                LogWriter.log(TAG, "保存独立配置: enabled=" + out.enabled
                        + " onlyWhenMentioned=" + out.onlyWhenMentioned
                        + " tts=" + out.ttsEnabled + " voices=" + out.voices
                        + " randomVoice=" + out.randomVoice
                        + " aiName=" + out.aiName + " aiIdentity=" + out.aiIdentity
                        + " memoryEnabled=" + out.memoryEnabled + " memoryLimit=" + out.memoryLimit);
                reload();
                toastQuiet(ctx, "已保存独立配置");
            } catch (Throwable t) {
                LogWriter.log(TAG, "conv save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            dismissCurrent();
            showConversationList(activity);
        });

        ModernButton btnDel = new ModernButton(ctx, "删除配置", ModernButton.STYLE_DANGER);
        btnDel.onClick(() -> {
            LogWriter.log(TAG, "click(独立配置): 删除 " + talker);
            try {
                cc.remove(talker);
                cc.save();
                reload();
                toastQuiet(ctx, "已删除独立配置");
            } catch (Throwable t) {
                LogWriter.log(TAG, "conv del err: " + t);
            }
            dismissCurrent();
            showConversationList(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            dismissCurrent();
            showConversationList(activity);
        });

        root.addView(newBtnRow(ctx, btnSave, btnDel, btnClose));
        // v1019: 系统返回键 = 回会话配置列表(父级), 中间丢弃未保存草稿
        showPopup(activity, root, 0, "convedit", () -> showConversationList(activity));
    }

    private static void showTemplatePicker(final Activity activity, final String talker,
                                           final boolean isGroup) {
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        if (cc == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        List<String> names = cc.templateNames();
        if (names.isEmpty()) {
            toastQuiet(ctx, "暂无模板, 请先在「模板配置」中创建");
            dismissCurrent();
            showConvEdit(activity, talker, isGroup);
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "套用模板"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.50f));
        for (String name : names) {
            final String tpl = name;
            list.addView(newRow(ctx, AiIconDrawable.G_LAYERS, tpl, entrySummary(cc.getTemplate(tpl)))
                    .arrow(() -> {
                        LogWriter.log(TAG, "click(套用模板): " + tpl + " -> " + talker);
                        try {
                            cc.applyTemplate(talker, tpl);
                            // v996: 套用模板即视为已配置该会话, 显式启用 AI。
                            ConversationConfig.Entry applied = cc.get(talker);
                            if (applied != null) applied.enabled = Boolean.TRUE;
                            cc.save();
                            reload();
                            toastQuiet(ctx, "已套用模板: " + tpl);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "applyTemplate err: " + t);
                        }
                        dismissCurrent();
                        showConvEdit(activity, talker, isGroup);
                    }));
        }
        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            dismissCurrent();
            showConvEdit(activity, talker, isGroup);
        });
        root.addView(btnClose);
        // v1019: 系统返回键 = 回独立配置编辑页(父级)
        showPopup(activity, root, 0, "tplpick", () -> showConvEdit(activity, talker, isGroup));
    }

    // ==================== 三级: 模板配置 ====================

    private static void showTemplateList(final Activity activity) {
        LogWriter.log(TAG, "showTemplateList: enter");
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        if (cc == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "模板配置"));
        root.addView(M3Page.note(ctx,
                "模板保存一套「开关 + 人设 + 模型」预设, 可在任意群/联系人的独立配置中一键套用。"));

        ModernButton btnAdd = new ModernButton(ctx, "＋ 新建模板", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpAdd.setMargins(0, 0, 0, dp(ctx, 8));
        btnAdd.setLayoutParams(lpAdd);
        btnAdd.onClick(() -> {
            dismissCurrent();
            showTemplateEdit(activity, null);
        });
        root.addView(btnAdd);

        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.50f));
        List<String> names = cc.templateNames();
        if (names.isEmpty()) {
            list.addView(M3Page.empty(ctx, "🧩", "暂无模板, 点击上方「新建模板」创建。"));
        } else {
            final String[] pendingDelete = {null};
            for (String name : names) {
                final String tpl = name;
                SettingRow row = newRow(ctx, AiIconDrawable.G_LAYERS, tpl, entrySummary(cc.getTemplate(tpl)));
                row.arrow(null);
                row.setOnClickListener(v -> {
                    if (!tpl.equals(pendingDelete[0])) {
                        pendingDelete[0] = tpl;
                        toastQuiet(ctx, "再次点击可删除, 或长按编辑");
                        return;
                    }
                    try {
                        cc.removeTemplate(tpl);
                        cc.save();
                        reload();
                        toastQuiet(ctx, "已删除模板: " + tpl);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "removeTemplate err: " + t);
                    }
                    dismissCurrent();
                    showTemplateList(activity);
                });
                row.setOnLongClickListener(v -> {
                    LogWriter.log(TAG, "longclick(模板): 编辑 " + tpl);
                    dismissCurrent();
                    showTemplateEdit(activity, tpl);
                    return true;
                });
                list.addView(row);
            }
        }

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            dismissCurrent();
            show(activity);
        });
        root.addView(btnClose);

        showPopup(activity, root, 0, "tpllist");
    }

    private static void showTemplateEdit(final Activity activity, final String originalName) {
        LogWriter.log(TAG, "showTemplateEdit: enter name=" + originalName);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        final AppConfig cfg = AIBotCore.config();
        if (cc == null || cfg == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        ConversationConfig.Entry e = sTemplateDraft != null ? sTemplateDraft
                : (originalName != null ? cc.getTemplate(originalName) : new ConversationConfig.Entry());
        if (e == null) e = new ConversationConfig.Entry();
        sTemplateDraft = e;

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, originalName == null ? "新建模板" : "编辑模板"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f));

        final EditText etName = M3Page.input(ctx, "模板名称");
        etName.setText(safe(originalName));
        list.addView(etName);

        list.addView(newSection(ctx, "开关", "留空则套用后仍可单独调整"));
        boolean effOnly = e.onlyWhenMentioned != null ? e.onlyWhenMentioned : cfg.isOnlyWhenMentioned();
        final Switch swOnly = makeSwitch(ctx, effOnly);
        addSwitchRow(list, ctx, swOnly, AiIconDrawable.G_BELL, "仅被@时回复", "群聊中仅被 @ / 唤醒词触发");
        boolean effTts = e.ttsEnabled != null ? e.ttsEnabled : cfg.isTtsEnabled();
        final Switch swTts = makeSwitch(ctx, effTts);
        addSwitchRow(list, ctx, swTts, AiIconDrawable.G_VOICE, "语音消息发送", "开=转语音发出; 关=发文本");
        final ConversationConfig.Entry tplEntry = e;

        addVoiceSection(list, ctx, activity, tplEntry, null,
                () -> showTemplateEdit(activity, originalName));

        list.addView(newSection(ctx, "身份与名称", "套用后各会话独立生效, 留空继承全局"));
        final EditText etAiName = M3Page.input(ctx, "AI 名称 (可留空)");
        etAiName.setSingleLine(true);
        etAiName.setText(safe(e.aiName));
        list.addView(etAiName);
        final EditText etAiIdentity = M3Page.input(ctx, "身份描述, 如: 你是我的私人助理 (可留空)");
        etAiIdentity.setSingleLine(false);
        etAiIdentity.setMinLines(2);
        etAiIdentity.setGravity(Gravity.TOP);
        etAiIdentity.setText(safe(e.aiIdentity));
        list.addView(etAiIdentity);

        list.addView(newSection(ctx, "人设提示词", "留空表示套用后继承全局"));
        final EditText etSys = M3Page.input(ctx, "人设提示词 (可留空)");
        etSys.setSingleLine(false);
        etSys.setMinLines(3);
        etSys.setGravity(Gravity.TOP);
        etSys.setText(safe(e.systemPrompt));
        list.addView(etSys);

        // v1019: 模板级上下文记忆开关 + 条数
        final Switch swMemory = makeSwitch(ctx,
                e.memoryEnabled != null ? e.memoryEnabled.booleanValue() : true);
        addSwitchRow(list, ctx, swMemory, AiIconDrawable.G_DB, "上下文记忆", "关闭后本会话不携带历史记忆");
        final EditText etMemoryLimit = M3Page.input(ctx, "记忆条数 (留空 = 全局 " + safe(String.valueOf(cfg.getMaxHistoryMessages())) + ")");
        etMemoryLimit.setSingleLine(true);
        etMemoryLimit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMemoryLimit.setText(e.memoryLimit == null ? "" : String.valueOf(e.memoryLimit));
        list.addView(etMemoryLimit);

        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            String name = str(etName).trim();
            if (TextUtils.isEmpty(name)) {
                toastQuiet(ctx, "请输入模板名称");
                return;
            }
            LogWriter.log(TAG, "click(模板): 保存 " + name);
            try {
                if (originalName != null && !name.equals(originalName)) {
                    cc.removeTemplate(originalName);
                }
                ConversationConfig.Entry out = new ConversationConfig.Entry();
                out.onlyWhenMentioned = swOnly.isChecked();
                out.ttsEnabled = swTts.isChecked();
                out.systemPrompt = str(etSys).trim();
                out.aiName = str(etAiName).trim();
                out.aiIdentity = str(etAiIdentity).trim();
                // v1019: 模板级记忆开关/条数
                out.memoryEnabled = swMemory.isChecked() ? Boolean.TRUE : Boolean.FALSE;
                String ml = str(etMemoryLimit).trim();
                if (ml.isEmpty()) {
                    out.memoryLimit = null;
                } else {
                    try {
                        int v = Integer.parseInt(ml);
                        out.memoryLimit = v > 0 ? Integer.valueOf(v) : null;
                    } catch (NumberFormatException nfe) {
                        out.memoryLimit = null;
                    }
                }
                out.voices = tplEntry.voices == null ? null : new ArrayList<>(tplEntry.voices);
                out.randomVoice = tplEntry.randomVoice != null && tplEntry.randomVoice;
                cc.putTemplate(name, out);
                cc.save();
                reload();
                toastQuiet(ctx, "已保存模板");
            } catch (Throwable t) {
                LogWriter.log(TAG, "template save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            sTemplateDraft = null;
            dismissCurrent();
            showTemplateList(activity);
        });

        ModernButton btnDel = new ModernButton(ctx, "删除", ModernButton.STYLE_DANGER);
        btnDel.onClick(() -> {
            LogWriter.log(TAG, "click(模板): 删除 " + originalName);
            try {
                String name = originalName != null ? originalName : str(etName).trim();
                if (!TextUtils.isEmpty(name)) {
                    cc.removeTemplate(name);
                    cc.save();
                    reload();
                    toastQuiet(ctx, "已删除模板");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "template del err: " + t);
            }
            sTemplateDraft = null;
            dismissCurrent();
            showTemplateList(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            sTemplateDraft = null;
            dismissCurrent();
            showTemplateList(activity);
        });

        root.addView(newBtnRow(ctx, btnSave, btnDel, btnClose));
        // v1019: 系统返回键 = 回模板列表(父级), 丢弃未保存草稿
        showPopup(activity, root, 0, "tpledit", () -> showTemplateList(activity));
    }

    // ==================== 工具 ====================

    private interface ConfigMutator {
        void apply(AppConfig c);
    }

    private static void persist(Context ctx, AppConfig config, ConfigMutator mutator, String toast) {
        try {
            mutator.apply(config);
            boolean ok = config.save();
            LogWriter.log(TAG, "persist ok=" + ok);
            reload();
            toastQuiet(ctx, toast);
        } catch (Throwable t) {
            LogWriter.log(TAG, "persist err: " + t);
            toastQuiet(ctx, "保存失败");
        }
    }

    private static void reload() {
        try {
            AIBotCore.reload();
        } catch (Throwable t) {
            LogWriter.log(TAG, "reload err: " + t);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String str(EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }

    private static void updateTempLabel(TextView tv, int progress) {
        tv.setText("温度: " + String.format(Locale.US, "%.1f", progress / TEMP_SCALE));
    }

    /** 把接口返回的原始错误翻译成用户可操作的中文提示。 */
    private static String friendlyApiError(String err) {
        if (err == null) return "获取失败";
        String low = err.toLowerCase(Locale.US);
        if (err.contains("401") || low.contains("authentication fails") || low.contains("invalid api key")) {
            return "密钥无效(401): 请到服务商控制台重新生成 Key 并完整粘贴(勿带空格/换行/隐藏字符)";
        }
        if (err.contains("403")) {
            return "密钥无权限(403): 请确认该 Key 已开通对应模型权限";
        }
        if (err.contains("404")) {
            return "接口地址不对(404): 请检查是否填写了正确的域名(如 https://api.deepseek.com)";
        }
        if (low.contains("unknownhost") || low.contains("failed to connect") || low.contains("timeout")) {
            return "网络连接失败: 请检查网络或接口地址";
        }
        return "获取失败: " + err;
    }

    /** 解析 providerType 字符串为枚举(兼容 "openai" 别名) */
    private static ProviderType providerTypeOf(String current) {
        if (current == null) return ProviderType.OPENAI_CHAT;
        String c = current.toLowerCase(Locale.US).trim();
        for (ProviderType pt : ProviderType.values()) {
            if (pt.name().toLowerCase(Locale.US).equals(c)) return pt;
        }
        if ("openai".equals(c) || "chat".equals(c) || "gpt".equals(c)) return ProviderType.OPENAI_CHAT;
        if ("responses".equals(c)) return ProviderType.OPENAI_RESPONSES;
        if ("claude".equals(c)) return ProviderType.ANTHROPIC;
        return ProviderType.OPENAI_CHAT;
    }

    private static boolean providerMatches(ProviderType pt, String current) {
        return providerTypeOf(current) == pt;
    }

    private static String providerLabel(ProviderType pt) {
        if (pt == null) return "未知";
        switch (pt) {
            case OPENAI_RESPONSES:
                return "OpenAI Responses";
            case ANTHROPIC:
                return "Anthropic Claude";
            case OPENAI_CHAT:
            default:
                return "OpenAI Chat";
        }
    }

    private static void toastQuiet(Context ctx, String msg) {
        if (ctx == null) return;
        try {
            M3Page.toast(ctx, msg);
        } catch (Throwable ignored) {
        }
    }
}
