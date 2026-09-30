package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.hook.MsgForgeHook;
import com.leshao.v3.ui.widgets.M3Page;

/** 消息伪装功能页（文档《WeChat_MsgForge_Analysis.md》）。 */
public final class MsgForgePageView {

    private MsgForgePageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        MsgForgeHook.updateConfig();

        // 总开关
        LinearLayout cardSwitch = PageKit.makeCard(ctx, d);
        cardSwitch.addView(PageKit.switchRow(ctx, d, "消息伪装",
                "发出的文本伪装成系统消息 / 名片 / 链接卡片", MsgForgeHook.isEnabled(),
                (v, on) -> {
                    MsgForgeHook.setEnabled(on);
                    Toast.makeText(ctx, "消息伪装已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardSwitch);
        root.addView(PageKit.divider(ctx));

        // 文案输入框（切换类型时同步刷新内容）与预览
        final EditText input = M3Page.input(ctx, "");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(currentContent());
        final TextView previewText = PageKit.bodyText(ctx, MsgForgeHook.preview());

        // 伪装类型（互斥开关：同时只允许一种生效）
        final String[] modeHolder = {MsgForgeHook.getMode()};
        final Switch[] swSystem = new Switch[1];
        final Switch[] swCard = new Switch[1];
        final Switch[] swApp = new Switch[1];
        LinearLayout cardMode = PageKit.makeCard(ctx, d);
        cardMode.addView(PageKit.sectionLabel(ctx, "伪装类型（同时只启用一种）"));
        cardMode.addView(PageKit.switchRow(ctx, d, "系统消息",
                "文本伪装成系统提示（type=10000）", MsgForgeHook.MODE_SYSTEM.equals(modeHolder[0]),
                (v, on) -> {
                    if (on) {
                        if (swCard[0] != null) swCard[0].setChecked(false);
                        if (swApp[0] != null) swApp[0].setChecked(false);
                        MsgForgeHook.setMode(MsgForgeHook.MODE_SYSTEM);
                        modeHolder[0] = MsgForgeHook.MODE_SYSTEM;
                        input.setText(currentContent());
                        previewText.setText(MsgForgeHook.preview());
                        Toast.makeText(ctx, "伪装类型：系统消息", Toast.LENGTH_SHORT).show();
                    } else if ((swCard[0] == null || !swCard[0].isChecked())
                            && (swApp[0] == null || !swApp[0].isChecked())) {
                        swSystem[0].setChecked(true);
                    }
                }, null, swSystem));
        cardMode.addView(PageKit.switchRow(ctx, d, "名片",
                "伪装成发送微信名片（type=42）", MsgForgeHook.MODE_CARD.equals(modeHolder[0]),
                (v, on) -> {
                    if (on) {
                        if (swSystem[0] != null) swSystem[0].setChecked(false);
                        if (swApp[0] != null) swApp[0].setChecked(false);
                        MsgForgeHook.setMode(MsgForgeHook.MODE_CARD);
                        modeHolder[0] = MsgForgeHook.MODE_CARD;
                        input.setText(currentContent());
                        previewText.setText(MsgForgeHook.preview());
                        Toast.makeText(ctx, "伪装类型：名片", Toast.LENGTH_SHORT).show();
                    } else if ((swSystem[0] == null || !swSystem[0].isChecked())
                            && (swApp[0] == null || !swApp[0].isChecked())) {
                        swCard[0].setChecked(true);
                    }
                }, null, swCard));
        cardMode.addView(PageKit.switchRow(ctx, d, "链接卡片",
                "伪装成发送链接卡片（type=49）", MsgForgeHook.MODE_APPMSG.equals(modeHolder[0]),
                (v, on) -> {
                    if (on) {
                        if (swSystem[0] != null) swSystem[0].setChecked(false);
                        if (swCard[0] != null) swCard[0].setChecked(false);
                        MsgForgeHook.setMode(MsgForgeHook.MODE_APPMSG);
                        modeHolder[0] = MsgForgeHook.MODE_APPMSG;
                        input.setText(currentContent());
                        previewText.setText(MsgForgeHook.preview());
                        Toast.makeText(ctx, "伪装类型：链接卡片", Toast.LENGTH_SHORT).show();
                    } else if ((swSystem[0] == null || !swSystem[0].isChecked())
                            && (swCard[0] == null || !swCard[0].isChecked())) {
                        swApp[0].setChecked(true);
                    }
                }, null, swApp));
        root.addView(cardMode);
        root.addView(PageKit.divider(ctx));

        // 文案
        LinearLayout cardContent = PageKit.makeCard(ctx, d);
        cardContent.addView(PageKit.sectionLabel(ctx, "伪装文案（按 | 分隔多项）"));
        cardContent.addView(input);
        cardContent.addView(PageKit.bodyText(ctx,
                "系统消息：直接填文案\n名片：wxid|昵称\n链接卡片：标题|描述|链接"));
        cardContent.addView(PageKit.actionButton(ctx, "保存文案", v -> {
            if (saveContent(input.getText().toString())) {
                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, "保存失败", Toast.LENGTH_SHORT).show();
            }
        }));
        root.addView(cardContent);
        root.addView(PageKit.divider(ctx));

        // 预览
        LinearLayout cardPreview = PageKit.makeCard(ctx, d);
        cardPreview.addView(PageKit.sectionLabel(ctx, "预览"));
        cardPreview.addView(previewText);
        root.addView(cardPreview);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "说明：仅对出站纯文本消息生效（type=1）。核心链路 Hook NetSceneSendMsg 构造器，"
                        + "把消息类型改写为伪装类型并附带配套 XML，由微信原生渲染。部分类型需 flag=1。"));
        root.addView(cardNote);

        return root;
    }

    private static String currentContent() {
        switch (MsgForgeHook.getMode()) {
            case MsgForgeHook.MODE_CARD:
                return MsgForgeHook.getCardWxid() + "|" + MsgForgeHook.getCardNick();
            case MsgForgeHook.MODE_APPMSG:
                return MsgForgeHook.getAppTitle() + "|" + MsgForgeHook.getAppDesc() + "|" + MsgForgeHook.getAppUrl();
            default:
                return MsgForgeHook.getText();
        }
    }

    private static boolean saveContent(String raw) {
        if (raw == null) raw = "";
        switch (MsgForgeHook.getMode()) {
            case MsgForgeHook.MODE_CARD: {
                String[] p = raw.split("\\|", -1);
                if (p.length < 2) return false;
                MsgForgeHook.setCardWxid(p[0].trim());
                MsgForgeHook.setCardNick(p[1].trim());
                return true;
            }
            case MsgForgeHook.MODE_APPMSG: {
                String[] p = raw.split("\\|", -1);
                if (p.length < 3) return false;
                MsgForgeHook.setAppTitle(p[0].trim());
                MsgForgeHook.setAppDesc(p[1].trim());
                MsgForgeHook.setAppUrl(p[2].trim());
                return true;
            }
            default:
                MsgForgeHook.setText(raw);
                return true;
        }
    }
}
