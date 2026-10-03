package com.leshao.v3.hook;

import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 定位伪装（伪造微信定位）——依据《伪装定位WeChat_FakeLocation_Analysis.md》。
 *
 * <p>四个 Hook 点（由弱到强、互相补充）：</p>
 * <ol>
 *   <li>{@code h51.h.c(h51.h,boolean,double,double,int,double,double,double,Bundle)}
 *       —— 位置结果总分发点：{@code args[2]=纬度}、{@code args[3]=经度}。</li>
 *   <li>{@code ct3.f.<init>(int,float,float,int,int,String,String)}
 *       —— 附近的人 lbsfind 请求：{@code args[1]=经度}、{@code args[2]=纬度}。</li>
 *   <li>{@code mf.e.d(TencentLocation,boolean)} —— 新版内部位置模型 {@code fp1.a}：
 *       {@code result.a=纬度}、{@code result.b=经度}、{@code result.l=坐标系}。</li>
 *   <li>{@code com.tencent.mm.plugin.location.ui.impl.n2/y3.getMyLocation()}
 *       —— 地图蓝点 {@code android.location.Location}。</li>
 * </ol>
 *
 * <p>配置持久化于模块 prefs（key 见下方常量）。用户输入的经纬度按所选坐标系解释，
 * 另一套坐标由 GCJ-02/WGS-84 互转推导。</p>
 */
public final class FakeLocationHook {

    private static final String TAG = "FakeLocation";

    public static final String K_ENABLED = "ls_fake_loc_enabled";
    public static final String K_LAT = "ls_fake_loc_lat";
    public static final String K_LNG = "ls_fake_loc_lng";
    /** 坐标系：gcj02 / wgs84 */
    public static final String K_COORD = "ls_fake_loc_coord";

    /** 北京天安门附近默认坐标（GCJ-02）。 */
    private static final double DEF_LAT = 39.909604;
    private static final double DEF_LNG = 116.397228;

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

    /** 返回 "gcj02" 或 "wgs84"。 */
    public static String getCoord() {
        SharedPreferences sp = prefs();
        String c = sp != null ? sp.getString(K_COORD, "gcj02") : "gcj02";
        return "wgs84".equalsIgnoreCase(c) ? "wgs84" : "gcj02";
    }

    public static void setConfig(String lat, String lng, String coord) {
        SharedPreferences sp = prefs();
        if (sp == null) return;
        sp.edit()
                .putString(K_LAT, lat)
                .putString(K_LNG, lng)
                .putString(K_COORD, "wgs84".equalsIgnoreCase(coord) ? "wgs84" : "gcj02")
                .apply();
        LogWriter.log(TAG, "setConfig lat=" + lat + " lng=" + lng + " coord=" + coord);
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; }
    }

    // ================================================================
    // 坐标推导
    // ================================================================

    private static final class Fix {
        double wgsLat, wgsLng, gcjLat, gcjLng;
    }

    private static Fix currentFix() {
        Fix f = new Fix();
        double lat = getLat();
        double lng = getLng();
        if ("wgs84".equalsIgnoreCase(getCoord())) {
            f.wgsLat = lat;
            f.wgsLng = lng;
            double[] g = wgs84ToGcj02(lat, lng);
            f.gcjLat = g[0];
            f.gcjLng = g[1];
        } else {
            f.gcjLat = lat;
            f.gcjLng = lng;
            double[] w = gcj02ToWgs84(lat, lng);
            f.wgsLat = w[0];
            f.wgsLng = w[1];
        }
        return f;
    }

    // ================================================================
    // Hook 安装
    // ================================================================

    public static void hook(final ClassLoader cl) {
        safe("LocationGeo", () -> hookLocationGeo(cl));
        safe("LbsFind", () -> hookLbsFind(cl));
        safe("DefaultTencentLocationManager", () -> hookDefaultLocationManager(cl));
        safe("MapBlueDot", () -> hookMapBlueDot(cl));
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

    /** Hook 1：位置总分发层 h51.h.c。 */
    private static void hookLocationGeo(ClassLoader cl) throws Throwable {
        final Class<?> cls = resolve(cl, "h51.h", "MicroMsg.LocationGeo", "startWgs84");
        if (cls == null) {
            LogWriter.log(TAG, "h51.h not found, location geo hook off");
            return;
        }
        final Class<?> x91o1 = resolve(cl, "x91.o1", (String) null);

        Method m = findMethod(cls, "c",
                cls, boolean.class, double.class, double.class, int.class,
                double.class, double.class, double.class, Bundle.class);
        if (m == null) {
            LogWriter.log(TAG, "h51.h.c signature not found");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isEnabled()) return;
                // 关闭微信内部测试坐标覆盖分支（x91/o1.b 为真时会绕过我们修改的 d/d2）
                if (x91o1 != null) {
                    try { XposedHelpers.setStaticBooleanField(x91o1, "b", false); } catch (Throwable ignored) {}
                }
                Fix f = currentFix();
                boolean gcj02 = false;
                try { gcj02 = XposedHelpers.getBooleanField(param.args[0], "a"); } catch (Throwable ignored) {}
                if (gcj02) {
                    param.args[2] = f.gcjLat;
                    param.args[3] = f.gcjLng;
                } else {
                    param.args[2] = f.wgsLat;
                    param.args[3] = f.wgsLng;
                }
            }
        });
        LogWriter.log(TAG, "hooked h51.h.c");
    }

    /** Hook 2：附近的人请求层 ct3.f.<init>。 */
    private static void hookLbsFind(ClassLoader cl) throws Throwable {
        final Class<?> cls = resolve(cl, "ct3.f", "/cgi-bin/micromsg-bin/lbsfind");
        if (cls == null) {
            LogWriter.log(TAG, "ct3.f not found, lbsfind hook off");
            return;
        }
        Constructor<?> ctor = findCtor(cls,
                int.class, float.class, float.class, int.class, int.class, String.class, String.class);
        if (ctor == null) {
            LogWriter.log(TAG, "ct3.f.<init> signature not found");
            return;
        }
        XposedBridge.hookMethod(ctor, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isEnabled()) return;
                Fix f = currentFix();
                param.args[1] = (float) f.wgsLng; // p2 -> qb4.e
                param.args[2] = (float) f.wgsLat; // p3 -> qb4.f
            }
        });
        LogWriter.log(TAG, "hooked ct3.f.<init>");
    }

    /** Hook 3：新版内部位置模型 mf.e.d。 */
    private static void hookDefaultLocationManager(ClassLoader cl) throws Throwable {
        final Class<?> cls = resolve(cl, "mf.e", "MicroMsg.DefaultTencentLocationManager");
        final Class<?> locCls = loadClass(cl, "com.tencent.map.geolocation.sapp.TencentLocation");
        if (cls == null || locCls == null) {
            LogWriter.log(TAG, "mf.e or TencentLocation not found, DefaultLocationManager hook off");
            return;
        }
        Method m = findMethod(cls, "d", locCls, boolean.class);
        if (m == null) {
            LogWriter.log(TAG, "mf.e.d signature not found");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isEnabled()) return;
                Object result = param.getResult();
                if (result == null) return;
                boolean isWgs84 = false;
                try { isWgs84 = (Boolean) param.args[1]; } catch (Throwable ignored) {}
                Fix f = currentFix();
                try {
                    if (isWgs84) {
                        setDoubleField(result, "a", f.wgsLat);
                        setDoubleField(result, "b", f.wgsLng);
                        XposedHelpers.setObjectField(result, "l", "wgs84");
                    } else {
                        setDoubleField(result, "a", f.gcjLat);
                        setDoubleField(result, "b", f.gcjLng);
                        XposedHelpers.setObjectField(result, "l", "gcj02");
                    }
                } catch (Throwable ignored) {}
            }
        });
        LogWriter.log(TAG, "hooked mf.e.d");
    }

    /** Hook 4：地图蓝点 getMyLocation。 */
    private static void hookMapBlueDot(ClassLoader cl) throws Throwable {
        hookGetMyLocation(cl, "com.tencent.mm.plugin.location.ui.impl.n2");
        hookGetMyLocation(cl, "com.tencent.mm.plugin.location.ui.impl.y3");
    }

    private static void hookGetMyLocation(ClassLoader cl, String className) {
        try {
            Class<?> cls = loadClass(cl, className);
            if (cls == null) return;
            Method m = findMethod(cls, "getMyLocation");
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isEnabled()) return;
                    Object r = param.getResult();
                    if (!(r instanceof Location)) return;
                    Location loc = (Location) r;
                    Fix f = currentFix();
                    loc.setLatitude(f.wgsLat);
                    loc.setLongitude(f.wgsLng);
                }
            });
            LogWriter.log(TAG, "hooked " + className + ".getMyLocation");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookGetMyLocation " + className + " err: " + t);
        }
    }

    // ================================================================
    // 反射工具
    // ================================================================

    private static Class<?> resolve(ClassLoader cl, String literal, String... anchors) {
        Class<?> c = loadClass(cl, literal);
        if (c != null) return c;
        if (anchors != null) {
            for (String a : anchors) {
                if (a == null) continue;
                try {
                    List<String> cands = DexKitHelper.findClassesByString(cl, a);
                    if (cands != null) {
                        for (String cn : cands) {
                            Class<?> cc = loadClass(cl, cn);
                            if (cc != null) {
                                LogWriter.log(TAG, "resolved " + literal + " -> " + cn + " via '" + a + "'");
                                return cc;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Class<?> loadClass(ClassLoader cl, String name) {
        if (name == null) return null;
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

    private static Constructor<?> findCtor(Class<?> c, Class<?>... params) {
        if (c == null) return null;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            Class<?>[] pts = ctor.getParameterTypes();
            if (pts.length != params.length) continue;
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                if (!pts[i].equals(params[i])) { ok = false; break; }
            }
            if (ok) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        return null;
    }

    /** api-82 的 XposedHelpers 无 setDoubleField，改用反射写 double 字段。 */
    private static void setDoubleField(Object obj, String name, double value) {
        if (obj == null) return;
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.setDouble(obj, value);
                return;
            } catch (Throwable ignored) {}
        }
    }

    // ================================================================
    // GCJ-02 / WGS-84 互转
    // ================================================================

    private static final double PI = Math.PI;
    private static final double AXIS = 6378245.0;
    private static final double OFFSET = 0.00669342162296594323;

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
                + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x
                + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    public static double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - OFFSET * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((AXIS * (1 - OFFSET)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat - dLat, lon - dLon};
    }

    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - OFFSET * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((AXIS * (1 - OFFSET)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }
}
