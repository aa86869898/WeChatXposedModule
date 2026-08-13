package com.leshao.v3.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VoiceHistoryDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "voice_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "voice_history";

    private static VoiceHistoryDbHelper sInstance;

    public static synchronized VoiceHistoryDbHelper getInstance(Context ctx) {
        if (sInstance == null) {
            sInstance = new VoiceHistoryDbHelper(ctx.getApplicationContext());
        }
        return sInstance;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
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
