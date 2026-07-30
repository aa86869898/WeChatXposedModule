package com.leshao.v3;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 自动扫码进群 v3 — 纯后台版
 * 
 * 链路:
 *   type=3图片 → 等待大图下载 → RecogQBarOfImageFileEvent触发QR识别
 *   → RecogQBarOfImageFileResultEvent拿结果
 *   → URL含/g/判断群邀请
 *   → y.a()Pipeline → batchgeturlinfo CGI → 自定义回调加群
 */
public class AutoJoinGroup {

    private static final String TAG = "AutoJoinGroup";
    private static final Set<Long> done = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> doneUrls = Collections.synchronizedSet(new HashSet<>());
    
    // ======== 入口: hook IEvent.e() 监听QR结果 ========
    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        try {
            Class<?> iEventClz = cl.loadClass("com.tencent.mm.sdk.event.IEvent");
            Class<?> resultClz = cl.loadClass(
                "com.tencent.mm.autogen.events.RecogQBarOfImageFileResultEvent");

            Method eMethod = iEventClz.getDeclaredMethod("e");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (resultClz.isInstance(p.thisObject)) {
                        onQrResult(p.thisObject);
                    }
                }
            });
            Log.e(TAG, "QR监听已挂载");
        } catch (Throwable e) {
            Log.e(TAG, "hook失败: " + e.getMessage(), e);
        }
    }

    // ======== MessageHook type==3 时调用 ========
    @SuppressWarnings("unchecked")
    public static void onImageMsg(Object e9) {
        try {
            long msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId");
            if (done.contains(msgId)) return;

            String path = (String) XposedHelpers.callMethod(e9, "y0");
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            Log.e(TAG, "图片: msgId=" + msgId + " talker=" + talker + " path=" + path);

            if (path == null || path.isEmpty()) {
                Log.e(TAG, "  路径为空, 跳过");
                return;
            }

            // THUMBNAIL_DIRPATH是虚拟路径, QR引擎可能读不了
            // 尝试获取真实路径: 从消息XML解析content URI
            String realPath = path;
            if (path.startsWith("THUMBNAIL_DIRPATH://")) {
                String content = (String) XposedHelpers.callMethod(e9, "j");
                if (content != null && content.contains("cdnattachurl")) {
                    // 尝试从Content URI加载
                    realPath = path; // 先用缩略图试, 同时延迟重试大图
                }
                
                // 检查是否为真实文件
                File f = new File(path);
                if (!f.exists() || f.length() == 0) {
                    // 不是真实文件, 用原始路径碰运气
                    String y0 = (String) XposedHelpers.callMethod(e9, "y0");
                    if (y0 != null && !y0.startsWith("THUMBNAIL_DIRPATH")) {
                        realPath = y0;
                    }
                }
            }

            Log.e(TAG, "  realPath=" + realPath);
            done.add(msgId);
            triggerQr(msgId, realPath, e9.getClass().getClassLoader());

        } catch (Throwable e) {
            Log.e(TAG, "onImageMsg err", e);
        }
    }

    // ======== 触发QR识别 (RecogQBarOfImageFileEvent) ========
    private static void triggerQr(long msgId, String imgPath, ClassLoader cl) {
        try {
            Class<?> evtClz = cl.loadClass(
                "com.tencent.mm.autogen.events.RecogQBarOfImageFileEvent");
            Object event = XposedHelpers.newInstance(evtClz);
            Object g = XposedHelpers.getObjectField(event, "g");  // am.bq

            XposedHelpers.setLongField(g, "a", msgId);
            XposedHelpers.setObjectField(g, "b", imgPath);
            XposedHelpers.setBooleanField(g, "e", false);
            XposedHelpers.setIntField(g, "f", 0);
            XposedHelpers.setBooleanField(g, "g", false);
            XposedHelpers.setObjectField(g, "h", "");
            XposedHelpers.setIntField(g, "j", 0);

            XposedHelpers.callMethod(event, "e");
            Log.e(TAG, "QR触发: msgId=" + msgId);

        } catch (Throwable e) {
            Log.e(TAG, "QR触发失败: " + e.getMessage());
        }
    }

    // ======== QR结果回调 ========
    @SuppressWarnings("unchecked")
    private static void onQrResult(Object event) {
        try {
            Object g = XposedHelpers.getObjectField(event, "g");  // am.dq
            String imgPath = (String) XposedHelpers.getObjectField(g, "a");
            ArrayList<String> texts = (ArrayList<String>) XposedHelpers.getObjectField(g, "b");

            if (texts == null || texts.isEmpty()) {
                return;
            }

            Log.e(TAG, "=== QR结果 " + texts.size() + "个 ===");
            for (String url : texts) {
                Log.e(TAG, "  url=" + url);
                
                if (doneUrls.contains(url)) continue;
                
                if (isGroup(url)) {
                    doneUrls.add(url);
                    Log.e(TAG, "  ★★★ 群邀请 ★★★");
                    joinByPipeline(url, event.getClass().getClassLoader());
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "onQrResult err: " + e.getMessage());
        }
    }

    // ======== 群URL判断 ========
    private static boolean isGroup(String url) {
        return url != null && url.contains("/g/") && url.contains("weixin.qq.com");
    }

    // ======== 纯后台加群: y.a() Pipeline ========
    private static void joinByPipeline(String url, ClassLoader cl) {
        try {
            Log.e(TAG, "→ Pipeline加群: " + url);

            // 1. 构造 f2 URL包装
            Class<?> f2Clz = cl.loadClass("jd0.f2");
            Object f2 = XposedHelpers.newInstance(f2Clz);
            XposedHelpers.setObjectField(f2, "a", url.trim());

            // 2. y.a(type=22, f2) 创建 Pipeline
            Class<?> yClz = cl.loadClass("com.tencent.mm.plugin.scanner.y");
            Object y = XposedHelpers.newInstance(yClz);
            Object pipeline = XposedHelpers.callMethod(y, "a", 22, f2);  // 22=WX_CODE

            // 3. 设置回调 — 实现 rn5.f 接口来接收结果
            // Pipeline.d() 执行 → x.call() 调 batchgeturlinfo CGI
            // → 结果传给回调 → 在回调中发起加入CGI
            Class<?> fIface = cl.loadClass("rn5.f");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                cl,
                new Class[]{fIface},
                (proxy, method, args) -> {
                    if (method.getName().equals("a")) {
                        // 回调参数: args[0]=mr3响应, args[1]=?
                        Log.e(TAG, "Pipeline回调收到: " + 
                            (args != null && args.length > 0 ? args[0] : "null"));
                        // TODO: 解析mr3, 发起加入CGI
                    }
                    return null;
                }
            );

            // 4. Pipeline.b(callback) 设置回调
            XposedHelpers.callMethod(pipeline, "b", callback);

            // 5. Pipeline.d() 执行
            XposedHelpers.callMethod(pipeline, "d");
            Log.e(TAG, "Pipeline已启动: " + url);

        } catch (Throwable e) {
            Log.e(TAG, "Pipeline失败: " + e.getMessage(), e);
            // 回退: 用旧的 CreateOrJoinChatroomEvent
            fallbackJoin(url, cl);
        }
    }

    // ======== 回退: CreateOrJoinChatroomEvent ========
    private static void fallbackJoin(String url, ClassLoader cl) {
        try {
            Log.e(TAG, "回退CreateOrJoinChatroomEvent: " + url);
            Class<?> evtClz = cl.loadClass(
                "com.tencent.mm.autogen.events.CreateOrJoinChatroomEvent");
            Object event = XposedHelpers.newInstance(evtClz);
            Object g = XposedHelpers.getObjectField(event, "g");
            XposedHelpers.setIntField(g, "a", 2);
            XposedHelpers.setObjectField(g, "b", new String[]{url});
            XposedHelpers.setObjectField(g, "c", new String[]{"", "", "", "", "", "", ""});
            XposedHelpers.callMethod(event, "e");
            Log.e(TAG, "回退事件已发");
        } catch (Throwable e) {
            Log.e(TAG, "回退失败: " + e.getMessage());
        }
    }
}
