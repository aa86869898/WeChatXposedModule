import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信消息防撤回 — 独立 Xposed 模块
 * 双路径覆盖：XML 协议 + Protobuf 协议
 *
 * 依赖: compileOnly 'de.robv.android.xposed:api:82'   (或 api:82~92)
 * 微信: 8.0.76 实测通过
 *
 * 使用方法:
 *   1. 复制此文件到你项目的 com.your.module 包下
 *   2. 在 xposed_init 中声明: com.your.module.AntiRevoke
 *   3. 编译 → 安装 → 激活模块 → 重启微信
 */
public class AntiRevoke implements IXposedHookLoadPackage {

    private static final String TAG = "AntiRevoke";
    private static final String WX_PKG = "com.tencent.mm";

    // 微信 8.0.76 混淆类名（如果版本升级后类名变了，见文件末尾兼容方案）
    private static final String CLS_XML_RUNNABLE = "af5.a";
    private static final String CLS_PROTO_HANDLER = "e01.u";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WX_PKG.equals(lpparam.packageName)) return;

        final ClassLoader cl = lpparam.classLoader;
        log("微信防撤回模块已加载");

        hookXmlRevoke(cl);
        hookProtoRevoke(cl);

        log("防撤回 Hook 全部安装完成");
    }

    // ================================================================
    // 路径1: XML 协议撤回
    //
    // 链路: 收到 XML → f21.c.a() 解析 → af5.b.b() 识别 "revokemsg"
    //      → af5.b.c() 超时检查 → 提交 af5.a (Runnable)
    //      → af5.a.run():
    //          ① msg.setType(0x2712)          // 10002 = "已撤回"类型
    //          ② d1.J("对方撤回了一条消息",…)   // 设置撤回提示
    //          ③ f9.Ra(msgId, msg)            // 写数据库
    //
    // 阻断: beforeHookedMethod → setResult(null)
    // ================================================================
    private void hookXmlRevoke(ClassLoader cl) {
        try {
            Class<?> af5a = XposedHelpers.findClass(CLS_XML_RUNNABLE, cl);
            XposedBridge.hookAllMethods(af5a, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                    log("[XML] 已阻止撤回");
                }
            });
            log("[XML] af5.a.run() Hook 成功");
        } catch (XposedHelpers.ClassNotFoundError e) {
            log("[XML] 类 " + CLS_XML_RUNNABLE + " 未找到, 请更新混淆类名");
        }
    }

    // ================================================================
    // 路径2: Protobuf 协议撤回
    //
    // 链路: 收到 Protobuf → e01.u.q7() 解析
    //      → e01.u.f(String talker, long svrId, p0, String, String, String)
    //          ① 通过 svrId 在数据库找原消息
    //          ② 删除/替换原消息内容
    //
    // 阻断: beforeHookedMethod → setResult(null)
    // ================================================================
    private void hookProtoRevoke(ClassLoader cl) {
        try {
            Class<?> e01u = XposedHelpers.findClass(CLS_PROTO_HANDLER, cl);
            XposedBridge.hookAllMethods(e01u, "f", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                    log("[Proto] 已阻止撤回");
                }
            });
            log("[Proto] e01.u.f() Hook 成功");
        } catch (XposedHelpers.ClassNotFoundError e) {
            log("[Proto] 类 " + CLS_PROTO_HANDLER + " 未找到, 请更新混淆类名");
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }
}


/*
================================================================================
                跨版本兼容 — DexKit 动态定位（类名失效时使用）
================================================================================

微信每次更新混淆类名都会变（af5.a → 其他随机名）。
以下是用 DexKit 自动搜索的通用方案，不依赖硬编码类名。

1. 在 build.gradle 添加:
   implementation 'org.luckypray:DexKit:2.2.1'

2. 在 handleLoadPackage 开头初始化 DexKit:

   String apkPath = lpparam.appInfo.sourceDir;
   DexKitBridge dexKit = DexKitBridge.create(apkPath);

3. 定位 af5.a (XML revoke Runnable) — 搜索 "checkExpired:" 字符串:

   List<String> list = dexKit.findClassByString("checkExpired:");
   for (String name : list) {
       Class<?> c = cl.loadClass(name);
       if (Runnable.class.isAssignableFrom(c)) {
           XposedBridge.hookAllMethods(c, "run", ...);
           break;
       }
   }

4. 定位 e01.u (Proto revoke handler) — 搜索 "doRevokeMsg" 字符串:

   List<String> list2 = dexKit.findClassByString("doRevokeMsg");
   for (String name : list2) {
       Class<?> c = cl.loadClass(name);
       for (Method m : c.getDeclaredMethods()) {
           if (m.getName().equals("f") && m.getParameterCount() >= 6) {
               XposedBridge.hookAllMethods(c, "f", ...);
               break;
           }
       }
   }


================================================================================
                关键字符串索引 (用于 DexKit / 手动定位)
================================================================================

字符串                            位置               用途
────────────────────────────────────────────────────────────
"revokemsg"                      af5.b.b()         识别撤回消息类型
"checkExpired:"                  af5.a.run()       Runnable 日志
"MicroMsg.InvokeMessageNewXmlMsg" af5.a / af5.b    XML撤回 log tag
"doRevokeMsg"                    e01.u.f()         执行撤回
"qy_revoke_msg"                  e01.u.<init>()    撤回标记


================================================================================
                撤回链路完整图
================================================================================

XML 路径:
  收到撤回 XML → f21.c.a() → af5.b.b() 识别 "revokemsg"
  → af5.b.c() 5分钟超时检查 → af5.a.run()
    ① setType(10002)
    ② 替换内容为"对方撤回了一条消息"
    ③ 写入数据库
  → UI 检测 type=10002 → 渲染撤回提示

Protobuf 路径:
  收到撤回 Proto → e01.u.q7() → e01.u.f()
    ① svrId 查数据库找原消息
    ② 删除/替换消息内容

★ Hook 位置 = 两个 run() / f() 的 beforeHookedMethod → setResult(null)
*/
