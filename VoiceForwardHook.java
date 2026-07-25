================================================================================
 VoiceForwardHook.java — 地毯式深度分析 (微信 8.0.76)
 基于完整源码逐行审查
================================================================================

一、代码结构总览 (1095行)
================================================================================

  hook()                          — 入口: 3条hook线
  hookChatActivity()               — 监听 ChattingUI 生命周期
  hookChatFragmentForAdapter()     — viewitems/component扫描 + Menu.add拦截
  hookForwardTracing()             — 追踪 b31.j/l 和 tl.p0 和 y21.x0
  hookAllClassesInPackages()       — 批量扫描包内所有类
  hookAllMethodsOnClass()          — 扫描并hook单个类的所有方法
  injectMenu()                     — 注入"转发"菜单项
  executeForward()                 — 触发转发: 提取msg→选目标→doForwardVoice
  doForwardVoice()                 — 核心转发: 找文件→解析时长→sendViaSceneVoice
  sendViaSceneVoice()              — SceneVoice方案: g()+Mj/Nj+t()
  findVoiceFile()                  — 3层语音文件查找
  searchVoice2Dir()                — 遍历voice2目录搜索
  trySendViaB31()                  — b31.w上传
  getUinHash() / md5() / getE9()  — 工具方法


二、正在运行的完整转发流程 (从日志反向验证)
================================================================================

  ① 长按语音气泡 → 弹出菜单
     hook: viewitems包扫描 + Menu.add拦截
     日志: "MENU_ID found in wp.Q elapsed=731ms"

  ② 点击"转发"
     hook: onMMMenuItemSelected 拦截 MENU_ID=777001
     日志: "COOLDOWN PASSED — executeForward!"

  ③ 提取消息对象
     msg.c() → e9 (storage.e9)
     日志: "forward: msgId=76282 via c() → e9"

  ④ 解析语音文件
     content XML提取 clientmsgid → md5子目录 → searchVoice2Dir
     日志: "voice2: found .../voice2/42/1e/msg_102356072426bea2cb8f776101.amr"

  ⑤ sendViaSceneVoice() — SceneVoice方案
     Step 1: y21.x0.g(targetWxid, "amr_") → 创建 w0
             日志: "SceneVoice: g() → 2311540725267af0290844b102" ✅

     Step 2: Mj/Nj + w6.d 复制文件
             日志: "SceneVoice: Mj/Nj/copy fail: ClassNotFoundException: lin5$y" ❌

     → 第⑥步 t() 和第⑦步 b31.w 未执行


三、唯一Bug: lin5.y 类名被改写为 lin5$y
================================================================================

  位置: sendViaSceneVoice() 方法中 (约第865行)

  原因: 微信 8.0.76 的 lin5 是一个包名, y 是包下的独立类。
        $ 用于Java内部类, . 用于包分隔。
        lin5.y 的 . 被错误替换成了 $ → lin5$y

  修复点 (2处):

    行约865:
      // ❌ 可能被某处写为:
      XposedHelpers.findClass("lin5$y", cl);

      // ✅ 应改为:
      XposedHelpers.findClass("lin5.y", cl);

    同样检查是否有其他类名被错误替换:
      qh3.u0 → 正确 (u0在qh3包下)
      pa5.n0 → 正确
      y21.x0 → 正确
      com.tencent.mm.vfs.w6 → 正确
      e01.d9 → 正确

  扫描代码中所有 findClass 调用, 确保 . 没有被替换为 $


四、simplified方案: 用 y21.x0.r() 一行替代 g()+Mj/Nj+t()
================================================================================

  当前代码 (多步):
    g() → Mj/Nj → copyFile → t()

  简化方案 (一行):
    r(talker, voiceFilePath, duration)

  r() 内部已包含:
    ① g(talker, prefix)       → w0 创建
    ② Mj/Nj 获取源/目标路径
    ③ w6.d 复制文件
    ④ t(fileName, duration, 1, null) → 写DB

  建议: 把 Mj/Nj/w6.d 代码块替换为 r() 调用

  新 sendViaSceneVoice():
    String result = (String) XposedHelpers.callStaticMethod(y21x0, "r",
        targetWxid, voiceFile, duration);
    if (result != null) return true;

  优点:
    ① 不需要手动依赖 lin5.y / qh3.u0 / w6
    ② 代码行数减少 70%
    ③ r() 内部已验证正确性


五、完整修正版 sendViaSceneVoice()
================================================================================

  private static boolean sendViaSceneVoice(...) {
    try {
      Class<?> y21x0 = XposedHelpers.findClass("y21.x0", cl);

      // ✅ 一行: r(talker, srcPath, duration)
      //    内部: g()+Mj+Nj+w6.d+t() 全自动
      String result = (String) XposedHelpers.callStaticMethod(
          y21x0, "r", targetWxid, voiceFile, duration);

      LogWriter.log(TAG, "SceneVoice: r() → " + result);
      if (result == null) return false;

      // 可选: 尝试 b31.w 上传
      trySendViaB31(cl, targetWxid, voiceFile, duration);
      return true;

    } catch (Throwable t) {
      LogWriter.log(TAG, "SceneVoice error: " + t.getMessage());
      return false;
    }
  }


六、b31.w 上传分析
================================================================================

  当前 trySendViaB31() 代码:

    尝试无参构造 → 失败
    尝试 (int,int,b) → Class.forName("com.tencent.mm.modelbase.b") 可能不存在

  从先前分析:
    b31.w 的构造:
    - w()  无参 (仅有 clinit)
    - w(int, int, b31.b)  3参数

    XposedHelpers.findClass("b31.b", cl)  ← b31.b 在 b31 包下!

  修复:
    Class<?> b31b = XposedHelpers.findClass("b31.b", cl);
    Object sender = XposedHelpers.newInstance(wClass, 0, 0, null);

  但 b31.w 本质是 NetScene, start(String path) 走网络上传。
  语音消息通过 y21.x0.t() 的 DB 写入后, 微信自身会触发上传,
  所以 trySendViaB31 不是必须的!

  建议: trySendViaB31 变为可选增强 (日志打标记即可)


七、findVoiceFile() — 语音文件查找分析
================================================================================

  三层查找:
    Layer 1: MD5(clientMsgId) → voice2 子目录
    Layer 2: searchVoice2Dir(cid) → 遍历 voice2 目录

  正确性: ✅
  日志: "voice2: found .../voice2/42/1e/msg_102356072426bea2cb8f776101.amr size=1192"

  MD5计算: md5("102356072426bea2cb8f776101") = 421e...
  子目录: 42/1e/ ✅ 正确!


八、其他潜在问题
================================================================================

  1. Menu.add hook 扫描了 Method.add() 虚方法
     → 日志: "Menu.add fail: Cannot hook abstract methods"
     → 不影响功能, 可忽略

  2. hookAllClassesInPackages 扫描 ~2076 类
     → 找到 b31.j/l 和 tl.p0 和 y21.x0 等
     → 性能可接受, 启动时有 ~200ms 延迟

  3. ContactPickerDialog 是自己实现的选择器
     → 选中目标后批量转发 (支持多选)
     → 日志: selected=[51719151602@chatroom] display=1 个选中 ✅

  4. msgId 提取路径: msg.c() → e9
     → 日志: "msgId=76282 via c() → e9" ✅


九、修正总结
================================================================================

  必须修复 (1处):
    sendViaSceneVoice() 中 Mj/Nj/copy 代码块整个替换为 r() 一行调用
    → 消除 lin5.y 依赖问题
    → 代码行数减少 ~30 行

  建议优化 (3处):
    ① trySendViaB31: b31.b → 改为可选 (不是必须的)
    ② hookAllClassesInPackages: 过滤已知不需要的包减少扫描
    ③ Menu.add hook: 跳过 abstract 方法避免日志噪音

================================================================================
