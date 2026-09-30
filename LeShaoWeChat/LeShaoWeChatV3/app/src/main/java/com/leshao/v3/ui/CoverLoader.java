package com.leshao.v3.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面图加载器：内存 LruCache + 磁盘缓存 + 网络下载，异步回调切回主线程。
 *
 * <p>仅用于展示型图片（在线音乐封面等），不做圆角裁剪，由调用方用 ImageView
 * 的缩放/父容器 outline 处理。资源受限场景下也不会无限占内存。</p>
 */
public final class CoverLoader {

    private static final String TAG = "CoverLoader";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13)";
    private static final int DEFAULT_SIZE = 512;

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 同一 URL 正在下载时的等待者；下载完成后统一回调，避免后续请求拿不到图。 */
    private static final Map<String, List<BitmapCallback>> PENDING =
            Collections.synchronizedMap(new HashMap<String, List<BitmapCallback>>());

    private static volatile LruCache<String, Bitmap> sCache;

    private CoverLoader() {}

    public interface BitmapCallback {
        void onBitmap(Bitmap bm);
    }

    private static LruCache<String, Bitmap> cache() {
        LruCache<String, Bitmap> c = sCache;
        if (c == null) {
            synchronized (CoverLoader.class) {
                c = sCache;
                if (c == null) {
                    int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
                    c = new LruCache<String, Bitmap>(maxKb) {
                        @Override
                        protected int sizeOf(String key, Bitmap value) {
                            return value.getByteCount() / 1024;
                        }
                    };
                    sCache = c;
                }
            }
        }
        return c;
    }

    /** 加载并绑定到 ImageView；内部用 tag 防止复用错位。 */
    public static void load(String url, ImageView iv) {
        if (iv == null) return;
        if (url == null || url.isEmpty()) {
            iv.setTag("");
            iv.setImageDrawable(null);
            return;
        }
        iv.setTag(url);
        Bitmap hit = cache().get(url);
        if (hit != null && !hit.isRecycled()) {
            iv.setImageBitmap(hit);
            return;
        }
        int w = iv.getWidth();
        loadBitmap(url, w > 0 ? w : 320, bm -> {
            if (bm != null && !bm.isRecycled() && url.equals(iv.getTag())) {
                iv.setImageBitmap(bm);
            }
        });
    }

    /** 加载指定尺寸的位图（max*2 像素解码上限），主线程回调。 */
    public static void loadBitmap(final String url, final int sizePx, final BitmapCallback cb) {
        if (url == null || url.isEmpty()) {
            if (cb != null) cb.onBitmap(null);
            return;
        }
        final LruCache<String, Bitmap> c = cache();
        Bitmap hit = c.get(url);
        if (hit != null && !hit.isRecycled()) {
            if (cb != null) cb.onBitmap(hit);
            return;
        }
        final int target = sizePx > 0 ? sizePx : DEFAULT_SIZE;
        boolean owner;
        synchronized (PENDING) {
            List<BitmapCallback> waiters = PENDING.get(url);
            owner = waiters == null;
            if (owner) {
                waiters = new ArrayList<>();
                PENDING.put(url, waiters);
            }
            if (cb != null) waiters.add(cb);
        }
        if (!owner) return;
        IO.execute(() -> {
            Bitmap bm = null;
            try {
                bm = loadSync(url, target);
            } catch (Throwable t) {
                LogWriter.log(TAG, "load fail " + url + ": " + t.getMessage());
            }
            final Bitmap out = bm;
            if (out != null && !out.isRecycled()) c.put(url, out);
            List<BitmapCallback> waiters;
            synchronized (PENDING) {
                waiters = PENDING.remove(url);
            }
            if (waiters != null) {
                for (final BitmapCallback w : waiters) {
                    MAIN.post(() -> w.onBitmap(out));
                }
            }
        });
    }

    private static Bitmap loadSync(String url, int target) throws Exception {
        File disk = diskFile(url);
        if (disk != null && disk.isFile() && disk.length() > 0) {
            Bitmap b = decodeFile(disk, target);
            if (b != null) return b;
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                LogWriter.log(TAG, "http " + code + " " + url);
                return null;
            }
            is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            if (disk != null) {
                try {
                    File p = disk.getParentFile();
                    if (p != null && !p.exists()) p.mkdirs();
                    FileOutputStream fo = new FileOutputStream(disk);
                    fo.write(data);
                    fo.close();
                    pruneDisk(p);
                } catch (Throwable ignored) {}
            }
            Bitmap decoded = decodeBytes(data, target);
            LogWriter.log(TAG, "ok " + data.length + "B -> "
                    + (decoded == null ? "null" : decoded.getWidth() + "x" + decoded.getHeight())
                    + " " + url);
            return decoded;
        } finally {
            if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    private static Bitmap decodeBytes(byte[] data, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sampleFor(o.outWidth, o.outHeight, target);
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    private static Bitmap decodeFile(File f, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sampleFor(o.outWidth, o.outHeight, target);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    private static int sampleFor(int w, int h, int target) {
        int sample = 1;
        int cap = target * 2;
        while (w / sample > cap || h / sample > cap) sample *= 2;
        return sample;
    }

    private static File diskFile(String url) {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return null;
            File dir = ctx.getExternalFilesDir("covers");
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, md5(url) + ".img");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 磁盘封面缓存上限; 超出按 lastModified 升序淘汰最旧。 */
    private static final int MAX_DISK_FILES = 200;

    private static void pruneDisk(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_DISK_FILES) return;
            java.util.Arrays.sort(files, (a, b) ->
                    Long.compare(b.lastModified(), a.lastModified()));
            for (int i = MAX_DISK_FILES; i < files.length; i++) {
                try { files[i].delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
