package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import java.util.ArrayList;
import java.util.List;

public class HookManager {

    private static final String TAG = "HookManager";
    private static final List<Runnable> sPendingHooks = new ArrayList<>();
    private static volatile boolean sHooked = false;

    public static void register(Runnable hook) {
        if (sHooked) {
            try { hook.run(); } catch (Throwable t) {
                LogWriter.log(TAG, "hook FAILED: " + t.getMessage());
            }
        } else {
            synchronized (sPendingHooks) {
                sPendingHooks.add(hook);
            }
        }
    }

    public static void activateAll() {
        if (sHooked) return;
        sHooked = true;
        List<Runnable> hooks;
        synchronized (sPendingHooks) {
            hooks = new ArrayList<>(sPendingHooks);
            sPendingHooks.clear();
        }
        for (Runnable h : hooks) {
            try { h.run(); } catch (Throwable t) {
                LogWriter.log(TAG, "activate hook FAILED: " + t.getMessage());
            }
        }
        LogWriter.log(TAG, "all hooks activated: " + hooks.size());
    }
}
