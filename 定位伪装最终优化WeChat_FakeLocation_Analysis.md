# 微信伪造定位逆向分析(Xposed 独立模块版)

- 目标：`com.tencent.mm`
- 结论适用性：基于本次逆向得到的微信版本；混淆类名会随版本变化，但 Hook 方法签名/逻辑在本分析中有 Smali/Java 二次核查。
- 本文档提供：位置架构、Hook 点、精确参数顺序、可编译的独立 Xposed 模块代码、坐标系处理、调用方法、测试/排错方法。

---

## 0. 结论先行

不依赖 LSPilot/BSH，直接用标准 Xposed API 即可伪造微信位置。

三个最有效的 Hook 点：

1. **核心位置分发层**
   - 类：`h51.h`，日志 tag：`MicroMsg.LocationGeo`
   - 方法：`public static c(Lh51/h;ZDDIDDDLandroid/os/Bundle;)V`
   - 作用：WeChat 把定位结果分发给 `h51.c.onGetLocation(...)` 的地方。
   - 伪造方式：Hook 该方法，修改：
     - `param.args[2]` = **纬度 latitude**
     - `param.args[3]` = **经度 longitude**

2. **“附近的人”网络协议层**
   - 类：`ct3.f`，日志 tag：`MicroMsg.NetSceneLbsP`
   - 方法：`<init>(IFFIILjava/lang/String;Ljava/lang/String;)V`
   - 作用：构造 `lbsfind` 请求，实际写入 protobuf：
     - `param.args[1]` -> `pc5/qb4.e`
     - `param.args[2]` -> `pc5/qb4.f`
   - 精确顺序经逆向确认：
     - `args[1] = longitude`
     - `args[2] = latitude`

3. **新版地图/小程序/内部 LocationSDK 封装层**
   - 类：`mf.e`，日志 tag：`MicroMsg.DefaultTencentLocationManager`
   - 方法：`public final d(Lcom/tencent/map/geolocation/sapp/TencentLocation;Z)Lfp1/a;`
   - 作用：把底层 `TencentLocation` 转换为微信内部位置模型 `fp1/a`。
   - `fp1/a` 字段：
     - `a` = latitude
     - `b` = longitude
     - `l` = 坐标系类型，值为 `"wgs84"` 或 `"gcj02"`
   - 伪造方式：Hook 后修改 `result.a` 和 `result.b`。

推荐组合：

- “附近的人”优先 Hook `h51.h.c` 和 `ct3.f.<init>`。
- 要让“地图定位、附近视频/人、小程序位置、部分发布位置”统一生效，加 Hook `mf.e.d`。
- 只修地图蓝点，可 Hook `com.tencent.mm.plugin.location.ui.impl.n2.getMyLocation` / `y3.getMyLocation`。
- 只想伪造“实时位置共享”，见第 10 章，只 Hook `hh3.s0.onGetLocation`。

---

## 1. 微信定位架构

### 1.1 底层腾讯定位 SDK

核心类：

- `com.tencent.map.geolocation.sapp.TencentLocationManager`
- `com.tencent.map.geolocation.sapp.TencentLocation`
- `com.tencent.map.geolocation.sapp.TencentLocationListener`
- `com.tencent.map.geolocation.sapp.TencentLocationRequest`
- `com.tencent.map.geolocation.sapp.TencentLocationUtils`
- `com.tencent.map.geolocation.sapp.databus.DataBusJni`

重要事实：

- `TencentLocation` 是接口，不是普通类。Smali 头部：
  ```smali
  .class public interface abstract Lcom/tencent/map/geolocation/sapp/TencentLocation;
  ```
- `TencentLocationManager.getLastKnownLocation()` 等通过反射 `mProxyClass` / `mProxyObj` 调用真实实现。
- 因此直接 Hook `TencentLocation.getLatitude()` 不一定稳定，真实实现很可能不在主 APK 的普通 DEX 类中。更稳的是 Hook 微信自己的封装层。

---

### 1.2 核心分发层：`h51.h` / LocationGeo

日志 tag：`MicroMsg.LocationGeo`

单例：

- `h51.h.h()` 返回 `Lh51/h;`

主要方法：

- `h51.h.m(c cVar, boolean z)`：日志 `startGcj02 ...`
- `h51.h.o(c cVar, boolean z, boolean z2, boolean z3)`：日志 `startWgs84 ...`
- `h51.h.p(c cVar)`
- `h51.h.f(c cVar)`
- `h51.h.c(...)`：真正的回调分发方法

回调接口：

```java
public interface h51.c {
    boolean onGetLocation(boolean retOk,
                          float arg1,
                          float arg2,
                          int arg3,
                          double arg4,
                          double arg5,
                          double arg6);
}
```

实测调用代码：

```java
dVar.onGetLocation(z, (float) d2, (float) d, i, (float) d3, d4, d5);
```

参数映射非常重要：

| `h51.h.c` 参数 | 含义 |
|---|---|
| `p0` | `h51.h` 实例 |
| `p1` | `boolean retOk` |
| `p2` | `double d` = **latitude** |
| `p3` | `double d2` = **longitude** |
| `p4` | `int i` |
| `p5` | `double d3` |
| `p6` | `double d4` |
| `p7` | `double d5` |
| `p8` | `Bundle bundle` |

回调 `onGetLocation` 中：

| 回调参数 | 含义 |
|---|---|
| 第 1 个 float | **longitude** |
| 第 2 个 float | **latitude** |
| 第 3 个 int | provider/type 相关 |
| 后续 double | speed/accuracy/altitude 等 |


补充架构事实：

- GCJ02 定位监听链：
  - 基类：`h51.t`
  - 实际分发子类：`h51.m extends h51.t`
  - `h51.h.m(...)` 启动时实际使用 `h51.m`
  - `h51.m.b(...)` 会 `post(new h51.l(...))`
  - `h51.l.run()` 调用 `h51.h.c(...)`

- WGS84 定位监听链：
  - 基类：`h51.u`
  - 实际分发子类：`h51.o extends h51.u`
  - `h51.h.o(...)` 启动时实际使用 `h51.o`
  - `h51.o.a(...)` 会 `post(new h51.n(...))`
  - `h51.n.run()` 调用 `h51.h.c(...)`

日志进一步确认经纬度顺序：

```java
Log.i("MicroMsg.LocationGeo",
    "onGetLocation fLongitude: %f fLatitude:%f locType:%d %f:spped",
    Double.valueOf(d2),
    Double.valueOf(d),
    Integer.valueOf(i),
    Double.valueOf(d3));
```

即：

- `d` = latitude
- `d2` = longitude

`h51.c` 的调用面很广，查看其实现类可确认 Hook `h51.h.c` 覆盖面很高，覆盖但不限于：

- `com.tencent.mm.plugin.nearby.ui.f0`
- `com.tencent.mm.plugin.finder.nearby.person.NearbyPersonV1UIC` 相关 `gw2.q`
- `com.tencent.mm.plugin.shake.ui.ShakeReportUI$1`
- `com.tencent.mm.plugin.sns.ui.h5`
- `com.tencent.mm.plugin.card.ui.o`
- `com.tencent.mm.plugin.address.ui.f1`
- `com.tencent.mm.plugin.finder.live.view.u6`
- `com.tencent.mm.plugin.scanner.ui.r0`
- `com.tencent.mm.plugin.webview.stub.z`
- `com.tencent.mm.plugin.appbrand.jsapi.lbs.z`

注意事项：`h51.h.c` 中存在一条内部测试分支：

```java
if (x91/o1.b && bf5/c.a()) {
    onGetLocation(z, (float) x91/o1.z, (float) x91/o1.y, ...);
} else {
    onGetLocation(z, (float) d2, (float) d, ...);
}
```

如果微信开发者选项开启了该内部覆盖，普通改 `d/d2` 可能不生效。文末代码中已加入每次回调前将 `x91/o1.b` 置 `false` 的处理。

---

### 1.3 新版内部管理器：`mf.e` / DefaultTencentLocationManager

日志 tag：`MicroMsg.DefaultTencentLocationManager`

对外接口：`fp1.c`

```java
public interface fp1.c {
    boolean H8(String coordType, fp1.b listener, Bundle options);
    boolean K6(String coordType, fp1.b listener, Bundle options);
    void M5(String coordType, fp1.b listener, Bundle options);
}
```

实现类：`mf.e`

关键方法：

- `mf.e.H8(String, fp1/b, Bundle)`：注册连续定位
- `mf.e.M5(String, fp1/b, Bundle)`：单次定位
- `mf.e.K6(String, fp1/b, Bundle)`：反注册
- `mf.e.b(String, int)`：获取缓存位置
- `mf.e.d(TencentLocation, boolean)`：转换为内部模型 `fp1/a`

`mf.e.d` 逻辑：

```java
public final a d(TencentLocation loc, boolean isWgs84) {
    a result = new a();
    if (isWgs84) {
        result.a = loc.getLatitude();
        result.b = loc.getLongitude();
        result.l = "wgs84";
    } else {
        double[] in = {loc.getLatitude(), loc.getLongitude()};
        double[] out = new double[2];
        TencentLocationUtils.wgs84ToGcj02(in, out);
        result.a = out[0];
        result.b = out[1];
        result.l = "gcj02";
    }
    result.c = "gps".equals(loc.getProvider()) ? "gps" : "network";
    result.d = loc.getSpeed();
    result.e = loc.getAccuracy();
    result.f = loc.getAltitude();
    result.i = loc.getIndoorLocationType();
    result.j = loc.getBearing();
    ...
    return result;
}
```

内部位置模型 `fp1.a`：

```java
public class fp1.a {
    public double a;      // latitude
    public double b;      // longitude
    public String c;      // gps / network
    public double d;      // speed
    public double e;      // accuracy
    public double f;      // altitude
    public String g;      // indoor building id
    public String h;      // indoor building floor
    public int i;         // indoor location type
    public float j;       // bearing
    public double k;      // steps
    public String l;      // wgs84 / gcj02
}
```

---

### 1.4 “附近的人”完整链路

老版附近的人：

- 页面：`com.tencent.mm.plugin.nearby.ui.NearbyFriendsUI`
- 定位回调：`com.tencent.mm.plugin.nearby.ui.f0.onGetLocation(ZFFIDDD)Z`
- 构建请求：
  ```java
  nearbyFriendsUI.g = new f(nearbyFriendsUI.p, f, f2, (int) d2, i, "", "");
  j1.e().g(nearbyFriendsUI.g);
  ```
- `f` 实际是 `ct3.f`，即 `NetSceneLbsP`。

新版 Finder 附近的人：

- 页面：`com.tencent.mm.plugin.finder.nearby.person.NearbyPersonV1UIC`
- 定位回调：`gw2.q.onGetLocation(ZFFIDDD)Z`
- 同样构造 `ct3.f`：
  ```java
  new f(i2, f3, f4, i3, i, "", "");
  j1.e().g(m1Var);
  ```

`ct3.f` 构造请求体：

```smali
iget p1, v0, Lpc5/qb4;->d:I   // opcode
iput p2, v0, Lpc5/qb4;->e:F   // longitude
iput p3, v0, Lpc5/qb4;->f:F   // latitude
iput p4, v0, Lpc5/qb4;->g:I
iput-object p6, v0, Lpc5/qb4;->h:Ljava/lang/String;
iput-object p7, v0, Lpc5/qb4;->i:Ljava/lang/String;
iput p5, v0, Lpc5/qb4;->m:I
```

请求 CGI：

```text
/cgi-bin/micromsg-bin/lbsfind
```

因此“附近的人”也可以直接在协议构造点伪造。

---

## 2. Hook 点与调用方法

### 2.1 主 Hook：`h51.h.c`

#### 为什么选它

它是微信位置结果进入业务层前的总分发点。

NearbyFriendsUI 有一处调用：

```java
h hVar = this.v;
hVar.o(this.C, true, false, false);
```

`h51.h.o` 是 `startWgs84`。也就是说“附近的人”默认拿 WGS84 坐标，然后交给 `h51.h.c` 分发。

#### Hook 参数

Smali 方法签名：

```smali
.method public static c(Lh51/h;ZDDIDDDLandroid/os/Bundle;)V
```

Java/Xposed 视角：

```java
Method m = XposedHelpers.findMethodExact(
    h51hClass,
    "c",
    h51hClass,
    boolean.class,
    double.class, // latitude
    double.class, // longitude
    int.class,
    double.class,
    double.class,
    double.class,
    Bundle.class
);
```

Hook 后伪造：

```java
param.args[2] = fakeLatitude;
param.args[3] = fakeLongitude;
```

注意：`h51.h.c` 是静态方法，但第一个参数是类实例，所以 Xposed 里 `param.args[0]` 仍是 `h51.h` 实例。

#### 坐标系选择

`h51.h` 中观察到：

- `h51.h.o(...)`：`startWgs84`，内部设置 `this.a = false`
- `h51.h.m(...)`：`startGcj02`，内部设置 `this.a = true`

所以可以增强判断：

```java
Object hObj = param.args[0];
boolean gcj02Mode = XposedHelpers.getBooleanField(hObj, "a");

if (gcj02Mode) {
    param.args[2] = fakeGcjLat;
    param.args[3] = fakeGcjLng;
} else {
    param.args[2] = fakeWgsLat;
    param.args[3] = fakeWgsLng;
}
```

如果只看“附近的人”，用 WGS84 即可。

---

### 2.2 协议层 Hook：`ct3.f.<init>`

Smali：

```smali
.method public constructor <init>(IFFIILjava/lang/String;Ljava/lang/String;)V
```

对应 Java：

```java
ct3.f(int opCode,
      float longitude,
      float latitude,
      int precision,
      int arg5,
      String arg6,
      String arg7)
```

Xposed：

```java
XposedHelpers.findAndHookConstructor(
    "ct3.f",
    classLoader,
    int.class,
    float.class,   // longitude
    float.class,   // latitude
    int.class,
    int.class,
    String.class,
    String.class,
    new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.args[1] = (float) fakeLongitude; // p2 -> qb4.e
            param.args[2] = (float) fakeLatitude;  // p3 -> qb4.f
        }
    }
);
```

---

### 2.3 全局封装 Hook：`mf.e.d`

Smali：

```smali
.method public final d(Lcom/tencent/map/geolocation/sapp/TencentLocation;Z)Lfp1/a;
```

Xposed：

```java
Class<?> mfE = XposedHelpers.findClassIfExists("mf.e", classLoader);
Class<?> tencentLocation =
    XposedHelpers.findClassIfExists("com.tencent.map.geolocation.sapp.TencentLocation", classLoader);

Method d = XposedHelpers.findMethodExact(mfE, "d", tencentLocation, boolean.class);
XposedBridge.hookMethod(d, new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Object result = param.getResult();
        if (result == null) return;

        boolean isWgs84 = (Boolean) param.args[1];
        if (isWgs84) {
            XposedHelpers.setDoubleField(result, "a", fakeWgsLat);
            XposedHelpers.setDoubleField(result, "b", fakeWgsLng);
            XposedHelpers.setObjectField(result, "l", "wgs84");
        } else {
            XposedHelpers.setDoubleField(result, "a", fakeGcjLat);
            XposedHelpers.setDoubleField(result, "b", fakeGcjLng);
            XposedHelpers.setObjectField(result, "l", "gcj02");
        }
    }
});
```

这个 Hook 会影响使用 `DefaultTencentLocationManager` 的业务，例如地图定位、部分小程序/Flutter 位置桥接。

---

### 2.4 可选：地图蓝点 Hook

类：

- `com.tencent.mm.plugin.location.ui.impl.n2.getMyLocation`
- `com.tencent.mm.plugin.location.ui.impl.y3.getMyLocation`

作用：向腾讯地图 SDK 返回 `android.location.Location`。

伪造方式：

```java
XposedHelpers.findAndHookMethod(
    "com.tencent.mm.plugin.location.ui.impl.y3",
    classLoader,
    "getMyLocation",
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Location loc = (Location) param.getResult();
            if (loc == null) return;
            loc.setLatitude(fakeWgsLat);
            loc.setLongitude(fakeWgsLng);
        }
    }
);
```

---

## 3. 可直接编译的独立 Xposed 模块代码

依赖：

```gradle
dependencies {
    compileOnly 'de.robv.android.xposed:api:82'
}
```

`AndroidManifest.xml` 中：

```xml
<meta-data
    android:name="xposedmodule"
    android:value="true" />
<meta-data
    android:name="xposedminversion"
    android:value="82" />
<meta-data
    android:name="xposedscope"
    android:value="com.tencent.mm" />
```

入口类：

```java
package com.yourname.wxfakeloc;

import android.location.Location;
import android.os.Bundle;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedInit implements IXposedHookLoadPackage {

    private static final String WECHAT = "com.tencent.mm";

    private static final class Cfg {
        static volatile boolean enabled = true;

        // 用户一般配置 GCJ02 坐标，例如从高德/腾讯地图拿到的坐标
        static volatile double gcjLat = 39.909604;
        static volatile double gcjLng = 116.397228;

        // 由 GCJ02 自动推导出的 WGS84 坐标
        static volatile double wgsLat;
        static volatile double wgsLng;

        static {
            readCfg();
            double[] w = gcj02ToWgs84(gcjLat, gcjLng);
            wgsLat = w[0];
            wgsLng = w[1];
        }

        static void readCfg() {
            File f = new File(Environment.getExternalStorageDirectory(), "wx_fake_location.txt");
            if (!f.exists()) return;

            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] kv = line.split("=", 2);
                    if (kv.length != 2) continue;

                    String k = kv[0].trim();
                    String v = kv[1].trim();

                    if ("enable".equals(k)) {
                        enabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                    } else if ("gcj02_lat".equals(k)) {
                        gcjLat = Double.parseDouble(v);
                    } else if ("gcj02_lng".equals(k)) {
                        gcjLng = Double.parseDouble(v);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[WxLocSpf] read cfg error: " + t);
            } finally {
                if (br != null) {
                    try { br.close(); } catch (Throwable ignore) {}
                }
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!WECHAT.equals(lp.packageName)) return;

        safeHook(() -> hookLocationGeo(lp.classLoader));
        safeHook(() -> hookLbsFindRequest(lp.classLoader));
        safeHook(() -> hookDefaultLocationManager(lp.classLoader));
        safeHook(() -> hookMapBlueDot(lp.classLoader));
    }

    private interface Hook {
        void run() throws Throwable;
    }

    private static void safeHook(Hook h) {
        try {
            h.run();
        } catch (Throwable t) {
            XposedBridge.log("[WxLocSpf] hook error: " + t);
        }
    }

    private static void hookLocationGeo(ClassLoader cl) {
        Class<?> h51h = XposedHelpers.findClassIfExists("h51.h", cl);
        if (h51h == null) {
            XposedBridge.log("[WxLocSpf] h51.h not found");
            return;
        }

        // 微信开发者选项/内部测试配置可能会让 h51.h.c 强制使用 x91/o1.y/z。
        // 这里每次回调前把 x91/o1.b 置 false，确保它走 else 分支，使用我们的伪造值。
        final Class<?> x91o1 = XposedHelpers.findClassIfExists("x91.o1", cl);

        Method c = XposedHelpers.findMethodExact(
                h51h,
                "c",
                h51h,
                boolean.class,
                double.class, // p2 = latitude
                double.class, // p3 = longitude
                int.class,
                double.class,
                double.class,
                double.class,
                Bundle.class
        );

        XposedBridge.hookMethod(c, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Cfg.enabled) return;

                if (x91o1 != null) {
                    try {
                        XposedHelpers.setStaticBooleanField(x91o1, "b", false);
                    } catch (Throwable ignore) {
                    }
                }

                Object hObj = param.args[0];
                boolean gcj02Mode;
                try {
                    gcj02Mode = XposedHelpers.getBooleanField(hObj, "a");
                } catch (Throwable t) {
                    gcj02Mode = false;
                }

                if (gcj02Mode) {
                    param.args[2] = Cfg.gcjLat;
                    param.args[3] = Cfg.gcjLng;
                } else {
                    param.args[2] = Cfg.wgsLat;
                    param.args[3] = Cfg.wgsLng;
                }
            }
        });
    }

    private static void hookLbsFindRequest(ClassLoader cl) {
        Class<?> ct3f = XposedHelpers.findClassIfExists("ct3.f", cl);
        if (ct3f == null) {
            XposedBridge.log("[WxLocSpf] ct3.f not found");
            return;
        }

        XposedHelpers.findAndHookConstructor(
                ct3f,
                int.class,
                float.class, // longitude
                float.class, // latitude
                int.class,
                int.class,
                String.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Cfg.enabled) return;

                        param.args[1] = (float) Cfg.wgsLng; // p2 -> qb4.e
                        param.args[2] = (float) Cfg.wgsLat; // p3 -> qb4.f
                    }
                }
        );
    }

    private static void hookDefaultLocationManager(ClassLoader cl) {
        Class<?> mfE = XposedHelpers.findClassIfExists("mf.e", cl);
        Class<?> tencentLocation =
                XposedHelpers.findClassIfExists("com.tencent.map.geolocation.sapp.TencentLocation", cl);

        if (mfE == null || tencentLocation == null) {
            XposedBridge.log("[WxLocSpf] mf.e or TencentLocation not found");
            return;
        }

        Method d = XposedHelpers.findMethodExact(mfE, "d", tencentLocation, boolean.class);

        XposedBridge.hookMethod(d, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Cfg.enabled) return;

                Object result = param.getResult();
                if (result == null) return;

                boolean isWgs84 = (Boolean) param.args[1];
                if (isWgs84) {
                    XposedHelpers.setDoubleField(result, "a", Cfg.wgsLat);
                    XposedHelpers.setDoubleField(result, "b", Cfg.wgsLng);
                    XposedHelpers.setObjectField(result, "l", "wgs84");
                } else {
                    XposedHelpers.setDoubleField(result, "a", Cfg.gcjLat);
                    XposedHelpers.setDoubleField(result, "b", Cfg.gcjLng);
                    XposedHelpers.setObjectField(result, "l", "gcj02");
                }
            }
        });
    }

    private static void hookMapBlueDot(ClassLoader cl) {
        hookGetMyLocation(cl, "com.tencent.mm.plugin.location.ui.impl.n2");
        hookGetMyLocation(cl, "com.tencent.mm.plugin.location.ui.impl.y3");
    }

    private static void hookGetMyLocation(ClassLoader cl, String className) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) return;

        XposedHelpers.findAndHookMethod(c, "getMyLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Cfg.enabled) return;

                Location loc = (Location) param.getResult();
                if (loc == null) return;

                loc.setLatitude(Cfg.wgsLat);
                loc.setLongitude(Cfg.wgsLng);
            }
        });
    }

    // ---------- 坐标转换：GCJ02 -> WGS84 ----------

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
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }

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
}
```

配置文件 `/sdcard/wx_fake_location.txt`：

```ini
enable=1
gcj02_lat=39.909604
gcj02_lng=116.397228
```

---

## 4. “如何调用，用什么方法？”

- 不需要启动 Activity，也不需要调用微信内部私有 API，直接通过 Xposed Hook 拦截即可。
- 用户操作路径：
  1. 安装本模块。
  2. 在 LSPosed 中勾选微信，作用域建议只选微信。
  3. 修改 `/sdcard/wx_fake_location.txt`。
  4. 强制停止微信。
  5. 进入“发现 -> 附近”、“摇一摇”、“Finder 附近”测试。

- 内部调用链：
  ```text
  用户进入附近的人
      -> NearbyFriendsUI / NearbyPersonV1UIC 请求定位
      -> h51.h.o / h51.h.m 启动定位
      -> TencentLocation 回调
      -> h51.h.c 分发
      -> h51.c.onGetLocation
      -> ct3.f 构造 lbsfind 请求
      -> 服务器返回附近列表
  ```

- Hook 优先级：
  1. `h51.h.c`：改业务层拿到的经纬度。
  2. `ct3.f.<init>`：改真正发送给服务器的经纬度。
  3. `mf.e.d`：改新版内部管理器返回的位置模型。
  4. `n2/y3.getMyLocation`：改地图蓝点。

---

## 5. 测试与 Logcat 观察

关键日志 tag：

```text
MicroMsg.LocationGeo
MicroMsg.SLocationListener
MicroMsg.SLocationListenerWgs84
MicroMsg.NearbyFriend
MicroMsg.NetSceneLbsP
MicroMsg.DefaultTencentLocationManager
MicroMsg.ViewMapUI
MicroMsg.MMPoiMapUI
```

过滤命令：

```bash
adb logcat | grep -E "LocationGeo|NearbyPersonUIC|NearbyFriend|NetSceneLbsP|DefaultTencentLocationManager"
```

伪造成功后应看到 `Latitude/Longitude` 或相关日志中的值接近你配置的坐标。

---

## 6. 二次核查记录

已完成的核查：

1. `h51.h.c` 存在，签名确认为：
   ```smali
   .method public static c(Lh51/h;ZDDIDDDLandroid/os/Bundle;)V
   ```

2. `h51.h.c` 的回调调用点确认为：
   ```java
   onGetLocation(z, (float)d2, (float)d, i, (float)d3, d4, d5);
   ```
   因此：
   - `p2 = d = latitude`
   - `p3 = d2 = longitude`

3. `ct3.f.<init>` 存在，签名确认为：
   ```smali
   .method public constructor <init>(IFFIILjava/lang/String;Ljava/lang/String;)V
   ```

4. `ct3.f.<init>` 中参数写入 protobuf：
   ```smali
   iput p2, v0, Lpc5/qb4;->e:F
   iput p3, v0, Lpc5/qb4;->f:F
   ```

5. `NearbyFriendsUI` / `NearbyPersonV1UIC` / `wb2.i` 均有 `onGetLocation` -> `ct3.f` 链路。

6. `mf.e.d` 存在，签名确认为：
   ```smali
   .method public final d(Lcom/tencent/map/geolocation/sapp/TencentLocation;Z)Lfp1/a;
   ```

7. `mf.e.d` 内部字段写入确认为：
   ```smali
   iget-wide v1, ... getLatitude
   iput-wide v1, v0, Lfp1/a;->a:D
   ... getLongitude
   iput-wide v1, v0, Lfp1/a;->b:D
   ```

8. `fp1.a` 字段：
   ```text
   a = latitude
   b = longitude
   l = wgs84 / gcj02
   ```

9. 已有 Xposed 独立能力，不依赖 LSPilot BSH。

---

## 7. 失败排查

### Hook 后附近的人位置没变

按顺序检查：

1. Logcat 是否出现模块日志：
   ```text
   [WxLocSpf]
   ```
2. 是否 Hook 到了 `h51.h.c`：
   - 打开附近的人，观察日志。
3. 未 Hook 到：
   - 微信版本更新导致混淆类名变化。
   - 用 DexKit / LSPilot 重新定位：
     - 字符串锚点：`startWgs84`、`startGcj02`、`MicroMsg.LocationGeo`、`/cgi-bin/micromsg-bin/lbsfind`
4. Hook 成功但服务器返回异常：
   - 交换经纬度：
     ```java
     param.args[2] = fakeLng;
     param.args[3] = fakeLat;
     ```
   - 或检查坐标系：
     - Nearby 默认 WGS84。
     - 如果页面/地图用 GCJ02，则需要传 GCJ02。

### 地图蓝点不变化

- 需要 Hook `mf.e.d` 或 `n2/y3.getMyLocation`。
- 只 Hook 协议层只会改服务器，不会改本地地图显示。

### 小程序/Flutter 位置不变化

- Hook `mf.e.d`。
- 注意 `fp1.a` 的 `l` 字段决定坐标系，不要只改 `a/b` 不改 `l`。

---

## 8. 注意事项

- 混淆类名如 `h51.h`、`mf.e`、`ct3.f`、`fp1.a` 会随版本变化，但 Hook 逻辑稳定。
- `TencentLocation` 是接口，真实实现可能不在主 APK DEX 中，直接 Hook 接口方法不可靠。
- 如果只伪造“附近的人”，建议只启用：
  - `h51.h.c`
  - `ct3.f.<init>`

如果想让整个 App 的位置统一伪造，建议启用全部 Hook，并正确维护 WGS84 / GCJ02 两套坐标。

---

## 9. 深度审查补充与纠正

本次深度审查在原分析基础上补充/纠正了以下问题。

### 9.1 纠正：`h51.t` / `h51.u` 不是最终分发者

原文档把 `h51.t` / `h51.u` 称为监听分发者，容易误导。准确结构是：

- `h51.t`：GCJ02 监听基类
  - 实际使用子类：`h51.m extends h51.t`
  - `h51.m.b(...)` 中调用：
    ```java
    new q3(Looper.getMainLooper()).post(new l(this, i3, str3, z, d, d2, i, d3, d4, d5));
    ```
  - `h51.l.run()` 调用：
    ```java
    h51.h.c(mVar.b, this.f, this.g, this.h, this.i, this.m, this.n, this.o, bundle);
    ```

- `h51.u`：WGS84 监听基类
  - 实际使用子类：`h51.o extends h51.u`
  - `h51.o.a(...)` 中调用：
    ```java
    new q3(Looper.getMainLooper()).postDelayed(
        new n(this, i3, str3, z, d, d2, i, d3, d4, d5), 200L);
    ```
  - `h51.n.run()` 调用：
    ```java
    h51.h.c(oVar.b, this.f, this.g, this.h, this.i, this.m, this.n, this.o, bundle);
    ```

因此真正稳定的 Hook 点仍然是：

```text
h51.h.c
```

而不是上层 `h51.t` / `h51.u`。

### 9.2 再次确认经纬度参数

来自 `h51.m.b` / `h51.o.a` 的日志：

```text
onGetLocation fLongitude: %f fLatitude:%f
```

日志打印顺序是：

```java
Double.valueOf(d2), Double.valueOf(d)
```

所以再次确认：

| 层 | 参数 | 值 |
|---|---|---|
| `h51.h.c` | `args[2]` / `p2` | latitude |
| `h51.h.c` | `args[3]` / `p3` | longitude |
| `h51.c.onGetLocation` | 第 1 个 float | longitude |
| `h51.c.onGetLocation` | 第 2 个 float | latitude |
| `ct3.f.<init>` | `args[1]` / `p2` | longitude |
| `ct3.f.<init>` | `args[2]` / `p3` | latitude |

这意味着原文档结论正确，且现在有了更直接的日志级证据。

### 9.3 补漏：内部测试覆盖分支

`h51.h.c` 内部存在：

```java
if (x91/o1.b && bf5/c.a()) {
    onGetLocation(z, (float) x91/o1.z, (float) x91/o1.y, ...);
} else {
    onGetLocation(z, (float) d2, (float) d, ...);
}
```

如果 `x91/o1.b` 为真，即使你改了 `d/d2`，业务层也可能拿到 `x91/o1.z` 和 `x91/o1.y`。

已补入防御代码：

```java
final Class<?> x91o1 = XposedHelpers.findClassIfExists("x91.o1", cl);

if (x91o1 != null) {
    try {
        XposedHelpers.setStaticBooleanField(x91o1, "b", false);
    } catch (Throwable ignore) {}
}
```

### 9.4 补漏：多进程作用域

微信有多个进程，包括但不限于：

- 主进程 `com.tencent.mm`
- `com.tencent.mm:sandbox`
- `com.tencent.mm:push`
- `com.tencent.mm:appbrand0` 到 `com.tencent.mm:appbrand4`
- 其他插件/工具进程

如果 Xposed 模块只 Hook 了主进程，定位 SDK/回调/业务 UI 不在同一进程时可能漏掉。

建议：

- LSPosed / Xposed 作用域直接选择 `com.tencent.mm`。
- 不要只勾“推荐进程”或只 Hook 主进程。
- 模块代码本身使用 `handleLoadPackage` 处理所有微信进程。
- 若使用自定义作用域，请把定位相关进程、附近的人所在进程都加入。

### 9.5 补漏：`h51.c` 覆盖面很广

通过接口查询可以看到大量 `h51.c` 实现，覆盖但不限于：

- 附近的人：`com.tencent.mm.plugin.nearby.ui.f0`
- Finder 附近的人：`gw2.q`
- 摇一摇：`com.tencent.mm.plugin.shake.ui.ShakeReportUI$1`
- 朋友圈/位置：`com.tencent.mm.plugin.sns.ui.h5`
- 地址/名片：`com.tencent.mm.plugin.address.ui.f1`、`com.tencent.mm.plugin.card.ui.o`
- Finder 直播：`com.tencent.mm.plugin.finder.live.view.u6`
- 扫码：`com.tencent.mm.plugin.scanner.ui.r0`
- WebView：`com.tencent.mm.plugin.webview.stub.z`
- 小程序：`com.tencent.mm.plugin.appbrand.jsapi.lbs.z`
- 城市选择：`com.tencent.mm.ui.tools.g7`、`com.tencent.mm.plugin.nearlife.ui.j`

这些都会经过 `h51.h.c`，所以 Hook 一个点即可覆盖大量场景。

### 9.6 补漏：缓存定位路径也会经过 `h51.h.c`

`h51.h.m` / `h51.h.o` 中命中缓存时，会调用：

```java
tVar.b(true, this.k, this.l, this.m, this.n, this.o, this.p, this.q, this.r, this.s, 0, "cache");
```

或：

```java
tencentLocationListener.a(true, this.k, this.l, this.m, this.n, this.o, this.p, this.q, this.r, this.s, 0, "cache");
```

最终仍通过 `h51.l` / `h51.n` 调用 `h51.h.c`。

结论：即使 WeChat 使用缓存位置，`h51.h.c` Hook 依然有效。

### 9.7 补漏：Finder Feed / Live 定位链路

`com.tencent.mm.plugin.finder.assist.g4` / `FinderLbsManager` 使用日志：

```text
Finder.FinderLbsManager
```

其 `syncWaitLbs` 是通过事件等待位置结果，不直接由 `h51.h.c` 单独表征。Finder Feed/Live 场景应确保启用：

```text
mf.e.d
```

因为 Finder/小程序部分链路会走 `DefaultTencentLocationManager`。

### 9.8 单独确认的边界

以下链路不是本次伪造“附近的人”的必需项，但如果想让全 App 所有坐标都统一，需要额外验证：

- `com.tencent.pigeon.biz.BizUserLocationInfo`
- `com.tencent.pigeon.finder.POIUserLocationInfo`
- `com.tencent.pigeon.mm_foundation.MMUserLocationInfo`

这些 Pigeon/Flutter 数据类本身不产生 GPS 定位，通常只是把已经获取到的 `latitude/longitude` 传给 Flutter 层。只要上游 `h51.h.c` 或 `mf.e.d` 被伪造，它们一般也会拿到伪造值；但不同 Flutter 模块可能有额外缓存，需要单独观察日志。

### 9.9 最终推荐 Hook 组合

如果目标是“全 App 统一伪造”，推荐按以下顺序全部启用：

1. `h51.h.c`  
   改业务层拿到的经纬度，覆盖 Nearby、Shake、SNS、WebView、小程序位置、城市/地址等大量场景。

2. `ct3.f.<init>`  
   改 `/cgi-bin/micromsg-bin/lbsfind` 真实请求，保证附近的人服务器收到伪造坐标。

3. `mf.e.d`  
   改 `DefaultTencentLocationManager` 输出模型，覆盖 Finder、地图、小程序、部分 Flutter 桥接。

4. `com.tencent.mm.plugin.location.ui.impl.n2.getMyLocation` / `y3.getMyLocation`  
   修正地图蓝点。

5. 可选：`x91/o1.b = false`  
   关闭微信内部测试坐标覆盖分支。

### 9.10 最终结论

深度审查后，原核心结论仍然成立：

```text
h51.h.c        -> args[2]=lat, args[3]=lng
ct3.f.<init>   -> args[1]=lng, args[2]=lat
mf.e.d         -> result.a=lat, result.b=lng
```

文档中已纠正 `h51.t/h51.u` 表述，补充了以下关键信息：

- `h51.m` / `h51.o` 子类链路
- 日志级经纬度顺序证据
- `x91/o1` 测试覆盖分支
- 缓存路径仍走 `h51.h.c`
- 多进程作用域要求
- Finder Feed/Live 依赖 `mf.e.d`


---

## 10. 只对“共享实时位置”生效，不影响“发送位置”

### 10.1 为什么要单独做

原文档中的 `h51.h.c` 覆盖面很广，会影响：

- 附近的人
- 摇一摇
- 朋友圈位置
- 小程序位置
- 发送位置选择器
- 实时位置共享

如果你只想改“共享实时位置”，不能优先 Hook `h51.h.c`，而应该只 Hook 实时共享专属监听者。

### 10.2 实时共享位置的关键链路

实时共享位置相关类：

- 新版共享 UI：`com.tencent.mm.plugin.location.ui.impl.w0`
  - 日志 tag：`MicroMsg.RealTimeLocationShareUIV2`
- 旧版共享 UI：`com.tencent.mm.plugin.location.ui.impl.j1`
- 共享刷新/网络管理器：`hh3.w0`
  - 日志 tag：`MicorMsg.TrackRefreshManager`
- 共享位置监听者：`hh3.s0`
  - 由 `hh3.w0.<init>` 创建：
    ```java
    this.R = new hh3.s0(this);
    ```
- `hh3.w0.q(...)` 启动定位：
  ```java
  h51.h.h().m(this.R, true);
  ```
  即走 `startGcj02`，所以拿到的是 GCJ02 坐标。

### 10.3 精准 Hook 点：`hh3.s0.onGetLocation`

方法签名：

```java
public boolean onGetLocation(boolean z,
                             float f,
                             float f2,
                             int i,
                             double d,
                             double d2,
                             double d3)
```

内部逻辑：

```java
if (z) {
    nh5Var.d = f2;
    nh5Var.e = f;
}
```

其中：

- `f` = longitude
- `f2` = latitude

也就是：

| 参数 | 含义 |
|---|---|
| `args[0]` | `boolean z` 定位是否成功 |
| `args[1]` | `float f` = **longitude** |
| `args[2]` | `float f2` = **latitude** |
| `args[3]` | `int i` 类型 |
| `args[4]` 之后 | speed/accuracy/altitude 等 |

因此只做共享位置伪造时，应 Hook：

```text
hh3.s0.onGetLocation
```

### 10.4 只对共享位置生效的代码

推荐使用 GCJ02 坐标，因为 `hh3.w0` 是 `startGcj02`。

```java
private static void hookOnlyLocationShare(ClassLoader cl) {
    Class<?> hh3s0 = XposedHelpers.findClassIfExists("hh3.s0", cl);
    if (hh3s0 == null) {
        XposedBridge.log("[WxLocSpf] hh3.s0 not found");
        return;
    }

    XposedHelpers.findAndHookMethod(
            hh3s0,
            "onGetLocation",
            boolean.class,
            float.class,  // longitude
            float.class,  // latitude
            int.class,
            double.class,
            double.class,
            double.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Cfg.enabled) return;

                    // 如果定位失败，可选强制成功，保证共享位置依然能发出去
                    param.args[0] = Boolean.TRUE;

                    param.args[1] = (float) Cfg.gcjLng; // f  = longitude
                    param.args[2] = (float) Cfg.gcjLat; // f2 = latitude
                }
            }
    );
}
```

注意：

- 该 Hook 只影响 `hh3.w0` 的 `hh3.s0` 回调。
- 发送位置选择器 `y2` 不使用 `hh3.s0`，所以不会受影响。
- 微信“发送位置”的定位回调一般是 `y2.o2` 类型的 `h51.d`，不是本 Hook 的目标。

### 10.5 验证是否只影响共享位置

1. 关闭 `h51.h.c`、`ct3.f.<init>` 等全局 Hook，只保留 `hh3.s0.onGetLocation`。
2. 进入聊天 -> 位置 -> 共享实时位置，观察共享坐标。
3. 回到聊天 -> 位置 -> 发送位置，选择当前位置，观察目标坐标。
4. 结论：
   - 共享实时位置坐标 = 伪造坐标
   - 发送位置坐标 = 真实坐标

---

## 11. 调用微信原生位置选择器

### 11.1 原生选择器入口

关键 Activity：

- `com.tencent.mm.plugin.location.ui.RedirectUI`
  - 负责根据 `map_view_type` 分发。
- `com.tencent.mm.plugin.location_soso.SoSoProxyUI`
  - 真正承载地图 UI。
  - 根据 `intent_map_key` 创建不同控制器。

`SoSoProxyUI.onCreate` 中：

```java
if (intExtra == 2) {
    y2Var = new y2(this, stringExtra); // 发送位置/POI 选择
} else if (intExtra == 4) {
    y2Var = new z2(this);              // 查看地图
} else if (intExtra == 5) {
    y2Var = bj ? new w0(this) : new j1(this); // 共享实时位置
}
```

所以原生位置选择器使用：

```text
intent_map_key = 2
```

### 11.2 启动参数

如果直接启动 `SoSoProxyUI`：

```java
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
```

如果希望通过微信官方 `RedirectUI` 流程启动：

```java
Intent intent = new Intent();
intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.location.ui.RedirectUI");
intent.putExtra("map_view_type", 0);
intent.putExtra("map_talker_name", "weixin");
intent.putExtra("map_sender_name", "");
intent.putExtra("view_type_key", 1);
intent.putExtra("key_get_location_type", 0);
intent.putExtra("KPickPoiLat", 0.0d);
intent.putExtra("KPickPoiLong", 0.0d);
```

`map_view_type` 常用值：

| 值 | 含义 |
|---|---|
| `0` | 发送位置 / POI 选择 |
| `3` | 发送位置相关变体 |
| `8` | 发送位置相关变体 |
| `6` | 进入 / 恢复实时共享位置 |
| `1/2/7/9/10...` | 查看地图等 |

### 11.3 只调用选择器不接收结果

在微信进程内，直接使用微信进程中的 `Context`：

```java
public static void launchWeChatPoiPicker(Context context, String talker) {
    Intent intent = new Intent();
    intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.location_soso.SoSoProxyUI");

    intent.putExtra("intent_map_key", 2);
    intent.putExtra("map_talker_name", talker);
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

    if (!(context instanceof android.app.Activity)) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    context.startActivity(intent);
}
```

重点：

- 该 Activity 很可能不是 exported。
- 外部模块 App 直接启动会抛 `SecurityException`。
- Xposed 模块代码运行在微信进程内时，由于就是微信自身调用，可以启动自身非 exported Activity。

### 11.4 调用选择器并拿到用户所选位置

直接启动 `SoSoProxyUI` 并获取结果：

```java
private static void launchPickerForResult(Activity activity, String talker) {
    Intent intent = new Intent();
    intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.location_soso.SoSoProxyUI");

    intent.putExtra("intent_map_key", 2);
    intent.putExtra("map_talker_name", talker);
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

    activity.startActivityForResult(intent, 3001);
}
```

结果数据在：

```text
KLocationIntent
```

对应类型：

```text
com.tencent.mm.pluginsdk.location.LocationIntent
```

关键字段：

```text
d = latitude
e = longitude
p = poiid
g / h = 地址相关字段
```

在 Xposed 中读取：

```java
Object locationIntent = intent.getParcelableExtra("KLocationIntent");
if (locationIntent != null) {
    double lat = XposedHelpers.getDoubleField(locationIntent, "d");
    double lng = XposedHelpers.getDoubleField(locationIntent, "e");
    XposedBridge.log("[WxLocSpf] picked lat=" + lat + " lng=" + lng);
}
```

### 11.5 从独立模块 UI 触发微信选择器

如果你自己的模块有外部 App UI，推荐这样桥接：

1. 在 Xposed 模块里 Hook 微信主界面：

```java
XposedHelpers.findAndHookMethod(
        "com.tencent.mm.ui.LauncherUI",
        classLoader,
        "onCreate",
        Bundle.class,
        new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;

                if (PickerBridge.consumeStartRequest()) {
                    launchPickerForResult(activity, PickerBridge.consumeTalker());
                }
            }
        }
);
```

2. 同时 Hook 结果回调：

```java
XposedHelpers.findAndHookMethod(
        "com.tencent.mm.ui.LauncherUI",
        classLoader,
        "onActivityResult",
        int.class,
        int.class,
        Intent.class,
        new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int requestCode = (int) param.args[0];
                int resultCode = (int) param.args[1];
                Intent data = (Intent) param.args[2];

                if (requestCode == 3001 && resultCode == Activity.RESULT_OK && data != null) {
                    Object loc = data.getParcelableExtra("KLocationIntent");
                    if (loc != null) {
                        double lat = XposedHelpers.getDoubleField(loc, "d");
                        double lng = XposedHelpers.getDoubleField(loc, "e");
                        PickerBridge.setResult(lat, lng);
                    }
                }
            }
        }
);
```

这样你的外部模块只负责发命令给 Xposed 侧，真正启动 Activity 和接收结果都发生在微信进程内，避免 exported/权限问题。

---

## 12. 深度审查后的最终 Hook 清单

### 12.1 如果只想伪造实时共享位置

只启用：

```text
hh3.s0.onGetLocation
```

参数：

```text
args[1] = GCJ02 longitude
args[2] = GCJ02 latitude
```

不要启用：

```text
h51.h.c
ct3.f.<init>
mf.e.d
```

否则会影响附近的人、发送位置、小程序等。

### 12.2 如果你想在看图界面调用微信原生选择器

使用：

```text
com.tencent.mm.plugin.location_soso.SoSoProxyUI
intent_map_key = 2
```

接收结果使用：

```text
KLocationIntent
com.tencent.mm.pluginsdk.location.LocationIntent
d = latitude
e = longitude
```

### 12.3 推荐组合

如果想让模块同时支持：

- 只伪造实时共享位置
- 调用原生选择器选点

推荐逻辑：

```text
用户点击“选择位置”
    -> 启动 SoSoProxyUI, intent_map_key=2
    -> 用户选点
    -> 读取 KLocationIntent.d/.e
    -> 写入配置

进入实时共享位置
    -> hh3.s0.onGetLocation
    -> 强制 args[0]=true
    -> args[1]=配置 lng
    -> args[2]=配置 lat
```

这样：

- 原生选择器只负责“选点”。
- 伪造只作用在共享实时位置。
- 发送位置、附近的人、地图默认不动。
