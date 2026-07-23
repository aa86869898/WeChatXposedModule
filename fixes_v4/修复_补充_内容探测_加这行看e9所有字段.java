/**
 * 内容探测: 在 handleN() 或 handleE() 第一行加:
 * 打印 e9 所有字段名+值, 找到真正的 content 字段
 */

for (java.lang.reflect.Field f : e9Obj.getClass().getDeclaredFields()) {
    f.setAccessible(true);
    try {
        Object v = f.get(e9Obj);
        String val = v == null ? "null" :
            (v instanceof String ? "\"" + v + "\"" : v.toString());
        if (val.length() > 60) val = val.substring(0, 60) + "...";
        XposedBridge.log("[PROBE] " + f.getType().getSimpleName() +
            " " + f.getName() + " = " + val);
    } catch (Exception ignored) {}
}
// 然后找到长度>3的 String 字段, 那就是 content
