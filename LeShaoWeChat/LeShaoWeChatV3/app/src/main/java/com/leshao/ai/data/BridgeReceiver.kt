package com.leshao.ai.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log

class BridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        val action = intent.action
        if (action == null) {
            return
        }
        if (!isCallerTrusted(context)) {
            Log.w(TAG, "忽略非白名单来源广播 action=" + action
                    + " uid=" + Binder.getCallingUid())
            return
        }
        try {
            if (AiDataProvider.ACTION_REQUEST_CONFIG == action) {
                AiDataProvider.pushRefresh(context)
            } else if (AiDataProvider.ACTION_PUSH_SESSIONS == action) {
                val sessions = intent.getStringExtra(AiDataProvider.EXTRA_SESSIONS)
                if (sessions != null && sessions.isNotEmpty()) {
                    AiDataProvider.writeLocal(context, "sessions.json", sessions)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onReceive 失败: " + t)
        }
    }

    private fun isCallerTrusted(context: Context): Boolean {
        val uid: Int = if (Build.VERSION.SDK_INT >= 34) {
            val suid = sentFromUid
            if (suid == Process.INVALID_UID) Binder.getCallingUid() else suid
        } else {
            Binder.getCallingUid()
        }
        return AiDataProvider.isTrustedUid(context, uid)
    }

    companion object {
        private const val TAG = "LeshaoAI.Bridge"
    }
}