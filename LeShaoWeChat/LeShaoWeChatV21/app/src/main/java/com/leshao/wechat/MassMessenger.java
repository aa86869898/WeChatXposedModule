package com.leshao.wechat;

import android.content.Context;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MassMessenger {
    private static Context ctx;
    private static PowerManager.WakeLock wl;
    private static volatile boolean running = false, stop = false;
    public static void init(Context c) { ctx = c; }

    public static void executeSend(final int type, final String text, final Set<String> targets, final long interval) {
        if (running) { Utils.t(ctx, "群发进行中"); return; }
        if (targets == null || targets.isEmpty()) { Utils.t(ctx, "未选择目标"); return; }
        final List<String> tl = new ArrayList<>(targets);
        running = true; stop = false;

        // 获取WakeLock保持设备唤醒
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Leshao:Mass");
                wl.acquire(10 * 60 * 1000L);
            }
        } catch (Throwable e) {
            Utils.flog("Mass: WakeLock获取失败: " + e.getMessage());
        }

        new Thread(new Runnable() { public void run() {
            int ok = 0, fail = 0;
            try {
                for (int i = 0; i < tl.size(); i++) {
                    if (stop) break;
                    boolean r = sendWithRetry(tl.get(i), text != null ? text : "");
                    if (r) ok++; else fail++;
                    if (i < tl.size() - 1 && !stop) {
                        try { Thread.sleep(interval); } catch (Throwable e) {}
                    }
                }
                final int fok = ok, ffail = fail;
                Utils.rM(new Runnable() { public void run() {
                    Utils.t(ctx, "群发完成: " + fok + "成功 " + ffail + "失败");
                }});
            } catch (Throwable e) {
                Utils.flog("Mass: 群发异常: " + e.getMessage());
            } finally {
                running = false;
                try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable e) {}
            }
        }}).start();
    }

    /** 带重试的发送，最多重试3次 */
    private static boolean sendWithRetry(String target, String text) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                boolean r = WeChatHooks.sendTextMessage(target, text);
                if (r) return true;
                Utils.flog("Mass: 发送失败 attempt=" + attempt + " target=" + target);
            } catch (Throwable e) {
                Utils.flog("Mass: 发送异常 attempt=" + attempt + ": " + e.getMessage());
            }
            if (attempt < 3 && !stop) {
                try { Thread.sleep(attempt * 500L); } catch (Throwable e) {}
            }
        }
        return false;
    }

    public static void stopSend() {
        stop = true; running = false;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable e) {}
    }

    public static boolean isRunning() { return running; }
}
