/**
 * 🔴 修复核心: content=talker → 从 p0 protobuf 解码
 *
 * 日志证据:
 *   E9 String: x2=""    (空)
 *   E9 String: z2=""    (空)
 *   M-STR: wxid_9ohhf82mrlgc22 ← 这是 talker,不是内容!
 *   #1 content=wxid_9ohhf82mrlgc22 ← 错误!
 *
 * 根因: e01.x9.n(e9, p0) 时 e9 刚创建,内容在 p0 protobuf 字节中
 *       m() 方法的 String 参数是 talker 用户名, 不是消息文本
 *
 * 解决: 从 p0(AddMsgInfo) 直接读取 protobuf 字节,
 *       或换用 e01.x9.e(e9, boolean) 此时 e9 内容已完整
 *
 * ★ 方案A: Hook e() 而非 n() — e9 内容已完整
 * ★ 方案B: 从 p0 字节流提取 protobuf → 解码 XML
 * ★ 方案C: 延迟读取 — n() 后 post 100ms 再读 e9.I0()
 */

// ============================
// 方案A (推荐): 主力用 e() Hook
// ============================
// e01.x9.e(e9 msgInfo, boolean isNew)
// 此时 e9 已完整写入, I0()/w2/x2 等都可读

XposedHelpers.findAndHookMethod("e01.x9", cl, "e",
    e9Cls, boolean.class,
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object e9 = param.args[0];
            boolean isNew = (boolean) param.args[1];
            if (!isNew) return;

            // ★ e9 已完整, 直接用 I0()
            int type  = (int) XposedHelpers.callMethod(e9, "getType");
            int isSend = getIntField(e9, "A2");  // 日志证明 A2=0
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = (String) XposedHelpers.callMethod(e9, "I0");

            // ★ I0() 如果还空, 直接遍历所有 String 字段
            if (content == null || content.isEmpty()) {
                for (java.lang.reflect.Field f : e9.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        try {
                            String v = (String) f.get(e9);
                            if (v != null && v.length() > 5
                                && !v.equals(talker)
                                && !v.startsWith("<xml>")
                                && !v.startsWith("<msg>")) {
                                content = v;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            XposedBridge.log("[MsgHook-E] #" + (++cnt) +
                " type=" + type + " isSend=" + isSend +
                " talker=" + trunc(talker,20) +
                " content=" + trunc(content,50));

            if (isSend == 1) return;
            if (type != 1 && type != 3 && type != 34 && type != 43 && type != 48) return;
            dispatch(type, talker, content);
        }
    });

// ============================
// 方案B (备选): n() 从 p0 protobuf 提取
// ============================
// p0 是 protobuf 消息, 包含 msgId/talker/content 的原始字节
// 反射找 byte[] 字段, 解析 protobuf

XposedHelpers.findAndHookMethod("e01.x9", cl, "n",
    e9Cls, p0Cls,
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object p0 = param.args[1];
            if (p0 == null) return;

            // ★ 找 p0 的 byte[] 字段 (protobuf 原始字节)
            byte[] protoBytes = null;
            for (java.lang.reflect.Field f : p0.getClass().getDeclaredFields()) {
                if (f.getType() == byte[].class) {
                    f.setAccessible(true);
                    try {
                        byte[] b = (byte[]) f.get(p0);
                        if (b != null && b.length > 10) {
                            protoBytes = b;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // ★ 找 p0 的 String 字段 (可能直接有 content)
            String content = null;
            for (java.lang.reflect.Field f : p0.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    try {
                        String v = (String) f.get(p0);
                        if (v != null && v.length() > 1
                            && !v.contains("@")
                            && !v.matches("\\d+")) {
                            content = v;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (content != null) {
                XposedBridge.log("[MsgHook-N] content from p0: " + trunc(content, 50));
                // 缓存 content, 等 e() 阶段使用
                contentCache.put(param.args[0], content);
            }
        }
    });

// ============================
// 方案C (兜底): n() 后延迟 200ms 读 e9
// ============================
final Object e9Ref = param.args[0];
new Handler(Looper.getMainLooper()).postDelayed(() -> {
    String delayed = (String) XposedHelpers.callMethod(e9Ref, "I0");
    XposedBridge.log("[MsgHook-delay] content=" + trunc(delayed, 50));
}, 200);

// 辅助方法
static Map<Object,String> contentCache = new WeakHashMap<>();
static int cnt = 0;
static int getIntField(Object obj, String name) {
    try { Field f=obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(obj); }
    catch(Exception e){ return -1; }
}
static String trunc(String s, int m) { return s==null?"null":s.length()>m?s.substring(0,m)+"...":s; }
