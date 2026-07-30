package com.leshao.v3;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.*;

/**
 * 自动扫码进群 v4 — 纯后台 CDN 下载版
 * 
 * 链路:
 *   type=3 图片消息 → XML解析(aeskey/cdnmidimgurl)
 *   → com.tencent.mm.app.r0 构造 CDN 请求
 *   → com.tencent.mm.app.s0.a(r0) 触发 CDN 下载
 *   → FileOutputStream hook 截获原图写入 → RecogQBarOfImageFileEvent QR识别
 *   → RecogQBarOfImageFileResultEvent 拿结果 → URL含/g/ → 群邀请
 *   → y.a() Pipeline → batchgeturlinfo CGI → 加群
 */
public class AutoJoinGroup {

    private static final String TAG = "AutoJoinGroup";
    private static final Set<Long> doneMsgIds = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> doneUrls = Collections.synchronizedSet(new HashSet<>());
    private static boolean sEnabled = true;
    private static boolean sHooked = false;

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        LogWriter.log(TAG, "setEnabled: " + enabled);
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (sHooked) return;
        sHooked = true;

        ClassLoader cl = lp.classLoader;
        try {
            Class<?> iEventClz = cl.loadClass("com.tencent.mm.sdk.event.IEvent");
            Class<?> resultClz = cl.loadClass(
                "com.tencent.mm.autogen.events.RecogQBarOfImageFileResultEvent");

            Method eMethod = iEventClz.getDeclaredMethod("e");
            final Class<?> evtReqClz = cl.loadClass(
                "com.tencent.mm.autogen.events.RecogQBarOfImageFileEvent");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (resultClz.isInstance(p.thisObject)) {
                        LogWriter.log(TAG, ">>> IEvent.e 回调: QR结果事件");
                        onQrResult(p.thisObject);
                    }
                    if (evtReqClz.isInstance(p.thisObject)) {
                        LogWriter.log(TAG, ">>> IEvent.e 发布: QR请求事件");
                    }
                }
            });

            XposedBridge.hookAllConstructors(FileOutputStream.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String path = null;
                        if (param.args.length > 0) {
                            if (param.args[0] instanceof File)
                                path = ((File) param.args[0]).getAbsolutePath();
                            else if (param.args[0] instanceof String)
                                path = (String) param.args[0];
                        }
                        if (path == null || !path.contains("/MicroMsg/")
                            || !path.contains("/image/") || path.contains("/image2/"))
                            return;

                        final String filePath = path;
                        LogWriter.log(TAG, "★原图写入: " + filePath);
                        new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> {
                                File f = new File(filePath);
                                if (f.exists() && f.length() > 10000) {
                                    LogWriter.log(TAG, "★原图就绪→触发QR size=" + f.length());
                                    triggerQrRaw(0, filePath, cl);
                                }
                            }, 500);
                    } catch (Throwable e) {}
                }
            });

            try {
                Class<?> r0Clz = cl.loadClass("com.tencent.mm.app.r0");
                for (java.lang.reflect.Constructor<?> c : r0Clz.getDeclaredConstructors()) {
                    StringBuilder sb = new StringBuilder("r0构造: ");
                    for (Class<?> p : c.getParameterTypes()) {
                        sb.append(p.getSimpleName()).append(" ");
                    }
                    LogWriter.log(TAG, sb.toString());
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "r0调试失败: " + e.getMessage());
            }

            try {
                Class<?> f2Clz = cl.loadClass("jd0.f2");
                for (java.lang.reflect.Constructor<?> c : f2Clz.getDeclaredConstructors()) {
                    StringBuilder sb = new StringBuilder("jd0.f2构造: ");
                    for (Class<?> p : c.getParameterTypes()) sb.append(p.getSimpleName()).append(" ");
                    LogWriter.log(TAG, sb.toString());
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "jd0.f2调试失败: " + e.getMessage());
            }

            try {
                Class<?> reClz = cl.loadClass(
                    "com.tencent.mm.autogen.events.RecogQBarOfImageFileEvent");
                for (java.lang.reflect.Constructor<?> c : reClz.getDeclaredConstructors()) {
                    StringBuilder sb = new StringBuilder("RecogQBar构造: ");
                    for (Class<?> p : c.getParameterTypes()) sb.append(p.getSimpleName()).append(" ");
                    LogWriter.log(TAG, sb.toString());
                }
                Object testEvent = XposedHelpers.newInstance(reClz);
                Object gObj = XposedHelpers.getObjectField(testEvent, "g");
                LogWriter.log(TAG, "RecogQBarInnerClz: " + gObj.getClass().getName());
                for (java.lang.reflect.Field f : gObj.getClass().getDeclaredFields()) {
                    LogWriter.log(TAG, "  g." + f.getName() + " : " + f.getType().getSimpleName());
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "RecogQBar调试失败: " + e.getMessage());
            }

            try {
                Class<?> rrClz = cl.loadClass(
                    "com.tencent.mm.autogen.events.RecogQBarOfImageFileResultEvent");
                Object testEvent = XposedHelpers.newInstance(rrClz);
                Object gObj = XposedHelpers.getObjectField(testEvent, "g");
                LogWriter.log(TAG, "RecogQBarResult inner=" + gObj.getClass().getName());
                for (java.lang.reflect.Field f : gObj.getClass().getDeclaredFields()) {
                    LogWriter.log(TAG, "  rg." + f.getName() + " : " + f.getType().getSimpleName());
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "RecogQBarResult调试失败: " + e.getMessage());
            }

            LogWriter.log(TAG, "QR监听已挂载 + FileOutputStream hook OK");

        } catch (Throwable e) {
            LogWriter.log(TAG, "hook失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static void onImageMsg(Object e9) {
        if (!sEnabled) return;
        try {
            long msgId = (Long) XposedHelpers.callMethod(e9, "H0");
            if (doneMsgIds.contains(msgId)) return;

            String y0 = (String) XposedHelpers.callMethod(e9, "y0");
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String xml = (String) XposedHelpers.callMethod(e9, "j");
            
            LogWriter.log(TAG, "图片: msgId=" + msgId + " talker=" + talker);
            doneMsgIds.add(msgId);

            ClassLoader cl = e9.getClass().getClassLoader();

            Class<?> gcClz = cl.loadClass("com.tencent.mm.vfs.ImageGCFileSystem");
            String thumbPath = (String) XposedHelpers.callStaticMethod(
                gcClz, "c", y0, null, null, false);

            File thumbFile = new File(thumbPath);
            LogWriter.log(TAG, "缩略图: " + thumbPath + " exists=" + thumbFile.exists()
                + " size=" + (thumbFile.exists() ? thumbFile.length() : 0));

            Matcher m = Pattern.compile(
                "aeskey=\"([^\"]+)\".*cdnmidimgurl=\"([^\"]+)\""
            ).matcher(xml);

            if (!m.find()) {
                LogWriter.log(TAG, "XML解析失败, 无CDN参数, 兜底缩略图");
                if (thumbFile.exists() && thumbFile.length() > 5000) {
                    triggerQrRaw(msgId, thumbPath, cl);
                }
                startPolling(thumbPath, msgId, cl);
                return;
            }

            String aeskey = m.group(1);
            String cdnMidUrl = m.group(2);

            Matcher m2 = Pattern.compile("cdnthumburl=\"([^\"]+)\"").matcher(xml);
            String cdnThumbUrl = m2.find() ? m2.group(1) : cdnMidUrl;

            LogWriter.log(TAG, "CDN参数: aeskey=" + aeskey.substring(0, 8) + "..."
                + " mid=" + cdnMidUrl.substring(0, 20) + "...");

            try {
                if (thumbFile.exists()) {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(thumbPath);
                    LogWriter.log(TAG, "BitmapFactory: " + (bmp != null
                        ? bmp.getWidth() + "x" + bmp.getHeight() : "null"));
                    if (bmp != null && bmp.getWidth() >= 50) {
                        int w = bmp.getWidth();
                        int h = bmp.getHeight();
                        android.graphics.Bitmap scaled = android.graphics.Bitmap
                            .createScaledBitmap(bmp, w * 2, h * 2, true);
                        bmp.recycle();
                        File tmp = new File(ContextManager.getAppContext()
                            .getCacheDir(), "qr_" + msgId + ".png");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                        fos.close();
                        scaled.recycle();

                        LogWriter.log(TAG, "BitmapFactory: " + w + "x" + h
                            + " → " + (w*2) + "x" + (h*2) + " PNG=" + tmp.length() + "bytes, 触发QR");
                        triggerQrRaw(msgId, tmp.getAbsolutePath(), cl);
                        return;
                    }
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "BitmapFactory err: " + e.getMessage());
            }

            startPolling(thumbPath, msgId, cl);

        } catch (Throwable e) {
            LogWriter.log(TAG, "onImageMsg err: " + e.getMessage());
        }
    }

    private static void startPolling(String thumbPath, long msgId, ClassLoader cl) {
        final String fullPath = thumbPath
            .replace("/image2/", "/image/")
            .replace("/th_", "/");
        LogWriter.log(TAG, "启动轮询: " + fullPath);

        new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                try { Thread.sleep(2000); } catch (Exception e) {}
                File f = new File(fullPath);
                if (f.exists() && f.length() > 10000) {
                    LogWriter.log(TAG, "轮询命中: " + f.length() + "bytes");
                    triggerQrRaw(msgId, fullPath, cl);
                    break;
                }
                if (i % 5 == 4) LogWriter.log(TAG, "轮询中... " + ((i + 1) * 2) + "s");
            }
        }, "qr-poller").start();
    }

    private static void triggerQrRaw(long msgId, String imgPath, ClassLoader cl) {
        try {
            Class<?> evtClz = cl.loadClass(
                "com.tencent.mm.autogen.events.RecogQBarOfImageFileEvent");
            Object event = XposedHelpers.newInstance(evtClz);
            Object g = XposedHelpers.getObjectField(event, "g");

            Field f = g.getClass().getDeclaredField("a");
            f.setAccessible(true);
            f.setLong(g, msgId);
            XposedHelpers.setObjectField(g, "b", imgPath);
            XposedHelpers.setBooleanField(g, "e", false);
            XposedHelpers.setIntField(g, "f", 0);
            XposedHelpers.setBooleanField(g, "g", false);
            XposedHelpers.setObjectField(g, "h", "");
            XposedHelpers.setIntField(g, "j", 0);

            XposedHelpers.callMethod(event, "e");
            LogWriter.log(TAG, "QR触发: msgId=" + msgId + " path=" + imgPath);

        } catch (Throwable e) {
            LogWriter.log(TAG, "QR触发失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void onQrResult(Object event) {
        try {
            LogWriter.log(TAG, ">>> onQrResult CALLED");
            Object g = XposedHelpers.getObjectField(event, "g");
            if (g == null) {
                LogWriter.log(TAG, ">>> onQrResult g=NULL");
                return;
            }
            ArrayList<String> texts = (ArrayList<String>) XposedHelpers.getObjectField(g, "b");
            LogWriter.log(TAG, ">>> onQrResult texts=" + (texts == null ? "null" : texts.size() + "个"));
            if (texts == null || texts.isEmpty()) return;

            LogWriter.log(TAG, "=== QR结果 " + texts.size() + "个 ===");
            for (String url : texts) {
                LogWriter.log(TAG, "  url=" + url);
                if (doneUrls.contains(url)) continue;
                if (isGroup(url)) {
                    doneUrls.add(url);
                    LogWriter.log(TAG, "  ★★★ 群邀请 ★★★");
                    joinByPipeline(url, event.getClass().getClassLoader());
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "onQrResult err: " + e.getMessage());
        }
    }

    private static boolean isGroup(String url) {
        return url != null && url.contains("/g/") && url.contains("weixin.qq.com");
    }

    private static void joinByPipeline(String url, ClassLoader cl) {
        try {
            LogWriter.log(TAG, "→ Pipeline加群: " + url);
            Class<?> f2Clz = cl.loadClass("jd0.f2");
            Object f2 = XposedHelpers.newInstance(f2Clz, url.trim());

            Class<?> yClz = cl.loadClass("com.tencent.mm.plugin.scanner.y");
            Object y = XposedHelpers.newInstance(yClz);
            Object pipeline = XposedHelpers.callMethod(y, "a", 22, f2);
            XposedHelpers.callMethod(pipeline, "d");
            LogWriter.log(TAG, "Pipeline已启动");
        } catch (Throwable e) {
            LogWriter.log(TAG, "Pipeline失败: " + e.getMessage());
            fallbackJoin(url, cl);
        }
    }

    private static void fallbackJoin(String url, ClassLoader cl) {
        try {
            Class<?> evtClz = cl.loadClass(
                "com.tencent.mm.autogen.events.CreateOrJoinChatroomEvent");
            Object event = XposedHelpers.newInstance(evtClz);
            Object g = XposedHelpers.getObjectField(event, "g");
            XposedHelpers.setIntField(g, "a", 2);
            XposedHelpers.setObjectField(g, "b", new String[]{url});
            XposedHelpers.setObjectField(g, "c", new String[]{"", "", "", "", "", "", ""});
            XposedHelpers.callMethod(event, "e");
            LogWriter.log(TAG, "回退事件已发");
        } catch (Throwable e) {
            LogWriter.log(TAG, "回退失败: " + e.getMessage());
        }
    }
}
