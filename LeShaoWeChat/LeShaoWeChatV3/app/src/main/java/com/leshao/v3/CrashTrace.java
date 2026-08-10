package com.leshao.v3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashTrace {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static volatile FileOutputStream sFos;
    private static volatile OutputStreamWriter sWriter;
    private static volatile boolean sInited;

    public static void t(String tag) {
        try {
            if (!sInited) {
                sInited = true;
                File rootDir = PathUtil.getLeshaoRootDir();
                File f = new File(rootDir, "crash_trace.txt");
                sFos = new FileOutputStream(f, true);
                sWriter = new OutputStreamWriter(sFos, StandardCharsets.UTF_8);
            }
            if (sWriter != null) {
                String line = SDF.format(new Date()) + " " + tag + "\n";
                sWriter.write(line);
                sWriter.flush();
                sFos.getFD().sync();
            }
        } catch (Throwable ignored) {}
    }
}
