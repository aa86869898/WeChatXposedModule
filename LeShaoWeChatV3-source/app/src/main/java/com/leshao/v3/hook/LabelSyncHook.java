package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 标签同步监听：微信标签同步完成后通知标签栏刷新。
 */
public class LabelSyncHook {

    private static final String TAG = "LabelSyncHook";

    public static void install(ClassLoader cl) {
        try {
            Class<?> netScene = XposedHelpers.findClass("aa3.d", cl);
            XposedBridge.hookAllMethods(netScene, "onGYNetEnd",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            int errType = (int) p.args[0];
                            int errCode = (int) p.args[1];
                            if (errType == 0 && errCode == 0) {
                                int count = ChatGroupHook.labelCount();
                                LogWriter.log(TAG, "标签同步完成，" + count + " 个标签");
                                EventBus.post(EventBus.Event.LABELS_SYNCED, count);
                            }
                        } catch (Throwable e) {
                            LogWriter.log("LabelSyncHook", "cb err: " + e);
                        }
                    }
                });
            LogWriter.log(TAG, "同步监听已安装");
        } catch (Throwable e) { LogWriter.log(TAG, "install error: " + e.getMessage()); }
    }
}
