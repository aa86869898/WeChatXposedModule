package com.leshao.ai.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class AiDataProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (!isCallerTrusted()) {
            Log.w(TAG, "query 拒绝非白名单调用方 uid=" + Binder.getCallingUid())
            return null
        }
        val path = uri.path
        val file = fileForPath(path)
        if (file == null) {
            return null
        }
        var json = readFile(File(baseDir(), file))
        if (json == null) {
            json = ""
        }
        val cursor = MatrixCursor(arrayOf(COL_JSON))
        cursor.addRow(arrayOf<Any>(json))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!isCallerTrusted()) {
            Log.w(TAG, "insert 拒绝非白名单调用方 uid=" + Binder.getCallingUid())
            return null
        }
        val path = uri.path
        val file = fileForPath(path)
        if (file == null || values == null) {
            return null
        }
        val json = values.getAsString(COL_JSON)
        if (json == null) {
            return null
        }
        if (writeFile(File(baseDir(), file), json)) {
            Log.i(TAG, "insert " + path + " len=" + json.length)
            return Uri.withAppendedPath(uri, file)
        }
        return null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int {
        if (!isCallerTrusted()) {
            Log.w(TAG, "update 拒绝非白名单调用方 uid=" + Binder.getCallingUid())
            return 0
        }
        return if (insert(uri, values) != null) 1 else 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isCallerTrusted()) {
            Log.w(TAG, "delete 拒绝非白名单调用方 uid=" + Binder.getCallingUid())
            return 0
        }
        return 0
    }

    override fun getType(uri: Uri): String {
        return "text/plain"
    }

    private fun isCallerTrusted(): Boolean {
        return isTrustedUid(context, Binder.getCallingUid())
    }

    private fun baseDir(): File {
        return appDir(context)
    }

    private fun fileForPath(path: String?): String? {
        if (path == null) {
            return null
        }
        if (path.endsWith("/config")) {
            return FILE_CONFIG
        }
        if (path.endsWith("/sessions")) {
            return FILE_SESSIONS
        }
        return null
    }

    companion object {
        private const val TAG = "LeshaoAI.Provider"

        const val AUTHORITY = "com.leshao.v3.aiconfig"
        const val ACTION_REFRESH_CONFIG = "com.leshao.ai.action.REFRESH_CONFIG"
        const val ACTION_REQUEST_CONFIG = "com.leshao.ai.action.REQUEST_CONFIG"
        const val ACTION_PUSH_SESSIONS = "com.leshao.ai.action.PUSH_SESSIONS"

        const val EXTRA_CONFIG = "config"
        const val EXTRA_SESSIONS = "sessions"

        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val MODULE_PACKAGE = "com.leshao.v3"
        const val BRIDGE_RECEIVER = "com.leshao.ai.data.BridgeReceiver"

        private const val DIR_NAME = "leshao_ai"
        private const val FILE_CONFIG = "config.json"
        private const val FILE_SESSIONS = "sessions.json"
        private const val COL_JSON = "json"

        @JvmStatic
        fun isTrustedUid(context: Context?, uid: Int): Boolean {
            if (context == null) {
                return false
            }
            if (uid == Process.myUid()) {
                return true
            }
            try {
                val pm = context.packageManager
                val packages = pm.getPackagesForUid(uid)
                if (packages != null) {
                    for (pkg in packages) {
                        if (WECHAT_PACKAGE == pkg || MODULE_PACKAGE == pkg) {
                            return true
                        }
                    }
                }
                val name = pm.getNameForUid(uid)
                return WECHAT_PACKAGE == name || MODULE_PACKAGE == name
            } catch (t: Throwable) {
                Log.w(TAG, "isTrustedUid 失败: " + t)
                return false
            }
        }

        @JvmStatic
        fun appDir(context: Context?): File {
            return File(context!!.filesDir.parentFile, DIR_NAME)
        }

        @JvmStatic
        fun readLocal(context: Context?, name: String?): String? {
            if (context == null || name == null) {
                return null
            }
            return readFile(File(appDir(context), name))
        }

        @JvmStatic
        fun writeLocal(context: Context?, name: String?, json: String?): Boolean {
            if (context == null || name == null || json == null) {
                return false
            }
            return writeFile(File(appDir(context), name), json)
        }

        @JvmStatic
        fun pushRefresh(context: Context?) {
            if (context == null) {
                return
            }
            try {
                val config = readLocal(context, FILE_CONFIG)
                val out = Intent(ACTION_REFRESH_CONFIG)
                out.setPackage(WECHAT_PACKAGE)
                if (config != null) {
                    out.putExtra(EXTRA_CONFIG, config)
                }
                context.sendBroadcast(out)
            } catch (t: Throwable) {
                Log.w(TAG, "pushRefresh 失败: " + t)
            }
        }

        private fun readFile(f: File?): String? {
            if (f == null || !f.exists()) {
                return null
            }
            try {
                FileInputStream(f).use { fis ->
                    val data = ByteArray(f.length().toInt())
                    val read = fis.read(data)
                    if (read <= 0) {
                        return null
                    }
                    return String(data, 0, read, StandardCharsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "readFile 失败: " + t)
                return null
            }
        }

        private fun writeFile(f: File?, json: String): Boolean {
            try {
                val parent = f?.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return false
                }
                FileOutputStream(f, false).use { out ->
                    out.write(json.toByteArray(StandardCharsets.UTF_8))
                }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "writeFile 失败: " + t)
                return false
            }
        }
    }
}