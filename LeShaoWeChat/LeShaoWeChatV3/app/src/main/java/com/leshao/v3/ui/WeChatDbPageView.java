package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ContactRepository;

import java.util.List;

/**
 * 微信数据库直接读取功能页。
 *
 * 调用参数严格对齐《数据库直读整套资料》(com.lspilot.wxpicker)：
 *   - 联系人表 rcontact（字段 username/nickname/alias/conRemark/type/contactLabelIds/
 *     verifyFlag/deleteFlag/pyInitial/quanPin/conRemarkPYFull/showHead/createTime）
 *   - 群聊表 chatroom（字段 chatroomname/displayname/memberlist/memberCount/roomowner/addtime）
 *   - 标签表 ContactLabel（字段 labelID/labelName/labelPYFull/labelPYShort/createTime/isTemporary/lastUseTime）
 *   - 性别：rcontact 无 sex 列（微信 8.x 不写旧 contact 表），仅提供说明与 contact 表探测
 */
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

        // ============ 快捷查询（保留原有功能） ============
        LinearLayout cardQuick = PageKit.makeCard(ctx, d);
        cardQuick.addView(PageKit.sectionLabel(ctx, "快捷查询"));
        cardQuick.addView(PageKit.actionButton(ctx, "群聊列表", v ->
                queryAsync("SELECT chatroomname, displayname FROM chatroom LIMIT 50",
                        null, 50, result)));
        cardQuick.addView(PageKit.bodyText(ctx, ""));
        cardQuick.addView(PageKit.actionButton(ctx, "群成员数 Top", v ->
                queryAsync("SELECT chatroomname, "
                        + "(LENGTH(memberlist)-LENGTH(REPLACE(memberlist,';',''))+1) AS memberCount "
                        + "FROM chatroom WHERE memberlist IS NOT NULL "
                        + "ORDER BY memberCount DESC LIMIT 50", null, 50, result)));
        cardQuick.addView(PageKit.bodyText(ctx, ""));
        cardQuick.addView(PageKit.actionButton(ctx, "最近消息", v ->
                queryAsync("SELECT talker, type, createTime, substr(content,1,60) AS content "
                        + "FROM message ORDER BY createTime DESC LIMIT 50", null, 50, result)));
        cardQuick.addView(PageKit.bodyText(ctx, ""));
        cardQuick.addView(PageKit.actionButton(ctx, "联系人统计", v ->
                queryAsync("SELECT COUNT(*) AS rcontact FROM rcontact", null, 1, result,
                        "rcontact 总数：%s")));
        root.addView(cardQuick);
        root.addView(PageKit.divider(ctx));

        // ============ 资料包增强：联系人 / 群聊 / 标签 / 性别 ============
        LinearLayout cardEnhance = PageKit.makeCard(ctx, d);
        cardEnhance.addView(PageKit.sectionLabel(ctx, "资料包增强查询（联系人/群聊/标签/性别）"));

        cardEnhance.addView(PageKit.actionButton(ctx, "联系人列表（完整字段）", v ->
                queryAsync("SELECT username, nickname, alias, conRemark, pyInitial, quanPin, "
                        + "conRemarkPYFull, type, showHead, contactLabelIds, verifyFlag, deleteFlag, "
                        + "chatroomFlag, createTime "
                        + "FROM rcontact WHERE deleteFlag = 0 "
                        + "AND (type & 1) != 0 AND (type & 32) = 0 AND (type & 8) = 0 "
                        + "AND username NOT LIKE '%@chatroom' "
                        + "AND username NOT LIKE '%@im.chatroom' "
                        + "AND username NOT LIKE '%@openim' "
                        + "AND username NOT LIKE 'gh_%' "
                        + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 "
                        + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC LIMIT 200",
                        null, 200, result)));
        cardEnhance.addView(PageKit.bodyText(ctx, ""));
        cardEnhance.addView(PageKit.bodyText(ctx,
                "说明：conRemark=备注、alias=微信号、type=类型位、contactLabelIds=标签ID列表（逗号分隔）、verifyFlag=验证状态、deleteFlag=删除标记。"));

        cardEnhance.addView(PageKit.actionButton(ctx, "群聊列表（含群主/成员数）", v ->
                queryAsync("SELECT c.chatroomname, c.displayname, c.memberCount, "
                        + "c.roomowner, c.addtime, "
                        + "(LENGTH(c.memberlist)-LENGTH(REPLACE(c.memberlist,';',''))+1) AS memberCnt "
                        + "FROM chatroom c ORDER BY c.addtime DESC LIMIT 200",
                        null, 200, result)));
        cardEnhance.addView(PageKit.bodyText(ctx, ""));
        cardEnhance.addView(PageKit.bodyText(ctx,
                "说明：memberCount 可能为 -1，memberCnt 按 memberlist 分号数本地计算；displayname=群显示名、roomowner=群主username。"));

        cardEnhance.addView(PageKit.actionButton(ctx, "标签列表（ContactLabel 表）", v ->
                queryAsync("SELECT labelID, labelName, labelPYFull, labelPYShort, "
                        + "createTime, isTemporary, lastUseTime "
                        + "FROM ContactLabel WHERE isTemporary = 0 ORDER BY createTime ASC LIMIT 200",
                        null, 200, result)));
        cardEnhance.addView(PageKit.bodyText(ctx, ""));
        cardEnhance.addView(PageKit.bodyText(ctx,
                "说明：labelID 即联系人 field_contactLabelIds 逗号分隔列表中的数字ID。"));

        // 标签成员查询：输入 labelID
        final EditText labelEt = makeEditText(ctx, d, "输入标签ID（如 1）");
        cardEnhance.addView(labelEt);
        cardEnhance.addView(PageKit.actionButton(ctx, "查询标签成员", v -> {
            String id = labelEt.getText().toString().trim();
            if (id.isEmpty()) {
                result.setText("请先输入标签ID。");
                return;
            }
            queryAsync("SELECT username, nickname, conRemark, alias, type, contactLabelIds "
                    + "FROM rcontact WHERE deleteFlag = 0 AND ( "
                    + "contactLabelIds = ? OR contactLabelIds LIKE ? || ',%' "
                    + "OR contactLabelIds LIKE '%,' || ? "
                    + "OR contactLabelIds LIKE '%,' || ? || ',%') LIMIT 200",
                    new String[]{id, id, id, id}, 200, result);
        }));
        cardEnhance.addView(PageKit.bodyText(ctx, ""));

        // 性别：资料包结论 + contact 表探测
        cardEnhance.addView(PageKit.actionButton(ctx, "性别探测（旧 contact 表）", v ->
                queryAsync("SELECT name, sex FROM contact LIMIT 20", null, 20, result)));
        cardEnhance.addView(PageKit.bodyText(ctx, ""));
        cardEnhance.addView(PageKit.actionButton(ctx, "性别说明", v -> result.setText(
                "资料包结论：\n"
                        + "1. rcontact 表 27 列中没有 sex 列；\n"
                        + "2. 旧版 contact 表虽有 sex INT，但微信 8.x 已不写该表；\n"
                        + "3. 本地库中与性别相关的只有 BizFansContact（公众号粉丝），仅企业微信侧有效；\n"
                        + "4. 推荐做法：用「标签」做男女分组（联系人 field_contactLabelIds 离线过滤）。")));
        root.addView(cardEnhance);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardResult = PageKit.makeCard(ctx, d);
        cardResult.addView(PageKit.sectionLabel(ctx, "结果"));
        cardResult.addView(result);
        root.addView(cardResult);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "说明：直接复用微信已打开的 WCDB 连接做只读 rawQuery，不新开库、不解密、不修改。"
                        + "字段名/表名与《数据库直读整套资料》一致，可放心作为二次开发调用参数。"));
        root.addView(cardNote);
        return root;
    }

    private static EditText makeEditText(Context ctx, float d, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setTextSize(14);
        et.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(AppColors.inputBg());
        bg.setCornerRadius((int) (AppColors.SHAPE_SM_DP * d));
        bg.setStroke((int) (1.5f * d), AppColors.candyPink());
        et.setBackground(bg);
        return et;
    }

    private static void queryAsync(final String sql, final String[] args,
                                   final int limit, final TextView out) {
        queryAsync(sql, args, limit, out, null);
    }

    /** 异步查询，避免大数据量（联系人/群聊/标签）阻塞主线程。 */
    private static void queryAsync(final String sql, final String[] args,
                                   final int limit, final TextView out, final String format) {
        out.setText("查询中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = WeChatDbPageView.run(sql, args, limit, format);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        out.setText(text);
                    }
                });
            }
        }).start();
    }

    private static String run(String sql, String[] args, int limit) {
        return run(sql, args, limit, null);
    }

    private static String run(String sql, String[] args, int limit, String format) {
        if (!ContactRepository.isDbReady()) return "数据库未就绪。";
        try {
            List<ContactRepository.DbRow> rows = ContactRepository.rawQuery(sql, args, limit);
            if (rows.isEmpty()) return "无数据。";
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(rows.size()).append(" 行\n");
            for (ContactRepository.DbRow r : rows) {
                for (int i = 0; i < r.cols.length; i++) {
                    sb.append(r.cols[i]).append("=").append(r.vals[i]).append("  ");
                }
                sb.append('\n');
            }
            return format == null ? sb.toString() : String.format(format, rows.get(0).vals[0]);
        } catch (Throwable t) {
            return "查询失败：" + t;
        }
    }
}