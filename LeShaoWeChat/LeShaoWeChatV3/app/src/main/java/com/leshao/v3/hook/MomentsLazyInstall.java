package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v3.0.151: Moments 系列 hook 延后到微信主界面(LauncherUI)就绪后安装。
 * <p>冷启动早期(Application 初始化中) hook SNS 类(eu5.s0 / jk4.p / ImproveInteractionLayout)
 * 会与微信主线程的类加载/初始化竞争，触发类锁死锁 → 首次启动卡死(实测 52s 无日志、全进程挂起)。
 * 主界面就绪后类已加载完毕，安装快速且无死锁风险。朋友圈功能需进入主界面后才能使用，不丢时机。</p>
 */
public final class MomentsLazyInstall {

    private static final String TAG = "MomentsLazyInstall";
    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);

    private MomentsLazyInstall() {}

    /** 由 WmEntry 在 LauncherUI.onResume(主界面就绪)后调用，每个进程只安装一次。 */
    public static void maybeInstall(ClassLoader cl) {
        if (cl == null) return;
        if (!sInstalled.compareAndSet(false, true)) return;
        LogWriter.log(TAG, "LauncherUI ready, installing Moments hooks lazily");
        Thread t = new Thread(() -> {
            try {
                long s = System.currentTimeMillis();
                MomentsAutoLikeHook.hook(cl);
                MomentsFakeLikeHook.hook(cl);
                LogWriter.log(TAG, "Moments hooks installed in "
                        + (System.currentTimeMillis() - s) + "ms");
            } catch (Throwable e) {
                LogWriter.log(TAG, "lazy install err: " + e.getMessage());
                sInstalled.set(false);
            }
        }, "leshao-moments-lazy");
        t.setDaemon(true);
        t.start();
    }
}
