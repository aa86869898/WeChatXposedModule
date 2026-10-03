package com.leshao.v3.hook;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;

import com.leshao.v3.LogWriter;

/**
 * 自定义气泡 Drawable 工厂（ChatBubbleHook 的辅助类）。
 *
 * <p>文本气泡承载视图是 {@code com.tencent.mm.ui.widget.MMNeat7extView}，其背景需可随内容拉伸。
 * 任意位图无法在 Java 层安全构造 native 9-patch chunk（device 格式含指针字段），因此：</p>
 * <ul>
 *   <li>若用户选择的是真正的 *.9.png，交给框架 {@link BitmapFactory} 解码，可得 device 格式的
 *       nine-patch chunk，走 {@link NinePatchDrawable}，保留原始拉伸与内容边距语义；</li>
 *   <li>否则使用自实现的 {@link NineSliceDrawable}，按四角固定、四边与中心拉伸的 9-slice 规则
 *       绘制，得到与 9-patch 等价的拉伸效果，且完全规避 native chunk 构造风险。</li>
 * </ul>
 */
final class BubbleDrawableFactory {

    private BubbleDrawableFactory() {}

    static Drawable build(Context ctx, String path) {
        if (ctx == null || path == null || path.isEmpty()) return null;
        Resources res = ctx.getResources();

        if (path.toLowerCase().endsWith(".9.png")) {
            // 框架解码 *.9.png 时会剥离 1px 边框并生成 device 格式 chunk，这是唯一安全的 9-patch 来源。
            Drawable np = buildFromNinePatchFile(res, path);
            if (np != null) return np;
            LogWriter.log("Bubble", "9.png framework decode failed, fallback nine-slice");
        }

        Bitmap bmp = decode(path);
        if (bmp == null) return null;
        return new NineSliceDrawable(res, bmp, Math.max(1, Math.min(bmp.getWidth(), bmp.getHeight()) / 6));
    }

    /** 用框架解码 *.9.png 得到真正的 NinePatchDrawable；失败返回 null。 */
    private static Drawable buildFromNinePatchFile(Resources res, String path) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) return null;
            byte[] chunk = bmp.getNinePatchChunk();
            if (chunk == null || !NinePatch.isNinePatchChunk(chunk)) {
                bmp.recycle();
                return null;
            }
            NinePatch np = new NinePatch(bmp, chunk, null);
            return new NinePatchDrawable(res, np);
        } catch (Throwable t) {
            LogWriter.log("Bubble", "ninepatch file decode err: " + t.getMessage());
            return null;
        }
    }

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int maxEdge = 1024;
            int sample = 1;
            while (sample > 0 && (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge)) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return null;
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = bmp.copy(Bitmap.Config.ARGB_8888, false);
                if (converted != null) {
                    bmp.recycle();
                    return converted;
                }
            }
            return bmp;
        } catch (Throwable t) {
            LogWriter.log("Bubble", "decode failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * 自实现 9-slice Drawable：把源位图按“四角 1:1、四边单轴拉伸、中心双轴拉伸”绘制到目标区域。
     *
     * <p>slice 尺寸取图片短边的 1/6（至少 1px），对常见圆角气泡可正确保留圆角而拉伸中间。
     * 纯 Java 绘制，不涉及 native nine-patch chunk，无崩溃风险。</p>
     */
    static final class NineSliceDrawable extends Drawable {
        private final Bitmap src;
        private final int slice;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        NineSliceDrawable(Resources res, Bitmap src, int slice) {
            this.src = src;
            this.slice = Math.max(1, Math.min(slice, Math.min(src.getWidth(), src.getHeight()) / 2));
            this.paint.setAntiAlias(true);
            this.paint.setFilterBitmap(true);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || src.isRecycled()) return;
            int w = src.getWidth();
            int h = src.getHeight();
            int s = slice;
            if (w <= 2 * s || h <= 2 * s) {
                canvas.drawBitmap(src, null, b, paint);
                return;
            }
            int[] xs = {0, s, w - s, w};
            int[] ys = {0, s, h - s, h};
            int[] dx = {b.left, b.left + s, b.right - s, b.right};
            int[] dy = {b.top, b.top + s, b.bottom - s, b.bottom};
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (i == 1 && j == 1) continue; // 中心单独处理
                    src(dx[i], dy[j], dx[i + 1], dy[j + 1],
                            xs[i], ys[j], xs[i + 1], ys[j + 1], canvas);
                }
            }
            // 中心区域：双向拉伸
            src(dx[1], dy[1], dx[2], dy[2], xs[1], ys[1], xs[2], ys[2], canvas);
        }

        private void src(int dl, int dt, int dr, int db,
                         int sl, int st, int sr, int sb, Canvas canvas) {
            if (dr <= dl || db <= dt || sr <= sl || sb <= st) return;
            dst.set(dl, dt, dr, db);
            Rect sRect = new Rect(sl, st, sr, sb);
            canvas.drawBitmap(src, sRect, dst, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return src.getWidth(); }
        @Override public int getIntrinsicHeight() { return src.getHeight(); }
        @Override public Drawable.ConstantState getConstantState() {
            return new ConstantState() {
                @Override public Drawable newDrawable() { return new NineSliceDrawable(null, src, slice); }
                @Override public Drawable newDrawable(Resources res) { return new NineSliceDrawable(res, src, slice); }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }
    }
}
