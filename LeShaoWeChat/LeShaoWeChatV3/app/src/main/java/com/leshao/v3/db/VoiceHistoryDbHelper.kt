package com.leshao.v3.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.leshao.v3.LogWriter
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VoiceHistoryDbHelper private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "file_path TEXT NOT NULL, "
            + "file_name TEXT NOT NULL, "
            + "talker TEXT, "
            + "duration_ms INTEGER DEFAULT 0, "
            + "created_at INTEGER NOT NULL DEFAULT 0"
            + ")")
        db.execSQL("CREATE INDEX IF NOT EXISTS " + IDX_CREATED_AT + " ON " + TABLE + "(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v986: 原空实现会静默丢失迁移。此处按版本增量迁移, 幂等可重复执行。
        try {
            LogWriter.log(TAG, "onUpgrade $oldVersion -> $newVersion")
            if (oldVersion < 2) {
                db.execSQL("CREATE INDEX IF NOT EXISTS " + IDX_CREATED_AT + " ON " + TABLE + "(created_at)")
            }
        } catch (t: Throwable) {
            LogWriter.log(TAG, "onUpgrade err: " + t.javaClass.simpleName + " " + t.message)
        }
    }

    fun insert(filePath: String, fileName: String, talker: String?, durationMs: Int): Long {
        val cv = ContentValues()
        cv.put("file_path", filePath)
        cv.put("file_name", fileName)
        cv.put("talker", talker ?: "")
        cv.put("duration_ms", durationMs)
        cv.put("created_at", System.currentTimeMillis())
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun queryAll(): List<VoiceHistoryItem> {
        val list = ArrayList<VoiceHistoryItem>()
        val db = readableDatabase
        val c = db.query(TABLE, null, null, null, null, null, "created_at DESC")
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    val item = VoiceHistoryItem()
                    item.id = c.getLong(c.getColumnIndexOrThrow("id"))
                    item.filePath = c.getString(c.getColumnIndexOrThrow("file_path"))
                    item.fileName = c.getString(c.getColumnIndexOrThrow("file_name"))
                    item.talker = c.getString(c.getColumnIndexOrThrow("talker"))
                    item.durationMs = c.getInt(c.getColumnIndexOrThrow("duration_ms"))
                    item.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    list.add(item)
                }
            } finally {
                c.close()
            }
        }
        return list
    }

    fun deleteById(id: Long): Boolean {
        return writableDatabase.delete(TABLE, "id=?", arrayOf(id.toString())) > 0
    }

    fun deleteExpired(cutoffTimeMs: Long): Int {
        return writableDatabase.delete(TABLE, "created_at < ?", arrayOf(cutoffTimeMs.toString()))
    }

    fun clearAll(): Int {
        return writableDatabase.delete(TABLE, null, null)
    }

    fun queryById(id: Long): VoiceHistoryItem? {
        val db = readableDatabase
        val c = db.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null)
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    val item = VoiceHistoryItem()
                    item.id = c.getLong(c.getColumnIndexOrThrow("id"))
                    item.filePath = c.getString(c.getColumnIndexOrThrow("file_path"))
                    item.fileName = c.getString(c.getColumnIndexOrThrow("file_name"))
                    item.talker = c.getString(c.getColumnIndexOrThrow("talker"))
                    item.durationMs = c.getInt(c.getColumnIndexOrThrow("duration_ms"))
                    item.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    return item
                }
            } finally {
                c.close()
            }
        }
        return null
    }

    class VoiceHistoryItem {
        @JvmField
        var id: Long = 0

        @JvmField
        var filePath: String = ""

        @JvmField
        var fileName: String = ""

        @JvmField
        var talker: String = ""

        @JvmField
        var durationMs: Int = 0

        @JvmField
        var createdAt: Long = 0
    }

    companion object {
        private const val TAG = "VoiceHistoryDb"
        private const val DB_NAME = "voice_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "voice_history"
        private const val IDX_CREATED_AT = "idx_created_at"

        /** v986: 双检锁单例必须 volatile, 否则指令重排可能导致其他线程读到未完全构造的实例。 */
        @Volatile
        private var sInstance: VoiceHistoryDbHelper? = null

        /** v986: 异步清理线程池(守护线程), 供调用方在后台执行 deleteExpired, 避免主线程阻塞。 */
        private val sCleanupPool: ExecutorService = Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "VoiceHistoryCleanup")
            t.isDaemon = true
            t
        }

        @JvmStatic
        fun getInstance(ctx: Context): VoiceHistoryDbHelper {
            var inst = sInstance
            if (inst == null) {
                synchronized(VoiceHistoryDbHelper::class.java) {
                    inst = sInstance
                    if (inst == null) {
                        inst = VoiceHistoryDbHelper(ctx.applicationContext)
                        sInstance = inst
                    }
                }
            }
            return inst!!
        }

        /**
         * v986: 供调用方(尤其是 MainHook 主线程链路)使用的异步清理入口。
         * 将 deleteExpired 调度到守护线程执行, 不在调用线程同步访问数据库。
         */
        @JvmStatic
        fun cleanupExpiredAsync(ctx: Context, cutoffTimeMs: Long) {
            try {
                sCleanupPool.execute {
                    try {
                        getInstance(ctx).deleteExpired(cutoffTimeMs)
                    } catch (t: Throwable) {
                        LogWriter.log(TAG, "cleanupExpiredAsync err: " + t.javaClass.simpleName)
                    }
                }
            } catch (ignored: Throwable) {
                // 线程池已关闭等异常不得逃逸
            }
        }
    }
}