package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import com.leshao.v3.ui.AppColors;

/**
 * 在线音乐矢量图标（v30033 落地「实心渐变 + 实心节点」方案）。
 *
 * <p>所有图形在 24x24 viewBox 内描述，绘制时按视图短边等比缩放并居中；描边色统一取自
 * 模块「糖果粉 · 纯色」同系色（粉 → 深玫，暗色自动切换），{@link #setActive(boolean)}
 * 关闭时退化为 {@link AppColors#text3()} 单色（用于底部导航未选中态）。</p>
 *
 * <p>两种渲染模式：</p>
 * <ul>
 *   <li>描边模式（默认）：渐变描边，粗轮廓圆头；{@link #QUALITY}/{@link #DISC} 额外绘制
 *       实心渐变节点。</li>
 *   <li>实心模式（{@link #setSolid(boolean)} 或 PLAY/PAUSE/MORE）：渐变实心填充，
 *       用于歌曲行右侧按钮。</li>
 * </ul>
 *
 * <p>纯皮肤绘制，不承载业务语义。</p>
 */
public class MusicIconView extends View {

    public static final String FAV = "fav";
    public static final String DL = "dl";
    public static final String SEND = "send";
    public static final String QUALITY = "quality";
    public static final String ORDER_LIST = "order_list";
    public static final String ORDER_SINGLE = "order_single";
    public static final String ORDER_SHUFFLE = "order_shuffle";
    public static final String PREV = "prev";
    public static final String NEXT = "next";
    public static final String PLAY = "play";
    public static final String PAUSE = "pause";
    public static final String HOME = "home";
    public static final String DISC = "disc";
    public static final String USER = "user";
    public static final String MORE = "more";
    public static final String SEARCH = "search";

    private static final float VB = 24f;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private String name = PLAY;
    private boolean solid;
    private boolean active = true;
    private float strokeWidth = 3.0f;
    private int sizePx;

    public MusicIconView(Context c) {
        super(c);
        init();
    }

    public MusicIconView(Context c, String icon) {
        super(c);
        this.name = icon == null ? PLAY : icon;
        init();
    }

    private void init() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(strokeWidth);
        fill.setStyle(Paint.Style.FILL);
    }

    public MusicIconView setIcon(String icon) {
        this.name = icon == null ? PLAY : icon;
        invalidate();
        return this;
    }

    public String icon() {
        return name;
    }

    public MusicIconView setSolid(boolean s) {
        this.solid = s;
        invalidate();
        return this;
    }

    public MusicIconView setActive(boolean a) {
        this.active = a;
        invalidate();
        return this;
    }

    public MusicIconView setStrokeWidthDp(float w) {
        this.strokeWidth = w;
        stroke.setStrokeWidth(w);
        invalidate();
        return this;
    }

    public MusicIconView setIconSizeDp(int dp) {
        this.sizePx = dp(dp);
        requestLayout();
        invalidate();
        return this;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int def = sizePx > 0 ? sizePx : dp(26);
        int w = def + getPaddingLeft() + getPaddingRight();
        int h = def + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    private boolean isFilled() {
        if (PLAY.equals(name) || PAUSE.equals(name) || MORE.equals(name)) return true;
        return solid;
    }

    private void configurePaints() {
        if (active) {
            stroke.setShader(null);
            fill.setShader(null);
            stroke.setColor(AppColors.primary());
            fill.setColor(AppColors.primary());
        } else {
            int c = AppColors.text3();
            stroke.setShader(null);
            fill.setShader(null);
            stroke.setColor(c);
            fill.setColor(c);
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) / VB;
        configurePaints();
        cv.save();
        cv.translate(getPaddingLeft() + (w - VB * s) / 2f, getPaddingTop() + (h - VB * s) / 2f);
        cv.scale(s, s);

        path.reset();
        buildPath(path);
        cv.drawPath(path, isFilled() ? fill : stroke);

        if (!isFilled()) drawNodes(cv);

        cv.restore();
    }

    /** 仅描边模式下的实心节点（音质推子旋钮、碟心）。 */
    private void drawNodes(Canvas cv) {
        if (QUALITY.equals(name)) {
            cv.drawCircle(9.2f, 8f, 2.5f, fill);
            cv.drawCircle(14.8f, 16f, 2.5f, fill);
        } else if (DISC.equals(name)) {
            cv.drawCircle(12f, 12f, 2.3f, fill);
        }
    }

    private void buildPath(Path p) {
        switch (name) {
            case FAV:
                p.moveTo(12f, 20f);
                p.cubicTo(12f, 20f, 3.8f, 14.8f, 3.8f, 9.8f);
                p.cubicTo(3.8f, 6.6f, 6.2f, 4.6f, 8.7f, 4.6f);
                p.cubicTo(10.4f, 4.6f, 11.6f, 5.5f, 12f, 6.4f);
                p.cubicTo(12.4f, 5.5f, 13.6f, 4.6f, 15.3f, 4.6f);
                p.cubicTo(17.8f, 4.6f, 20.2f, 6.6f, 20.2f, 9.8f);
                p.cubicTo(20.2f, 14.8f, 12f, 20f, 12f, 20f);
                p.close();
                break;
            case DL:
                if (isFilled()) {
                    p.moveTo(10.8f, 3.4f);
                    p.lineTo(13.2f, 3.4f);
                    p.lineTo(13.2f, 9.6f);
                    p.lineTo(15.5f, 9.6f);
                    p.lineTo(12f, 13.7f);
                    p.lineTo(8.5f, 9.6f);
                    p.lineTo(10.8f, 9.6f);
                    p.close();
                    p.addRoundRect(new RectF(5f, 16.8f, 19f, 19.6f), 1.35f, 1.35f, Path.Direction.CW);
                } else {
                    p.moveTo(12f, 3.8f);
                    p.lineTo(12f, 14f);
                    p.moveTo(7.5f, 9.6f);
                    p.lineTo(12f, 14.1f);
                    p.lineTo(16.5f, 9.6f);
                    p.moveTo(5.1f, 19.5f);
                    p.lineTo(18.9f, 19.5f);
                }
                break;
            case SEND:
                if (isFilled()) {
                    p.moveTo(2.6f, 20.5f);
                    p.lineTo(21.4f, 12f);
                    p.lineTo(2.6f, 3.5f);
                    p.lineTo(2.55f, 10.1f);
                    p.lineTo(16.2f, 12f);
                    p.lineTo(2.55f, 13.9f);
                    p.close();
                } else {
                    p.moveTo(20.5f, 4.1f);
                    p.lineTo(3.9f, 11.2f);
                    p.lineTo(10.5f, 13.3f);
                    p.lineTo(12.7f, 19.9f);
                    p.close();
                    p.moveTo(10.5f, 13.3f);
                    p.lineTo(20.5f, 4.1f);
                }
                break;
            case QUALITY:
                p.moveTo(4.5f, 8f);
                p.lineTo(19.5f, 8f);
                p.moveTo(4.5f, 16f);
                p.lineTo(19.5f, 16f);
                break;
            case ORDER_LIST:
                orderLoop(p);
                break;
            case ORDER_SINGLE:
                orderLoop(p);
                p.moveTo(11.2f, 10.6f);
                p.lineTo(12.6f, 9.7f);
                p.lineTo(12.6f, 14.4f);
                break;
            case ORDER_SHUFFLE:
                p.moveTo(3.5f, 6.5f);
                p.lineTo(7f, 6.5f);
                p.lineTo(17f, 17.5f);
                p.lineTo(20.5f, 17.5f);
                p.moveTo(17.6f, 15.2f);
                p.lineTo(20.9f, 17.5f);
                p.lineTo(17.6f, 19.8f);
                p.moveTo(3.5f, 17.5f);
                p.lineTo(7f, 17.5f);
                p.lineTo(17f, 6.5f);
                p.lineTo(20.5f, 6.5f);
                p.moveTo(17.6f, 4.2f);
                p.lineTo(20.9f, 6.5f);
                p.lineTo(17.6f, 8.8f);
                break;
            case PREV:
                p.moveTo(6f, 5.6f);
                p.lineTo(6f, 18.4f);
                p.moveTo(18f, 5.6f);
                p.lineTo(8.6f, 12f);
                p.lineTo(18f, 18.4f);
                p.close();
                break;
            case NEXT:
                p.moveTo(18f, 5.6f);
                p.lineTo(18f, 18.4f);
                p.moveTo(6f, 5.6f);
                p.lineTo(15.4f, 12f);
                p.lineTo(6f, 18.4f);
                p.close();
                break;
            case PLAY:
                p.moveTo(8.6f, 5.7f);
                p.cubicTo(8.6f, 4.68f, 9.73f, 4.08f, 10.56f, 4.67f);
                p.lineTo(18.76f, 10.97f);
                p.cubicTo(19.44f, 11.49f, 19.44f, 12.51f, 18.76f, 13.03f);
                p.lineTo(10.56f, 19.33f);
                p.cubicTo(9.73f, 19.92f, 8.6f, 19.32f, 8.6f, 18.3f);
                p.close();
                break;
            case PAUSE:
                p.addRoundRect(new RectF(7f, 5f, 10.2f, 19f), 1.6f, 1.6f, Path.Direction.CW);
                p.addRoundRect(new RectF(13.8f, 5f, 17f, 19f), 1.6f, 1.6f, Path.Direction.CW);
                break;
            case HOME:
                p.moveTo(4f, 11f);
                p.lineTo(12f, 4.1f);
                p.lineTo(20f, 11f);
                p.moveTo(6.2f, 9.6f);
                p.lineTo(6.2f, 19f);
                p.cubicTo(6.2f, 19.55f, 6.65f, 20f, 7.2f, 20f);
                p.lineTo(16.8f, 20f);
                p.cubicTo(17.35f, 20f, 17.8f, 19.55f, 17.8f, 19f);
                p.lineTo(17.8f, 9.6f);
                break;
            case DISC:
                p.addOval(new RectF(3.8f, 3.8f, 20.2f, 20.2f), Path.Direction.CW);
                break;
            case USER:
                p.addOval(new RectF(8.1f, 4.4f, 15.9f, 12.2f), Path.Direction.CW);
                p.moveTo(4.8f, 19.9f);
                p.cubicTo(4.8f, 16.3f, 8f, 14.3f, 12f, 14.3f);
                p.cubicTo(16f, 14.3f, 19.2f, 16.3f, 19.2f, 19.9f);
                break;
            case MORE:
                p.addOval(new RectF(4f, 10f, 8f, 14f), Path.Direction.CW);
                p.addOval(new RectF(10f, 10f, 14f, 14f), Path.Direction.CW);
                p.addOval(new RectF(16f, 10f, 20f, 14f), Path.Direction.CW);
                break;
            case SEARCH:
                p.addOval(new RectF(4.7f, 4.7f, 16.9f, 16.9f), Path.Direction.CW);
                p.moveTo(15.4f, 15.4f);
                p.lineTo(20f, 20f);
                break;
            default:
                break;
        }
    }

    /** 两条水平方向箭头组成的循环回路（列表循环 / 单曲循环共用底图）。 */
    private void orderLoop(Path p) {
        p.moveTo(4f, 10f);
        p.cubicTo(4f, 6.8f, 6.5f, 5.4f, 8.6f, 5.4f);
        p.lineTo(17f, 5.4f);
        p.moveTo(14.6f, 2.9f);
        p.lineTo(17.7f, 5.4f);
        p.lineTo(14.6f, 7.9f);
        p.moveTo(20f, 14f);
        p.cubicTo(20f, 17.2f, 17.5f, 18.6f, 15.4f, 18.6f);
        p.lineTo(7f, 18.6f);
        p.moveTo(9.4f, 21.1f);
        p.lineTo(6.3f, 18.6f);
        p.lineTo(9.4f, 16.1f);
    }
}
