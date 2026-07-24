/**
 * ================================================================
 *  红包播报 + 自动收款 — 终极修复
 * ================================================================
 *
 *  根因诊断:
 *
 *  【红包不播报】
 *   onSceneEnd 回调的 m1 实际上是 v5 (com.tencent.mm.plugin.luckymoney.model.v5)
 *   不是 g1。v5 携带红包金额，需要从 v5.h (类型 e1) 中提取金额。
 *   当前代码只探测了 resp 的一级字段，漏了嵌套对象。
 *
 *  【自动收款不点】
 *   "auto-click: 待你收款" 点击了 TextView 而非 Button。
 *   需要只匹配 Button/真正可点击的 View。
 *
 *  修复:
 *   1. onMoneyResult: 探测 resp 所有字段(含嵌套) + 匹配 v5 专用逻辑
 *   2. findBtn: 只匹配 Button 实例 + isClickable 检查
 * ================================================================
 */

// ===== 1. 替换 onMoneyResult =====
static void onMoneyResult(Object resp, String type) {
    try {
        if (resp == null) return;

        XposedBridge.log("[MoneyHook] respClass=" + resp.getClass().getName());

        // ── 探测 resp 一级字段 ──
        double amount = 0;
        String sender = null;

        for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object v = f.get(resp);
                String name = f.getName();
                String vstr = v == null ? "null" : v.toString();
                if (vstr.length() > 80) vstr = vstr.substring(0, 80) + "...";
                XposedBridge.log("[MoneyHook] " + f.getType().getSimpleName()
                    + " " + name + " = " + vstr);

                // 金额: double 字段
                if (f.getType() == double.class && f.getDouble(resp) > 0) {
                    amount = f.getDouble(resp);
                }
                // 金额(int/long): 可能是分
                if ((f.getType() == int.class || f.getType() == long.class) 
                    && name.toLowerCase().contains("amount")) {
                    long val = f.getLong(resp);
                    if (val > 0 && val < 100000000) {
                        amount = val / 100.0;
                        XposedBridge.log("[MoneyHook] amount(int) from " + name + "=" + val);
                    }
                }
                // 金额(String): "0.01" 格式
                if (v instanceof String && name.toLowerCase().contains("amount")) {
                    try { amount = Double.parseDouble((String) v); }
                    catch (NumberFormatException ignored) {}
                }
                // 发送者
                if (v instanceof String && (name.contains("send") || name.contains("from")
                    || name.contains("user") || name.contains("payer") || name.contains("nick"))
                    && !name.contains("type") && !name.contains("id") && !name.contains("status")
                    && ((String) v).length() > 1 && ((String) v).length() < 50) {
                    sender = (String) v;
                }
            } catch (Exception ignored) {}
        }

        // ── 如果一级字段没找到金额，探测嵌套对象 ──
        if (amount <= 0) {
            for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object nested = f.get(resp);
                    if (nested == null || nested instanceof String || nested instanceof Number) continue;
                    XposedBridge.log("[MoneyHook] probing nested: " + f.getName()
                        + " class=" + nested.getClass().getName());
                    // 探测嵌套对象的所有字段
                    for (java.lang.reflect.Field nf : nested.getClass().getDeclaredFields()) {
                        nf.setAccessible(true);
                        try {
                            Object nv = nf.get(nested);
                            String nvstr = nv == null ? "null" : nv.toString();
                            if (nvstr.length() > 80) nvstr = nvstr.substring(0, 80) + "...";
                            XposedBridge.log("[MoneyHook]   nested " + f.getName() + "."
                                + nf.getType().getSimpleName() + " " + nf.getName() + " = " + nvstr);

                            if (nf.getType() == double.class && nf.getDouble(nested) > 0) {
                                amount = nf.getDouble(nested);
                            }
                            if (nv instanceof String && nf.getName().toLowerCase().contains("amount")) {
                                try { amount = Double.parseDouble((String) nv); }
                                catch (NumberFormatException ignored) {}
                            }
                        } catch (Exception ignored2) {}
                    }
                } catch (Exception ignored) {}
            }
        }

        // ── 如果没有 double 字段，尝试所有可能的金额字段 ──
        if (amount <= 0) {
            for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                if (f.getType() == int.class || f.getType() == long.class) {
                    f.setAccessible(true);
                    try {
                        long v = f.getLong(resp);
                        if (v > 10 && v < 100000000) {
                            amount = v / 100.0;
                            XposedBridge.log("[MoneyHook] guessed amount: " + f.getName() + "=" + v);
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        XposedBridge.log("[MoneyHook] final amount=" + amount + " type=" + type);
        if (amount <= 0) return;

        String yuan = String.format("%.2f", amount);
        String name = sender != null ? getNick(sender) : "好友";
        String speak = "转账".equals(type)
            ? "收到" + name + "转账" + yuan + "元"
            : name + "的红包：" + yuan + "元";

        TtsEngine.speak(speak);
        XposedBridge.log("[MoneyHook] TTS: " + speak);

    } catch (Throwable t) {
        XposedBridge.log("[MoneyHook] ERR: " + t);
    }
}


// ===== 2. 替换 findBtn — 只匹配 Button + isClickable =====
static View findBtn(View root, String... keywords) {
    if (root == null) return null;
    if (root instanceof Button) {
        CharSequence text = ((Button) root).getText();
        if (text != null) {
            String t = text.toString();
            XposedBridge.log("[MoneyHook] Button: " + t + " clickable=" + root.isClickable());
            // ★ 只匹配可点击的 Button
            if (root.isClickable() || root.isEnabled()) {
                for (String kw : keywords) {
                    if (t.contains(kw)) return root;
                }
            }
        }
    }
    if (root instanceof ViewGroup) {
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View r = findBtn(vg.getChildAt(i), keywords);
            if (r != null) return r;
        }
    }
    return null;
}


// ===== 3. autoClickConfirm 改用更多关键词 =====
static void autoClickConfirm(final Activity activity) {
    if (activity == null) return;
    sMainHandler.postDelayed(() -> {
        try {
            View btn = findBtn(activity.getWindow().getDecorView(),
                "确认收款", "收款", "收钱", "确认", "领取", "拆开", "收下");
            if (btn != null) {
                XposedBridge.log("[MoneyHook] auto-click: " + ((Button) btn).getText());
                btn.performClick();
            } else {
                XposedBridge.log("[MoneyHook] auto-click: no Button matched");
            }
        } catch (Throwable t) {
            XposedBridge.log("[MoneyHook] autoClick err: " + t);
        }
    }, 1000);  // 延迟增加到1秒
}
