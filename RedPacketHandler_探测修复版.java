/**
 * 红包播报修复版 — 带 m1 响应全字段探测
 * 导出路径: /sdcard/RedPacketHandler_探测修复版.java
 * 
 * 用法: 替换现有 RedPacketHandler 的 handleReceiveResult() 方法
 *       收到红包后日志会打印 [RP-PROBE] 带所有字段名和值
 */

// ===== 替换 handleReceiveResult =====
void handleReceiveResult(Object ui, Object resp) {
    try {
        // ── 第一步: 打印 resp (m1 响应) 的所有字段 ──
        XposedBridge.log("[RP] ===== m1 fields START =====");
        if (resp != null) {
            for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(resp);
                    String val = v == null ? "null" : 
                        v instanceof byte[] ? "byte[" + ((byte[])v).length + "]" :
                        v.toString();
                    if (val.length() > 100) val = val.substring(0, 100) + "...";
                    XposedBridge.log("[RP-PROBE] " + f.getType().getSimpleName() + 
                        " " + f.getName() + " = " + val);
                } catch (Exception e) {
                    XposedBridge.log("[RP-PROBE] " + f.getType().getSimpleName() + 
                        " " + f.getName() + " = <err>");
                }
            }
        } else {
            XposedBridge.log("[RP-PROBE] resp is NULL!");
        }
        XposedBridge.log("[RP] ===== m1 fields END =====");

        // ── 第二步: 如果 resp 字段没有, 打印 toString ──
        if (resp != null) {
            String str = resp.toString();
            if (str != null && str.length() > 0) {
                XposedBridge.log("[RP-TOSTR] " + (str.length() > 300 ? 
                    str.substring(0, 300) + "..." : str));
            }
        }

        // ── 第三步: 尝试从 UI 对象打印字段 ──
        if (ui != null) {
            XposedBridge.log("[RP] ===== UI fields START =====");
            for (java.lang.reflect.Field f : ui.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(ui);
                    if (v == null) continue;
                    String val = v.toString();
                    if (val.length() > 80) val = val.substring(0, 80) + "...";
                    // 只打印非空 String/int/double 类型
                    if (v instanceof String || f.getType() == int.class 
                        || f.getType() == double.class || f.getType() == long.class
                        || f.getType() == float.class) {
                        XposedBridge.log("[RP-UI] " + f.getType().getSimpleName() + 
                            " " + f.getName() + " = " + val);
                    }
                } catch (Exception ignored) {}
            }
            XposedBridge.log("[RP] ===== UI fields END =====");
        }

        // ── 第四步: 尝试提取金额并播报 ──
        String amount = null;
        String sender = null;
        String wishing = null;

        if (resp != null) {
            // 反射所有字段找金额
            for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                String n = f.getName().toLowerCase();
                try {
                    Object v = f.get(resp);
                    if (v == null) continue;

                    // 金额: 搜索 amount/total/fee/receive 关键字的 String/int/long 字段
                    if ((n.contains("amount") || n.contains("total") || n.contains("fee") 
                         || n.contains("receive") || n.contains("money")
                         || n.contains("hb") || n.contains("value") || n.contains("price"))
                        && !n.contains("req") && !n.contains("type") && !n.contains("status")) {
                        if (v instanceof String) {
                            amount = (String) v;
                            XposedBridge.log("[RP-AMT] candidate: " + n + "=" + amount);
                        } else if (v instanceof Integer || v instanceof Long) {
                            long fen = ((Number)v).longValue();
                            if (fen > 0 && fen < 100000000) {  // 合理范围: 0~100万元
                                amount = String.valueOf(fen);
                                XposedBridge.log("[RP-AMT] candidate(int): " + n + "=" + fen);
                            }
                        }
                    }
                    // 发送者
                    if ((n.contains("send") || n.contains("from") || n.contains("payer") || n.contains("nick"))
                        && !n.contains("type") && !n.contains("id") && v instanceof String) {
                        String s = (String) v;
                        if (s.length() > 1 && s.length() < 50) {
                            sender = s;
                            XposedBridge.log("[RP-SENDER] candidate: " + n + "=" + s);
                        }
                    }
                    // 祝福语
                    if ((n.contains("wish") || n.contains("desc") || n.contains("greet") 
                         || n.contains("word") || n.contains("msg") || n.contains("text"))
                        && v instanceof String) {
                        String s = (String) v;
                        if (s.length() > 1 && s.length() < 100) {
                            wishing = s;
                            XposedBridge.log("[RP-WISH] candidate: " + n + "=" + s);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // ── 如果 resp 没找到, 从 UI 对象找 ──
        if (amount == null && ui != null) {
            for (java.lang.reflect.Field f : ui.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                String n = f.getName().toLowerCase();
                if (!n.contains("amount") && !n.contains("total") && !n.contains("fee")
                    && !n.contains("money")) continue;
                try {
                    Object v = f.get(ui);
                    if (v instanceof String) amount = (String) v;
                    else if (v instanceof Number) amount = String.valueOf(((Number)v).longValue());
                    if (amount != null) {
                        XposedBridge.log("[RP-AMT-UI] " + n + "=" + amount);
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        // ── 第五步: 播报 ──
        if (amount != null && amount.length() > 0) {
            String yuan = fenToYuan(amount);
            String name = sender != null ? sender : "好友";
            String wish = wishing != null ? wishing : "恭喜发财";
            String speak = name + "的红包：" + wish + "，你抢到了" + yuan + "元";
            TtsEngine.speak(speak);
            XposedBridge.log("[RP-TTS] " + speak);
        } else {
            XposedBridge.log("[RP-FAIL] no amount found! resp=" + 
                (resp != null ? resp.getClass().getName() : "null"));
        }

    } catch (Throwable t) {
        XposedBridge.log("[RP-ERR] " + t);
        t.printStackTrace();
    }
}

// ===== 辅助方法 =====
static String fenToYuan(String s) {
    try {
        long f = Long.parseLong(s.trim());
        if (f > 10000) return String.format("%.2f", f / 100.0);  // >10000 = 分
        else if (f > 100) return String.format("%.2f", f / 100.0);  // 100~10000 = 分
        else return String.format("%.2f", f);  // 0~100 可能已经是元
    } catch (NumberFormatException e) { return s; }
}


/**
 * ===== 收款/转账修复 =====
 * 同理, 在 AutoCollect 的 onSceneEnd 回调里加同样的探测代码
 * 把 [RP-PROBE] 改为 [AC-PROBE] 即可
 *
 * 关键: 
 * 1. onSceneEnd 的 4 个参数: (int errType, int errCode, String errMsg, m1 resp)
 *    只有 errType==0 && errCode==0 才表示成功
 * 2. resp (m1) 对象的字段才是关键, 里面有金额
 * 3. 如果 onSceneEnd 根本没触发, 说明 Hook 的方法签名不对
 *    当前 Hook: onSceneEnd(int, int, String, m1)
 *    如果实际方法是 onSceneEnd(int, int, String, m1, boolean) 就匹配不上
 *    需要扫描 onSceneEnd 的所有重载
 */
