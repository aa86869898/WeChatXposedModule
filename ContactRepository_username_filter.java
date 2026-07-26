/**
 * ContactRepository — 用 username 后缀过滤版本
 * =============================================
 * 不用 type 字段，纯用 username 后缀规则区分好友/群聊/公众号
 * 避免 type=0 在不同版本微信中的值差异
 */
public class ContactRepository {

    // ═══════════════════════════════════════════════════
    // 核心过滤规则 — 用 username 后缀
    // ═══════════════════════════════════════════════════

    /**
     * 只需判断 username 后缀即可区分：
     * 
     * @chatroom  → 群聊
     * gh_       → 公众号
     * @openim   → OpenIM
     * @app      → 应用
     * 其他      → 好友（唯一保留的）
     */
    public static boolean isFriend(String username) {
        if (username == null || username.isEmpty()) return false;
        // 排除群聊
        if (username.endsWith("@chatroom")) return false;
        // 排除公众号
        if (username.startsWith("gh_")) return false;
        // 排除系统账号
        if (username.equals("weixin")) return false;
        if (username.equals("filehelper")) return false;
        if (username.equals("medianote")) return false;
        if (username.equals("newsapp")) return false;
        if (username.equals("floatbottle")) return false;
        if (username.equals("blog_app")) return false;
        if (username.equals("masssendapp")) return false;
        if (username.equals("meishiapp")) return false;
        if (username.equals("fmessage")) return false;
        if (username.equals("voipapp")) return false;
        if (username.equals("officialaccounts")) return false;
        if (username.equals("helper_entry")) return false;
        if (username.equals("pc_share")) return false;
        if (username.equals("cardpackage")) return false;
        if (username.equals("googlecontact")) return false;
        if (username.equals("linkedincontact")) return false;
        if (username.equals("mobileconta")) return false;
        if (username.startsWith("qqmail_")) return false;
        // 排除其他内置账户
        if (username.contains("@lbsroom")) return false;
        if (username.contains("@openim")) return false;
        if (username.contains("@im.chatroom")) return false;
        if (username.endsWith("@stranger")) return false;
        if (username.endsWith("@app")) return false;
        if (username.endsWith("@talkroom")) return false;
        // 剩下的就是好友
        return true;
    }

    public static boolean isGroup(String username) {
        return username != null && username.endsWith("@chatroom");
    }

    // ═══════════════════════════════════════════════════
    // queryContactsWcdb — 不分 type，只按 username 后缀分
    // ═══════════════════════════════════════════════════

    private static boolean queryContactsWcdb(Object db) {
        List<Contact> all = new ArrayList<>();
        List<Contact> friends = new ArrayList<>();
        List<Contact> groups = new ArrayList<>();
        Object cursor = null;

        try {
            // ⚠️ ka5 wrapper 只有 u() 方法，没有 rawQuery()
            //    Strategy A 的错误日志确认了这一点
            //    所以这里用 u()，但通过 getColumnIndex 取列
            String sql = "SELECT * FROM rcontact"
                + " WHERE deleteFlag = 0"
                + " ORDER BY"
                + "   CASE WHEN username LIKE '%@chatroom' THEN 1 ELSE 0 END,"
                + "   CASE WHEN conRemark IS NOT NULL AND conRemark != '' THEN 0 ELSE 1 END,"
                + "   nickname";

            cursor = (Cursor) XposedHelpers.callMethod(db, "u", sql, null);

            int idxU = colIdx(cursor, "username");
            int idxN = colIdx(cursor, "nickname");
            int idxA = colIdx(cursor, "alias");
            int idxR = colIdx(cursor, "conRemark");
            int idxT = colIdx(cursor, "type");

            int friendCount = 0, groupCount = 0;
            while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                String wxid = colStr(cursor, idxU);
                if (wxid == null || wxid.isEmpty()) continue;

                String nickname = colStr(cursor, idxN);
                String alias = colStr(cursor, idxA);
                String remark = colStr(cursor, idxR);
                int type = colInt(cursor, idxT);

                String displayName = computeDisplayName(remark, alias, nickname, wxid);

                Contact contact = new Contact(wxid, nickname, remark, alias, type, 0, 0);
                contact.displayName = displayName;

                if (isGroup(wxid)) {
                    groups.add(contact);
                    groupCount++;
                } else if (isFriend(wxid)) {
                    friends.add(contact);
                    friendCount++;
                }
                all.add(contact);
            }
            XposedHelpers.callMethod(cursor, "close");

            LogWriter.log(TAG, "queryContactsWcdb: friends=" + friendCount
                + " groups=" + groupCount + " total=" + all.size());

            // 打印前5条好友验证
            for (int i = 0; i < Math.min(5, friends.size()); i++) {
                Contact c = friends.get(i);
                LogWriter.log(TAG, "  friend[" + i + "] " + c.displayName
                    + " (" + c.wxid + ") type=" + c.type);
            }

            if (all.isEmpty()) return false;
            sAllContacts = all;
            sFriends = friends;
            sGroups = groups;
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "queryContactsWcdb ERROR: " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
        }
    }

    /**
     * 显示名计算
     */
    private static String computeDisplayName(String remark, String alias, 
            String nick, String wxid) {
        if (remark != null && !remark.isEmpty()) return remark;
        if (alias != null && !alias.isEmpty() && !alias.startsWith("wxid_")) return alias;
        if (nick != null && !nick.isEmpty()) return nick;
        return wxid;
    }

    // ═══════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════

    private static int colIdx(Object cursor, String name) {
        return (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", name);
    }

    private static String colStr(Object cursor, int idx) {
        if (idx < 0) return "";
        try { return (String) XposedHelpers.callMethod(cursor, "getString", idx); }
        catch (Throwable t) { return ""; }
    }

    private static int colInt(Object cursor, int idx) {
        if (idx < 0) return 0;
        try { return (Integer) XposedHelpers.callMethod(cursor, "getInt", idx); }
        catch (Throwable t) { return 0; }
    }
}
