package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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

        // 伪装类型
        final String[] modeHolder = {MsgForgeHook.getMode()};
        LinearLayout cardMode = PageKit.makeCard(ctx, d);
        final TextView modeText = PageKit.bodyText(ctx, "");
        cardMode.addView(PageKit.sectionLabel(ctx, "伪装类型"));
        cardMode.addView(modeText);
        LinearLayout modeBtns = new LinearLayout(ctx);
        modeBtns.setOrientation(LinearLayout.HORIZONTAL);
        modeBtns.addView(PageKit.actionButton(ctx, "系统消息", v -> {
            MsgForgeHook.setMode(MsgForgeHook.MODE_SYSTEM);
            modeHolder[0] = MsgForgeHook.MODE_SYSTEM;
            modeText.setText("当前：系统消息（type=10000）");
        }));
        modeBtns.addView(PageKit.actionButton(ctx, "名片", v -> {
            MsgForgeHook.setMode(MsgForgeHook.MODE_CARD);
            modeHolder[0] = MsgForgeHook.MODE_CARD;
            modeText.setText("当前：名片（type=42）");
        }));
        modeBtns.addView(PageKit.actionButton(ctx, "链接卡片", v -> {
            MsgForgeHook.setMode(MsgForgeHook.MODE_APPMSG);
            modeHolder[0] = MsgForgeHook.MODE_APPMSG;
            modeText.setText("当前：链接卡片（type=49）");
        }));
        cardMode.addView(modeBtns);
        root.addView(cardMode);
        root.addView(PageKit.divider(ctx));

        // 文案
        LinearLayout cardContent = PageKit.makeCard(ctx, d);
        cardContent.addView(PageKit.sectionLabel(ctx, "伪装文案（按 | 分隔多项）"));
        final EditText input = M3Page.input(ctx, "");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(currentContent());
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
        cardPreview.addView(PageKit.bodyText(ctx, MsgForgeHook.preview()));
        root.addView(cardPreview);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "说明：仅对出站纯文本消息生效（type=1）。核心链路 Hook NetSceneSendMsg 构造器，"
                        + "把消息类型改写为伪装类型并附带配套 XML，由微信原生渲染。部分类型需 flag=1。"));
        root.addView(cardNote);

        modeText.setText(modeLabel(MsgForgeHook.getMode()));
        return root;
    }

    private static String modeLabel(String mode) {
        switch (mode) {
            case MsgForgeHook.MODE_CARD: return "当前：名片（type=42）";
            case MsgForgeHook.MODE_APPMSG: return "当前：链接卡片（type=49）";
            default: return "当前：系统消息（type=10000）";
        }
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
