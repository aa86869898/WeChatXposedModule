# 微信联系人 / 群聊数据读取 —— 独立 Xposed 模块逆向分析与实现方案

- **分析对象**：`com.tencent.mm`（微信，`base.apk`，2292 个组件）
- **分析方式**：DexKit 类/方法/字段/字符串检索 + jadx Java 反编译 + baksmali Smali 交叉验证
- **适用场景**：自研独立 Xposed 模块（不依赖 LSPilot / 不依赖 BSH 脚本），做「联系人选择器」「群成员选择器」
- **报告版本**：v1.0（含二次核查记录，见 §9）

---

## 0. 结论速览（TL;DR）

| 问题 | 结论 |
|---|---|
| 数据在哪 | `/data/data/com.tencent.mm/MicroMsg/{md5("mm"+uin)}/EnMicroMsg.db` |
| 数据库格式 | **WCDB（腾讯开源）封装的 SQLite**，**SQLCipher v1 加密**，pageSize=1024 |
| 读取方式（★★★★★ 推荐） | Hook `com.tencent.wcdb.database.SQLiteDatabase#openDatabase(...)`，直接拿微信**已打开**的 `SQLiteDatabase` 对象，`rawQuery()` 读。**无需算密码、无需处理 WAL** |
| 读取方式（离线/外部进程） | 口令 = `md5_hex(imei + uin).substring(0, 7)`；CipherSpec：pageSize=1024、kdfIteration=64000、hmacEnabled=true、hmacAlgorithm=SHA1、SQLCipherVersion=1 |
| 联系人表 | `rcontact`（含好友、群、公众号、企业微信等全部"联系人"） |
| 群聊表 | `chatroom`（群成员在 `memberlist` 列，**用 `;` 分隔**） |
| 会话列表表 | `rconversation` |
| 聊天记录表 | `message` |
| 自己的 wxid | SharedPreferences 文件 `notify_key_pref_no_account`，键 `login_weixin_username` |
| 头像文件 | `{账号目录}/avatar/{md5(wxid)[0:2]}/{md5(wxid)[2:4]}/user_[hd_]{md5(wxid)}.png` |
| 数据变更通知 | 事件 `com.tencent.mm.autogen.events.ModNewContactEvent` / `ChatroomMemberDataUpdatedEvent` |

---

## 1. 微信存储体系逆向结果

### 1.1 账号目录推导（已核实）

微信每个账号一个独立目录，目录名是 `md5("mm" + uin)`。

**证据链：**

1. 基础数据根目录 `com.tencent.mm.storage.v3.a`：
   ```java
   // com.tencent.mm.storage.v3.<clinit>
   a = qs0.b.e();
   ```
2. `qs0.b.e()`：
   ```java
   public static synchronized String e() {
       return Y() + "MicroMsg/";          // Y() = context.getFilesDir().getParentFile() + "/"
   }
   // qs0.b.Y()
   strArr[0] = context.getFilesDir().getParentFile().getAbsolutePath() + "/";
   ```
   → 即 `/data/data/com.tencent.mm/MicroMsg/`

3. 账号子目录名 `gp0.b0.e(int uin)`（`gp0.b0` = CoreStorage）：
   ```smali
   .method public static e(I)Ljava/lang/String;
       const-string v1, "mm"
       invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(I)  // "mm" + uin
       invoke-static {p0}, Lpk/k;->g([B)Ljava/lang/String;            // MD5 hex 小写
   ```
4. 完整账号目录 `gp0.b0.i(int uin)`：
   ```java
   return com.tencent.mm.storage.u3.a + md5("mm"+uin) + "/";
   ```
5. 主库路径 `gp0.b0.g()`：
   ```java
   public String g() { return h() + "EnMicroMsg.db"; }   // h() = this.e（账号目录）
   ```

**结论：**
```
/data/data/com.tencent.mm/MicroMsg/<md5("mm"+uin)>/EnMicroMsg.db
```
其中 `<md5("mm"+uin)>` 是 32 位小写十六进制。

> 另有 SD 卡路径 `qs0.b.E()` = `f0() + "/MicroMsg/"`（外置存储），默认走内部存储。

### 1.2 加密体系（已核实）

微信主库由 `kh5.f`（LOG tag `MicroMsg.MMDataBase`）打开：

```java
// kh5.f.w(String path, String password, int flags, boolean wal)
public static f w(String str, String str2, int i2, boolean z) {
    byte[] bytes;
    SQLiteCipherSpec sQLiteCipherSpec;
    if (y8.J0(str2)) {                 // 密码为空 → 不加密
        bytes = null; sQLiteCipherSpec = null;
    } else {
        bytes = str2.getBytes();       // 密码直接用 ASCII 字节
        sQLiteCipherSpec = l;          // 静态 SQLiteCipherSpec
    }
    ...
    if (str3.endsWith("EnMicroMsg.db")) {
        fVar.a = SQLiteDatabase.openDatabase(str3, bytes, sQLiteCipherSpec,
                     (SQLiteDatabase.CursorFactory) null, i4, fVar, 32);
    } else {
        fVar.a = SQLiteDatabase.openDatabase(str3, bytes, sQLiteCipherSpec,
                     (SQLiteDatabase.CursorFactory) null, i4, fVar);
    }
    ...
}
```

`kh5.f.<clinit>` 中的 CipherSpec：

```smali
new-instance v0, Lcom/tencent/wcdb/database/SQLiteCipherSpec;
const/16 v1, 0x400                              // 1024
invoke-virtual {v0, v1}, SQLiteCipherSpec;->setPageSize(I)
const/4 v1, 0x1
invoke-virtual {v0, v1}, SQLiteCipherSpec;->setSQLCipherVersion(I)
```

`SQLiteCipherSpec` 默认值（来自构造函数）：
```
kdfIteration  = 64000 (0xFA00)
hmacEnabled   = true
hmacAlgorithm = 0 (HMAC_SHA1)
kdfAlgorithm  = 0
pageSize      = 1024（微信显式覆写）
```

**即：SQLCipher v1 兼容参数 + 1024 字节页 + 64000 次 KDF + SHA1 HMAC。**

### 1.3 数据库口令推导（已核实）

`kh5.b0.R(...)`（LOG tag `MicroMsg.DBInit`）中的核心逻辑：

```java
Iterator it = IMEISave.a().iterator();
while (it.hasNext()) {
    String str6 = (String) it.next();                       // IMEI 候选
    String substring = k.g((str6 + j).getBytes()).substring(0, 7);   // ← 口令
    aVar.b = substring;
    try {
        f w = f.w(str2, substring, 0, true);                // 用它开库
        ...
    } catch (SQLiteException e) { ... 换下一个 IMEI 候选 ... }
}
```

`pk.k.g(byte[])` 已确认为 **MD5 → 小写 hex**：

```java
// pk.k.g(byte[])
MessageDigest messageDigest = MessageDigest.getInstance("MD5");
messageDigest.update(bArr);
byte[] digest = messageDigest.digest();
... 转 16 进制小写字符串 ...
```

IMEI 候选来源 `com.tencent.mm.storagebase.IMEISave.a()`：

```java
public static Collection a() {
    LinkedHashSet set = new LinkedHashSet();
    set.add(w0.g(true));                 // 设备标识（主）
    set.add(w0.g(false));                // 设备标识（备）
    // 解密 files/KeyInfo.bin（RC4，key = "_wEcHAT_"），每行一个候选
    SecretKeySpec key = new SecretKeySpec("_wEcHAT_".getBytes(), "RC4");
    Cipher cipher = Cipher.getInstance("RC4");
    cipher.init(2, key);
    new BufferedReader(new InputStreamReader(
        new CipherInputStream(context.openFileInput("KeyInfo.bin"), cipher)));
    ...
    set.add("1234567890ABCDEF");          // 兜底
    return set;
}
```

**结论（离线解密公式）：**
```
password = md5_hex( imei + uin ).substring(0, 7)
```
- `imei`：优先 `KeyInfo.bin` 解密结果 / `w0.g()`；失败时 `1234567890ABCDEF`
- `uin`：账号 uin（`int` 十进制字符串）
- 开库时 password 用 `getBytes()`（ASCII），**不是 hex 字节**

---

## 2. 核心表结构（已核实）

### 2.1 `rcontact` —— 联系人表

字段清单来自 `com.tencent.mm.storage.j4.D()` 的真实 SELECT：

```sql
select  username, alias, conRemark, domainList, nickname, pyInitial, quanPin,
        showHead, type, uiType, weiboFlag, weiboNickname, conRemarkPYFull,
        conRemarkPYShort, lvbuff, verifyFlag, encryptUsername, chatroomFlag,
        deleteFlag, contactLabelIds, descWordingId, openImAppid, sourceExtInfo,
        rowid, contactExtra
from rcontact
```

| 列名 | 含义 |
|---|---|
| `username` | wxid / gh_xxx / 群 id（**主键**） |
| `alias` | 微信号（用户自己设置的） |
| `conRemark` | 备注名 |
| `nickname` | 微信昵称 |
| `pyInitial` | 昵称拼音首字母（排序/索引用） |
| `quanPin` | 昵称全拼 |
| `conRemarkPYFull` / `conRemarkPYShort` | 备注拼音 |
| `showHead` | 是否显示在通讯录（1 显示） |
| `type` | 类型位掩码（见下） |
| `verifyFlag` | 认证标志位掩码 |
| `chatroomFlag` | 群相关标志 |
| `deleteFlag` | 1 = 已删除 |
| `encryptUsername` | 加密用户名 |
| `lvbuff` | LVBuffer（扩展信息 blob） |
| `contactExtra` | 扩展 blob |
| `domainList` | 企业微信域名列表 |

**"正常联系人" 过滤条件（微信官方口径，来自 `com.tencent.mm.storage.j4.t()`）：**

```java
public static String t(boolean z, boolean z2) {
    String str = (" where (" + (z ? "type & 1!=0 or type & 16!=0" : "type & 1!=0") + ")")
               + " and type & 32=0 " + " and type & 8 =0 ";
    if (!z2) str += " and verifyFlag & 8 =0 ";
    return str + " and deleteFlag = 0";
}
```

即：

```sql
WHERE (type & 1 != 0)          -- 是真实联系人
  AND type & 32 = 0            -- 非特殊/隐藏类
  AND type & 8  = 0            -- 非黑名单
  AND verifyFlag & 8 = 0       -- 过滤公众号/认证号
  AND deleteFlag = 0           -- 未删除
```

**type 位含义（`com.tencent.mm.storage.u3.<clinit>` 的特设用户名映射 + 实测常用值）：**

| 位 | 值 | 含义 |
|---|---|---|
| bit0 | 1 | 是联系人（个人/群/公众号都置位） |
| bit3 | 8 | 黑名单 |
| bit4 | 16 | 聊天室/语音聊天室（`@talkroom` 等） |
| bit5 | 32 | 不在"所有联系人"列表中的类型 |
| bit6 | 64 | 群名片 `@groupcard` |
| bit7 | 128 | `@micromsg.qq.com`（QQ 好友/邮箱） |
| bit9 | 512 | `@t.qq.com`（腾讯微博） |
| bit14| 16384 | `@app` |
| bit16| 65536 | `@openim` |

**群的判定**：`username` 后缀为 `@chatroom`、`@im.chatroom`、`@chatroom_exclusive`（见 `b41.g2.d()` 的完整清单）。

### 2.2 `chatroom` —— 群聊表

字段清单来自 `im.y1.initAutoDBInfo()`（`im.y1` = `BaseChatRoomMember`，LOG tag `MicroMsg.SDK.BaseChatRoomMember`）：

```sql
CREATE TABLE IF NOT EXISTS chatroom (
  chatroomname                    TEXT default ''  PRIMARY KEY,
  addtime                          LONG,
  memberlist                       TEXT,          -- 群成员 wxid 列表，';' 分隔
  displayname                      TEXT,          -- 群昵称表
  chatroomnick                     TEXT,
  roomflag                         INTEGER,
  roomowner                        TEXT,
  roomdata                         BLOB,
  isShowname                       INTEGER,
  selfDisplayName                  TEXT,
  style                            INTEGER,
  chatroomdataflag                 INTEGER,
  modifytime                       LONG,
  chatroomnotice                   TEXT,
  xmlChatroomnotice                TEXT,
  chatroomVersion                  INTEGER,
  chatroomnoticeEditor             TEXT,
  chatroomnoticePublishTime        LONG,
  chatroomNoticeNew                INTEGER,
  chatroomLocalVersion             LONG,
  chatroomStatus                   INTEGER default '0',
  memberCount                      INTEGER default '-1',
  chatroomfamilystatusmodifytime   LONG default '0',
  associateOpenIMRoomName          TEXT,
  openIMRoomMigrateStatus          INTEGER default '0',
  saveByteVersion                  TEXT,
  handleByteVersion                TEXT,
  roomInfoDetailResByte            BLOB,
  oldChatroomVersion               INTEGER,
  localChatRoomWatchMembers        BLOB,
  spamStatus                       INTEGER default '0',
  compactFlag                      LONG default '0',
  qrCodeAccessType                 INTEGER default '0',
  rowid
)
```

**成员列表分隔符是 `;`（不是逗号）**，双证据：

```java
// com.tencent.mm.storage.a3.K1(String)   ← ChatroomStorage
public List K1(String str) {
    String x1 = x1(str);
    ...
    for (String str2 : x1.split(";")) { linkedList.add(str2); }
}
```
```java
// com.tencent.mm.ui.contact.item.h.<clinit>  ← MicroMsg.ChatroomDataItem
";"                                   // H.split(memberlist)
```

**`displayname`（群昵称）**：形如 `wxid1,群昵称1;wxid2,群昵称2` 的原始串，由微信内部 `com.tencent.mm.contact.s.o3()` → `ChatroomStorage.u1(chatroomname)` 解析。
> ⚠️ 该字段原始格式在不同版本可能有差异，建议直接调 `com.tencent.mm.storage.a3.u1()` 拿串后自行 split(";") + split(",")，或在真机 dump 一次确认。

### 2.3 `rconversation` —— 会话列表表

字段清单来自 `com.tencent.mm.storage.l4.A()`：

```sql
select unReadCount, status, isSend, conversationTime, username, content, msgType,
       flag, digest, digestUser, attrflag, editingMsg, atCount, unReadMuteCount,
       UnReadInvite, hasTodo, hbMarkRed, remitMarkRed, hasSpecialFollow
from rconversation
order by flag desc
```

排序键 `flag`（置顶/排序权重）在前，`conversationTime`（毫秒时间戳）在后。

### 2.4 `message` —— 聊天记录表

```sql
CREATE TABLE IF NOT EXISTS message (
  msgId INTEGER PRIMARY KEY, msgSvrId INTEGER, type INT, status INT, isSend INT,
  isShowTimer INTEGER, createTime INTEGER, talker TEXT, content TEXT, imgPath TEXT,
  reserved TEXT, lvbuffer BLOB, transContent TEXT, transBrandWording TEXT,
  talkerId INTEGER, bizClientMsgId TEXT, bizChatId INTEGER DEFAULT -1,
  bizChatUserId TEXT, msgSeq INTEGER, flag INT, solitaireFoldInfo BLOB, historyId TEXT
)
```

---

## 3. 三种读取方案对比

| 方案 | 依赖 | 优点 | 缺点 | 推荐度 |
|---|---|---|---|---|
| **A. Hook WCDB openDatabase** | Xposed，微信进程内 | 零密码计算、零 WAL 处理、版本无关（WCDB 公共 API） | 必须等微信登录后开库 | ★★★★★ |
| **B. 自持 WCDB/SQLCipher 打开 db 文件** | 自己打包 wcdb aar 或 root | 可在任意进程/任意时机读 | 要算 imei+uin，要处理 `-wal`，大库慢 | ★★★ |
| **C. 反射调用微信自身 Storage** | 反混淆类名 | 数据最"干净" | 类名 `j4/a3/l4` 每版都变，极易失效 | ★★ |

> 结论：**主用 A，B 作为兜底/离线备份**。

---

## 4. 方案 A：Hook WCDB 拿主库（推荐）

### 4.1 Hook 点

```java
// 微信实际调用的是 7 参重载：
com.tencent.wcdb.database.SQLiteDatabase
    .openDatabase(String path,
                  byte[] password,
                  SQLiteCipherSpec cipher,
                  SQLiteDatabase$CursorFactory factory,
                  int openFlags,
                  DatabaseErrorHandler errorHandler,
                  int connectionFlags)
```

> 说明：WCDB 的 `openDatabase` 有多个重载，最终都汇聚到这个 7 参版本；6 参版本只是把 `connectionFlags` 传 0。Hook 7 参版本即可覆盖微信主库打开。

### 4.2 可直接落地的模块代码

```java
package com.yourname.wxcontact;

import android.database.Cursor;
import android.text.TextUtils;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class WxContactHook implements IXposedHookLoadPackage {

    private static final String WX_PKG  = "com.tencent.mm";
    private static final String MAIN_DB = "EnMicroMsg.db";

    /** 微信主库对象（com.tencent.wcdb.database.SQLiteDatabase） */
    public static volatile Object sMainDb;
    public static volatile byte[] sDbPassword;
    public static volatile String sDbPath;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) throws Throwable {
        if (!WX_PKG.equals(lp.packageName)) return;
        hookWcdbOpen(lp.classLoader);
    }

    private void hookWcdbOpen(ClassLoader cl) {
        Class<?> cipherSpec, cursorFactory, errorHandler;
        try {
            cipherSpec    = XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteCipherSpec", cl);
            cursorFactory = XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase$CursorFactory", cl);
            errorHandler  = XposedHelpers.findClass("com.tencent.wcdb.DatabaseErrorHandler", cl);
        } catch (Throwable t) {
            XposedBridge.log("[WxContact] WCDB not found: " + t);
            return;
        }

        XposedHelpers.findAndHookMethod(
                "com.tencent.wcdb.database.SQLiteDatabase", cl,
                "openDatabase",
                String.class, byte[].class, cipherSpec, cursorFactory,
                int.class, errorHandler, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String path = (String) param.args[0];
                        if (path == null || !path.endsWith(MAIN_DB)) return;
                        sMainDb     = param.getResult();
                        sDbPassword = (byte[]) param.args[1];
                        sDbPath     = path;
                        XposedBridge.log("[WxContact] main db captured: " + path
                                + " pwd=" + (sDbPassword == null ? "<null>" : new String(sDbPassword)));
                    }
                });
    }

    /* ---------------- 通用 rawQuery ---------------- */

    public static Cursor rawQuery(String sql) {
        Object db = sMainDb;
        if (db == null) return null;
        try {
            Method m = db.getClass().getMethod("rawQuery", String.class, Object[].class);
            return (Cursor) m.invoke(db, sql, new Object[0]);
        } catch (Throwable t) {
            XposedBridge.log("[WxContact] rawQuery err: " + t);
            return null;
        }
    }

    /* ---------------- 1. 联系人列表 ---------------- */

    public static List<Contact> loadContacts() {
        List<Contact> out = new ArrayList<>();
        Cursor c = rawQuery(
            "SELECT username, alias, conRemark, nickname, pyInitial, quanPin, showHead, type, "
          + "verifyFlag, deleteFlag, encryptUsername, chatroomFlag "
          + "FROM rcontact "
          + "WHERE (type & 1 != 0) AND type & 32 = 0 AND type & 8 = 0 "
          + "  AND verifyFlag & 8 = 0 AND deleteFlag = 0 "
          + "ORDER BY showHead ASC, "
          + "  CASE WHEN length(conRemarkPYFull) > 0 THEN upper(conRemarkPYFull) "
          + "       ELSE upper(quanPin) END ASC, "
          + "  CASE WHEN length(conRemark) > 0 THEN upper(conRemark) ELSE upper(quanPin) END ASC, "
          + "  upper(quanPin) ASC, upper(nickname) ASC, upper(username) ASC");
        if (c == null) return out;
        while (c.moveToNext()) {
            Contact ct = new Contact();
            ct.username  = c.getString(0);
            ct.alias     = c.getString(1);
            ct.remark    = c.getString(2);
            ct.nickname  = c.getString(3);
            ct.pyInitial = c.getString(4);
            ct.quanPin   = c.getString(5);
            ct.showHead  = c.getInt(6);
            ct.type      = c.getInt(7);
            out.add(ct);
        }
        c.close();
        return out;
    }

    /* ---------------- 2. 群聊列表 ---------------- */

    public static List<Group> loadGroups() {
        List<Group> out = new ArrayList<>();
        Cursor c = rawQuery(
            "SELECT chatroomname, displayname, memberlist, memberCount, roomowner, roomflag, modifytime "
          + "FROM chatroom "
          + "WHERE chatroomname LIKE '%@chatroom' "
          + "   OR chatroomname LIKE '%@im.chatroom' "
          + "   OR chatroomname LIKE '%@chatroom_exclusive' "
          + "ORDER BY modifytime DESC");
        if (c == null) return out;
        while (c.moveToNext()) {
            Group g = new Group();
            g.chatroomname = c.getString(0);
            g.displayname  = c.getString(1);
            g.memberlist   = c.getString(2);
            g.memberCount  = c.getInt(3);
            g.roomOwner    = c.getString(4);
            out.add(g);
        }
        c.close();
        return out;
    }

    /* ---------------- 3. 群成员 wxid 列表 ---------------- */

    public static List<String> groupMembers(String chatroomname) {
        List<String> out = new ArrayList<>();
        Cursor c = rawQuery("SELECT memberlist FROM chatroom WHERE chatroomname='" + safe(chatroomname) + "'");
        if (c != null) {
            if (c.moveToFirst()) {
                String ml = c.getString(0);
                if (!TextUtils.isEmpty(ml)) {
                    for (String s : ml.split(";")) {
                        if (!TextUtils.isEmpty(s)) out.add(s.trim());
                    }
                }
            }
            c.close();
        }
        return out;
    }

    /* ---------------- 4. 群成员资料 ---------------- */

    public static List<Contact> groupMemberContacts(String chatroomname) {
        List<String> members = groupMembers(chatroomname);
        if (members.isEmpty()) return new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) in.append(',');
            in.append('\'').append(safe(members.get(i))).append('\'');
        }
        List<Contact> out = new ArrayList<>();
        Cursor c = rawQuery(
            "SELECT username, alias, conRemark, nickname, pyInitial, quanPin, type "
          + "FROM rcontact WHERE username IN (" + in + ") "
          + "ORDER BY showHead ASC, "
          + "  CASE WHEN length(conRemarkPYFull) > 0 THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC");
        if (c != null) {
            while (c.moveToNext()) {
                Contact ct = new Contact();
                ct.username  = c.getString(0);
                ct.alias     = c.getString(1);
                ct.remark    = c.getString(2);
                ct.nickname  = c.getString(3);
                ct.pyInitial = c.getString(4);
                ct.quanPin   = c.getString(5);
                ct.type      = c.getInt(6);
                out.add(ct);
            }
            c.close();
        }
        return out;
    }

    /* ---------------- 5. 会话列表 ---------------- */

    public static List<Conversation> loadConversations() {
        List<Conversation> out = new ArrayList<>();
        Cursor c = rawQuery(
            "SELECT username, content, msgType, unReadCount, conversationTime, flag, digest, digestUser "
          + "FROM rconversation ORDER BY flag DESC, conversationTime DESC");
        if (c == null) return out;
        while (c.moveToNext()) {
            Conversation v = new Conversation();
            v.username        = c.getString(0);
            v.content         = c.getString(1);
            v.msgType         = c.getInt(2);
            v.unReadCount     = c.getInt(3);
            v.conversationTime= c.getLong(4);
            v.flag            = c.getInt(5);
            v.digest          = c.getString(6);
            v.digestUser      = c.getString(7);
            out.add(v);
        }
        c.close();
        return out;
    }

    /* ---------------- 6. 自己的 wxid ---------------- */

    public static String selfWxid() {
        try {
            android.content.Context ctx = wechatContext();
            if (ctx == null) return null;
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("notify_key_pref_no_account", 4);
            String v = sp.getString("login_weixin_username", "");
            return TextUtils.isEmpty(v) ? null : v;
        } catch (Throwable t) { return null; }
    }

    public static android.content.Context wechatContext() {
        // Xposed 注入在微信进程内，直接拿微信的 Application
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            return (android.content.Context) XposedHelpers.getObjectField(at, "mInitialApplication");
        } catch (Throwable t) { return null; }
    }

    /* ---------------- 7. 头像路径 ---------------- */

    public static String avatarPath(String wxid, boolean hd) {
        try {
            if (sDbPath == null) return null;
            String md5 = md5(wxid);
            String dir = sDbPath.substring(0, sDbPath.length() - MAIN_DB.length());
            return dir + "avatar/" + md5.substring(0, 2) + "/" + md5.substring(2, 4)
                    + "/user_" + (hd ? "hd_" : "") + md5 + ".png";
        } catch (Throwable t) { return null; }
    }

    public static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            int v = b & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static String safe(String s) { return s == null ? "" : s.replace("'", "''"); }

    /* ---------------- 数据模型 ---------------- */

    public static class Contact {
        public String username, alias, remark, nickname, pyInitial, quanPin;
        public int showHead, type;
        public boolean isGroup() { return username != null && username.endsWith("@chatroom"); }
        public String displayName() {
            if (!TextUtils.isEmpty(remark))   return remark;
            if (!TextUtils.isEmpty(nickname)) return nickname;
            return username;
        }
    }

    public static class Group {
        public String chatroomname, displayname, memberlist, roomOwner;
        public int memberCount;
        public String[] members() {
            return TextUtils.isEmpty(memberlist) ? new String[0] : memberlist.split(";");
        }
    }

    public static class Conversation {
        public String username, content, digest, digestUser;
        public int msgType, unReadCount, flag;
        public long conversationTime;
    }
}
```

### 4.3 群成员资料查询的标准写法

先取 `memberlist` 拆成 wxid 列表，再拼 `IN (...)` 查 `rcontact`：

```java
List<String> members = groupMembers(chatroomname);
StringBuilder in = new StringBuilder();
for (int i = 0; i < members.size(); i++) {
    if (i > 0) in.append(',');
    in.append('\'').append(safe(members.get(i))).append('\'');
}
Cursor c = rawQuery(
    "SELECT username, alias, conRemark, nickname, pyInitial, quanPin, type "
  + "FROM rcontact WHERE username IN (" + in + ") "
  + "ORDER BY showHead ASC, "
  + "  CASE WHEN length(conRemarkPYFull) > 0 THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC");
```

> 这正是微信自己的做法（见 `b41.lb.a()`：`... and (username in (select chatroomname from chatroom where (memberlist like '%wxid%' ...)))`），只是我们用 `IN` 更直观。

---

## 5. 方案 B：离线/外部进程直读（兜底）

适用于：模块跑在别的进程、或者要在微信未启动时读库。

### 5.1 口令计算

```java
public static String wxDbPassword(String imei, long uin) throws Exception {
    MessageDigest md = MessageDigest.getInstance("MD5");
    byte[] d = md.digest((imei + uin).getBytes());
    StringBuilder sb = new StringBuilder();
    for (byte b : d) { int v = b & 0xFF; if (v < 16) sb.append('0'); sb.append(Integer.toHexString(v)); }
    return sb.substring(0, 7);
}
```

- `imei` 候选顺序：`files/KeyInfo.bin`（RC4，key `_wEcHAT_`，逐行）→ `w0.g(true)` → `w0.g(false)` → `"1234567890ABCDEF"`
- `uin`：SharedPreferences 里拿不到，通常要从微信运行时取（hook `com.tencent.mm.network.AccInfo#getUin()` 或 `com.tencent.mm.modelbase.*#getUin()`），或穷举目录名反推。

### 5.2 自持 WCDB 开库

```gradle
implementation 'com.tencent.wcdb:wcdb-android:1.1.0'   // 版本需与微信内置一致或兼容
```

```java
com.tencent.wcdb.database.SQLiteCipherSpec spec =
        new com.tencent.wcdb.database.SQLiteCipherSpec()
                .setPageSize(1024)
                .setSQLCipherVersion(1);   // 其余默认：kdf 64000 / hmac SHA1

com.tencent.wcdb.database.SQLiteDatabase db =
        com.tencent.wcdb.database.SQLiteDatabase.openDatabase(
                dbPath,
                password.getBytes(),
                spec,
                null,
                com.tencent.wcdb.database.SQLiteDatabase.OPEN_FLAG_READONLY,  // 只读！
                null,
                0);

Cursor c = db.rawQuery("select username, nickname from rcontact where deleteFlag=0", new Object[0]);
```

### 5.3 必须注意的坑

1. **WAL**：微信运行时以 WAL 模式打开，`EnMicroMsg.db-wal` 可能很大。**只读打开时 WCDB 会自动应用 WAL**；若 `-wal` 无法写入（外部进程、权限不足），需要先把 `-wal`/`-shm` 一起拷贝到可写目录再开。
2. **只读标志**：`OPEN_FLAG_READONLY` 必须加，否则可能触发 checkpoint 改写用户数据库。
3. **native 库**：WCDB 需要 `libwcdb.so`。若你的模块 APK 不带，可复用微信进程内的 WCDB 类（方案 A）。
4. **页大小 1024** 与默认 4096 不同，**必须显式 `setPageSize(1024)`**，否则报 `file is not a database`。

---

## 6. 联系人选择器落地建议

### 6.1 数据分层

```
联系人选择器
├── Tab1 最近会话   → rconversation (order by flag desc, conversationTime desc)
├── Tab2 联系人     → rcontact  (type&1!=0 and type&32=0 and type&8=0 and deleteFlag=0)
├── Tab3 群聊       → chatroom  (chatroomname like '%@chatroom')
│     └── 群成员    → chatroom.memberlist split(";") → rcontact IN (...)
└── 搜索            → rcontact where nickname/conRemark/quanPin/pyInitial like ?
```

### 6.2 显示名优先级（与微信一致）

```java
// 微信口径（b41.z1.d / t73.n.d 等价）
1. conRemark   （备注）
2. nickname    （昵称）
3. username    （wxid）
// 群聊中再叠加 chatroom.displayname 中的群昵称
```

### 6.3 头像

```
{账号目录}/avatar/{md5(wxid)[0:2]}/{md5(wxid)[2:4]}/user_{hd_?}{md5(wxid)}.png
```
- 群头像同样用 `chatroomname` 计算
- 文件不存在时说明本地没有缓存，可 Hook `com.tencent.mm.modelavatar.y`（AvatarStorage）走微信自己的下载/解码逻辑

### 6.4 拼音索引

直接用 `pyInitial` / `quanPin` / `conRemarkPYFull` 列，无需自己转拼音（`conRemark` 为空时用 nickname 的拼音列）。

### 6.5 实时刷新

监听微信事件总线（同进程可直接 hook 发布点）：

| 事件类 | 触发时机 |
|---|---|
| `com.tencent.mm.autogen.events.ModNewContactEvent` | 联系人新增/修改 |
| `com.tencent.mm.autogen.events.GetNewContactEvent` | 拉取新联系人 |
| `com.tencent.mm.autogen.events.ChatroomMemberDataUpdatedEvent` | 群成员数据更新 |
| `com.tencent.mm.autogen.events.BizDeleteContactEvent` | 联系人删除 |

```java
XposedHelpers.findAndHookConstructor(
    "com.tencent.mm.autogen.events.ModNewContactEvent", cl, new XC_MethodHook() {
        protected void afterHookedMethod(MethodHookParam p) { reloadContacts(); }
    });
```

---

## 7. 版本适配 / 防翻车清单

| 风险 | 说明 | 对策 |
|---|---|---|
| 混淆类名 | `com.tencent.mm.storage.j4/a3/l4/f9` 等**每版都变** | 只用 `com.tencent.wcdb.*` 这种公共 API；不要硬编码微信内部类名 |
| WCDB 版本 | 不同微信版本内置 WCDB 版本可能不同 | Hook 而非自带 wcdb；自带时 `setPageSize(1024)` 必须显式 |
| 多账号 | `MicroMsg/` 下可能有多个 md5 目录 | 以实际 `openDatabase` 捕获到的 path 为准 |
| 其他库 | `SnsMicroMsg.db`、`FTS5IndexMicroMsg_encrypt.db`、`WxFileIndex.db`、`IndexMicroMsg.db` | Hook 时按 `endsWith("EnMicroMsg.db")` 过滤 |
| 时序 | 未登录时主库未打开 | 等 `handleLoadPackage` 后延迟轮询 `sMainDb != null`，或用 `SQLiteDatabase.getActiveDatabases()` 兜底 |
| 线程 | WCDB 连接池非线程安全的部分操作 | 所有 rawQuery 放到单一 HandlerThread，或每次新开 cursor 后立即 close |
| SQL 注入 | chatroomname 来自外部 | 单引号转义 `'` → `''` |

**兜底获取主库对象（不依赖 Hook 时机）：**

```java
// com.tencent.wcdb.database.SQLiteDatabase.getActiveDatabases() 是公开静态方法
ArrayList<?> dbs = (ArrayList<?>) XposedHelpers.callStaticMethod(
        XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", cl),
        "getActiveDatabases");
for (Object db : dbs) {
    String p = (String) XposedHelpers.callMethod(db, "getPath");
    if (p != null && p.endsWith("EnMicroMsg.db")) { sMainDb = db; break; }
}
```

---

## 8. 混淆类名 → 真实身份对照表（本次逆向成果）

| 混淆类名 | 真实身份 | 判定依据 |
|---|---|---|
| `com.tencent.mm.storage.j4` | **ContactStorage** | LOG `MicroMsg.ContactStorage`，操作 `rcontact` / `bottlecontact` / `contact_ext` |
| `com.tencent.mm.storage.l4` | **ConversationStorage** | LOG `MicroMsg.ConversationStorage`，操作 `rconversation` |
| `com.tencent.mm.storage.a3` | **ChatroomStorage** | LOG `MicroMsg.ChatroomStorage`，操作 `chatroom`，`x1()`=memberlist、`u1()`=displayname |
| `com.tencent.mm.storage.f9` | **MsgInfoStorage** | LOG `MicroMsg.MsgInfoStorage`，`message` 表建表语句 |
| `com.tencent.mm.storage.y9` | NotifyMessageRecordStorage | LOG `MicroMsg.NotifyMessageRecordStorage` |
| `com.tencent.mm.storage.q3` | ConfigStorage（userinfo 表） | `CREATE TABLE IF NOT EXISTS userinfo` |
| `com.tencent.mm.storage.z2` / `im.y1` | ChatRoomMember 模型 | `chatroom` 表 AutoDB，`initAutoDBInfo()` |
| `com.tencent.mm.storage.y3` / `com.tencent.mm.contact.s` / `im.f2` | Contact 模型 | `rcontact` 行对象 |
| `com.tencent.mm.storage.i3` | systemInfo.cfg 管理 | `systemInfo.cfg` |
| `com.tencent.mm.storagebase.IMEISave` | IMEI 候选集 | RC4 `_wEcHAT_` + `KeyInfo.bin` |
| `pk.k` | MD5 工具类 | `g(byte[])` = MD5 hex |
| `qs0.b` | 路径常量集合 | `MicroMsg/`、`/image/` 等 |
| `gp0.b0` | **CoreStorage** | LOG `MMKernel.CoreStorage`，`e(int)`=`md5("mm"+uin)` |
| `gp0.m` | CoreAccount | LOG `MMKernel.CoreAccount`，`account.bin` / `account.mapping` |
| `kh5.b0` | **SqliteDB** | LOG `MicroMsg.SqliteDB`，包 `SQLiteDatabase` |
| `kh5.f` | **MMDataBase** | LOG `MicroMsg.MMDataBase`，`w()` 开库 + CipherSpec |
| `com.tencent.mm.modelavatar.y` | **AvatarStorage** | LOG `MicroMsg.AvatarStorage`，`avatar/` + `user_[hd_]md5.png` |
| `com.tencent.mm.plugin.messenger.foundation.h2` | StorageFactory | `cj()`→ContactStorage，`ej()`→ConversationStorage |
| `com.tencent.mm.network.a3` | MMPushCore | `notify_key_pref_no_account` / `login_weixin_username` |
| `com.tencent.mm.network.AccInfo` | 账号信息 | `getUin()` / `getUsername()` |

---

## 9. 二次核查记录

对全部关键结论做了交叉验证：

| # | 结论 | 证据 1 | 证据 2 | 状态 |
|---|---|---|---|---|
| 1 | 账号目录 = `MicroMsg/` + `md5("mm"+uin)` | `gp0.b0.e(int)` | `gp0.b0.i(int)` + `gp0.m.r()` 中 `b0.e(i6)` | ✅ |
| 2 | 基础目录 = `getFilesDir().getParentFile()` | `qs0.b.Y()` | `qs0.b.e()` = `Y()+"MicroMsg/"` | ✅ |
| 3 | 主库文件名 `EnMicroMsg.db` | `gp0.b0.g()` = `h()+"EnMicroMsg.db"` | `kh5.f.w()` 中 `str3.endsWith("EnMicroMsg.db")` | ✅ |
| 4 | 口令 = `md5(imei+uin)[0:7]` | `kh5.b0.R()` smali `Lpk/k;->g([B)` + `substring(0,7)` | `pk.k.g()` = MD5 hex | ✅ |
| 5 | CipherSpec pageSize=1024 / SQLCipher v1 | `kh5.f.<clinit>` smali | `SQLiteCipherSpec` 默认值（kdf 64000 / SHA1） | ✅ |
| 6 | 开库调用 7 参重载 | `kh5.f.w()` Java | `SQLiteDatabase` smali 方法声明 | ✅ |
| 7 | `rcontact` 字段清单 | `com.tencent.mm.storage.j4.D()` | `b41.lb.a()` 同样的 SELECT | ✅ |
| 8 | 联系人过滤条件 | `com.tencent.mm.storage.j4.t()` | `com.tencent.mm.storage.j4.I("@all.contact.android")` | ✅ |
| 9 | `chatroom` 表 33 字段 | `im.y1.initAutoDBInfo()` | `im.y1.createMyTable()` / `convertTo()` | ✅ |
| 10 | `memberlist` 用 `;` 分隔 | `com.tencent.mm.storage.a3.K1()` `split(";")` | `com.tencent.mm.ui.contact.item.h.<clinit>` = `";"` | ✅ |
| 11 | `rconversation` 字段清单 | `com.tencent.mm.storage.l4.A()` | `l4.B()` 同样 SELECT | ✅ |
| 12 | `message` 表结构 | `com.tencent.mm.storage.f9.<clinit>` | 同 | ✅ |
| 13 | 头像路径规则 | `com.tencent.mm.modelavatar.y.f()` + `d()` | 同（`md5(username)` 两段分目录） | ✅ |
| 14 | 自身 wxid 存储位置 | `com.tencent.mm.network.a3.j()` | `com.tencent.mm.app.q3.i()` 同 key | ✅ |
| 15 | 运行时可直接 `rawQuery` | `com.tencent.mm.ui.contact.item.h.a()` 中 `j1.x().f.f("SELECT memberlist FROM chatroom WHERE chatroomname=?;", ...)` | `SQLiteDatabase.rawQuery(String, Object[])` 返回 `android.database.Cursor` | ✅ |

**未 100% 确认、需真机实测的项：**
1. `chatroom.displayname` 的原始串精确格式（`username,昵称` 对的分隔符组合）——建议 dump 一次 `select displayname from chatroom limit 1` 确认。
2. `type` 位掩码中 bit5(32)、bit4(16) 的语义命名——已按微信 SQL 行为反推，未找到常量定义类。
3. `w0.g(boolean)` 生成的设备标识算法（该类位于未被索引的 dex，jadx/DexKit 均无法解析）。
4. WCDB `openDatabase` 第 7 个 int 参数的确切名称（smali 显示它最终传入 `SQLiteConnectionPool.open`，微信固定传 `32`；Hook 时无需关心）。

---

## 10. 附：最小可用代码骨架

核心三行：

```java
// 1) Hook 拿库
XposedHelpers.findAndHookMethod("com.tencent.wcdb.database.SQLiteDatabase", cl,
    "openDatabase", String.class, byte[].class, CipherSpecClass, CursorFactoryClass,
    int.class, ErrorHandlerClass, int.class, param -> {
        if (((String) param.args[0]).endsWith("EnMicroMsg.db")) sMainDb = param.getResult();
    });

// 2) 查询
Method m = sMainDb.getClass().getMethod("rawQuery", String.class, Object[].class);
Cursor c = (Cursor) m.invoke(sMainDb, "select username,nickname,conRemark from rcontact where deleteFlag=0", new Object[0]);

// 3) 群成员
String ml = ...; // select memberlist from chatroom where chatroomname=?
String[] members = ml.split(";");
```

---

*报告由 LSPilot AI 分析助手生成，全部结论基于对 `com.tencent.mm` base.apk 的静态逆向，建议在目标微信版本上真机复核后投入使用。*
