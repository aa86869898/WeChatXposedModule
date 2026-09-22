package com.leshao.ai.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
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

    /** 微信侧请求模块 app 推送配置（显式广播，绕过包可见性限制）。 */
    public static final String ACTION_REQUEST_CONFIG = "com.leshao.ai.action.REQUEST_CONFIG";

    /** 微信侧回传会话列表（显式广播）。 */
    public static final String ACTION_PUSH_SESSIONS = "com.leshao.ai.action.PUSH_SESSIONS";

    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_WHITELIST = "whitelist";
    public static final String EXTRA_SESSIONS = "sessions";

    /** 宿主（微信）包名。 */
    public static final String WECHAT_PACKAGE = "com.tencent.mm";
    /** 本模块 app 包名。 */
    public static final String MODULE_PACKAGE = "com.leshao.v3";
    /** app 侧广播接收器（微信侧显式指定，避免依赖包可见性）。 */
    public static final String BRIDGE_RECEIVER = "com.leshao.ai.data.BridgeReceiver";

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
        return appDir(getContext());
    }

    /** 模块 app 的 AI 数据目录：{@code <data>/leshao_ai}（与 AppConfig/Whitelist 一致）。 */
    public static File appDir(Context context) {
        return new File(context.getFilesDir().getParentFile(), DIR_NAME);
    }

    /** 读取 app 侧本地 JSON 文件（不存在返回 null）。 */
    public static String readLocal(Context context, String name) {
        if (context == null || name == null) {
            return null;
        }
        return readFile(new File(appDir(context), name));
    }

    /** 写入 app 侧本地 JSON 文件。 */
    public static boolean writeLocal(Context context, String name, String json) {
        if (context == null || name == null || json == null) {
            return false;
        }
        return writeFile(new File(appDir(context), name), json);
    }

    /**
     * 把当前配置/白名单推送给微信进程（显式携带 JSON，微信侧无需再读 Provider）。
     * 供设置页保存、白名单页保存、以及微信侧拉取请求三种场景复用。
     */
    public static void pushRefresh(Context context) {
        if (context == null) {
            return;
        }
        try {
            String config = readLocal(context, FILE_CONFIG);
            String whitelist = readLocal(context, FILE_WHITELIST);
            Intent out = new Intent(ACTION_REFRESH_CONFIG);
            out.setPackage(WECHAT_PACKAGE);
            if (config != null) {
                out.putExtra(EXTRA_CONFIG, config);
            }
            if (whitelist != null) {
                out.putExtra(EXTRA_WHITELIST, whitelist);
            }
            context.sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "pushRefresh 失败: " + t);
        }
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
