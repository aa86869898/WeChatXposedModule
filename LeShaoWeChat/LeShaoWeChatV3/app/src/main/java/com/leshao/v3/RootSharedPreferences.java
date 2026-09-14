package com.leshao.v3;

import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分身微信专用的 SharedPreferences 实现：通过 root(su) 直接读写主微信
 * (/data/data/com.tencent.mm/) 下同名 SharedPreferences 文件，实现主分身配置统一。
 *
 * 主微信(user 0)直接使用系统 SharedPreferences，不经过本类。
 * 本类只在分身(user != 0)实例化，把读写重定向到主微信路径。
 */
public class RootSharedPreferences implements SharedPreferences {

    private static final String MAIN_DATA_DIR = "/data/data/com.tencent.mm";
    private static final String TAG = "RootPrefs";
    private static final Object REMOVE = new Object();

    private final String mName;
    private final String mXmlPath;
    private final File mTmpFile;
    private final Map<String, Object> mMap = new HashMap<>();
    private final Object mLock = new Object();
    private boolean mLoaded = false;

    private static final ExecutorService sWriter = Executors.newSingleThreadExecutor();
    private static volatile String sUidGid = null;

    public RootSharedPreferences(String name, File tmpDir) {
        mName = name;
        mXmlPath = MAIN_DATA_DIR + "/shared_prefs/" + name + ".xml";
        mTmpFile = new File(tmpDir, "leshao_" + name + ".tmp");
    }

    // ================= 加载 =================

    private void ensureLoaded() {
        if (mLoaded) return;
        synchronized (mLock) {
            if (mLoaded) return;
            try {
                String xml = execRoot("cat " + mXmlPath);
                if (xml != null) parseXml(xml);
            } catch (Throwable t) {
                LogWriter.log(TAG, "load fail(" + mName + "): " + t.getMessage());
            }
            mLoaded = true;
        }
    }

    private void parseXml(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        String pendingStringKey = null;
        int ev = parser.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("string".equals(tag)) {
                    pendingStringKey = parser.getAttributeValue(null, "name");
                } else if ("boolean".equals(tag)) {
                    String k = parser.getAttributeValue(null, "name");
                    mMap.put(k, Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                } else if ("int".equals(tag)) {
                    String k = parser.getAttributeValue(null, "name");
                    try { mMap.put(k, Integer.parseInt(parser.getAttributeValue(null, "value"))); }
                    catch (Throwable ignored) {}
                } else if ("long".equals(tag)) {
                    String k = parser.getAttributeValue(null, "name");
                    try { mMap.put(k, Long.parseLong(parser.getAttributeValue(null, "value"))); }
                    catch (Throwable ignored) {}
                } else if ("float".equals(tag)) {
                    String k = parser.getAttributeValue(null, "name");
                    try { mMap.put(k, Float.parseFloat(parser.getAttributeValue(null, "value"))); }
                    catch (Throwable ignored) {}
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (pendingStringKey != null) {
                    mMap.put(pendingStringKey, parser.getText());
                    pendingStringKey = null;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("string".equals(parser.getName()) && pendingStringKey != null) {
                    mMap.put(pendingStringKey, "");
                    pendingStringKey = null;
                }
            }
            ev = parser.next();
        }
    }

    // ================= 读取 =================

    @Override
    public String getString(String key, String defValue) {
        ensureLoaded();
        synchronized (mLock) {
            Object v = mMap.get(key);
            return v instanceof String ? (String) v : defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        ensureLoaded();
        synchronized (mLock) {
            Object v = mMap.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        ensureLoaded();
        synchronized (mLock) {
            Object v = mMap.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        ensureLoaded();
        synchronized (mLock) {
            Object v = mMap.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        ensureLoaded();
        synchronized (mLock) {
            Object v = mMap.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }
    }

    @Override
    public boolean contains(String key) {
        ensureLoaded();
        synchronized (mLock) { return mMap.containsKey(key); }
    }

    @Override
    public Map<String, ?> getAll() {
        ensureLoaded();
        synchronized (mLock) { return new HashMap<>(mMap); }
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    // ================= 写入 =================

    @Override
    public Editor edit() {
        return new EditorImpl();
    }

    private class EditorImpl implements Editor {
        private final Map<String, Object> mPending = new HashMap<>();
        private boolean mClear = false;

        @Override public Editor putString(String key, String value) { mPending.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { mPending.put(key, value); return this; }
        @Override public Editor putInt(String key, int value) { mPending.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { mPending.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { mPending.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { return this; }
        @Override public Editor remove(String key) { mPending.put(key, REMOVE); return this; }
        @Override public Editor clear() { mClear = true; mPending.clear(); return this; }

        @Override
        public boolean commit() {
            applyToMap();
            persistNow();
            return true;
        }

        @Override
        public void apply() {
            applyToMap();
            sWriter.execute(RootSharedPreferences.this::persistNow);
        }

        private void applyToMap() {
            synchronized (mLock) {
                if (mClear) mMap.clear();
                for (Map.Entry<String, Object> e : mPending.entrySet()) {
                    if (e.getValue() == REMOVE) mMap.remove(e.getKey());
                    else mMap.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    private void persistNow() {
        String xml;
        synchronized (mLock) { xml = serializeXml(); }
        writeToRoot(xml);
    }

    private String serializeXml() {
        try {
            XmlSerializer ser = Xml.newSerializer();
            StringWriter sw = new StringWriter();
            ser.setOutput(sw);
            ser.startDocument("UTF-8", true);
            ser.startTag(null, "map");
            for (Map.Entry<String, Object> e : mMap.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) {
                    ser.startTag(null, "string");
                    ser.attribute(null, "name", e.getKey());
                    ser.text((String) v);
                    ser.endTag(null, "string");
                } else if (v instanceof Boolean) {
                    ser.startTag(null, "boolean");
                    ser.attribute(null, "name", e.getKey());
                    ser.attribute(null, "value", String.valueOf(v));
                    ser.endTag(null, "boolean");
                } else if (v instanceof Integer) {
                    ser.startTag(null, "int");
                    ser.attribute(null, "name", e.getKey());
                    ser.attribute(null, "value", String.valueOf(v));
                    ser.endTag(null, "int");
                } else if (v instanceof Long) {
                    ser.startTag(null, "long");
                    ser.attribute(null, "name", e.getKey());
                    ser.attribute(null, "value", String.valueOf(v));
                    ser.endTag(null, "long");
                } else if (v instanceof Float) {
                    ser.startTag(null, "float");
                    ser.attribute(null, "name", e.getKey());
                    ser.attribute(null, "value", String.valueOf(v));
                    ser.endTag(null, "float");
                }
            }
            ser.endTag(null, "map");
            ser.endDocument();
            return sw.toString();
        } catch (Throwable t) {
            LogWriter.log(TAG, "serialize fail: " + t.getMessage());
            return null;
        }
    }

    private void writeToRoot(String xml) {
        if (xml == null) return;
        try {
            FileWriter fw = new FileWriter(mTmpFile);
            fw.write(xml);
            fw.close();

            String uidGid = getUidGid();
            String target = MAIN_DATA_DIR + "/shared_prefs/" + mName + ".xml";
            StringBuilder cmd = new StringBuilder()
                .append("cp ").append(mTmpFile.getAbsolutePath()).append(" ").append(target).append(".tmp")
                .append(" && mv ").append(target).append(".tmp ").append(target)
                .append(" && chmod 660 ").append(target);
            if (uidGid != null) {
                cmd.append(" && chown ").append(uidGid).append(" ").append(target);
            }
            execRoot(cmd.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "write fail(" + mName + "): " + t.getMessage());
        }
    }

    private static String getUidGid() {
        if (sUidGid != null) return sUidGid;
        try {
            String out = execRoot("stat -c %u:%g " + MAIN_DATA_DIR);
            if (out != null) {
                out = out.trim();
                if (out.matches("\\d+:\\d+")) sUidGid = out;
            }
        } catch (Throwable ignored) {}
        return sUidGid;
    }

    // ================= root 执行 =================

    private static final String[] SU_CANDIDATES = {
        "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/sbin/su", "su"
    };

    private static String execRoot(String cmd) {
        for (String su : SU_CANDIDATES) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
                String out = readFully(p.getInputStream());
                String err = readFully(p.getErrorStream());
                int code = p.waitFor();
                if (code == 0) {
                    LogWriter.log(TAG, "execRoot OK via " + su);
                    return out;
                }
                LogWriter.log(TAG, "execRoot FAIL via " + su + " code=" + code
                    + " err=" + err.trim());
            } catch (Throwable t) {
                LogWriter.log(TAG, "execRoot EXCEPTION via " + su + ": "
                    + t.getClass().getSimpleName() + ":" + t.getMessage() + " cmd=" + cmd);
            } finally {
                if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
            }
        }
        return null;
    }

    private static String readFully(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), "UTF-8");
    }
}
