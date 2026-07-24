/**
 * ================================================================
 *   红包/转账播报 — onSceneEnd Hook 修复补丁
 *   WeChat 8.0.76 (版号 3140)
 * ================================================================
 *
 *  【问题】
 *   红包和转账的 onSceneEnd TTS 播报完全不触发。
 *   日志显示:
 *     RedPacket: tts FAILED: LuckyMoneyNewReceiveUI#onSceneEnd(int,int,String,m1,boolean)#exact
 *     AutoCollect: transfer hook FAILED: RemittanceDetailUI#onSceneEnd(int,int,String,m1,boolean)#exact
 *
 *  【根因】
 *   代码中同时注册了 5参数和4参数两个 findAndHookMethod:
 *     findAndHookMethod(uiCls, "onSceneEnd", int, int, String, m1, boolean, ...)  // ← 不存在
 *     findAndHookMethod(uiCls, "onSceneEnd", int, int, String, m1, ...)            // ← 实际方法
 *
 *   5参数版本在这些UI子类上不存在，findAndHookMethod 抛出 NoSuchMethodError，
 *   被外层 catch 捕获后跳过整个 try 块，4参数版本也来不及注册。
 *   所有 6 个红包 UI 类和 1 个转账 UI 类全部 Hook 失败。
 *
 *  【修复】
 *   删除5参数 onSceneEnd Hook，仅保留4参数 onSceneEnd(int, int, String, m1)。
 *   实际方法签名存在于所有目标 UI 类中(21:49日志 probe 已验证)。
 *
 * ================================================================
 *   修改内容
 * ================================================================
 *
 *   文件1: RedPacketHook.java
 *   hookTtsCheck() 方法中，删除:
 *     XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
 *         int.class, int.class, String.class, m1Cls, boolean.class,
 *         new MoneyResultHook("红包"));
 *   保留:
 *     XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
 *         int.class, int.class, String.class, m1Cls,
 *         new MoneyResultHook("红包"));
 *
 *   文件2: AutoCollectHook.java
 *   hookTransferResult() 方法中，删除:
 *     XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
 *         int.class, int.class, String.class, m1Cls, boolean.class,
 *         new MoneyResultHook("转账"));
 *   保留:
 *     XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
 *         int.class, int.class, String.class, m1Cls,
 *         new MoneyResultHook("转账"));
 *
 * ================================================================
 *   预期日志
 * ================================================================
 *
 *   [MoneyHook] 红包 OK: LuckyMoneyNewReceiveUI
 *   [MoneyHook] 红包 OK: LuckyMoneyDetailUI
 *   ...
 *   [MoneyHook] 转账 OK
 *
 *   收到红包/转账后:
 *   [MoneyHook] 红包 onSceneEnd FIRE respClass=g1
 *   [MoneyHook] tts: type=红包 sender=xxx amount=0.02
 *   或
 *   [MoneyHook] 转账 onSceneEnd FIRE respClass=g1
 *   [MoneyHook] tts: type=转账 sender=xxx amount=0.02
 *
 * ================================================================
 */

/* ─── 补丁 diff (也可直接 git cherry-pick 5e911ef) ─── */

// ===== RedPacketHook.java hookTtsCheck() =====
//
// --- a/RedPacketHook.java
// +++ b/RedPacketHook.java
// @@ -92,9 +92,6 @@
//          for (String clsName : classes) {
//              try {
//                  Class<?> uiCls = cl.loadClass(clsName);
// -                XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
// -                    int.class, int.class, String.class, m1Cls, boolean.class,
// -                    new MoneyResultHook("红包"));
//                  XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
//                      int.class, int.class, String.class, m1Cls,
//                      new MoneyResultHook("红包"));

// ===== AutoCollectHook.java hookTransferResult() =====
//
// --- a/AutoCollectHook.java
// +++ b/AutoCollectHook.java
// @@ -84,9 +84,6 @@
//          try {
//              Class<?> uiCls = cl.loadClass(PKG_WECHAT + ".plugin.remittance.ui.RemittanceDetailUI");
//  
// -            XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
// -                int.class, int.class, String.class, m1Cls, boolean.class,
// -                new MoneyResultHook("转账"));
//              XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
//                  int.class, int.class, String.class, m1Cls,
//                  new MoneyResultHook("转账"));
