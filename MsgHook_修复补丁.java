/**
 * 只改两个方法，不改整体结构
 * 在现有工作代码的 clean() / speak() 里加这几行即可
 */

// ===== 改动1: clean() 第一行加 =====
s = s.replaceFirst("^wxid_[a-z0-9]+:[\u00A0\\s]*", "");
s = s.replaceFirst("^[a-z][a-z0-9]+@chatroom:", "");  // 备用

// ===== 改动2: speak() 昵称改用DB直查 =====
// 替换 NicknameResolver.resolve(talker) 为:
String name = getNickFromDB(talker);

// ===== 改动3: 加这个DB直查方法 =====
static java.util.Map<String,String> nickCache = new java.util.HashMap<>();
static android.database.sqlite.SQLiteDatabase nickDb;

static String getNickFromDB(String talker) {
    if (talker == null || talker.isEmpty()) return "";
    String cached = nickCache.get(talker);
    if (cached != null) return cached;

    try {
        if (nickDb == null) {
            java.io.File mm = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            String[] dirs = mm.list();
            if (dirs != null) for (String d : dirs) if (d.length() == 32) {
                nickDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                    mm.getAbsolutePath() + "/" + d + "/EnMicroMsg.db",
                    null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                break;
            }
        }
        if (nickDb != null) {
            android.database.Cursor c = nickDb.rawQuery(
                "SELECT conRemark, nickname FROM rcontact WHERE username=? LIMIT 1",
                new String[]{talker});
            if (c.moveToFirst()) {
                String remark = c.getString(0);
                String nick = c.getString(1);
                if (remark != null && !remark.isEmpty()) { nickCache.put(talker, remark); return remark; }
                if (nick != null && !nick.isEmpty()) { nickCache.put(talker, nick); return nick; }
            }
            c.close();
        }
    } catch (Throwable ignored) {}

    String fallback = talker.endsWith("@chatroom") ? "群聊" :
                       talker.startsWith("gh_") ? "公众号" : talker;
    if (fallback.length() > 20) fallback = fallback.substring(0, 20);
    nickCache.put(talker, fallback);
    return fallback;
}
