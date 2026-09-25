package com.leshao.v3.ui.widgets;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import com.leshao.v3.ui.AppColors;

/**
 * AI 助手功能图标(自绘, 不依赖 appcompat/Material 资源)。
 *
 * <p>样式对齐「彩色圆底」方案: 同色系浅色圆角方底 + 主题色实心字形。
 * 字形在 24x24 逻辑网格内定义, 绘制时按控件尺寸等比缩放并居中。</p>
 */
public class AiIconDrawable extends Drawable {

    public static final int G_SPARK = 0;
    public static final int G_CLOUD = 1;
    public static final int G_CHIP = 2;
    public static final int G_USER = 3;
    public static final int G_BELL = 4;
    public static final int G_VOICE = 5;
    public static final int G_EQ = 6;
    public static final int G_DB = 7;
    public static final int G_LAYERS = 8;
    public static final int G_SLIDERS = 9;

    // v1067 葡萄气泡：图标色板统一到葡萄/粉/薰衣草家族
    private static final int[] PALETTE = {
            0xFF8B5CF6, // spark 总开关
            0xFF9F7BFF, // cloud 服务商
            0xFFC026D3, // chip 模型/核心
            0xFFB56BF0, // user 人设/会话
            0xFF9333EA, // bell 唤醒/@
            0xFFDB2777, // voice 语音
            0xFFD946EF, // eq 音色
            0xFF7C3AED, // db 记忆
            0xFFFF8FC7, // layers 模板
            0xFFA78BFA, // sliders 独立配置
    };

    private final int mGlyph;
    private final int mColor;
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mFillPath = new Path();
    private final Path mStrokePath = new Path();
    private final RectF mTmp = new RectF();
    private final RectF mBox = new RectF();

    private AiIconDrawable(int glyph, int color) {
        mGlyph = glyph;
        mColor = color;
        mBgPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(color);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setColor(color);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        buildGlyph();
    }

    /** 按内置调色板取色。 */
    public static AiIconDrawable of(int glyph) {
        int c = (glyph >= 0 && glyph < PALETTE.length) ? PALETTE[glyph] : PALETTE[0];
        return new AiIconDrawable(glyph, c);
    }

    /** 指定颜色。 */
    public static AiIconDrawable of(int glyph, int color) {
        return new AiIconDrawable(glyph, color);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float w = b.width();
        float h = b.height();
        float box = Math.min(w, h);
        float radius = box * 0.28f;
        int base = AppColors.isDarkMode() ? AppColors.surfaceContainerHigh() : Color.WHITE;
        mBgPaint.setColor(blend(mColor, base, 0.16f));
        mBox.set(b.left, b.top, b.right, b.bottom);
        canvas.drawRoundRect(mBox, radius, radius, mBgPaint);

        float scale = (box * 0.56f) / 24f;
        canvas.save();
        canvas.translate(b.left + (w - 24f * scale) / 2f, b.top + (h - 24f * scale) / 2f);
        canvas.scale(scale, scale);
        mStrokePaint.setStrokeWidth(1.9f);
        if (!mFillPath.isEmpty()) canvas.drawPath(mFillPath, mFillPaint);
        if (!mStrokePath.isEmpty()) canvas.drawPath(mStrokePath, mStrokePaint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        mBgPaint.setAlpha(alpha);
        mFillPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mBgPaint.setColorFilter(cf);
        mFillPaint.setColorFilter(cf);
        mStrokePaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private void buildGlyph() {
        mFillPath.setFillType(Path.FillType.WINDING);
        switch (mGlyph) {
            case G_SPARK: {
                mFillPath.moveTo(12f, 2.4f);
                mFillPath.lineTo(13.95f, 8.05f);
                mFillPath.lineTo(19.6f, 10f);
                mFillPath.lineTo(13.95f, 11.95f);
                mFillPath.lineTo(12f, 17.6f);
                mFillPath.lineTo(10.05f, 11.95f);
                mFillPath.lineTo(4.4f, 10f);
                mFillPath.lineTo(10.05f, 8.05f);
                mFillPath.close();
                mFillPath.moveTo(18.6f, 15.3f);
                mFillPath.lineTo(19.35f, 17.35f);
                mFillPath.lineTo(21.4f, 18.1f);
                mFillPath.lineTo(19.35f, 18.85f);
                mFillPath.lineTo(18.6f, 20.9f);
                mFillPath.lineTo(17.85f, 18.85f);
                mFillPath.lineTo(15.8f, 18.1f);
                mFillPath.lineTo(17.85f, 17.35f);
                mFillPath.close();
                break;
            }
            case G_CLOUD: {
                mFillPath.addCircle(9.5f, 13.2f, 4.3f, Path.Direction.CW);
                mFillPath.addCircle(14.6f, 11.6f, 5.2f, Path.Direction.CW);
                mFillPath.addCircle(18.1f, 13.8f, 3.7f, Path.Direction.CW);
                mTmp.set(6.8f, 12.8f, 18.5f, 17.6f);
                mFillPath.addRoundRect(mTmp, 2.4f, 2.4f, Path.Direction.CW);
                break;
            }
            case G_CHIP: {
                // 外框 CW + 内框 CCW = 中空; 引脚独立 CW 与外框重叠非零仍填充
                mTmp.set(5.5f, 5.5f, 18.5f, 18.5f);
                mFillPath.addRoundRect(mTmp, 2.2f, 2.2f, Path.Direction.CW);
                mTmp.set(9.4f, 9.4f, 14.6f, 14.6f);
                mFillPath.addRoundRect(mTmp, 1.0f, 1.0f, Path.Direction.CCW);
                addPin(10f, 2.6f, 11.3f, 5.6f);
                addPin(12.7f, 2.6f, 14f, 5.6f);
                addPin(10f, 18.4f, 11.3f, 21.4f);
                addPin(12.7f, 18.4f, 14f, 21.4f);
                addPin(2.6f, 10f, 5.6f, 11.3f);
                addPin(2.6f, 12.7f, 5.6f, 14f);
                addPin(18.4f, 10f, 21.4f, 11.3f);
                addPin(18.4f, 12.7f, 21.4f, 14f);
                break;
            }
            case G_USER: {
                mFillPath.addCircle(12f, 7.9f, 3.5f, Path.Direction.CW);
                mTmp.set(5.1f, 13.2f, 18.9f, 19.3f);
                mFillPath.addRoundRect(mTmp, 6.9f, 6.9f, Path.Direction.CW);
                break;
            }
            case G_BELL: {
                mFillPath.addCircle(12f, 8.4f, 4f, Path.Direction.CW);
                mFillPath.moveTo(8f, 8.4f);
                mFillPath.lineTo(8f, 11.8f);
                mFillPath.lineTo(6.4f, 14.2f);
                mFillPath.lineTo(17.6f, 14.2f);
                mFillPath.lineTo(16f, 11.8f);
                mFillPath.lineTo(16f, 8.4f);
                mFillPath.close();
                mTmp.set(9.9f, 15.9f, 14.1f, 18f);
                mFillPath.addRoundRect(mTmp, 1.05f, 1.05f, Path.Direction.CW);
                break;
            }
            case G_VOICE: {
                mFillPath.moveTo(5f, 9.4f);
                mFillPath.lineTo(8.2f, 9.4f);
                mFillPath.lineTo(13f, 5.2f);
                mFillPath.lineTo(13f, 18.8f);
                mFillPath.lineTo(8.2f, 14.6f);
                mFillPath.lineTo(5f, 14.6f);
                mFillPath.close();
                addArc(mStrokePath, 13f, 8.4f, 19.6f, 15.6f, -52f, 104f);
                addArc(mStrokePath, 12f, 6f, 22.6f, 18f, -50f, 100f);
                break;
            }
            case G_EQ: {
                addBar(5f, 10f, 7.3f, 19f);
                addBar(9f, 4f, 11.3f, 19f);
                addBar(13f, 12f, 15.3f, 19f);
                addBar(17f, 7f, 19.3f, 19f);
                break;
            }
            case G_DB: {
                mTmp.set(5.4f, 5.7f, 18.6f, 18.3f);
                mFillPath.addRoundRect(mTmp, 6.6f, 6.6f, Path.Direction.CW);
                mTmp.set(5.4f, 3f, 18.6f, 8.4f);
                mFillPath.addOval(mTmp, Path.Direction.CW);
                addArc(mStrokePath, 5.4f, 10.6f, 18.6f, 13.6f, 0f, 180f);
                break;
            }
            case G_LAYERS: {
                mFillPath.moveTo(12f, 3f);
                mFillPath.lineTo(20.5f, 7.4f);
                mFillPath.lineTo(12f, 11.8f);
                mFillPath.lineTo(3.5f, 7.4f);
                mFillPath.close();
                mStrokePath.moveTo(3.5f, 11.9f);
                mStrokePath.lineTo(12f, 16.3f);
                mStrokePath.lineTo(20.5f, 11.9f);
                mStrokePath.moveTo(3.5f, 15.6f);
                mStrokePath.lineTo(12f, 20f);
                mStrokePath.lineTo(20.5f, 15.6f);
                break;
            }
            case G_SLIDERS:
            default: {
                mStrokePath.moveTo(3.4f, 7.1f);
                mStrokePath.lineTo(11.8f, 7.1f);
                mStrokePath.moveTo(18.2f, 7.1f);
                mStrokePath.lineTo(20.6f, 7.1f);
                mFillPath.addCircle(15f, 7.1f, 2.9f, Path.Direction.CW);
                mStrokePath.moveTo(3.4f, 12.1f);
                mStrokePath.lineTo(6.4f, 12.1f);
                mStrokePath.moveTo(12.6f, 12.1f);
                mStrokePath.lineTo(20.6f, 12.1f);
                mFillPath.addCircle(9.5f, 12.1f, 2.9f, Path.Direction.CW);
                mStrokePath.moveTo(3.4f, 17.1f);
                mStrokePath.lineTo(9.9f, 17.1f);
                mStrokePath.moveTo(16.1f, 17.1f);
                mStrokePath.lineTo(20.6f, 17.1f);
                mFillPath.addCircle(13f, 17.1f, 2.9f, Path.Direction.CW);
                break;
            }
        }
    }

    private void addPin(float l, float t, float r, float b) {
        mTmp.set(l, t, r, b);
        mFillPath.addRoundRect(mTmp, 0.65f, 0.65f, Path.Direction.CW);
    }

    private void addBar(float l, float t, float r, float b) {
        mTmp.set(l, t, r, b);
        mFillPath.addRoundRect(mTmp, 1.15f, 1.15f, Path.Direction.CW);
    }

    private void addArc(Path p, float l, float t, float r, float b, float start, float sweep) {
        mTmp.set(l, t, r, b);
        p.addArc(mTmp, start, sweep);
    }

    private static int blend(int fg, int bg, float a) {
        int fr = (fg >> 16) & 0xFF;
        int fgc = (fg >> 8) & 0xFF;
        int fb = fg & 0xFF;
        int br = (bg >> 16) & 0xFF;
        int bgc = (bg >> 8) & 0xFF;
        int bb = bg & 0xFF;
        int r = Math.round(fr * a + br * (1 - a));
        int g = Math.round(fgc * a + bgc * (1 - a));
        int bl = Math.round(fb * a + bb * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
