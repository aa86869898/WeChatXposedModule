package com.leshao.v3;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 主微信 -> 分身微信 配置单向同步。
 *
 * 分身微信(user != 0)进程内 su 二进制不可见(挂载命名空间隔离)，无法用 su 读主微信 prefs。
 * 因此改为：分身直接读写自己的原生 prefs(UnifiedPrefs 统一返回原生实现)，
 * 由主微信(user 0)在启动/需要时用 su 把主微信的关键 prefs 推送到所有分身的 prefs 目录。
 */
public class ConfigSync {

    private static final String TAG = "ConfigSync";
    private static final String MAIN_DATA = "/data/data/com.tencent.mm";
    private static final int WX_APP_ID = 10356;

    private static final String[] SYNC_PREFS = {
        "leshao_v3_prefs",
        "leshao_shadow_labels",
        "wxgroup_config",
        "wm_prefs"
    };

    private static final String[] SU_CANDIDATES = {
        "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/sbin/su", "su"
    };

    private ConfigSync() {}

    public static void syncFromMainToClones() {
        if (PathUtil.getMyUserId() != 0) return;
        Thread t = new Thread(() -> {
            try {
                List<Integer> clones = listCloneUserIds();
                LogWriter.log(TAG, "发现分身 user: " + clones);
                for (int uid : clones) {
                    for (String name : SYNC_PREFS) {
                        syncOne(uid, name);
                    }
                }
                LogWriter.log(TAG, "同步完成");
            } catch (Throwable t2) {
                LogWriter.log(TAG, "sync FAIL: " + t2.getClass().getSimpleName() + ":" + t2.getMessage());
            }
        }, "leshao-config-sync");
        t.setDaemon(true);
        t.start();
    }

    private static List<Integer> listCloneUserIds() {
        List<Integer> result = new ArrayList<>();
        String out = execRoot("ls /data/user");
        if (out == null || out.isEmpty()) return result;
        for (String line : out.split("\\s+")) {
            line = line.trim();
            if (line.isEmpty() || !line.matches("\\d+")) continue;
            int uid = Integer.parseInt(line);
            if (uid == 0) continue;
            String check = execRoot("ls -d /data/user/" + uid + "/com.tencent.mm 2>/dev/null");
            if (check != null && !check.trim().isEmpty()) {
                result.add(uid);
            }
        }
        return result;
    }

    private static void syncOne(int userId, String name) {
        String src = MAIN_DATA + "/shared_prefs/" + name + ".xml";
        String dstDir = "/data/user/" + userId + "/com.tencent.mm/shared_prefs";
        String dst = dstDir + "/" + name + ".xml";
        int uid = userId * 100000 + WX_APP_ID;
        String cmd = "mkdir -p " + dstDir
            + " && cp " + src + " " + dst + ".tmp"
            + " && mv " + dst + ".tmp " + dst
            + " && chmod 660 " + dst
            + " && chown " + uid + ":" + uid + " " + dst;
        String out = execRoot(cmd);
        LogWriter.log(TAG, "syncOne user=" + userId + " name=" + name + " ok=" + (out != null));
    }

    private static String execRoot(String cmd) {
        for (String su : SU_CANDIDATES) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
                String out = readFully(p.getInputStream());
                String err = readFully(p.getErrorStream());
                int code = p.waitFor();
                if (code == 0) {
                    return out;
                }
                LogWriter.log(TAG, "execRoot FAIL via " + su + " code=" + code
                    + " err=" + err.trim() + " cmd=" + cmd);
            } catch (Throwable t) {
                LogWriter.log(TAG, "execRoot EXCEPTION via " + su + ": "
                    + t.getClass().getSimpleName() + ":" + t.getMessage());
            } finally {
                if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
            }
        }
        return null;
    }

    private static String readFully(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), "UTF-8");
    }
}
