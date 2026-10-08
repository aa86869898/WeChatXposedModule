package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.leshao.v3.hook.FakeLocationHook
import com.leshao.v3.ui.widgets.M3Page

/**
 * 定位伪装功能页 —— 开关 + 微信原生位置选择器选点。
 *
 * v3.0.139 重写：去掉经纬度输入框，改为调用微信原生位置选择器
 * （SoSoProxyUI）选点，坐标直接写入配置。Hook 点见 [FakeLocationHook]
 * （依据《定位伪装最终优化WeChat_FakeLocation_Analysis.md》第 10~12 章，
 * 仅伪造实时共享位置，不影响附近的人 / 发送位置 / 小程序）。
 */
class FakeLocationPageView private constructor() {

    companion object {
        @JvmStatic
        fun create(ctx: Context, act: Activity?): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)

            root.addView(M3Page.section(ctx, "定位伪装",
                    "仅对「实时共享位置」生效，其它定位保持真实"))
            root.addView(M3Page.spacer(ctx, 2f))

            val cardSwitch = PageKit.makeCard(ctx, d)
            cardSwitch.addView(PageKit.switchRow(ctx, d, "开启定位伪装",
                    "开启后共享实时位置时，对方看到的是你选择的坐标",
                    FakeLocationHook.isEnabled(),
                    { _, on ->
                        FakeLocationHook.setEnabled(on)
                        Toast.makeText(ctx, "定位伪装已" + (if (on) "开启" else "关闭")
                                + "（进入实时共享位置生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardSwitch)
            root.addView(PageKit.divider(ctx))

            val cardPick = PageKit.makeCard(ctx, d)
            cardPick.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            cardPick.addView(M3Page.fieldLabel(ctx, "当前伪造坐标（GCJ-02）"))
            cardPick.addView(PageKit.bodyText(ctx,
                    "纬度 " + String.format("%.6f", FakeLocationHook.getLat())
                            + "，经度 " + String.format("%.6f", FakeLocationHook.getLng())))
            cardPick.addView(M3Page.spacer(ctx, 1f))
            cardPick.addView(M3Page.button(ctx, "选择位置") {
                if (act == null) {
                    Toast.makeText(ctx, "未找到微信页面，无法打开位置选择器", Toast.LENGTH_SHORT).show()
                    return@button
                }
                FakeLocationHook.launchPicker(act)
            })
            root.addView(cardPick)
            root.addView(PageKit.divider(ctx))

            val cardNote = PageKit.makeCard(ctx, d)
            cardNote.addView(PageKit.bodyText(ctx,
                    "原理：Hook 实时共享位置监听者 hh3.s0.onGetLocation，把回调中的经纬度替换为你选择的坐标"
                            + "（该链路走 startGcj02，坐标为 GCJ-02）。"
                            + "不 Hook 全局定位，因此附近的人 / 发送位置 / 小程序等仍返回真实坐标。"
                            + "在聊天中发起「共享实时位置」即可看到效果。"))
            root.addView(cardNote)
            return root
        }
    }
}