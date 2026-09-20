package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 纯后台拆红包/收转账：不打开聊天页、不扫描视图点击，
 * 直接从消息 XML 解析 sendId/channelId/nativeUrl，构造领取 Scene 并后台派发。
 *
 * 精确链路（见 自动抢红包并播报.md）：
 *   new n6(1, channelId, sendId, nativeUrl, inWay, "v1.0", sessionUsername)
 *   → gp0.j1.j().e().h(scene, 0)   // NetSceneQueue.doScene
 *   → n6.onGYNetEnd(0, msg, json)  → json.optLong("sceneAmount") 分 → TTS 播报
 *
 * n6 类用 DexKit 动态定位（字符串锚点 receivewxhb / anymore sceneAmount），不再硬编码类名。
 */
public class LuckMoneyBackend {

    private static final String TAG = "LuckMoneyBackend";
    private static final AtomicBoolean sScanning = new AtomicBoolean(false);

    // n6 (NetSceneReceiveLuckyMoney) 类，lazy 定位
    private static volatile Class<?> sSceneClass;
    // appmsg 解析类 (dx0.r 等价): 静态 v(String) → 实例含 s1 字段=nativeUrl
    private static volatile Class<?> sParserClass;
    private static volatile String sParserVMethod = "v";
    private static volatile boolean sInitDone;

    private LuckMoneyBackend() {}

    public static void init(ClassLoader cl) {
        if (sInitDone) return;
        sInitDone = true;
        try {
            locateReceiveScene(cl);
            locateAppMsgParser(cl);
            if (sSceneClass != null) {
                hookSceneResult(cl, sSceneClass);
            }
            LogWriter.log(TAG, "init: scene=" + (sSceneClass != null ? sSceneClass.getName() : "null")
                + " parser=" + (sParserClass != null ? sParserClass.getName() : "null"));
        } catch (Throwable t) {
            LogWriter.log(TAG, "init err: " + t);
        }
    }

    // ==================== 红包入口 ====================

    public static void grabRedPacket(String talker, String content, long msgId) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            if (cl == null) return;
            init(cl);

            String nativeUrl = parseNativeUrl(cl, content);
            if (nativeUrl == null || nativeUrl.isEmpty()) {
                LogWriter.log(TAG, "grab: no nativeUrl (msgId=" + msgId + ")");
                return;
            }
            LogWriter.log(TAG, "grab: nativeUrl=" + truncate(nativeUrl, 90));

            // 只抢可识别红包类型（普通/商家）
            if (!nativeUrl.startsWith("wxpay://c2cbizmessagehandler/hongbao")
                && !nativeUrl.startsWith("weixin://openNativeUrl/weixinHB")) {
                LogWriter.log(TAG, "grab: not hongbao url, skip");
                return;
            }

            String sendId = queryParam(nativeUrl, "sendid");
            if (sendId == null || sendId.isEmpty()) {
                // 兜底: 从 XML 直接取 sendid
                sendId = extractParam(content, "sendid");
            }
            if (sendId == null || sendId.isEmpty()) {
                LogWriter.log(TAG, "grab: no sendId, skip");
                return;
            }
            String channel = queryParam(nativeUrl, "channelid");
            if (channel == null || channel.isEmpty()) {
                channel = extractParam(content, "channelid");
            }
            int channelId = 1;
            if (channel != null && channel.matches("\\d+")) channelId = Integer.parseInt(channel);
            String session = talker != null ? talker : "";
            int inWay = 1;

            Object scene = buildScene(cl, channelId, sendId, nativeUrl, inWay, session);
            if (scene == null) {
                LogWriter.log(TAG, "grab: buildScene failed");
                return;
            }
            logSceneClass(scene);

            boolean dispatched = dispatchScene(cl, scene);
            LogWriter.log(TAG, "grab: dispatched=" + dispatched + " scene="
                + scene.getClass().getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "grabRedPacket err: " + t);
        }
    }

    // ==================== 转账入口 ====================

    public static void grabTransfer(String talker, String content, long msgId) {
        try {
            LogWriter.log(TAG, "grabTransfer: 转账自动收款暂走 UI 点击链路(Rm 插件 RemittanceUI)");
            // 转账类型校验较重, 为避免误触发, 当前以 UI 自动化为主(RedPacketHook hookTransferUIs)。
            // 后台收款 CGI (NetSceneRemittance) 过于复杂且风控高, 保留后续扩展。
        } catch (Throwable t) {
            LogWriter.log(TAG, "grabTransfer err: " + t);
        }
    }

    // ==================== XML / URL 解析 ====================

    static String parseNativeUrl(ClassLoader cl, String content) {
        if (content == null || content.isEmpty()) return null;
        // 1) appmsg 解析器 dx0.r.v(content).s1 (优先, md 官方判定点)
        if (cl != null && sParserClass == null) locateAppMsgParser(cl);
        Object r = null;
        if (sParserClass != null) {
            try {
                Method v = null;
                for (Method m : sParserClass.getDeclaredMethods()) {
                    if (m.getName().equals(sParserVMethod) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                        v = m;
                        break;
                    }
                }
                if (v == null) v = sParserClass.getMethod(sParserVMethod, String.class);
                v.setAccessible(true);
                r = v.invoke(null, content);
            } catch (Throwable ignored) {}
        }
        if (r != null) {
            try {
                for (Field f : r.getClass().getDeclaredFields()) {
                    if ((f.getName().equals("s1") || f.getName().contains("native"))
                        && f.getType() == String.class) {
                        f.setAccessible(true);
                        String u = (String) f.get(r);
                        if (u != null && !u.isEmpty()) {
                            LogWriter.log(TAG, "parseNativeUrl: via parser." + f.getName());
                            return u;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // 兜底: 遍历 String 字段找以 wxpay:// 或 weixin:// 开头的
            try {
                for (Field f : r.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object v = f.get(r);
                        if (v instanceof String) {
                            String s = (String) v;
                            if (s.startsWith("wxpay://") || s.startsWith("weixin://")) {
                                LogWriter.log(TAG, "parseNativeUrl: via anyStringField " + f.getName());
                                return s;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        // 2) 直接正则取 <nativeurl>/<url> 标签
        String url = extractTag(content, "nativeurl");
        if (url == null || url.isEmpty()) url = extractTag(content, "url");
        return url;
    }

    static String extractTag(String xml, String tag) {
        if (xml == null || tag == null) return null;
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        s += tag.length() + 2;
        int e = xml.indexOf("</" + tag + ">", s);
        if (e < 0) return null;
        String v = xml.substring(s, e);
        return v.trim();
    }

    static String extractParam(String s, String key) {
        if (s == null || key == null) return null;
        Matcher m = Pattern.compile("(?:[?&;])" + key + "=([^&\"'<>\\s]+)").matcher(s);
        if (m.find()) {
            String v = m.group(1);
            if (v.endsWith("&amp;")) v = v.substring(0, v.length() - 5);
            return v;
        }
        m = Pattern.compile("<" + key + ">([^<]+)</" + key + ">").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String queryParam(String url, String key) {
        if (url == null || key == null) return null;
        try {
            return android.net.Uri.parse(url).getQueryParameter(key);
        } catch (Throwable t) {
            return extractParam(url, key);
        }
    }

    // ==================== 类定位 (DexKit 字符串锚点) ====================

    /** 定位红包领取 Scene (n6): findClassesByString("receivewxhb") */
    private static void locateReceiveScene(ClassLoader cl) {
        String[] anchors = {"receivewxhb", "sceneAmount", "NetSceneReceiveLuckyMoney"};
        for (String kw : anchors) {
            List<String> cands = DexKitHelper.findClassesByString(cl, kw);
            LogWriter.log(TAG, "locateScene(" + kw + "): " + cands.size() + " cands");
            Class<?> hit = pickSceneClass(cl, cands);
            if (hit != null) {
                sSceneClass = hit;
                LogWriter.log(TAG, "locateScene: " + hit.getName());
                return;
            }
        }
        // 兜底: 直接尝试已知短类名
        for (String n : new String[]{"com.tencent.mm.plugin.luckymoney.model.n6",
            "com.tencent.mm.plugin.luckymoney.model.o6"}) {
            try {
                sSceneClass = cl.loadClass(n);
                LogWriter.log(TAG, "locateScene (hardcoded): " + n);
                return;
            } catch (Throwable ignored) {}
        }
    }

    /** 从候选类中挑选构造签名匹配的领取 Scene 类 */
    private static Class<?> pickSceneClass(ClassLoader cl, List<String> cands) {
        if (cands == null) return null;
        for (String cn : cands) {
            try {
                Class<?> c = cl.loadClass(cn);
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    Class<?>[] pts = ctor.getParameterTypes();
                    if (pts.length == 7 && pts[0] == int.class && pts[1] == int.class
                        && pts[2] == String.class && pts[3] == String.class
                        && pts[4] == int.class && pts[5] == String.class && pts[6] == String.class) {
                        return c;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 定位 appmsg 解析类 (dx0.r): 包含 receivehongbao 字符串的类, 找静态 v(String) */
    private static void locateAppMsgParser(ClassLoader cl) {
        String[] anchors = {"receivehongbao", "weixin://openNativeUrl/weixinHB"};
        for (String kw : anchors) {
            List<String> cands = DexKitHelper.findClassesByString(cl, kw);
            for (String cn : cands) {
                try {
                    Class<?> c = cl.loadClass(cn);
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals("v") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == String.class
                            && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                            sParserClass = c;
                            sParserVMethod = "v";
                            LogWriter.log(TAG, "locateParser: " + cn + ".v(String)");
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        // 兜底: 硬编码
        try {
            Class<?> c = cl.loadClass("dx0.r");
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("v") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                    sParserClass = c;
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ==================== Scene 构造 + 派发 ====================

    private static Object buildScene(ClassLoader cl, int channelId, String sendId,
                                     String nativeUrl, int inWay, String session) {
        if (sSceneClass == null) locateReceiveScene(cl);
        if (sSceneClass == null) return null;
        try {
            Constructor<?> target = null;
            for (Constructor<?> ctor : sSceneClass.getDeclaredConstructors()) {
                Class<?>[] pts = ctor.getParameterTypes();
                if (pts.length == 7) {
                    target = ctor;
                    break;
                }
            }
            if (target == null) {
                // 尝试构造参数较少的（商家/简化场景退路）
                for (Constructor<?> ctor : sSceneClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 4 || ctor.getParameterCount() == 5
                        || ctor.getParameterCount() == 6) {
                        target = ctor;
                        break;
                    }
                }
            }
            if (target == null) {
                LogWriter.log(TAG, "buildScene: no suitable ctor for "
                    + sSceneClass.getName());
                return null;
            }
            target.setAccessible(true);
            Class<?>[] pts = target.getParameterTypes();
            Object[] args = new Object[pts.length];
            int strIdx = 0;
            String[] strs = {sendId, nativeUrl, session, "v1.0"};
            for (int i = 0; i < pts.length; i++) {
                Class<?> p = pts[i];
                if (p == int.class || p == Integer.class) {
                    args[i] = (i == 0) ? 1 : ((i == 1) ? channelId : inWay);
                } else if (p == String.class) {
                    args[i] = (strIdx < strs.length && strs[strIdx] != null) ? strs[strIdx] : "";
                    strIdx++;
                } else {
                    args[i] = defaultArg(p);
                }
            }
            return target.newInstance(args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "buildScene err: " + t);
            return null;
        }
    }

    private static Object defaultArg(Class<?> p) {
        if (p == boolean.class) return false;
        if (p == long.class || p == long.class) return 0L;
        if (p == float.class) return 0f;
        if (p == double.class) return 0d;
        return null;
    }

    private static void logSceneClass(Object scene) {
        try {
            StringBuilder sb = new StringBuilder("scene ctor: ").append(scene.getClass().getName())
                .append(" (");
            for (Constructor<?> c : scene.getClass().getDeclaredConstructors()) {
                sb.append(c.getParameterCount()).append(",");
            }
            sb.append(")");
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable ignored) {}
    }

    /** gp0.j1.j() → kernel.e() → queue.h(scene, 0) */
    private static boolean dispatchScene(ClassLoader cl, Object scene) {
        try {
            Class<?> kernelCls = null;
            List<String> cands = new ArrayList<>();
            String dk = DexKitHelper.getJ1ServiceClass();
            if (dk != null && !dk.isEmpty()) cands.add(dk);
            cands.add("gp0.j1");
            cands.add("gp0.j1.j");
            cands.add("hm0.j1");
            for (String cn : cands) {
                try {
                    kernelCls = cl.loadClass(cn);
                    break;
                } catch (Throwable ignored) {}
            }
            if (kernelCls == null) {
                // DexKit 定位: 静态 j() 方法所在类 (MMKernel)
                List<String> kcs = DexKitHelper.findClassesByString(cl, "MMKernel");
                for (String cn : kcs) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        boolean hasJ = false;
                        for (Method m : c.getDeclaredMethods()) {
                            if (m.getName().equals("j") && m.getParameterCount() == 0
                                && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                                hasJ = true;
                                break;
                            }
                        }
                        if (hasJ) { kernelCls = c; break; }
                    } catch (Throwable ignored) {}
                }
            }
            if (kernelCls == null) {
                LogWriter.log(TAG, "dispatch: no kernel class");
                return false;
            }

            Object kernel = callStaticNoArg(kernelCls, "j");
            if (kernel == null) {
                LogWriter.log(TAG, "dispatch: kernel.j() null");
                return false;
            }
            Object queue = callNoArg(kernel, "e");
            if (queue == null) {
                LogWriter.log(TAG, "dispatch: kernel.e() null");
                return false;
            }
            for (Method m : queue.getClass().getDeclaredMethods()) {
                if (m.getName().equals("h") && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    Object r = m.invoke(queue, scene, 0);
                    LogWriter.log(TAG, "dispatch: queue.h -> " + r);
                    return true;
                }
            }
            LogWriter.log(TAG, "dispatch: no h(m1,int) found");
            return false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "dispatch err: " + t);
            return false;
        }
    }

    private static Object callStaticNoArg(Class<?> cls, String name) {
        try {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : cls.getDeclaredMethods()) {
                if (mm.getName().equals(name) && mm.getParameterCount() == 0) {
                    m = mm;
                    break;
                }
            }
            if (m == null) m = cls.getMethod(name);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object callNoArg(Object obj, String name) {
        try {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : obj.getClass().getDeclaredMethods()) {
                if (mm.getName().equals(name) && mm.getParameterCount() == 0) {
                    m = mm;
                    break;
                }
            }
            if (m == null) m = obj.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 领取结果 → TTS 播报 ====================

    /** hook n6.onGYNetEnd(int,String,JSONObject): 成功抢到(sceneAmount>0)即播报 */
    private static void hookSceneResult(ClassLoader cl, Class<?> sceneCls) {
        try {
            String[] names = {"onGYNetEnd", "e", "c"};
            for (String mn : names) {
                for (Method m : sceneCls.getDeclaredMethods()) {
                    if (!m.getName().equals(mn)) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != 3) continue;
                    // 优先精准匹配 (int,String,JSONObject)
                    boolean json = pts[2] == org.json.JSONObject.class
                        || pts[2].getName().equals("org.json.JSONObject");
                    if (json) {
                        final String fName = mn;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object self = param.thisObject;
                                    int err = param.args[0] instanceof Number
                                        ? ((Number) param.args[0]).intValue() : -1;
                                    Object jo = param.args[2];
                                    if (!(jo instanceof JSONObject)) return;
                                    JSONObject jsonObj = (JSONObject) jo;
                                    long fen = jsonObj.optLong("sceneAmount", 0);
                                    String wishing = jsonObj.optString("wishing", "");
                                    String sendNick = jsonObj.optString("sendNick", "");
                                    int receiveStatus = jsonObj.optInt("receiveStatus", 0);
                                    if (err == 0 && fen > 0) {
                                        double yuan = fen / 100.0;
                                        String amt = String.format(java.util.Locale.CHINA, "%.2f", yuan);
                                        String sender = (sendNick != null && !sendNick.isEmpty())
                                            ? sendNick : "";
                                        String room = (self != null) ? self.getClass().getName() : "";
                                        LogWriter.log(TAG, "GOT redpacket amount=" + amt
                                            + " yuan receiveStatus=" + receiveStatus
                                            + " wishing=" + wishing);
                                        com.leshao.v3.service.TTSBroadcaster.announceRedPacket(
                                            sender, null, wishing, amt);
                                    }
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "sceneResult cb err: " + t);
                                }
                            }
                        });
                        LogWriter.log(TAG, "hooked " + sceneCls.getName() + "." + fName
                            + "(int,String,JSONObject) OK");
                        return;
                    }
                }
            }
            LogWriter.log(TAG, "hookSceneResult: not found onGYNetEnd(JSONObject)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookSceneResult err: " + t);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}