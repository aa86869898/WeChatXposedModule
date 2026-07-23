/**
 * p0 字段探测: 打印 AddMsgInfo 所有字段
 * 找到 byte[] 字段 = protobuf 原始字节
 */

for (java.lang.reflect.Field f : p0Obj.getClass().getDeclaredFields()) {
    f.setAccessible(true);
    try {
        Object v = f.get(p0Obj);
        String val;
        if (v instanceof byte[]) {
            val = "byte[" + ((byte[])v).length + "]";
        } else {
            val = v == null ? "null" : v.toString();
            if (val.length() > 60) val = val.substring(0, 60) + "...";
        }
        XposedBridge.log("[PROBE-P0] " + f.getType().getSimpleName() +
            " " + f.getName() + " = " + val);
    } catch (Exception ignored) {}
}
