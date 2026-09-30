package com.leshao.v3.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.leshao.v3.LogWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class VoiceHistoryDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "VoiceHistoryDb";
    private static final String DB_NAME = "voice_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "voice_history";
    private static final String IDX_CREATED_AT = "idx_created_at";

    /** v986: 双检锁单例必须 volatile, 否则指令重排可能导致其他线程读到未完全构造的实例。 */
    private static volatile VoiceHistoryDbHelper sInstance;

    /** v986: 异步清理线程池(守护线程), 供调用方在后台执行 deleteExpired, 避免主线程阻塞。 */
    private static final ExecutorService sCleanupPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "VoiceHistoryCleanup");
            t.setDaemon(true);
            return t;
        }
    });

    public static VoiceHistoryDbHelper getInstance(Context ctx) {
        VoiceHistoryDbHelper inst = sInstance;
        if (inst == null) {
            synchronized (VoiceHistoryDbHelper.class) {
                inst = sInstance;
                if (inst == null) {
                    inst = new VoiceHistoryDbHelper(ctx.getApplicationContext());
                    sInstance = inst;
                }
            }
        }
        return inst;
    }

    /**
     * v986: 供调用方(尤其是 MainHook 主线程链路)使用的异步清理入口。
     * 将 deleteExpired 调度到守护线程执行, 不在调用线程同步访问数据库。
     */
    public static void cleanupExpiredAsync(final Context ctx, final long cutoffTimeMs) {
        try {
            sCleanupPool.execute(() -> {
                try {
                    getInstance(ctx).deleteExpired(cutoffTimeMs);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "cleanupExpiredAsync err: " + t.getClass().getSimpleName());
                }
            });
        } catch (Throwable ignored) {
            // 线程池已关闭等异常不得逃逸
        }
    }

    private VoiceHistoryDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "file_path TEXT NOT NULL, "
                + "file_name TEXT NOT NULL, "
                + "talker TEXT, "
                + "duration_ms INTEGER DEFAULT 0, "
                + "created_at INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS " + IDX_CREATED_AT + " ON " + TABLE + "(created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v986: 原空实现会静默丢失迁移。此处按版本增量迁移, 幂等可重复执行。
        try {
            LogWriter.log(TAG, "onUpgrade " + oldVersion + " -> " + newVersion);
            if (oldVersion < 2) {
                db.execSQL("CREATE INDEX IF NOT EXISTS " + IDX_CREATED_AT + " ON " + TABLE + "(created_at)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "onUpgrade err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    public long insert(String filePath, String fileName, String talker, int durationMs) {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put("file_path", filePath);
        cv.put("file_name", fileName);
        cv.put("talker", talker != null ? talker : "");
        cv.put("duration_ms", durationMs);
        cv.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE, null, cv);
    }

    public List<VoiceHistoryItem> queryAll() {
        List<VoiceHistoryItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, "created_at DESC");
        if (c != null) {
            try {
            while (c.moveToNext()) {
                VoiceHistoryItem item = new VoiceHistoryItem();
                item.id = c.getLong(c.getColumnIndexOrThrow("id"));
                item.filePath = c.getString(c.getColumnIndexOrThrow("file_path"));
                item.fileName = c.getString(c.getColumnIndexOrThrow("file_name"));
                item.talker = c.getString(c.getColumnIndexOrThrow("talker"));
                item.durationMs = c.getInt(c.getColumnIndexOrThrow("duration_ms"));
                item.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                list.add(item);
            }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public boolean deleteById(long id) {
        return getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    public int deleteExpired(long cutoffTimeMs) {
        return getWritableDatabase().delete(TABLE, "created_at < ?", new String[]{String.valueOf(cutoffTimeMs)});
    }

    public int clearAll() {
        return getWritableDatabase().delete(TABLE, null, null);
    }

    public VoiceHistoryItem queryById(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    VoiceHistoryItem item = new VoiceHistoryItem();
                    item.id = c.getLong(c.getColumnIndexOrThrow("id"));
                    item.filePath = c.getString(c.getColumnIndexOrThrow("file_path"));
                    item.fileName = c.getString(c.getColumnIndexOrThrow("file_name"));
                    item.talker = c.getString(c.getColumnIndexOrThrow("talker"));
                    item.durationMs = c.getInt(c.getColumnIndexOrThrow("duration_ms"));
                    item.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                    return item;
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    public static class VoiceHistoryItem {
        public long id;
        public String filePath;
        public String fileName;
        public String talker;
        public int durationMs;
        public long createdAt;
    }
}
