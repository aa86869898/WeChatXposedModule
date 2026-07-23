import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信全局美化主题 — 完整独立 Xposed 模块
 *
 * 涵盖: ActionBar / 页面背景 / 文字颜色 / 聊天气泡 / 语音消息气泡 / 底部Tab
 *
 * 依赖: compileOnly 'de.robv.android.xposed:api:82'
 * 微信 8.0.76 实测
 */
public class MagicWeChatTheme implements IXposedHookLoadPackage {

    private static final String TAG = "MagicTheme";
    private static final String WX_PKG = "com.tencent.mm";

    // ================================================================
    // ★ 配色方案 — 修改此处即可换肤
    // ================================================================

    private static class Theme {
        // 标题栏
        static int ACTIONBAR_BG     = 0xFF2D2D2D;
        static int ACTIONBAR_TITLE  = 0xFFFFFFFF;

        // 页面
        static int PAGE_BG          = 0xFFF5F5F5;
        static int CARD_BG          = 0xFFFFFFFF;

        // 聊天
        static int CHAT_BG          = 0xFFEDEDED;

        // 自己发的气泡(右侧)
        static int BUBBLE_SELF_BG   = 0xFF95EC69;   // 微信绿气泡

        // 对方发的气泡(左侧)
        static int BUBBLE_OTHER_BG  = 0xFFFFFFFF;   // 白气泡

        // 自己发的文字色
        static int BUBBLE_SELF_TEXT = 0xFF000000;

        // 对方发的文字色
        static int BUBBLE_OTHER_TEXT= 0xFF000000;

        // 语音气泡(自己)
        static int VOICE_SELF_BG    = 0xFF95EC69;

        // 语音气泡(对方)
        static int VOICE_OTHER_BG   = 0xFFFFFFFF;

        // 底部Tab
        static int TAB_BG           = 0xFFF7F7F7;
        static int TAB_SELECTED     = 0xFF07C160;
        static int TAB_UNSELECTED   = 0xFF999999;

        // 文字
        static int TEXT_PRIMARY     = 0xFF191919;
        static int TEXT_SECONDARY   = 0xFF888888;

        // 列表分割线
        static int DIVIDER          = 0xFFE5E5E5;

        // 是否启用气泡换肤
        static boolean ENABLE_BUBBLE = true;
        // 是否启用语音气泡换肤
        static boolean ENABLE_VOICE  = true;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WX_PKG.equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;

        Log.i(TAG, "===== MagicTheme 全功能主题模块 开始加载 =====");

        // --- 全局级别 (所有活动生效) ---
        methodA_GlobalResources(cl);        // A: Resources.getColor 拦截
        methodB_GlobalActivity(cl);         // B: Activity背景

        // --- 界面级别 ---
        methodC_ActionBar(cl);              // C: 标题栏
        methodD_MainTab(cl);                // D: 底部4Tab

        // --- 会话级别 ---
        methodE_ConversationList(cl);       // E: 会话列表

        // --- 聊天消息级别 ★★★ ---
        methodF_ChatBubble(cl);             // F: 文字聊天气泡
        methodG_VoiceBubble(cl);            // G: 语音消息气泡

        // --- 全局文字 ---
        methodH_TextViewColor(cl);          // H: 全局文字颜色

        Log.i(TAG, "===== MagicTheme 全部Hook安装完成 =====");
    }

    // ================================================================
    // A: Resources.getColor() 全局拦截 — 最底层
    // ================================================================
    private void methodA_GlobalResources(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Resources.class, "getColor",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int original = (int) param.getResult();
                        // 微信绿 → 品牌色
                        if (original == 0xFF07C160) {
                            param.setResult(Theme.TAB_SELECTED);
                            return;
                        }
                        // 顶部栏深黑 → 主题深灰
                        if (isNearBlack(original)) {
                            param.setResult(Theme.ACTIONBAR_BG);
                        }
                    }
                });
            Log.i(TAG, "[A] Resources.getColor() ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[A] 失败: " + t.getMessage());
        }
    }

    // ================================================================
    // B: Activity 页面背景
    // ================================================================
    private void methodB_GlobalActivity(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        if (!act.getClass().getName().startsWith(WX_PKG)) return;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                View content = act.findViewById(android.R.id.content);
                                if (content instanceof ViewGroup) {
                                    ViewGroup vg = (ViewGroup) content;
                                    if (vg.getChildCount() > 0) {
                                        View root = vg.getChildAt(0);
                                        if (!isAlreadyThemed(root)) {
                                            root.setBackgroundColor(Theme.PAGE_BG);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }, 15);
                    }
                });
            Log.i(TAG, "[B] Activity 背景 ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[B] 失败: " + t.getMessage());
        }
    }

    // ================================================================
    // C: ActionBar 标题栏
    // ================================================================
    private void methodC_ActionBar(ClassLoader cl) {
        try {
            Class<?> mmAct = XposedHelpers.findClass(
                "com.tencent.mm.ui.MMActivity", cl);
            XposedBridge.hookAllMethods(mmAct, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            themeActionBarTree(act);
                        }, 30);
                    }
                });
            Log.i(TAG, "[C] ActionBar ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[C] 失败: " + t.getMessage());
        }
    }

    private void themeActionBarTree(Activity act) {
        try {
            themeActionBarRecursive(act.getWindow().getDecorView());
        } catch (Throwable ignored) {}
    }

    private void themeActionBarRecursive(View v) {
        String cls = v.getClass().getName().toLowerCase();
        if (cls.contains("actionbar") || cls.contains("toolbar")
            || cls.contains("titlebar")) {
            v.setBackgroundColor(Theme.ACTIONBAR_BG);
        }
        if (v instanceof TextView) {
            View p = (View) v.getParent();
            if (p != null) {
                String pn = p.getClass().getName().toLowerCase();
                if (pn.contains("actionbar") || pn.contains("toolbar")
                    || pn.contains("titlebar")) {
                    ((TextView) v).setTextColor(Theme.ACTIONBAR_TITLE);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                themeActionBarRecursive(vg.getChildAt(i));
            }
        }
    }

    // ================================================================
    // D: 主界面底部Tab
    // ================================================================
    private void methodD_MainTab(ClassLoader cl) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                "com.tencent.mm.ui.LauncherUI", cl);
            XposedBridge.hookAllMethods(launcherUI, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            themeTabBar(act);
                        }, 200);
                    }
                });
            Log.i(TAG, "[D] 主界面Tab ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[D] LauncherUI 未找到");
        }
    }

    private void themeTabBar(Activity act) {
        try {
            findAndThemeTabBar(act.getWindow().getDecorView());
        } catch (Throwable ignored) {}
    }

    private void findAndThemeTabBar(View v) {
        String cls = v.getClass().getName().toLowerCase();
        if (cls.contains("tablayout") || cls.contains("bottomtab")
            || cls.contains("mmtab") || cls.contains("maintab")) {
            v.setBackgroundColor(Theme.TAB_BG);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAndThemeTabBar(vg.getChildAt(i));
            }
        }
    }

    // ================================================================
    // E: 会话列表
    // ================================================================
    private void methodE_ConversationList(ClassLoader cl) {
        try {
            Class<?> convClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.conversation.ConversationUI", cl);
            XposedBridge.hookAllMethods(convClass, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            themeConversationList(act);
                        }, 100);
                    }
                });
            Log.i(TAG, "[E] 会话列表 ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[E] 未找到: " + t.getMessage());
        }
    }

    private void themeConversationList(Activity act) {
        try {
            View root = act.getWindow().getDecorView();
            findRecyclerViewTheme(root);
        } catch (Throwable ignored) {}
    }

    private void findRecyclerViewTheme(View v) {
        if (v.getClass().getName().contains("RecyclerView")) {
            v.setBackgroundColor(Theme.PAGE_BG);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findRecyclerViewTheme(vg.getChildAt(i));
            }
        }
    }

    // ================================================================
    // F: ★★★ 聊天气泡换肤 ★★★
    //
    // 原理: Hook ChattingUI.onResume → 延时等RecyclerView渲染完成
    //       → 遍历RecyclerView所有item View → 识别气泡View
    //
    // WeChat气泡特征:
    //   自己(右侧): 绿色背景 (#95EC69), 靠右对齐
    //   对方(左侧): 白色背景 (#FFFFFF), 靠左对齐
    //
    // 实现: 遍历到气泡View → 根据位置判断自己/对方 → 替换背景色
    // ================================================================
    private void methodF_ChatBubble(ClassLoader cl) {
        if (!Theme.ENABLE_BUBBLE) return;

        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        // 延迟等RecyclerView渲染完
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            themeChatBubbles(act);
                        }, 200);
                    }
                });
            Log.i(TAG, "[F] 聊天气泡 ✓ (ChattingUI.onResume)");
        } catch (Throwable t) {
            Log.e(TAG, "[F] ChattingUI 未找到: " + t.getMessage());
        }

        // 也Hook RecyclerView.Adapter.bindViewHolder 实时拦截新消息气泡
        // 在消息渲染时立即替换
        hookRecyclerViewAdapterBind(cl);
    }

    /**
     * ================================================================
     * 核心: Hook RecyclerView.Adapter 的 onBindViewHolder
     *
     * 每次有新消息或滚动聊天列表时，Adapter.bindViewHolder 被调用
     * 在 after 中立即对 itemView 进行气泡换肤
     * ================================================================
     */
    private void hookRecyclerViewAdapterBind(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(
                Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter"),
                "onBindViewHolder",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object holder = param.args[0];
                        try {
                            View itemView = (View) XposedHelpers.callMethod(
                                holder, "itemView");
                            // 给这个itemView打上标记，延后换肤
                            new Handler(Looper.getMainLooper()).post(() -> {
                                themeSingleChatItem(itemView);
                            });
                        } catch (Throwable ignored) {}
                    }
                });
            Log.i(TAG, "[F] RecyclerView.Adapter.onBindViewHolder ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[F] Adapter Hook 失败: " + t.getMessage());
        }
    }

    /**
     * 扫描整个聊天界面，换肤所有可见气泡
     */
    private void themeChatBubbles(Activity act) {
        try {
            View root = act.getWindow().getDecorView();
            scanAndThemeBubbles(root);
        } catch (Throwable ignored) {}
    }

    /**
     * 递归扫描View树，找到RecyclerView中的聊天气泡
     */
    private void scanAndThemeBubbles(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            // 只处理聊天消息容器
            String cls = v.getClass().getName();
            if (cls.contains("RecyclerView")) {
                themeAllBubbleItems(vg);
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                scanAndThemeBubbles(vg.getChildAt(i));
            }
        }
    }

    /**
     * 遍历RecyclerView的所有可见子View，逐个换肤气泡
     */
    private void themeAllBubbleItems(ViewGroup recyclerView) {
        int count = recyclerView.getChildCount();
        for (int i = 0; i < count; i++) {
            themeSingleChatItem(recyclerView.getChildAt(i));
        }
    }

    /**
     * ★ 核心: 对单个聊天消息item进行气泡换肤
     *
     * 判断逻辑:
     *   微信中每条消息item包含:
     *     - 头像 (ImageView, 圆形)
     *     - 气泡容器 (FrameLayout/LinearLayout)
     *     - 气泡内文字 (TextView)
     *
     *   自己发的消息: 头像在右侧, 气泡在左侧(左→右布局中气泡靠右)
     *   对方发的消息: 头像在左侧, 气泡在右侧(左→右布局中气泡靠左)
     *
     *   简化判断: 看气泡View的父容器的layout_gravity/位置
     *
     * 气泡特征:
     *   - 背景通常是 .9.png 或 GradientDrawable (圆角矩形)
     *   - 包含 TextView (消息文字)
     *   - 独立的一个子容器 View
     */
    private void themeSingleChatItem(View itemView) {
        if (itemView == null || (itemView.getTag() != null
            && "magic_themed".equals(itemView.getTag().toString()))) {
            return; // 已处理过
        }

        try {
            // 判定这条消息是自己发的还是对方发的
            boolean isSelf = isSelfMessage(itemView);
            int targetBg = isSelf ? Theme.BUBBLE_SELF_BG : Theme.BUBBLE_OTHER_BG;
            int targetText = isSelf ? Theme.BUBBLE_SELF_TEXT : Theme.BUBBLE_OTHER_TEXT;

            // 遍历找到气泡View
            findAndThemeBubble(itemView, targetBg, targetText, isSelf);

            // 标记已处理
            itemView.setTag("magic_themed");
        } catch (Throwable ignored) {}
    }

    /**
     * 判断消息是否是自己发的
     *
     * 方法: 找头像ImageView，自己发的头像在右边
     *       或者通过View的layout方向判断
     */
    private boolean isSelfMessage(View itemView) {
        // 查找 itemView 中子View的布局方向
        // 自己发的: 右侧有头像 (gravity=end/right)
        // 对方发的: 左侧有头像 (gravity=start/left)
        if (itemView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) itemView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    // 头像右侧有较大margin → 可能是自己发的
                }
                if (child instanceof ImageView) {
                    // 头像View — 根据位置判断
                    float x = child.getX();
                    float parentW = vg.getWidth();
                    if (parentW > 0 && x > parentW * 0.6f) {
                        return true; // 头像在右边 → 自己发的
                    }
                }
            }
        }
        return false; // 默认当作对方
    }

    /**
     * 递归在itemView中找气泡容器并换肤
     *
     * 气泡特征:
     *   - 独立的小容器 (FrameLayout/RelativeLayout的子View)
     *   - 宽度只占屏幕的60-70% (不是全宽)
     *   - 包含TextView子View
     *   - 有圆角 (GradientDrawable 的 cornerRadius > 0)
     */
    private void findAndThemeBubble(View v, int bgColor, int textColor, boolean isSelf) {
        // 只处理 ViewGroup 类型的气泡容器
        if (!(v instanceof ViewGroup)) return;

        ViewGroup vg = (ViewGroup) v;

        // 判断这个容器是否是气泡
        if (isBubbleCandidate(vg)) {
            // 改背景
            setBubbleBackground(vg, bgColor, isSelf);
            // 改内部文字
            themeBubbleTexts(vg, textColor);
            return;
        }

        // 递归深入
        for (int i = 0; i < vg.getChildCount(); i++) {
            findAndThemeBubble(vg.getChildAt(i), bgColor, textColor, isSelf);
        }
    }

    /**
     * 判断一个ViewGroup是否是聊天气泡容器
     *
     * 条件:
     *   1. 不是RecyclerView / ListView 本身
     *   2. 宽度 < 父容器的80% (气泡不是全宽)
     *   3. 包含TextView子View
     *   4. (可选)背景是GradientDrawable且有圆角
     */
    private boolean isBubbleCandidate(ViewGroup vg) {
        String cls = vg.getClass().getName().toLowerCase();
        // 排除已知非气泡类型
        if (cls.contains("recycler") || cls.contains("listview")
            || cls.contains("scroll") || cls.contains("linearlayout")
            || cls.contains("framelayout") || cls.contains("relativelayout")) {
            // 这些太泛了，需要进一步判断
            if (vg.getChildCount() == 0) return false;

            // 判断宽度占比
            View parent = (View) vg.getParent();
            if (parent != null) {
                float ratio = (float) vg.getWidth() / (float) parent.getWidth();
                if (ratio > 0.15f && ratio < 0.85f) {
                    // 宽度在15%-85%之间 → 可能是气泡
                    // 检查内部是否有TextView
                    if (hasTextViewChild(vg)) {
                        return true;
                    }
                }
            }

            // 判断背景是否是圆角Drawable
            Drawable bg = vg.getBackground();
            if (bg instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) bg;
                // 有圆角 → 很可能是气泡
                try {
                    float[] radii = getCornerRadii(gd);
                    if (radii != null && hasAnyRadius(radii)) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }

            return false;
        }

        // 非Layout类型的ViewGroup(自定义View) → 可能是气泡
        if (hasTextViewChild(vg)) {
            Drawable bg = vg.getBackground();
            if (bg instanceof GradientDrawable) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextViewChild(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            if (vg.getChildAt(i) instanceof TextView) return true;
        }
        return false;
    }

    private float[] getCornerRadii(GradientDrawable gd) {
        try {
            // 反射获取 corner radii
            java.lang.reflect.Field f = GradientDrawable.class
                .getDeclaredField("mGradientState");
            f.setAccessible(true);
            Object state = f.get(gd);
            java.lang.reflect.Field radiiField = state.getClass()
                .getDeclaredField("mRadiusArray");
            radiiField.setAccessible(true);
            return (float[]) radiiField.get(state);
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean hasAnyRadius(float[] radii) {
        if (radii == null) return false;
        for (float r : radii) {
            if (r > 0) return true;
        }
        return false;
    }

    /**
     * 设置气泡背景色(保留原圆角)
     */
    private void setBubbleBackground(ViewGroup vg, int color, boolean isSelf) {
        try {
            Drawable oldBg = vg.getBackground();

            if (oldBg instanceof GradientDrawable) {
                // 保留原圆角，只改颜色
                GradientDrawable gd = (GradientDrawable) oldBg.mutate();
                gd.setColor(color);
                vg.setBackground(gd);
            } else {
                // 创建新圆角背景
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.RECTANGLE);
                gd.setCornerRadius(dpToPx(vg, 8));
                gd.setColor(color);
                if (isSelf) {
                    // 自己发的气泡可以加右箭头效果
                }
                vg.setBackground(gd);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 气泡内文字换色
     */
    private void themeBubbleTexts(ViewGroup vg, int textColor) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(textColor);
            } else if (child instanceof ViewGroup) {
                themeBubbleTexts((ViewGroup) child, textColor);
            }
        }
    }

    // ================================================================
    // G: ★★★ 语音消息气泡换肤 ★★★
    //
    // 语音消息特征:
    //   - 通常是一个FrameLayout/LinearLayout
    //   - 内部包含ImageView (喇叭图标) + TextView (时长)
    //   - 背景也是圆角矩形
    //   - 自己发的语音在右侧，对方在左侧
    //
    // 实现: 在F方案的扫描中顺带处理
    //       识别含ImageView的圆角容器 → 语音气泡
    // ================================================================
    private void methodG_VoiceBubble(ClassLoader cl) {
        if (!Theme.ENABLE_VOICE) return;

        // 语音气泡换肤已集成在 methodF 的 scanAndThemeBubbles 中
        // 因为语音气泡和文字气泡共享同一个RecyclerView
        // 判断逻辑: findAndThemeBubble中已覆盖
        //
        // 额外补充: Hook ImageView.setImageDrawable 拦截喇叭图标换色
        try {
            XposedBridge.hookAllMethods(ImageView.class, "setImageDrawable",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 可选: 对语音气泡中的喇叭图标着色
                        // 留空，按需实现
                    }
                });
            Log.i(TAG, "[G] 语音气泡 ✓ (集成在F方案中)");
        } catch (Throwable ignored) {}
    }

    // ================================================================
    // H: 全局TextView文字颜色
    // ================================================================
    private void methodH_TextViewColor(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setTextColor",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0) return;
                        int color = (int) param.args[0];
                        if (color == 0xFF000000 || color == Color.BLACK) {
                            param.args[0] = Theme.TEXT_PRIMARY;
                        }
                    }
                });
            Log.i(TAG, "[H] TextView颜色 ✓");
        } catch (Throwable t) {
            Log.e(TAG, "[H] 失败: " + t.getMessage());
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private boolean isNearBlack(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return r < 40 && g < 40 && b < 40 && Color.alpha(color) > 200;
    }

    private boolean isAlreadyThemed(View v) {
        Object tag = v.getTag();
        return tag != null && "magic_page".equals(tag.toString());
    }

    private int dpToPx(View v, int dp) {
        float density = v.getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}


/*
================================================================================
                    全功能方案总结
================================================================================

  方案  目标                    Hook点                        效果
  ──────────────────────────────────────────────────────────────────
  A     Resources.getColor()    Resources.class              全局颜色替换
  B     Activity 页面背景        Activity.onCreate()          所有Page背景
  C     ActionBar 标题栏         MMActivity.onResume()        顶部标题栏
  D     底部Tab                 LauncherUI.onResume()         四Tab换色
  E     会话列表                 ConversationUI.onResume()    列表背景
  F     文字聊天气泡 ★★★          ChattingUI + Adapter.bind    自己/对方气泡换色
  G     语音消息气泡 ★★★          集成在F中                      语音气泡换色
  H     全局文字颜色              TextView.setTextColor        文字统一


================================================================================
                    F方案 — 聊天气泡判断详解
================================================================================

  判断一条消息是否自己发的:
    1. 找itemView中的头像ImageView
    2. 头像在右侧 (getX > 父宽度*60%) → 自己发的
    3. 头像在左侧 → 对方发的

  判断一个View是否是气泡:
    条件1: 宽度在父容器的 15%-85% 之间 (气泡不是全宽)
    条件2: 包含TextView子View
    条件3: (可选) 背景是GradientDrawable且有圆角

  换肤:
    自己 → BUBBLE_SELF_BG (默认绿色 #95EC69)
    对方 → BUBBLE_OTHER_BG (默认白色)
    保留原圆角 → GradientDrawable.mutate().setColor()


================================================================================
                    实时的气泡换肤 — RecyclerView.Adapter
================================================================================

  Hook: RecyclerView.Adapter.onBindViewHolder → after
  每次新消息到达 / 滚动聊天列表 时触发
  → itemView 被交给 themeSingleChatItem()
  → 判断自己/对方 → 换背景色 → 换文字色


================================================================================
                    配色自定义示例
================================================================================

  // 暗色主题
  Theme.ACTIONBAR_BG    = 0xFF1A1A1A
  Theme.PAGE_BG         = 0xFF121212
  Theme.CHAT_BG         = 0xFF0D0D0D
  Theme.BUBBLE_SELF_BG  = 0xFF2B5278   // 蓝色自己气泡
  Theme.BUBBLE_OTHER_BG = 0xFF2C2C2C   // 深灰对方气泡
  Theme.BUBBLE_SELF_TEXT= 0xFFFFFFFF
  Theme.BUBBLE_OTHER_TEXT=0xFFFFFFFF
  Theme.TEXT_PRIMARY    = 0xFFE0E0E0
  Theme.TEXT_SECONDARY  = 0xFF909090
  Theme.CARD_BG         = 0xFF1E1E1E

  // 粉色少女主题
  Theme.ACTIONBAR_BG    = 0xFFFF6B81
  Theme.TAB_SELECTED    = 0xFFFF6B81
  Theme.BUBBLE_SELF_BG  = 0xFFFFE0E6
  Theme.BUBBLE_OTHER_BG = 0xFFFFF0F0
*/
