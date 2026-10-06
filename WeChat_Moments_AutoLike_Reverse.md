# 微信「朋友圈」右上角三个点菜单 + 自动点赞 — 完整逆向（最终修正版）

> 独立 Xposed 模块，不依赖 LSPilot BSH。对象：本机 com.tencent.mm base.apk（versionCode 3180）。
> ★核心：菜单注入 hook `ImproveSnsTimelineUI.onCreateOptionsMenu`(before)；点赞=反射静态方法 `h6.n(SnsInfo,1,null,0)`（立即发送）；枚举=hook `lk4.g.W7` 收 `args[0]` 真实 `SnsInfo` 并过滤空壳。
> ⚠️ 类名为混淆名、分版本；机制/CGI/字段不变。升级按 §11 字符串锚点重定位。

## 0. TL;DR
| 项 | 结论 |
|---|---|
| 时间线 Activity | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI`（旧 `SnsTimeLineUI` 已 @Deprecated 空壳，非线上） |
| 注入右上角菜单 | hook 其 `onCreateOptionsMenu(Menu)` **before**：`menu.add(id,0,"自动点赞").setIcon(⋮).setShowAsAction(ALWAYS)`；上层 `super(VASActivity)→mController.g0(menu)` 渲染进 ActionBar |
| **点赞（用它）** | `XposedHelpers.callStaticMethod(h6,"n", snsInfo, 1, null, 0)`；`h6=com.tencent.mm.plugin.sns.model.h6`(SnsLogic$SnsServer)；`n`→内部 `o(...)` **立即** `j1.q().b.g(new o2(...))` |
| 别用 | `h6.p(wxid,5,null,info,scene)` strangers 路由，**只入队不立即发** |
| 取消赞 | `h6.a(snsInfo.getSnsId())` |
| 网络 | `NetSceneSnsComment`(混淆 `o2`；图评论 `j2`) → CGI `/cgi-bin/micromsg-bin/mmsnscomment`，Cmd=0xD5(213) |
| **枚举（关键）** | hook `lk4.g.W7(SnsInfo, tf5.b)` 收 **`args[0]`** 真实 `SnsInfo`；**勿**用 `jk4.p.<init>→Z0()`（懒加载未就绪，返回空壳 `sns_table_0/poster=null`） |
| 空壳过滤 | `getUserName()==null` 或 `field_snsId==0`（localId=`sns_table_0`）跳过 |
| 筛选中联系人 | `SnsInfo.getUserName()`(=发布者wxid) 比对勾选集合；且 `getLikeFlag()==0`、非广告 |
| 写回刷新 | `l1.d(getSnsId(), info)`（=MergeInfoStorage.update→`p4.Jj().Y4`） |

## 1. 时间线界面结构
继承链：`ImproveSnsTimelineUI → ImproveSnsJankUI → MMSecDataActivity → BaseMvvmActivity → VASActivity → VASActivityJava → MMActivity`
- `onCreate`：`setMMTitle(2131777179)`("朋友圈")、`setActionbarColor(2131102557)`、`findViewById(2131362004)`(=ActionBar容器)。
- `importUIComponents()`：`ImproveHeaderUIC, ImproveMainUIC, ImproveDataUIC, ImproveInputUIC, ...`。
- `onCreateOptionsMenu(Menu)`→`super(VASActivity).onCreateOptionsMenu`→`mController.g0(menu)`（平台Menu→自定义ActionBar）。**注入点。**
- 旧 `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI`：全类仅 `<init>`（@Deprecated），非线上界面。
- 数据：每行 bean=`jk4.p`(`ImproveSnsInfo`)，`Z0()→SnsInfo`（懒加载 `this.l1.getValue()`）；列表=`MvvmList`(`ImproveDataUIC.J7()`；`MvvmList.d()`=数据副本)。

## 2. 右上角「三个点」菜单注入
原理：`MMActivity` 把 `onCreateOptionsMenu` 的 Menu 经 `mController.g0(menu)` 渲染进自定义 ActionBar。必须 **before 加项**（after 时原方法体已执行 super 渲染完，新项不显示）。
```java
Class<?> ui=findClass("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI", cl);
findAndHookMethod(ui,"onCreateOptionsMenu",Menu.class,new XC_MethodHook(){
  protected void beforeHookedMethod(MethodHookParam p){
    MenuItem it=((Menu)p.args[0]).add(0,0x990011,0,"自动点赞");
    it.setIcon(android.R.drawable.ic_menu_more);          // 三连点；可换更精致 vector
    it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);   // 固定右上角
    it.setOnMenuItemClickListener(x->{ showThreeDot(); return true; });
  }
});
findAndHookMethod(ui,"onCreate",android.os.Bundle.class,new XC_MethodHook(){
  protected void afterHookedMethod(MethodHookParam p){ sTimelineActivity=(Activity)p.thisObject; }
});
```
兜底：若某版本不渲染任意 MenuItem，则 after `onCreate` 后向 `findViewById(2131362004)` / `ImproveHeaderUIC.actionBarView` 右侧容器 add 自绘 `ImageView`(⋮)，点击弹 `PopupMenu`。

## 3. 点赞底层机制
时间线真实赞处理器=`ImproveInteractionUtil.changeLikeStatus`（混淆内类 `mk4.r.onClick`/春季态 `mk4.s.onClick`）：
```java
if (pVar.getLikeFlag()==0){
    StrictMode.allowThreadDiskReads();
    pVar.setLikeFlag(1);
    pVar.Z0().setLikeFlag(1);           // Z0()=SnsInfo
    l1.d(pVar.e1(), pVar.Z0());         // 落库刷UI
    h6.n(pVar.Z0(), 1, (lj4.a)null, 0); // ★真正的“赞”，opType=1，空内容
} else {
    pVar.setLikeFlag(0); pVar.Z0().setLikeFlag(0);
    l1.d(pVar.e1(), pVar.Z0());
    h6.a(pVar.e1());                    // 取消
}
```
- `h6.n(info,1,null,0)`→`h6.o(info,1,null,null,false,0,0)`：组 `pc5.af6`(SnsComment: d=自己 e=对方 f="" h=1)+`pc5.bf6`(d=`((ua)info).field_snsId`)，入队后**立即** `j1.q().b.g(new o2(bf6,clientId,0))` 发网络。`h6.n/.o/.m/.a` 均 public static（已 Smali 确认）。
- **别用 `h6.p`**：`h6.p(wxid,5,null,info,scene)` Smali 末尾**只有** `p4.Bj().a(...)`(=m4.a→m4.b：opType=5 塞 `this.a.g` 落盘)，**没有** `j1.q().b.g(o2)`，靠队列冲刷器(UI滚动/定时)才发 → 模块带外调用无触发器 → 赞躺队列 → "无效"。
- `m4.b` 按 opType 分流：op=1→`this.a.d`；op=5→`this.a.g`(仅入队)。opType=1 时 `return !y`(y=removeLiked，无待取消=false→true)→触发直发。
- 协议字段（`af6` proto 号）：d=1(自己) e=2(对方) f=3(内容) h=5(opType) i=6(场景) o=9(commentId) r=12(createTime)。
- `SnsInfo.getSnsId()`=`w2.j("sns_table_", field_snsId)`；**空壳(field_snsId=0)→`getSnsId()="sns_table_0"`、`getUserName()=null`**（本次 bug 根因）。

## 4. 一共有几种触发方式
| # | 方式 | 调用 | 发送 | 建议 |
|---|---|---|---|---|
| 1 | 标准/时间线(推荐) | `h6.n(SnsInfo,1,null,0)`(=o) | **立即** `g(new o2)` | ✅用它 |
| 2 | 同源评论式 | `h6.m(SnsInfo,1,"",0L,"",false,scene)` | 立即 | ✅等价 |
| 3 | strangers | `h6.p(wxid,5,null,SnsInfo,scene)` | 仅入队 | ❌上次bug |
| 4 | 直投 NetScene | 自造 `bf6` 再 `j1.q().b.g(new o2(...))` | 立即 | ⚠️兜底 |
| 5 | 复刻 UI 回调 | 反射 `ImproveInteractionUtil.changeLikeStatus`/`mk4.r.onClick(view)` | 立即(带UI副作用) | ⚠️较重 |
| — | 取消赞 | `info.setLikeFlag(0); h6.a(info.getSnsId())` | — | 全统一 |

## 5. 枚举动态（本次修正重点）+ 按勾选联系人筛选
### ✅ 正确：hook `lk4.g.W7`，收 `args[0]` 真实 `SnsInfo`
`lk4.g.W7(SnsInfo, tf5.b)`=`SnsImproveStorage.postEvent`，第一参即**已填充的真实数据库 `SnsInfo`**（内部 `p.convertFrom(snsInfo.convertTo())` 用的就是它）。
```java
findAndHookMethod("lk4.g", cl, "W7",
    findClass("com.tencent.mm.plugin.sns.storage.SnsInfo", cl),
    findClass("tf5.b", cl), new XC_MethodHook() {
  protected void afterHookedMethod(MethodHookParam p) {
    Object info = p.args[0];                              // 真实 SnsInfo
    try {
      String poster = (String) callMethod(info, "getUserName");
      Object sidO   = getObjectField(info, "field_snsId");         // Long
      long   sid    = (sidO instanceof Number)?((Number)sidO).longValue():0L;
      if (poster == null || sid == 0) return;             // ★过滤空壳(sns_table_0)
      if ((boolean) callMethod(info, "isAd")) return;     // 广告
      sItems.put((String) callMethod(info, "getSnsId"), info);     // sns_table_XXX
    } catch (Throwable ig) {}
  }
});
```
### ❌ 错误（勿用）：`jk4.p.<init>()` 后立即 `Z0()`
`jk4.p.Z0()` 读懒加载 `this.l1.getValue()`，构造那一刻还没绑数据 → 返回默认空 `SnsInfo`(field_snsId=0/user=null/getSnsId="sns_table_0")。所有行去重成同一空对象 → 点赞发给 0（"无效"根因）。
### 筛选（单条）
```java
if((int)callMethod(info,"getLikeFlag")!=0) continue;   // 已赞
if((boolean)callMethod(info,"isAd")) continue;         // 广告
String to=(String)callMethod(info,"getUserName");
if(sel!=null && !sel.isEmpty() && !sel.contains(to)) continue; // 勾选过滤
```

## 6. 必成写法（完整 Java）
```java
public class MomentsLike implements IXposedHookLoadPackage {
  private static final String TAG="MLike";
  private static ClassLoader cl; private static Class<?> h6, snsInfoCls, lj4a, tf5b, l1;
  private static Activity sAct;
  private static final LinkedHashMap<String,Object> sItems=new LinkedHashMap<>(); // localId->真实SnsInfo
  public static volatile Set<String> sel;              // 勾选wxid；null/空=全赞
  private static volatile boolean running;

  public void handleLoadPackage(LoadPackageParam lpp){
    if(!"com.tencent.mm".equals(lpp.packageName)) return;
    cl=lpp.classLoader;
    h6=findClass("com.tencent.mm.plugin.sns.model.h6", cl);
    snsInfoCls=findClass("com.tencent.mm.plugin.sns.storage.SnsInfo", cl);
    lj4a=findClass("lj4.a", cl); tf5b=findClass("tf5.b", cl);
    l1=findClass("com.tencent.mm.plugin.sns.storage.l1", cl);
    Class<?> ui=findClass("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI", cl);

    findAndHookMethod(ui,"onCreate",android.os.Bundle.class,new XC_MethodHook(){
      protected void afterHookedMethod(MethodHookParam p){ sAct=(Activity)p.thisObject; }});
    findAndHookMethod(ui,"onCreateOptionsMenu",Menu.class,new XC_MethodHook(){
      protected void beforeHookedMethod(MethodHookParam p){
        MenuItem it=((Menu)p.args[0]).add(0,0x990011,0,"自动点赞");
        it.setIcon(android.R.drawable.ic_menu_more);
        it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        it.setOnMenuItemClickListener(x->{ showMenu(); return true; }); }});

    // ★枚举：W7 的真实 SnsInfo(args[0])，过滤空壳
    findAndHookMethod("lk4.g", cl, "W7", snsInfoCls, tf5b, new XC_MethodHook(){
      protected void afterHookedMethod(MethodHookParam p){ collect(p.args[0]); }});

    // 只读探针：打真实目标(args[0])
    findAndHookMethod(h6,"n",snsInfoCls,int.class,lj4a,int.class,new XC_MethodHook(){
      protected void beforeHookedMethod(MethodHookParam p){
        Object n=p.args[0];
        XposedBridge.log("REAL h6.n op="+p.args[1]+" to="+callMethod(n,"getUserName")
          +" sid="+getObjectField(n,"field_snsId")+" localId="+callMethod(n,"getSnsId")); }});
  }
  private void collect(Object info){
    if(info==null) return;
    try{
      String poster=(String)callMethod(info,"getUserName");
      Object sidO=getObjectField(info,"field_snsId");
      long sid=(sidO instanceof Number)?((Number)sidO).longValue():0L;
      if(poster==null || sid==0) return;                 // ★空壳过滤
      if((boolean)callMethod(info,"isAd")) return;
      sItems.put((String)callMethod(info,"getSnsId"), info);
    }catch(Throwable ig){}
  }
  private void showMenu(){
    if(sAct==null) return;
    PopupMenu pm=new PopupMenu(sAct, sAct.getWindow().getDecorView());
    pm.getMenu().add(0,1,0,"选择点赞联系人");   // TODO 调你的选择器回写 sel
    pm.getMenu().add(0,2,0,"开始自动点赞");
    pm.getMenu().add(0,3,0,"停止");
    pm.setOnMenuItemClickListener(mi->{ switch(mi.getItemId()){
      case 1: return true; case 2: start(); return true; case 3: running=false; return true; } return false; });
    pm.show();
  }
  private void start(){ if(running) return; running=true; doOne(new ArrayList<>(sItems.values()),0); }
  private void doOne(final List<Object> list, final int i){
    if(!running||i>=list.size()){ running=false; return; }
    try{ like(list.get(i)); }catch(Throwable t){ XposedBridge.log(TAG+" err "+t); }
    new Handler(Looper.getMainLooper()).postDelayed(()->doOne(list,i+1), 3000+new java.util.Random().nextInt(3500));
  }
  private void like(Object info) throws Throwable {
    if(info==null) return;
    if((int)callMethod(info,"getLikeFlag")!=0) return;         // 已赞
    String to=(String)callMethod(info,"getUserName"); if(to==null) return;
    Set<String> s=sel; if(s!=null && !s.isEmpty() && !s.contains(to)) return;
    callMethod(info,"setLikeFlag",1);
    callStaticMethod(l1,"d",(String)callMethod(info,"getSnsId"), info); // 落库刷新
    StrictMode.allowThreadDiskReads();
    callStaticMethod(h6,"n", info, 1, null, 0);                 // ★立即发赞
    XposedBridge.log(TAG+" liked by="+to+" localId="+callMethod(info,"getSnsId"));
  }
}
```

## 7. 触发 / 刷新动态（如何"刷新朋友圈"）
- **刷新动态=微信自己的事**：`ImproveOverScrollView.a(int)`=`directShowTopLoading`（内部 `this.g.invoke(i)` 触发刷新回调）→`ImproveDataUIC.refresh()`→仓库拉取→每行重建 `jk4.p`/触发 `lk4.g.W7`→新 `SnsInfo` 进集合。
- **默认不强制刷新**：hook W7 随用户自然浏览/刷新收集 + 定时扫描 §10 循环点赞，风控最低。
- **可选定时原生刷新**（自动找新帖）：hook `ImproveDataUIC.<init>(androidx.appcompat.app.AppCompatActivity)` 存实例 → 定时
  ```java
  Object osv=callMethod(sDataUIC,"getOverScrollView");
  callMethod(osv,"a",1);   // 复用微信原生下拉刷新（比模拟手势/自打网络稳）
  ```
  `REFRESH_MS` 建议 ≥2~5 分钟。`getOverScrollView()` 在基类 `com.tencent.mm.plugin.sns.ui.improve.component.j`，返回 `ImproveOverScrollView`。
- 分层：L0 收集(W7) / L1 定时扫描点赞(默认) / L3 事件即时(可选：W7 after 直接入队) / L2 定时原生刷新(可选)。

## 8. 符号速查（本版本混淆名）
| 用途 | 符号 | 方法/字段 |
|---|---|---|
| 注入菜单 | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI` | `onCreateOptionsMenu(Menu)`/`onCreate(Bundle)` |
| **枚举** | `lk4.g` | `W7(com.tencent.mm.plugin.sns.storage.SnsInfo, tf5.b)`，收 `args[0]` |
| **点赞** | `com.tencent.mm.plugin.sns.model.h6`(SnsLogic$SnsServer) | 静态 `n(SnsInfo,1,null,0)`；`p(..,5,..)`；`a(snsId)` |
| 写回刷新 | `com.tencent.mm.plugin.sns.storage.l1`(MergeInfoStorage) | `d(String snsId, SnsInfo)`→`p4.Jj().Y4(long,SnsInfo)` |
| 列表容器 | `com.tencent.mm.plugin.mvvmlist.MvvmList` | `d()→ArrayList`(副本)；`ImproveDataUIC.J7()` |
| 原生刷新 | `com.tencent.mm.plugin.sns.ui.improve.view.ImproveOverScrollView` | `a(int)`=directShowTopLoading |
| 网络场景 | `com.tencent.mm.plugin.sns.model.o2`(NetSceneSnsComment) | CGI `mmsnscomment` Cmd=0xD5 |
| 单例 | `com.tencent.mm.plugin.sns.model.p4`(SnsCore) | `Bj()`=SnsAsyncQueueMgr(m4)；`Jj()`=SnsInfoStorage(f2) |
| 真实赞入口 | `ImproveInteractionUtil`(内类 `mk4.r`/`mk4.s`) | `changeLikeStatus`→`h6.n`，取消→`h6.a` |

`SnsInfo` 公开可读方法（反射安全）：`getLikeFlag/setLikeFlag/getUserName/getSnsId/isAd`。`jk4.p` 的 `getLikeFlag` 是混淆名，别反射它——一律用 `W7(args[0])`，或运行时再 `jk4.p.Z0()`（构造期无效）。

## 9. 手动点赞也失败 —— 先隔离 FakeLike
- 你模块另一功能 `MomentsFakeLike` hook 了 `jk4.p.Q0()`/`jk4.p.b1()`（取 `SnsObject`）并向内存注入 +10 假赞：
  ```
  MomentsFakeLike: fakeLike(in-memory,no-persist) OK added=10 likeCount=10
  MomentsFakeLike: hooked jk4.p.Q0 x1 / b1 x1
  ```
  它把被赞 moment 的内存 `SnsObject`(赞列表)换成假数据，与真点赞读同一份 → 赞数/赞列表错乱 → 表现像"手动点赞也没反应/对不上"。
- **隔离测试**：只留 `h6.n` 只读探针，关掉 `MomentsFakeLike`+自动点赞，再手动点一次赞：
  - 探针打出 `to=<真wxid> sid=<非0长整型>` 且 UI 正常出赞 → 是 FakeLike 污染，与自动点赞无关；
  - 真赞时探针没打 → 被 AdBlocker/FakeLike 在更上游(isAd/SnsObject)拦了。

## 10. 扫描/节流循环（可选，全自动）
```java
private static final ArrayDeque<Object> sQueue=new ArrayDeque<>();
private static final Set<String> sQueued=new HashSet<>();     // 防重复入队(localId)
private static final int SCAN_MS=20000;
private void startAuto(){
  if(running) return; running=true;
  Handler H=new Handler(Looper.getMainLooper());
  final Runnable scan=new Runnable(){ public void run(){
    for(Object info:new ArrayList<>(sItems.values())) enqueue(info);
    if(running) H.postDelayed(this, SCAN_MS); }};
  final Runnable work=new Runnable(){ public void run(){
    Object info=sQueue.poll();
    if(info!=null){ try{ like(info); }catch(Throwable t){ XposedBridge.log(TAG+" err "+t); } }
    if(running) H.postDelayed(this, 3000+new java.util.Random().nextInt(3500)); }};
  H.post(scan); H.post(work);
}
private void enqueue(Object info){
  try{
    if((int)callMethod(info,"getLikeFlag")!=0) return;
    if((boolean)callMethod(info,"isAd")) return;
    String to=(String)callMethod(info,"getUserName"); if(to==null) return;
    String key=(String)callMethod(info,"getSnsId"); if(sQueued.contains(key)) return;
    Set<String> s=sel; if(s!=null && !s.isEmpty() && !s.contains(to)) return;
    sQueued.add(key); sQueue.add(info);
  }catch(Throwable ig){}
}
```

## 11. 二次核查 / 版本兼容 / 调试
**已取证确认**
- ✅ 时间线赞=`ImproveInteractionUtil.changeLikeStatus`(`mk4.r/mk4.s.onClick`)：`h6.n(info,1,null,0)`，取消 `h6.a(snsId)`。
- ✅ `h6.n` 静态→`h6.o(...)` 末尾**直接** `j1.q().b.g(new o2(bf6,clientId,0))`；`h6.p` 只 `p4.Bj().a(...)` 不直发。
- ✅ `m4.b` 按 opType 分流：op=1→`this.a.d`；op=5→`this.a.g`。
- ✅ `lk4.g.W7` args[0]=真实 `SnsInfo`；`jk4.p.Z0()`=`this.l1.getValue()` 懒加载，构造期未就绪→空壳。
- ✅ `SnsInfo.getSnsId()`=`w2.j("sns_table_", field_snsId)`；空壳=`sns_table_0`。

**运行日志定位（本次）**
- `collect snsId=sns_table_0 poster=null likeFlag=0` + `real h6.n ... snsId=sns_table_0` = 拿到空壳 `SnsInfo`(field_snsId=0) 就去赞 → 服务器不认。修法见 §5：改收 `W7(args[0])` 并过滤 `poster==null||field_snsId==0`。

**需在你机器最终确认**
- ⚠️ 混淆名分版本，升级重定位：搜 `"/cgi-bin/micromsg-bin/mmsnscomment"`→`o2`；`find_caller(o2.<init>)`→`m4`/`h6`/`j2`；在 `h6` 找末尾 `g(new o2(...))` 且 `af6.h=i` 的方法=`o`/`n`。
- ⚠️ 三连点图标可换自带 vector；菜单不显示则用 §2 兜底注入 `2131362004`/`ImproveHeaderUIC.actionBarView`。
- ⚠️ 频率：每条 3~6s；只赞勾选好友；跳过广告/已赞；陌生人流约 5000 上限。

**调试步骤**
1. 只装只读 `h6.n` 探针（打 `args[0]` 真实 `sid/to`），手动点一次赞，确认出现 `sid=<非0> to=<wxid>`。
2. 只收 `W7(args[0])` 并打印，滚一遍朋友圈，确认出现 `localId=sns_table_780 sid=<非0> poster=wxid_xxx`（不是 `sns_table_0`）。
3. 对真实项 `h6.n(realInfo,1,null,0)`，并挂 `o2.doScene`/`o2.onGYNetEnd` 看 `errType/errCode==0`（服务器接受）。
4. 手动点赞失败：先关 `MomentsFakeLike` 隔离（见 §9）。

---
**一句话总结**：方法对（`h6.n(SnsInfo,1,null,0)`），之前"无效"是因为**枚举用了 `jk4.p.<init>→Z0()` 拿到懒加载空壳 `sns_table_0/poster=null`**；改成 **hook `lk4.g.W7` 收 `args[0]` 真实 `SnsInfo` 并过滤空壳**即可。手动点赞失败先排查 `MomentsFakeLike` 对 `jk4.p.b1/Q0` 的假 SnsObject 污染。
