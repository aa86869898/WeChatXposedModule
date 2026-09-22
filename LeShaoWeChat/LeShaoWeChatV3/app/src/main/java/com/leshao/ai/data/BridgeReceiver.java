package com.leshao.ai.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 模块 app 侧的桥接接收器（运行在本 app 进程，随模块 APK 安装）。
 * <p>
 * 背景：Android 11+ 的包可见性使微信进程无法解析本 app 的
 * {@code content://com.leshao.v3.aiconfig}（日志表现为
 * {@code Failed to find provider info for com.leshao.v3.aiconfig}）。
 * 因此改用<b>显式广播</b>在两个进程间搬运数据：
 * <ul>
 *   <li>{@link AiDataProvider#ACTION_REQUEST_CONFIG}：微信侧请求配置，
 *       本器读取本地 JSON 后回推 {@link AiDataProvider#ACTION_REFRESH_CONFIG}
 *       （payload 直接放在 extras，微信侧无需再读 Provider）。</li>
 *   <li>{@link AiDataProvider#ACTION_PUSH_SESSIONS}：微信侧回传会话列表 JSON，
 *       本器落盘到 {@code sessions.json}，供白名单页「导入微信会话」读取。</li>
 * </ul>
 */
public class BridgeReceiver extends BroadcastReceiver {

    private static final String TAG = "LeshaoAI.Bridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        try {
            if (AiDataProvider.ACTION_REQUEST_CONFIG.equals(action)) {
                AiDataProvider.pushRefresh(context);
            } else if (AiDataProvider.ACTION_PUSH_SESSIONS.equals(action)) {
                String sessions = intent.getStringExtra(AiDataProvider.EXTRA_SESSIONS);
                if (sessions != null && !sessions.isEmpty()) {
                    AiDataProvider.writeLocal(context, "sessions.json", sessions);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onReceive 失败: " + t);
        }
    }
}
