# 微信朋友圈「右上角三个点菜单 + 自动点赞」完整逆向（v2 修正版）

> 独立 Xposed 模块，不依赖 LSPilot BSH。分析对象：本机 `com.tencent.mm` base.apk。
> ⚠️ v2 修正：初版把点赞误写成 `h6.p(wxid,5,...)`（strangers 路由，只入队不发送），导致“注入菜单成功但不点赞”。
> **正确方法 = `h6.n(SnsInfo, 1, null, 0)`**（标准路由，立即发送）。详见第 3、4 节。

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| 时间线 Activity | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI`（旧 `SnsTimeLineUI` 已 `@Deprecated` 空壳） |
| 注入右上角菜单 | hook `ImproveSnsTimelineUI.onCreateOptionsMenu(Menu)`，**before** 里 `menu.add(...).setIcon(⋮).setShowAsAction(ALWAYS)` |
| **点赞（务必用这个）** | 反射静态方法 `com.tencent.mm.plugin.sns.model.h6` 的 `n(SnsInfo, 1, null, 0)` → 内部 `o(...)` **立即** `j1.q().b.g(new o2(...))` 发网络 |
| 取消赞 | `h6.n` 的反向：`info.setLikeFlag(0); h6.a(info.getSnsId())` |
| 别用 | `h6.p(wxid,5,null,info,scene)` 是 strangers 路由，**只入队** `SnsAsyncQueueMgr` 不立即发，自动化不可靠 |
| 网络 | `NetSceneSnsComment`(混淆 `o2`，图评论态 `j2`) → CGI `/cgi-bin/micromsg-bin/mmsnscomment`，Cmd=0xD5(213) |
| 枚举 | hook `lk4.g.W7(SnsInfo, tf5.b)` 收集已加载 `SnsInfo`；或 `MvvmList.d()` |
| 筛选中联系人 | `SnsInfo.getUserName()`(=发布者wxid) 与你的勾选集合比对，且 `getLikeFlag()==0` |
| 写回刷新 | `com.tencent.mm.plugin.sns.storage.l1.d(snsId, SnsInfo)`（或 `p4.Jj().w4(info)`）|

---

## 1. 时间线界面结构

继承链：`ImproveSnsTimelineUI → ImproveSnsJankUI → MMSecDataActivity → BaseMvvmActivity → VASActivity → VASActivityJava → MMActivity`

- `onCreate`：`setMMTitle(2131777179)`(朋友圈)、`setActionbarColor(2131102557)`、`this.s=findViewById(2131362004)`(=ActionBar 容器，按状态栏高度 padding)。
- `importUIComponents()`：`ImproveHeaderUIC, ImproveMainUIC, ImproveUnreadUIC, ImproveDataUIC, ...`。头部/右上角相机发布按钮在 `ImproveHeaderUIC`，列表数据在 `ImproveDataUIC`/`ImproveMainUIC`。
- `onCreateOptionsMenu(Menu)`：`super(VASActivity).onCreateOptionsMenu(menu)` → `mController.g0(menu)`。**平台 Menu 被灌入自定义 ActionBar** → 这就是右上角注入点。
- 旧 `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI`：反编译+Smali 确认全类仅 `<init>`（`.annotation Deprecated`），非线上界面。

---

## 2. 右上角「三个点」菜单注入

原理：WeChat `MMActivity` 把 `onCreateOptionsMenu` 的 `Menu` 经 `mController.g0(menu)` 渲染进自定义 ActionBar。必须**在 before 里加项**（否则原方法体执行 `super` 时已按旧 menu 渲染完，新项不显示）。
```java
findAndHookMethod(timelineCls, "onCreateOptionsMenu", Menu.class, new XC_MethodHook(){
  protected void beforeHookedMethod(MethodHookParam p){
    Menu m=(Menu)p.args[0];
    MenuItem it=m.add(0, 0x990011, 0, "自动点赞");
    it.setIcon(android.R.drawable.ic_menu_more);              // 三连点；可换更精致 vector
    it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);       // 固定右上角
    it.setOnMenuItemClickListener(x->{ showThreeDot(); return true; });
  }
});
```
兜底：若某版本不渲染任意 MenuItem，则 after `onCreate` 后向 `findViewById(2131362004)`（或 `ImproveHeaderUIC.actionBarView` 右侧容器）add 自绘 `ImageView`(⋮)，`layout_gravity=end|top`，点击弹 `PopupMenu`。

---

## 3. 点赞底层机制 + 【根因：h6.p 为什么不发】

### 3.1 时间线真实赞按钮路径
处理器 = `com.tencent.mm.plugin.sns.ui.improve.util.ImproveInteractionUtil.changeLikeStatus`（混淆内类 `mk4.r.onClick`；春季态 `mk4.s.onClick`）：
```java
if (pVar.getLikeFlag()==0){                    // 未赞
    StrictMode.allowThreadDiskReads();
    pVar.setLikeFlag(1);
    pVar.Z0().setLikeFlag(1);                  // Z0()=SnsInfo
    l1.d(pVar.e1(), pVar.Z0());                // 写库(e1()=snsId)
    h6.n(pVar.Z0(), 1, (lj4.a)null, 0);        // ★发赞，opType=1，空内容
    ...动画/上报...
} else {
    pVar.setLikeFlag(0); pVar.Z0().setLikeFlag(0);
    l1.d(pVar.e1(), pVar.Z0());
    h6.a(pVar.e1());                           // 取消
}
```
`h6` = `com.tencent.mm.plugin.sns.model.SnsLogic$SnsServer`（混淆 `h6`）。

### 3.2 h6.n → h6.o：如何“立即发送”
`h6.n(snsInfo,i=1,null,i2)` = `h6.o(snsInfo,1,null,null,false,0,i2)`。`o(...)` 关键：
```java
af6 c=new af6();                 // SnsComment
c.n=""; c.m=now; c.f=y1.m(); c.d=y1.u();   // 自己
c.e=snsInfo.getUserName();       // 对方wxid
c.g=z1.e(c.e);                   // 对方nick
c.h=i;                           // opType=1
c.u=i2; c.o=0;                   // 场景/回复id
d(c,null);                       // 无表情/图片
bf6 op=new bf6(); op.d=((ua)snsInfo).field_snsId; op.f=c; op.g=new af6();
... if(p4.Bj().a(clientId,op,i2)){     // 入异步队列(opType=1→this.a.d)
      if(m4.n(clientId)) j1.q().b.g(new j2(...));     // 图评论态
      else                j1.q().b.g(new o2(op,clientId,0));  // ★立即投递网络场景
  } else Log.e("can not add Comment");                    // 未入队则不发
```
即：`o` 除了入队，还把 `NetSceneSnsComment(o2)` **直接**推进网络调度器发请求。

### 3.3 h6.p：只入队、不发送（本次 bug 根因）
`h6.p(wxid,5,null,info,scene)`（`sendCommentToStranger`）末尾只有：
```java
p4.Bj().a(g, bf6Var, i2);        // = m4.a -> m4.b：只把 opType=5 项放进 this.a.g 并落盘，不投递场景
```
`m4.b` 按 opType 分流（取证）：
```java
int op=bf6.f.h;
if(op==1)      this.a.d.add(item); y=y(snsId);   // 标准：d 队列
else if(op==5) this.a.g.add(item);               // ★strangers：g 队列，仅入队
else if(op==3) this.a.f.add(item);
...
return op决定;  // opType=1: z=!y(removeLiked),无待取消=>true
```
`h6.p` 不做 `j1.q().b.g(o2)` → 依赖队列冲刷器 `checkQueue/checkQueueImp`（由 UI 滚动/定时触发）才会把 `g` 队列发出去。模块带外直调 `h6.p` 无任何触发 → 赞躺在 `this.a.g` → **表现为“不起作用”**（直到某次无关事件顺带冲刷才可能补发）。

---

## 4. 一共有几种触发方式（全景）

| # | 方式 | 调用 | 发送时机 | 说明 |
|---|---|---|---|---|
| 1 | **标准/时间线（推荐）** | `h6.n(SnsInfo,1,null,0)`(=o，opType=1 空内容) | **立即** `j1.q().b.g(o2)` | 时间线/详情，点赞就用它 |
| 2 | 标准评论式同源 | `h6.m(SnsInfo,1,"",0L,"",false,scene)` | 立即 | opType=1 空串，等价可用 |
| 3 | strangers 路由 | `h6.p(wxid,5,null,SnsInfo,scene)` | **仅入队**等冲刷 | 陌生人/加密流；自动化不可靠（bug 根源） |
| 4 | 直建 NetScene | 自造 `bf6`(af6.h=1,e=对方,d=自己,bf6.d=snsId) 再 `j1.q().b.g(new o2(bf6,clientId,0))` | 立即 | 终极兜底 |
| 5 | 复刻 UI 回调 | 反射调 `ImproveInteractionUtil.changeLikeStatus` / `mk4.r.onClick(view)` | 立即(带UI副作用) | 需 item 的 contentView，较重 |
| — | 取消赞 | `info.setLikeFlag(0); h6.a(info.getSnsId())` | — | 全部统一 |

1/2/3 最终都汇聚到同一网络场景 `NetSceneSnsComment`(混淆 `o2`；图评论 `j2`)，CGI 相同；差别只在 af6.opType、字段与**是否立即 `o2` 派发**。

---

## 5. 枚举动态 + 按勾选联系人筛选

- 数据流：`ImproveDataUIC.J7()`=getLiveList→`MvvmList`；`MvvmList.d()`=getData→`ArrayList`；条目 bean `jk4.p`，`jk4.p.Z0()→SnsInfo`；库层 `lk4.g.W7(SnsInfo, tf5.b)` 每条动态入列即带 SnsInfo 调一次。
- 推荐用 hook `lk4.g.W7` 收集：最稳、随滚动自动覆盖已加载全部动态、只依赖一个方法两参类型。
- `SnsInfo`：`getUserName()`(发布者wxid) / `getSnsId()`(String) / `getLikeFlag()`(0未赞,1已赞) / `isAd()` / `isExtFlag()`。
- 过滤：
```java
if(info.getLikeFlag()!=0) continue;   // 已赞
if(info.isAd()) continue;             // 广告
String poster=info.getUserName();
if(sel==null||sel.isEmpty()||sel.contains(poster)) like(info);   // 空=全赞
```

---

## 6. 独立 Xposed 模块完整实现（Java）

```java
package com.yourmod.moments;

import static de.robv.android.xposed.XposedHelpers.*;
import android.app.Activity; import android.os.*; import android.view.*; import android.widget.PopupMenu;
import com.tencent.mm.plugin.sns.storage.SnsInfo;
import java.util.*; import java.util.concurrent.ThreadLocalRandom;
import de.robv.android.xposed.*;

public class MomentsAutoLikeHook implements IXposedHookLoadPackage {
    private static final String TAG="MomentsAutoLike";
    private static final int MENU_ID=0x990011;
    private static ClassLoader sCl;
    private static Activity sTimeline;
    private static final LinkedHashMap<String,SnsInfo> sLive=new LinkedHashMap<>();
    private static volatile boolean sRunning;
    public  static volatile Set<String> sSelectedWxids;     // 你的联系人选择器回写；空=全赞
    private static Class<?> h6;

    public void handleLoadPackage(LoadPackageParam lpp){
        if(!"com.tencent.mm".equals(lpp.packageName)) return;
        sCl=lpp.classLoader;
        h6=findClass("com.tencent.mm.plugin.sns.model.h6", sCl);           // SnsLogic$SnsServer
        final Class<?> ui=findClass("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI", sCl);

        findAndHookMethod(ui,"onCreate",android.os.Bundle.class,new XC_MethodHook(){
            protected void afterHookedMethod(MethodHookParam p){ sTimeline=(Activity)p.thisObject; }
        });

        findAndHookMethod(ui,"onCreateOptionsMenu",Menu.class,new XC_MethodHook(){
            protected void beforeHookedMethod(MethodHookParam p){
                MenuItem it=((Menu)p.args[0]).add(0,MENU_ID,0,"自动点赞");
                it.setIcon(android.R.drawable.ic_menu_more);
                it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                it.setOnMenuItemClickListener(x->{ showThreeDot(); return true; });
            }
        });

        // 收集已加载动态
        findAndHookMethod("lk4.g", sCl, "W7", SnsInfo.class, "tf5.b", new XC_MethodHook(){
            protected void beforeHookedMethod(MethodHookParam p){
                SnsInfo n=(SnsInfo)p.args[0];
                if(n!=null) sLive.put(n.getSnsId(), n);
            }
        });
    }

    private void showThreeDot(){
        if(sTimeline==null) return;
        PopupMenu pm=new PopupMenu(sTimeline, sTimeline.getWindow().getDecorView());
        pm.getMenu().add(0,1,0,"选择点赞联系人");
        pm.getMenu().add(0,2,0,"开始自动点赞");
        pm.getMenu().add(0,3,0,"停止");
        pm.setOnMenuItemClickListener(mi->{
            switch(mi.getItemId()){
                case 1: /* TODO: 启动你的选择器，回写 sSelectedWxids */ return true;
                case 2: startAutoLike(); return true;
                case 3: sRunning=false;   return true;
            } return false; });
        pm.show();
    }

    private void startAutoLike(){
        if(sRunning) return;
        final List<SnsInfo> targets=new ArrayList<>();
        for(SnsInfo n: sLive.values()){
            if(n.getLikeFlag()!=0) continue;
            if(n.isAd()) continue;
            Set<String> sel=sSelectedWxids;
            if(sel==null||sel.isEmpty()||sel.contains(n.getUserName())) targets.add(n);
        }
        if(targets.isEmpty()){ XposedBridge.log(TAG+" no target"); return; }
        sRunning=true; doLike(0,targets);
        XposedBridge.log(TAG+" totalsource="+targets.size());
    }

    private void doLike(final int i, final List<SnsInfo> targets){
        if(!sRunning||i>=targets.size()){ sRunning=false; return; }
        final SnsInfo n=targets.get(i);
        try{
            like(n);                                   // <<< 核心：h6.n
        }catch(Throwable t){ XposedBridge.log(TAG+" like err "+t); }
        long delay=3000+ThreadLocalRandom.current().nextInt(3000);   // 3~6s 节流
        new Handler(Looper.getMainLooper()).postDelayed(()->doLike(i+1,targets), delay);
    }

    private void like(SnsInfo n) throws Throwable {
        if(n.getLikeFlag()!=0) return;
        n.setLikeFlag(1);                                   // 本地先置位，防同轮重复
        callStaticMethod(h6, "n", n, 1, null, 0);           // ★立即发送“赞”（opType=1,空内容）
        // 写回刷新列表/缓存
        callStaticMethod(findClass("com.tencent.mm.plugin.sns.storage.l1", sCl), "d", n.getSnsId(), n);
        XposedBridge.log(TAG+" liked id="+n.getSnsId()+" by="+n.getUserName());
    }

    private void unlike(SnsInfo n) throws Throwable {
        n.setLikeFlag(0);
        callStaticMethod(h6, "a", n.getSnsId());
        callStaticMethod(findClass("com.tencent.mm.plugin.sns.storage.l1", sCl), "d", n.getSnsId(), n);
    }
}
```
> `h6.n` 是静态；`lj4.a` 参数传 `null` 用可装箱 `null` 即可匹配唯一重载。`callStaticMethod` 单个 `null` 在 5 参重载下无歧义。

---

## 7. 钩子/反射符号速查（本版本混淆名）

| 用途 | 类型 | 符号 | 方法/字段 |
|---|---|---|---|
| 注入菜单 | Hook | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI` | `onCreateOptionsMenu(Menu)` / `onCreate(Bundle)` |
| 枚举动态 | Hook | `lk4.g` | `W7(com.tencent.mm.plugin.sns.storage.SnsInfo, tf5.b)` |
| **点赞** | 反射静态 | `com.tencent.mm.plugin.sns.model.h6` | `n(SnsInfo,1,null,0)` → 内部 `o(...)` |
| 同源评论式 | 反射静态 | 同上 | `m(SnsInfo,1,"",0L,"",false,scene)` |
| 取消赞 | 反射静态 | 同上 | `a(String snsId)` |
| 写回存储 | 反射静态 | `com.tencent.mm.plugin.sns.storage.l1` | `d(String snsId, SnsInfo)` |
| 点赞真实入口 | Hook | `com.tencent.mm.plugin.sns.ui.improve.util.ImproveInteractionUtil` | `changeLikeStatus`(内类 `mk4.r`/`mk4.s`) |
| 列表条目 | 反射 | `jk4.p` | `Z0()→SnsInfo` |
| 列表容器 | 反射 | `com.tencent.mm.plugin.mvvmlist.MvvmList` | `d()→ArrayList`（getLiveList: `ImproveDataUIC.J7()`） |
| 网络场景 | — | `com.tencent.mm.plugin.sns.model.o2`(=NetSceneSnsComment) | CGI `mmsnscomment`,Cmd=0xD5 |
| 单例 | 反射 | `com.tencent.mm.plugin.sns.model.p4`(=SnsCore) | `Bj()`=SnsAsyncQueueMgr(`m4`); `Jj()`=SnsInfoStorage(`f2`)→写回 `Y4(long,SnsInfo)` 或直接用 `l1.d()`; `Ej().insert()`=评论写 |

---

## 8. 二次核查清单

### 已取证确认
1. ✅ 时间线赞 = `ImproveInteractionUtil.changeLikeStatus`(混淆 `mk4.r/mk4.s.onClick`)：监证第 69/56 行 `h6.n(info,1,null,0)`，取消 `h6.a(snsId)`。
2. ✅ `h6.n` = `h6.o(snsInfo,i,null,null,false,0,i2)`：`h6.n` 反编译体。
3. ✅ `h6.o` 末尾入队成功后**直接** `j1.q().b.g(new o2(bf6,clientId,0))`：`o` 方法 Smali 第 686–731 行。
4. ✅ `h6.p(...,5,...)` 末尾仅 `p4.Bj().a(...)`(=m4.a→m4.b)，**无**直发：`h6.p` 反编译第 539 行 + `m4.a`/`m4.b` 反编译。
5. ✅ `m4.b` 按 opType 分流：op=1→`this.a.d`；op=5→`this.a.g`：`m4.b` 反编译第 181–194 行。
6. ✅ opType=1 addComment 返回 true（z=!y；y=`removeLiked`，无待取消=false）→ 触发直发：`m4.b` 第 222 行 + `m4.y` 反编译。
7. ✅ `af6` proto 字段号：d=1,e=2,f=3,h=5(opType),i=6场景,o=9,commentId,r=12 createTime…：`af6.op()` 写字段序。
8. ✅ 枚举：`jk4.p.Z0()→SnsInfo`、`W7(SnsInfo,tf5.b)`、`MvvmList.d()`。

### 需在你机器最终确认
1. ⚠️ 混淆名分版本：`h6/o2/lk4.g/tf5.b/lj4.a/af6/bf6/jk4.p/p4/l1` 本版本有效，升级可能变，**机制/CGI/字段不变**。重定位法：搜 `"/cgi-bin/micromsg-bin/mmsnscomment"`→`o2`；`find_caller(o2.<init>)`→`m4`/`h6`/`j2`；在 `h6` 找含 `if(x!=3 && x!=5)return`→`p`，含 `af6.h=i`且末尾 `g(new o2(...))`→`o`/`n`。
2. ⚠️ 仍想用 opType=5：需额外触发队列冲刷（`m4.checkQueue()`，由 `g()`/`checkQueueImp` 承担），否则不发送；不推荐。
3. ⚠️ 三连点图标可换自带 vector；若 MenuItem 不显示，用 §2 兜底（直接 add 到容器 2131362004 / `ImproveHeaderUIC.actionBarView`）。
4. ⚠️ 频率：每条 3–6s 随机；非好友跳过（`poster∈好友集`）；广告/已赞跳过，避免风控与重复。

---

## 9. 版本兼容/风控/调试

- 不变量：**赞 = 一条 opType=1、空内容、指向 `field_snsId` 的 SnsComment，经 mmsnscomment(Cmd 0xD5) 提交**。用此目标+字符串锚点可跨版本重定位，勿死记类名。
- 稳定优先「hook 枚举 + 反射静态调用（`h6.n`）」，不模拟点击。
- 调试步骤：
  1) 开 `h6` 所有重载日志，手动点一次赞，确认 `mk4.r` 调 `h6.n` 且弹赞生效；
  2) 开 `lk4.g.W7` 打印 `getSnsId/getUserName/getLikeFlag/isAd`，确认枚举齐全；
  3) 先只勾 1~2 好友小流量验证 `h6.n` 发赞，再放开；
  4) 不发/异常：确认 `p4.Bj().a` 返回 true（未打印 “can not add Comment”），否则 `info` 的 `field_snsId`/`getUserName` 无效（用列表真实 SnsInfo，别自造）。

—— 完（v2 修正）——

---

# 附录 B —— 二次/三次复核补丁（v3，补全与勘误）

## B.1 本轮新取证（确认无错）
- ✅ `l1.d(String,SnsInfo)` 实为 `MergeInfoStorage.update`：合法 id → `p4.Jj().Y4(w2.n(str), snsInfo)`；广告 → `p4.ij().N1(...)`。故「写回刷新」用 `l1.d(getSnsId(),info)` 是对的（l1.d 反编译第 83–93 行）。
- ✅ `p4.Jj()` 返回 `f2` = **SnsInfoStorage**（方法注释 getSnsInfoStorage），写回方法 `Y4(long,SnsInfo)`；Gallery 里的 `w4(SnsInfo)` 同属此类。`p4.Bj()` 返回 `m4`=SnsAsyncQueueMgr（注释 getSnsAsyncQueueMgr）。（p4.Jj/Bj 反编译）
- ✅ `MvvmList.d()` = `new ArrayList(this.o)`，是内部数据列表 `o` 的**副本**，遍历安全；元素为动态条目 bean，经 `Z0()`/反射取 `SnsInfo`。（MvvmList.d 反编译）
- ✅ `tf5.b` 类真实存在（`lk4.g.W7(SnsInfo, tf5.b)` 的第二参类型），枚举 Hook 的类型串可用。
- ⚠️ 上版速查表把 `p4.Jj()` 误写成「信息写=w4」且没标返回类型，已在 §7 勘误为 `f2/SnsInfoStorage`，写回优先用 `l1.d()`。

## B.2 与真实路径对齐的细节（建议）
- 顺序对齐 `mk4.r`：`setLikeFlag(1)` → `l1.d(...)` → `h6.n(...)`。三者无强依赖，但先落库可让列表立即显示“已赞”。示例改：
```java
n.setLikeFlag(1);
callStaticMethod(findClass("com.tencent.mm.plugin.sns.storage.l1", sCl), "d", n.getSnsId(), n);
StrictMode.allowThreadDiskReads();            // 对齐 app，抑制主线程磁盘读告警
callStaticMethod(h6, "n", n, 1, null, 0);      // 立即发送
StrictMode.setThreadPolicy(StrictMode.allowThreadDiskWrites()); // 视需要恢复
```
- 可选：`pVar` 的 bean flag（`jk4.p.setLikeFlag`）模块拿不到，无需管；`l1.d`→DB 更新→LiveDB 观察者→自动 rebind 会刷新列表。

## B.3 仍存在的覆盖缺口（须知）
- Hook `W7` 只覆盖**已加载**（列表渲染过）的动态。若目标好友的动态还没滚到/没加载，会漏。对策：
  1) 自动化前先把时间线滚到底触发加载（反射调列表滚动/`MvvmList` 加载更多）再执行；
  2) 或改成从 `SnsInfoStorage`(`p4.Jj()`=f2) 直接查库遍历（含未渲染的）。
- 不能赞的类型：广告(`isAd`)、非好友（按你的好友集过滤）、已自己赞过(`getLikeFlag!=0`) 均应跳过。

## B.4 自检/排错建议（新）
在正式跑前，加三个只读 log Hook 验证链路：
```java
// 1) 确认走进了 o（组包+入队+直发）
findAndHookMethod(h6, "o", SnsInfo.class, int.class, "lj4.a", "pc5.uf6", boolean.class, int.class, int.class,
    new XC_MethodHook(){ protected void afterHookedMethod(MethodHookParam p){
        XposedBridge.log(TAG+" h6.o hit sns="+((SnsInfo)p.args[0]).getSnsId()+" op="+p.args[1]); }});
// 2) 确认网络场景真的 doScene
findAndHookMethod("com.tencent.mm.plugin.sns.model.o2", sCl, "doScene",
    "com.tencent.mm.network.s", "com.tencent.mm.modelbase.u0",
    new XC_MethodHook(){ protected void afterHookedMethod(MethodHookParam p){ XposedBridge.log(TAG+" o2.doScene"); }});
// 3) 确认服务器回包 errType/errCode（0=成功）
findAndHookMethod("com.tencent.mm.plugin.sns.model.o2", sCl, "onGYNetEnd",
    int.class,int.class,int.class,String.class,"com.tencent.mm.network.y0","[B",
    new XC_MethodHook(){ protected void afterHookedMethod(MethodHookParam p){
        XposedBridge.log(TAG+" o2.onGYNetEnd errType="+p.args[0]+" errCode="+p.args[1]); }});
```
现象对照：
- `h6.o` 命中且控制台打印 “can not add Comment”（来自 `h6.o`）→ `field_snsId` 无效，换用列表真实 `SnsInfo`；
- `o2.doScene` 未触发 → 走错了方法（确认不是 `h6.p`）；
- `onGYNetEnd errType/errCode` 非 0 → 服务器拒绝（多见于频率/风控/陌生人限 5000）。

## B.5 结论
- 上一版唯一功能性错误 = 用了 `h6.p(opType=5, 只入队不发送)`，已改为 `h6.n(SnsInfo,1,null,0)`（立即发送）。
- 菜单注入链路、点赞协议(CGI 0xD5)、枚举(Hook W7 / MvvmList.d)、写回(l1.d) 本轮复核均一致，无新错误。
- 类名混淆分版本，升级需按 §8/§9 的字符串锚点重定位。

—— 完（v3 复核）——
