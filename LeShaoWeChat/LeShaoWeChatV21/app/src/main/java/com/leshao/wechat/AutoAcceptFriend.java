package com.leshao.wechat;

public class AutoAcceptFriend {
    public static boolean accept(String wxid) {
        try {
            ClassLoader cl = WeChatHooks.getCL();
            Class<?> k = cl.loadClass("com.tencent.mm.kernel.h");
            Object svc = null;
            for (String cn : new String[]{"com.tencent.mm.plugin.profile.a$k", "com.tencent.mm.plugin.profile.a.k"}) {
                try { svc = de.robv.android.xposed.XposedHelpers.callStaticMethod(k, "ax", cl.loadClass(cn)); break; } catch (Exception e) {}
            }
            if (svc == null) return false;
            de.robv.android.xposed.XposedHelpers.callMethod(svc, "a", wxid, Integer.valueOf(2));
            String msg = ModuleSettings.autoAcceptFriendMsg;
            if (msg != null && !msg.isEmpty()) { Utils.rMD(new Runnable() { public void run() { WeChatHooks.sendTextMessage(wxid, msg); } }, 2000); }
            return true;
        } catch (Exception e) { return false; }
    }
}
