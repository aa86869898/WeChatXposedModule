package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.leshao.ai.data.AiDataProvider;
import com.leshao.ai.hook.dexkit.DexKitAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 会话列表查询（文档 §9.3 线路六）。
 * <p>
 * 访问链：{@code b41.g2}（ConversationLogic，TAG {@code MicroMsg.ConversationLogic}）
 * 单例 → {@code g()} → {@code List<k4>}；每项 {@code z0()} 取 username。
 * g() 已内置过滤官方账号/服务号等系统会话（文档 §9.3 实证过滤清单）。
 * <p>
 * 会话列表经显式广播转储到模块 app（Android 11+ 包可见性下微信进程
 * 无法访问模块 app 的 ContentProvider），供白名单页「导入微信会话」使用。
 */
public final class ConversationQuery {

    private static final String TAG = "LeshaoAI.ConvQuery";

    private ConversationQuery() {
    }

    /**
     * 列出当前会话。
     *
     * @return {@code [talker, displayName][]}；失败返回空列表
     */
    public static List<String[]> listSessions() {
        List<String[]> out = new ArrayList<>();
        try {
            Class<?> g2 = DexKitAdapter.findConversationLogicClass();
            if (g2 == null) {
                Log.w(TAG, "b41.g2(ConversationLogic) 未定位");
                return out;
            }
            Object logic = singletonOf(g2);
            if (logic == null) {
                Log.w(TAG, "ConversationLogic 单例获取失败");
                return out;
            }
            Object list = XposedHelpers.callMethod(logic, "g");
            if (!(list instanceof List)) {
                Log.w(TAG, "g() 未返回 List: " + list);
                return out;
            }
            for (Object item : (List<?>) list) {
                if (item == null) {
                    continue;
                }
                String talker = sessionTalker(item);
                if (talker == null || talker.isEmpty()) {
                    continue;
                }
                out.add(new String[]{talker, ContactQuery.displayName(talker)});
            }
        } catch (Throwable t) {
            Log.w(TAG, "listSessions 失败: " + t);
        }
        return out;
    }

    /** 从会话实体（k4）取 username：z0() 优先，兜底遍历 String 字段。 */
    private static String sessionTalker(Object item) {
        try {
            Object o = XposedHelpers.callMethod(item, "z0");
            if (o instanceof String && !((String) o).isEmpty()) {
                return (String) o;
            }
        } catch (Throwable ignored) {
        }
        for (java.lang.reflect.Field f : item.getClass().getFields()) {
            if (f.getType() != String.class) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object o = f.get(item);
                if (o instanceof String) {
                    String s = (String) o;
                    if (s.endsWith("@chatroom") || s.startsWith("wxid_") || s.startsWith("gh_")) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 通用单例反射：静态无参方法返回自身，或静态字段为自身类型。
     */
    private static Object singletonOf(Class<?> cls) {
        // 1) 静态无参方法（getInstance / d / b ...）
        for (Method m : cls.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                continue;
            }
            if ("values".equals(m.getName()) || "getDeclaringClass".equals(m.getName())) {
                continue;
            }
            if (cls.isAssignableFrom(m.getReturnType())) {
                try {
                    Object o = m.invoke(null);
                    if (o != null) {
                        return o;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 2) 静态字段
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (cls.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object o = f.get(null);
                    if (o != null) {
                        return o;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 把会话列表回传给模块 app（白名单页导入数据源）。 */
    public static boolean dumpSessions(Context context) {
        if (context == null) {
            return false;
        }
        try {
            List<String[]> sessions = listSessions();
            JSONArray arr = new JSONArray();
            for (String[] s : sessions) {
                JSONObject o = new JSONObject();
                o.put("talker", s[0]);
                o.put("name", s[1]);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("updated", System.currentTimeMillis());
            root.put("sessions", arr);

            Intent push = new Intent(AiDataProvider.ACTION_PUSH_SESSIONS);
            push.setClassName(AiDataProvider.MODULE_PACKAGE, AiDataProvider.BRIDGE_RECEIVER);
            push.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            push.putExtra(AiDataProvider.EXTRA_SESSIONS, root.toString());
            context.sendBroadcast(push);
            Log.i(TAG, "会话已回传模块 app: " + sessions.size() + " 个");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "dumpSessions 失败: " + t);
            return false;
        }
    }
}
