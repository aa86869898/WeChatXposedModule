package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.dexkit.DexKitAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 联系人查询（文档 §9 线路六）。
 * <p>
 * 访问链：{@code b41.g2}/{@code j4.n(String,boolean)} 拿联系人对象，
 * 显示名按文档 §9.2 优先级：<b>备注 > 昵称 > wxid</b>。
 * 字段名（{@code field_conRemark}/{@code field_nickname}）未混淆，跨版本稳定。
 */
public final class ContactQuery {

    private static final String TAG = "LeshaoAI.Contact";

    private ContactQuery() {
    }

    /** 取联系人显示名（备注 > 昵称 > wxid）。失败返回 wxid。 */
    public static String displayName(String wxid) {
        if (wxid == null || wxid.isEmpty()) {
            return wxid;
        }
        try {
            Object rcs = StorageHub.get().rcontactStorage();
            if (rcs == null) {
                return wxid;
            }
            Object contact = XposedHelpers.callMethod(rcs, "n", wxid, true);
            if (contact == null) {
                return wxid;
            }
            // 1) 直接读未混淆字段（n() 可能直接返回 RContact 实体）
            String remark = readStringField(contact, "field_conRemark");
            String nick = readStringField(contact, "field_nickname");
            if (!isEmpty(remark)) {
                return remark;
            }
            if (!isEmpty(nick)) {
                return nick;
            }
            // 2) 文档 §9.2：g.newInstance() + g.b(contact) 转换后再读
            Class<?> g = DexKitAdapter.findRContactClass();
            if (g != null) {
                try {
                    Object rc = XposedHelpers.newInstance(g);
                    XposedHelpers.callMethod(rc, "b", contact);
                    remark = readStringField(rc, "field_conRemark");
                    nick = readStringField(rc, "field_nickname");
                    if (!isEmpty(remark)) {
                        return remark;
                    }
                    if (!isEmpty(nick)) {
                        return nick;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "displayName(" + wxid + ") 失败: " + t);
        }
        return wxid;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String readStringField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            f.setAccessible(true);
            Object o = f.get(obj);
            return o instanceof String ? (String) o : null;
        } catch (Throwable t) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object o = f.get(obj);
                return o instanceof String ? (String) o : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
