package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ContactRepository;

import java.util.List;

/** 微信数据库直接读取功能页（文档《微信数据库直接读取-逆向分析报告.md》）。 */
public final class WeChatDbPageView {

    private WeChatDbPageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        final TextView result = PageKit.bodyText(ctx, "点击下方按钮开始读取。");
        result.setTextIsSelectable(true);

        LinearLayout cardStatus = PageKit.makeCard(ctx, d);
        cardStatus.addView(PageKit.sectionLabel(ctx, "数据库状态"));
        cardStatus.addView(PageKit.bodyText(ctx, ContactRepository.isDbReady()
                ? "已捕获微信主库（EnMicroMsg.db）句柄，可直接只读查询。"
                : "尚未捕获数据库句柄。请先正常收发一条消息/打开会话，等待微信打开数据库后重试。"));
        root.addView(cardStatus);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardActions = PageKit.makeCard(ctx, d);
        cardActions.addView(PageKit.sectionLabel(ctx, "快捷查询"));
        cardActions.addView(PageKit.actionButton(ctx, "群聊列表", v ->
                result.setText(run("SELECT chatroomname, displayname FROM chatroom LIMIT 50"))));
        cardActions.addView(PageKit.bodyText(ctx, ""));
        cardActions.addView(PageKit.actionButton(ctx, "群成员数 Top", v ->
                result.setText(run("SELECT chatroomname, "
                        + "(LENGTH(memberlist)-LENGTH(REPLACE(memberlist,';',''))+1) AS memberCount "
                        + "FROM chatroom WHERE memberlist IS NOT NULL "
                        + "ORDER BY memberCount DESC LIMIT 50"))));
        cardActions.addView(PageKit.bodyText(ctx, ""));
        cardActions.addView(PageKit.actionButton(ctx, "最近消息", v ->
                result.setText(run("SELECT talker, type, createTime, substr(content,1,60) AS content "
                        + "FROM message ORDER BY createTime DESC LIMIT 50"))));
        cardActions.addView(PageKit.bodyText(ctx, ""));
        cardActions.addView(PageKit.actionButton(ctx, "联系人统计", v ->
                result.setText("rcontact 总数：" + count("SELECT COUNT(*) FROM rcontact")
                        + "\nchatroom 总数：" + count("SELECT COUNT(*) FROM chatroom")
                        + "\nmessage 总数：" + count("SELECT COUNT(*) FROM message"))));
        root.addView(cardActions);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardResult = PageKit.makeCard(ctx, d);
        cardResult.addView(PageKit.sectionLabel(ctx, "结果"));
        cardResult.addView(result);
        root.addView(cardResult);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "说明：直接复用微信已打开的 WCDB 连接做只读 rawQuery，不新开库、不解密、不修改。"
                        + "rcontact=联系人，chatroom=群（memberlist 为分号分隔成员），message=消息。"));
        root.addView(cardNote);
        return root;
    }

    private static String run(String sql) {
        if (!ContactRepository.isDbReady()) return "数据库未就绪。";
        try {
            List<ContactRepository.DbRow> rows = ContactRepository.rawQuery(sql, null, 100);
            if (rows.isEmpty()) return "无数据。";
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(rows.size()).append(" 行\n");
            for (ContactRepository.DbRow r : rows) {
                for (int i = 0; i < r.cols.length; i++) {
                    sb.append(r.cols[i]).append("=").append(r.vals[i]).append("  ");
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "查询失败：" + t;
        }
    }

    private static String count(String sql) {
        if (!ContactRepository.isDbReady()) return "-";
        try {
            List<ContactRepository.DbRow> rows = ContactRepository.rawQuery(sql, null, 1);
            if (!rows.isEmpty() && rows.get(0).vals.length > 0) return rows.get(0).vals[0];
        } catch (Throwable ignored) {}
        return "-";
    }
}
