# 微信主页左上角注入「模块入口按钮」完整逆向分析与 Xposed 实现（v2 修正版）

> 目标 APK：`com.tencent.mm`（微信）
> 分析基线：DexKit / jadx / baksmali 实测
> 适用：独立 Xposed 模块（**不依赖 LSPilot / BSH**），仅在微信**消息主页(MainUI)**左上角注入按钮，**不串到聊天窗口**。
> v2 变更：主 Hook 增加「聊天态 + MainUI」双门控（修复串显）；兜底改 Hook `getActionBarCustomView()`；禁用共用链路 Hook。详见 §8 修正记录。

---

## 1. 结论（TL;DR）

- 微信「主页」= `LauncherUI`(Activity) → `HomeUI`(UIC) → `MainTabUI` → `MainUI`(消息列表 Fragment)。
- 主页 ActionBar 由 `HomeUI.m()` 初始化；但**主页与聊天窗口共用同一个 ActionBar**，且两套 custom view 布局**共用同一批子控件 id**（含左上角容器 `0x7f0a0158`）→ 直接注入会串到聊天窗口（根因见 §8）。
- **正确做法**：Hook `HomeUI.m()` 的 after，先过两道门控——① `HomeUI.r.m()`(isChattingForeground)==false；② `HomeUI.t.g()` 当前 Fragment 是 `com.tencent.mm.ui.conversation.MainUI`——再往 `0x7f0a0158` 注入。
- 兜底（通知/快捷方式等入口走旧容器时）：Hook `BaseConversationUI.getActionBarCustomView()` 的 after，`param.result` 必为主页布局，天然带门控。

---

## 2. 逆向链路图

```
LauncherUI (onResume) ─► HomeUI.m()  ←【主 Hook：initActionBar，带门控】
        │  else 分支(非聊天态):
        │     actionBar.y( inflate(0x7f0e0086) )         // 主页 custom view
        │     actionBar.j() ─ findViewById(0x7f0a0158)   // 左上角 LinearLayout【注入点】
        │                      findViewById(0x7f0a0159)   // backBtn/多任务
        │  if(this.r.m()) return;                        // 聊天态：不动 ActionBar
        ▼
NewChattingTabUI.m()Z = (o!=null && o.f.i)               // isChattingForeground
MainTabUI.g() -> 当前 MMFragment(需 == MainUI)

[聊天页] com.tencent.mm.ui.chatting.component.fg.F0()
        └ actionBar.y( inflate(0x7f0e0068) )             // 聊天 custom view，同含 0x7f0a0158
        （与主页共用同一 ActionBar → 无门控即串显，见 §8）
```

---

## 3. 定位表（当前样本实测 + 动态定位法 + 门控字段）

| 语义 | 签名 | 当前样本 | 动态定位（DexKit） |
|---|---|---|---|
| 主页 Activity | class | `com.tencent.mm.ui.LauncherUI` | usingStrings `"MicroMsg.LauncherUI"` |
| ActionBar 控制器 | class | `com.tencent.mm.ui.HomeUI` | usingStrings `"[initActionBar] mActionBar == null"` |
| initActionBar 方法 | `()V` | `HomeUI.m()` | inClass=HomeUI usingStrings 上行 |
| **门控：聊天前台标志** | 字段→方法 | `HomeUI.r`:`m8` → `m()Z` | 接口实现类=`NewChattingTabUI`；`m()Z`=`o!=null&&o.f.i` |
| **门控：当前 Tab Fragment** | 字段→方法 | `HomeUI.t`:`MainTabUI` → `g():MMFragment` | inClass=MainTabUI returnType=`...MMFragment` paramCount=0 |
| ActionBar 字段 | 字段 | `HomeUI.c`:`androidx.appcompat.app.b` | findField inClass=HomeUI type=`androidx.appcompat.app.b` |
| getCustomView | `()View` | `b.j()` | inClass=b returnType=`android.view.View` paramCount=0（**唯一**） |
| setCustomView | `(View)V` | `b.y(View)` | inClass=b paramTypes=[View] returnType=void（≠带 LayoutParams 的 `z`） |
| 主页 custom view 布局 | layout | `0x7f0e0086`(2131624054) | `HomeUI.m()` inflate 常量 |
| 聊天 custom view 布局 | layout | `0x7f0e0068`(2131624040) | `fg.I0()` inflate 常量 |
| **左上角容器** | id | `0x7f0a0158`(2131362136) LinearLayout | 主页/聊天布局共有，`HomeUI.i()` 强转 LinearLayout |
| backBtn | id | `0x7f0a0159`(2131362137) | 主页/聊天共有 |
| 消息页 Fragment | class | `com.tencent.mm.ui.conversation.MainUI` | MainTabUI.b() 字符串 `"MainUI"` |
| 兜底容器 Activity | class | `com.tencent.mm.ui.conversation.BaseConversationUI` | extends `MMFragmentActivity` |

---

## 4. 注入原理

1. `HomeUI.m()` 每次 `LauncherUI.onResume`/`closeChatting` 触发，after 时主页 custom view 已 attach。
2. 必须加**双门控**，否则聊天态时 `m()` 提前 return、`getCustomView()` 返回的是**聊天布局**，`0x7f0a0158` 照样命中 → 按钮串到聊天窗口（§8）。
3. 过门控后：`actionBar.j()` → `findViewById(0x7f0a0158)` → `addView(btn, 0)` 插最左。
4. 每次 inflate 新 View，天然不重复；仍用 `Tag` 双保险。

---

## 5. 完整独立 Xposed 模块代码（v2 修正版）

### 5.1 `MainHook.java`

```java
package com.your.module.wechat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG   = "WXLeftTopInject";
    private static final String WX    = "com.tencent.mm";
    private static final String HOME_UI      = "com.tencent.mm.ui.HomeUI";
    private static final String BASE_CONV_UI = "com.tencent.mm.ui.conversation.BaseConversationUI";
    private static final String MAIN_UI      = "com.tencent.mm.ui.conversation.MainUI";
    private static final String ACTIONBAR_IMPL = "androidx.appcompat.app.b";

    private static final int ID_LEFT_CONTAINER = 0x7f0a0158; // 2131362136 左上角容器
    private static final String BTN_TAG = "wx_lefttop_module_entry";

    // 你的模块入口（改成自己的）
    private static final String MY_PKG      = "com.your.module";
    private static final String MY_ACTIVITY = "com.your.module.EntryActivity";

    private static Field  sActionBarField;
    private static Method sGetCustomView;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WX.equals(lpparam.packageName)) return;
        hookHomeUI(lpparam);            // 主方案（带双门控）
        hookBaseConversation(lpparam);  // 兜底（天然门控）
        XposedBridge.log(TAG + " loaded for " + lpparam.packageName);
    }

    // ============ 主方案：HomeUI.m() + 双门控 ============
    private void hookHomeUI(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(HOME_UI, lpparam.classLoader, "m",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object homeUI = param.thisObject;
                            if (!isHomeMain(homeUI)) return;          // ①聊天态 ②非MainUI → 跳过
                            Object actionBar = getActionBarField(homeUI);
                            if (actionBar == null) return;
                            View custom = invokeGetCustomView(actionBar);
                            if (custom != null) injectButton(param, custom);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " home err: " + t);
                        }
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hookHomeUI fail: " + t); }
    }

    /** 双门控：非聊天前台 且 当前 Tab==MainUI */
    private boolean isHomeMain(Object homeUI) {
        try {
            Object r = XposedHelpers.getObjectField(homeUI, "r");      // m8 -> NewChattingTabUI
            if (r != null && (boolean) XposedHelpers.callMethod(r, "m")) return false;
            Object t = XposedHelpers.getObjectField(homeUI, "t");      // MainTabUI
            if (t == null) return false;
            Object frag = XposedHelpers.callMethod(t, "g");            // 当前 MMFragment
            return MAIN_UI.equals(frag == null ? null : frag.getClass().getName());
        } catch (Throwable e) { return false; }
    }

    // ============ 兜底：BaseConversationUI.getActionBarCustomView() ============
    // 仅 e7() 在"非聊天态"调用它，故 param.result 必为主页布局，天然带门控
    private void hookBaseConversation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(BASE_CONV_UI, lpparam.classLoader,
                "getActionBarCustomView", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.getResult();
                        if (v != null) injectButton(param, v);
                    }
                });
        } catch (Throwable ignore) {}
    }

    // ============ 注入 ============
    private void injectButton(XC_MethodHook.MethodHookParam param, View customView) {
        ViewGroup target = findLeftContainer(customView);
        if (target == null || target.findViewWithTag(BTN_TAG) != null) return;
        final Context ctx = customView.getContext();

        ImageView btn = new ImageView(ctx);
        btn.setTag(BTN_TAG);
        btn.setImageResource(android.R.drawable.ic_menu_edit);
        btn.setColorFilter(Color.parseColor("#07C160"));
        btn.setBackground(circle(ctx));

        int size = dp(ctx, 24);
        ViewGroup.LayoutParams lp;
        if (target instanceof LinearLayout) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(size, size);
            l.gravity = Gravity.CENTER_VERTICAL; l.leftMargin = dp(ctx,6); l.rightMargin = dp(ctx,6);
            lp = l;
        } else {
            ViewGroup.MarginLayoutParams l = new ViewGroup.MarginLayoutParams(size, size);
            l.leftMargin = dp(ctx,6); l.rightMargin = dp(ctx,6); lp = l;
        }
        btn.setLayoutParams(lp);
        btn.setContentDescription("模块入口");
        btn.setOnClickListener(v -> {
            try {
                Intent i = new Intent().setClassName(MY_PKG, MY_ACTIVITY);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(ctx, "打开模块失败", Toast.LENGTH_SHORT).show();
                XposedBridge.log(TAG + " start err: " + t);
            }
        });
        target.addView(btn, 0);
        XposedBridge.log(TAG + " injected into " + target.getClass().getName());
    }

    private ViewGroup findLeftContainer(View customView) {
        View v = null;
        try { v = customView.findViewById(ID_LEFT_CONTAINER); } catch (Throwable ignore) {}
        if (v instanceof ViewGroup) return (ViewGroup) v;
        return (customView instanceof ViewGroup) ? (ViewGroup) customView : null;
    }

    private android.graphics.drawable.Drawable circle(Context ctx) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(Color.parseColor("#1A07C160"));
        return d;
    }

    // ============ 反射：ActionBar 字段 & getCustomView ============
    private Object getActionBarField(Object homeUI) {
        if (homeUI == null) return null;
        try {
            if (sActionBarField == null) {
                sActionBarField = homeUI.getClass().getDeclaredField("c");
                sActionBarField.setAccessible(true);
            }
            return sActionBarField.get(homeUI);
        } catch (Throwable t) {
            try {
                for (Field f : homeUI.getClass().getDeclaredFields())
                    if (f.getType().getName().equals(ACTIONBAR_IMPL)) { f.setAccessible(true); return f.get(homeUI); }
            } catch (Throwable ignore) {}
            return null;
        }
    }

    private View invokeGetCustomView(Object actionBar) throws Exception {
        if (actionBar == null) return null;
        if (sGetCustomView == null) {
            for (Method m : actionBar.getClass().getDeclaredMethods())
                if (m.getParameterTypes().length == 0 && View.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true); sGetCustomView = m; break;
                }
        }
        return sGetCustomView == null ? null : (View) sGetCustomView.invoke(actionBar);
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
```

### 5.2 `AndroidManifest.xml`
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.your.module.wechat">
    <application android:label="WXLeftTopInject"/>
</manifest>
```

### 5.3 `assets/xposed_init`
```
com.your.module.wechat.MainHook
```

### 5.4 `build.gradle`
```gradle
dependencies { compileOnly 'de.robv.android.xposed:api:82' }
```

> LSPosed 勾选「微信」即可，微信内无需任何脚本。

---

## 6. 绝对不要这样 Hook（会必然串显/全局污染）

| 禁用 Hook 点 | 原因 |
|---|---|
| `com.tencent.mm.ui.j.<init>(View)` | 主页(`HomeUI.m`)、聊天(`fg.F0`)、BaseConversationUI 共用同一 helper，双端注入 |
| `androidx.appcompat.app.b.y(View)` | setCustomView 被全 App 复用，且主页/聊天都调 |
| `BaseConversationUI.e7()` 直接拿 customView | e7 在聊天态 return，之后 `mActionBar`/`mActionBarHelper` 仍是旧值，拿到的不是主页布局 |

只 Hook `HomeUI.m()`(带 §5.1 双门控) 或 `BaseConversationUI.getActionBarCustomView()`。

---

## 7. 二次核查清单（v2 已逐项核对）

| # | 核查点 | 结论 |
|---|---|---|
| 1 | 主页 Activity | ✅ Manifest 主入口 `com.tencent.mm.ui.LauncherUI` |
| 2 | 主页 ActionBar 入口 | ✅ `HomeUI.m()`，日志 `[initActionBar] mActionBar == null` |
| 3 | `HomeUI.m()` 频率 | ✅ 仅 `LauncherUI.onResume` / `closeChatting` 调用 |
| 4 | 聊天态门控 | ✅ `NewChattingTabUI.m()Z = o!=null && o.f.i`，`HomeUI.m() if(this.r.m())return` |
| 5 | 当前 Tab 判定 | ✅ `MainTabUI.g():MMFragment`，比对 `com.tencent.mm.ui.conversation.MainUI` |
| 6 | ActionBar 字段/方法 | ✅ `HomeUI.c:androidx.appcompat.app.b`；`b.j()` 为唯一「无参返回View」 |
| 7 | 主页布局 | ✅ `0x7f0e0086`；左上角容器 `0x7f0a0158`(LinearLayout) |
| 8 | 聊天布局（串显源） | ✅ `fg.I0()` inflate `0x7f0e0068`，同含 `0x7f0a0158` |
| 9 | 主页/聊天共用 ActionBar | ✅ `HomeUI.m` 与 `fg.F0` 均 `actionBar.y(...)` 于 LauncherUI |
| 10 | 双门控后不串显 | ✅ 聊天态 `r.m()`=true 直接 return；非 MainUI return |
| 11 | 兜底天然门控 | ✅ `getActionBarCustomView()` 仅被非聊天态 `e7()` 调用 |
| 12 | 重复注入 | ✅ 每次 inflate 新 View + `Tag` 双保险 |
| 13 | 线程 | ✅ Hook 点在 UI 线程，直接操作 View |
| 14 | 文件导出 | ✅ `/sdcard/Download/WeChat_LeftTop_Inject_Analysis.md`（本文件 v2） |

---

## 8. 串显根因 & v2 修正记录

### 8.1 根因（已实证）
主页与聊天**共用 LauncherUI 同一 ActionBar**，`HomeUI.m()` 在聊天态（`this.r.m()`=true）**直接 return，不挂主页布局**；但你 hook 的 after 仍执行，`actionBar.j()` 返回的是**聊天页 `fg.F0()` 挂上的聊天布局(0x7f0e0068)**，该布局**同样含 `0x7f0a0158`** → `findViewById` 命中 → 按钮加到聊天窗口。

### 8.2 v2 修正点
1. **主 Hook 增双门控**：`HomeUI.r.m()`==false **且** `HomeUI.t.g()`==`MainUI` 才注入（§5.1 `isHomeMain`）。
2. **兜底改 Hook `getActionBarCustomView()`**（非 `e7()`）：仅主页态被调用，`param.result` 必为主页布局，天然免门控。
3. **禁用共用链路**：`j.<init>` / `b.y()` / `e7()` 不得直接注入（§6）。
4. `MainUI` 判定由 `endsWith` 改为**全名 equals**，避免误配。
5. `getActionBarField`/`invokeGetCustomView` 保留类型兜底，抗字段/方法名漂移。

---

## 9. 版本兼容与风险

1. **混淆/资源 ID 漂移**：`HomeUI`/`c`/`r`/`t`/`j()`/`0x7f0a0158` 跨版本可能变。建议接入 DexKit 动态定位（§3 右列），失败时走 §5.1 类型兜底。
2. **加固**：强加固下类可能延迟加载，`findAndHookMethod` 需配合 `deoptimize`/延迟 Hook 或监听 `Activity.onResume` 二次触发。
3. **多入口**：通知/快捷方式可能走 `BaseConversationUI`，已由 §5.1 兜底覆盖。
4. **只要 MainUI**：若你想在「通讯录/发现/我」也显示，去掉 §5.1 的 `MAIN_UI` 比对即可（但仍保留聊天态门控）。

---

## 10. 调试技巧

- 日志过滤 `WXLeftTopInject`，应见 `injected into ...LinearLayout`。
- 按钮没出现：`adb shell dumpsys activity top | grep -i launcher` 确认前台 `LauncherUI`；临时在 `isHomeMain` 打点 `r.m()` 与 `t.g()` 的类名，确认门控放行。
- 仍串到聊天：确认没有同时启用 §6 的禁用 Hook；打印 `custom.getClass()` 与 `findViewById(0x7f0a0158)` 父链核对布局来源。
- 换图标：`btn.setImageResource(...)` 改为微信自身 drawable：`ctx.getResources().getIdentifier("name","drawable",WX)`。

---

*生成方式：LSPilot 定位(search/find) → 检查(view_strings/inspect) → 取证(decompile/smali) → 交叉核验 → 修正（门控）→ 导出 v2。*
