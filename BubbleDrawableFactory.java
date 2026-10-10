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

    /** v3.0.270：按路径缓存已构建 Drawable 的 ConstantState。
     *  WxBubbleModule 对每个气泡都会调用 createCustomBubbleDrawable→build，若每次都重新
     *  decodeFile 会大量解码位图，造成列表卡顿/偶发 OOM，部分气泡来不及替换。缓存后每次
     *  仅 newDrawable() 出一份轻量新实例，且不共享同一可变实例（防 bounds 串扰）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Drawable.ConstantState> sCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    static Drawable build(Context ctx, String path) {
        if (ctx == null || path == null || path.isEmpty()) return null;
        Resources res = ctx.getResources();
        Drawable.ConstantState cached = sCache.get(path);
        if (cached != null) {
            try {
                Drawable d = cached.newDrawable(res);
                if (d != null) return d;
            } catch (Throwable t) {
                sCache.remove(path);   // 缓存损坏则重建
            }
        }
        Drawable built = buildFresh(res, path);
        if (built != null) {
            try {
                Drawable.ConstantState cs = built.getConstantState();
                if (cs != null) sCache.put(path, cs);
            } catch (Throwable ignored) {}
        }
        return built;
    }

    /** v3.0.270：清除路径缓存（换图/切主题时调用，避免旧位图长期驻留内存）。 */
    static void clearCache() {
        sCache.clear();
    }

    private static Drawable buildFresh(Resources res, String path) {
        // v3.0.270：只解码一次。若文件是编译过的 .9.png（带 tEXt chunk），直接走框架
        // NinePatchDrawable，保留精确拉伸区与内容边距；否则按未编译 .9 黑线 / 对称 slice 兜底。
        Bitmap bmp = decode(path);
        if (bmp == null) return null;

        byte[] chunk = bmp.getNinePatchChunk();
        if (chunk != null && NinePatch.isNinePatchChunk(chunk)) {
            NinePatch np = new NinePatch(bmp, chunk, null);
            return new NinePatchDrawable(res, np);
        }

        // v3.0.142b：未编译 .9.png 边缘黑线解析为 NineSliceDrawable
        NinePatchRegion region = detectNinePatchRegion(bmp);
        if (region != null) {
            LogWriter.log("Bubble", "ninepatch region detected stretchX=" + region.stretchXStart
                    + ".." + region.stretchXEnd + " stretchY=" + region.stretchYStart
                    + ".." + region.stretchYEnd
                    + " padX=" + region.padLeft + ".." + region.padRight
                    + " padY=" + region.padTop + ".." + region.padBottom
                    + " size=" + bmp.getWidth() + "x" + bmp.getHeight());
            return new NineSliceDrawable(res, bmp, region);
        }
        // v3.0.142c：无黑线可辨时回退对称 slice（短边 1/6），保底不崩
        return new NineSliceDrawable(res, bmp, Math.max(1, Math.min(bmp.getWidth(), bmp.getHeight()) / 6));
    }

    /** v3.0.259：将微信原生气泡的内容边距覆盖到自定义气泡上（仅 NineSliceDrawable 支持），
     *  避免用户图片 slice 过大导致文字内容区过窄。 */
    static void applyNativePadding(Drawable custom, Drawable nativeOrig) {
        if (custom == null || nativeOrig == null || !(custom instanceof NineSliceDrawable)) return;
        Rect r = new Rect();
        if (nativeOrig.getPadding(r)) {
            ((NineSliceDrawable) custom).overridePadding(r.left, r.top, r.right, r.bottom);
        }
    }

    

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int maxEdge = 1024;
            int sample = 1;
            while ((bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge)
                    && sample <= (1 << 16)) {
                sample *= 2;
            }
            if (sample <= 0) sample = 1 << 16;   // 防溢出为负，回退到最大采样
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return null;
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                // v3.0.270：若位图带 nine-patch chunk 则不能 copy（copy 会丢失 chunk），保持原样。
                if (bmp.getNinePatchChunk() != null) return bmp;
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
     * 从位图四条边解析 .9.png 黑线标记（未编译的 nine-patch 源图）。
     *
     * <p>nine-patch 语义（Android 规范）：</p>
     * <ul>
     *   <li><b>顶边黑线</b>：水平拉伸区，黑线区间 [start,end] 内的列会被拉伸；</li>
     *   <li><b>左边黑线</b>：垂直拉伸区，黑线区间 [start,end] 内的行会被拉伸；</li>
     *   <li><b>右边黑线</b>：内容区左/右 padding 的参考（本实现用顶/左黑线外沿兜底）；</li>
     *   <li><b>底边黑线</b>：内容区上/下 padding 的参考。</li>
     * </ul>
     *
     * <p>返回非对称的拉伸区与内容边距，供 {@link NineSliceDrawable} 使用。
     * 顶/左黑线必须存在且不与四角贯通；右/底黑线缺失时以拉伸区外沿作为内容边距兜底。
     * 完全无法判定时返回 null（调用方回退对称 slice）。</p>
     */
    private static NinePatchRegion detectNinePatchRegion(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 4 || h < 4) return null;
        try {
            // 读取四条边各一行/列像素
            int[] top = new int[w];
            int[] bottom = new int[w];
            int[] left = new int[h];
            int[] right = new int[h];
            bmp.getPixels(top, 0, w, 0, 0, w, 1);
            bmp.getPixels(bottom, 0, w, 0, h - 1, w, 1);
            bmp.getPixels(left, 0, 1, 0, 0, 1, h);
            bmp.getPixels(right, 0, 1, w - 1, 0, 1, h);

            // 黑线需要"有实体内容"支撑：黑线所在位置不应贯穿整条边（至少留 1px 角区）
            // 顶边黑线 -> 水平拉伸区
            int tStart = -1, tEnd = -1;
            for (int x = 0; x < w; x++) {
                if (isNinePatchMark(top[x])) {
                    if (tStart < 0) tStart = x;
                    tEnd = x;
                }
            }
            // 左边黑线 -> 垂直拉伸区
            int lStart = -1, lEnd = -1;
            for (int y = 0; y < h; y++) {
                if (isNinePatchMark(left[y])) {
                    if (lStart < 0) lStart = y;
                    lEnd = y;
                }
            }
            // 顶/左黑线必须有效且不贯通到角区（避免整边拉伸或空拉伸）
            boolean topValid = tStart > 0 && tEnd < w - 1 && (tEnd - tStart + 1) < w - 2;
            boolean leftValid = lStart > 0 && lEnd < h - 1 && (lEnd - lStart + 1) < h - 2;
            if (!topValid || !leftValid) return null;

            // 拉伸区外沿：左固定区 = tStart，右固定区 = w - tEnd - 1（非对称）
            int stretchXStart = tStart;
            int stretchXEnd = tEnd;
            int stretchYStart = lStart;
            int stretchYEnd = lEnd;

            // 内容边距：底边黑线 = 内容区左/右 padding；右边黑线 = 内容区上/下 padding。
            // Android 规范：底边黑线段起点=内容左padding、终点=内容右padding 外沿；
            // 右边黑线段起点=内容上padding、终点=内容下padding 外沿。
            int padLeft = stretchXStart, padRight = w - 1 - stretchXEnd;
            int padTop = stretchYStart, padBottom = h - 1 - stretchYEnd;

            // 底边黑线（若存在且不贯通）
            int bStart = -1, bEnd = -1;
            for (int x = 0; x < w; x++) {
                if (isNinePatchMark(bottom[x])) {
                    if (bStart < 0) bStart = x;
                    bEnd = x;
                }
            }
            if (bStart > 0 && bEnd < w - 1 && (bEnd - bStart + 1) < w - 2) {
                padLeft = Math.max(stretchXStart, bStart);
                padRight = Math.max(w - 1 - stretchXEnd, w - 1 - bEnd);
            }
            // 右边黑线（若存在且不贯通）
            int rStart = -1, rEnd = -1;
            for (int y = 0; y < h; y++) {
                if (isNinePatchMark(right[y])) {
                    if (rStart < 0) rStart = y;
                    rEnd = y;
                }
            }
            if (rStart > 0 && rEnd < h - 1 && (rEnd - rStart + 1) < h - 2) {
                padTop = Math.max(stretchYStart, rStart);
                padBottom = Math.max(h - 1 - stretchYEnd, h - 1 - rEnd);
            }

            // 兜底：至少保留 1px 固定区，且固定区不得超过图片一半（防整图拉伸）
            int maxFx = Math.max(1, w / 2 - 1);
            int maxFy = Math.max(1, h / 2 - 1);
            NinePatchRegion r = new NinePatchRegion();
            r.stretchXStart = Math.min(Math.max(1, stretchXStart), maxFx);
            r.stretchXEnd = Math.max(Math.min(w - 2, stretchXEnd), w - 1 - maxFx);
            r.stretchYStart = Math.min(Math.max(1, stretchYStart), maxFy);
            r.stretchYEnd = Math.max(Math.min(h - 2, stretchYEnd), h - 1 - maxFy);
            r.padLeft = Math.max(0, Math.min(padLeft, r.stretchXStart));
            r.padRight = Math.max(0, Math.min(padRight, w - 1 - r.stretchXEnd));
            r.padTop = Math.max(0, Math.min(padTop, r.stretchYStart));
            r.padBottom = Math.max(0, Math.min(padBottom, h - 1 - r.stretchYEnd));
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** nine-patch 四边黑线解析结果（全部以像素计）。 */
    static final class NinePatchRegion {
        int stretchXStart;   // 顶边黑线起点（左固定区宽）
        int stretchXEnd;     // 顶边黑线终点（右固定区由 w-1-end 推得）
        int stretchYStart;   // 左边黑线起点（上固定区高）
        int stretchYEnd;     // 左边黑线终点（下固定区由 h-1-end 推得）
        int padLeft;         // 内容左内边距
        int padRight;        // 内容右内边距
        int padTop;          // 内容上内边距
        int padBottom;       // 内容下内边距
    }

    /** .9.png 标记像素：高不透明度的近黑色像素（黑线可能被微信重编码，放宽阈值）。 */
    private static boolean isNinePatchMark(int pixel) {
        int a = (pixel >>> 24) & 0xFF;
        if (a < 0x80) return false;
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return r < 0x60 && g < 0x60 && b < 0x60;
    }

    /**
     * 自实现 9-slice Drawable：把源位图按"四角 1:1、四边单轴拉伸、中心双轴拉伸"绘制到目标区域。
     *
     * <p>slice 尺寸取图片短边的 1/6（至少 1px），对常见圆角气泡可正确保留圆角而拉伸中间。
     * 纯 Java 绘制，不涉及 native nine-patch chunk，无崩溃风险。
     * v3.0.142b：支持水平/垂直不同 slice（sliceX/sliceY），用于未编译 .9.png 黑线拉伸区。
     * v3.0.142c：支持 {@link NinePatchRegion} 非对称四边固定区与内容边距，完整还原 .9 语义。</p>
     */
    static final class NineSliceDrawable extends Drawable {
        private final Bitmap src;
        private final int left;       // 左固定区宽
        private final int top;        // 上固定区高
        private final int right;      // 右固定区宽
        private final int bottom;     // 下固定区高
        private int padLeft;
        private int padTop;
        private int padRight;
        private int padBottom;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();
        private final Rect sRect = new Rect();
        private final Drawable.ConstantState mState;

        NineSliceDrawable(Resources res, Bitmap src, int slice) {
            this(res, src, slice, slice);
        }

        NineSliceDrawable(Resources res, Bitmap src, int sliceX, int sliceY) {
            this(res, src, sliceX, sliceY, sliceX, sliceY);
        }

        NineSliceDrawable(Resources res, Bitmap src, int sliceX, int sliceY,
                          int padX, int padY) {
            this(res, src, sliceX, sliceY, sliceX, sliceY, padX, padX, padY, padY);
        }

        NineSliceDrawable(Resources res, Bitmap src, NinePatchRegion r) {
            this(res, src, r.stretchXStart, r.stretchYStart,
                    Math.max(1, src.getWidth() - 1 - r.stretchXEnd),
                    Math.max(1, src.getHeight() - 1 - r.stretchYEnd),
                    r.padLeft, r.padRight, r.padTop, r.padBottom);
        }

        NineSliceDrawable(Resources res, Bitmap src, int left, int top,
                          int right, int bottom, int padLeft, int padRight,
                          int padTop, int padBottom) {
            this.src = src;
            int maxX = Math.max(1, src.getWidth() / 2);
            int maxY = Math.max(1, src.getHeight() / 2);
            this.left = Math.max(1, Math.min(left, maxX));
            this.right = Math.max(1, Math.min(right, maxX));
            this.top = Math.max(1, Math.min(top, maxY));
            this.bottom = Math.max(1, Math.min(bottom, maxY));
            this.padLeft = Math.max(0, Math.min(padLeft, this.left));
            this.padRight = Math.max(0, Math.min(padRight, this.right));
            this.padTop = Math.max(0, Math.min(padTop, this.top));
            this.padBottom = Math.max(0, Math.min(padBottom, this.bottom));
            this.paint.setAntiAlias(true);
            this.paint.setFilterBitmap(true);
            // v3.0.270：稳定 ConstantState（同一实例），供缓存复用且保留 overridePadding 生效。
            this.mState = new ConstantState() {
                @Override public Drawable newDrawable() {
                    return new NineSliceDrawable(null, src, left, top, right, bottom,
                            padLeft, padRight, padTop, padBottom);
                }
                @Override public Drawable newDrawable(Resources res) {
                    return new NineSliceDrawable(res, src, left, top, right, bottom,
                            padLeft, padRight, padTop, padBottom);
                }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || src.isRecycled()) return;
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= left + right || h <= top + bottom) {
                canvas.drawBitmap(src, null, b, paint);
                return;
            }
            int[] xs = {0, left, w - right, w};
            int[] ys = {0, top, h - bottom, h};
            int[] dx = {b.left, b.left + left, b.right - right, b.right};
            int[] dy = {b.top, b.top + top, b.bottom - bottom, b.bottom};
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
            sRect.set(sl, st, sr, sb);
            canvas.drawBitmap(src, sRect, dst, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return src.getWidth(); }
        @Override public int getIntrinsicHeight() { return src.getHeight(); }

        /** v3.0.141：提供内容边距。微信原生气泡 NinePatchDrawable 自带 padding 指定文字
         *  内容区；此前 NineSliceDrawable 未实现 getPadding → 替换后文字贴边/错位。
         *  v3.0.142c：内容边距直接采用 .9 底/右黑线解析出的值（非对称），
         *  圆角与尾巴均被正确避让，文字不再压到气泡边缘。
         *  v3.0.259：允许外部覆盖为微信原生气泡的 padding，避免用户大图 slice 过大
         *  导致文字内容区过窄（一句话只显示两三个字就换行）。 */
        @Override public boolean getPadding(Rect padding) {
            if (padding == null) return true;
            padding.set(padLeft, padTop, padRight, padBottom);
            return true;
        }

        /** v3.0.259：用微信原生气泡的内容边距覆盖本 Drawable 的 padding。 */
        void overridePadding(int l, int t, int r, int b) {
            padLeft = Math.max(0, l);
            padTop = Math.max(0, t);
            padRight = Math.max(0, r);
            padBottom = Math.max(0, b);
        }

        @Override public Drawable.ConstantState getConstantState() {
            return mState;
        }
    }
}
