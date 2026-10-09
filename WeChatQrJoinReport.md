# 微信 8.0.78 (3180) — 聊天图片长按「识别图中二维码」→ 加群 全链路逆向报告

> **v3 · 已完成二次复检（Smali 级交叉验证）**
>
> 目标 APK：微信 8.0.78 (3180)　工具：LSPilot DexKit / jadx / baksmali
> 配套代码：`WeChatQrJoin.kt`（独立 Kotlin Xposed 模块，不依赖 LSPilot）
> 标记：✅ = Smali 逐行验证　⚠️ = 需运行时确认

---

## 0. 本轮复检的 4 项修正

| # | 断言 | 复检后 |
|---|---|---|
| 1 | `wk5.g0.h()` 的 enableScan 判断 | ✅ Smali `iget-boolean b` / `if-nez→cond_6` / `return-void`：**`b==false` 直接 return** |
| 2 | `wk5.g0.<init>` 参数顺序 | ✅ Smali `p1→a`(Activity) `p2→b`(boolean) `p3→c`(String) 完全吻合 |
| 3 | **UI 侧 `wk5.n` 的实现类** | ✅ **新定位：`com.tencent.mm.ui.chatting.gallery.w6`**（`implements wk5.n`），是链路第⑤步真正入口 |
| 4 | **单码时的自动触发点** | ✅ **修正**：`w6.a()` 里 `g.k==2` 且仅 1 个码 → 直接 `j3.j(bean.e, d8(bean.d), 1000L)`；`wk5.g0.b()` 在这之后 |

---

## 1. 完整链路（12 步）

```
① 长按图片 →「识别图中二维码」
   com.tencent.mm.ui.chatting.gallery.m7.onMMMenuItemSelected(MenuItem, int)
     └─ new l7(imageGalleryUI, z)        ← l7 implements com.tencent.mm.plugin.scanner.b0

② 发起识别
   ImageGalleryUI.K7(int type, View, String path, Bitmap, boolean retry, wk5.n cb)
     └─ if (type != 2) return                    ← ✅ const/4 v1,0x2 / if-ne
     └─ F2.h(view, D/*msgId*/, E/*talker*/, path, bitmap, true, 2, retry, u3)
        └─ wk5.e0.invoke()                       ← 组装 wk5.t + 截屏/裁剪
           └─ wk5.g0.c(wk5.t, cb)               ← ★ doScanCode

③ 抛事件给解码引擎
   new RecogQBarOfImageFileEvent()   载荷 fm.hq
     .g.a=sessionId  .g.b=imgPath  .g.c=bitmap  .g.e=true  .g.f=type
     .g.g=是否截屏   .g.h="chat-"+msgId  .g.j=1
     .g.i=ScanCodeInfo(群名, 群ticket)          ← ★★ 加群上下文
     .e()

④ 解码引擎异步解码（scanner 插件）
   ├─ 成功 → RecogQBarOfImageFileResultEvent  载荷 fm.jq  .g.k==2
   │        └─ ImageScanCodeManager$mScanQRCodeResultEventListener$1
   │           (__eventId=0x30685fd7)
   │           └─ wk5.z.invoke() → 取回 wk5.t → 回调所有 wk5.n
   └─ 失败 → RecogQBarOfImageFileFailedEvent    载荷 fm.iq

⑤ ★UI 侧回调 = com.tencent.mm.ui.chatting.gallery.w6.a(event)
   ├─ 校验 z6.i(J2(),false).equals(event.g.a)  否则 "not same filepath"
   ├─ g0Var.n = event.g.n
   ├─ D2.a = s6.a.a(event)          全部 ImageQBarDataBean
   ├─ D2.m = imgPath ; D2.n = String.valueOf(msg.F0())
   ├─ G2.g(2,1)                     ImageScanButtonStatusManager
   └─ if (event.g.k == 2) {
        if (D2.a.size() == 1) {                  ← ★★ 单码直接走，无需用户操作
          bean = D2.a.get(0); D2.c=bean; D2.b=null;
          j3.j(bean.e, d8(bean.d), 1000L);     ← ★★★ 真正触发点
        } else {
          list = c0.a(ui, ui.b8(...), D2.a, 0).d
          if (list.size()==1) { D2.b=D2.c=list.get(0); j3.j(...); }
          else { D2.b=null; D2.c=null; }        ← 0 或 >1 个：等用户在遮罩上点
        }
      }

⑥ 用户点选 / 单码已自动
   com.tencent.mm.plugin.scanner.c0.b(Context, View, ArrayList, int, b0)
     ("MicroMsg.ScanCodeViewHelper", "handleCode  dataList:")
     └─ b0.b(bean, list) → com.tencent.mm.ui.chatting.gallery.l7.b(bean, list)
          ├─ E2 = bean.d                       ← codeString
          ├─ F2.b(D2, bean, v3)                ← ★★ 调 wk5.g0.b()
          └─ d0.c(...) 埋点

⑦ ★触发 DealQBarStrEvent（自动进群入口）
   wk5.g0.b(wk5.l0, ImageQBarDataBean, wk5.o)
     ├─ h[codeString] += o ; g[codeString] = l0
     └─ new DealQBarStrEvent().e()   载荷 fm.v3（见 §3.4）

⑧ 全局监听器接住
   com.tencent.mm.plugin.scanner.model.s
     (ExternRequestDealQBarStrHandler$2.callback, __eventId=0x71ab5ba7)
     ├─ s.i = v3.a  (codeString) ; s.m = v3.b (Activity)
     ├─ ScanIdentifyReportInfo(m,n,o)；bundle: result_image_width/height/normalize_rect/path/source=2
     ├─ if (s.m instanceof MMActivity) 注册生命周期观察者
     └─ ↓ v74.v (QBarStringHandler)

⑨ QBarStringHandler 分发
   v74.v.g(Activity, codeString, scanUIScene, source, getA8KeyScene, processOfflineScan,
           networkStatus, isFromScanUI, codeType, codeVersion, redirect, view, bundle, reportInfo, kc0)
     Log: "[handleCode-dealQBarString] info ： dealQBarString %s, scanUIScene: %d, source:%d, …"
     ├─ "weixin://qr/<x>" → x+"@qr" → v74.v.a() → s1 搜联系人
     └─ 其他 URL → com.tencent.mm.modelsimple.k0（NetSceneGetA8Key, type 106）

⑩ geta8key 解析 → 群 username + ticket
   com.tencent.mm.modelsimple.k0
     └─ v74.v.onSceneEnd(errType, errCode, errMsg, scene)
        └─ v74.v.l(v36)   ← 打开群资料页 / 直接进群

⑪ ★点「加入群聊」/「申请加群」
   pe5.f.j(chatRoomName, memberList, ticket, localHistoryInfo)
     → ln.a.j → new qn.m(...) = NetSceneAddChatRoomMember
        CGI /cgi-bin/micromsg-bin/addchatroommember  cmd 0x78 / resp 0x24
     经 com.tencent.mm.roomsdk.model.factory.c
        .a() 直接发  |  .c(...) 先弹确认框

⑫ 服务端确认 → oplog/sync → 本地建会话
   b41.u1.N(room, self, pc5.zz memberList, status, oldVer, newVer, owner, sso.a, replace, z2, qrCodeAccessType)
     "ChatroomManagerIndependentQR sync AddChatroomMember room:%s, qrCodeAccessType: %d"
     "chatroomJoinInfo source:ModContact room:%s AddChatRoomScene:%d JoinTime:%d"
```

---

## 2. 识别用的方法 / 参数（重点）

### 2.1 `ImageScanCodeManager` = **`wk5.g0`**

```java
public final class g0 {
    public static final s s = new s((i) null);       // 伴生对象
    public final Activity a;      // ★ 构造参数1：宿主 Activity
    public final boolean b;       // ★ 构造参数2：enableScan 总开关
    public final String c;        // ★ 构造参数3：talker
    public final HashMap d;       // path -> wk5.t        正在解码
    public final HashMap e;       // path -> List<wk5.n>  结果回调
    public final HashMap f;       // path -> wk5.u        结果缓存
    public final HashMap g;       // codeString -> wk5.l0
    public final HashMap h;       // codeString -> List<wk5.o>
    public Long i;  public long j;  public boolean k;  public boolean l;
    public long m;  public l n;     public boolean o;
    public final ImageScanCodeManager.mScanQRCodeResultEventListener.1   p;
    public final ImageScanCodeManager.mScanQRCodeFailEventListener.1     q;
    public final ImageScanCodeManager.mNotifyDealQBarStrResultListener.1 r;

    // ✅ Smali：<init>(Activity, boolean, String)
    public g0(Activity activity, boolean z, String str) {
        o.h(activity, "context");
        this.b = true;                       // 先置 true
        this.d = new HashMap(); this.e = new HashMap();
        this.f = new HashMap(); this.g = new HashMap(); this.h = new HashMap();
        v vVar = v.d;
        this.p = new ImageScanCodeManager$mScanQRCodeResultEventListener$1(this, vVar);
        this.q = new ImageScanCodeManager$mScanQRCodeFailEventListener$1(this, vVar);
        this.r = new ImageScanCodeManager$mNotifyDealQBarStrResultListener$1(this, vVar);
        this.a = activity;  this.b = z;  this.c = str;
        Log.i("MicroMsg.ImageScanCodeManager", "scanCode enableScan: %b, talker: %s", …);
        if (z) { this.p.alive(); this.q.alive(); this.r.alive(); }   // ★ 只在 true 时注册
    }
}
```

**创建点**：`ImageGalleryUI.initView` → `this.F2 = new g0(getContext(), true, talker)`；
新图库 `BaseQRCodeScanComponent.<init>` → `this.i = new g0(appCompatActivity, true, "")`。

**`ImageGalleryUI` 相关字段**（Smali 字段表逐一核对）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `D` | `J` | **msgId** |
| `E` | `String` | **talker** |
| `F2` | `Lwk5/g0;` | **ImageScanCodeManager** |
| `D2` | `Lwk5/l0;` | 结果聚合 |
| `E2` | `String` | codeString |
| `u3` | `Lwk5/n;` | **识别回调**（实现类 `w6`） |
| `v3` | `Lwk5/o;` | 事件回调 |
| `h3`/`f3`/`g3` | `Long` | 发起时间 / sessionId / decode耗时 |
| `G2` | `Lwk5/m;` | ImageScanButtonStatusManager |
| `M3` | `Ld64/d9;` | 扫描状态机 |
| `L1` | `MultiCodeMaskView` | 多码遮罩 |
| `W3` | `I` | 当前 msg 索引 |

### 2.2 ★「开始识别」`h(...)`

```java
public final void h(View view, long j, String str, String str2, Bitmap bitmap,
                    boolean z, int i, boolean z2, n nVar) {
    if (this.b) {                                    // ✅ enableScan
        if (str2 == null || str2.length() == 0) return;
        s.a(s, new e0(this, view, j, str, str2, bitmap, z, i, z2, nVar));
    }
}
```

**Smali 逐字**：
```smali
iget-boolean v0, v12, Lwk5/g0;->b:Z
if-nez v0, :cond_6
return-void
:cond_6
if-eqz p5, :cond_11
invoke-interface/range {p5 .. p5}, Ljava/lang/CharSequence;->length()I
if-nez v0, :cond_f
goto :goto_11
:cond_11
:goto_11
const/4 v0, 0x1
:goto_12
if-eqz v0, :cond_15
return-void
:cond_15
sget-object v13, Lwk5/g0;->s:Lwk5/s;
new-instance v14, Lwk5/e0;
invoke-direct/range {v0 .. v11}, Lwk5/e0;-><init>(Lwk5/g0;Landroid/view/View;JLjava/lang/String;Ljava/lang/String;Landroid/graphics/Bitmap;ZIZLwk5/n;)V
invoke-static {v13, v14}, Lwk5/s;->a(Lwk5/s;Lr96/a;)V
return-void
```

| # | 参数 | 类型 | 含义 |
|---|---|---|---|
| 1 | `view` | `View` | 锚点（算裁剪区/挂 dialog） |
| 2 | `j` | `long` | **msgId**（`-1`=非聊天消息） |
| 3 | `str` | `String` | 备用 path（旧图库传 talker，新图库传 `""`） |
| 4 | `str2` | `String` | **imgPath 图片路径**（空则 return） |
| 5 | `bitmap` | `Bitmap` | 图片（null→截屏） |
| 6 | `z` | `boolean` | `getCodePosition` |
| 7 | `i` | `int` | **recognizeType，必须 2** |
| 8 | `z2` | `boolean` | `retry` |
| 9 | `nVar` | `wk5.n` | 结果回调 |

**三个调用点（已验证）**：
```java
// ① 旧图库 ImageGalleryUI.K7 —— 只有 recognizeType==2 才识别
public final K7(int i, View view, String str, Bitmap bitmap, boolean z, wk5.n nVar) {
    if (i != 2) return;                              // const/4 v1,0x2 / if-ne v10,v1,:cond_42
    this.F2.h(view, this.D, this.E, str, bitmap, true, i, z, nVar);
}
// ② 新图库 BaseQRCodeScanComponent.M4 —— 只有路径
public void M4(String str) {
    if (((HashMap) this.g).containsKey(str)) return;
    View view = this.d != null ? this.d.getView() : null;
    if (this.d != null) this.d.a();
    this.i.h(view, -1L, "", str, null, true, 2, true, new gq5.g(view, this, str));
}
// ③ 新图库 BaseQRCodeScanComponent.R1 —— 带 bitmap
public void R1(k kVar, Bitmap bitmap, h hVar) {
    String c = kVar.g().c();
    View view = this.d != null ? this.d.getView() : null;
    if (this.d != null) this.d.a();
    this.i.h(view, -1L, "", c, bitmap, true, 2, true, new gq5.h(view, this, g, c, kVar, hVar));
}
```### 2.3 `wk5.e0.invoke()` — 组装识别参数

```java
public Object invoke() {
    g0 g0Var = this.d;
    f0 f0Var = this.e;              // anchor view
    long j      = this.f;            // msgId
    String str  = this.g;
    String str2 = this.h;            // imgPath
    Bitmap bitmap2 = this.i;
    boolean z3 = this.m;             // getCodePosition
    int i     = this.n;              // recognizeType
    boolean z4 = this.o;             // retry
    n nVar2   = this.p;

    Point point = bitmap2 != null ? new Point(bitmap2.getWidth(), bitmap2.getHeight()) : null;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    x.I(str2, options);                                // 只读图片头拿尺寸
    Point point2 = new Point(options.outWidth, options.outHeight);

    t tVar = new t(System.currentTimeMillis(), j, str2, bitmap2, i, str);   // ★ 组装
    // ... 计算 z（verticalLong / getCodePosition）；tVar.g = z ; tVar.i = z4 ...
    Log.i("MicroMsg.ImageScanCodeManager",
      "scanCode recognizeType: %s, retry: %s, session: %s, getCodePosition: %b, verticalLong: %s, " +
      "imageSize: %s, imagePath: %s, bitmap: %s, bimapSize: %s", …);

    if (g0Var.a(tVar)) {                     // 命中缓存 g0.f
        // u.X(new v(nVar, uVar)) 直接回调，不再解码
        return f0.a;
    }

    if (i != 1) {
        if (i != 2)             { tVar.d = null; g0Var.c(tVar, nVar); }
        else if (!z5 || !tVar.g){ tVar.d = null; g0Var.c(tVar, nVar); }
        else if (tVar.i)         { tVar.d = null; g0Var.c(tVar, nVar); }
        else { tVar.d = g0Var.d(tVar, f0Var, bitmap2, point2);    // 截屏/裁剪
               tVar.j = !o.c(r0, bitmap2);
               g0Var.c(tVar, nVar); }
    } else if (!tVar.g)         { tVar.d = null; g0Var.c(tVar, nVar); }
    else if (z5) {                                                     // ★ 延迟截上半屏
        t0.d.l(new r(new f0(tVar, g0Var, f0Var, point2, bitmap2, nVar)), 100L,
               "ImageScanCodeManagerTask");
    } else                        { tVar.d = null; g0Var.c(tVar, nVar); }
    return f0.a;
}
```

`wk5.g0.a(t)` 缓存判定：`tVar != null && tVar.e == 1 && !tVar.i`（type==1 且非重试才走缓存）

### 2.4 ★「发起识别」`c(wk5.t, wk5.n)` — doScanCode

```java
public final void c(t tVar, n nVar) {
    u.X(new x(nVar, tVar));
    StringBuilder sb = new StringBuilder("doScanCode from decoder msgId: ");
    sb.append(tVar.b); sb.append(", talker: "); sb.append(tVar.f);
    sb.append(" recognizeType:"); sb.append(tVar.e);
    sb.append(", path:");          sb.append(tVar.c);
    sb.append(", screenShot: ");   sb.append(tVar.h);
    sb.append(", bitmap: ");      sb.append(tVar.d != null ? tVar.d.isRecycled() : null);
    Log.i("MicroMsg.ImageScanCodeManager", sb.toString());

    String str3 = tVar.c;
    if (y8.J0(str3)) return;                          // path 为空
    /* 挂回调到 this.e[path]；若 this.d[path] 已存在 → "already decoding and ignore" */
    this.d.put(str3, tVar);

    RecogQBarOfImageFileEvent ev = new RecogQBarOfImageFileEvent();
    hq hqVar = ev.g;
    hqVar.a = tVar.a;                                // sessionId
    hqVar.b = str3;                                  // imgPath
    hqVar.c = tVar.d;                                // bitmap
    hqVar.e = true;
    hqVar.f = tVar.e;                                // recognizeType
    if (tVar.h && tVar.d != null) {
        if (!tVar.d.isRecycled()) {
            hqVar.g = true;
            hqVar.h = "chat-" + tVar.b;               // ★ tag
            hqVar.j = 1;
            if (tVar.e == 2 && tVar.b >= 0) {         // ★★ 只有这里组 ScanCodeInfo
                e9 n     = k0.F0.n(tVar.f /*talker*/, tVar.b /*msgId*/);
                String xml = ((c8) n).G;
                if (xml != null && xml.length() != 0) {
                    e info = new e(); info.fromXml(xml);
                    r k = info.k();
                    if (k != null && k.j() != null && k.j().length() != 0) {
                        scanCodeInfo = new ScanCodeInfo(k.j(), k.k());
                    }
                }
                hqVar.i = scanCodeInfo;               // ★★ 加群上下文
            }
            ev.e();
        }
    }
    hqVar.g = false;                                 // 非截屏图分支
    hqVar.h = "chat-" + tVar.b;
    hqVar.j = 1;
    if (tVar.e == 2) {
        e9 n2 = k0.F0.n(tVar.f, tVar.b);
        String xml = ((c8) n2).G;
        if (xml != null && xml.length() != 0) { /* 同上取 ScanCodeInfo */ }
        hqVar.i = scanCodeInfo;
    }
    ev.e();
}
```

### 2.5 数据结构完整字段表

#### `wk5.t`（识别参数对象）

```java
// <init>(JJLjava/lang/String;Landroid/graphics/Bitmap;ILjava/lang/String;)V
//  = (sessionId, msgId, imagePath, bitmap, recognizeType, talker)
.field public final a:J                       // sessionId = System.currentTimeMillis()
.field public final b:J                       // msgId
.field public final c:Ljava/lang/String;      // imagePath
.field public d:Landroid/graphics/Bitmap;     // 实际送解码的图（可能是裁剪/截屏结果）
.field public final e:I                       // recognizeType (1 / 2)
.field public final f:Ljava/lang/String;      // talker
.field public g:Z                             // verticalLong / getCodePosition
.field public h:Z                             // screenShot
.field public i:Z                             // retry
.field public j:Z                             // 是否 recycle d
```

#### `fm.hq`（`RecogQBarOfImageFileEvent` 载荷，10 字段）

```java
.field public a:J                                             // sessionId
.field public b:Ljava/lang/String;                            // imgPath
.field public c:Landroid/graphics/Bitmap;                     // bitmap
.field public d:Ljava/util/Set;
.field public e:Z
.field public f:I                                             // recognizeType
.field public g:Z                                             // screenShot
.field public h:Ljava/lang/String;                            // "chat-<msgId>"
.field public i:Lcom/tencent/mm/modelscan/ScanCodeInfo;        // ★★ 加群上下文
.field public j:I
```

#### `fm.jq`（`RecogQBarOfImageFileResultEvent` 载荷，14 字段）

```java
.field public a:Ljava/lang/String;      // sessionId（w6.a 里与 z6.i(J2(),false) 比对）
.field public b:Ljava/util/ArrayList;
.field public c:Ljava/util/ArrayList;
.field public d:Ljava/util/ArrayList;
.field public e:Ljava/util/ArrayList;
.field public f:Ljava/util/ArrayList;
.field public g:I
.field public h:I
.field public i:Ljava/util/ArrayList;
.field public j:Ljava/util/ArrayList;
.field public k:I                      // ★ recognizeType（`2 == event.g.k`）
.field public l:Z
.field public m:Ljava/util/ArrayList;
.field public n:Lc64/l;                // 耗时 → g0.n
```

#### `fm.iq`（`RecogQBarOfImageFileFailedEvent` 载荷，3 字段）

```java
.field public a:Ljava/lang/String;      // sessionId
.field public b:I                      // recognizeType
.field public c:Z                      // 可否重试（`iqVar.c && iqVar.b==2` → 全屏再试一次）
```

#### `com.tencent.mm.modelscan.ScanCodeInfo`

```java
.field public d:Ljava/lang/String;      // 群名 / chatroom nickname
.field public e:I                       // 类型
// 构造：new ScanCodeInfo(String, int)
```

#### `wk5.n`（识别回调接口）

```java
public interface n {
    void a(RecogQBarOfImageFileResultEvent event);   // 成功
    void b(RecogQBarOfImageFileFailedEvent event);   // 失败
    void c(String codeString);                       // 发起识别（codeString=imgPath）
}
```

#### `wk5.o`（事件回调接口）

```java
public interface o {
    void a(NotifyDealQBarStrResultEvent event);      // 处理完成
}
```

#### `wk5.l0`（识别结果聚合，14 字段）

```java
.field public a:Ljava/util/ArrayList;                                    // ArrayList<ImageQBarDataBean>
.field public b:Lcom/tencent/mm/plugin/scanner/ImageQBarDataBean;        // 多码时用户选的
.field public c:Lcom/tencent/mm/plugin/scanner/ImageQBarDataBean;        // 最终选中的
.field public d:Z                            // → v3.o
.field public e:Z
.field public f:I                            // → v3.i（getA8KeyScene，-1 用默认 37）
.field public g:I                            // → v3.g（source，-1 用默认 4）
.field public h:Ljava/lang/String;           // → v3.f
.field public i:I                            // → v3.e（recognizeType）
.field public j:Landroid/os/Bundle;          // → v3.l（stat 埋点包）
.field public k:Ljava/lang/String;           // → v3.k
.field public l:Ljava/lang/String;           // → v3.j
.field public m:Ljava/lang/String;           // → v3.m
.field public n:Ljava/lang/String;           // → kc0.e（w6.a 里放 msgSvrId）
```

#### `com.tencent.mm.plugin.scanner.ImageQBarDataBean`（13 字段）

```java
.field public d:Ljava/lang/String;                                  // ★ codeString（二维码内容 URL）
.field public e:I                                                   // 码场景
.field public f:I                                                   // 图片来源
.field public g:Ljava/lang/String;
.field public h:F   .field public i:F
.field public m:I                                                   // 图片宽
.field public n:I                                                   // 图片高
.field public o:Ljava/lang/String;
.field public p:I
.field public q:Z
.field public r:Lcom/tencent/mm/plugin/scanner/CodePointRect;        // 码位置
```

---

## 3. 「加入群聊」→ 加群请求

### 3.1 加群 API：`pe5.f`（实现 `ln.a`，工厂 `un.k`）

```java
// pe5.f 接口（17 个方法）
public abstract j(String chatRoomName, List<String> memberList, String ticket, Object localHistoryInfo);
                   → com.tencent.mm.roomsdk.model.factory.a
// 实现 ln.a.j → new qn.m(...) = NetSceneAddChatRoomMember
//   CGI: /cgi-bin/micromsg-bin/addchatroommember   cmd 0x78 / resp 0x24 / flag 0x3b9aca24
```

### 3.2 `qn.m` 请求体（Smali 级验证）

```smali
req = pc5/a4 (AddChatRoomMemberRequest)
  req.f = x91/j1.i(chatRoomName)                  // 群 username（skbuffer）
  req.e = LinkedList<pc5/kq4>  (每个 .d = 成员 username)
  req.d = memberList.size()
  req.h = ticket                                // ★★ ticket
  req.i = ChatroomInfoUI$LocalHistoryInfo        // 可选（仅 UI 场景带）
  l.c   = "/cgi-bin/micromsg-bin/addchatroommember"
  l.d   = 0x78(120)  l.e = 0x24(36)  l.f = 0x3b9aca24
```

### 3.3 执行器 + 确认框

```java
// com.tencent.mm.roomsdk.model.factory.c
public void a() {                                          // ★ 直接发送
    if (this.f == null) return;
    j1.j(); j1.q().b.d(this.f);                            // mars 网络队列
    j1.j(); j1.q().b.q(this.f.getType(), this.g);          // 注册回调
}
public void c(Context ctx, String title, String msg,
              boolean z, boolean z2, DialogInterface.OnCancelListener onCancel) {
    if (this.f == null) return;
    this.e = e1.Q(ctx, title, msg, z, z2, onCancel);        // ★ 确认框
    b();
}
```

### 3.4 真实调用点：`vn.m`（ChatRoomAddContactProcess）

```java
// vn.m.b(String ticket, int confirmMsgResId)
public final void b(String str, int i) {
    String str2 = this.b;                       // 群 username
    MMActivity ctx = this.a;
    String str3 = this.c;                       // 目标成员（自己）
    if (y3.Q4(str2) && !d2.E(str2) && !s1.a(str3)) { e1.s(ctx, c(2131774503), c(2131756315)); return; }

    String self = b41.y1.u();                   // 自己的 wxid
    boolean inRoom = ...;                        // 是否已在群里
    if (inRoom) { e1.s(ctx, c(2131755216), c(2131756315)); }
    else if (str3 != null && P1 != null) {
        a j = ((vf0.e) n0.c(vf0.e.class)).bj(str2)   // 取 pe5.f
                 .j(str2, P1, str, this.d);          // ★ (room, members, ticket, localHistoryInfo)
        j.d = new b(this);
        j.c(this.a, c(2131756315), c(i), true, true, new c(j));   // ★ 确认框 → 确认后 a() 发送
    }
}
```

`vn.m` 由 `ChatroomInfoUI.onActivityResult` / `SeeRoomMemberUI.onActivityResult` 创建；
`vn.m.<init>` 中若群**不需验证**（`t1.M0() == false`）直接 `b(null, 2131755233)` 立即发；
**需验证**则先跳 `InvitationReasonEditorUI`（邀请理由，requestCode `132412`），返回后带 ticket 再调 `b(ticket, …)`。

---

## 4. `wk5.g0.b(...)` 完整源码（触发 DealQBarStrEvent）

```java
public final void b(l0 l0Var, ImageQBarDataBean imageQBarDataBean, o oVar) {
    o.h(l0Var, "codeResult");
    o.h(imageQBarDataBean, "codePointInfo");

    String str = imageQBarDataBean.d;        // ★ codeString
    if (str == null) str = "";

    HashMap hashMap = this.h;
    if (!hashMap.containsKey(str)) hashMap.put(str, new ArrayList());
    Object obj = hashMap.get(str);  o.e(obj);
    if (!((ArrayList) obj).contains(oVar)) {
        Object obj2 = hashMap.get(str);  o.e(obj2);
        ((ArrayList) obj2).add(oVar);
    }
    this.g.put(str, l0Var);

    DealQBarStrEvent ev = new DealQBarStrEvent();
    Activity activity = this.a;              // ★ 构造 g0 时传入
    v3 v = ev.g;
    v.b = activity;
    v.a = imageQBarDataBean.d;               // ★ codeString
    v.c = imageQBarDataBean.e;               // 码场景
    v.d = imageQBarDataBean.f;               // 来源
    v.i = 37;                                 // getA8KeyScene 默认 37
    int i = l0Var.f;  if (i != -1) v.i = i;
    v.g = 4;                                  // source 默认 4
    int i2 = l0Var.g; if (i2 != -1) v.g = i2;
    v.f = l0Var.h;
    v.e = l0Var.i;                            // recognizeType
    v.k = l0Var.k;
    v.j = l0Var.l;
    v.l = l0Var.j;                            // Bundle
    v.m = l0Var.m;

    kc0 kc0Var = new kc0();
    o46 o46Var = new o46();
    String str2 = this.c;                     // ★ talker
    o46Var.e = y3.s4(str2) ? 4
             : (y3.o4(str2) || d2.U(str2)) ? 2
             : d2.G(str2) ? 3 : 1;
    kc0Var.d = o46Var;
    String str3 = l0Var.n;
    kc0Var.e = str3 != null ? str3 : "";
    v.p = kc0Var;

    v.s = imageQBarDataBean.n;                // 图片高
    v.r = imageQBarDataBean.m;                // 图片宽
    v.q = imageQBarDataBean.r;                // CodePointRect
    v.o = l0Var.d;

    ev.e();                                   // ★★★ → 全局监听器 → geta8key → 加群
}
```

### `DealQBarStrEvent` 载荷 `fm.v3` 全字段（19 个）

```java
.field public a:Ljava/lang/String;         // ★ codeString
.field public b:Landroid/app/Activity;     // ★ Activity
.field public c:I                          // 码场景
.field public d:I                          // 来源
.field public e:I                          // recognizeType
.field public f:Ljava/lang/String;
.field public g:I                          // source（默认 4）
.field public h:Ljava/lang/String;
.field public i:I                          // ★ getA8KeyScene（默认 37）
.field public j:Ljava/lang/String;
.field public k:Ljava/lang/String;
.field public l:Landroid/os/Bundle;        // stat
.field public m:Ljava/lang/String;
.field public n:[B
.field public o:Z
.field public p:Lpc5/kc0;                  // 上报对象
.field public q:Lcom/tencent/mm/plugin/scanner/CodePointRect;
.field public r:I                          // 图片宽
.field public s:I                          // 图片高
```

### `pc5.kc0` / `pc5.o46`

```java
// pc5/kc0（2 字段）
.field public d:Lpc5/o46;
.field public e:Ljava/lang/String;      // = l0Var.n

// pc5/o46（6 字段）—— 这里只赋 e
.field public d:I
.field public e:I                      // ★ 会话类型：1=好友 2=群 3=公众号 4=企业微信
.field public f:Ljava/lang/String;
.field public g:Lpc5/at4;
.field public h:Lpc5/v2;
.field public i:Lpc5/yz3;
```

---

## 5. 配套类速查

| 混淆名 | 原名 / 作用 |
|---|---|
| **`wk5.g0`** | **`ImageScanCodeManager`** |
| `wk5.s` | 伴生对象（`g0.s`），`s.a(s, r96.a)` 切主线程 |
| `wk5.t` | 识别参数对象 |
| `wk5.n` | 识别结果回调接口（3 方法） |
| `wk5.o` | 事件回调接口（1 方法） |
| `wk5.l0` | 识别结果聚合（14 字段） |
| `wk5.u` | 结果缓存项（`a`=resultEvent, `b`=failedEvent, `c`=success） |
| `wk5.e0` | `h()` 的 lambda（组装 t + 裁剪） |
| `wk5.z` | 成功事件 lambda |
| `wk5.c0` | 回调派发 |
| `wk5.f0` | 延迟截屏任务（`ImageScanCodeManagerTask`, 100ms） |
| `wk5.m` | ImageScanButtonStatusManager |
| **`com.tencent.mm.ui.chatting.gallery.w6`** | **UI 侧 `wk5.n` 实现**（★本轮新定位） |
| `com.tencent.mm.ui.chatting.gallery.l7` | `plugin.scanner.b0` 实现（选中码后调 `g0.b`） |
| `com.tencent.mm.ui.chatting.gallery.q5` | `b0` 另一实现（单码） |
| `com.tencent.mm.ui.chatting.gallery.m7` | 长按菜单分发 `onMMMenuItemSelected` |
| `com.tencent.mm.plugin.scanner.c0` | ScanCodeViewHelper 多码遮罩（`handleCode`） |
| `com.tencent.mm.plugin.scanner.ScanCodeSheetItemLogic` | 扫码落地页菜单 |
| `com.tencent.mm.plugin.scanner.model.s` | DealQBarStrEvent 全局监听器 |
| `v74.v` | QBarStringHandler（`g`/`onSceneEnd`/`l`） |
| `com.tencent.mm.plugin.qrcode.model.p` | GetA8KeyRedirect（actionCode 分发） |
| `com.tencent.mm.modelsimple.k0` | NetSceneGetA8Key（type 106） |
| `pe5.f` / `ln.a` / `un.k` | ChatRoom API 接口 / 实现 / 工厂 |
| `qn.m` | NetSceneAddChatRoomMember |
| `qn.x` | NetSceneInviteChatRoomMember |
| `com.tencent.mm.roomsdk.model.factory.c` | 任务执行器（`a`=发, `c`=弹框） |
| `vn.m` | ChatRoomAddContactProcess（弹确认 → 发请求） |
| `b41.u1` | ChatroomMembersLogic（`N`=SyncAddChatroomMember 本地建会话） |

---

## 6. 复检记录（17 项，全部 ✅）

| # | 断言 | 验证方式 |
|---|---|---|
| 1 | `g0.<init>` 参数顺序 `(Activity, boolean, String)` | Smali `iput-object p1→a` / `iput-boolean p2→b` / `iput-object p3→c` |
| 2 | `g0.h()` 中 `b==false` 直接 return | Smali `iget-boolean b` / `if-nez→cond_6` / `return-void` |
| 3 | `g0.h()` 中 imgPath 为空直接 return | Smali `if-eqz p5→cond_11` + `CharSequence.length()` + `if-nez v0→cond_f` |
| 4 | `g0.h()` 全部 9 个参数顺序 | Smali `move-object/from16 v2=p1 / v3=p1(long) / v5=p4 / v6=p5 / v7=p6 / v8=p7 / v9=p8 / v10=p9 / v11=p10` |
| 5 | `e0.<init>` 签名 `(g0,View,J,String,String,Bitmap,Z,I,Z,n)` | Smali `Lwk5/e0;-><init>(...)` invoke-direct 描述符 |
| 6 | UI 侧 `wk5.n` 实现类 = `w6` | `decompile_class_methods_only` 显示 `w6 implements n` 且三方法签名完全匹配 |
| 7 | `w6.a()` 里 `2 == event.g.k` 才走单码分支 | Java `if (2 == recogQBarOfImageFileResultEvent.g.k)` |
| 8 | 单码自动触发点 `j3.j(bean.e, d8(bean.d), 1000L)` | `w6.a` 第 111 行（`imageGalleryUI4.j3.j(...)`） |
| 9 | `w6.a()` 里 `D2.m = i2`（imgPath）、`D2.n = String.valueOf(msg.F0())` | `l0Var2.m = i2` / `l0Var2.n = valueOf` |
| 10 | `l7.b()` → `F2.b(D2, bean, v3)` | Smali `invoke-virtual {p2, v1, p1, v2}, Lwk5/g0;->b(...)` |
| 11 | `g0.b()` 的 `v3.a = bean.d`（codeString） | Smali `iget-object v0, p2, ImageQBarDataBean;->d` → `iput-object v0, v2, Lfm/v3;->a` |
| 12 | `g0.b()` 的 `v3.b = this.a`（Activity） | Smali `iget-object v0, p0, Lwk5/g0;->a` → `iput-object v0, v2, Lfm/v3;->b` |
| 13 | `g0.b()` 的 `v3.i` 默认 37（0x25） | Smali `const/16 v0, 0x25` → `iput v0, v2, Lfm/v3;->i` |
| 14 | `g0.b()` 的 `v3.g` 默认 4 | Smali `const/4 v0, 0x4` → `iput v0, v2, Lfm/v3;->g` |
| 15 | `c()` 中 ScanCodeInfo 仅在 `e==2` 时构造 | `if (i == 2 && j >= 0)` |
| 16 | `pe5.f.j` 签名与返回类型 | `get_method_smali` → `j(Ljava/lang/String;Ljava/util/List;Ljava/lang/String;Ljava/lang/Object;)Lcom/tencent/mm/roomsdk/model/factory/a;` |
| 17 | `ImageGalleryUI.K7` 只有 `i==2` 才调 `F2.h` | Smali `const/4 v1,0x2` / `if-ne v10,v1,:cond_42` |

---

## 7. 踩坑清单

| # | 坑 | 说明 |
|---|---|---|
| 1 | `recognizeType` 必须 = 2 | 只有 2 才组 `ScanCodeInfo`（加群上下文），1 不会 |
| 2 | `this.a` 必须是真实 `MMActivity` | `ExternRequestDealQBarStrHandler$2` 有 `instanceof MMActivity` 判断 |
| 3 | `enableScan` 必须 true | false 时 `h()` 直接 return，三个监听器也不注册 |
| 4 | 同图不重复解码 | `c()` 里 `this.d.containsKey(path)` 直接忽略 |
| 5 | `codeString` 必须是微信能识别的码 | `weixin://qr/…` / `wxp://…` / `https://c.weixin.com/…` |
| 6 | 单码会自动触发，多码必须用户点 | `w6.a` 里 `size()==1` 直接 `j3.j(...)`；>1 弹遮罩 |
| 7 | `factory.a()` 任意线程可调 | 走 `j1.q().b.d()` mars 队列，不必主线程 |
| 8 | 群需验证时不能直接进 | `vn.m` 先跳 `InvitationReasonEditorUI`（132412）；静默需自造 ticket 或走 `pe5.f.a`（invitechatroommember） |
| 9 | addchatroommember 有频控 | cmd 0x78，建议 3~10s 随机延时 + 单日限额 |

---

## 8. 一句话总结

> 长按图片识别群码走的是 **`wk5.g0`（ImageScanCodeManager）→ `RecogQBarOfImageFileEvent` → 解码引擎 → `wk5.z` → UI 回调 `w6.a()`**，
> 单码时 `w6.a()` 延时 1s 自动触发 `j3.j(...)` → `l7.b()` → **`wk5.g0.b()`** 造 `DealQBarStrEvent` → `scanner.model.s` 接住 → `v74.v.g()` → `geta8key` 解析出群 username+ticket → `pe5.f.j()` 发 `addchatroommember`。
> 想静默自动加群，直接调 `pe5.f.j(room, [self], ticket, null).a()` 即可（跳过第⑧步之后所有 UI）。