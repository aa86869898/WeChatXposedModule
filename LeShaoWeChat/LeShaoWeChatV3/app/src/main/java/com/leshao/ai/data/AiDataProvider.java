package com.leshao.ai.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AI 数据跨进程交换层（ContentProvider）。
 * <p>
 * 背景：设置页/白名单页运行在<b>模块 app 进程</b>（数据落模块 files 目录），
 * 而微信 hook 运行在<b>微信进程</b>（无法直接读模块私有目录）。
 * 本 Provider 以模块 files 目录的 JSON 文件为唯一数据源，向微信进程提供：
 * <ul>
 *   <li>{@code /config}    —— 配置 config.json（微信侧同步镜像后读取）</li>
 *   <li>{@code /whitelist} —— 白名单 whitelist.json</li>
 *   <li>{@code /sessions}  —— 会话列表（微信侧转储，白名单页导入用）</li>
 * </ul>
 * 配置变更后设置页发送 {@link #ACTION_REFRESH_CONFIG} 广播（目标包 com.tencent.mm），
 * 微信侧 {@link com.leshao.ai.hook.wechat.ConfigBridge} 接收并重新同步。
 */
public class AiDataProvider extends ContentProvider {

    private static final String TAG = "LeshaoAI.Provider";

    /** 与 manifest 声明的 authority 一致。 */
    public static final String AUTHORITY = "com.leshao.v3.aiconfig";

    /** 配置刷新广播 action。 */
    public static final String ACTION_REFRESH_CONFIG = "com.leshao.ai.action.REFRESH_CONFIG";

    private static final String DIR_NAME = "leshao_ai";
    private static final String FILE_CONFIG = "config.json";
    private static final String FILE_WHITELIST = "whitelist.json";
    private static final String FILE_SESSIONS = "sessions.json";

    private static final String COL_JSON = "json";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String path = uri.getPath();
        String file = fileForPath(path);
        if (file == null) {
            return null;
        }
        String json = readFile(new File(baseDir(), file));
        if (json == null) {
            json = "";
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{COL_JSON});
        cursor.addRow(new Object[]{json});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        String path = uri.getPath();
        String file = fileForPath(path);
        if (file == null || values == null) {
            return null;
        }
        String json = values.getAsString(COL_JSON);
        if (json == null) {
            return null;
        }
        if (writeFile(new File(baseDir(), file), json)) {
            Log.i(TAG, "insert " + path + " len=" + json.length());
            return Uri.withAppendedPath(uri, file);
        }
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return insert(uri, values) != null ? 1 : 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    private static String fileForPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.endsWith("/config")) {
            return FILE_CONFIG;
        }
        if (path.endsWith("/whitelist")) {
            return FILE_WHITELIST;
        }
        if (path.endsWith("/sessions")) {
            return FILE_SESSIONS;
        }
        return null;
    }

    private File baseDir() {
        return new File(getContext().getFilesDir().getParentFile(), DIR_NAME);
    }

    private static String readFile(File f) {
        if (f == null || !f.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = in.read(data);
            if (read <= 0) {
                return null;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "readFile 失败: " + t);
            return null;
        }
    }

    private static boolean writeFile(File f, String json) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "writeFile 失败: " + t);
            return false;
        }
    }
}
