package com.leshao.v3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashTrace {

    private static final String TRACE_FILE = "crash_trace.txt";
    /** v1131: crash_trace.txt 大小上限, 超限轮转 .bak, 防止长跑无限增长 */
    private static final long MAX_SIZE = 256 * 1024;
    private static final long SIZE_CHECK_INTERVAL_MS = 5000;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final Object LOCK = new Object();

    private static File sFile;
    private static FileOutputStream sFos;
    private static OutputStreamWriter sWriter;
    private static boolean sInited;
    private static long sLastSizeCheck;

    public static void t(String tag) {
        try {
            synchronized (LOCK) {
                if (!sInited) {
                    sInited = true;
                    File rootDir = PathUtil.getLeshaoRootDir();
                    sFile = new File(rootDir, TRACE_FILE);
                    sFos = new FileOutputStream(sFile, true);
                    sWriter = new OutputStreamWriter(sFos, StandardCharsets.UTF_8);
                    sLastSizeCheck = System.currentTimeMillis();
                }
                if (sWriter == null) return;
                // v1131: 去掉每行 getFD().sync()(Hook 主线程回调里 fsync 严重拖慢), 仅 flush;
                // 并低频校验大小, 超限则轮转。
                long now = System.currentTimeMillis();
                if (now - sLastSizeCheck > SIZE_CHECK_INTERVAL_MS) {
                    sLastSizeCheck = now;
                    rotateIfNeededLocked();
                }
                sWriter.write(SDF.format(new Date()) + " " + tag + "\n");
                sWriter.flush();
            }
        } catch (Throwable ignored) {}
    }

    private static void rotateIfNeededLocked() {
        try {
            if (sFos == null || sFile == null || !sFile.exists() || sFile.length() <= MAX_SIZE) return;
            try { sWriter.flush(); } catch (Throwable ignored) {}
            try { sFos.close(); } catch (Throwable ignored) {}
            File bak = new File(sFile.getParent(), sFile.getName() + ".bak");
            if (bak.exists()) bak.delete();
            sFile.renameTo(bak);
            sFos = new FileOutputStream(sFile, true);
            sWriter = new OutputStreamWriter(sFos, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }
}
