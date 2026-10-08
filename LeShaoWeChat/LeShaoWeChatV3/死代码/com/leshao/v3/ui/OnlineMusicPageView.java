package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.music.DianGeService;
import com.leshao.v3.music.KuwoMusicApi;
import com.leshao.v3.music.OnlineMusicPrefs;
import com.leshao.v3.ui.widgets.DiscView;
import com.leshao.v3.ui.widgets.EqBarsView;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernTopBar;
import com.leshao.v3.ui.widgets.MusicIconView;
import com.leshao.v3.ui.widgets.SegmentedControl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 「在线音乐」功能页（原型第一套 · 流光卡片）。
 *
 * <p>底部导航固定为「首页 / 播放 / 我的」，导航上方为常驻「正在播放」条
 * （显示歌曲 + 播放/暂停 + 下一首），点击该条进入播放器页；播放器页保留底部
 * 导航栏，仅隐藏冗余的「正在播放」条。</p>
 *
 * <p>首页顶部分类：搜索 / 榜单 / 歌单 / 导入；搜索页内再分 歌曲 / 专辑 / 歌手 / 歌单。
 * 歌曲行提供 播放 / 下载 / 发送 图标；发送走模块联系人选择器（可多选）。</p>
 */
public final class OnlineMusicPageView {

    private OnlineMusicPageView() {}

    public static View create(Context ctx, Activity parentAct) {
        return new Controller(ctx, parentAct).build();
    }

    // ==================== 悬浮球/后台播放 外部控制入口 ====================

    /** 是否有活动播放(含暂停)。 */
    public static boolean hasPlayback() {
        return Controller.player != null || Controller.current != null;
    }

    public static boolean playbackPaused() {
        return Controller.paused || Controller.player == null;
    }

    public static String playbackTitle() {
        KuwoMusicApi.Song s = Controller.current;
        return s == null || s.title == null ? "" : s.title;
    }

    public static void togglePlayback() {
        Controller c = Controller.sOwner;
        if (c != null) c.togglePlay();
    }

    public static void nextPlayback() {
        Controller c = Controller.sOwner;
        if (c != null) c.next();
    }

    public static void prevPlayback() {
        Controller c = Controller.sOwner;
        if (c != null) c.prev();
    }

    public static void stopPlayback() {
        Controller c = Controller.sOwner;
        if (c != null) c.stopPlayer();
        MusicFloatBall.hide();
    }

    /** 根容器，把返回事件转交给 Controller 的内部返回栈。 */
    private static final class RootView extends LinearLayout implements SubPageActivity.BackHandler {
        private Controller controller;

        RootView(Context ctx) { super(ctx); }

        void bind(Controller c) { this.controller = c; }

        @Override
        public boolean onBack() {
            return controller != null && controller.handleBack();
        }
    }

    private interface ListRenderer<T> {
        void render(LinearLayout host, List<T> data);
    }

    private static final class Controller {

        private static final int NAV_HOME = 0;
        private static final int NAV_PLAY = 1;
        private static final int NAV_MINE = 2;

        // 首页标签（搜索改为顶部只读搜索框 + 弹窗，不再占标签位）
        private static final int HOME_TAB_CHARTS = 0;
        private static final int HOME_TAB_SHEETS = 1;
        private static final int HOME_TAB_IMPORT = 2;
        /** 首页默认标签：歌单 */
        private static final int HOME_TAB_DEFAULT = HOME_TAB_SHEETS;

        private final Context ctx;
        private final Activity act;
        private final float d;

        private LinearLayout screenHolder;
        private LinearLayout miniBar;
        private LinearLayout navBar;
        private View homeView;
        private View playView;
        private View mineView;
        private int nav = NAV_HOME;

        private final LinearLayout content;
        private int tab = HOME_TAB_DEFAULT;

        // v1105: 播放状态提升为静态, 关闭页面后由静态状态继续播放; 重新进入时接管并可继续控制
        private static MediaPlayer player;
        private static KuwoMusicApi.Song current;
        private static boolean paused;
        private static final List<KuwoMusicApi.Song> queue = new ArrayList<>();
        private static int queueIndex = -1;
        /** 当前处于前台的控制器(关闭页面后置空, 仅保留静态播放状态)。 */
        private static Controller sActive;
        /** 播放会话持有者: 关闭页面后仍用于推进后台播放/响应悬浮球控制。 */
        private static Controller sOwner;
        /** 恢复播放时待跳转的进度(ms)。 */
        private int pendingSeekMs = -1;

        /** 歌曲行右侧播放按钮引用：用于同步当前播放曲目的播放/暂停态。 */
        private final List<RowPlay> rowPlayIcons = new ArrayList<>();

        private static final class RowPlay {
            final String id;
            final MusicIconView icon;
            RowPlay(String id, MusicIconView icon) { this.id = id; this.icon = icon; }
        }

        // 正在播放条
        private TextView miniTitle;
        private TextView miniArtist;
        private MusicIconView miniPlay;
        private MusicIconView miniNext;
        private View progressFill;
        private View progressTrack;

        // 下载进度弹窗
        private AlertDialog downloadDialog;
        private FlyingProgressBar downloadBar;
        private TextView downloadPct;

        // 播放器页
        private TextView playTitle;
        private TextView playArtist;
        private FrameLayout playToggle;
        private MusicIconView playToggleIcon;
        private TextView playPos;
        private TextView playDur;
        private TextView playQueueInfo;
        private FlyingProgressBar playSeek;
        private EqBarsView playEq;
        private MusicIconView playOrderBtn;
        private DiscView playDisc;
        private View playCollectBtn;
        private ImageView miniCoverIv;
        private String playerCoverToken;
        private boolean seeking;
        // 播放顺序: 1=列表循环 2=单曲循环 3=随机播放
        private static int playMode = 1;

        // 底部导航
        private final MusicIconView[] navIcons = new MusicIconView[3];
        private final TextView[] navLabels = new TextView[3];

        // v30029: 内部返回栈 —— 详情页/播放器页逐级返回, 不再一次关闭整个弹窗
        private boolean showingDetail = false;
        /** 详情页返回目标: -1=返回「我的」; >=0=返回对应首页标签 */
        private int detailReturnTab = 0;
        /** 进入播放器页前的底部导航位置, 用于返回原页面 */
        private int playerOrigin = NAV_HOME;

        private final Handler uiHandler = new Handler(Looper.getMainLooper());
        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (sActive != Controller.this) { uiHandler.removeCallbacks(this); return; }
                updateProgress();
                if (current != null && player != null) {
                    uiHandler.postDelayed(this, 500);
                }
            }
        };

        Controller(Context ctx, Activity parentAct) {
            this.ctx = ctx;
            this.act = parentAct;
            this.d = ctx.getResources().getDisplayMetrics().density;
            this.content = new LinearLayout(ctx);
            this.content.setOrientation(LinearLayout.VERTICAL);
        }

        // ==================== 骨架 ====================

        View build() {
            RootView root = new RootView(ctx);
            root.bind(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setTag("no_wrap");

            screenHolder = new LinearLayout(ctx);
            screenHolder.setOrientation(LinearLayout.VERTICAL);
            root.addView(screenHolder, new LinearLayout.LayoutParams(-1, 0, 1f));

            root.addView(buildMiniBar());
            root.addView(buildNavBar());

            selectNav(NAV_HOME);

            sActive = this;
            sOwner = this;
            MusicFloatBall.hide();
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }
                @Override public void onViewDetachedFromWindow(View v) {
                    // v1105: 页面关闭时保存播放进度, 交由静态状态继续播放并由悬浮球控制
                    if (sActive == Controller.this) {
                        savePlaybackState();
                        sActive = null;
                    }
                    if (hasPlayback()) MusicFloatBall.show(act);
                }
            });
            adoptOrRestore();
            return root;
        }

        /** v1105: 重新进入页面时接管后台播放, 或从偏好恢复上次播放状态。 */
        private void adoptOrRestore() {
            if (player != null) {
                // 后台仍在播放：把回调重新绑定到本控制器并刷新 UI
                bindPlayerCallbacks(player);
                try { paused = !player.isPlaying(); } catch (Throwable ignored) { paused = false; }
                if (playOrderBtn != null) playOrderBtn.setIcon(orderIcon());
                refreshPlayerUi();
                updateProgress();
                syncEq();
                if (!paused && current != null) {
                    uiHandler.removeCallbacks(ticker);
                    uiHandler.post(ticker);
                }
                return;
            }
            List<KuwoMusicApi.Song> saved = OnlineMusicPrefs.playbackQueue();
            if (!saved.isEmpty()) {
                queue.clear();
                queue.addAll(saved);
                queueIndex = OnlineMusicPrefs.playbackIndex();
                playMode = OnlineMusicPrefs.playbackMode();
                if (queueIndex < 0 || queueIndex >= queue.size()) queueIndex = 0;
                current = queue.get(queueIndex);
                paused = true;
                pendingSeekMs = OnlineMusicPrefs.playbackPosition();
                if (playOrderBtn != null) playOrderBtn.setIcon(orderIcon());
                refreshPlayerUi();
                updateProgressTextFromPending();
            }
            maybeAutoRandomPlay();
        }

        /** v1105: 进入页面即随机播放(优先从已有队列/上次队列取, 空队列则拉取默认榜单)。 */
        private void maybeAutoRandomPlay() {
            if (!OnlineMusicPrefs.autoRandom()) return;
            if (!queue.isEmpty()) {
                queueIndex = new java.util.Random().nextInt(queue.size());
                playQueueAt(queueIndex);
                return;
            }
            bg(() -> {
                try {
                    List<KuwoMusicApi.ChartGroup> groups = KuwoMusicApi.chartGroups();
                    if (groups == null || groups.isEmpty()) return;
                    KuwoMusicApi.ChartGroup g = groups.get(0);
                    if (g.charts == null || g.charts.isEmpty()) return;
                    List<KuwoMusicApi.Song> songs = KuwoMusicApi.chartSongs(g.charts.get(0).id);
                    if (songs == null || songs.isEmpty()) return;
                    ui(() -> {
                        if (sActive != this) return;
                        queue.clear();
                        queue.addAll(songs);
                        queueIndex = new java.util.Random().nextInt(queue.size());
                        playQueueAt(queueIndex);
                    });
                } catch (Throwable ignored) {
                }
            });
        }

        private void selectNav(int index) {
            nav = index;
            screenHolder.removeAllViews();
            if (index == NAV_HOME) {
                if (homeView == null) homeView = buildHome();
                screenHolder.addView(homeView, new LinearLayout.LayoutParams(-1, -1));
            } else if (index == NAV_PLAY) {
                if (playView == null) playView = buildPlayer();
                screenHolder.addView(playView, new LinearLayout.LayoutParams(-1, -1));
            } else {
                if (mineView == null) mineView = buildMine();
                screenHolder.addView(mineView, new LinearLayout.LayoutParams(-1, -1));
            }
            boolean isPlayer = index == NAV_PLAY;
            // 播放器页保留底部导航栏(用户要求, 避免影响体验); 仅隐藏冗余的「正在播放」条
            miniBar.setVisibility(isPlayer ? View.GONE : View.VISIBLE);
            navBar.setVisibility(View.VISIBLE);
            updateNavSelection();
            refreshPlayerUi();
            updateProgress();
            syncEq();
        }

        // ==================== 内部返回 ====================

        /**
         * 逐级向上返回：详情页 → 首页标签 → 我的/播放器 → 首页。
         * 已在最顶层时返回 false，交回宿主关闭弹窗。
         */
        boolean handleBack() {
            if (nav == NAV_HOME && showingDetail) {
                backFromDetail();
                return true;
            }
            if (nav == NAV_PLAY) {
                backFromPlayer();
                return true;
            }
            if (nav == NAV_MINE) {
                selectNav(NAV_HOME);
                return true;
            }
            if (nav == NAV_HOME && tab != HOME_TAB_DEFAULT) {
                selectTab(HOME_TAB_DEFAULT);
                return true;
            }
            return false;
        }

        private void backFromDetail() {
            showingDetail = false;
            if (detailReturnTab < 0) {
                selectNav(NAV_MINE);
            } else {
                selectTab(detailReturnTab);
            }
        }

        private void backFromPlayer() {
            selectNav(playerOrigin == NAV_PLAY ? NAV_HOME : playerOrigin);
        }

        private View buildHome() {
            ScrollView scroll = new ScrollView(ctx);
            scroll.setFillViewport(true);

            LinearLayout page = new LinearLayout(ctx);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(12), dp(6), dp(12), dp(12));

            page.addView(searchEntry());
            page.addView(M3Page.spacer(ctx, 8));

            SegmentedControl seg = new SegmentedControl(ctx,
                    new String[]{"榜单", "歌单", "导入"}, HOME_TAB_SHEETS);
            seg.setOnSegmentChangedListener((i, label) -> selectTab(i));
            page.addView(seg);
            page.addView(M3Page.spacer(ctx, 8));
            page.addView(content);

            scroll.addView(page);
            selectTab(HOME_TAB_SHEETS);
            return scroll;
        }

        /** 首页顶部只读搜索框：点击弹出搜索窗口。 */
        private View searchEntry() {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(14), dp(11), dp(14), dp(11));
            try {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(AppColors.surfaceContainerHighest());
                bg.setCornerRadius(dp(AppColors.SHAPE_INPUT_DP));
                box.setBackground(bg);
            } catch (Throwable ignored) {}
            CandyUi.ripple(box, AppColors.SHAPE_INPUT_DP);

            MusicIconView ic = new MusicIconView(ctx, MusicIconView.SEARCH);
            ic.setIconSizeDp(18);
            ic.setStrokeWidthDp(2.4f);
            ic.setActive(false);
            box.addView(ic);

            TextView hint = new TextView(ctx);
            hint.setText("搜索歌曲 / 专辑 / 歌手 / 歌单");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            hint.setTextColor(AppColors.text3());
            hint.setSingleLine(true);
            hint.setPadding(dp(8), 0, 0, 0);
            box.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));

            box.setContentDescription("搜索");
            box.setOnClickListener(v -> showSearchWindow());
            return box;
        }

        // ==================== 底部 · 正在播放条 ====================

        private View buildMiniBar() {
            miniBar = new LinearLayout(ctx);
            miniBar.setOrientation(LinearLayout.VERTICAL);
            try {
                GradientDrawable bgd = new GradientDrawable();
                bgd.setColor(AppColors.surfaceContainerLowest());
                float r = dp(AppColors.SHAPE_LG_DP);
                bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                miniBar.setBackground(bgd);
            } catch (Throwable ignored) {}
            CandyUi.elevate(miniBar);

            // 顶部细进度线（分隔 + 进度）
            FrameLayout track = new FrameLayout(ctx);
            track.setBackgroundColor(AppColors.outlineVariant());
            progressTrack = track;
            View fill = new View(ctx);
            try {
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(AppColors.primary());
                fill.setBackground(gd);
            } catch (Throwable ignored) { fill.setBackgroundColor(AppColors.primary()); }
            fill.setLayoutParams(new FrameLayout.LayoutParams(0, dp(2)));
            progressFill = fill;
            track.addView(fill);
            miniBar.addView(track, new LinearLayout.LayoutParams(-1, dp(2)));

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(10), dp(8));
            miniBar.addView(row);

            FrameLayout miniCover = baseCover(40, 18);
            miniCoverIv = addCoverImage(miniCover);
            row.addView(miniCover);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            info.setPadding(dp(10), 0, dp(6), 0);

            miniTitle = new TextView(ctx);
            miniTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            miniTitle.setTypeface(Typeface.DEFAULT_BOLD);
            miniTitle.setTextColor(AppColors.text1());
            miniTitle.setSingleLine(true);
            miniTitle.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(miniTitle);

            miniArtist = new TextView(ctx);
            miniArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            miniArtist.setTextColor(AppColors.text2());
            miniArtist.setSingleLine(true);
            miniArtist.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(miniArtist);
            info.setOnClickListener(v -> openPlayer());
            row.addView(info);

            miniPlay = miniIcon(MusicIconView.PLAY, "播放/暂停", v -> togglePlay());
            row.addView(miniPlay);
            miniNext = miniIcon(MusicIconView.NEXT, "下一首", v -> next());
            row.addView(miniNext);

            return miniBar;
        }

        private MusicIconView miniIcon(String icon, String desc, View.OnClickListener cb) {
            MusicIconView ic = new MusicIconView(ctx, icon);
            ic.setIconSizeDp(20);
            ic.setStrokeWidthDp(2.6f);
            ic.setPadding(dp(9), dp(6), dp(9), dp(6));
            ic.setContentDescription(desc);
            ic.setOnClickListener(cb);
            return ic;
        }

        // ==================== 底部 · 导航栏 ====================

        private View buildNavBar() {
            navBar = new LinearLayout(ctx);
            navBar.setOrientation(LinearLayout.HORIZONTAL);
            navBar.setBackgroundColor(AppColors.surfaceContainer());
            CandyUi.elevate(navBar);
            String[] icons = {MusicIconView.HOME, MusicIconView.DISC, MusicIconView.USER};
            String[] labels = {"首页", "播放", "我的"};
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                LinearLayout item = new LinearLayout(ctx);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER);
                item.setPadding(0, dp(7), 0, dp(6));
                item.setOnClickListener(v -> selectNav(idx));
                CandyUi.ripple(item, 0);

                MusicIconView ic = new MusicIconView(ctx, icons[i]);
                ic.setIconSizeDp(23);
                ic.setStrokeWidthDp(2.6f);
                ic.setActive(false);
                item.addView(ic);

                TextView lb = new TextView(ctx);
                lb.setText(labels[i]);
                lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                lb.setGravity(Gravity.CENTER);
                lb.setPadding(0, dp(2), 0, 0);
                item.addView(lb);

                navIcons[i] = ic;
                navLabels[i] = lb;
                navBar.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            return navBar;
        }

        private void updateNavSelection() {
            for (int i = 0; i < 3; i++) {
                boolean on = i == nav;
                int c = on ? AppColors.primary() : AppColors.text2();
                navIcons[i].setActive(on);
                navLabels[i].setTextColor(c);
                navLabels[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            }
        }

        // ==================== 播放器页 ====================

        private View buildPlayer() {
            LinearLayout page = new LinearLayout(ctx);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setGravity(Gravity.CENTER_HORIZONTAL);
            page.setPadding(dp(22), dp(6), dp(22), dp(12));
            page.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

            TextView back = new TextView(ctx);
            back.setText("\u2190 返回");
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            back.setTextColor(AppColors.primary());
            back.setTypeface(Typeface.DEFAULT_BOLD);
            back.setPadding(dp(2), dp(4), dp(2), dp(4));
            back.setOnClickListener(v -> backFromPlayer());
            page.addView(back, new LinearLayout.LayoutParams(-1, -2));

            page.addView(spaceGrow());

            // 碟片：加深渐变 + 旋转发光，中心圆形＝封面位
            DiscView disc = new DiscView(ctx);
            playDisc = disc;
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(200), dp(200));
            dLp.gravity = Gravity.CENTER_HORIZONTAL;
            page.addView(disc, dLp);

            page.addView(spaceGrow());

            playTitle = centerText(20, AppColors.text1(), true);
            page.addView(playTitle, new LinearLayout.LayoutParams(-1, -2));
            playArtist = centerText(13, AppColors.text2(), false);
            playArtist.setPadding(0, dp(5), 0, 0);
            page.addView(playArtist, new LinearLayout.LayoutParams(-1, -2));

            page.addView(spaceGrow());

            // 旋律柱(宽 90%), 播放时显示并绑定播放器频谱
            playEq = new EqBarsView(ctx);
            playEq.setSpanRatio(0.90f);
            playEq.setVisibility(View.GONE);
            page.addView(playEq, new LinearLayout.LayoutParams(-1, dp(160)));

            page.addView(spaceGrow());

            LinearLayout prog = new LinearLayout(ctx);
            prog.setOrientation(LinearLayout.VERTICAL);
            // 进度条：与「转码中」同款的流光飞鸟进度条，支持拖动调节
            playSeek = new FlyingProgressBar(ctx);
            playSeek.setSeekable(true);
            playSeek.setOnSeekListener(new FlyingProgressBar.OnSeekListener() {
                @Override public void onSeekStart() {
                    seeking = true;
                }
                @Override public void onSeek(int percent) {
                    updateProgressText(percent);
                }
                @Override public void onSeekEnd(int percent) {
                    seeking = false;
                    if (player != null) {
                        try {
                            int dur = player.getDuration();
                            if (dur > 0) player.seekTo((int) (dur * (percent / 100f)));
                        } catch (Throwable ignored) {}
                    }
                    updateProgress();
                    savePlaybackState();
                }
            });
            prog.addView(playSeek, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout times = new LinearLayout(ctx);
            times.setOrientation(LinearLayout.HORIZONTAL);
            playPos = new TextView(ctx);
            playPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            playPos.setTextColor(AppColors.text2());
            playPos.setText("00:00");
            playPos.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            playDur = new TextView(ctx);
            playDur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            playDur.setTextColor(AppColors.text2());
            playDur.setText("00:00");
            playDur.setGravity(Gravity.END);
            times.addView(playPos);
            times.addView(playDur);
            prog.addView(times, new LinearLayout.LayoutParams(-1, -2));
            page.addView(prog, new LinearLayout.LayoutParams(-1, -2));

            page.addView(spaceGrow());

            LinearLayout frow = new LinearLayout(ctx);
            frow.setOrientation(LinearLayout.HORIZONTAL);
            playCollectBtn = fbtn(MusicIconView.FAV, "收藏", v -> toggleCollect());
            frow.addView(playCollectBtn,
                    new LinearLayout.LayoutParams(0, -2, 1f));
            frow.addView(fbtn(MusicIconView.DL, "下载", v -> {
                KuwoMusicApi.Song s = current;
                if (s == null) { M3Page.toast(ctx, "还没有在播放的歌曲"); return; }
                download(s);
            }), new LinearLayout.LayoutParams(0, -2, 1f));
            frow.addView(fbtn(MusicIconView.SEND, "发送", v -> {
                KuwoMusicApi.Song s = current;
                if (s == null) { M3Page.toast(ctx, "还没有在播放的歌曲"); return; }
                sendToTargets(s);
            }), new LinearLayout.LayoutParams(0, -2, 1f));
            frow.addView(fbtn(MusicIconView.QUALITY, "音质", v -> showQualityDialog()),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            page.addView(frow, new LinearLayout.LayoutParams(-1, -2));

            page.addView(spaceGrow());

            View transport = transportBar();
            transport.setTranslationY(dp(6));
            page.addView(transport, new LinearLayout.LayoutParams(-1, -2));

            return page;
        }

        private View playToggle() {
            FrameLayout box = new FrameLayout(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
            lp.setMargins(dp(14), 0, dp(14), 0);
            box.setLayoutParams(lp);
            playToggle = box;
            playToggleIcon = new MusicIconView(ctx, MusicIconView.PLAY);
            playToggleIcon.setIconSizeDp(28);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            box.addView(playToggleIcon, ilp);
            CandyUi.ripple(box, 24);
            box.setContentDescription("播放/暂停");
            box.setOnClickListener(v -> togglePlay());
            return box;
        }

        private View spaceGrow() {
            View v = new View(ctx);
            v.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            return v;
        }

        private FrameLayout baseCover(int sizeDp, int glyphSp) {
            FrameLayout box = new FrameLayout(ctx);
            try {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                final float r = dp(sizeDp >= 100 ? AppColors.SHAPE_CARD_DP : AppColors.SHAPE_MD_DP);
                bg.setCornerRadius(r);
                bg.setColor(AppColors.primary());
                box.setBackground(bg);
                box.setClipToOutline(true);
                box.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                    }
                });
            } catch (Throwable ignored) {}
            TextView note = new TextView(ctx);
            note.setText("\u266A");
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, glyphSp);
            note.setTextColor(AppColors.onGradient());
            note.setGravity(Gravity.CENTER);
            box.addView(note, new FrameLayout.LayoutParams(-1, -1));
            box.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
            return box;
        }

        private ImageView addCoverImage(FrameLayout box) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            return iv;
        }

        /** 根据当前歌曲刷新盘心封面与「正在播放」条缩略图；列表接口无封面时按 id 懒解析。 */
        private void applyCover() {
            if (miniCoverIv != null) miniCoverIv.setImageDrawable(null);
            if (playDisc != null) playDisc.setCoverBitmap(null);
            final KuwoMusicApi.Song s = current;
            if (s == null) return;
            final String token = "cover:" + s.id;
            playerCoverToken = token;
            if (miniCoverIv != null) miniCoverIv.setTag(token);
            if (s.artwork != null && !s.artwork.isEmpty()) {
                if (miniCoverIv != null) CoverLoader.load(s.artwork, miniCoverIv);
                CoverLoader.loadBitmap(s.artwork, dp(220), bm -> {
                    if (token.equals(playerCoverToken) && playDisc != null) playDisc.setCoverBitmap(bm);
                });
                return;
            }
            bg(() -> {
                final String c = KuwoMusicApi.cover(s.id);
                if (c == null || c.isEmpty()) return;
                s.artwork = c;
                ui(() -> {
                    if (!token.equals(playerCoverToken)) return;
                    if (miniCoverIv != null && token.equals(miniCoverIv.getTag())) {
                        CoverLoader.load(c, miniCoverIv);
                    }
                    CoverLoader.loadBitmap(c, dp(220), bm -> {
                        if (token.equals(playerCoverToken) && playDisc != null) playDisc.setCoverBitmap(bm);
                    });
                });
            });
        }

        /** 列表行缩略图：有封面直载，无封面按 id 懒解析后回填。 */
        private void bindRowCover(final KuwoMusicApi.Song s, final ImageView iv) {
            if (iv == null || s == null) return;
            if (s.artwork != null && !s.artwork.isEmpty()) {
                CoverLoader.load(s.artwork, iv);
                return;
            }
            final String token = "cover:" + s.id;
            iv.setTag(token);
            bg(() -> {
                final String c = KuwoMusicApi.cover(s.id);
                if (c == null || c.isEmpty()) return;
                s.artwork = c;
                ui(() -> {
                    if (token.equals(iv.getTag())) CoverLoader.load(c, iv);
                });
            });
        }

        private TextView centerText(int sp, int color, boolean bold) {
            TextView tv = new TextView(ctx);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(color);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            return tv;
        }

        // 图标化功能按钮(收藏/下载/发送/音质)
        private View fbtn(String icon, String label, View.OnClickListener cb) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setOnClickListener(cb);
            CandyUi.ripple(item, 0);
            MusicIconView ic = new MusicIconView(ctx, icon);
            ic.setIconSizeDp(22);
            item.addView(ic);
            TextView lb = new TextView(ctx);
            lb.setText(label);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            lb.setTextColor(AppColors.text2());
            lb.setGravity(Gravity.CENTER);
            lb.setPadding(0, dp(4), 0, 0);
            item.addView(lb);
            return item;
        }

        // 播放顺序最左 + 上一首/播放暂停/下一首底部居中(间距拉开)
        private View transportBar() {
            FrameLayout bar = new FrameLayout(ctx);
            LinearLayout center = new LinearLayout(ctx);
            center.setOrientation(LinearLayout.HORIZONTAL);
            center.setGravity(Gravity.CENTER);
            center.addView(transportIcon(MusicIconView.PREV, "上一首", v -> prev()),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(48), dp(48));
            plp.setMargins(dp(14), 0, dp(14), 0);
            center.addView(playToggle(), plp);
            center.addView(transportIcon(MusicIconView.NEXT, "下一首", v -> next()),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
            bar.addView(center, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

            MusicIconView order = transportIcon(orderIcon(), "播放顺序", v -> cyclePlayMode());
            bar.addView(order, new FrameLayout.LayoutParams(-2, -2,
                    Gravity.START | Gravity.CENTER_VERTICAL));
            playOrderBtn = order;
            return bar;
        }

        private MusicIconView transportIcon(String icon, String desc, View.OnClickListener cb) {
            MusicIconView ic = new MusicIconView(ctx, icon);
            ic.setIconSizeDp(26);
            ic.setContentDescription(desc);
            ic.setOnClickListener(cb);
            return ic;
        }

        private String orderIcon() {
            if (playMode == 2) return MusicIconView.ORDER_SINGLE;
            if (playMode == 3) return MusicIconView.ORDER_SHUFFLE;
            return MusicIconView.ORDER_LIST;
        }

        private void cyclePlayMode() {
            playMode = playMode == 1 ? 2 : (playMode == 2 ? 3 : 1);
            if (playOrderBtn != null) playOrderBtn.setIcon(orderIcon());
            M3Page.toast(ctx, playMode == 2 ? "单曲循环" : (playMode == 3 ? "随机播放" : "列表循环"));
            savePlaybackState();
        }

        private void toggleCollect() {
            if (current == null || current.id == null || current.id.isEmpty()) {
                M3Page.toast(ctx, "还没有在播放的歌曲");
                return;
            }
            boolean now = OnlineMusicPrefs.toggleFavorite(current.id);
            refreshCollectBtn();
            M3Page.toast(ctx, now ? "已收藏" : "已取消收藏");
        }

        /** 同步收藏按钮心形填充态。 */
        private void refreshCollectBtn() {
            if (!(playCollectBtn instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) playCollectBtn;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch instanceof MusicIconView) {
                    boolean fav = current != null && current.id != null
                            && OnlineMusicPrefs.isFavorite(current.id);
                    ((MusicIconView) ch).setSolid(fav);
                    break;
                }
            }
        }

        // ===== 音质档位（自动 / 无损 / 320K / 128K） =====
        private static final String[] Q_LABELS = {"自动", "无损 FLAC", "320K", "128K"};
        private static final String[] Q_SUBS = {
                "优先最高音质 · 自动降级", "最高音质 · 文件较大", "高音质 · 推荐", "标准音质 · 省流量"};
        private static final String[] Q_VALS = {
                KuwoMusicApi.Q_AUTO, KuwoMusicApi.Q_FLAC, KuwoMusicApi.Q_320, KuwoMusicApi.Q_128};

        private static int qualityIndex(String q) {
            for (int i = 0; i < Q_VALS.length; i++) {
                if (Q_VALS[i].equals(q)) return i;
            }
            return 0;
        }

        /** AUTO 无独立档位,取流时按最高档处理(playLevels 已含自动降级)。 */
        private static String effectiveLevel(String q) {
            return KuwoMusicApi.Q_AUTO.equals(q) ? KuwoMusicApi.Q_FLAC : q;
        }

        private void showQualityDialog() {
            final String[] labels = Q_LABELS;
            final String[] subs = Q_SUBS;
            final String[] vals = Q_VALS;
            String q = OnlineMusicPrefs.quality();
            final int[] cur = {qualityIndex(q)};

            final AlertDialog[] holder = new AlertDialog[1];

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(CandyUi.dialogBg(ctx));
            InsetsUtil.clipRounded(root);

            ModernTopBar topBar = new ModernTopBar(ctx, "选择音质", true,
                    () -> { if (holder[0] != null) holder[0].dismiss(); });
            root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(12), dp(4), dp(12), dp(12));

            final LinearLayout card = M3Page.card(ctx);
            for (int i = 0; i < labels.length; i++) {
                if (i > 0) card.addView(M3Page.divider(ctx));
                card.addView(qualityRow(labels[i], subs[i], i == cur[0],
                        vals[i], labels[i], holder));
            }
            body.addView(card);
            root.addView(body);

            AlertDialog dialog = new AlertDialog.Builder(ctx)
                    .setView(root)
                    .setCancelable(true)
                    .create();
            holder[0] = dialog;
            InsetsUtil.transparentWindow(dialog);
            dialog.show();
            WindowLayer.track(dialog.getWindow());
        }

        /** 音质选项行：标题 + 说明 + 右侧勾选；点击即选中并关闭。 */
        private View qualityRow(String label, String sub, boolean checked,
                                final String value, final String labelText,
                                final AlertDialog[] holder) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(CandyUi.rowPressBg(ctx));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            TextView title = new TextView(ctx);
            title.setText(label);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(AppColors.text1());
            col.addView(title);
            TextView s = new TextView(ctx);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s.setTextColor(AppColors.text2());
            col.addView(s);
            row.addView(col);

            TextView check = new TextView(ctx);
            check.setText(checked ? "✓" : "");
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setTextColor(AppColors.primary());
            check.setGravity(Gravity.CENTER);
            row.addView(check);

            row.setOnClickListener(v -> {
                OnlineMusicPrefs.setQuality(value);
                M3Page.toast(ctx, "音质：" + labelText);
                if (holder[0] != null) holder[0].dismiss();
            });
            return row;
        }

        // ==================== 我的 ====================

        private View buildMine() {
            ScrollView scroll = new ScrollView(ctx);
            scroll.setFillViewport(true);

            LinearLayout page = new LinearLayout(ctx);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(12), dp(6), dp(12), dp(12));

            page.addView(M3Page.section(ctx, "点歌与设置"));
            LinearLayout card1 = M3Page.card(ctx);
            card1.addView(M3Page.clickRow(ctx, "\u2699", "点歌设置",
                    "白名单 / 别名 / 音质 / 误报时长", () -> {
                        selectNav(NAV_HOME);
                        openSettings();
                    }));
            page.addView(card1);

            page.addView(M3Page.section(ctx, "默认音质"));
            String q = OnlineMusicPrefs.quality();
            int qIdx = qualityIndex(q);
            SegmentedControl qSeg = new SegmentedControl(ctx, Q_LABELS, qIdx);
            qSeg.setOnSegmentChangedListener((i, label) -> {
                OnlineMusicPrefs.setQuality(Q_VALS[i]);
                M3Page.toast(ctx, "音质：" + label);
            });
            page.addView(qSeg);
            page.addView(M3Page.spacer(ctx, 6));
            page.addView(M3Page.note(ctx, "“自动”优先取最高音质，不可用时自动降档；点歌与在线试听均按此设置。"));

            page.addView(M3Page.section(ctx, "下载"));
            LinearLayout card2 = M3Page.card(ctx);
            card2.addView(M3Page.clickRow(ctx, "\u2B07", "下载目录",
                    downloadDir().getAbsolutePath(), () ->
                            M3Page.toast(ctx, "已保存歌曲位于：" + downloadDir().getAbsolutePath())));
            page.addView(card2);

            scroll.addView(page);
            return scroll;
        }

        private File downloadDir() {
            File base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = ctx.getFilesDir();
            return new File(base, "LeShaoMusic");
        }

        // ==================== 播放控制 ====================

        private void openPlayer() {
            if (current == null) {
                M3Page.toast(ctx, "还没有在播放的歌曲");
                return;
            }
            playerOrigin = nav;
            selectNav(NAV_PLAY);
        }

        private void refreshPlayerUi() {
            if (miniTitle == null) return;
            boolean has = current != null;
            miniTitle.setText(has ? nz(current.title) : "未在播放");
            miniArtist.setText(has ? (nz(current.artist) + (current.album == null || current.album.isEmpty()
                    ? "" : " · " + current.album)) : "点击选择歌曲试听");
            miniPlay.setIcon(paused ? MusicIconView.PLAY : MusicIconView.PAUSE);
            if (playTitle != null) playTitle.setText(has ? nz(current.title) : "未在播放");
            if (playArtist != null) playArtist.setText(has ? nz(current.artist) : "选择一首歌开始");
            if (playToggleIcon != null) playToggleIcon.setIcon(paused ? MusicIconView.PLAY : MusicIconView.PAUSE);
            if (playQueueInfo != null) {
                playQueueInfo.setText(queue.isEmpty() ? "播放队列为空"
                        : "播放队列 " + (queueIndex + 1) + " / " + queue.size());
            }
            refreshCollectBtn();
            refreshRowPlayIcons();
            applyCover();
        }

        private String nz(String s) { return s == null ? "" : s; }

        /** 旋律柱：仅在播放器页且正在播放时显示并跟随播放器频谱跳动。 */
        private void syncEq() {
            if (playEq == null) return;
            boolean show = current != null && nav == NAV_PLAY;
            playEq.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && !paused && player != null) {
                playEq.start(player);
            } else {
                playEq.stop();
            }
        }

        private void updateProgress() {
            int dur = 0, pos = 0;
            try {
                if (player != null) { dur = player.getDuration(); pos = player.getCurrentPosition(); }
            } catch (Throwable ignored) {}
            float frac = dur > 0 ? Math.max(0f, Math.min(1f, pos / (float) dur)) : 0f;
            if (playSeek != null && !seeking) playSeek.setProgress((int) (frac * 100));
            if (seeking) return;
            if (progressFill != null && progressTrack != null) {
                int tw = progressTrack.getWidth();
                ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
                int nw = (int) (tw * frac);
                if (lp.width != nw) { lp.width = nw; progressFill.setLayoutParams(lp); }
            }
            if (playPos != null) playPos.setText(fmtTime(pos));
            if (playDur != null) playDur.setText(fmtTime(dur));
        }

        /** 拖动进度条时实时刷新左侧时间（按百分比预估）。 */
        private void updateProgressText(int percent) {
            if (player == null) return;
            try {
                int dur = player.getDuration();
                if (dur > 0 && playPos != null) {
                    playPos.setText(fmtTime((int) (dur * (percent / 100f))));
                }
                if (playDur != null) playDur.setText(fmtTime(dur));
            } catch (Throwable ignored) {}
        }

        private String fmtTime(int ms) {
            if (ms <= 0) return "00:00";
            int s = ms / 1000;
            return String.format(java.util.Locale.CHINA, "%02d:%02d", s / 60, s % 60);
        }

        private void onSongTap(final KuwoMusicApi.Song s, List<KuwoMusicApi.Song> fromList) {
            if (current != null && s.id != null && s.id.equals(current.id) && player != null) {
                stopPlayer();
                return;
            }
            if (fromList != null && !fromList.isEmpty()) {
                queue.clear();
                queue.addAll(fromList);
                queueIndex = indexOfId(queue, s.id);
            } else if (!queue.isEmpty()) {
                int idx = indexOfId(queue, s.id);
                if (idx >= 0) queueIndex = idx;
            }
            resolveAndPlay(s);
        }

        private int indexOfId(List<KuwoMusicApi.Song> list, String id) {
            if (id == null) return -1;
            for (int i = 0; i < list.size(); i++) {
                if (id.equals(list.get(i).id)) return i;
            }
            return -1;
        }

        private void playQueueAt(int i) {
            if (queue.isEmpty() || i < 0 || i >= queue.size()) return;
            queueIndex = i;
            resolveAndPlay(queue.get(i));
        }

        private void next() {
            if (queue.isEmpty()) { M3Page.toast(ctx, "播放队列为空"); return; }
            playQueueAt((queueIndex + 1) % queue.size());
        }

        private void prev() {
            if (queue.isEmpty()) { M3Page.toast(ctx, "播放队列为空"); return; }
            playQueueAt((queueIndex - 1 + queue.size()) % queue.size());
        }

        /** 试听取流档位: 以所选音质为首选, 失败时逐级降档(与点歌一致)。 */
        private String[] playLevels() {
            String q = OnlineMusicPrefs.quality();
            // 自动 / 无损：从最高档开始，逐级降档
            if (KuwoMusicApi.Q_AUTO.equals(q) || KuwoMusicApi.Q_FLAC.equals(q)) {
                return new String[]{KuwoMusicApi.Q_FLAC, KuwoMusicApi.Q_320, KuwoMusicApi.Q_128};
            }
            if (KuwoMusicApi.Q_320.equals(q)) {
                return new String[]{KuwoMusicApi.Q_320, KuwoMusicApi.Q_128};
            }
            return new String[]{KuwoMusicApi.Q_128, KuwoMusicApi.Q_320};
        }

        private void resolveAndPlay(final KuwoMusicApi.Song s) {
            stopPlayer();
            if (sActive == this) M3Page.toast(ctx, "正在解析音质…");
            bg(() -> {
                Throwable last = null;
                for (String level : playLevels()) {
                    try {
                        String finalUrl = KuwoMusicApi.resolveFinalUrl(
                                KuwoMusicApi.streamUrl(s.id, level));
                        if (finalUrl != null && !finalUrl.isEmpty()) {
                            ui(() -> startPlayer(s, finalUrl));
                            return;
                        }
                        last = new Exception(level + " 无可用直链");
                    } catch (Throwable t) {
                        last = t;
                    }
                }
                final Throwable f = last;
                ui(() -> { if (sActive == this) M3Page.toastError(ctx, "取流失败：" + (f == null ? "无可用音质" : msg(f))); });
            });
        }

        private void startPlayer(final KuwoMusicApi.Song s, String url) {
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                mp.setDataSource(url);
                mp.setOnPreparedListener(p -> {
                    current = s;
                    paused = false;
                    if (pendingSeekMs > 0) {
                        try { p.seekTo(pendingSeekMs); } catch (Throwable ignored) {}
                    }
                    pendingSeekMs = -1;
                    p.start();
                    savePlaybackState();
                    // 页面已关闭时仅后台继续播放, 不刷新 UI, 由悬浮球接管控制
                    if (sActive != this) { MusicFloatBall.show(act); return; }
                    refreshPlayerUi();
                    updateProgress();
                    syncEq();
                    uiHandler.removeCallbacks(ticker);
                    uiHandler.post(ticker);
                    M3Page.toast(ctx, "播放中：" + s.title);
                });
                bindPlayerCallbacks(mp);
                mp.prepareAsync();
                player = mp;
            } catch (Throwable t) {
                stopPlayer();
                M3Page.toastError(ctx, "播放失败：" + msg(t));
            }
        }

        /** v1105: 绑定播放完成/错误回调(可重复调用以接管后台播放器)。 */
        private void bindPlayerCallbacks(final MediaPlayer mp) {
            mp.setOnCompletionListener(p -> {
                if (queue.isEmpty() || queueIndex < 0 || queueIndex >= queue.size()) {
                    stopPlayer();
                    return;
                }
                if (playMode == 2) {
                    playQueueAt(queueIndex);
                } else if (playMode == 3) {
                    playQueueAt(new java.util.Random().nextInt(queue.size()));
                } else {
                    playQueueAt((queueIndex + 1) % queue.size());
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                stopPlayer();
                if (sActive == this) M3Page.toastError(ctx, "播放失败");
                return true;
            });
        }

        /** v1105: 保存当前播放进度/模式, 便于关闭页面后恢复。 */
        private void savePlaybackState() {
            try {
                int pos = 0;
                if (player != null) {
                    try { pos = player.getCurrentPosition(); } catch (Throwable ignored) {}
                } else if (pendingSeekMs > 0) {
                    pos = pendingSeekMs;
                }
                OnlineMusicPrefs.savePlayback(queue, queueIndex, pos, playMode, paused || player == null);
            } catch (Throwable ignored) {
            }
        }

        /** v1105: 恢复状态下把上次进度显示到时间栏。 */
        private void updateProgressTextFromPending() {
            if (pendingSeekMs > 0 && playPos != null) playPos.setText(fmtTime(pendingSeekMs));
        }

        private void togglePlay() {
            if (player == null) {
                if (current != null) {
                    // 恢复的暂停曲目: 重新取流并跳回上次进度
                    pendingSeekMs = OnlineMusicPrefs.playbackPosition();
                    resolveAndPlay(current);
                    return;
                }
                M3Page.toast(ctx, "还没有在播放的歌曲");
                return;
            }
            try {
                if (paused) { player.start(); paused = false; uiHandler.post(ticker); }
                else { player.pause(); paused = true; uiHandler.removeCallbacks(ticker); }
            } catch (Throwable ignored) {}
            refreshPlayerUi();
            syncEq();
            savePlaybackState();
        }

        private void stopPlayer() {
            uiHandler.removeCallbacks(ticker);
            if (player != null) {
                try { player.stop(); } catch (Throwable ignored) {}
                try { player.release(); } catch (Throwable ignored) {}
                player = null;
            }
            current = null;
            paused = false;
            if (progressFill != null) {
                ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
                lp.width = 0;
                progressFill.setLayoutParams(lp);
            }
            refreshPlayerUi();
            updateProgress();
            syncEq();
            savePlaybackState();
        }

        // ==================== 分类 ====================

        private void selectTab(int index) {
            tab = index;
            showingDetail = false;
            content.removeAllViews();
            switch (index) {
                case HOME_TAB_CHARTS: buildCharts(); break;
                case HOME_TAB_SHEETS: buildSheets(); break;
                case HOME_TAB_IMPORT: buildImport(); break;
            }
        }

        // ==================== 搜索（弹窗） ====================

        private String searchKeyword = "";
        private int searchType = 0;
        private AlertDialog searchDialog;
        private EditText searchInput;
        private LinearLayout searchHistoryHost;
        private LinearLayout searchResultHost;

        /** 弹出独立搜索窗口：输入 + 类型切换 + 搜索历史 + 结果（模块统一弹窗风格）。 */
        private void showSearchWindow() {
            final AlertDialog[] holder = new AlertDialog[1];

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            // v30111: 高度随内容自适应（上限 90%），不再强制最小高避免底部留白
            root.setBackground(CandyUi.dialogBg(ctx));
            InsetsUtil.clipRounded(root);

            ModernTopBar topBar = new ModernTopBar(ctx, "搜索", true,
                    () -> { if (holder[0] != null) holder[0].dismiss(); });
            root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(12), dp(4), dp(12), dp(10));
            root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

            LinearLayout inputRow = new LinearLayout(ctx);
            inputRow.setOrientation(LinearLayout.HORIZONTAL);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            searchInput = M3Page.input(ctx, "输入歌曲 / 专辑 / 歌手 / 歌单");
            searchInput.setText(searchKeyword);
            searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            searchInput.setOnEditorActionListener((v, actionId, event) -> {
                boolean enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
                if (enter) { runSearch(); return true; }
                return false;
            });
            searchInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            inputRow.addView(searchInput);
            TextView go = actionText("搜索", () -> runSearch());
            go.setPadding(dp(12), dp(9), dp(2), dp(9));
            inputRow.addView(go);
            body.addView(inputRow);

            searchHistoryHost = new LinearLayout(ctx);
            searchHistoryHost.setOrientation(LinearLayout.VERTICAL);
            searchHistoryHost.setPadding(0, dp(8), 0, 0);
            body.addView(searchHistoryHost);
            renderSearchHistory();

            body.addView(M3Page.spacer(ctx, 10));

            SegmentedControl types = new SegmentedControl(ctx,
                    new String[]{"歌曲", "专辑", "歌手", "歌单"}, searchType);
            types.setOnSegmentChangedListener((i, label) -> {
                searchType = i;
                if (!searchKeyword.isEmpty()) doSearch(i);
            });
            body.addView(types);
            body.addView(M3Page.spacer(ctx, 8));

            ScrollView resultScroll = new ScrollView(ctx);
            resultScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            searchResultHost = new LinearLayout(ctx);
            searchResultHost.setOrientation(LinearLayout.VERTICAL);
            resultScroll.addView(searchResultHost);
            body.addView(resultScroll);

            try {
                searchDialog = new AlertDialog.Builder(ctx)
                        .setView(root)
                        .setCancelable(true)
                        .create();
                holder[0] = searchDialog;
                InsetsUtil.transparentWindow(searchDialog);
                searchDialog.show();
                WindowLayer.track(searchDialog.getWindow());
            } catch (Throwable ignored) {}
        }

        private void runSearch() {
            if (searchInput == null) return;
            searchKeyword = searchInput.getText().toString().trim();
            if (searchKeyword.isEmpty()) {
                M3Page.toast(ctx, "请输入关键词");
                return;
            }
            OnlineMusicPrefs.addSearchHistory(searchKeyword);
            renderSearchHistory();
            doSearch(searchType);
        }

        /** 搜索历史：标题 + 清空 + 横向滚动的关键词胶囊。 */
        private void renderSearchHistory() {
            if (searchHistoryHost == null) return;
            searchHistoryHost.removeAllViews();
            final java.util.List<String> hist = OnlineMusicPrefs.searchHistory();
            if (hist.isEmpty()) return;

            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(ctx);
            title.setText("搜索历史");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setTextColor(AppColors.text2());
            title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            head.addView(title);
            head.addView(actionText("清空", () -> {
                OnlineMusicPrefs.clearSearchHistory();
                renderSearchHistory();
            }));
            searchHistoryHost.addView(head);

            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(ctx);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(0, dp(6), 0, 0);
            for (final String kw : hist) {
                TextView chip = new TextView(ctx);
                chip.setText(kw);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                chip.setTextColor(AppColors.text1());
                chip.setSingleLine(true);
                chip.setPadding(dp(12), dp(6), dp(12), dp(6));
                try {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(AppColors.surfaceContainerHigh());
                    bg.setCornerRadius(dp(AppColors.SHAPE_FULL_DP));
                    chip.setBackground(bg);
                } catch (Throwable ignored) {}
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(8);
                chip.setLayoutParams(lp);
                chip.setOnClickListener(v -> {
                    if (searchInput != null) searchInput.setText(kw);
                    searchKeyword = kw;
                    OnlineMusicPrefs.addSearchHistory(kw);
                    renderSearchHistory();
                    doSearch(searchType);
                });
                chips.addView(chip);
            }
            hs.addView(chips);
            searchHistoryHost.addView(hs);
        }

        private void doSearch(final int type) {
            if (searchResultHost == null) return;
            final String kw = searchKeyword;
            if (type == 0) {
                loadList(searchResultHost, () -> KuwoMusicApi.search(kw, 1), this::renderSongs);
            } else if (type == 1) {
                loadList(searchResultHost, () -> KuwoMusicApi.searchAlbums(kw, 1),
                        this::renderAlbums);
            } else if (type == 2) {
                loadList(searchResultHost, () -> KuwoMusicApi.searchArtists(kw, 1),
                        this::renderArtists);
            } else {
                loadList(searchResultHost, () -> KuwoMusicApi.searchPlaylists(kw, 1),
                        this::renderPlaylists);
            }
        }

        /** 关闭搜索窗口（进入详情/播放前调用，避免遮罩残留）。 */
        private void dismissSearchWindow() {
            try {
                if (searchDialog != null && searchDialog.isShowing()) searchDialog.dismiss();
            } catch (Throwable ignored) {}
            searchDialog = null;
            searchInput = null;
            searchHistoryHost = null;
            searchResultHost = null;
        }

        // ==================== 榜单 ====================

        private void buildCharts() {
            content.addView(M3Page.section(ctx, "排行榜"));
            LinearLayout host = new LinearLayout(ctx);
            host.setOrientation(LinearLayout.VERTICAL);
            content.addView(host);
            loadList(host, KuwoMusicApi::chartGroups, (h, groups) -> {
                for (KuwoMusicApi.ChartGroup g : groups) {
                    h.addView(M3Page.section(ctx, g.title));
                    LinearLayout card = M3Page.card(ctx);
                    for (int i = 0; i < g.charts.size(); i++) {
                        if (i > 0) card.addView(M3Page.divider(ctx));
                        KuwoMusicApi.Chart c = g.charts.get(i);
                        card.addView(M3Page.clickRow(ctx, "\uD83C\uDFC6", c.title, c.description,
                                () -> openChart(c)));
                    }
                    h.addView(card);
                }
            });
        }

        private void openChart(final KuwoMusicApi.Chart c) {
            showDetail(c.title, host -> loadList(host,
                    () -> KuwoMusicApi.chartSongs(c.id), this::renderSongs));
        }

        // ==================== 歌单 ====================

        private void buildSheets() {
            content.addView(M3Page.section(ctx, "推荐歌单"));
            final LinearLayout tagHost = new LinearLayout(ctx);
            tagHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(tagHost);
            final LinearLayout listHost = new LinearLayout(ctx);
            listHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(listHost);

            tagHost.addView(M3Page.note(ctx, "加载标签中…"));
            bg(() -> {
                try {
                    final List<KuwoMusicApi.Tag> tags = KuwoMusicApi.recommendTags();
                    ui(() -> {
                        tagHost.removeAllViews();
                        buildTagBar(tagHost, listHost, tags);
                    });
                } catch (final Throwable t) {
                    ui(() -> {
                        tagHost.removeAllViews();
                        tagHost.addView(M3Page.note(ctx, "标签加载失败：" + msg(t)));
                    });
                }
            });
        }

        private void buildTagBar(LinearLayout tagHost, final LinearLayout listHost,
                                 final List<KuwoMusicApi.Tag> tags) {
            if (tags.isEmpty()) {
                loadPlaylists(listHost, "");
                return;
            }
            int n = Math.min(5, tags.size());
            String[] labels = new String[n];
            for (int i = 0; i < n; i++) labels[i] = tags.get(i).title;
            SegmentedControl seg = new SegmentedControl(ctx, labels, 0);
            seg.setOnSegmentChangedListener((i, label) -> loadPlaylists(listHost, tags.get(i).id));
            tagHost.addView(seg);
            tagHost.addView(M3Page.spacer(ctx, 6));
            tagHost.addView(M3Page.clickRow(ctx, "\u2630", "更多标签", "浏览全部音乐分类", () -> {
                String[] all = new String[tags.size()];
                for (int i = 0; i < tags.size(); i++) all[i] = tags.get(i).title;
                new AlertDialog.Builder(ctx)
                        .setTitle("选择标签")
                        .setItems(all, (dlg, which) -> {
                            loadPlaylists(listHost, tags.get(which).id);
                            M3Page.toast(ctx, "已选择：" + tags.get(which).title);
                        })
                        .show();
            }));
            tagHost.addView(M3Page.spacer(ctx, 6));
            loadPlaylists(listHost, tags.get(0).id);
        }

        private void loadPlaylists(LinearLayout host, final String tagId) {
            loadList(host, () -> KuwoMusicApi.recommendPlaylists(tagId, 1),
                    this::renderPlaylists);
        }

        // ==================== 导入 ====================

        private void buildImport() {
            content.addView(M3Page.section(ctx, "导入歌单"));
            LinearLayout card = M3Page.card(ctx);
            final EditText et = M3Page.input(ctx, "粘贴酷我歌单链接或纯数字 ID");
            card.addView(et);
            card.addView(M3Page.spacer(ctx, 8));
            final LinearLayout host = new LinearLayout(ctx);
            host.setOrientation(LinearLayout.VERTICAL);
            card.addView(M3Page.button(ctx, "导 入", () -> {
                String id = KuwoMusicApi.parsePlaylistId(et.getText().toString());
                if (id == null || id.isEmpty()) {
                    M3Page.toast(ctx, "无法识别歌单 ID");
                    return;
                }
                loadList(host, () -> KuwoMusicApi.playlistSongs(id, 1, 200),
                        (h, data) -> {
                            h.addView(M3Page.note(ctx, "共导入 " + data.size() + " 首（歌单 " + id + "）"));
                            renderSongs(h, data);
                        });
            }));
            content.addView(card);
            content.addView(M3Page.note(ctx,
                    "支持 www.kuwo.cn/playlist_detail/{id} 或 m.kuwo.cn/h5app/playlist/{id}，也可直接填数字 ID"));
            content.addView(host);
        }

        // ==================== 列表渲染 ====================

        private void renderSongs(LinearLayout host, List<KuwoMusicApi.Song> data) {
            rowPlayIcons.clear();
            LinearLayout card = M3Page.card(ctx);
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) card.addView(M3Page.divider(ctx));
                card.addView(songRow(data.get(i), data));
            }
            host.addView(card);
            refreshRowPlayIcons();
        }

        private View songRow(final KuwoMusicApi.Song s, final List<KuwoMusicApi.Song> list) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(6), dp(8));
            row.setBackground(CandyUi.rowPressBg(ctx));

            FrameLayout thumbBox = baseCover(44, 20);
            ImageView thumb = addCoverImage(thumbBox);
            bindRowCover(s, thumb);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            thumbLp.rightMargin = dp(10);
            row.addView(thumbBox, thumbLp);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

            TextView title = new TextView(ctx);
            title.setText(s.title == null ? "" : s.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(AppColors.text1());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title);

            String sub = s.artist == null ? "" : s.artist;
            if (s.album != null && !s.album.isEmpty()) sub += " · " + s.album;
            TextView subTv = new TextView(ctx);
            subTv.setText(sub);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subTv.setTextColor(AppColors.text2());
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subTv);
            row.addView(col);

            final MusicIconView playIc = icon(MusicIconView.PLAY, "播放", () -> onRowPlay(s, list));
            rowPlayIcons.add(new RowPlay(s.id, playIc));
            row.addView(playIc);
            row.addView(icon(MusicIconView.DL, "下载", () -> download(s)));
            row.addView(icon(MusicIconView.SEND, "发送", () -> sendToTargets(s)));
            row.setOnClickListener(v -> onSongTap(s, list));
            return row;
        }

        /** 歌曲行播放按钮：当前曲目则切换播放/暂停，否则开始播放该曲。 */
        private void onRowPlay(KuwoMusicApi.Song s, List<KuwoMusicApi.Song> list) {
            if (current != null && s.id != null && s.id.equals(current.id) && player != null) {
                togglePlay();
            } else {
                onSongTap(s, list);
            }
        }

        /** 同步歌曲行播放按钮：当前曲目显示暂停/播放态，其余显示播放态。 */
        private void refreshRowPlayIcons() {
            if (rowPlayIcons.isEmpty()) return;
            for (RowPlay rp : rowPlayIcons) {
                if (rp.icon == null) continue;
                boolean isCur = current != null && rp.id != null && rp.id.equals(current.id);
                boolean playing = isCur && !paused;
                rp.icon.setIcon(playing ? MusicIconView.PAUSE : MusicIconView.PLAY);
            }
        }

        private void renderAlbums(LinearLayout host, List<KuwoMusicApi.Album> data) {
            LinearLayout card = M3Page.card(ctx);
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) card.addView(M3Page.divider(ctx));
                final KuwoMusicApi.Album a = data.get(i);
                StringBuilder sub = new StringBuilder();
                if (a.artist != null && !a.artist.isEmpty()) sub.append(a.artist);
                if (a.date != null && !a.date.isEmpty()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(a.date);
                }
                card.addView(M3Page.clickRow(ctx, "\uD83D\uDCBF", a.title,
                        sub.length() == 0 ? null : sub.toString(), () -> openAlbum(a)));
            }
            host.addView(card);
        }

        private void renderArtists(LinearLayout host, List<KuwoMusicApi.Artist> data) {
            LinearLayout card = M3Page.card(ctx);
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) card.addView(M3Page.divider(ctx));
                final KuwoMusicApi.Artist a = data.get(i);
                String sub = a.worksNum > 0 ? (a.worksNum + " 首作品") : a.description;
                card.addView(M3Page.clickRow(ctx, "\uD83C\uDFA4", a.name, sub, () -> openArtist(a)));
            }
            host.addView(card);
        }

        private void renderPlaylists(LinearLayout host, List<KuwoMusicApi.Playlist> data) {
            LinearLayout card = M3Page.card(ctx);
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) card.addView(M3Page.divider(ctx));
                final KuwoMusicApi.Playlist p = data.get(i);
                StringBuilder sub = new StringBuilder();
                if (p.artist != null && !p.artist.isEmpty()) sub.append(p.artist);
                if (p.playCount > 0) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(fmtCount(p.playCount)).append(" 播放");
                }
                card.addView(M3Page.clickRow(ctx, "\uD83C\uDFB5", p.title,
                        sub.length() == 0 ? null : sub.toString(), () -> openPlaylist(p)));
            }
            host.addView(card);
        }

        // ==================== 详情 ====================

        private interface DetailBuilder {
            void build(LinearLayout host);
        }

        private void showDetail(String title, DetailBuilder builder) {
            showDetail(title, tab, builder);
        }

        /** returnTab: 详情返回目标, -1=返回「我的」, >=0=返回对应首页标签。 */
        private void showDetail(String title, int returnTab, DetailBuilder builder) {
            dismissSearchWindow();
            showingDetail = true;
            detailReturnTab = returnTab;
            content.removeAllViews();
            TextView back = new TextView(ctx);
            back.setText("← 返回");
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            back.setTextColor(AppColors.primary());
            back.setTypeface(Typeface.DEFAULT_BOLD);
            back.setPadding(dp(2), dp(4), dp(2), dp(8));
            back.setOnClickListener(v -> backFromDetail());
            content.addView(back);
            content.addView(M3Page.section(ctx, title));
            LinearLayout host = new LinearLayout(ctx);
            host.setOrientation(LinearLayout.VERTICAL);
            content.addView(host);
            builder.build(host);
        }

        private void openAlbum(final KuwoMusicApi.Album a) {
            showDetail(a.title, host -> {
                if (a.artist != null && !a.artist.isEmpty()) host.addView(M3Page.note(ctx, a.artist));
                loadList(host, () -> KuwoMusicApi.albumSongs(a.id), this::renderSongs);
            });
        }

        private void openArtist(final KuwoMusicApi.Artist a) {
            showDetail(a.name, host -> {
                if (a.description != null && !a.description.isEmpty()) {
                    host.addView(M3Page.note(ctx, a.description));
                }
                host.addView(M3Page.section(ctx, "代表作品"));
                LinearLayout songHost = new LinearLayout(ctx);
                songHost.setOrientation(LinearLayout.VERTICAL);
                host.addView(songHost);
                loadList(songHost, () -> KuwoMusicApi.artistSongs(a.id, 1), this::renderSongs);
            });
        }

        private void openPlaylist(final KuwoMusicApi.Playlist p) {
            showDetail(p.title, host -> {
                if (p.description != null && !p.description.isEmpty()) {
                    host.addView(M3Page.note(ctx, p.description));
                }
                loadList(host, () -> KuwoMusicApi.playlistSongs(p.id, 1, 200), this::renderSongs);
            });
        }

        // ==================== 点歌设置 ====================

        private void openSettings() {
            showDetail("点歌设置", -1, host -> {
                LinearLayout card = M3Page.card(ctx);
                card.addView(M3Page.switchRow(ctx, "\uD83C\uDFA7", "启用点歌",
                        "聊天窗口发送「点歌 歌名」自动取最高音质转为语音消息",
                        OnlineMusicPrefs.enabled(),
                        (v, on) -> OnlineMusicPrefs.setEnabled(on)));
                card.addView(M3Page.divider(ctx));
                card.addView(M3Page.switchRow(ctx, "\uD83D\uDCAC", "发送提示语",
                        "处理前在会话内发送提示语（内容可在下方自定义）",
                        OnlineMusicPrefs.notice(),
                        (v, on) -> OnlineMusicPrefs.setNotice(on)));
                card.addView(M3Page.divider(ctx));
                card.addView(M3Page.switchRow(ctx, "\u2B07", "自动下载",
                        "点歌后将音频保存到本地",
                        OnlineMusicPrefs.autoDownload(),
                        (v, on) -> OnlineMusicPrefs.setAutoDownload(on)));
                card.addView(M3Page.divider(ctx));
                card.addView(M3Page.switchRow(ctx, "\uD83D\uDD00", "进入自动随机播放",
                        "打开在线音乐即随机播放一首（优先上次队列/默认榜单）",
                        OnlineMusicPrefs.autoRandom(),
                        (v, on) -> OnlineMusicPrefs.setAutoRandom(on)));
                host.addView(card);

                host.addView(M3Page.section(ctx, "生效范围"));
                LinearLayout wlCard = M3Page.card(ctx);
                wlCard.addView(M3Page.clickRow(ctx, "\uD83D\uDC65", "点歌白名单",
                        "仅白名单会话内生效；为空时不触发（已选 "
                                + OnlineMusicPrefs.whitelist().size() + " 个）",
                        () -> {
                            String cur = String.join(",", OnlineMusicPrefs.whitelist());
                            ContactPickerDialog.show(act, cur,
                                    ContactPickerDialog.MODE_FRIEND, (selected, display) -> {
                                        OnlineMusicPrefs.setWhitelist(String.join(",", selected));
                                        M3Page.toast(ctx, "已保存 " + selected.size() + " 个会话");
                                        openSettings();
                                    });
                        }));
                host.addView(wlCard);

                host.addView(M3Page.section(ctx, "指令与参数"));
                LinearLayout card2 = M3Page.card(ctx);
                card2.addView(inputSaveRow("触发别名", "逗号分隔，如：点歌,点唱,来一首",
                        String.join(",", OnlineMusicPrefs.aliases()), true,
                        OnlineMusicPrefs::setAliases));
                card2.addView(M3Page.divider(ctx));
                card2.addView(inputSaveRow("误报时长（秒）", "超过 60 秒的语音按此秒数上报，范围 1-60",
                        String.valueOf(OnlineMusicPrefs.falseDurSec()), false,
                        val -> {
                            try { OnlineMusicPrefs.setFalseDurSec(Integer.parseInt(val.trim())); }
                            catch (Throwable ignored) { M3Page.toast(ctx, "请输入 1-60 的数字"); }
                        }));
                card2.addView(M3Page.divider(ctx));
                card2.addView(inputSaveRow("提示语内容", "点歌处理前发送的文字；{song} 代表歌名",
                        OnlineMusicPrefs.noticeText(), true,
                        OnlineMusicPrefs::setNoticeText));
                host.addView(card2);

                host.addView(M3Page.section(ctx, "音质"));
                String q = OnlineMusicPrefs.quality();
                int qIdx = qualityIndex(q);
                SegmentedControl qSeg = new SegmentedControl(ctx, Q_LABELS, qIdx);
                qSeg.setOnSegmentChangedListener((i, label) -> {
                    OnlineMusicPrefs.setQuality(Q_VALS[i]);
                    M3Page.toast(ctx, "音质：" + label);
                });
                host.addView(qSeg);
                host.addView(M3Page.note(ctx, "“自动”优先取最高音质，不可用时自动降档；点歌与在线试听均按此设置。"));
            });
        }

        private View inputSaveRow(String title, String desc, String value, boolean text,
                                  final ValueCallback cb) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackgroundColor(AppColors.whiteCard());

            TextView tv = new TextView(ctx);
            tv.setText(title);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(AppColors.text1());
            card.addView(tv);

            if (desc != null && !desc.isEmpty()) {
                TextView dv = new TextView(ctx);
                dv.setText(desc);
                dv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                dv.setTextColor(AppColors.text2());
                dv.setPadding(0, dp(3), 0, 0);
                card.addView(dv);
            }

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);

            final EditText et = new EditText(ctx);
            et.setText(value);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            et.setSingleLine(true);
            et.setHorizontallyScrolling(true);
            et.setTextColor(AppColors.text1());
            et.setInputType(text ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_NUMBER);
            et.setBackground(CandyUi.inputBg(ctx));
            et.setPadding(dp(12), dp(9), dp(12), dp(9));
            et.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            // v1105: 修复点击输入框不弹键盘 —— 显式允许软键盘、聚焦后强制唤起 IME
            et.setFocusable(true);
            et.setFocusableInTouchMode(true);
            et.setShowSoftInputOnFocus(true);
            et.setOnClickListener(v -> { et.requestFocus(); showKeyboard(et); });
            et.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    et.requestFocus();
                    showKeyboard(et);
                }
                return false;
            });
            et.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) showKeyboard(et);
            });
            // 进入点歌设置即让宿主弹窗支持 IME 缩放(避免键盘被输入框挡住/不弹出)
            ensureImeResize();
            row.addView(et);

            TextView save = actionText("保存", () -> {
                cb.onValue(et.getText().toString());
                M3Page.toastSuccess(ctx, "已保存");
            });
            save.setPadding(dp(12), dp(9), dp(4), dp(9));
            row.addView(save);
            card.addView(row);
            return card;
        }

        /** v1105: 强制唤起软键盘(修复点歌设置输入框点击无键盘)。 */
        private void showKeyboard(final View v) {
            if (v == null) return;
            v.postDelayed(() -> {
                try {
                    if (v instanceof EditText) ((EditText) v).requestFocusFromTouch();
                    else v.requestFocus();
                    SubPageActivity.ensureImeVisible();
                    InputMethodManager imm = (InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(v, 0);
                        if (!imm.isActive(v)) {
                            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, 80);
        }

        /** v1105: 让承载本页的宿主 AlertDialog 窗口支持软键盘缩放。 */
        private void ensureImeResize() {
            try {
                SubPageActivity.ensureImeResize();
            } catch (Throwable ignored) {
            }
        }

        // ==================== 下载 / 发送 ====================

        private void download(final KuwoMusicApi.Song s) {
            M3Page.toast(ctx, "开始下载：" + s.title);
            showDownloadProgress();
            bg(() -> {
                try {
                    final String level = effectiveLevel(OnlineMusicPrefs.quality());
                    String url = KuwoMusicApi.resolveFinalUrl(KuwoMusicApi.streamUrl(s.id, level));
                    File dir = downloadDir();
                    if (!dir.exists()) dir.mkdirs();
                    String ext = KuwoMusicApi.Q_FLAC.equals(level) ? ".flac" : ".mp3";
                    String safe = (s.title + (s.artist == null || s.artist.isEmpty()
                            ? "" : " - " + s.artist)).replaceAll("[\\\\/:*?\"<>|]", "_");
                    File out = new File(dir, safe + ext);
                    KuwoMusicApi.download(url, out, new KuwoMusicApi.Progress() {
                        @Override public void onProgress(long cur, long total) {
                            if (total > 0) {
                                final int p = (int) Math.min(100L, cur * 100L / total);
                                ui(() -> { if (downloadBar != null) downloadBar.setProgress(p); });
                            }
                        }
                        @Override public boolean isCancelled() { return false; }
                    });
                    ui(() -> {
                        dismissDownloadProgress();
                        M3Page.toastSuccess(ctx, "已保存：" + out.getName());
                    });
                } catch (final Throwable t) {
                    ui(() -> {
                        dismissDownloadProgress();
                        M3Page.toastError(ctx, "下载失败：" + msg(t));
                    });
                }
            });
        }

        /** 下载进度弹窗（复用「转码中」同款流光飞鸟进度条）。 */
        private void showDownloadProgress() {
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(22), dp(18), dp(22), dp(16));

            TextView label = new TextView(ctx);
            label.setText("下载中…");
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(AppColors.text1());
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, 0, 0, dp(10));
            root.addView(label);

            FlyingProgressBar bar = new FlyingProgressBar(ctx);
            root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            downloadBar = bar;

            TextView pct = new TextView(ctx);
            pct.setText("0%");
            pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            pct.setTypeface(Typeface.DEFAULT_BOLD);
            pct.setGravity(Gravity.CENTER);
            pct.setPadding(0, dp(8), 0, 0);
            GradientText.apply(pct);
            root.addView(pct);
            downloadPct = pct;

            bar.setProgressListener(p -> { if (downloadPct != null) downloadPct.setText(p + "%"); });

            try {
                downloadDialog = new AlertDialog.Builder(ctx)
                        .setTitle("下载音频")
                        .setView(root)
                        .setCancelable(false)
                        .create();
                downloadDialog.show();
            } catch (Throwable ignored) {}
        }

        private void dismissDownloadProgress() {
            try {
                if (downloadDialog != null && downloadDialog.isShowing()) downloadDialog.dismiss();
            } catch (Throwable ignored) {}
            downloadDialog = null;
            downloadBar = null;
            downloadPct = null;
        }

        private void sendToTargets(final KuwoMusicApi.Song s) {
            final ClassLoader cl = ContextManager.getClassLoader();
            if (cl == null) {
                M3Page.toastError(ctx, "模块未初始化，无法发送");
                return;
            }
            ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_FRIEND,
                    (selected, display) -> {
                        if (selected == null || selected.isEmpty()) return;
                        DianGeService.sendSongToTargets(cl, s, new ArrayList<>(selected));
                        M3Page.toast(ctx, "正在发送到 " + selected.size() + " 个会话…");
                    });
        }

        // ==================== 通用 ====================

        private <T> void loadList(final LinearLayout host, final Callable<List<T>> task,
                                  final ListRenderer<T> render) {
            host.removeAllViews();
            host.addView(M3Page.note(ctx, "加载中…"));
            bg(() -> {
                try {
                    final List<T> data = task.call();
                    ui(() -> {
                        host.removeAllViews();
                        if (data == null || data.isEmpty()) {
                            host.addView(M3Page.empty(ctx, "\uD83C\uDFB5", "暂无结果"));
                            return;
                        }
                        render.render(host, data);
                    });
                } catch (final Throwable t) {
                    ui(() -> {
                        host.removeAllViews();
                        host.addView(M3Page.note(ctx, "加载失败：" + msg(t)));
                    });
                }
            });
        }

        private MusicIconView icon(String name, String desc, final Runnable cb) {
            MusicIconView ic = new MusicIconView(ctx, name);
            ic.setSolid(true);
            ic.setIconSizeDp(28);
            ic.setPadding(dp(10), dp(10), dp(10), dp(10));
            ic.setContentDescription(desc);
            ic.setOnClickListener(v -> {
                try { cb.run(); } catch (Throwable ignored) {}
            });
            return ic;
        }

        private TextView actionText(String label, final Runnable cb) {
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(AppColors.primary());
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setOnClickListener(v -> {
                try { cb.run(); } catch (Throwable ignored) {}
            });
            return tv;
        }

        private String fmtCount(long n) {
            if (n >= 100000000L) return String.format(java.util.Locale.CHINA, "%.1f亿", n / 100000000.0);
            if (n >= 10000L) return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000.0);
            return String.valueOf(n);
        }

        private String msg(Throwable t) {
            String m = t == null ? null : t.getMessage();
            return (m == null || m.isEmpty()) ? String.valueOf(t) : m;
        }

        private void bg(Runnable r) {
            new Thread(r, "OnlineMusic").start();
        }

        private void ui(Runnable r) {
            // v1105: 页面关闭后仍需在后台推进播放(下一首), 统一投递到主线程, 不再依赖 Activity
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                new Handler(Looper.getMainLooper()).post(r);
            }
        }

        private int dp(float v) {
            return (int) (v * d + 0.5f);
        }
    }

    private interface ValueCallback {
        void onValue(String value);
    }
}
