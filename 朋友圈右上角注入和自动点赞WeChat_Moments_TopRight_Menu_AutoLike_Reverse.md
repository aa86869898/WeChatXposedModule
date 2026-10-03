# 微信「朋友圈」右上角注入三个点菜单 + 自动点赞 — 完整逆向分析

> 目标：在一个**独立的 Xposed 模块**中（不依赖 LSPilot 的 BSH 脚本），
> 1) 在朋友圈页面右上角注入一个「⋮ 三个点」菜单；
> 2) 点击后对**你自己联系人选择器勾选的联系人**所发的朋友圈自动点赞。
>
> 分析对象：本机安装的微信 `com.tencent.mm` 的 `base.apk`。
> 说明：当前微信的核心类多为**混淆名**（如 `h6`/`o2`），但方法体、字段、协议均已在下方逐条取证；混淆名是**分版本**的，用前请按第 8 节清单做一次重映射核对。

---

## 0. 结论速览（TL;DR）

| 问题 | 结论 |
|---|---|
| 朋友圈页面是哪个 Activity | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI`（旧 `SnsTimeLineUI` 已 `@Deprecated` 且只剩空壳构造，**不是**线上界面） |
| 在哪里注入右上角菜单 | hook `ImproveSnsTimelineUI.onCreateOptionsMenu(Menu)`，在 **before** 里 `menu.add(...)` + `setIcon(⋮)` + `SHOW_AS_ACTION_ALWAYS`，WeChat 会经 `mController.g0(menu)` 把它渲染到右上角 |
| 「赞」到底怎么发 | 调 `SnsLogic$SnsServer`(混淆类 `com.tencent.mm.plugin.sns.model.h6`) 的静态方法 `h6.p(对方wxid, 5, null, SnsInfo, scene)`；`opType=5` 即点赞 |
| 底层走什么网络 | 内部组 `pc5.bf6`(点赞/评论操作) + `pc5.af6`(SnsComment)，入 `NetSceneSnsComment`(混淆 `com.tencent.mm.plugin.sns.model.o2`) → CGI `/cgi-bin/micromsg-bin/mmsnscomment`，CmdId=0xD5(213) |
| 怎么枚举要赞的动态 | hook `lk4.g.W7(SnsInfo, tf5.b)` 收集当前时间线已加载的 `SnsInfo`；或 `MvvmList.d()` 取列表 |
| 怎么筛你勾选的联系人 | 每条动态 `SnsInfo.getUserName()`(= 发布者 wxid) 与你的勾选集合比对，且 `getLikeFlag()==0` 才赞 |
| 取消赞 | `h6.a(snsInfo.getSnsId())`（`cancelLiked`） |

---

## 1. 目标页面架构（朋友圈时间线）

继承链（自底向上）：

```
ImproveSnsTimelineUI
  └ ImproveSnsJankUI
      └ com.tencent.mm.plugin.secdata.ui.MMSecDataActivity
          └ com.tencent.mm.plugin.mvvmbase.BaseMvvmActivity
              └ com.tencent.mm.ui.vas.VASActivity
                  └ com.tencent.mm.ui.vas.VASActivityJava
                      └ com.tencent.mm.ui.MMActivity   <-- 自定义 ActionBar + Menu 全在这里
```

关键事实（反编译取证）：

- `ImproveSnsTimelineUI.onCreate(Bundle)`：
  ```java
  setMMTitle(2131777179);          // ``"朋友圈" 标题（资源字符串，勿用硬编码中文，直接引用/无需改）
  setActionbarColor(getResources().getColor(2131102557));
  this.s = findViewById(2131362004);   // ActionBar 容器（顶部条），按状态栏高度加 padding
  hideActionbarLine();
  ```
  → 右上角菜单属于这条顶部 ActionBar，容器 view id = **`2131362004`**（做直接 View 注入时可用）。
- `ImproveSnsTimelineUI.importUIComponents()` 注册的组件：
  `ImproveHeaderUIC, ImproveMainUIC, ImproveUnreadUIC, ..., ImproveDataUIC, d0, ...`
  其中 `ImproveHeaderUIC` 管理头部（含右上角相机/发布图标），`ImproveDataUIC`/`ImproveMainUIC` 是列表与数据。
- `ImproveSnsTimelineUI.onCreateOptionsMenu(Menu)`：
  ```java
  public boolean onCreateOptionsMenu(Menu menu) {
      boolean r = super/*VASActivity*/.onCreateOptionsMenu(menu);   // -> mController.g0(menu)
      ao5.z.a.a(this).a(d0.class).K7();
      return r;
  }
  ```
  `VASActivity.onCreateOptionsMenu` → `((MMActivity)this).mController.g0(menu)`，即**平台 Menu 会被塞进 WeChat 自定义 ActionBar** 渲染成右上角按钮。这就是注入点。

> 旧的 `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI` 反编译 + 取 Smali 确认：类标注
> `.annotation runtime Ljava/lang/Deprecated;`，**全类只有 `<init>`（仅调 `HellActivity.<init>`）**，无任何生命周期方法，无法作为线上界面。故只 hook `ImproveSnsTimelineUI` 即可（旧类可作兼容性空 hook，无害）。

---

## 2. 右上角「三个点」菜单注入

### 2.1 方案 A（推荐）：hook onCreateOptionsMenu 追加 MenuItem

原理：WeChat 的 `MMActivity` 会把 `onCreateOptionsMenu` 收到的 `Menu` 通过 `mController.g0(menu)` 同步到自定义 ActionBar。因此必须在**原方法体执行 super 之前**把菜单项加进 menu → 用 **beforeHookedMethod**（而不是 after，否则 ActionBar 已按旧 menu 渲染完，新项不显示）。

要点：
- `menu.add(0, 你的ID, 0, "自动点赞")`；
- `setIcon(三个点图标)` + `setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)` → 以图标形式固定在右上角；
- `setOnMenuItemClickListener(...)` 弹 `PopupMenu`/`Dialog`（这就是「三个点菜单」的展开内容）；
- 图标：先用框架自带更稳妥（`android.R.drawable.ic_menu_more` 或 `stat_notify_more`）；如需更精致可 `ContextCompat.getDrawable(ctx, R.drawable.xxx)` 或直接用纯白竖排三点 vector。

### 2.2 方案 B（兜底）：直接把 View 塞进 ActionBar/Header 容器

若某个 WeChat 版本对任意 MenuItem 渲染不友好，可改为 **after** `onCreate` 后，向顶部容器 `findViewById(2131362004)`（`this.s`）或其内的 Header 右侧容器 add 一个 `ImageView`（三连点），`layout_gravity=end|top`，`setOnClickListener` 弹同样的 `PopupMenu`。`ImproveHeaderUIC` 有 `actionBarView` 委托，右上角相机图标即在该区域内，把三连点作为其兄弟视图 add 进去即可。方案 A 不足以显示时再上 B。

---

## 3. 点赞 / 评论的底层机制（核心取证）

### 3.1 UI 触发链（确认「赞」到底调了哪个方法）

时间线每一项底部的「赞/评论」点击处理器（两个布局版本，逻辑一致）：

- `GalleryFooter$1.onClick`（混淆类 `com.tencent.mm.plugin.sns.ui.k3`）
- `GalleryFooterNew$5.onClick`（混淆类 `com.tencent.mm.plugin.sns.ui.y3`）

反编译关键分支：
```java
if (snsInfo.getLikeFlag() == 0) {                 // 当前未赞
    if (snsInfo.isExtFlag()) {                    // 特殊/转发态（少见）
        h6.m(snsInfo, 1, "", 0L, "", false, scene);          // 评论式路由
    } else {
        String to = ((ua) snsInfo).field_userName; // 动态发布者 wxid
        h6.p(to, 5, (lj4.a) null, snsInfo, scene); // <== 正常点赞：opType=5
    }
    snsInfo.setLikeFlag(1);
    p4.Jj().w4(snsInfo);                          // 写回并触发列表刷新
    ...
} else {                                          // 已赞 -> 取消
    snsInfo.setLikeFlag(0);
    p4.Jj().w4(snsInfo);
    h6.a(snsInfo.getSnsId());                     // cancelLiked
}
```

结论：**普通动态点赞 = `h6.p(发布者wxid, 5, null, snsInfo, 场景int)`**。
`scene` 仅为上报 `comment_scene` 用，是否“标准”不影响服务器受理，默认 0 或抓一次真实值即可。

### 3.2 `SnsLogic$SnsServer`（混淆 `com.tencent.mm.plugin.sns.model.h6`）关键方法

| 混淆签名（可直接反射） | 作用 | 备注 |
|---|---|---|
| `p(String,int,lj4.a,SnsInfo,int)` | `sendCommentToStranger` **点赞/陌生人评论** | `if(i!=3 && i!=5) return;` **i=5 即赞**，i=3 陌生人评论 |
| `m(SnsInfo,int,String,long,String,boolean,int)` | `sendComment`（评论式路由，extFlag 用） | 内部 opType=1 |
| `a(String)` | `cancelLiked` 取消赞 | 参数 = `snsInfo.getSnsId()` |
| `n(...)`/`o(...)` | 普通评论增强版 | 暂不用 |

`h6.p(...)` 反编译内部（节选，确认字段）：
```java
af6 cmt = new af6();
cmt.n = (aVar==null?"":aVar.d());           // 内容（赞为空）
cmt.m = (int)(System.currentTimeMillis()/1000);
cmt.f = y1.m();                             // 自己昵称md5
cmt.d = y1.u();                             // 自己 wxid
cmt.i = i2;                                 // 场景int
cmt.g = z1.e(str);                          // 对方昵称（由wxid取）
cmt.e = str;                                // 对方 wxid  <== 要赞的人
cmt.h = i;                                  // opType = 5 (赞)
d(cmt, aVar);                               // 内容填充
bf6 op = new bf6();
op.d = ((ua)snsInfo).field_snsId;           // 目标动态 id (long)
op.f = cmt;
op.g = new af6();                           // 空 reply-to
// 插入本地“评论/赞”库，再入异步队列：
p4.Ej().insert(v1Var(...));
p4.Bj().a(g, op, i2);                        // SnsAsyncQueueMgr.addComment(...)
```

### 3.3 网络层 `NetSceneSnsComment`（混淆 `com.tencent.mm.plugin.sns.model.o2`）

- 请求体 `pc5.bf6`（操作）内含 `pc5.af6`（SnsComment）。
- 构造参数可见 CGI 常量化：`/cgi-bin/micromsg-bin/mmsnscomment`，`CmdId=0xD5(213)`、`funcId=0x64(100)`，RR=`0x3b9aca64`。
- `request` 结构 `pc5.vf6.d = bf6`；RR `pc5.wf6`。响应 `onGYNetEnd` 解析 `FeedId / CommentId / CommentUserList / EmojiMd5` 并 `mergeToDb`。
- `af6`(SnsComment) proto 字段号映射（来自 `op()` 写字段顺序）：
  | 字段名 | proto# | 含义 |
  |---|---|---|
  | d | 1 | 自己 wxid |
  | e | 2 | 对方 nick |
  | f | 3 | 内容 |
  | g | 4 | 对方 nick |
  | h | 5 | **opType/来源（赞=5）** |
  | i | 6 | 场景/类型 int |
  | o | 9 | commentId |
  | r | 12 | createTime(long) |
  | s / t | 13/14 | 时间戳 / 包装 |
  | ... | 16..23 | 扩展 |

> 因此**一次真正的朋友圈点赞，本质就是向 `mmsnscomment` 投递一条 `opType=5`、内容为空、指向目标 `field_snsId` 的 SnsComment**。

---

## 4. 如何枚举要点赞的动态

时间线数据流（反编译 `ImproveDataUIC` / `ImproveMainUIC`）：

- 列表框架用 WeChat 自研 MVVM：`com.tencent.mm.plugin.mvvmlist.MvvmList`。
  `ImproveDataUIC.J7()` = `getLiveList()` → `MvvmList`。
- `MvvmList.d()` = `getData()`：返回 `ArrayList`，元素为动态条目的 `bean`。
- 单个条目 bean `jk4.p`，`jk4.p.Z0()` = `getSnsInfo()` → **`com.tencent.mm.plugin.sns.storage.SnsInfo`**（这就是点赞要用的对象）。
- 数据从库层模型 `lk4.g`（BaseMvvmDB 的 model，字段即 LiveDB）组装，关键绑定方法
  `lk4.g.W7(SnsInfo, tf5/b)`：**每条动态进入列表时都会带着它的 `SnsInfo` 调用一次**。

`SnsInfo` 公开方法（反编译确认，均可用）：
- `getUserName()` → 发布者 wxid（用于匹配你勾选的联系人）
- `getSnsId()` → String 形式的动态 id（取消赞用）
- `getLikeFlag()` → `0=未赞`、`1=已赞`
- `isAd()` → 是否广告（应跳过）
- `isExtFlag()` → 特殊/转发态（决定走 `p` 还是 `m` 路由）

### 4.1 两种枚举策略

**策略 1（推荐，最稳）**：hook `lk4.g.W7(SnsInfo, tf5.b)`，把每条进入列表的 `SnsInfo` 收集进一个 `Map<snsId, SnsInfo>`。
优点：不依赖 Activity/组件管理器反射、随滚动自动覆盖已加载全部动态、抗版本小改动（只依赖一个方法两个参数类型）。

**策略 2**：点击菜单时，反射拿 `ImproveDataUIC.J7()`→`MvvmList`→`d()` 的 `ArrayList`，逐个 `Z0()` 取 `SnsInfo`。
缺点：需要先拿到 `ImproveDataUIC` 实例（WeChat UIComponent 由 `ao5.z.a` 管理，反射链路长、较脆）。

下面代码采用策略 1。

---

## 5. 联系人选择器如何对接

你的模块里维护一个勾选集合（建议持久化）：
```java
Set<String> selectedWxids;   // 来自你自己的联系人选择器，元素为联系人 wxid
```
判定规则（在自动点赞循环里）：
```java
if (info.getLikeFlag() != 0) continue;      // 已赞，跳过（防重复）
if (info.isAd())          continue;         // 广告，跳过
String poster = info.getUserName();          // 发布者 wxid
if (selectedWxids.isEmpty() || selectedWxids.contains(poster)) { likeIt(info); }
```
`selectedWxids.isEmpty()`=全赞；非空=只赞勾选的人。勾选名单位置建议放在三个点菜单的第一项「选择点赞联系人」，调到你自己已有的选择器 Activity。

---

## 6. 独立 Xposed 模块完整实现（Java，可直接用）

> 依赖：LSPosed/Xposed 的 `XposedBridge`、`XposedHelpers`、`XC_MethodHook`、`IXposedHookLoadPackage`。
> 这些混淆名（`h6`/`lk4.g`/`tf5.b`/`lj4.a`）是**本版本**的类名；升级微信后需按第 8 节重新核对。

```java
package com.yourmod.moments;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import com.tencent.mm.plugin.sns.storage.SnsInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MomentsAutoLikeHook implements IXposedHookLoadPackage {

    private static final String TAG = "MomentsAutoLike";
    private static final int MENU_ID = 0x990011;   // 三个点菜单项 id

    // ---- 状态 ----
    private static ClassLoader sCl;                 // 宿主 classloader
    private static Activity sTimeline;              // 朋友圈 Activity 实例
    private static final LinkedHashMap<String, SnsInfo> sLive =
            new LinkedHashMap<>();                  // 已加载的动态 snsId -> SnsInfo
    private static volatile boolean sRunning;       // 自动点赞开关
    private static int sScene = 0;                  // comment_scene（可被实测覆盖）
    private final Handler mH = new Handler(Looper.getMainLooper());
    // 由你自己的联系人选择器写入：
    public static volatile Set<String> sSelectedWxids;   // 空 = 全赞

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        if (!"com.tencent.mm".equals(lpp.packageName)) return;
        sCl = lpp.classLoader;

        // ============ 1) 注入右上角三个点菜单 ============
        final Class<?> timelineCls = findClass(
                "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI", sCl);

        // 记录 Activity 实例
        findAndHookMethod(timelineCls, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                sTimeline = (Activity) p.thisObject;
            }
        });

        // before 注入（让 super.onCreateOptionsMenu -> mController.g0 能渲染我们的项）
        findAndHookMethod(timelineCls, "onCreateOptionsMenu", Menu.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Menu menu = (Menu) p.args[0];
                MenuItem item = menu.add(0, MENU_ID, 0, "自动点赞");
                try {
                    item.setIcon(android.R.drawable.ic_menu_more); // 三个点图标
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                } catch (Throwable t) {
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IFROOM);
                }
                item.setOnMenuItemClickListener(mi -> { showThreeDotMenu(); return true; });
            }
        });

        // ============ 2) 枚举：收集时间线已加载的 SnsInfo ============
        findAndHookMethod("lk4.g", sCl, "W7", SnsInfo.class, "tf5.b", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                SnsInfo info = (SnsInfo) p.args[0];
                if (info != null) sLive.put(info.getSnsId(), info);
            }
        });

        // ============ 3) (可选) 抓真实 comment_scene ============
        findAndHookMethod("com.tencent.mm.plugin.sns.model.h6", sCl, "p",
                String.class, int.class, "lj4.a", SnsInfo.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                sScene = (int) p.args[4];
                XposedBridge.log(TAG + " real h6.p scene=" + p.args[4]
                        + " op=" + p.args[1] + " to=" + p.args[0]);
            }
        });
    }

    // ============ 三个点菜单内容 ============
    private void showThreeDotMenu() {
        if (sTimeline == null) return;
        PopupMenu pm = new PopupMenu(sTimeline, findTopRightAnchor(sTimeline));
        pm.getMenu().add(0, 1, 0, "选择点赞联系人");
        pm.getMenu().add(0, 2, 0, "开始自动点赞");
        pm.getMenu().add(0, 3, 0, "停止");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openYourContactPicker(); return true;  // 调你的选择器，回写 sSelectedWxids
                case 2: startAutoLike();        return true;
                case 3: sRunning = false;       return true;
            }
            return false;
        });
        pm.show();
    }

    private View findTopRightAnchor(Activity act) {
        try {
            View decor = act.getWindow().getDecorView();
            return decor.findFocus() != null ? decor.findFocus() : decor;
        } catch (Throwable ignore) { return null; }
    }

    private void openYourContactPicker() { /* TODO: 启动你的选择器，回写 sSelectedWxids */ }

    // ============ 自动点赞 ============
    private void startAutoLike() {
        if (sRunning) { return; }
        final List<SnsInfo> targets = new ArrayList<>();
        for (SnsInfo info : sLive.values()) {
            if (info.getLikeFlag() != 0) continue;    // 已赞
            if (info.isAd())          continue;       // 广告
            String poster = info.getUserName();
            Set<String> sel = sSelectedWxids;
            if (sel == null || sel.isEmpty() || sel.contains(poster)) targets.add(info);
        }
        if (targets.isEmpty()) {
            XposedBridge.log(TAG + " no target");
            return;
        }
        sRunning = true;
        final Class<?> h6 = findClass("com.tencent.mm.plugin.sns.model.h6", sCl);
        doLikeChain(0, targets, h6);
    }

    private void doLikeChain(final int idx, final List<SnsInfo> targets, final Class<?> h6) {
        if (!sRunning || idx >= targets.size()) { sRunning = false; return; }
        final SnsInfo info = targets.get(idx);
        try {
            if (info.isExtFlag()) {
                // 特殊态：评论式路由 opType=1
                callStaticMethod(h6, "m", info, 1, "", 0L, "", Boolean.FALSE, sScene);
            } else {
                // 正常：点赞 opType=5，第三参 lj4.a 传 NULL
                callStaticMethod(h6, "p", info.getUserName(), 5, null, info, sScene);
            }
            info.setLikeFlag(1);          // 乐观置位，避免同轮重复
            XposedBridge.log(TAG + " liked " + info.getSnsId() + " by " + info.getUserName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " like fail: " + t);
        }
        // 节流：3.5~6s 随机，降低风控/频率告警；WeChat 本身即时发送，队列发网络
        long delay = 3500 + ThreadLocalRandom.current().nextInt(2500);
        mH.postDelayed(() -> doLikeChain(idx + 1, targets, h6), delay);
    }
}
```

> 线程说明：上面用主线程 Looper 逐个投递。`h6.p` 内部只做「本地库 insert + 入 `SnsAsyncQueueMgr` 队列」，真正的 `mmsnscomment` 网络由队列在工作线程跑，主线程调用安全（与微信自己点击「赞」所在线程一致）。

---

## 7. 关联系一图（钩子/反射符号速查）

| 用途 | 类型 | 符号（本版本混淆名） | 方法/字段 |
|---|---|---|---|
| 注入菜单 | Hook | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI` | `onCreateOptionsMenu(Menu)` / `onCreate(Bundle)` |
| 枚举动态 | Hook | `lk4.g` | `W7(com.tencent.mm.plugin.sns.storage.SnsInfo, tf5.b)` |
| 抓场景 | Hook | `com.tencent.mm.plugin.sns.model.h6` | `p(String,int,lj4.a,SnsInfo,int)` |
| **点赞** | 反射调静态 | `com.tencent.mm.plugin.sns.model.h6` | `p(对方wxid, 5, null, SnsInfo, scene)` |
| 特殊态点赞 | 反射调静态 | 同上 | `m(SnsInfo, 1, "", 0L, "", false, scene)` |
| 取消赞 | 反射调静态 | 同上 | `a(String snsId)` |
| 列表条目→对象 | 反射 | `jk4.p` | `Z0() → SnsInfo` |
| 列表容器 | 反射 | `com.tencent.mm.plugin.mvvmlist.MvvmList` | `d() → ArrayList` |
| 动态对象 | 反射 | `com.tencent.mm.plugin.sns.storage.SnsInfo` | `getUserName()/getSnsId()/getLikeFlag()/isAd()/isExtFlag()` |
| 点评/赞请求体 | — | `pc5.af6`(SnsComment) / `pc5.bf6`(操作) | h(popType=5) / d(field_snsId) |
| 网络场景 | — | `com.tencent.mm.plugin.sns.model.o2`(=NetSceneSnsComment) | CGI `mmsnscomment` Cmd=0xD5 |
| 单例容器 | 反射 | `com.tencent.mm.plugin.sns.model.p4`(=SnsCore) | `Bj()`=异步队列, `Jj()`=信息库, `Ej()`=评论库 |

---

## 8. 二次核查清单（已逐条取证的事实 / 残留不确定性）

### 8.1 已确认（有反编译/ Smali 证据）
1. ✅ 朋友圈线上界面 = `ImproveSnsTimelineUI`；`SnsTimeLineUI` 为 `@Deprecated` 空壳（仅 `<init>`）。—— 依据：`get_class_smali` 显示全类 19 行，只有构造。
2. ✅ 菜单注入链路成立：`ImproveSnsTimelineUI.onCreateOptionsMenu` → `super=VASActivity.onCreateOptionsMenu` → `mController.g0(menu)`（菜单进自定义 ActionBar）。—— 依据：`decompile_method(VASActivity.onCreateOptionsMenu)`。
3. ✅ 点赞入口= `h6.p(wxid,5,null,SnsInfo,scene)`：`h6.p` 内 `if(i!=3 && i!=5) return;` `cmt.h=i`；两处 UI(`y3.onClick`/`k3.onClick`)在 `getLikeFlag()==0 && !isExtFlag()` 时正是 `h6.p(field_userName,5,null,snsInfo,scene)`。
4. ✅ 取消赞 = `h6.a(String)`（`cancelLiked`），参数 `snsInfo.getSnsId()`。
5. ✅ 点赞落点 = `NetSceneSnsComment`(`o2`) → CGI `/cgi-bin/micromsg-bin/mmsnscomment`，Cmd=0xD5(213)。—— 依据：`o2` 构造 Smali 中 CGI/Cmd/RR 常量；`<init>(pc5/bf6;String;I)`。
6. ✅ `af6` proto 字段：h=#5(opType)、e=#2、i=#6、o=#9(commentId) 等（`op()` 写字段序）。
7. ✅ 枚举：条目 bean `jk4.p.Z0()→SnsInfo`；`W7(SnsInfo, tf5.b)` 绑定；`MvvmList.d()` 取数据。
8. ✅ `SnsInfo` 有 `getUserName/getSnsId/getLikeFlag/isAd/isExtFlag`（methods_only 全列）。

### 8.2 需在你机器上做最终确认的点
1. ⚠️ **混淆类是分版本的**：`h6 / o2 / lk4.g / tf5.b / lj4.a / pc5.af6 / pc5.bf6 / jk4.p / p4` 名字在当前 APK 有效；升级微信后**名字可能变**，但**机制、字段、CGI 不变**。若 hook 不命中，请按下面流程重定位：
   - 用字符串 `"/cgi-bin/micromsg-bin/mmsnscomment"` 反查使用类 → 即 `NetSceneSnsComment`(本例 `o2`)。
   - `find_caller(NetSceneSnsComment.<init>)` → 得入队类 `m4`(SnsAsyncQueueMgr) 与 `h6`(SnsServer)。
   - `h6` 内找含 `if(x!=3 && x!=5) return;` 且 `cmt.h=x` 的静态方法 → 即 `p`（点赞）。
   - 用户手动点一次「赞」，用 8.1#3 的 Hook 打日志核对 `op=5`、`to=发布者wxid`、`scene` 的真实整数值，把你的 `sScene` 固定成该值（仅影响上报）。
2. ⚠️ 三个点图标 `android.R.drawable.ic_menu_more` 只是稳妥的「更多」符号；想要标准竖排三点请自备 vector drawable（`showAsAction=ALWAYS` + icon 即可固定在右上角）。
3. ⚠️ 若某版本菜单项未出现在右上角，改用方案 B：向容器 `findViewById(2131362004)`/`ImproveHeaderUIC.actionBarView` 内 add 自绘 `ImageView`。
4. ⚠️ 频率：连续大量点赞可能触发微信风控/隐私限制，务必节流（示例 3.5~6s）并对「非好友/陌生人」动态跳过（确保 `poster ∈ 好友集`）。

---

## 9. 版本兼容 & 风险

- **兼容方向**：不要用类名字面量以外的东西做强耦合。核心不变量 = 「点赞 = 一条 opType=5 的空内容 SnsComment 指向 field_snsId，经 mmsnscomment 提交」。按此目标 + 字符串锚点可跨版本重定位。
- **稳定性**：优先用「hook 枚举 + 反射静态调用」而非「模拟点击」，避免时序/动画/可见性耦合。
- **合规/风控**：自动点赞属敏感行为，注意频率、好友范围、以及第三方风控。
- **线程**：`h6.p` 内部 DB insert + 入队，可主线程调用；网络由队列异步完成。

---

## 10. 附：调试建议

1. 先只开 8.1#3 的 `h6.p` 日志 Hook，手动点一次赞，确认参数（尤其 `scene` 与 `op=5`）。
2. 再开 `lk4.g.W7` 打印 `getSnsId()/getUserName()/getLikeFlag()/isAd()`，确认枚举齐全、范围正确。
3. 最后把 `sSelectedWxids` 接你的选择器，跑一轮小流量验证（先只勾 1~2 个好友）。
4. 若取消赞要联动：调 `h6.a(info.getSnsId())` 后 `info.setLikeFlag(0)`。

—— 完 ——
