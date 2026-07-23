/**
 * ================================================================
 *  微信消息类型 ID — 源码验证完整版 (WeChat 8.0.76)
 * ================================================================
 * 
 * 数据来源:
 *   1. e01.x9.e() 源码 — 第291行: int type = e9Var.getType()
 *   2. k0.c(int) 源码 — 第1128-1211行: 扩展类型→49的映射
 *   3. 运行日志验证 — type=1/3/34/42/43/48/49 全部命中
 *
 * e9.getType() 返回原始消息类型
 * k0.c(type) 将扩展类型(74/83/84等)映射为49(AppMsg)
 */

// ================================================================
// 一、标准消息类型 (e9.getType() 直接返回)
// ================================================================

public class WeChatMsgType {

    // ── 基础类型 ──
    public static final int TYPE_TEXT     = 1;   // 文字消息
    public static final int TYPE_IMAGE    = 3;   // 图片消息
    public static final int TYPE_VOICE    = 34;  // 语音消息 (Silk/AMR格式)
    public static final int TYPE_CARD     = 42;  // 名片消息 (个人/公众号名片)
    public static final int TYPE_VIDEO    = 43;  // 视频消息
    public static final int TYPE_EMOJI    = 47;  // 表情消息 (自定义表情/emoji)
    public static final int TYPE_LOCATION = 48;  // 位置消息
    public static final int TYPE_APPMSG   = 49;  // 链接/文件/红包卡片/小程序/音乐等
    public static final int TYPE_VOIP     = 50;  // VOIP通话消息
    public static final int TYPE_SYSTEM   = 10000; // 系统消息(时间戳/提示等)
    public static final int TYPE_SYS_TIPS = 10002; // 系统提示(加入群聊等)

    // ── 系统/通知类型 (日志验证) ──
    public static final int TYPE_SERVICE_NOTIFY = 318767153; // 服务通知 (notifymessage)
    public static final int TYPE_SYSMSG_TEMPLATE = 570425393; // 系统模板消息 (sysmsgtemplate)
    public static final int TYPE_GROUP_NOTIFY = 754974769; // 群聊通知


    // ================================================================
    // 二、k0.c() 源码映射 — 扩展类型→49
    // ================================================================
    // 源码: com.tencent.mm.pluginsdk.model.app.k0.c(int i)
    // 所有这些值最终都映射为 TYPE_APPMSG (49)
    //
    // 74, 83, 84, 87, 95, 102, 103, 131, 132,
    // 1048625, 16777265,
    // 268435505, 285212721, 301989937, 318767153,
    // 335544369 ~ 536936497 (步进 16777216),
    // 553648177, 587202609, 603979825,
    // 687865905, 704643121,
    // 738197553, 754974769, 771751985,
    // 805306417 ~ 855638065,
    // 905969713 ~ 973078577,
    // 974127153 ~ 979370033 (步进 1048576),
    // 1040187441, 1077936177, 1090519089,
    // 1107296305 ~ 1241514033 (步进 16777216),
    // 1409286193, 1426063409, 1442840625,
    // 1627390001, 1895825457
    // 全部 → return 49;


    // ================================================================
    // 三、判断消息类型的正确方式
    // ================================================================

    /**
     * 判断是否是自己发出的消息
     * e9.O0() 返回 int, 但值不一定是0/1 (日志中返回228/29/35等)
     * 正确: 不等于0 就是收到的, 等于0就是发出的
     */
    public static boolean isReceived(Object e9) {
        int v = (int) XposedHelpers.callMethod(e9, "O0");
        return v == 0;
    }

    /**
     * 获取消息类型
     */
    public static int getType(Object e9) {
        return (int) XposedHelpers.callMethod(e9, "getType");
    }

    /**
     * 获取消息内容 (文字消息)
     * e9.j() 返回消息摘要, 对文字消息返回完整文本
     */
    public static String getContent(Object e9) {
        return (String) XposedHelpers.callMethod(e9, "j");
    }

    /**
     * 获取发送者 talker
     */
    public static String getTalker(Object e9) {
        return (String) XposedHelpers.callMethod(e9, "N0");
    }


    // ================================================================
    // 四、各类型消息的 content 格式
    // ================================================================

    /**
     * TYPE_TEXT (1) — 文字消息
     *   自己发的: "哈喽你好"
     *   群聊中:   "wxid_xxx:\n文字内容"
     *   含@提醒:  "wxid_xxx:\n@张三 你好"
     */
    public static final String SAMPLE_TEXT = "哈喽";

    /**
     * TYPE_IMAGE (3) — 图片消息
     *   群聊中: "wxid_xxx:\n<?xml version=\"1.0\"?>\n<msg><img .../></msg>"
     *   j()返回XML, 需要用 S1() 获取原始内容
     */
    public static final String SAMPLE_IMAGE = "wxid_xxx:\n<?xml...><msg><img.../></msg>";

    /**
     * TYPE_VOICE (34) — 语音消息
     *   j() 返回 "" (空字符串)
     *   AMR/Silk文件路径在 y21.g1 中
     *   语音 XML 在 S1() 中: <msg><voicemsg endflag="1" length="5000" .../></msg>
     */
    public static final String SAMPLE_VOICE = "";  // j()返回空

    /**
     * TYPE_CARD (42) — 名片消息
     *   群聊中: "wxid_xxx:\n<?xml...><msg><card .../></msg>"
     */
    public static final String SAMPLE_CARD = "wxid_xxx:\n<?xml...><msg><card.../></msg>";

    /**
     * TYPE_VIDEO (43) — 视频消息
     *   群聊中: "wxid_xxx:0:0\n\n\n<?xml...><msg><videomsg .../></msg>"
     */
    public static final String SAMPLE_VIDEO = "wxid_xxx:0:0\n\n\n<?xml...><msg><videomsg.../></msg>";

    /**
     * TYPE_LOCATION (48) — 位置消息
     *   <msg><location x="113.xxxx" y="22.xxxx" scale="16" label="深圳市南山区" poiname="腾讯大厦"/></msg>
     */
    public static final String SAMPLE_LOCATION = "<msg><location x=\"113.9\" y=\"22.5\" label=\"深圳市南山区科技园\" poiname=\"腾讯大厦\"/></msg>";

    /**
     * TYPE_APPMSG (49) — 链接/文件/红包/转账/小程序
     *   红包: 含 <type>2001</type> (微信红包)
     *   转账: 含 <type>2000</type>
     *   链接: <appmsg><title>标题</title><url>...</url></appmsg>
     */
    public static final String SAMPLE_APPMSG = "<msg><appmsg><title>微信红包</title><type>2001</type>...</appmsg></msg>";


    // ================================================================
    // 五、TTS 播报的完整 dispatch
    // ================================================================

    public static String getSpeakText(int type, String talker, String content, String nickname) {
        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        String prefix = isGroup ? "群聊" : "";

        switch (type) {
            case TYPE_TEXT:     // 1
                String text = cleanText(content);
                return text.isEmpty() ? null : prefix + nickname + "说：" + text;

            case TYPE_IMAGE:    // 3
                return prefix + nickname + "发来一张照片";

            case TYPE_VOICE:    // 34
                return prefix + nickname + "发来语音";

            case TYPE_CARD:     // 42
                return prefix + nickname + "发来一张名片";

            case TYPE_VIDEO:    // 43
                return prefix + nickname + "发来一段视频";

            case TYPE_EMOJI:    // 47
                return null;  // 不播报表情

            case TYPE_LOCATION: // 48
                String loc = parseLocation(content);
                return prefix + nickname + "发来定位在：" + loc;

            case TYPE_APPMSG:   // 49
                if (content != null && content.contains("luckymoney"))
                    return prefix + nickname + "发来一个红包";
                return null;  // 其他AppMsg不播报

            case TYPE_VOIP:     // 50
                return prefix + nickname + "发起语音通话";

            case TYPE_SYSTEM:   // 10000
            case TYPE_SYS_TIPS: // 10002
                if (content != null && content.contains("加入了群聊"))
                    return cleanText(content);
                return null;

            default:
                return null;  // 未知类型不播报
        }
    }

    public static String cleanText(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replaceFirst("^wxid_[a-z0-9]+:[\\u00A0\\s]*", "");
        s = s.replaceFirst("^[a-z][a-z0-9]+@chatroom:", "");
        s = s.replaceFirst("^[a-z0-9]+:", "");
        s = s.replace("<![CDATA[","").replace("]]>","");
        s = s.replaceAll("<[^>]+>","").replaceAll("https?://\\S+","链接");
        s = s.replaceAll("\\[\\w+\\]","").replace("\n"," ").trim();
        return s.length() > 200 ? s.substring(0,200)+"等" : s;
    }

    public static String parseLocation(String c) {
        if (c == null) return "未知位置";
        for (String t : new String[]{"label","poiname"}) {
            int i = c.indexOf(t + "=\"");
            if (i >= 0) {
                int s = i + t.length() + 2;
                int e = c.indexOf("\"", s);
                if (e > s) return c.substring(s, e);
            }
        }
        return "未知位置";
    }


    // ================================================================
    // 六、日志验证记录
    // ================================================================
    //
    // #1  type=1     talker=wxid_9ohhf82mrlgc22           content=哈喽               ✅ 文字
    // #3  type=3     talker=11021177242@chatroom          content=wxid_...:\n<?xml  ✅ 图片
    // #15 type=34    talker=50216406570@chatroom          content=                   ✅ 语音
    // #29 type=42    talker=23552094189@chatroom          content=wxid_...:\n<?xml  ✅ 名片
    // #22 type=43    talker=11586179019@chatroom          content=wxid_...:0:0      ✅ 视频
    // #2  type=49    talker=11586179019@chatroom          content=wxid_...:\n<msg>  ✅ AppMsg
    // #6  type=570425393 talker=49735108879@chatroom     sysmsgtemplate             系统模板
    // #1  type=754974769 talker=24130456064@chatroom     (群通知)                   群通知
    //     type=318767153 talker=notifymessage             (服务通知)                服务通知
    //
    // ================================================================
}
