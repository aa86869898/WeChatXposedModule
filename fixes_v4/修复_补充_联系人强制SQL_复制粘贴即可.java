/**
 * 联系人 all=124 → 应 14217
 * 
 * 在 loadContacts() 中把 SQL 改成:
 */

Cursor c = db.rawQuery(
    "SELECT username, nickname, conRemark, alias, type, verifyFlag " +
    "FROM rcontact WHERE type IN (0,1) AND verifyFlag=0 " +
    "AND username NOT LIKE 'gh_%' " +
    "ORDER BY CASE WHEN username LIKE '%@chatroom' THEN 1 ELSE 0 END, username",
    null);
