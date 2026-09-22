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

import com.leshao.ai.data.AiDataProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 配置桥（微信侧）。
 * <p>
 * 设置页/白名单页运行在模块 app 进程，配置需要同步到微信进程供
 * {@link AIBotCore} 读取。由于 Android 11+ 的包可见性让微信进程<b>无法</b>
 * 解析模块 app 的 ContentProvider（日志表现为
 * {@code Failed to find provider info for com.leshao.v3.aiconfig}），
 * 本类改为以<b>显式广播 payload</b> 为主通道：
 * <ul>
 *   <li>{@link #applyFromIntent}：从 {@code ACTION_REFRESH_CONFIG} 广播 extras
 *       直接取出 config/whitelist JSON，镜像到微信进程的
 *       {@code <wechatData>/leshao_ai}（{@link AIBotCore} 读取路径）。</li>
 *   <li>{@link #requestConfig}：微信启动时主动请求模块 app 推送一次，
 *       解决「配置页保存时微信未运行」的冷启动旧数据问题。</li>
 *   <li>{@link #syncFromProvider}：ContentProvider 兜底（可见性允许时可用）。</li>
 * </ul>
 */
public final class ConfigBridge {

    private static final String TAG = "LeshaoAI.ConfigBridge";

    private static final String AUTHORITY = AiDataProvider.AUTHORITY;
    private static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    private static final Uri WHITELIST_URI = Uri.parse("content://" + AUTHORITY + "/whitelist");

    private static final String DIR_NAME = "leshao_ai";
    private static final String FILE_CONFIG = "config.json";
    private static final String FILE_WHITELIST = "whitelist.json";

    private static volatile boolean receiverRegistered;

    private ConfigBridge() {
    }

    /**
     * 微信进程侧的镜像文件路径：{@code <wechatData>/leshao_ai/xxx.json}。
     * 必须与 {@link AIBotCore#ensureInit} 传入的 hostDataDir 保持一致
     * （{@code context.getFilesDir().getParent()}），否则镜像写入后读不到。
     */
    private static File mirrorFile(Context context, String name) {
        return new File(context.getFilesDir().getParentFile(), DIR_NAME + "/" + name);
    }

    /** 从刷新广播 payload 应用配置/白名单（主通道，无需 Provider）。 */
    public static boolean applyFromIntent(Context context, Intent intent) {
        if (context == null || intent == null) {
            return false;
        }
        boolean ok = false;
        try {
            String config = intent.getStringExtra(AiDataProvider.EXTRA_CONFIG);
            if (config != null && !config.isEmpty()) {
                ok |= writeFile(mirrorFile(context, FILE_CONFIG), config);
            }
            String whitelist = intent.getStringExtra(AiDataProvider.EXTRA_WHITELIST);
            if (whitelist != null && !whitelist.isEmpty()) {
                ok |= writeFile(mirrorFile(context, FILE_WHITELIST), whitelist);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyFromIntent 失败: " + t);
        }
        return ok;
    }

    /** ContentProvider 兜底：可见性允许时拉取 config/whitelist 并镜像。 */
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
                ok |= writeFile(mirrorFile(context, FILE_CONFIG), config);
            }
            String whitelist = readUri(cr, WHITELIST_URI);
            if (whitelist != null && !whitelist.isEmpty()) {
                ok |= writeFile(mirrorFile(context, FILE_WHITELIST), whitelist);
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncFromProvider 失败: " + t);
        }
        return ok;
    }

    /**
     * 微信启动时向模块 app 请求一次配置推送。
     * 用显式 component + FLAG_INCLUDE_STOPPED_PACKAGES，避免受包可见性限制，
     * 且模块 app 处于 stopped 状态也能被唤起。
     */
    public static void requestConfig(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent req = new Intent(AiDataProvider.ACTION_REQUEST_CONFIG);
            req.setClassName(AiDataProvider.MODULE_PACKAGE, AiDataProvider.BRIDGE_RECEIVER);
            req.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(req);
            Log.i(TAG, "已请求模块 app 推送配置");
        } catch (Throwable t) {
            Log.w(TAG, "requestConfig 失败: " + t);
        }
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
                    // 优先用广播 payload；无 payload 时回退 Provider
                    boolean applied = applyFromIntent(ctx, intent);
                    if (!applied) {
                        applied = syncFromProvider(ctx);
                    }
                    if (applied) {
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
