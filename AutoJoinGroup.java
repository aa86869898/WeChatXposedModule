package com.leshao.v3;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.*;

/**
 * 自动扫码进群 v4
 * 
 * 集成方式(两行):
 *   MainHook.activateAll() 里加: AutoJoinGroup.hook(lpparam);
 *   MessageHook type==3 分支加:  AutoJoinGroup.onImageMsg(e9);
 */
public class AutoJoinGroup {

    private static final String TAG = "AutoJoinGroup";
    private static final Set<Long> doneMsgIds = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> doneUrls = Collections.synchronizedSet(new HashSet<>());

    // ====== hook入口 ======
    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        try {
            // Hook 1: 监听 QR 识别结果
            Class<?> iEventClz = cl.loadClass("com.tencent.mm.sdk.event.IEvent");
            Class<?> resultClz = cl.loadClass("com.tencent.mm.autogen.events.RecogQBarOfImageFileResultEvent");
            Method eMethod = iEventClz.getDeclaredMethod("e");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (resultClz.isInstance(p.thisObject)) onQrResult(p.thisObject);
                }
            });

            // Hook 2: 监听原图写入
            XposedBridge.hookAllConstructors(FileOutputStream.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String path = null;
                        if (param.args.length > 0) {
                            if (param.args[0] instanceof File) path = ((File) param.args[0]).getAbsolutePath();
                            else if (param.args[0] instanceof String) path = (String) param.args[0];
                        }
                        if (path == null || !path.contains("/MicroMsg/") || !path.contains("/image/") || path.contains("/image2/")) return;
                        Log.e(TAG, "★原图写入: " + path);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            File f = new File(path);
                            if (f.exists() && f.length() > 10000) {
                                Log.e(TAG, "★原图就绪→触发QR size=" + f.length());
                                triggerQrRaw(0, path, cl);
                            }
                        }, 500);
                    } catch (Throwable e) {}
                }
            });

            Log.e(TAG, "QR监听已挂载 + FileOutputStream hook OK");
        } catch (Throwable e) {
            Log.e(TAG, "hook失败: " + e.getMessage(), e);
        }
    }

    // ====== MessageHook type==3 时调用 ======
    @SuppressWarnings("unchecked")
    public static void onImageMsg(Object e9) {
        try {
            long msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId");
            if (doneMsgIds.contains(msgId)) return;

            String y0 = (String) XposedHelpers.callMethod(e9, "y0");
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String xml = (String) XposedHelpers.callMethod(e9, "j");
            Log.e(TAG, "图片: msgId=" + msgId + " talker=" + talker);
            doneMsgIds.add(msgId);

            ClassLoader cl = e9.getClass().getClassLoader();
            Class<?> gcClz = cl.loadClass("com.tencent.mm.vfs.ImageGCFileSystem");
            String thumbPath = (String) XposedHelpers.callStaticMethod(gcClz, "c", y0, null, null, false);
            File thumbFile = new File(thumbPath);
            Log.e(TAG, "缩略图: " + thumbPath + " exists=" + thumbFile.exists() + " size=" + (thumbFile.exists() ? thumbFile.length() : 0));

            // 解析 CDN 参数
            Matcher m = Pattern.compile("aeskey=\"([^\"]+)\".*cdnmidimgurl=\"([^\"]+)\"").matcher(xml);
            if (!m.find()) {
                Log.e(TAG, "XML无CDN参数, 兜底缩略图");
                if (thumbFile.exists() && thumbFile.length() > 5000) triggerQrRaw(msgId, thumbPath, cl);
                startPolling(thumbPath, msgId, cl);
                return;
            }
            String aeskey = m.group(1), cdnMidUrl = m.group(2);
            Matcher m2 = Pattern.compile("cdnthumburl=\"([^\"]+)\"").matcher(xml);
            String cdnThumbUrl = m2.find() ? m2.group(1) : cdnMidUrl;
            Log.e(TAG, "CDN参数: aeskey=" + aeskey.substring(0, 8) + "... mid=" + cdnMidUrl.substring(0, 20) + "...");

            // CDN 下载
            try {
                Class<?> r0Clz = cl.loadClass("com.tencent.mm.app.r0");
                Object r0 = XposedHelpers.newInstance(r0Clz);
                XposedHelpers.setObjectField(r0, "b", talker);
                XposedHelpers.setObjectField(r0, "d", XposedHelpers.callMethod(e9, "H0"));
                XposedHelpers.setObjectField(r0, "f", cdnMidUrl);
                XposedHelpers.setObjectField(r0, "i", cdnThumbUrl);
                XposedHelpers.setObjectField(r0, "k", cdnMidUrl);
                XposedHelpers.setObjectField(r0, "l", aeskey);
                XposedHelpers.setObjectField(r0, "m", "");
                XposedHelpers.setObjectField(r0, "n", "jpg");
                XposedHelpers.setObjectField(r0, "g", 0);
                XposedHelpers.setObjectField(r0, "h", 0);
                XposedHelpers.setObjectField(r0, "o", 0);
                XposedHelpers.callStaticMethod(cl.loadClass("com.tencent.mm.app.s0"), "a", r0);
                Log.e(TAG, "CDN下载已触发 → 等待FileOutputStream截获");
            } catch (Throwable e) {
                Log.e(TAG, "CDN触发失败: " + e.getMessage());
            }

            startPolling(thumbPath, msgId, cl);

        } catch (Throwable e) {
            Log.e(TAG, "onImageMsg err: " + e.getMessage());
        }
    }

    // ====== 轮询原图 ======
    private static void startPolling(String thumbPath, long msgId, ClassLoader cl) {
        final String fullPath = thumbPath.replace("/image2/", "/image/").replace("/th_", "/");
        Log.e(TAG, "启动轮询: " + fullPath);
        new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                try { Thread.sleep(2000); } catch (Exception e) {}
                File f = new File(fullPath);
                if (f.exists() && f.length() > 10000) {
                    Log.e(TAG, "轮询命中: " + f.length() + "bytes");
                    triggerQrRaw(msgId, fullPath, cl);
                    break;
                }
            }
        }, "qr-poller").start();
    }

    // ====== 触发 QR 识别 ======
    private static void triggerQrRaw(long msgId, String imgPath, ClassLoader cl) {
        try {
            Class<?> evtClz = cl.loadClass("com.tencent.mm.autogen.events.RecogQBarOfImageFileEvent");
            Object event = XposedHelpers.newInstance(evtClz);
            Object g = XposedHelpers.getObjectField(event, "g");
            XposedHelpers.setLongField(g, "a", msgId);
            XposedHelpers.setObjectField(g, "b", imgPath);
            XposedHelpers.setBooleanField(g, "e", false);
            XposedHelpers.setIntField(g, "f", 0);
            XposedHelpers.setBooleanField(g, "g", false);
            XposedHelpers.setObjectField(g, "h", "");
            XposedHelpers.setIntField(g, "j", 0);
            XposedHelpers.callMethod(event, "e");
            Log.e(TAG, "QR触发: " + imgPath);
        } catch (Throwable e) {
            Log.e(TAG, "QR触发失败: " + e.getMessage());
        }
    }

    // ====== QR 结果 ======
    @SuppressWarnings("unchecked")
    private static void onQrResult(Object event) {
        try {
            Object g = XposedHelpers.getObjectField(event, "g");
            ArrayList<String> texts = (ArrayList<String>) XposedHelpers.getObjectField(g, "b");
            if (texts == null || texts.isEmpty()) return;
            Log.e(TAG, "=== QR结果 " + texts.size() + "个 ===");
            for (String url : texts) {
                Log.e(TAG, "  url=" + url);
                if (doneUrls.contains(url)) continue;
                if (url != null && url.contains("/g/") && url.contains("weixin.qq.com")) {
                    doneUrls.add(url);
                    Log.e(TAG, "★★★ 群邀请 ★★★");
                    joinByPipeline(url, event.getClass().getClassLoader());
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "onQrResult err: " + e.getMessage());
        }
    }

    // ====== Pipeline 加群 ======
    private static void joinByPipeline(String url, ClassLoader cl) {
        try {
            Class<?> f2Clz = cl.loadClass("jd0.f2");
            Object f2 = XposedHelpers.newInstance(f2Clz);
            XposedHelpers.setObjectField(f2, "a", url.trim());
            Class<?> yClz = cl.loadClass("com.tencent.mm.plugin.scanner.y");
            Object y = XposedHelpers.newInstance(yClz);
            Object pipeline = XposedHelpers.callMethod(y, "a", 22, f2);
            XposedHelpers.callMethod(pipeline, "d");
            Log.e(TAG, "Pipeline已启动");
        } catch (Throwable e) {
            Log.e(TAG, "Pipeline失败: " + e.getMessage());
            fallbackJoin(url, cl);
        }
    }

    private static void fallbackJoin(String url, ClassLoader cl) {
        try {
            Class<?> evtClz = cl.loadClass("com.tencent.mm.autogen.events.CreateOrJoinChatroomEvent");
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
