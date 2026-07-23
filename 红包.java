import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信自动抢红包 — 独立 Xposed 模块 (不依赖任何第三方)
 *
 * 核心原理:
 *   红包收取界面 LuckyMoneyNewReceiveUI 中有个"开"按钮 (this.o)
 *   在 onCreate → initView 完成后自动点击它。
 *
 * 微信 8.0.76 实测通过
 * 依赖: compileOnly 'de.robv.android.xposed:api:82'
 *
 * 使用方法:
 *   1. 复制到项目 com.your.module 包
 *   2. xposed_init: com.your.module.AutoGrabRedEnvelope
 *   3. 编译安装 → 激活 → 重启微信
 */
public class AutoGrabRedEnvelope implements IXposedHookLoadPackage {

    private static final String TAG = "AutoGrab";
    private static final String WX_PKG = "com.tencent.mm";

    // ================================================================
    // Hook 入口
    // ================================================================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WX_PKG.equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;
        log("自动抢红包模块加载");

        hookReceiveUIs(cl);
        hookChatListClick(cl);
        log("Hook 安装完成");
    }

    // ================================================================
    // 核心: Hook 红包收取界面，自动点击"开"按钮
    //
    // LuckyMoneyNewReceiveUI.initView() → this.o = Button (开按钮)
    // 在 initView 完成后直接 performClick()
    // ================================================================

    private void hookReceiveUIs(ClassLoader cl) {
        // 主收取界面
        hookInitView(cl,
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI",
            "initView");

        // 不防Hook的收取界面 (备用)
        hookInitView(cl,
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI",
            "initView");

        // 企业红包收取界面
        tryHookAndClick(cl, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI");
        tryHookAndClick(cl, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2");
        // 香港红包
        tryHookAndClick(cl, "com.tencent.mm.plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI");
    }

    /**
     * Hook 指定类的 initView 方法，完成后自动点击开按钮
     */
    private void hookInitView(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    // 延时 50ms 确保按钮已渲染到 View 树
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        clickOpenButton(act);
                    }, 50);
                }
            });
            log("  [✓] " + className + "." + methodName + "()");
        } catch (Throwable e) {
            // 类不存在，静默跳过
        }
    }

    /**
     * 兜底: Hook onCreate → 延时点击
     */
    private void tryHookAndClick(ClassLoader cl, String className) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        clickOpenButton(act);
                    }, 100);
                }
            });
            log("  [✓] " + className + ".onCreate()");
        } catch (Throwable ignored) {}
    }

    // ================================================================
    // 自动点击: 在 Activity 的 View 树中找"开"按钮并点击
    // ================================================================

    private void clickOpenButton(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            Button btn = findButtonRecursive(root);
            if (btn != null && btn.isEnabled() && isVisible(btn)) {
                btn.performClick();
                log("★ 点击成功 [" + btn.getText() + "]");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 递归找 Button，匹配"开"或"拆"文字
     */
    private Button findButtonRecursive(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            CharSequence t = b.getText();
            if (t != null) {
                String s = t.toString();
                // 红包按钮文字: "开" "拆" "Open"
                if (s.contains("开") || s.contains("拆")
                    || s.contains("Open") || s.contains("OPEN")) {
                    return b;
                }
            }
            // 也尝试匹配 id 包含 "open" 的按钮
            String resName = getResourceName(v);
            if (resName != null && resName.toLowerCase().contains("open")) {
                return b;
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                Button r = findButtonRecursive(vg.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private boolean isVisible(View v) {
        return v.getVisibility() == View.VISIBLE
            && v.getWidth() > 0 && v.getHeight() > 0;
    }

    private String getResourceName(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                return v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ================================================================
    // 方案2: 聊天列表自动检测红包并点击
    //
    // 红包消息类型 MSG_TYPE_APPMSG = 49, content 含 "nativeurl"
    // 在消息渲染时对红包消息自动触发点击
    // ================================================================

    private void hookChatListClick(ClassLoader cl) {
        // 红包消息在聊天列表中通过 ChattingItemAppMsg 渲染
        // 尝试 Hook 消息的点击分发
        try {
            // 方法A: Hook 聊天界面的消息点击事件
            Class<?> chattingUI = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        scanAndClickRedEnvelope(act);
                    }, 300);
                }
            });
            log("  [✓] ChattingUI.onResume()");
        } catch (Throwable e) {
            log("  [!] ChattingUI 未找到");
        }
    }

    /**
     * 扫描当前聊天界面的 View 树，找到红包消息并点击
     *
     * 红包消息特征:
     *   - 消息容器 view 中包含 "微信红包" 文字
     *   - 或者在 TextView 中显示红包提示文字
     *   - 典型红包气泡是一个带红色背景的 FrameLayout/LinearLayout
     */
    private void scanAndClickRedEnvelope(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            View envelope = findRedEnvelopeView(root);
            if (envelope != null) {
                envelope.performClick();
                log("★ 自动点击红包消息");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 递归找红包消息 View
     * 特征: TextView 文字含 "微信红包" 或红色背景的 FrameLayout
     */
    private View findRedEnvelopeView(View v) {
        // 特征1: TextView 显示"微信红包"
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && (
                t.toString().contains("微信红包") ||
                t.toString().contains("紅包"))) {
                // 找到这个 TextView 后，找它的可点击父容器
                View clickable = findClickableParent(v);
                if (clickable != null) return clickable;
            }
        }

        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View r = findRedEnvelopeView(vg.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private View findClickableParent(View v) {
        View parent = (View) v.getParent();
        while (parent != null) {
            if (parent.isClickable()) return parent;
            parent = (View) parent.getParent();
        }
        return v;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }
}


/*
================================================================================
                         完整架构说明
================================================================================

【红包消息生命周期】

  1. 收到红包消息 (type=436207665)
     → 聊天列表显示红包气泡 (红色背景 + "微信红包"文字)

  2. 用户点击红包气泡
     → 启动 LuckyMoneyNewReceiveUI

  3. LuckyMoneyNewReceiveUI.onCreate()
     → initView() 初始化界面 (红包封面 + "开"按钮)

  4. 用户点击"开"按钮 (this.o)
     → W6(f6)
     → 发送网络请求 NetSceneOpenLuckyMoney
     → startActivity(LuckyMoneyBeforeDetailUI)

  5. LuckyMoneyBeforeDetailUI.onCreate()
     → 发送拆红包请求
     → onSceneEnd() 处理结果
     → 显示金额

【Hook 点设计】

  ★ hookReceiveUIs:
     Hook LuckyMoneyNewReceiveUI.initView() → after
     → 自动 clickOpenButton()
     覆盖了主收取界面 + 企业红包 + 香港红包 + 备用UI

  ★ hookChatListClick:
     Hook ChattingUI.onResume() → after
     → scanAndClickRedEnvelope()
     自动点击聊天界面中的红包气泡


================================================================================
                         类名失效时的 DexKit 定位
================================================================================

build.gradle 加入: implementation 'org.luckypray:DexKit:2.2.1'

定位 LuckyMoneyNewReceiveUI:
  搜索字符串: "MicroMsg.LuckyMoneyNewReceiveUI"
           或 "[openLuckyDetail] click btn and receive"

定位 ChattingUI:
  搜索字符串: "MicroMsg.ChattingUI"

定位红包消息类型:
  搜索 f9 类中定义了 msg type 常量的静态字段
  或 Hook 消息的 getType() 方法，打印日志观察 436207665 是否变化


================================================================================
                         更多加强方案
================================================================================

1. 延迟抢 (模拟真人):
   在 clickOpenButton 前加随机延时 Random().nextInt(2000) + 500

2. 过滤自己的红包:
   在initView() 后检查红包发送者，
   如果是自己发的 → 不抢

3. 仅抢群聊红包:
   检查 Intent 中的 key_username 是否以 @chatroom 结尾

4. 黑名单/白名单:
   维护一个群聊/发送者名单，按规则决定是否抢

5. 通知栏提示:
   抢到后通过 Notification 显示金额
*/
