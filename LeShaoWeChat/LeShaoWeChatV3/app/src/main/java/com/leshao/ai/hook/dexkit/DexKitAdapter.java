package com.leshao.ai.hook.dexkit;

import android.util.Log;

import com.leshao.ai.hook.HookEntry;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 微信（com.tencent.mm）跨版本运行时适配核心。
 * <p>
 * 依据《微信AI助手-完整逆向分析与实现交付文档》§15.4 / §20 的字符串锚点全集，
 * 用 DexKit 在微信进程内动态定位全部关键类。所有锚点均为「日志 TAG + 特征串」
 * 双层结构，混淆类名每版变化均不受影响。
 * <p>
 * 定位结果缓存；失败返回 null 并记录日志，由上层安全降级。
 */
public final class DexKitAdapter {

    private static final String TAG = "LeshaoAI.Adapter";

    // ---------- 文档 §20 字符串锚点 ----------
    /** f9 MsgInfoStorage 日志 TAG + insert 格式串。 */
    private static final String TAG_MSG_INFO_STORAGE = "MicroMsg.MsgInfoStorage";
    private static final String FMT_INSERT =
            "insert:%d talker:%s id:%d type:%d status:%d svrid:%d msgseq:%d flag:%d create:%d issend:%d lockforsync[%s,%d]";
    /** e9 MsgInfo 日志 TAG + convertFrom 字段日志。 */
    private static final String TAG_MSG_INFO = "MicroMsg.MsgInfo";
    private static final String FIELD_LOG_TALKER = "\ntalker = ";
    private static final String FIELD_LOG_SVRID = "\nmsgSvrId = ";
    private static final String FIELD_LOG_CONTENT = "\ncontent = ";
    /** r1 SendMsgCgiFactory 日志 TAG + executeByPPC 特征串。 */
    private static final String TAG_SEND_FACTORY = "MicroMsg.SendMsgCgiFactory";
    private static final String STR_EXECUTE_BY_PPC = "executeByPPC() called with: content size = ";
    /** p1 发送类型枚举成员名（values() + 枚举常量串）。 */
    private static final String[] SEND_TYPE_ENUM_NAMES = {
            "TEXT", "IMAGE", "VIDEO", "CDN_IMAGE", "EMOJI", "APPMSG", "LOCATION", "SHARECARD"
    };
    /** b41.aa MsgInfoStorageLogic 特征串。 */
    private static final String STR_TALKER_POS_NULL = "dz[getGroupChatMsgTalkerPos text is null]";
    /** b41.g2 ConversationLogic。 */
    private static final String TAG_CONVERSATION_LOGIC = "MicroMsg.ConversationLogic";
    private static final String STR_CONV_SQL = "rconversation WHERE ";
    /** l4 ConversationStorage。 */
    private static final String TAG_CONVERSATION_STORAGE = "MicroMsg.ConversationStorage";
    private static final String STR_CONV_INSERT_FAIL = "insert conversation failed, username empty";
    /** b41.e AccountStorage。 */
    private static final String TAG_ACCOUNT_STORAGE = "MicroMsg.AccountStorage";
    private static final String STR_ATTACHMENT = "attachment/";
    private static final String STR_RECORD = "record/";
    private static final String STR_IMG_SHAKE = "image/shakeTranImg/";
    /** j4 RContactStorage 接口（未混淆）。 */
    private static final String IFACE_RCONTACT_STORAGE = "com.tencent.mm.storage.d8";
    /** g RContact 实体字段名（未混淆，最稳）。 */
    private static final String FIELD_USERNAME = "field_username";
    private static final String FIELD_NICKNAME = "field_nickname";
    private static final String FIELD_CON_REMARK = "field_conRemark";
    /** ChattingUIFragment 菜单方法签名。 */
    private static final String METHOD_ON_CREATE_OPTIONS_MENU = "onCreateOptionsMenu";
    private static final String PARAM_MENU = "Landroid/view/Menu;";
    private static final String PARAM_MENU_INFLATER = "Landroid/view/MenuInflater;";

    // ---------- 类名缓存 ----------
    private static volatile Class<?> msgInfoStorageClass;   // f9
    private static volatile Class<?> msgInfoClass;          // e9
    private static volatile Class<?> sendFactoryClass;      // v51.r1
    private static volatile Class<?> sendTypeEnumClass;     // v51.p1
    private static volatile Class<?> storageLogicClass;     // b41.aa
    private static volatile Class<?> conversationLogicClass;// b41.g2
    private static volatile Class<?> conversationStorageClass; // l4
    private static volatile Class<?> rcontactStorageClass;  // j4
    private static volatile Class<?> rcontactClass;         // g
    private static volatile Class<?> accountStorageClass;   // b41.e
    private static volatile Class<?> coreHubClass;          // b41.h9
    private static volatile Class<?> chattingFragmentClass; // ChattingUIFragment

    private DexKitAdapter() {
    }

    // ---------- 定位器 ----------

    /** f9 MsgInfoStorage（接收入口 / 历史消息）。 */
    public static Class<?> findMsgInfoStorageClass() {
        if (msgInfoStorageClass == null) {
            msgInfoStorageClass = classByMethodStrings(
                    Arrays.asList(TAG_MSG_INFO_STORAGE, FMT_INSERT), "MsgInfoStorage");
        }
        return msgInfoStorageClass;
    }

    /** e9 MsgInfo（hook f9.Bb 的参数类型）。 */
    public static Class<?> findMsgInfoClass() {
        if (msgInfoClass == null) {
            msgInfoClass = classByMethodStrings(
                    Arrays.asList(TAG_MSG_INFO, FIELD_LOG_TALKER, FIELD_LOG_SVRID, FIELD_LOG_CONTENT),
                    "MsgInfo");
        }
        return msgInfoClass;
    }

    /** v51.r1 SendMsgCgiFactory.Builder（发送 API）。 */
    public static Class<?> findSendFactoryClass() {
        if (sendFactoryClass == null) {
            sendFactoryClass = classByMethodStrings(
                    Arrays.asList(TAG_SEND_FACTORY, STR_EXECUTE_BY_PPC), "SendMsgCgiFactory");
        }
        return sendFactoryClass;
    }

    /** v51.p1 发送类型枚举（values() + 枚举常量串）。 */
    public static Class<?> findSendTypeEnumClass() {
        if (sendTypeEnumClass == null) {
            sendTypeEnumClass = findClass(FindClass.create().matcher(ClassMatcher.create()
                    .addMethod(MethodMatcher.create()
                            .name("values", StringMatchType.Equals, false)
                            .usingStrings(Arrays.asList(SEND_TYPE_ENUM_NAMES)))), "SendTypeEnum");
        }
        return sendTypeEnumClass;
    }

    /** b41.aa MsgInfoStorageLogic。 */
    public static Class<?> findStorageLogicClass() {
        if (storageLogicClass == null) {
            storageLogicClass = classByMethodStrings(
                    Arrays.asList(STR_TALKER_POS_NULL), "MsgInfoStorageLogic");
        }
        return storageLogicClass;
    }

    /** b41.g2 ConversationLogic（会话列表 g()）。 */
    public static Class<?> findConversationLogicClass() {
        if (conversationLogicClass == null) {
            conversationLogicClass = classByMethodStrings(
                    Arrays.asList(TAG_CONVERSATION_LOGIC, STR_CONV_SQL), "ConversationLogic");
        }
        return conversationLogicClass;
    }

    /** l4 ConversationStorage。 */
    public static Class<?> findConversationStorageClass() {
        if (conversationStorageClass == null) {
            conversationStorageClass = classByMethodStrings(
                    Arrays.asList(TAG_CONVERSATION_STORAGE, STR_CONV_INSERT_FAIL), "ConversationStorage");
        }
        return conversationStorageClass;
    }

    /** j4 RContactStorage（实现未混淆接口 com.tencent.mm.storage.d8）。 */
    public static Class<?> findRContactStorageClass() {
        if (rcontactStorageClass == null) {
            rcontactStorageClass = findClass(FindClass.create().matcher(ClassMatcher.create()
                    .addInterface(IFACE_RCONTACT_STORAGE, StringMatchType.Equals, false)), "RContactStorage");
        }
        return rcontactStorageClass;
    }

    /** g RContact 实体（字段名未混淆匹配）。 */
    public static Class<?> findRContactClass() {
        if (rcontactClass == null) {
            rcontactClass = findClass(FindClass.create().matcher(ClassMatcher.create()
                    .addFieldForName(FIELD_USERNAME, StringMatchType.Equals, false)
                    .addFieldForName(FIELD_NICKNAME, StringMatchType.Equals, false)
                    .addFieldForName(FIELD_CON_REMARK, StringMatchType.Equals, false)), "RContact");
        }
        return rcontactClass;
    }

    /** b41.e AccountStorage。 */
    public static Class<?> findAccountStorageClass() {
        if (accountStorageClass == null) {
            accountStorageClass = classByMethodStrings(
                    Arrays.asList(TAG_ACCOUNT_STORAGE, STR_ATTACHMENT, STR_RECORD, STR_IMG_SHAKE),
                    "AccountStorage");
        }
        return accountStorageClass;
    }

    /**
     * b41.h9 核心 Hub。
     * <p>
     * 无直接字符串锚点，改用结构特征：含方法 {@code b()} 且返回类型即 AccountStorage
     * （文档 §15.4 核心 Hub 定位法）。
     */
    public static Class<?> findCoreHubClass() {
        if (coreHubClass == null) {
            Class<?> acc = findAccountStorageClass();
            if (acc == null) {
                Log.w(TAG, "findCoreHubClass: AccountStorage 未定位，无法反推 Hub");
                return null;
            }
            coreHubClass = findClass(FindClass.create().matcher(ClassMatcher.create()
                    .addMethod(MethodMatcher.create()
                            .name("b", StringMatchType.Equals, false)
                            .returnType(acc.getName(), StringMatchType.Equals, false))), "CoreHub");
        }
        return coreHubClass;
    }

    /**
     * ChattingUIFragment（菜单注入宿主）。
     * 按 {@code onCreateOptionsMenu(Menu, MenuInflater)} 方法签名定位（文档 §15.4）。
     */
    public static Class<?> findChattingUIFragment() {
        if (chattingFragmentClass == null) {
            MethodDataList list = findMethods(FindMethod.create().matcher(MethodMatcher.create()
                    .name(METHOD_ON_CREATE_OPTIONS_MENU, StringMatchType.Equals, false)
                    .paramTypes(Arrays.asList(PARAM_MENU, PARAM_MENU_INFLATER))));
            if (list.isEmpty()) {
                // 回退：方法名 + 参数个数
                list = findMethods(FindMethod.create().matcher(MethodMatcher.create()
                        .name(METHOD_ON_CREATE_OPTIONS_MENU, StringMatchType.Equals, false)
                        .paramCount(2)));
            }
            for (MethodData md : list) {
                String cn = md.getDeclaredClassName();
                if (cn != null && cn.contains("ChattingUI")) {
                    chattingFragmentClass = toClass(cn, "ChattingUIFragment");
                    if (chattingFragmentClass != null) {
                        return chattingFragmentClass;
                    }
                }
            }
            if (!list.isEmpty()) {
                chattingFragmentClass = toClass(list.get(0).getDeclaredClassName(), "ChattingUIFragment");
            }
        }
        return chattingFragmentClass;
    }

    // ---------- 通用查询 ----------

    /** 按「类中某方法使用了全部给定字符串」定位类。 */
    private static Class<?> classByMethodStrings(List<String> strings, String label) {
        return findClass(FindClass.create().matcher(ClassMatcher.create()
                .addMethod(MethodMatcher.create().usingStrings(strings))), label);
    }

    private static Class<?> findClass(FindClass query, String label) {
        DexKitBridge bridge = com.leshao.ai.util.DexKitBridgeHolder.get();
        if (bridge == null) {
            Log.w(TAG, label + ": DEXKIT 不可用");
            return null;
        }
        try {
            ClassDataList list = bridge.findClass(query);
            if (list == null || list.isEmpty()) {
                Log.w(TAG, label + " 未找到匹配类");
                return null;
            }
            if (list.size() > 1) {
                Log.i(TAG, label + " 命中 " + list.size() + " 个类，取第一个: "
                        + list.get(0).getName());
            }
            return toClass(list.get(0).getName(), label);
        } catch (Throwable t) {
            Log.w(TAG, "findClass(" + label + ") 失败: " + t);
            return null;
        }
    }

    private static MethodDataList findMethods(FindMethod query) {
        DexKitBridge bridge = com.leshao.ai.util.DexKitBridgeHolder.get();
        if (bridge == null) {
            return new MethodDataList();
        }
        try {
            MethodDataList list = bridge.findMethod(query);
            return list == null ? new MethodDataList() : list;
        } catch (Throwable t) {
            Log.w(TAG, "findMethods 失败: " + t);
            return new MethodDataList();
        }
    }

    private static Class<?> toClass(String className, String label) {
        if (className == null) {
            return null;
        }
        // v1042: 优先用微信 Tinker 真实运行时 CL(DelegateLastClassLoader) 加载,
        // 否则拿到的是 base.apk 平行副本, hook 挂上不生效/静态单例取不到。
        ClassLoader cl = HookEntry.appClassLoader;
        try {
            ClassLoader tk = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao")
                    && tk != cl) {
                cl = tk;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            if (cl != HookEntry.appClassLoader) {
                Log.i(TAG, label + " 经真实 CL 加载: " + className);
            }
            return c;
        } catch (Throwable t) {
            Log.w(TAG, label + " findClass 失败: " + className + " -> " + t);
            return null;
        }
    }

    /** 供日志/调试：当前解析到的关键类名。 */
    public static String dump() {
        return "f9=" + name(msgInfoStorageClass)
                + " e9=" + name(msgInfoClass)
                + " r1=" + name(sendFactoryClass)
                + " p1=" + name(sendTypeEnumClass)
                + " aa=" + name(storageLogicClass)
                + " g2=" + name(conversationLogicClass)
                + " l4=" + name(conversationStorageClass)
                + " j4=" + name(rcontactStorageClass)
                + " g=" + name(rcontactClass)
                + " e=" + name(accountStorageClass)
                + " h9=" + name(coreHubClass)
                + " fragment=" + name(chattingFragmentClass);
    }

    private static String name(Class<?> c) {
        return c == null ? "null" : c.getName();
    }
}
