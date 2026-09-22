package com.leshao.ai.hook.wechat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 配置桥（微信侧）。
 * <p>
 * 设置页/白名单页运行在模块 app 进程，数据经 {@link com.leshao.ai.data.AiDataProvider}
 * 交换到微信进程：
 * <ul>
 *   <li>{@link #syncFromProvider}：拉取 config/whitelist JSON，镜像到微信 files 目录
 *       （{@link AIBotCore} 的读取路径）</li>
 *   <li>{@link #registerRefreshReceiver}：监听 {@code ACTION_REFRESH_CONFIG} 广播，
 *       设置页保存后实时刷新</li>
 * </ul>
 */
public final class ConfigBridge {

    private static final String TAG = "LeshaoAI.ConfigBridge";

    private static final String AUTHORITY = "com.leshao.v3.aiconfig";
    private static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    private static final Uri WHITELIST_URI = Uri.parse("content://" + AUTHORITY + "/whitelist");

    private static final String DIR_NAME = "leshao_ai";
    private static final String FILE_CONFIG = "config.json";
    private static final String FILE_WHITELIST = "whitelist.json";

    private static volatile boolean receiverRegistered;

    private ConfigBridge() {
    }

    /** 从 Provider 拉取配置与白名单，镜像写入微信 files 目录。 */
    public static boolean syncFromProvider(Context context) {
        if (context == null) {
            return false;
        }
        ContentResolver cr = context.getContentResolver();
        if (cr == null) {
            return false;
        }
        boolean ok = false;
        try {
            String config = readUri(cr, CONFIG_URI);
            if (config != null && !config.isEmpty()) {
                ok |= writeFile(new File(context.getFilesDir(), DIR_NAME + "/" + FILE_CONFIG), config);
            }
            String whitelist = readUri(cr, WHITELIST_URI);
            if (whitelist != null && !whitelist.isEmpty()) {
                ok |= writeFile(new File(context.getFilesDir(), DIR_NAME + "/" + FILE_WHITELIST), whitelist);
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncFromProvider 失败: " + t);
        }
        return ok;
    }

    private static String readUri(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("json");
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getString(idx);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "readUri(" + uri + ") 失败: " + t);
        }
        return null;
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

    /** 注册配置刷新广播（模块进程设置页保存后触发）。 */
    public static void registerRefreshReceiver(Context context) {
        if (receiverRegistered || context == null) {
            return;
        }
        receiverRegistered = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    Log.i(TAG, "收到配置刷新广播");
                    if (syncFromProvider(ctx)) {
                        AIBotCore.reload();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(
                    com.leshao.ai.data.AiDataProvider.ACTION_REFRESH_CONFIG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            Log.i(TAG, "配置刷新广播接收器已注册");
        } catch (Throwable t) {
            receiverRegistered = false;
            Log.w(TAG, "registerRefreshReceiver 失败: " + t);
        }
    }
}
