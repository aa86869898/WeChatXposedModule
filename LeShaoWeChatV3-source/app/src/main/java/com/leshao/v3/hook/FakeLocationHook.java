package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 定位伪装（仅共享实时位置）——依据《定位伪装最终优化WeChat_FakeLocation_Analysis.md》第 10~12 章。
 *
 * <p>v3.0.139 重写要点：</p>
 * <ul>
 *   <li><b>只保留一个 Hook 点</b>：{@code hh3.s0.onGetLocation(boolean,float,float,int,double,double,double)}
 *       —— 实时位置共享专属监听者（由 {@code hh3.w0.<init>} 创建，走 {@code startGcj02}，坐标 GCJ02）。
 *       不再 Hook {@code h51.h.c} / {@code ct3.f.<init>} / {@code mf.e.d} / {@code n2.getMyLocation}，
 *       避免影响附近的人、发送位置、小程序等。</li>
 *   <li>参数：{@code args[1]=经度}、{@code args[2]=纬度}（GCJ02）；同时强制 {@code args[0]=true}
 *       保证定位失败时共享位置依然能发出去。</li>
 *   <li>坐标来源：微信原生位置选择器（{@code com.tencent.mm.plugin.location_soso.SoSoProxyUI}，
 *       {@code intent_map_key=2}，结果在 {@code KLocationIntent} 的 {@code d=纬度 / e=经度}），
 *       由模块 UI 发起 {@code startActivityForResult(requestCode=3001)}，
 *       本类 Hook {@code Activity.onActivityResult} 接收并写入配置。</li>
 * </ul>
 */
public final class FakeLocationHook {

    private static final String TAG = "FakeLocation";

    public static final String K_ENABLED = "ls_fake_loc_enabled";
    public static final String K_LAT = "ls_fake_loc_lat";
    public static final String K_LNG = "ls_fake_loc_lng";

    /** 与模块 UI 约定的选择器请求码。 */
    public static final int REQ_PICK_LOCATION = 3001;

    /** 北京天安门附近默认坐标（GCJ-02，与微信 startGcj02 链路一致）。 */
    private static final double DEF_LAT = 39.909604;
    private static final double DEF_LNG = 116.397228;

    private static volatile ClassLoader sCl;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    private FakeLocationHook() {}

    // ================================================================
    // 配置读写
    // ================================================================

    private static SharedPreferences prefs() {
        return ContextManager.getPrefs();
    }

    public static boolean isEnabled() {
        SharedPreferences sp = prefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putBoolean(K_ENABLED, on).apply();
        LogWriter.log(TAG, "setEnabled " + on);
    }

    public static double getLat() {
        return parseDouble(prefs() != null ? prefs().getString(K_LAT, null) : null, DEF_LAT);
    }

    public static double getLng() {
        return parseDouble(prefs() != null ? prefs().getString(K_LNG, null) : null, DEF_LNG);
    }

    /** 保存微信原生位置选择器返回的坐标（微信选择器返回 GCJ02，直接存储）。 */
    public static void setLocation(double lat, double lng) {
        SharedPreferences sp = prefs();
        if (sp == null) return;
        sp.edit()
                .putString(K_LAT, String.valueOf(lat))
                .putString(K_LNG, String.valueOf(lng))
                .apply();
        LogWriter.log(TAG, "setLocation lat=" + lat + " lng=" + lng);
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; }
    }

    // ================================================================
    // Hook 安装
    // ================================================================

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        safe("LocationShare", () -> hookOnlyLocationShare(cl));
        safe("PickerResult", () -> hookActivityResult(cl));
    }

    private interface Hook {
        void run() throws Throwable;
    }

    private static void safe(String name, Hook h) {
        try {
            h.run();
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook " + name + " err: " + t);
        }
    }

    /**
     * 唯一 Hook 点：实时位置共享监听者 {@code hh3.s0.onGetLocation}。
     *
     * <p>依据文档第 10.4 节：</p>
     * <pre>
     * public boolean onGetLocation(boolean z, float f, float f2, int i,
     *                              double d, double d2, double d3)
     * // f  = longitude（经度）
     * // f2 = latitude （纬度）
     * </pre>
     * {@code hh3.w0} 走 {@code startGcj02}，因此坐标必须是 GCJ02。
     */
    private static void hookOnlyLocationShare(ClassLoader cl) throws Throwable {
        Class<?> cls = loadClass(cl, "hh3.s0");
        if (cls == null) {
            LogWriter.log(TAG, "[WARN] hh3.s0 not found, location-share hook off");
            return;
        }
        Method m = findMethod(cls, "onGetLocation",
                boolean.class, float.class, float.class, int.class,
                double.class, double.class, double.class);
        if (m == null) {
            LogWriter.log(TAG, "hh3.s0.onGetLocation signature not found");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isEnabled()) return;
                try {
                    param.args[0] = Boolean.TRUE;                       // 强制定位成功
                    param.args[1] = (float) getLng();                   // f  = longitude
                    param.args[2] = (float) getLat();                   // f2 = latitude
                    LogWriter.log(TAG, "share-loc -> lng=" + param.args[1]
                            + " lat=" + param.args[2]);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "hh3.s0.onGetLocation err: " + t);
                }
            }
        });
        LogWriter.log(TAG, "hooked hh3.s0.onGetLocation (location-share only)");
    }

    /**
     * 接收微信原生位置选择器结果（requestCode=3001）。
     *
     * <p>模块 UI（AlertDialog 宿主于微信 Activity）通过宿主 Activity
     * {@code startActivityForResult(intent, 3001)} 启动 SoSoProxyUI；结果回到该 Activity，
     * 这里全局 Hook {@code Activity.onActivityResult} 捕获并写入配置。</p>
     */
    private static void hookActivityResult(final ClassLoader cl) throws Throwable {
        XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    if (requestCode != REQ_PICK_LOCATION) return;
                    if (resultCode != Activity.RESULT_OK || data == null) {
                        LogWriter.log(TAG, "location picker canceled");
                        return;
                    }
                    Object loc = data.getParcelableExtra("KLocationIntent");
                    if (loc == null) {
                        LogWriter.log(TAG, "KLocationIntent null");
                        return;
                    }
                    double lat = readDoubleField(loc, "d"); // d = latitude
                    double lng = readDoubleField(loc, "e"); // e = longitude
                    setLocation(lat, lng);
                    final Activity act = (Activity) param.thisObject;
                    toast(act, "已选位置：纬度 " + String.format("%.6f", lat)
                            + "，经度 " + String.format("%.6f", lng));
                } catch (Throwable t) {
                    LogWriter.log(TAG, "onActivityResult picker err: " + t);
                }
            }
        });
        LogWriter.log(TAG, "hooked Activity.onActivityResult (REQ=3001)");
    }

    /**
     * 启动微信原生位置选择器（由模块 UI 调用）。
     *
     * <p>依据文档第 11.3/11.4 节：{@code SoSoProxyUI} + {@code intent_map_key=2} 即发送位置/POI 选择。
     * 模块运行在微信进程内，可启动微信自身非 exported Activity。</p>
     */
    public static void launchPicker(Activity host) {
        if (host == null) return;
        try {
            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.location_soso.SoSoProxyUI");
            intent.putExtra("intent_map_key", 2);
            intent.putExtra("map_talker_name", "weixin");
            intent.putExtra("map_view_type", 0);
            intent.putExtra("map_indoor_support", 0);
            intent.putExtra("kShowshare", true);
            intent.putExtra("kimg_path", "");
            intent.putExtra("kPoi_url", "");
            intent.putExtra("kPoiid", "");
            intent.putExtra("KIsFromPoiList", false);
            intent.putExtra("kwebmap_slat", 0.0d);
            intent.putExtra("kwebmap_lng", 0.0d);
            intent.putExtra("kwebmap_scale", 15);
            intent.putExtra("kPoiName", "");
            host.startActivityForResult(intent, REQ_PICK_LOCATION);
            LogWriter.log(TAG, "launched SoSoProxyUI picker");
        } catch (Throwable t) {
            LogWriter.log(TAG, "launchPicker err: " + t);
            toast(host, "无法打开位置选择器：" + t.getMessage());
        }
    }

    // ================================================================
    // 反射工具
    // ================================================================

    private static Class<?> loadClass(ClassLoader cl, String name) {
        if (name == null || cl == null) return null;
        for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
            try { return l.loadClass(name); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** api-82 的 XposedHelpers 无 getDoubleField，改用反射读 double 字段。 */
    private static double readDoubleField(Object obj, String name) {
        if (obj == null) return 0d;
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.getDouble(obj);
            } catch (Throwable ignored) {}
        }
        return 0d;
    }

    private static void toast(final Context ctx, final String msg) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        sH.post(() -> {
            try { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) {}
        });
    }
}