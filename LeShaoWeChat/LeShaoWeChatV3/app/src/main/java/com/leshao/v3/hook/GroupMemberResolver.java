/*
 * ============================================================================
 *  文件名: GroupMemberResolver.java
 *  功能  : 群成员管理工具(GroupMemberTools)的 DexKit 动态适配解析器
 *  参照  : 成员改动记录查询.zip / DexKitResolver.java
 *  适配  : zip 使用 org.lsposed.lspd.dexkit.DexKitBridge(反射), 本项目使用
 *          org.luckypray:dexkit:2.2.0 的 DexKitCacheBridge 缓存桥(DexKitHelper 模式),
 *          此处按 luckypray API 重写 zip 的锚点解析语义。
 *
 *  【锚点依据】(zip 实测, 与 GroupMemberTools 硬编码一致)
 *  ChatroomInfoUI            : 字符串 roominfo_contact_anchor + room_name + see_room_member
 *  PreferenceScreen(g0)      : 字符串 notifyDataSetChanged + getView
 *  ChatroomMembersStorage(a3): 日志 MicroMsg.ChatroomMembersLogic
 *  ChatroomInfo(im.y1)       : 表名 chatroom + 字段 field_selfDisplayName / field_modifytime
 *  ContactStorage(j4)        : 日志 MicroMsg.ContactStorage / 方法 n(String,boolean)
 *  房间服务(vf0.e)           : 方法 bj(String)->pe5.f
 *  RoomFactory(pe5.f)        : 方法 n(String,String,String)
 *  场景回调(qe5.b)           : 方法 a(int,int,String,qe5.b)
 *  头像加载(zo5.a)           : 静态方法 a/b(ImageView,String)
 *  当前用户(b41.y1)          : 静态方法 u()->String
 *
 *  解析失败项保持 null, GroupMemberTools 回退硬编码类名。
 * ============================================================================
 */
package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GroupMemberResolver {

    private static final String TAG = "GroupMemberResolver";

    /** 解析结果容器(与 GroupMemberTools 的 CLS_* 一一对应) */
    public static class ResolveResult {
        public String chatroomInfoUI;
        public String prefScreen;
        public String imY1;
        public String a3;
        public String j4;
        public String roomSvc;
        public String roomFactory;
        public String sceneCb;
        public String zo5a;
        public String b41Y1;
    }

    /** 入口: 解析关键类, 成功后回填给 GroupMemberTools。失败项保持 null 回退硬编码。 */
    public static boolean resolve(ClassLoader cl) {
        ResolveResult r = new ResolveResult();
        try {
            r.chatroomInfoUI = pickClass(cl, "roominfo_contact_anchor", "see_room_member", null);
            r.prefScreen = pickClass(cl, "notifyDataSetChanged", "getView", "preference");
            r.imY1 = pickClass(cl, "field_selfDisplayName", "field_modifytime", null);
            r.a3 = firstClass(cl, "MicroMsg.ChatroomMembersLogic");
            // 优先复用 DexKit 已扫描确认的 ContactStorage 类名(ChatGroupHook 同一来源),
            // 避免 firstClass("MicroMsg.ContactStorage") 误选 i4 而真实类是 v7。
            r.j4 = DexKitHelper.getContactStorageClass();
            if (r.j4 == null || r.j4.isEmpty()) {
                r.j4 = firstClass(cl, "MicroMsg.ContactStorage");
            }
            r.roomSvc = null;
            r.roomFactory = null;
            r.sceneCb = null;
            r.zo5a = null;
            r.b41Y1 = null;

            LogWriter.log(TAG, "resolve: ui=" + r.chatroomInfoUI
                    + " screen=" + r.prefScreen + " y1=" + r.imY1
                    + " a3=" + r.a3 + " j4=" + r.j4);
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolve err: " + t.getMessage());
        }
        GroupMemberTools.applyResolve(r);
        return r.chatroomInfoUI != null || r.imY1 != null || r.a3 != null;
    }

    /* 通过串用关键字过滤类: 每个关键字都用 DexKit 找类, 取交集; 可选按类名片段过滤 */
    private static String pickClass(ClassLoader cl, String kw1, String kw2, String nameFragment) {
        List<String> a = classByKeyword(cl, kw1);
        if (a.isEmpty()) return null;
        List<String> b = classByKeyword(cl, kw2);
        List<String> merged = new ArrayList<>();
        for (String s : a) {
            if (b.contains(s) && !merged.contains(s)) merged.add(s);
        }
        List<String> pool = merged.isEmpty() ? a : merged;
        for (String s : pool) {
            if (nameFragment != null && !containsIgnoreCase(s, nameFragment)) continue;
            return s;
        }
        return pool.isEmpty() ? null : pool.get(0);
    }

    /* 按关键字找出"方法中使用该字符串"或"字段类型为该字符串"的类 */
    private static List<String> classByKeyword(ClassLoader cl, String keyword) {
        if (keyword == null || keyword.isEmpty()) return Collections.emptyList();
        try {
            List<String> r = DexKitHelper.findClassesByString(cl, keyword);
            return r == null ? Collections.<String>emptyList() : r;
        } catch (Throwable t) {
            LogWriter.log(TAG, "classByKeyword(" + keyword + ") err " + t.getMessage());
            return Collections.emptyList();
        }
    }

    /* 取关键字命中的第一个类名(带常见路径过滤), 无则 null */
    private static String firstClass(ClassLoader cl, String keyword) {
        List<String> list = classByKeyword(cl, keyword);
        if (list.isEmpty()) return null;
        for (String s : list) {
            String low = s.toLowerCase();
            if (low.contains("com.tencent.mm.storage")) return s;
        }
        return list.get(0);
    }

    private static boolean containsIgnoreCase(String s, String p) {
        return s != null && p != null && s.toLowerCase().contains(p.toLowerCase());
    }
}