# 微信 8.0.78 (3180) — THUMBNAIL_DIRPATH / 缩略图路径 / 图片下载 API 逆向报告

> **v2 — 已按"独立 Kotlin Xposed 模块"要求修订**
>
> 目标 APK：微信 8.0.78 (3180)
> 分析工具：LSPilot DexKit / jadx / baksmali
> 配套代码：`WeChatThumbnailPathResolver/WeChatThumb.kt`（独立 Kotlin Xposed 模块，不依赖 LSPilot）
> 旧 BSH 版插件已废弃（其 `hookMethodBefore/findClass/getObjectField/log` 均为 LSPilot 全局函数，独立模块不可用）

---

## 0. TL;DR

| 问题 | 结论 |
|---|---|
| `THUMBNAIL_DIRPATH` 常量定义在哪？ | **不存在**。硬编码字符串字面量，散落 13 类 / 26 方法 |
| 谁把 `THUMBNAIL_DIRPATH://th_xxx` 转真实路径？ | `wb0.b.tj()` → `wb0.b.oj()` → `com.tencent.mm.sdk.platformtools.k1.a()` |
| 缩略图真实目录 | `/data/user/0/com.tencent.mm/MicroMsg/<32hex>/image2/<md5[0:2]>/<md5[2:4]>/th_<md5>` |
| `v61.m1` 是什么？ | **VoiceInfoStorage（语音存储）**，与图片无关 |
| `ex0.k0` 是什么？ | MsgInfo 按 talker+svrId 查询，**不是** ImgInfoService |
| 主动下载 API | `ja0.g.K7()` + `ja0.g.J7()`（suspend）；非 suspend 直达见 §5.4 |

---

## 1. 清单 A：`THUMBNAIL_DIRPATH` 常量定义

### 1.1 结论：没有常量，只有字符串字面量

`search_strings("THUMBNAIL_DIRPATH")` = 39 条 = **13 类 + 26 方法，全部是使用点**。
无 `public static final String THUMBNAIL_DIRPATH` 字段（DEX 中字段名不是字符串常量，且该类没有此字段）。

`THUMBNAIL_DIRPATH://` 是**协议前缀 / 占位符**，只存在于 `ImgInfo2.thumbImgPath` 列：

```
THUMBNAIL_DIRPATH://th_3f2a1b8c9d0e4f5a6b7c8d9e0f1a2b3c
                        ^^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                        |   32位小写 hex（MD5）
                        固定前缀
```

### 1.2 MD5 算法（三处一致，已交叉验证）

```java
// l51.l0.D4(...)  L5-6
String str3 = "SERVERID://" + j;              // j = msgSvrId
String g = k.g(str3.getBytes());

// l51.l0.X1(...)  L5
String g = k.g(("SERVERID://" + j).getBytes());

// l51.m0.a(...)  L87-93
StringBuilder sb = new StringBuilder("SERVERID://");
sb.append(gVar.getLong(gVar.d + 1));
byte[] bytes = sb2.getBytes(kc6.c.a);
String g = pk.k.g(bytes);

// 写入 DB
b0Var.F("THUMBNAIL_DIRPATH://th_" + g);
```

即 `hash = md5("SERVERID://" + msgSvrId)`。
`k.g()` 是标准 MD5 小写 hex，**独立 Kotlin 模块用 `java.security.MessageDigest` 完全等价**（输入为 ASCII）。

> ⚠️ `kz1.a0.G()`（CardCompatService 名片缩略图）是特例：用 `l0.L1()` → `N1("")` → `k.g((str + currentTimeMillis).getBytes())`，**不是** SERVERID 派生。

### 1.3 前缀常量全集

| 前缀 | 长度 | 用途 | 生成处 |
|---|---|---|---|
| `THUMBNAIL_DIRPATH://th_` | 20+3 | 普通图片缩略图 | `l51.l0.D4` / `X1` / `l51.m0.a` |
| `THUMBNAIL_APPMSG_DIR://msgth_` | 29+9 | App 消息缩略图 | `l51.l0.z2` |
| `SERVERID://` | 11 | 大图 bigImgPath | `l51.l0.D4` / `X1` |
| `THUMBNAIL://` | 12 | 旧版内存态 | `wb0.b.tj` / `l51.l0.O3` |

---

## 2. 清单 B：`THUMBNAIL_DIRPATH://` → 真实路径

### 2.1 链路

```
ImgInfoStorage / C2CImgPathFeatureService
   └─ wb0.b.tj(String uri, boolean mkDir)        剥前缀
       └─ wb0.b.oj(String name, String prefix, String suffix, boolean mkDir)   选根 + 组路径
           └─ com.tencent.mm.sdk.platformtools.k1.a(root, dir, prefix, name, ext, 1, mkDir)
               └─ com.tencent.mm.sdk.platformtools.k1.d(dir, prefix, name, ext, 1, mkDir)
                   └─ com.tencent.mm.sdk.platformtools.k1.b(name) = name[0:2]+"/"+name[2:4]+"/"
```

### 2.2 `wb0.b.tj(String str, boolean z)` — 剥壳

```java
public final String tj(String str, boolean z) {
    if (str == null || str.length() == 0) return null;
    int length = str.length() - 1;  int i = 0;  boolean z2 = false;
    while (i <= length) { /* Kotlin trim 内联 */ }
    String obj = str.subSequence(i, length + 1).toString();

    if (j0.B(obj, "THUMBNAIL://", false, 2, (Object) null)) {          // 12
        String substring = obj.substring(12);
        try { obj = b1.ej().J2(Long.valueOf(Long.parseLong(substring))).g; }
        catch (NumberFormatException e) { return null; }
    } else if (j0.z(obj, "THUMBNAIL_DIRPATH://", false)) {             // 20
        obj = obj.substring(20);
        if (j0.z(obj, "th_", false)) {                                 // 3
            String substring2 = obj.substring(3);
            return oj(substring2, "th_", "", z);                       // ★ 只传纯 hash
        }
    }
    return oj(obj, "", "", z);
}
```

### 2.3 `wb0.b.oj(...)` — 组真实路径（★ 双调用约定，务必注意）

```java
public final String oj(String str, String str2, String str3, boolean z) {
    if (str7 == null || str.length() == 0) return "";

    if (j0.z(str7, "SERVERID://", false)) {                            // 11
        str4 = b1.ej().D3(Long.parseLong(str7.substring(11))).e;        // bigImgPath
    } else { str4 = ""; }

    String b = a.b();                        // = pe3.a.b() = t7.b("image2") + "/"

    if (j0.z(str7, "THUMBNAIL_DIRPATH://", false)) {                    // ★ 完整串入口
        str7 = str7.substring(23);                                     // 20 + "th_"3
        str5 = "th_";
    } else if (j0.z(str7, "THUMBNAIL_APPMSG_DIR://", false)) {          // 29
        str7 = str7.substring(29);                                     // 20 + "msgth_"9
        b = b1.ej().p2();                                              // openapi/thumb/
        str5 = "msgth_";
    } else {
        str5 = str2;                                                   // tj() 传来的 "th_"
    }

    str6 = (str5 != null && o.c(str5, "msgth_")) ? b1.ej().p2() : b;

    String a = k1.a(a.a(), str6, str5, y8.J0(str4) ? str7 : str4, str3, 1, z);
    return a == null ? "" : a;
}
```

> 📌 **两种调用约定**
> - 从 `tj()` 进：`str` = **纯 32 位 hash**，`str2` = `"th_"`，`str3` = `""`
> - 从 `nj()` / `l51.m0.a()` 进：`str` = **完整** `THUMBNAIL_DIRPATH://th_<hash>`，走 `substring(23)`

### 2.4 `wb0.b.nj(String)` — 无 mkDir 变体

```java
public final String nj(String str) {
    String str2 = "";
    if (str == null || str.length() == 0) return "";
    String b = a.b();
    String str3 = "th_";
    if (j0.z(str, "SERVERID://", false)) {
        str2 = b1.ej().D3(Long.parseLong(str.substring(11))).e;
    } else if (j0.z(str, "THUMBNAIL_DIRPATH://", false)) {
        str2 = str.substring(23);
    } else if (j0.z(str, "THUMBNAIL_APPMSG_DIR://", false)) {
        str2 = str.substring(29);
        b = b1.ej().p2();  str3 = "msgth_";
    }
    return k1.a(a.a(), b, str3, y8.J0(str2) ? str : str2, "", 1, true);
}
```

### 2.5 `com.tencent.mm.sdk.platformtools.k1` — 路径生成器

```java
public static String a(String str, String str2, String str3, String str4, String str5, int i, boolean z) {
    String str6 = str + str3 + str4 + str5;             // 旧路径 image/th_<hash>
    String d = d(str2, str3, str4, str5, i, z);         // 新路径 image2/xx/yy/th_<hash>
    if (y8.J0(str6) || y8.J0(d)) return null;
    c8 a = c8.a(d);  c8 a2 = c8.a(str6);                /* normalize */
    d3 m = e3.a.m(a, null);
    if (m.a() && m.a.E(m.b)) return d;                  // 新路径存在
    d3 m2 = e3.a.m(a2, null);
    if (m2.a() && m2.a.E(m2.b)) n1.a(str6, d, false);   // 迁移 copy
    return d;
}

public static String d(String str, String str2, String str3, String str4, int i, boolean z) {
    if (!y8.J0(str) && str.endsWith("/")) {
        String b = (i == 1) ? b(str3) : (i == 2) ? (y8.J0(str3) ? null : b(k.g(str3.getBytes()))) : "";
        if (y8.J0(b)) return null;
        String str5 = str + b;
        if (z) z6.u(str5);                              // mkdirs
        return str5 + (str2 == null ? "" : str2) + str3 + (str4 == null ? "" : str4);
    }
    return null;
}

public static String b(String str) {
    if (!y8.J0(str) && str.length() > 4)                // ★ 必须 > 4
        return str.substring(0, 2) + "/" + str.substring(2, 4) + "/";
    return null;
}
```

### 2.6 VFS 根目录来源

```java
public static String a() { return t7.b("image")  + "/"; }         // 旧根
public static String b() { return t7.b("image2") + "/"; }         // ★ 新根
public static String c() { return t7.b("bizCoverImg")  + "/"; }
public static String d() { return t7.b("bizPreviewImg") + "/"; }

// com.tencent.mm.vfs.t7.b   —— 无尾斜杠
public static String b(String str) { return h7.a.c(str); }

// com.tencent.mm.vfs.h7.c
public final String c(String str) {
    k1 k1Var = (k1) c.get(str);
    if (k1Var != null) {
        if (k1Var.b == null) k1Var.b = k1Var.a.a(e3.a.d());
        return k1Var.b;
    }
    return null;
}

// l51.l0.p2() —— App 消息缩略图目录（不是普通图片！）
public String p2() { return p.cj() + "thumb/"; }
// bv3.p.cj() = j1.x().d() + "openapi/"
```

### 2.7 `${data}` / `${account}` 实参

```java
// com.tencent.mm.vfs.f3.<init>  L156-180
r5.e.put("data",      context.getCacheDir().getParent());   // /data/user/0/com.tencent.mm
r5.e.put("dataCache", context.getCacheDir().getPath());     // /data/user/0/com.tencent.mm/cache

// f3.g(Context) 另加：
//   extData  = externalCacheDir.getParent()  → /storage/emulated/0/Android/data/com.tencent.mm
//   extCache = externalCacheDir.getPath()
//   storage  = Environment.getExternalStorageDirectory().getPath() → /sdcard
```

`account` = 登录后写入的 **32 位小写 hex**（MicroMsg 目录名）。

### 2.8 `image2` VFS → 物理路径推导

```java
// com.tencent.mm.vfs.e3.<clinit>  :cond_541
String root = b8.c[loc] + storage.b;      // b = dirName
// b8.c[1] = "${data}/MicroMsg/${account}/"    b8.c[0] = "${data}/MicroMsg/"

// :cond_610
if (v7 != null) h7.c.put(storage.a /* fsName */, new k1(v7 /* 首个 mount point */));
```

结果：

```
${data}/MicroMsg/${account}/image2
= /data/user/0/com.tencent.mm/MicroMsg/<32hex>/image2
```

`c2c` / `c2c_temp` 同理 → `/data/user/0/com.tencent.mm/MicroMsg/<32hex>/c2c/<biz>/...`

---

## 3. 清单 C：`v61.m1` 完整源码

### 3.1 ⚠️ 纠正：`v61.m1` = **VoiceInfoStorage**

```java
.class public Lv61/m1;
.super Lqf5/s0;

// 静态字段（只有 SQL）
.field public static final i:[Ljava/lang/String;    // voiceinfo 建表 + 3 索引
.field public static final m:[Ljava/lang/String;    // voice_union_* 索引

// 实例字段
.field public final d:Lqf5/k0;                       // DB
.field public final e:Ljava/util/Map;
.field public final f:Ljava/util/Map;
.field public final g:Ljava/util/Map;
.field public final h:Ljava/util/Map;                // ConcurrentHashMap
```

`<clinit>` 铁证：

```java
const-string v2, "CREATE TABLE IF NOT EXISTS voiceinfo ( FileName TEXT PRIMARY KEY, User TEXT,
  MsgId INT, NetOffset INT, FileNowSize INT, TotalLen INT, Status INT, CreateTime INT,
  LastModifyTime INT, ClientId TEXT, VoiceLength INT, MsgLocalId INT, Human TEXT, reserved1 INT,
  reserved2 TEXT, MsgSource TEXT, MsgFlag INT, MsgSeq INT, MasterBufId INT,
  checksum INT DEFAULT 0, VoiceFlag INT DEFAULT 0, VoiceInfoExt BLOB, MsgTalker TEXT  )"
const-string v3, "CREATE INDEX IF NOT EXISTS voiceinfomsgidindex ON voiceinfo ( MsgId ) "
const-string v0, "CREATE UNIQUE INDEX IF NOT EXISTS voiceinfouniqueindex ON voiceinfo ( FileName )"
const-string v1, "CREATE INDEX IF NOT EXISTS voice_unfinish_info_index ON voiceinfo ( Status,User,CreateTime )"
```

完整方法列表：

```
Lv61/m1;-><clinit>()V
Lv61/m1;-><init>(Lqf5/k0;)V
Lv61/m1;->b1(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;   ★
Lv61/m1;->g1(J)Ljava/lang/String;                                     ★
Lv61/m1;->K1(Ljava/lang/String;Lv61/c1;)Z
Lv61/m1;->Z0(Lou5/x;Ljava/lang/String;)V
Lv61/m1;->d(Ljava/lang/String;)Z
Lv61/m1;->j1(Lou5/x;Ljava/lang/String;)Lv61/j;
Lv61/m1;->n1(Ljava/lang/String;)Lv61/c1;
Lv61/m1;->t1(Ljava/lang/String;)Lv61/c1;      // WHERE FileName=?
Lv61/m1;->u1(J)Lv61/c1;                      // WHERE MsgId=?
Lv61/m1;->x1(Ljava/lang/String;J)Lv61/c1;
Lv61/m1;->y1(Lv61/c1;)Z
```

### 3.2 `b1(String, String)`

```java
public static String b1(String str, String str2) {
    boolean z = y8.a;
    String a = x1.a(str, System.currentTimeMillis());     // b41.x1.a
    if (y8.J0(str2)) return a;
    return str2 + a;
}
```

```smali
.method public static b1(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    sget-boolean v0, Lcom/tencent/mm/sdk/platformtools/y8;->a:Z
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    move-result-wide v0
    invoke-static {p0, v0, v1}, Lb41/x1;->a(Ljava/lang/String;J)Ljava/lang/String;
    move-result-object p0
    invoke-static {p1}, Lcom/tencent/mm/sdk/platformtools/y8;->J0(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_11
    return-object p0
    :cond_11
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method
```

### 3.3 `g1(long)`

```java
public static String g1(long j) { return j + "_m"; }
```

### 3.4 依赖 `b41.x1.a(String, long)` — 语音文件名生成

```java
public static String a(String str, long j) {
    String format = new SimpleDateFormat("ssHHmmMMddyy").format(new Date(j));
    if (str == null || str.length() <= 1) str = format + "fffffff";
    else str = format + k.g(str.getBytes()).substring(0, 7);   // md5(talker) 前 7 位
    return (str + String.format("%04x", Long.valueOf(j % 65535))) + ((j % 7) + 100);
}
```

### 3.5 结论

`e9.x0()` 中 `m1.g1(F0())` / `m1.b1(N0(), "")` **仅在语音消息分支 `u.o` 且 voiceinfo 查不到时**触发，
产出形如 `"1234567890_m"` 的**语音文件名**，与图片/缩略图**完全无关**。若插件把它当图片路径用，必错。

---

## 4. 清单 D：缩略图真实存储目录

### 4.1 公式

```
<image2_root>/<hash[0:2]>/<hash[2:4]>/th_<hash>
```

- `<image2_root>` = `t7.b("image2")` = `/data/user/0/com.tencent.mm/MicroMsg/<32hex>/image2/`（无尾斜杠，`pe3.a.b()` 会补 `/`）
- `<hash>` = `md5("SERVERID://" + msgSvrId)`（32 位小写 hex）

### 4.2 实例

```
DB  : THUMBNAIL_DIRPATH://th_3f2a1b8c9d0e4f5a6b7c8d9e0f1a2b3c
REAL: /data/user/0/com.tencent.mm/MicroMsg/ee1da3ae2100e09165c2e52382cfe79f/image2/3f/2a/th_3f2a1b8c9d0e4f5a6b7c8d9e0f1a2b3c
```

### 4.3 其他 resType 后缀（`ou5.b0.m`）

```java
switch (e0Var.ordinal()) {
    case 0:  return "";                                          // UNKNOWN
    case 1:  case 9:  s(..., j + "_l")                           // ORIGIN_IMAGE / ORIGIN_VIDEO
    case 2:  case 10: s(..., k(j, str2))                         // MID_IMAGE / VIDEO
    case 3:  case 11: s(..., j + "_s")                           // ★ THUMB_IMAGE
    case 4:  s(..., j + "_shd")                                  // HD_THUMB_IMAGE
    case 5:  s(..., j + "_m_hevc")                               // MID_HEVC_IMAGE
    case 6:  s(..., j + "_l_hevc")                               // ORIGIN_HEVC_IMAGE
    case 7:  s(..., j + "_m_lp")                                 // LIVE_PHOTO_VIDEO
    case 8:  s(..., j + "_l_lp")                                 // HD_LIVE_PHOTO_VIDEO
    case 12: s(..., k(j, str2)) + ".amr"                         // AUDIO
    case 13: s(..., k(j, str2))                                  // RECORD_DATA_ITEM
    case 14: s(..., k(j, null)) + '.' + str2                     // FILE
}
```

`ou5.e0` 枚举 ordinal 表（**已核实**，静态字段名 = ordinal 对应的混淆名）：

| ordinal | 字段 | 名称 |
|---|---|---|
| 0 | `f` | UNKNOWN |
| 1 | `g` | ORIGIN_IMAGE |
| 2 | `h` | MID_IMAGE |
| **3** | **`i`** | **THUMB_IMAGE** |
| 4 | `m` | HD_THUMB_IMAGE |
| 5 | `n` | MID_HEVC_IMAGE |
| 6 | `o` | ORIGIN_HEVC_IMAGE |
| 7 | `p` | LIVE_PHOTO_VIDEO |
| 8 | `q` | HD_LIVE_PHOTO_VIDEO |
| 9 | `r` | ORIGIN_VIDEO |
| 10 | `s` | VIDEO |
| 11 | `t` | VIDEO_THUMB |
| 12 | `u` | AUDIO |
| 13 | `v` | RECORD_DATA_ITEM |
| 14 | `w` | FILE |

C2C 目录（`ou5.b0.s/f/q/j/p/r`）：

```
t7.b("c2c")/<biz>/<md5(talker)[0:2]>/<msgSvrId[0:2]>/<md5(talker)>_<msgSvrId>_s
t7.b("c2c_temp")/<biz>/.../temp_<md5(talker)>_<msgSvrId>_s
```

### 4.4 `th_` 前缀定义位置（全部）

| 位置 | 代码 |
|---|---|
| `wb0.b.tj` L38 | `if (j0.z(obj, "th_", false)) obj.substring(3)` |
| `wb0.b.oj` L141 | `const-string/jumbo v1, "th_"` |
| `wb0.b.nj` L11/L24 | `String str3 = "th_"` / `substring(23)` |
| `l51.l0.D4` L32 | `b0Var.F("THUMBNAIL_DIRPATH://th_" + g)` |
| `l51.l0.X1` L17 | `pString.value = "THUMBNAIL_DIRPATH://th_" + g` |
| `l51.m0.a` L94/L145/L163 | `"THUMBNAIL_DIRPATH://th_" + g` |
| `kz1.a0.G` L45 | `"THUMBNAIL_DIRPATH://th_" + l0.L1()`（名片特例） |
| `ys1.f.e` / `ys1.h.e` | `"THUMBNAIL_DIRPATH://th_"` |
| `oa0.e.J7` / `oa0.e.K7` | `"THUMBNAIL_DIRPATH://th_"` |
| `na0.u.sj` | `"THUMBNAIL_DIRPATH://th_"` |
| `com.tencent.mm.pluginsdk.model.app.a0.b` | `"THUMBNAIL_DIRPATH://th_"` |
| `z02.b.invoke` | `"type=3 OR imgPath LIKE 'THUMBNAIL_DIRPATH://%'"` |
| `com.tencent.mm.console.y0.run` | `if (str4.startsWith("th_"))` |

`msgth_` 仅出现在 `l51.l0.z2` / `wb0.b.nj` / `wb0.b.oj`。

### 4.5 其他 `"thumb"` 目录（勿混淆）

```java
// l51.l0.p2() —— App 消息缩略图
p2() = p.cj() + "thumb/"  =  /data/user/0/com.tencent.mm/MicroMsg/<32hex>/openapi/thumb/

// z02.g.<clinit> —— C2CWildFileCleaner 的 VFS URI 前缀
"/attachment/"  "/image2/"  "/record/"  "/video/"  "/voice2/"

// bv3.p.cj()
cj() = j1.x().d() + "openapi/"
```

---

## 5. 清单 E：主动触发图片下载的 API

### 5.1 类定位表（含纠正）

| 混淆名 | 真实语义 | 证据 |
|---|---|---|
| `rn3.u0` | **ImgInfoService 接口** | 由 `wb0.b` 实现 |
| `wb0.b` | ImgInfoService 实现 | Log `MicroMsg.C2CFileFeatureService` / `C2CImgPathFeatureService` |
| `ex0.k0` | **MsgInfo 查询**（不是 ImgInfoService） | 仅 `L7/N9/f5/k2/pg/yi`，全是 DB 查询 |
| `l51.l0` | **ImgInfoStorage** | Log `MicroMsg.ImgInfoStorage` |
| `l51.b1` | ImgInfoStorage 工厂 | `b1.ej()` |
| `l51.b0` | ImgInfo ORM 行 | 见 §5.2 字段表 |
| `ja0.g` | **MsgImgSyncDownloadFSC** | Log `MicroMsg.ImgDownload.MsgImgSyncDownloadFSC` |
| `ka0.k` | ImgLoadDataFromRemotePPC | Log `MicroMsg.ImgDownload.MsgImgLoadDataFromRemotePPC` |
| `ha0.a0/b0` | MsgImgLoaderFeatureService | Log `MicroMsg.ImgDownload.MsgImgLoaderFeatureService` |
| `ky.b0` | CdnFSC 接口 | `Pi` = `startSyncCdnDownload` |
| `com.tencent.mm.modelcdntran.z` | CdnFSC | — |
| `com.tencent.mm.modelcdntran.n1` | CdnTransportEngine | — |
| `com.tencent.mm.modelcdntran.u1` | Cdn 服务工厂 | `kj()→z`, `mj()→n1` |

**`ex0.k0` 全方法（证明它不是下载服务）**：

```
Lex0/k0;->L7(Ljava/lang/String;J)I
Lex0/k0;->N9(Ljava/lang/String;J)Lcom/tencent/mm/storage/e9;
Lex0/k0;->f5(...)Lcom/tencent/mm/storage/e9;
Lex0/k0;->k2(Ljava/lang/String;J)Lcom/tencent/mm/storage/e9;
Lex0/k0;->pg(Ljava/lang/String;JZ)Lcom/tencent/mm/storage/e9;
Lex0/k0;->yi(Ljava/lang/String;J)Lcom/tencent/mm/storage/e9;
```

### 5.2 `l51.b0` 字段表（ImgInfo 行，已核实用法）

| 字段 | 类型 | 含义 | 依据 |
|---|---|---|---|
| `a` | J | id / rowid | `J2.a == w3.q` 比较 |
| `b` | J | msgSvrId | `m()` 用 `b == 0` |
| `c` | I | totalLen | `l()` 用 `d == c` |
| `d` | I | offset | 同上 |
| `e` | String | **bigImgPath** | `getBigImgPath()` |
| `f` | String | **midImgPath** | `u0.jj(..., F3.f, ...)` |
| `g` | String | **thumbImgPath** | `b0Var.g` / `pString.value = b0Var.g` |
| `j` | String | hevcPath | `u0.jj(..., F3.j, ...)` |
| `m` | String | msgTalker | `TextUtils.isEmpty(F3.m) ? N0 : F3.m` |
| `o` | J | msgLocalId | `k0.yi(talker, F3.o)` |
| `q` | J | **hdId** | `k()` = `q > 0` |
| `t` | String | contentXml | `e3.t` 传入 `h95.d.fromXml` |

判定方法：

```java
public boolean k() { return this.q > 0; }              // 有 HD 图
public boolean l() { int i = this.d; return i != 0 && i == this.c; }   // 大图完整
public boolean m() { return this.o == 0 && this.b == 0; }              // 空行
```

### 5.3 `wb0.b` 全部可 Hook 方法

```
Lwb0/b;->Bj(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->Cj(Lcom/tencent/mm/storage/e9;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->Dj(Lcom/tencent/mm/storage/e9;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->Ej(Lou5/x;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->Fj(Lou5/x;Ljava/lang/String;ZZ)Ljava/lang/String;
Lwb0/b;->gj(Lou5/x;)Ljava/lang/String;
Lwb0/b;->hj(Lou5/x;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->ij(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;)Ljava/lang/String;
Lwb0/b;->jj(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
Lwb0/b;->kj(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)Ljava/lang/String;   ★
Lwb0/b;->lj(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZ)Ljava/lang/String; ★ 核心
Lwb0/b;->mj(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->nj(Ljava/lang/String;)Ljava/lang/String;
Lwb0/b;->oj(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;   ★ 剥壳组路径
Lwb0/b;->pj(Lou5/x;Ljava/lang/String;Ljava/lang/String;ZZ)Ljava/lang/String;
Lwb0/b;->qj(Lsp3/g;Lou5/e0;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)Ljava/lang/String;
Lwb0/b;->rj(Lcom/tencent/mm/storage/e9;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->sj(Lcom/tencent/mm/storage/e9;Ljava/lang/String;ZZ)Ljava/lang/String;          ★ getFullThumbPath
Lwb0/b;->tj(Ljava/lang/String;Z)Ljava/lang/String;                                       ★ 剥前缀
Lwb0/b;->uj(Lsp3/g;Ljava/lang/String;ZZ)Ljava/lang/String;
Lwb0/b;->vj(Lou5/x;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
Lwb0/b;->wj(Lcom/tencent/mm/storage/e9;Lou5/e0;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->xj(Lou5/x;Z)Ljava/lang/String;
Lwb0/b;->yj(Lsp3/g;Lou5/e0;Ljava/lang/String;Z)Ljava/lang/String;
Lwb0/b;->zj(Lcom/tencent/mm/storage/e9;Ljava/lang/String;Z)Ljava/lang/String;
```

`rn3.u0` 接口默认参数合成方法（12 个）：

```
C8(rn3/u0, sp3/g, String, ZZ, I, Object)String      ← 可写缩略图路径
Fd(rn3/u0, sp3/g, String, Z, I, Object)String
Q2(rn3/u0, ou5/x, String, Z, I, Object)String
R7(rn3/u0, sp3/g, String, Z, I, Object)String
Z1(rn3/u0, e9, String, I, Object)String
Zi(rn3/u0, sp3/g, ou5/e0, String, Z, I, Object)String
cc(rn3/u0, e9, String, ZZ, I, Object)String
jb(rn3/u0, e9, ou5/e0, String, String, String, ZZ, I, Object)String
o6(rn3/u0, e9, String, Z, I, Object)String
re(rn3/u0, e9, ou5/e0, String, Z, I, Object)String
v8(rn3/u0, e9, String, Z, I, Object)String
ya(rn3/u0, sp3/g, ou5/e0, String, String, String, ZZ, I, Object)String
```

### 5.4 ★ 下载链路（区分 suspend / 非 suspend）

```
ja0.g.K7(e9, ea0.c, boolean)  -> x90.d        ★ 非 suspend，可反射调用
        │
        ▼
ja0.g.J7(x90.d, Continuation)                 ✗ suspend，不可直接反射调用
        │
        ▼
ky.b0.Pi(b0, jn.m, long, WeakReference, cont, int, Object)   ✗ suspend
        │
        ▼
com.tencent.mm.modelcdntran.l0.invokeSuspend                ✗ suspend
        │
        ▼
com.tencent.mm.modelcdntran.z.U7(jn.m)  -> n2               ★ 非 suspend
        │
        ▼
com.tencent.mm.modelcdntran.z.J7(z, ConcurrentHashMap, jn.m, i2)   ★ 非 suspend（static）
        │
        ▼
com.tencent.mm.modelcdntran.r.invoke(e)  -> int             ★ 非 suspend
        │
        ▼
com.tencent.mm.modelcdntran.n1.u(jn.m)  -> int              ★ 非 suspend，真正发起 C2C 下载
        └─ CdnManager.startC2CDownload(n1.a(task), n1)
```

#### `ja0.g.K7(e9, ea0.c, boolean)` — 构造参数

```java
public final d K7(e9 e9Var, c cVar, boolean z) {
    String j = e9Var != null ? e9Var.j() : null;        // content xml
    if (j == null) return null;
    h95.d dVar2 = new h95.d();  dVar2.fromXml(j);
    h0 h0Var2 = new h0();  h0Var2.d = "";
    String str5 = z2.a(String.valueOf(e9Var.F0())) + '_' + e9Var.getCreateTime();

    int ordinal = cVar.ordinal();
    if (ordinal != 1) {                                  // != Thumb
        if (ordinal != 3) {                              // != Mid
            if (ordinal != 4 && ordinal != 5 && ordinal != 6) return null;
            // ===== Big / BigLive =====
            String str6 = "downloadBig_" + str5;
            e0 e0Var = e0.g;                             // ORIGIN_IMAGE
            b0 w3 = b1.ej().w3(e9Var);
            if (w3 != null && w3.a > 0) {
                if (!w3.k()) { Log.i(...,"no hd img"); return null; }
                b0 J2 = b1.ej().J2(Long.valueOf(w3.q));
                if (J2.a != w3.q) { Log.i(...,"get hd img fial"); return null; }
                String str4 = j0.z(J2.e, "SERVERID://", false) ? zVar.b(J2.e, true) : J2.e;
                if (cVar == c.m) { str6 = "downloadBigLive_" + str6; e0Var = e0.q; }
                h0Var2.d = ((u0) n0.c(u0.class)).kj(e9Var, e0Var, str4, null, "", true, true);
                aVar = (z && cVar == c.h) ? new e(J2, h0Var2, str4) : null;
            }
            return null;
        }
        // ===== Mid / MidLive =====
        String str10 = "downloadMid_" + str5;
        e0 e0Var2 = e0.h;                                // MID_IMAGE
        b0 w32 = b1.ej().w3(e9Var);
        if (w32 == null || w32.a <= 0) return null;
        String str2 = j0.z(w32.e, "SERVERID://", false) ? zVar.b(w32.e, false) : w32.e;
        if (cVar == c.i) { e0Var2 = e0.p; str10 = "downloadMidLive_" + str10; }
        h0Var.d = ((u0) n0.c(u0.class)).kj(e9Var, e0Var2, str2, null, "", true, true);
        aVar = (z && cVar == c.g) ? new d(w32, h0Var, str2) : null;
    } else {
        // ===== Thumb（默认） =====
        h0Var.d = c0.a(e9Var, e0.i, false, true, 2, (Object) null);   // ou5.c0.a → 缩略图路径
        str = "downloadThumb_" + str5;
    }
    boolean z2 = (y != null && !o0.P(y)) && (I != null && !o0.P(I));
    d dVar3 = new d(z2, cVar, dVar2, e9Var.N0(), str, h0Var.d);
    dVar3.m = aVar;
    return dVar3;
}
```

`ea0.c` 枚举（已核实）：

| ordinal | 字段 | 名称 |
|---|---|---|
| 0 | `d` | All |
| **1** | **`e`** | **Thumb** |
| 2 | `f` | HdThumb |
| 3 | `g` | Mid |
| 4 | `h` | Big |
| 5 | `i` | MidLive |
| 6 | `m` | BigLive |

#### `x90.d` 字段表（下载参数）

| 字段 | 类型 | 含义 |
|---|---|---|
| `a` | Z | isBigFile |
| `b` | `ea0.c` | 下载类型 |
| `c` | `h95.d` | 图片 xml |
| `d` | String | username / talker |
| `e` | String | mediaId |
| `f` | String | **outputPath（落盘路径）** |
| `g` | String | downId |
| `h` | Z | 支持 HEVC |
| `i` | String | 场景标记（如 `img_loader_thumb_down`） |
| `j` | HashMap | 埋点 map（down_id / down_img_type / down_media_id / down_user_type / down_output_path / down_svr_id ...） |
| `k` | Map | 扩展 map |
| `l` | Z | — |
| `m` | `x90.a` | 回调 |
| `n` | WeakReference | — |
| `o` | I | concurrentCount（默认 4） |
| `p` | J | — |
| `q`/`r` | Z | — |

`x90.e`（结果）：`a: x90.f`（结果类型枚举 d/e/f/g/h/i）、`b: int`（retCode）

#### `ja0.g.J7(...)` 关键片段

```java
long elapsedRealtime = SystemClock.elapsedRealtime();
b.h(new f(new x90.b(dVar2, c.d)));
HashMap hashMap = dVar2.j;
hashMap.put("down_id", dVar2.g);
hashMap.put("down_img_type", dVar2.b.toString());
hashMap.put("down_media_id", dVar2.e);
hashMap.put("down_user_type", ...);            // normal / biz / other.at
hashMap.put("down_output_path", dVar2.f);

String str21 = dVar2.f + "_temp";             // ★ 先下到 _temp
m mVar = new m();                             // jn.m CdnTask
mVar.e = false;
mVar.d = "task_MsgImgUploadFSC";
mVar.field_mediaId  = dVar2.e;
mVar.field_fullpath = str21;
mVar.y0 = dVar2.o;
mVar.j("MicroMsg.ImgDownload.MsgImgSyncDownloadFSC", dVar2.k);

boolean z2 = dVar2.h && ((v) n0.c(v.class)).jj();
boolean z3 = dVar2.a;
h95.d dVar3 = dVar2.c;

if (z3) {                                     // 大文件
    mVar.field_fileType = 19;
    mVar.field_authKey = dVar3.o().y();
    mVar.field_fileId  = "";
    if (cVar3 == ea0.c.e) {                   // Thumb
        mVar.field_aesKey = dVar3.o().D();
        mVar.z = dVar3.o().G();
        mVar.field_totalLen = dVar3.o().F();
        mVar.field_supportFormats = z2 ? new int[]{1,2} : new int[]{1};
    } else if (cVar3 == ea0.c.g) {            // Mid
        mVar.field_aesKey = dVar3.o().getAeskey();
        mVar.z = dVar3.o().I();
        mVar.field_totalLen = dVar3.o().C();
        ...
    }
} else {
    if (cVar3 == ea0.c.e) {                   // Thumb（非大文件）
        mVar.field_aesKey = dVar3.o().m();
        String p = dVar3.o().p();
        if (p != null && j0.z(p, "http", false)) mVar.z = p;
        else { mVar.field_fileType = 3; mVar.field_fileId = p; }
        mVar.field_totalLen = dVar3.o().o();
        mVar.field_supportFormats = z2 ? new int[]{1,2} : new int[]{1};
    } else if (cVar3 == ea0.c.g) {            // Mid
        mVar.field_fileType = 2;
        mVar.field_aesKey = dVar3.o().getAeskey();
        mVar.field_fileId  = dVar3.o().k();
        mVar.field_totalLen = dVar3.o().getLength();
        mVar.field_wxamTotalLen = (int) dVar3.o().s();
    } else if (cVar3 == ea0.c.i) { field_fileType = 24; ... }   // MidLive
      else if (cVar3 == ea0.c.m) { field_fileType = 25; ... }   // BigLive
      else { mVar.field_fileType = 1; ... }                     // Big
}
mVar.field_filemd5 = dVar3.o().getMd5();
mVar.field_priority = (cVar3 == ea0.c.e) ? 3 : 2;
// ... 之后 ky.b0.Pi(this, mVar, 3600000L, weakRef, cont, 4, null)
```

### 5.5 图片加载器自动触发（收消息时）

```java
// l51.m0.k(p0)   line 38-95
public q0 k(p0 p0Var) {
    q0 k3 = super.k(p0Var);
    e9 e9Var = k3.a;
    if (e9Var != null && e9Var.J2() && (((c8) e9Var).F & 4) != 4) {
        b0 b0Var = (f1) n0.c(f1.class);
        sp3.f fVar = new sp3.f();  kb.a(fVar, e9Var2);
        a0 a0Var = b0.m;
        String a = a0Var.a(fVar);                       // genImageKey
        Log.i("...MsgImgLoaderFeatureService", "loadMsgImgThumb imageKey:" + a + " source:ImgMsgExtension");

        List k4 = c0.k(new Class[]{e.class, g.class, ka0.k.class, l.class, o.class});
        c96.l jj = b0.jj(b0Var, fVar, false, 2, (Object) null);
        u0 c = n0.c(u0.class);
        String C8 = u0.C8(c, fVar, fVar.k(), false, true, 4, (Object) null);   // 可写 thumb
        String concat = C8.concat("hd");                                       // 可写 hd thumb

        z zVar = new z();
        zVar.m("Common_ImageKey", a);
        zVar.m("Common_StartTimestamp", Long.valueOf(SystemClock.elapsedRealtime()));
        zVar.m("Common_ImageViewRefMap", a0Var.b());
        zVar.m("key_thumb_path", (String) jj.d);
        zVar.m("key_hd_thumb_path", (String) jj.e);
        zVar.m("key_write_thumb_path", C8);
        zVar.m("key_write_hd_thumb_path", concat);
        zVar.m("key_msg_info", fVar);

        r rVar = new r(d96.n0.Z0(k4), zVar, "MsgImgLoader@" + (System.currentTimeMillis() % 4),
                       (s) null, true, 8, (i) null);
        rVar.d();
        new WeakReference(rVar);
    }
    return k3;
}
```

`ha0.b0.lj(e9, String)` — HD 缩略图生成后强制刷新（`Common_ForceRefresh = TRUE`），结构同上。

`ka0.k.j(z, d, cont)` — PPC 远程拉取节点，真正调 `ja0.g.J7`：

```java
String l = h().l("Common_ImageKey");
f fVar = (f) zVar.j("key_msg_info");
l0 ej = b1.ej();
b0 e3 = ej.e3(talker, msgId, msgSvrId);            // 查 imgInfo
String k2 = zVar.k("key_write_thumb_path", "");
String k3 = zVar.k("key_write_hd_thumb_path", "");
String str8 = "downimgthumb_" + l;
if (((HashSet) nVar.getValue()).contains(l)) return new e(dVar5.b, (byte[]) null);

h95.d dVar2 = new h95.d();  dVar2.fromXml(string);
String p = dVar2.o().p();   String G = dVar2.o().G();
if ((p == null || o0.P(p)) && (G == null || o0.P(G))) return new e(dVar5.b, (byte[]) null);

x90.d dVar6 = new x90.d(!y8.J0(G), c.e, dVar2, talker, str8, k2);
dVar6.l = true;  dVar6.o = 4;  dVar6.i = "img_loader_thumb_down";
dVar6.j.put("down_svr_id", Long.valueOf(msgSvrId));
dVar6.j.put("down_inner_version", 6);

ja0.g a = ja0.g.f.a();
obj = a.J7(dVar6, jVar);                           // ★★ 调 MsgImgSyncDownloadFSC.J7
```

### 5.6 底层 CdnFSC / CdnTransportEngine

```java
// com.tencent.mm.modelcdntran.z.U7(m task)
public final n2 U7(m mVar) {
    i2 b = r2.b(3, 0, null, 6, null);              // MutableSharedFlow(buffer=3)
    Log.i("MicroMsg.Cdn.CdnFSC", "startDownloadTask " + mVar.field_mediaId + ' ' + P7().size());
    mVar.e = false;
    T7(new w(this, mVar, b, null));
    return l.a(b);
}

// z.J7(z, ConcurrentHashMap map, m task, i2 flow)   static
if (map.containsKey(task.field_mediaId)) { /* 已在队列，直接回调 */ }
task.field_startTime = md.c();
// y10/i.kj(md5, totalLen, fullpath, fileType)  —— 文件已存在则直接成功返回
e eVar = new e(zVar, task, flow);
map.put(task.field_mediaId, eVar);
int ret = new r(zVar).invoke(eVar);                // JNI 启动
if (ret != 0) map.remove(task.field_mediaId);
Log.i("MicroMsg.Cdn.CdnFSC", "startJniTask " + task.field_mediaId + ' ' + ret);

// com.tencent.mm.modelcdntran.r.invoke(e)
u1.nj().c();
int fileType = task.field_fileType;
if (fileType == 40001 || fileType == 19)        i = u1.mj().s(task);       // 大图/视频
else if (fileType == 30001 || 30003 || 30007 || 31000) i = u1.mj().k(task); // 短视频
else if (task.F) { task.field_fileType = 30002; i = u1.mj().k(task); }      // http
else if (!task.K) i = task.e ? u1.mj().v(task) : u1.mj().u(task);            // ★ C2C
else if (task instanceof i)  i = u1.mj().o(task);
else if (task instanceof j)  i = u1.mj().p((j) task);

// com.tencent.mm.modelcdntran.n1.u(m task)
public int u(m mVar) { return MarsContext.getManager(CdnManager.class).startC2CDownload(a(mVar), this); }

// n1.a(m task) -> CdnManager.C2CDownloadRequest（节选）
req.fileid      = task.field_fileId;
req.url         = task.z;
req.aeskey      = task.field_aesKey;
req.fileKey     = task.field_mediaId;
req.setSavePath(task.field_fullpath);            // ★ 落盘路径 = <thumbPath>_temp
req.fileType    = task.field_fileType;
req.isStorageMode = task.field_needStorage;
req.isSmallVideo  = task.field_smallVideoFlag == 1;
req.isAutoStart   = task.field_autostart;
req.supportFormats = task.field_supportFormats;
req.expectFileMD5  = (task.field_fileType == 30002) ? task.field_filemd5 : null;
req.concurrentCount = task.y0;
req.connectionCount = task.x0;
req.bizid / apptype / allow_mobile_net_download / wifiAutoStart ...
```

### 5.7 独立 Kotlin 模块的推荐 Hook 点

| 目的 | Hook 目标 | 参数/返回值 |
|---|---|---|
| 拿真实路径（最稳） | `wb0.b.oj(String,String,String,boolean)` **after** | `param.result` 即真实绝对路径 |
| 拿 hash（剥壳） | `wb0.b.tj(String,boolean)` **before** | `args[0]` = DB 值 |
| 拿可写缩略图路径 | `wb0.b.sj(e9,String,boolean,boolean)` after | `result` |
| 拿可写任意 resType 路径 | `wb0.b.kj(e9,ou5/e0,String,String,String,boolean,boolean)` after | `result` |
| 观察下载参数 | `ja0.g.K7(e9,ea0.c,boolean)` after | `result` = `x90.d` |
| 观察下载结果 | `ja0.g.J7(x90.d,Continuation)` before/after | `result` = `x90.e` |
| 捕获 CDN Task | `com.tencent.mm.modelcdntran.n1.a(jn.m)` before | `args[0]` = `jn.m` |
| 强制发起下载 | 反射 `n1.u(jn.m)` | 返回 int（0 = 失败） |

---

## 6. 清单 F：`x0()` 完整源码核对

### 6.1 `im.c8.x0()`

```java
public String x0() { return this.field_imgPath; }
```

### 6.2 `com.tencent.mm.storage.e9.x0()`

```java
public String x0() {
    String e;
    j0 j0Var = k0.F0;
    String x0 = super/*im.c8*/.x0();                     // field_imgPath
    j0Var.getClass();
    ((k0) n0.c(k0.class)).getClass();

    if (x0 == null || x0.length() == 0) {
        if (v.a(this) == u.g) {                          // 视频
            ((q) n0.c(q.class)).getClass();
            ((q1) n0.c(q1.class)).getClass();
            List h = o2.hj().h(this, x0);
            if (h != null) {
                Iterator it = ((ArrayList) h).iterator();
                while (true) {
                    if (!it.hasNext()) break;
                    Object next = it.next();
                    o.g(((v2) next).e(), "getFileName(...)");
                    if ((!kc6.j0.o(r6, "origin", false)) != false) { r5 = next; break; }
                }
                r5 = (v2) r5;
            }
            return (r5 == null || (e = r5.e()) == null) ? x0 : e;

        } else if (v.a(this) != u.o) {                   // 非语音
            return v.a(this) == u.n
                ? ((s5) n0.c(s5.class)).gj(this, x0)
                : x0;

        } else {                                          // ★ 语音 u.o
            ((h1) n0.c(h1.class)).getClass();
            if (x0 == null || x0.length() == 0) {
                c1 u1 = F0() != 0 ? v0.ej().u1(F0()) : null;      // 按 msgSvrId 查 voiceinfo
                if (u1 == null) u1 = v0.ej().x1(N0(), getMsgId()); // 按 talker+msgId 查
                r5 = u1 != null ? u1.b : null;                     // voiceinfo.FileName
                if (r5 == null) {
                    if (x0 == null) {
                        x0 = F0() != 0 ? m1.g1(F0())               // "<msgSvrId>_m"
                                       : m1.b1(N0(), "");           // 时间戳+md5(talker)
                    }
                    o.e(x0);
                    return x0;
                }
                return r5;
            }
            return x0;
        }
    }
    return x0;
}
```

### 6.3 核对结论

| 项 | 文档原说法 | 实际 | 判定 |
|---|---|---|---|
| `im.c8.x0()` | 反编译过 | `return this.field_imgPath;` | ✅ 一致 |
| `e9.x0()` 空 imgPath 回退 | 调 `v61.m1.b1` / `g1` | 确实调，但**仅在 `v.a(this)==u.o`（语音）分支** | ⚠️ 语义需修正 |
| `v61.m1` | 路径拼接器 | **VoiceInfoStorage** | ❌ 定性错误 |
| `THUMBNAIL_DIRPATH` 是常量 | `public static final String` | 硬编码字面量，无此字段 | ❌ 假设错误 |

---

## 7. 二次严审记录（v2 增补）

| # | 断言 | 验证方式 | 结果 |
|---|---|---|---|
| 1 | 前缀长度 20 / 3 / 29 / 9 / 11 / 12 | `tj` L26/L36；`oj` smali `0x17`=23、`0x1d`=29；`nj` L24/L28 | ✅ |
| 2 | `oj()` 双调用约定 | `tj()` 传纯 hash + prefix；`nj`/`m0.a` 传完整串走 `substring(23)` | ✅ |
| 3 | 两级散列 `xx/yy/`，要求 `length()>4` | `k1.b(String)` | ✅ |
| 4 | md5 输入 `"SERVERID://"+msgSvrId` | `l51.l0.D4`/`X1`、`l51.m0.a` 三处一致 | ✅ |
| 5 | `${data}` = app data root | `f3.<init>`：`getCacheDir().getParent()` | ✅ |
| 6 | `image2` 根 = `${data}/MicroMsg/${account}/image2` | `e3.<clinit>` `:cond_541`：`b8.c[loc] + f6.b`，`b8.c[1]="${data}/MicroMsg/${account}/"` | ✅ |
| 7 | `t7.b()` 无尾斜杠 | `pe3.a.b() = t7.b("image2") + "/"` | ✅ |
| 8 | `v61.m1` = VoiceInfoStorage | `<clinit>` `CREATE TABLE voiceinfo`；`t1/u1` 查 voiceinfo | ✅ |
| 9 | `ex0.k0` 非 ImgInfoService | 方法列表仅 DB 查询 | ✅ |
| 10 | `ja0.g` 是下载 FSC | Log tag + 构造 `jn.m` + 调 `ky.b0.Pi` | ✅ |
| 11 | 落盘先写 `_temp` | `J7`：`String str21 = str20 + "_temp"` | ✅ |
| 12 | `kz1.a0.G` hash 算法不同 | `l0.L1()` → `N1("")` → `k.g((str+currentTimeMillis))` | ✅ 名片特例 |
| 13 | `ja0.g.J7` / `ky.b0.Pi` / `modelcdntran.l0.invokeSuspend` 均为 **suspend** | 签名含 `Lkotlin/coroutines/Continuation;` | ✅ v2 新增 |
| 14 | `z.U7` / `z.J7` / `n1.u` / `n1.a` 均为**非 suspend**，可反射直达 | 方法签名无 Continuation | ✅ v2 新增 |
| 15 | `e0.i` = THUMB_IMAGE（ordinal 3），`ea0.c.e` = Thumb（ordinal 1） | `<clinit>` 逐行核对 | ✅ v2 新增 |
| 16 | `l51.b0` 字段语义（e/f/g/j/m/o/q/t） | 交叉引用 `l51.m0.a`/`ja0.g.K7`/`ka0.k.j`/`l51.l0.C2` | ✅ v2 新增 |
| 17 | `k()`=`q>0`(有HD)、`l()`=`d!=0&&d==c`(大图完整)、`m()`=`o==0&&b==0`(空行) | 直接反编译 | ✅ v2 新增 |

---

## 8. 独立 Kotlin 模块使用要点

### 8.1 与 LSPilot BSH 插件的 API 差异

| LSPilot BSH | 独立 Kotlin Xposed |
|---|---|
| `findClass("wb0.b")` | `XposedHelpers.findClassIfExists("wb0.b", lpparam.classLoader)` |
| `hookMethodBefore(c, m, cb)` | `XposedHelpers.findAndHookMethod(c, m, paramTypes..., callback)` |
| `getObjectField(o, "f")` | `XposedHelpers.getObjectField(o, "f")` |
| `callMethod(o, "a", x)` | `XposedHelpers.callMethod(o, "a", x)` |
| `log(s)` | `XposedBridge.log(s)` |
| `md5Hex(s)` | `MessageDigest.getInstance("MD5")` 自实现 |
| 全局函数（已注册） | 全部需 `XposedHelpers.` 前缀 |

### 8.2 三个可直接抄的用法

```kotlin
// ① 拿真实根目录（运行时，最稳）
val root = XposedHelpers.callStaticMethod(
    XposedHelpers.findClass("pe3.a", cl), "b") as String   // ".../image2/"

// ② 本地算路径（零反射，最快）
val real = WeChatThumb.resolveFromMsgSvrId(root, msgSvrId)

// ③ 反射让微信自己算（最权威）
val real2 = WeChatThumb.hostResolveByUri(cl, "THUMBNAIL_DIRPATH://th_$hash")
```

### 8.3 服务定位器 `ph5.n0.c(Class)`（独立模块取单例的正道）

微信内部所有服务获取都写成 `((Xxx) n0.c(Xxx.class))`，反编译后是：

```smali
const-class p3, Lrn3/u0;
invoke-static {p3}, Lph5/n0;->c(Ljava/lang/Class;)Lph5/m;
move-result-object p3
check-cast p3, Lrn3/u0;
```

即 **`ph5.n0.c(Class)` 是静态泛型服务定位器**。独立 Kotlin 模块直接反射它，比扫静态字段稳：

```kotlin
fun service(cl: ClassLoader, ifaceName: String): Any? =
    XposedHelpers.callStaticMethod(
        XposedHelpers.findClass("ph5.n0", cl), "c",
        XposedHelpers.findClass(ifaceName, cl)
    )

// 用法
val imgSvc  = service(cl, "rn3.u0")                       // wb0.b  ImgInfoService
val storage = service(cl, "l51.l0")                       // 或 l51.b1.ej()
val cdnFsc  = service(cl, "ky.b0")                        // com.tencent.mm.modelcdntran.z
val engine  = service(cl, "com.tencent.mm.modelcdntran.n1")
```

`ph5.n0` 其它可用方法（已核实签名）：

```
a(Iterable, ph5/r, ZZ)V      b(String)V      c(Class)Lph5/m;
d(Application, ph5/y, rh5/a)V  e(ZZ)V        f(Callable)Object
g()Z                         h(Class)Z       i()V
j(ph5/w, ph5/w, ph5/r, ZZZ)V  k(Z)V          l(Collection, Z)V
```

### 8.4 关于"图片未落地"的解决顺序

1. **先确认路径算对了**：Hook `wb0.b.oj` after，对比 `param.result` 与本地算出的路径。
2. **确认文件是否真的存在**：`File(real).exists()`；不存在说明没下载完或已被 C2CWildFileCleaner 清理。
3. **确认是否有 `_temp` 残留**：`File(real + "_temp")`。`ja0.g.J7` 先写 `_temp`，成功后改名；有 `_temp` 说明下载中断。
4. **主动触发**：优先 Hook `ka0.k.j` / `l51.m0.k` 让微信自然加载；需要强制时用 `n1.u(capturedTask)`。
5. **老账号目录兼容**：`pe3.a.a()`（`image` 根）是旧位置，`k1.a()` 内部会自动做迁移 copy，所以只需检查新路径。

---

## 9. 附录：涉及类速查表

| 混淆类名 | 推断真实类名 | 作用 |
|---|---|---|
| `wb0.b` | ImgInfoService impl | 路径解析核心 |
| `rn3.u0` | ImgInfoService 接口 | — |
| `l51.l0` | ImgInfoStorage | ImgInfo2 表 |
| `l51.b1` | ImgInfoStorage 工厂 | `b1.ej()` |
| `l51.b0` | ImgInfo ORM 行 | 字段见 §5.2 |
| `l51.c0` | ImgInfoStorage 静态工具 | `a(String)=md5(path)` |
| `l51.m0` | ImgMsgExtension | 收图消息 |
| `ou5.b0` | C2CPath | C2C 目录拼接 |
| `ou5.e0` | ResType 枚举 | ordinal 表 §4.3 |
| `ou5.u` | BusinessType 枚举 | `f=IMAGE, g=VIDEO, p=UNKNOWN` |
| `ou5.x` | C2CFileDescriptor | — |
| `ou5.y` | C2CPath 静态工具 | — |
| `ou5.c0` | C2CPath 扩展 | `a(e9,e0,z,z)` |
| `ou5.w` / `ou5.i` | C2CFileDescriptor 工厂 | — |
| `ma0.b` | C2CImgPathFeatureService | — |
| `ja0.g` | MsgImgSyncDownloadFSC | 同步下载 |
| `ja0.a` | 伴生对象 | `ja0.g.f.a()` |
| `ka0.k` | ImgLoadDataFromRemotePPC | 图片加载状态机 |
| `ka0.f` | ImgLoadDataFromRemotePPC 另一态 | — |
| `ha0.a0` / `ha0.b0` | MsgImgLoaderFeatureService | — |
| `ky.b0` | CdnFSC 接口 | `Pi`=startSyncCdnDownload, `te`=startSyncCdnUpload |
| `com.tencent.mm.modelcdntran.z` | CdnFSC | `U7/J7/N7/P7` |
| `com.tencent.mm.modelcdntran.n1` | CdnTransportEngine | `u/a/k/s/v/o/p` |
| `com.tencent.mm.modelcdntran.u1` | Cdn 服务工厂 | `kj/mj/nj/lj/jj` |
| `com.tencent.mm.modelcdntran.l0` | 下载协程 | suspend |
| `com.tencent.mm.modelcdntran.e` | 下载任务持有者 | `a=jn.m, b=i2` |
| `com.tencent.mm.modelcdntran.r` | JNI 启动器 | `invoke(e)→int` |
| `com.tencent.mm.modelcdntran.p1` | 下载状态事件 | `o1` 状态 + `jn.m` |
| `com.tencent.mm.modelcdntran.o1` | 下载状态枚举 | — |
| `jn.m` | CDN Task | `field_fullpath/field_mediaId/field_fileId/...` |
| `jn.h` | CDN Result | `field_retCode` |
| `x90.d` / `x90.e` / `x90.f` | 下载参数/结果/结果枚举 | 字段见 §5.4 |
| `ea0.c` | 下载类型枚举 | ordinal 表 §5.4 |
| `com.tencent.mm.vfs.t7` | VFSStrategy | `b(fsName)` |
| `com.tencent.mm.vfs.h7` | VFS 环境持有者 | `c(fsName)` |
| `com.tencent.mm.vfs.f3` | VFS.FileSystemManager | `<init>` 定义 `${data}` |
| `com.tencent.mm.vfs.f1` | `${...}` 模板解析器 | `a(Map)→String` |
| `com.tencent.mm.vfs.k1` | fsName→mount point 缓存 | — |
| `com.tencent.mm.vfs.e3` | VFS 初始化 | `<clinit>` 注册全部 fs |
| `com.tencent.mm.vfs.b8` | 路径模板表 | `a[16]` 原型 / `c[16]` 根前缀 |
| `com.tencent.mm.vfs.s6`/`i6`/`f6`/`u`/`q` | VFS.Config 构建器 | — |
| `bv5.eb` | VFS 配置 lambda | 注册全部 storage |
| `bv5.fb` | `bv5.eb` 结果容器 | `a = Collection<f6>` |
| `pe3.a` | VFS 根目录工具 | `a()=image, b()=image2` |
| `com.tencent.mm.sdk.platformtools.k1` | 路径生成器 | `a/d/b` |
| `v61.m1` | **VoiceInfoStorage** | 语音 |
| `b41.x1` | 语音文件名生成 | — |
| `ex0.k0` | MsgInfo 查询 | — |
| `com.tencent.mm.storage.e9` | MsgInfo | `x0()=imgPath` |
| `im.c8` | MsgInfo 基类 | `field_imgPath` |
| `z02.g` | C2CWildFileCleaner | 扫 `wcf://image2/` |
| `z02.b` | image 清理任务 | `LIKE 'THUMBNAIL_DIRPATH://%'` |
| `kz1.a0` | CardCompatService | 名片缩略图（特例 hash） |

---

## 10. 一句话总结

> **`THUMBNAIL_DIRPATH://` 不是路径，是数据库占位符。**
> 微信用 `md5("SERVERID://" + msgSvrId)` 作为文件名，把 `"THUMBNAIL_DIRPATH://th_" + md5` 存进 `ImgInfo2.thumbImgPath`；
> 读取时由 `wb0.b.tj()/oj()` 剥前缀，`com.tencent.mm.sdk.platformtools.k1.a()` 拼成
> **`/data/user/0/com.tencent.mm/MicroMsg/<32hex>/image2/<md5[0:2]>/<md5[2:4]>/th_<md5>`**。
> 下载：`ja0.g.K7()` 构造 `x90.d` → `ja0.g.J7()`（suspend）→ `ky.b0.Pi()` → `z.U7()` → `n1.u()` → `CdnManager.startC2CDownload`，
> 落盘路径 `<thumbPath>_temp`，完成后改名。独立模块若需强制下载，走非 suspend 的 `n1.u(capturedTask)`。
