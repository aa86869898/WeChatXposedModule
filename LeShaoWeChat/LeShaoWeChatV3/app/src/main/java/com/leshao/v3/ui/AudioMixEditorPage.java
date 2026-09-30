package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ChatFooterLongPressMenu;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.SegmentedControl;
import com.leshao.v3.wm.utils.WmPrefs;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v30008 音频拼接 / 混合编辑页。
 *
 * <p>核心交互（唯一剪辑面 = 时间线）：
 * <ul>
 *   <li>时间线可视化所有片段；拖动片段主体可调整顺序(拼接)或起始时间(混合)，播放时显示实时播放头。</li>
 *   <li>选中片段后，时间线上该片段显示波形与两端手柄：左右拖动左/右手柄即可设置起点/终点区间，
 *       单指在时间线上左右拖动即可剪辑；<b>双指捏合可放大时间线</b>用于精确定位起始/终点。</li>
 *   <li>片段列表卡片行：序号 + 名称 + 来源/时长/起始/已剪辑，行内 试听 / 剪辑 / 删除。</li>
 *   <li>三种来源：本地音频文件、文字转语音、麦克风录音。</li>
 *   <li>两种合成：拼接(顺序相接) / 混合(按起始时间多轨叠加 + 每段音量，峰值自动归一化)。</li>
 * </ul>
 *
 * <p>PCM 统一 24kHz / mono / 16bit，复用 {@link TtsVoiceSender} 的解码、合成、编码与发送能力。</p>
 */
public final class AudioMixEditorPage {

    private static final String TAG = "LsAudioMix";

    public static final int TYPE_FILE = 0;
    public static final int TYPE_TTS = 1;
    public static final int TYPE_REC = 2;

    private static final int MODE_STITCH = 0;
    private static final int MODE_MIX = 1;

    private static final int SAMPLE_RATE = 24000;
    private static final int BYTES_PER_MS = SAMPLE_RATE * 2 / 1000; // 48
    private static final int ENV_COLUMNS = 1800;
    private static final int REC_SAMPLE_RATE = 24000;

    private static final int MIN_SEG_MS = 80;        // 片段 / 区间最小长度
    private static final int PREVIEW_TAIL_MS = 3000; // 拖左手柄: 从新起点起预览的时长
    private static final int PREVIEW_LEAD_MS = 3000; // 拖右手柄: 从新终点前 3s 预览到终点

    // ==================== 片段模型 ====================

    static final class Seg {
        String name;
        int type;
        byte[] pcm;
        int durMs;
        int startMs = 0;
        int endMs = 0;          // 0 => 到末尾
        float gain = 1f;
        int offsetMs = 0;       // 混合模式起始时间
        float[] env;            // 波形包络(有效值RMS, 归一化) 0..1 —— 内层亮芯, 反映大小声
        float[] envPeak;        // 波形包络(峰值, 归一化) 0..1 —— 外层暗轮廓

        Seg(String name, int type, byte[] pcm) {
            this.name = name;
            this.type = type;
            this.pcm = pcm;
            this.durMs = TtsVoiceSender.pcmDurationMs(pcm);
            this.endMs = this.durMs;
        }

        int effStart() {
            if (startMs < 0) return 0;
            return startMs > durMs ? durMs : startMs;
        }

        int effEnd() {
            int e = (endMs <= 0) ? durMs : endMs;
            if (e > durMs) e = durMs;
            int s = effStart();
            return e < s ? s : e;
        }

        int effDur() {
            return Math.max(0, effEnd() - effStart());
        }

        boolean trimmed() {
            return effStart() > 0 || effEnd() < durMs;
        }

        byte[] effective() {
            if (!trimmed()) return pcm;
            return cutPcmMs(pcm, effStart(), effEnd());
        }

        String typeLabel() {
            switch (type) {
                case TYPE_TTS: return "文字转语音";
                case TYPE_REC: return "录音";
                default: return "音频文件";
            }
        }
    }

    // ==================== 状态与视图 ====================

    private final Context ctx;
    private final String talker;
    private final List<Seg> segs = new ArrayList<>();

    private Dialog dialog;
    private SegmentedControl modeCtrl;
    private TimelineView timeline;
    private LinearLayout listCard;
    private LinearLayout editorHost;
    private TextView emptyHint;
    private TextView exportBtn;
    private TextView playAllBtn;

    private int mode = MODE_STITCH;
    private int selected = -1;

    private Player globalPlayer;
    private Player editPlayer;

    private TextView cursorValueLabel;      // 实时显示剪辑游标位置(ms)
    private TextView playIconBtn;           // 按钮区上方的播放图标
    private int timelineHeightDp = -1;      // 时间线高度缓存(避免重复 relayout)
    private int lastPreviewFrom = Integer.MIN_VALUE; // 手柄拖拽预览的节流基准

    /** 单次改动的快照(记录片段顺序与每段的剪辑/音量/起始), 用于撤销。 */
    private static final class SegState {
        final Seg seg;
        final int startMs, endMs, offsetMs;
        final float gain;
        SegState(Seg s) {
            this.seg = s;
            this.startMs = s.startMs;
            this.endMs = s.endMs;
            this.offsetMs = s.offsetMs;
            this.gain = s.gain;
        }
    }

    private final List<List<SegState>> undoStack = new ArrayList<>();
    private List<SegState> pendingUndo;
    private static final int UNDO_MAX = 60;

    private AudioRecord recorder;
    private volatile boolean recording = false;
    private ByteArrayOutputStream recBuffer;
    private volatile boolean exporting = false;        // 防止连点重复发送
    private volatile boolean preparingPreview = false; // 防止连点重复构建试听

    private final Handler ui = new Handler(Looper.getMainLooper());

    private AudioMixEditorPage(Context ctx, String talker) {
        this.ctx = ctx;
        this.talker = talker;
    }

    public static void show(Context ctx, String talker) {
        try {
            new AudioMixEditorPage(ctx, talker).showInternal();
        } catch (Throwable t) {
            LogWriter.log(TAG, "show err: " + android.util.Log.getStackTraceString(t));
            Toast.makeText(ctx, "打开编辑页失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 页面骨架 ====================

    private void showInternal() {
        globalPlayer = new Player(this::onGlobalTick, this::onGlobalFinished);
        editPlayer = new Player(this::onEditTick, this::onEditFinished);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        int m = dp(14);
        root.setPadding(m, dp(10), m, dp(10));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));

        modeCtrl = new SegmentedControl(ctx, new String[]{"拼接", "混合"}, 0);
        modeCtrl.setOnSegmentChangedListener((idx, label) -> {
            mode = idx;
            if (selected >= 0 && selected < segs.size()) {
                // 保持选中有效性
            }
            rebuildTimeline();
            rebuildEditor();
            rebuildList();
        });
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-1, -2);
        modeLp.setMargins(dp(2), dp(8), dp(2), dp(8));
        root.addView(modeCtrl, modeLp);

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        content.addView(hint("时间线: 点/拖波形定位剪辑游标; 双指捏合放大波形精确剪辑; 左右滑动波形可移动时间; 长按某段可直接删除; 剪辑按钮在波形下方"));
        content.addView(M3Page.spacer(ctx, 6));

        timeline = new TimelineView(ctx);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(-1, dp(130));
        tlLp.setMargins(0, 0, 0, dp(6));
        content.addView(timeline, tlLp);

        editorHost = new LinearLayout(ctx);
        editorHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(editorHost);

        content.addView(M3Page.section(ctx, "片段列表", "点「剪辑」后在时间线上左右拖动两端手柄剪辑 / 行内试听、删除"));

        listCard = M3Page.card(ctx);
        content.addView(listCard);

        emptyHint = M3Page.note(ctx, "还没有片段。用下方「选择音频 / 文字转语音 / 录制录音」添加。");
        content.addView(emptyHint);

        content.addView(buildAddRow());

        root.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));

        dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setOnDismissListener(d -> {
            if (globalPlayer != null) globalPlayer.release();
            if (editPlayer != null) editPlayer.release();
            stopRecording(true);
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        rebuildTimeline();
        rebuildEditor();
        rebuildList();
        dialog.show();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(ctx);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(AppColors.onSurface());
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(6), 0, dp(6), 0);
        back.setOnClickListener(v -> dialog.dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(ctx);
        title.setText("音频拼接与混合");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(AppColors.onSurface());
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(title);

        TextView send = new TextView(ctx);
        send.setText("发送");
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setTextColor(AppColors.primary());
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(10), dp(6), dp(6), dp(6));
        send.setOnClickListener(v -> doExport());
        bar.addView(send);
        return bar;
    }

    private View buildAddRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(4));
        row.setLayoutParams(lp);

        row.addView(smallButton("选择音频", this::onAddFile), weight());
        row.addView(smallButton("文字转语音", this::onAddTts), weight());
        row.addView(smallButton("录制录音", this::onAddRec), weight());
        return row;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, 0);

        playAllBtn = outlined("试听全部");
        playAllBtn.setOnClickListener(v -> {
            if (globalPlayer.isActive()) { globalPlayer.stop(); return; }
            if (preparingPreview) return;
            if (segs.isEmpty()) { toast("还没有片段"); return; }
            editPlayer.stop();
            preparingPreview = true;
            playAllBtn.setText("正在准备 …");
            new Thread(() -> {
                final byte[] all = buildOutputPcm(); // 拼接/混音放后台, 避免主线程卡顿
                ui.post(() -> {
                    preparingPreview = false;
                    if (all == null || all.length == 0) {
                        playAllBtn.setText("试听全部");
                        toast("还没有可用片段");
                        return;
                    }
                    globalPlayer.play(all, 0, 0, false);
                });
            }, "ls-amix-preview").start();
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp1.rightMargin = dp(8);
        bar.addView(playAllBtn, lp1);

        exportBtn = new TextView(ctx);
        exportBtn.setText("导出并发送");
        exportBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        exportBtn.setTypeface(Typeface.DEFAULT_BOLD);
        exportBtn.setTextColor(AppColors.textOnPrimary());
        exportBtn.setGravity(Gravity.CENTER);
        exportBtn.setBackground(CandyUi.gradientBg(ctx, 16));
        exportBtn.setOnClickListener(v -> doExport());
        bar.addView(exportBtn, new LinearLayout.LayoutParams(0, dp(46), 1.4f));
        return bar;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(AppColors.onSurfaceVariant());
        tv.setLineSpacing(0, 1.2f);
        return tv;
    }

    // ==================== 片段列表（V4 卡片行） ====================

    private void rebuildList() {
        if (listCard == null) return;
        listCard.removeAllViews();
        if (segs.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            return;
        }
        emptyHint.setVisibility(View.GONE);
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) listCard.addView(M3Page.divider(ctx));
            listCard.addView(buildRow(i));
        }
    }

    private View buildRow(final int index) {
        final Seg s = segs.get(index);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(selected == index ? borderBg() : CandyUi.rowPressBg(ctx));

        TextView badge = new TextView(ctx);
        badge.setText(String.valueOf(index + 1));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(AppColors.textOnPrimary());
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(AppColors.primary());
        badge.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(30), dp(30));
        blp.rightMargin = dp(10);
        row.addView(badge, blp);

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = new TextView(ctx);
        name.setText(s.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(AppColors.onSurface());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mid.addView(name);

        TextView sub = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(s.typeLabel()).append(" · ").append(fmtMs(s.effDur()));
        if (s.trimmed()) sb.append(" (原 ").append(fmtMs(s.durMs)).append(")");
        if (mode == MODE_MIX) sb.append(" · 起始 ").append(fmtMs(s.offsetMs));
        if (s.trimmed()) sb.append(" · 已剪辑");
        sub.setText(sb.toString());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setTextColor(AppColors.onSurfaceVariant());
        mid.addView(sub);
        row.addView(mid);

        row.addView(iconButton("试听", v -> {
            globalPlayer.stop();
            if (s.effDur() <= 0) { toast("无可播放内容"); return; }
            // 直接播放原 PCM + 区间, 不复制裁剪(零拷贝, 点击即响)
            editPlayer.play(s.pcm, s.effStart(), s.effEnd(), false);
        }));
        row.addView(iconButton("剪辑", v -> {
            select(index);
            toast("在时间线上左右拖动两端手柄剪辑, 双指捏合可放大");
        }));
        row.addView(dangerButton("删除", v -> deleteSegment(index)));

        final int idx = index;
        row.setOnClickListener(v -> select(idx));
        return row;
    }

    private void select(int index) {
        if (index < 0 || index >= segs.size()) return;
        if (editPlayer != null && editPlayer.isActive()) editPlayer.stop();
        selected = index;
        rebuildList();
        rebuildTimeline();
        if (timeline != null) timeline.centerOn(index);
        rebuildEditor();
    }

    // ==================== 选中片段参数（剪辑指令区，剪辑动作在时间线上完成） ====================

    private void rebuildEditor() {
        if (editorHost == null) return;
        editorHost.removeAllViews();
        if (segs.isEmpty()) return;
        Seg s = (selected >= 0 && selected < segs.size()) ? segs.get(selected) : null;
        if (s == null) return;

        LinearLayout card = M3Page.card(ctx);
        card.addView(M3Page.section(ctx, "选中片段 · 时间线剪辑",
                "点波形任意位置即从该点起播; 分割后每份都是独立选区, 点选区右上角的 × 即可删除; 拖左手柄自动从起点试听、拖右手柄自动试听终点前 3 秒; 双指捏合放大"));

        final TextView rangeLabel = valueLabel("");
        updateRangeLabel(rangeLabel, s);
        card.addView(rangeLabel);

        // 实时显示剪辑游标的精确毫秒位置
        cursorValueLabel = valueLabel("剪辑游标 " + fmtMs3(timeline == null ? s.effStart() : timeline.scrubLocal()));
        card.addView(cursorValueLabel);

        // 按钮区上方居中的播放图标(方便快速预览选中选区)
        playIconBtn = playIconButton();
        card.addView(playIconBtn);

        // 工具按钮(波形下方) · 第 1 行: 循环试听
        LinearLayout tools1 = new LinearLayout(ctx);
        tools1.setOrientation(LinearLayout.HORIZONTAL);
        tools1.setPadding(0, dp(6), 0, 0);
        tools1.addView(smallButton("循环试听", () -> {
            if (s.effDur() <= 0) { toast("区间无效"); return; }
            globalPlayer.stop();
            editPlayer.play(s.pcm, s.effStart(), s.effEnd(), true);
            updatePlayIcon();
        }), weight());
        card.addView(tools1);

        editPlayer.setStateListener(() -> {
            if (timeline != null) timeline.setEditPlayhead(editPlayer.positionMs(), editPlayer.isActive());
            updatePlayIcon();
        });
        updatePlayIcon();
        playIconBtn.setOnClickListener(v -> {
            if (editPlayer.isActive()) {
                editPlayer.stop();
            } else {
                if (s.effDur() <= 0) { toast("区间无效"); return; }
                int from = (timeline == null) ? s.effStart() : Math.max(s.effStart(), timeline.scrubLocal());
                if (s.effEnd() - from < 40) from = s.effStart();
                globalPlayer.stop();
                editPlayer.play(s.pcm, from, s.effEnd(), false);
            }
            updatePlayIcon();
        });

        // 工具按钮 · 第 2 行: 设为起点 / 设为终点 / 在此分割
        LinearLayout tools2 = new LinearLayout(ctx);
        tools2.setOrientation(LinearLayout.HORIZONTAL);
        tools2.setPadding(0, dp(6), 0, 0);

        tools2.addView(smallButton("设为起点", () -> {
            List<SegState> pre = beginEdit();
            int local = (timeline == null) ? s.effStart() : timeline.scrubLocal();
            if (local > s.effEnd() - MIN_SEG_MS) local = s.effEnd() - MIN_SEG_MS;
            if (local < 0) local = 0;
            s.startMs = local;
            if (editPlayer.isActive()) editPlayer.updateRegion(s.effStart(), s.effEnd());
            if (!sameAsSnapshot(pre)) recordUndo(pre);
            afterTrimEdit();
        }), weight());

        tools2.addView(smallButton("设为终点", () -> {
            List<SegState> pre = beginEdit();
            int local = (timeline == null) ? s.effEnd() : timeline.scrubLocal();
            if (local < s.effStart() + MIN_SEG_MS) local = s.effStart() + MIN_SEG_MS;
            if (local > s.durMs) local = s.durMs;
            s.endMs = local;
            if (editPlayer.isActive()) editPlayer.updateRegion(s.effStart(), s.effEnd());
            if (!sameAsSnapshot(pre)) recordUndo(pre);
            afterTrimEdit();
        }), weight());

        tools2.addView(smallButton("在此分割", this::splitSelected), weight());
        card.addView(tools2);

        // 工具按钮 · 第 3 行: 撤销改动 / 删除此段
        LinearLayout tools4 = new LinearLayout(ctx);
        tools4.setOrientation(LinearLayout.HORIZONTAL);
        tools4.setPadding(0, dp(6), 0, 0);
        tools4.addView(smallButton(undoStack.isEmpty() ? "撤销改动" : "撤销改动 (" + undoStack.size() + ")",
                this::undoLast), weight());
        tools4.addView(smallDangerButton("删除此段", () -> deleteSegment(selected)), weight());
        card.addView(tools4);

        // 混合模式: 音量 + 起始时间
        if (mode == MODE_MIX) {
            final TextView gainVal = valueLabel((int) (s.gain * 100) + "% · 起始 " + fmtMs(s.offsetMs));
            card.addView(fieldRow("音量 / 起始", gainVal));
            SeekBar gainBar = M3Page.slider(ctx);
            gainBar.setMax(200);
            gainBar.setProgress((int) (s.gain * 100));
            gainBar.setOnSeekBarChangeListener(new SimpleSeek() {
                public void onStartTrackingTouch(SeekBar sb) {
                    pendingUndo = snapshot();
                }
                public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                    s.gain = value / 100f;
                    gainVal.setText(value + "% · 起始 " + fmtMs(s.offsetMs));
                }
                public void onStopTrackingTouch(SeekBar sb) {
                    commitPendingUndo();
                    rebuildList();
                }
            });
            card.addView(gainBar);

            SeekBar offBar = M3Page.slider(ctx);
            int maxOff = Math.max(1000, totalMixMs() + 3000);
            offBar.setMax(maxOff);
            offBar.setProgress(Math.min(s.offsetMs, maxOff));
            offBar.setOnSeekBarChangeListener(new SimpleSeek() {
                public void onStartTrackingTouch(SeekBar sb) {
                    pendingUndo = snapshot();
                }
                public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                    s.offsetMs = value;
                    gainVal.setText((int) (s.gain * 100) + "% · 起始 " + fmtMs(s.offsetMs));
                    rebuildTimeline();
                }
                public void onStopTrackingTouch(SeekBar sb) {
                    commitPendingUndo();
                    rebuildList();
                }
            });
            card.addView(offBar);
        }

        editorHost.addView(card);
    }

    private void updateRangeLabel(TextView tv, Seg s) {
        tv.setText("起点 " + fmtMs3(s.effStart()) + "   终点 " + fmtMs3(s.effEnd())
                + "   保留 " + fmtMs3(s.effDur()));
    }

    private int totalMixMs() {
        int total = 0;
        for (Seg s : segs) total = Math.max(total, s.offsetMs + s.effDur());
        return total;
    }

    /** 剪辑参数变化后统一刷新列表 / 时间线 / 工具区。 */
    private void afterTrimEdit() {
        rebuildList();
        rebuildTimeline();
        rebuildEditor();
    }

    /**
     * 拖动起点/终点手柄时的自动试听(边拖边听, 便于精准卡点)。
     *
     * <ul>
     *   <li><b>左手柄</b>: 从新起点开始播放一小段(最长 {@link #PREVIEW_TAIL_MS})，听清起点落点。</li>
     *   <li><b>右手柄</b>: 从新终点<b>前 3 秒</b>播放到终点，听清终点前后的收尾。</li>
     * </ul>
     * 拖拽中按 60ms 粒度节流 seek，避免频繁 flush 造成卡顿。
     */
    private void previewHandleBoundary(Seg s, boolean left) {
        if (s == null || s.effDur() <= 0) return;
        int from, to;
        if (left) {
            from = s.effStart();
            to = Math.min(s.effEnd(), from + PREVIEW_TAIL_MS);
        } else {
            to = s.effEnd();
            from = Math.max(s.effStart(), to - PREVIEW_LEAD_MS);
        }
        if (to - from < 60) return;
        if (globalPlayer.isActive()) globalPlayer.stop();
        if (editPlayer.isActive()) {
            editPlayer.updateRegion(from, to);
            if (Math.abs(from - lastPreviewFrom) >= 60) {
                editPlayer.seek(from);
                lastPreviewFrom = from;
            }
        } else {
            editPlayer.play(s.pcm, from, to, false);
            lastPreviewFrom = from;
        }
    }

    /** 停止手柄拖拽预览。 */
    private void stopHandlePreview() {
        lastPreviewFrom = Integer.MIN_VALUE;
        if (editPlayer != null && editPlayer.isActive()) editPlayer.stop();
    }

    /** 剪辑游标移动时刷新 UI 上的精确毫秒显示。 */
    private void onScrubMoved(int localMs) {
        if (cursorValueLabel != null) cursorValueLabel.setText("剪辑游标 " + fmtMs3(localMs));
    }

    // ==================== 撤销 ====================

    /** 当前片段列表(顺序 + 每段剪辑参数)的快照。 */
    private List<SegState> snapshot() {
        List<SegState> st = new ArrayList<>(segs.size());
        for (Seg s : segs) st.add(new SegState(s));
        return st;
    }

    /** 当前状态是否与快照一致(用于判断一次手势是否真的改动了内容)。 */
    private boolean sameAsSnapshot(List<SegState> st) {
        if (st == null || st.size() != segs.size()) return false;
        for (int i = 0; i < st.size(); i++) {
            SegState a = st.get(i);
            Seg b = segs.get(i);
            if (a.seg != b) return false;
            if (a.startMs != b.startMs || a.endMs != b.endMs || a.offsetMs != b.offsetMs) return false;
            if (Math.abs(a.gain - b.gain) > 1e-6f) return false;
        }
        return true;
    }

    /** 记录一步可撤销的改动(传入改动前快照)。 */
    private void recordUndo(List<SegState> pre) {
        if (pre == null) return;
        undoStack.add(pre);
        if (undoStack.size() > UNDO_MAX) undoStack.remove(0);
    }

    /** 在改动前调用: 返回改动前快照。 */
    private List<SegState> beginEdit() {
        return snapshot();
    }

    /** 撤销上一步改动。 */
    private void undoLast() {
        if (undoStack.isEmpty()) { toast("没有可撤销的改动"); return; }
        List<SegState> st = undoStack.remove(undoStack.size() - 1);
        if (editPlayer.isActive()) editPlayer.stop();
        segs.clear();
        for (SegState ss : st) {
            ss.seg.startMs = ss.startMs;
            ss.seg.endMs = ss.endMs;
            ss.seg.offsetMs = ss.offsetMs;
            ss.seg.gain = ss.gain;
            segs.add(ss.seg);
        }
        if (segs.isEmpty()) selected = -1;
        else if (selected >= segs.size()) selected = segs.size() - 1;
        rebuildList();
        rebuildTimeline();
        rebuildEditor();
        toast("已撤销上一步改动");
    }

    /** 一次拖拽/滑动结束后, 若确有改动则记入撤销栈。 */
    private void commitPendingUndo() {
        if (pendingUndo != null && !sameAsSnapshot(pendingUndo)) recordUndo(pendingUndo);
        pendingUndo = null;
    }

    /** 在选中片段的游标处一分为二(仅在保留区间内切, 边界精确到毫秒)。 */
    private void splitSelected() {
        if (selected < 0 || selected >= segs.size()) { toast("请先选中片段"); return; }
        Seg s = segs.get(selected);
        int start = s.effStart();
        int end = s.effEnd();
        if (end - start < MIN_SEG_MS * 2) { toast("片段太短, 无法分割"); return; }
        List<SegState> pre = beginEdit();
        int local = (timeline == null) ? (start + end) / 2 : timeline.scrubLocal();
        if (local < start + MIN_SEG_MS) local = start + MIN_SEG_MS;
        if (local > end - MIN_SEG_MS) local = end - MIN_SEG_MS;
        if (editPlayer.isActive()) editPlayer.stop();
        byte[] a = cutPcmMs(s.pcm, start, local);
        byte[] b = cutPcmMs(s.pcm, local, end);
        if (a.length == 0 || b.length == 0) { toast("分割失败"); return; }
        Seg s1 = new Seg(s.name + "·A", s.type, a);
        s1.gain = s.gain;
        s1.offsetMs = s.offsetMs;
        Seg s2 = new Seg(s.name + "·B", s.type, b);
        s2.gain = s.gain;
        s2.offsetMs = (mode == MODE_MIX) ? s.offsetMs + (local - start) : 0;
        // 直接切片父包络(O(1)), 免去两次全量扫描导致的主线程卡顿; 父包络缺失时兜底重算
        if (s.env != null && s.env.length > 0 && s.envPeak != null && s.envPeak.length > 0) {
            s1.env = sliceEnv(s.env, start, local, s.durMs);
            s1.envPeak = sliceEnv(s.envPeak, start, local, s.durMs);
            s2.env = sliceEnv(s.env, local, end, s.durMs);
            s2.envPeak = sliceEnv(s.envPeak, local, end, s.durMs);
        } else {
            fillEnvs(s1);
            fillEnvs(s2);
        }
        int idx = selected;
        segs.set(idx, s1);
        segs.add(idx + 1, s2);
        selected = idx + 1;
        recordUndo(pre);
        rebuildList();
        rebuildTimeline();
        rebuildEditor();
        toast("已在 " + fmtMs3(local) + " 分割为两段");
    }

    /** 按身份删除某个片段(即使列表顺序已变化也能删对)。 */
    private void deleteSegmentObject(Seg target) {
        int index = segs.indexOf(target);
        if (index < 0) return;
        deleteSegment(index);
    }

    /** 删除指定片段并修正选中索引。 */
    private void deleteSegment(int index) {
        if (index < 0 || index >= segs.size()) return;
        List<SegState> pre = beginEdit();
        if (editPlayer.isActive()) editPlayer.stop();
        String who = segs.get(index).name;
        segs.remove(index);
        if (segs.isEmpty()) {
            selected = -1;
        } else {
            // 删除后自动选中相邻片段, 保持编辑上下文(而不是清空工具区)
            if (selected == index) selected = Math.min(index, segs.size() - 1);
            else if (selected > index) selected--;
            else if (selected < 0) selected = Math.min(index, segs.size() - 1);
            if (selected >= segs.size()) selected = segs.size() - 1;
            if (selected < 0) selected = 0;
        }
        recordUndo(pre);
        rebuildList();
        rebuildTimeline();
        rebuildEditor();
        toast("已删除第 " + (index + 1) + " 段: " + who + " (剩 " + segs.size() + " 段)");
    }

    // ==================== 全局时间线（唯一剪辑面） ====================

    private void rebuildTimeline() {
        if (timeline == null) return;
        timeline.setData(segs, mode, selected);
        int h = (mode == MODE_MIX) ? 170 : 130;
        if (h != timelineHeightDp) {
            timelineHeightDp = h;
            timeline.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(h)));
        }
        timeline.invalidate();
    }

    private final class TimelineView extends View {
        private List<Seg> data = new ArrayList<>();
        private int vMode = MODE_STITCH;
        private int vSel = -1;
        private int playheadMs = -1;   // 全局时间线播放头
        private int editPosMs = -1;    // 选中片段内的试听播放头(片段内 ms)
        private boolean editActive = false;

        private float zoom = 1f;       // 1x..MAX_ZOOM
        private float scrollMs = 0f;   // 视口左边界(时间线 ms)

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private static final int NONE = 0, BODY = 1, TRIM_L = 2, TRIM_R = 3, PAN = 4, SCRUB = 5;
        private static final float MAX_ZOOM = 12f;
        private int touchMode = NONE;
        private int dragIndex = -1;
        private int downHit = -1;
        private float downX;
        private float downY;
        private int dragOrigOffset;
        private float dragSpanMs = 0f;   // 按下瞬间的视口跨度, 固定整个拖拽过程避免缩放反馈抖动
        private boolean moved;

        private boolean pinching = false;
        private float pinchStartDist = 1f;
        private float pinchStartZoom = 1f;
        private float pinchFocusX = 0f;
        private float pinchFocusMs = 0f;

        // 选中片段的剪辑游标（片段内 ms）；-1 = 未设置(默认取起点)
        private int scrubMs = -1;

        // ===== 布局缓存(避免 onDraw/hitTest 里 O(n^2) 重算) =====
        private boolean layoutDirty = true;
        private int[] cumStart = new int[0];   // STITCH: 每段起始; MIX: 每段 offset
        private int cachedTotalMs = 1;

        // ===== 绘制缓存(避免每帧分配 Shader/Path) =====
        private int shaderW = -1;
        private Shader shDim, shDimSel, shBright, shBorder;
        private int vgTop = -1, vgBot = -1;
        private Shader vGrad;
        private final android.graphics.Path cursorTri = new android.graphics.Path();

        TimelineView(Context c) {
            super(c);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void setData(List<Seg> segs, int mode, int sel) {
            if (sel != vSel) scrubMs = -1;
            this.data = new ArrayList<>(segs);
            this.vMode = mode;
            this.vSel = sel;
            layoutDirty = true;
            clampScroll();
            invalidate();
        }

        /** 片段时长/顺序/起始发生变化时调用, 让布局缓存失效。 */
        void markLayoutDirty() {
            layoutDirty = true;
            clampScroll();
        }

        private void ensureLayout() {
            if (!layoutDirty) return;
            layoutDirty = false;
            int n = data.size();
            if (cumStart.length < n + 1) cumStart = new int[n + 1];
            int total = 1;
            if (vMode == MODE_STITCH) {
                cumStart[0] = 0;
                for (int i = 0; i < n; i++) {
                    cumStart[i + 1] = cumStart[i] + Math.max(1, data.get(i).durMs);
                }
                total = n > 0 ? cumStart[n] : 1;
            } else {
                for (int i = 0; i < n; i++) {
                    int st = Math.max(0, data.get(i).offsetMs);
                    cumStart[i] = st;
                    int end = st + Math.max(1, data.get(i).durMs);
                    if (end > total) total = end;
                }
            }
            cachedTotalMs = Math.max(1, total);
        }

        /** 当前选中片段的剪辑游标（片段内 ms，未设置时返回起点）。 */
        int scrubLocal() {
            if (vSel < 0 || vSel >= data.size()) return 0;
            Seg s = data.get(vSel);
            int d = Math.max(1, s.durMs);
            int v = (scrubMs < 0) ? s.effStart() : scrubMs;
            if (v < 0) v = 0;
            if (v > d) v = d;
            return v;
        }

        void setPlayhead(int ms) {
            this.playheadMs = ms;
            invalidate();
        }

        void setEditPlayhead(int localMs, boolean active) {
            this.editPosMs = localMs;
            this.editActive = active;
            if (active && localMs >= 0) {
                this.scrubMs = localMs;
                onScrubMoved(localMs);
            }
            invalidate();
        }

        void centerOn(int i) {
            if (i < 0 || i >= data.size()) return;
            if (getWidth() <= 0) return;
            int[] g = blockGeom(i);
            float span = viewMsSpan();
            scrollMs = (g[0] + g[1] / 2f) - span / 2f;
            clampScroll();
            invalidate();
        }

        private int padPx() { return dp(10); }

        private int totalMs() {
            ensureLayout();
            return cachedTotalMs;
        }

        private float viewMsSpan() {
            return Math.max(1f, totalMs() / Math.max(1f, zoom));
        }

        private void clampScroll() {
            float maxScroll = Math.max(0f, totalMs() - viewMsSpan());
            if (scrollMs < 0f) scrollMs = 0f;
            if (scrollMs > maxScroll) scrollMs = maxScroll;
        }

        /** 片段在时间线坐标系中的 {起始ms, 时长ms}（按完整时长布局，剪辑手柄在块内滑动）。 */
        private int[] blockGeom(int i) {
            ensureLayout();
            int dur = Math.max(1, data.get(i).durMs);
            if (vMode == MODE_STITCH) return new int[]{cumStart[i], dur};
            return new int[]{Math.max(0, data.get(i).offsetMs), dur};
        }

        private float xOf(float timelineMs) {
            int pad = padPx();
            int avail = Math.max(1, getWidth() - pad * 2);
            return pad + (timelineMs - scrollMs) / viewMsSpan() * avail;
        }

        private float msOf(float x) {
            int pad = padPx();
            int avail = Math.max(1, getWidth() - pad * 2);
            return scrollMs + (x - pad) / avail * viewMsSpan();
        }

        private int rulerH() { return dp(20); }

        private int laneTop(int lane, int laneH, int laneGap) {
            return rulerH() + dp(5) + lane * (laneH + laneGap);
        }

        private int laneCount() {
            return (vMode == MODE_MIX) ? Math.min(3, Math.max(1, data.size())) : 1;
        }

        private int laneHeight(int laneGap) {
            int h = getHeight();
            int lanes = laneCount();
            return (h - rulerH() - dp(5) - dp(6) - laneGap * (lanes - 1)) / lanes;
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            ensureShaders(w);

            // ===== 流光录音棚：深色渐变面板 =====
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    0xFF241E31, 0xFF2E2640, Shader.TileMode.CLAMP));
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, dp(14), dp(14), paint);
            paint.setShader(null);

            if (data.isEmpty()) {
                textPaint.setColor(0xFFA896C9);
                textPaint.setTextSize(dp(12));
                textPaint.setTextAlign(Paint.Align.CENTER);
                cv.drawText("添加片段后可在此查看轨道并剪辑", w / 2f, h / 2f, textPaint);
                return;
            }

            int pad = padPx();
            int lanes = laneCount();
            int laneGap = dp(6);
            int laneH = laneHeight(laneGap);
            if (laneH <= 0) return;
            int availW = w - pad * 2;
            float span = viewMsSpan();
            int rh = rulerH();

            // 顶部标尺条（深色，下方直角）
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, 0, rh,
                    0xFF211B2C, 0xFF1B1626, Shader.TileMode.CLAMP));
            rect.set(0, 0, w, rh);
            cv.drawRoundRect(rect, dp(14), dp(14), paint);
            cv.drawRect(0, rh / 2f, w, rh, paint);
            paint.setShader(null);
            drawRuler(cv, pad, availW);

            // 竖向网格
            drawGrid(cv, availW, rh, h);

            // 片段波形
            for (int i = 0; i < data.size(); i++) {
                Seg s = data.get(i);
                int[] g = blockGeom(i);
                int lane = (vMode == MODE_MIX) ? (i % lanes) : 0;
                int top = laneTop(lane, laneH, laneGap);
                float x = xOf(g[0]);
                float rw = Math.max(dp(2), g[1] / span * availW);
                float inX = xOf(g[0] + s.effStart());
                float outX = xOf(g[0] + s.effEnd());
                drawBlock(cv, s, x, rw, top, laneH, inX, outX, i == vSel);
                if (rw > dp(44)) {
                    textPaint.setColor(0xFFEDE7FF);
                    textPaint.setTextSize(dp(9));
                    textPaint.setTextAlign(Paint.Align.LEFT);
                    cv.drawText(s.name, x + dp(5), top + dp(10), textPaint);
                }
            }

            // 全局播放头
            if (playheadMs >= 0) {
                drawVLine(cv, xOf(playheadMs), rh, h - dp(2), 0xFFFFAFCC);
            }
            // 选中片段的剪辑游标
            if (vSel >= 0 && vSel < data.size()) {
                int[] g = blockGeom(vSel);
                float cx = xOf(g[0] + scrubLocal());
                drawVLine(cv, cx, rh, h - dp(2), editActive ? 0xFFFFE08A : 0xFFFFC2D8);
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(editActive ? 0xFFFFE08A : 0xFFFFC2D8);
                cursorTri.reset();
                cursorTri.moveTo(cx - dp(5), rh + dp(1));
                cursorTri.lineTo(cx + dp(5), rh + dp(1));
                cursorTri.lineTo(cx, rh + dp(7));
                cursorTri.close();
                cv.drawPath(cursorTri, paint);
            }
        }

        /** 主刻度间隔(ms): 标尺标签与竖向网格共用, 保证时间线与波形竖柱严格对齐。 */
        private float majorInterval() {
            float span = viewMsSpan();
            float availW = Math.max(1, getWidth() - padPx() * 2);
            float pxPerMs = availW / span;
            float[] steps = {100, 200, 500, 1000, 2000, 5000, 10000, 20000, 30000, 60000};
            for (float st : steps) if (st * pxPerMs >= dp(52)) return st;
            return steps[steps.length - 1];
        }

        private void drawRuler(Canvas cv, int pad, int availW) {
            float span = viewMsSpan();
            float interval = majorInterval();
            long first = (long) (Math.floor(scrollMs / interval) * interval);
            textPaint.setTextSize(dp(9));
            textPaint.setColor(0xFFA896C9);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(0xFFA896C9);
            for (float t = first; t <= scrollMs + span + 1; t += interval) {
                float x = xOf(t);
                if (x < pad - dp(2) || x > getWidth() - pad + dp(2)) continue;
                cv.drawRect(x, dp(3), x + dp(1.5f), rulerH() - dp(1), paint);
                textPaint.setTextAlign(Paint.Align.LEFT);
                cv.drawText(fmtMs((int) t), x + dp(4), dp(12), textPaint);
            }
            if (zoom > 1.01f) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setColor(0xFFDCC3FF);
                cv.drawText(String.format(java.util.Locale.US, "%.1fx", zoom),
                        getWidth() - pad, dp(12), textPaint);
            }
        }

        private void drawGrid(Canvas cv, int availW, int top, int bottom) {
            float span = viewMsSpan();
            float major = majorInterval();
            float minor = major / 4f;
            long firstMin = (long) (Math.floor(scrollMs / minor) * minor);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(0x10C4B5FD);
            for (float t = firstMin; t <= scrollMs + span + 1; t += minor) {
                float x = xOf(t);
                if (x < 0 || x > getWidth()) continue;
                cv.drawRect(x, top, x + dp(1), bottom, paint);
            }
            long firstMaj = (long) (Math.floor(scrollMs / major) * major);
            paint.setColor(0x30C4B5FD);
            for (float t = firstMaj; t <= scrollMs + span + 1; t += major) {
                float x = xOf(t);
                if (x < 0 || x > getWidth()) continue;
                cv.drawRect(x, top, x + dp(1.5f), bottom, paint);
            }
        }

        private int[] grad(int alpha) {
            return new int[]{
                    withAlpha(AppColors.gradientStart(), alpha),
                    withAlpha(AppColors.gradientMid(), alpha),
                    withAlpha(AppColors.gradientEnd(), alpha)
            };
        }

        private Shader gradShader(int w, int alpha) {
            return new LinearGradient(0, 0, Math.max(1, w), 0, grad(alpha),
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }

        /** 按宽度缓存横向渐变(避免每帧分配 Shader 造成 GC 抖动)。 */
        private void ensureShaders(int w) {
            if (w == shaderW && shDim != null) return;
            shaderW = w;
            shDim = gradShader(w, 65);
            shDimSel = gradShader(w, 80);
            shBright = gradShader(w, 255);
            shBorder = gradShader(w, 230);
            vgTop = -1;
            vgBot = -1;
            vGrad = null;
        }

        /** 缓存纵向渐变(手柄/选区边界用)。 */
        private Shader vGrad(int top, int bottom) {
            if (vGrad == null || vgTop != top || vgBot != bottom) {
                vgTop = top;
                vgBot = bottom;
                vGrad = new LinearGradient(0, top, 0, bottom,
                        AppColors.gradientStart(), AppColors.gradientEnd(), Shader.TileMode.CLAMP);
            }
            return vGrad;
        }

        private void drawBlock(Canvas cv, Seg s, float x, float rw, int top, int laneH,
                float inX, float outX, boolean sel) {
            float midY = top + laneH / 2f;
            float maxH = Math.max(dp(3), laneH - dp(8));

            // 片段底
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(0x26FFFFFF);
            rect.set(x, top, x + rw, top + laneH);
            cv.drawRoundRect(rect, dp(6), dp(6), paint);

            // 双层波形（暗层整块 + 选区亮层）
            float[] pk = (s.envPeak != null && s.envPeak.length > 0) ? s.envPeak : s.env;
            float[] rms = (s.env != null && s.env.length > 0) ? s.env : s.envPeak;
            Shader dim = sel ? shDimSel : shDim;
            waveLayer(cv, pk, x, rw, midY, maxH, 1.0f, 95, dim);
            waveLayer(cv, rms, x, rw, midY, maxH, 0.62f, 150, dim);

            if (sel) {
                // 选区外压暗
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(0x6E0A0612);
                if (inX > x) cv.drawRect(x, top, Math.min(inX, x + rw), top + laneH, paint);
                if (outX < x + rw) cv.drawRect(Math.max(outX, x), top, x + rw, top + laneH, paint);

                // 选区内亮层
                cv.save();
                cv.clipRect(Math.max(x, inX), top, Math.min(x + rw, outX), top + laneH);
                waveLayer(cv, pk, x, rw, midY, maxH, 1.0f, 160, shBright);
                waveLayer(cv, rms, x, rw, midY, maxH, 0.62f, 255, shBright);
                cv.restore();

                // 边框
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setShader(shBorder);
                rect.set(x, top, x + rw, top + laneH);
                cv.drawRoundRect(rect, dp(6), dp(6), paint);
                paint.setShader(null);

                // 选区边界 + 渐变手柄
                drawSelLine(cv, inX, top, top + laneH);
                drawSelLine(cv, outX, top, top + laneH);
                drawHandle(cv, inX, top, top + laneH);
                drawHandle(cv, outX, top, top + laneH);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(0x2E000000);
                rect.set(x, top, x + rw, top + laneH);
                cv.drawRoundRect(rect, dp(6), dp(6), paint);
            }

            // 每份切分出的选区右上角都有删除角标(点一下即可删除该选区, 仅当足够宽时显示)
            if (rw > dp(40)) {
                float cx = x + rw - dp(12), cy = top + dp(12);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(sel ? 0xE6E0455F : 0xB30A0612);
                cv.drawCircle(cx, cy, dp(9), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(0xFFFFFFFF);
                cv.drawLine(cx - dp(3.6f), cy - dp(3.6f), cx + dp(3.6f), cy + dp(3.6f), paint);
                cv.drawLine(cx + dp(3.6f), cy - dp(3.6f), cx - dp(3.6f), cy + dp(3.6f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        private void waveLayer(Canvas cv, float[] env, float x, float rw, float midY,
                float maxH, float k, int alpha, Shader shader) {
            if (rw <= 0) return;
            boolean has = env != null && env.length > 0;
            // 每 ~2dp 一根竖柱, 竖柱数不超过包络列数, 保证采样一一对应, 强弱段不丢失
            int cols = Math.max(1, (int) (rw / dp(2)));
            if (cols > 400) cols = 400;
            float colW = rw / cols;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(shader);
            paint.setAlpha(alpha);
            for (int c = 0; c < cols; c++) {
                float v;
                if (has) {
                    int src = (int) ((long) c * env.length / cols);
                    if (src >= env.length) src = env.length - 1;
                    v = env[src];
                    if (v < 0.04f) v = 0.04f;
                    if (v > 1f) v = 1f;
                } else {
                    v = 0.5f;
                }
                float bh = Math.max(dp(1), v * maxH * k);
                float cx = x + c * colW;
                cv.drawRect(cx, midY - bh / 2f, cx + Math.max(dp(1), colW * 0.68f), midY + bh / 2f, paint);
            }
            paint.setAlpha(255);
            paint.setShader(null);
        }

        private void drawSelLine(Canvas cv, float hx, int top, int bottom) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.4f));
            paint.setShader(vGrad(top, bottom));
            cv.drawLine(hx, top, hx, bottom, paint);
            paint.setShader(null);
        }

        private void drawHandle(Canvas cv, float hx, int top, int bottom) {
            float bw = dp(5);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(vGrad(top, bottom));
            rect.set(hx - bw / 2f, top, hx + bw / 2f, bottom);
            cv.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
            cv.drawCircle(hx, top + dp(7), dp(6), paint);
            cv.drawCircle(hx, bottom - dp(7), dp(6), paint);
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            cv.drawCircle(hx, top + dp(7), dp(2f), paint);
            cv.drawCircle(hx, bottom - dp(7), dp(2f), paint);
        }

        private void drawVLine(Canvas cv, float x, int top, int bottom, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setShader(null);
            paint.setColor(color);
            cv.drawLine(x, top, x, bottom, paint);
        }

        private int hitIndex(float x, float y) {
            int pad = padPx();
            int laneGap = dp(6);
            int laneH = laneHeight(laneGap);
            if (laneH <= 0) return -1;
            int lanes = laneCount();
            float span = viewMsSpan();
            int availW = getWidth() - pad * 2;
            for (int i = 0; i < data.size(); i++) {
                int[] g = blockGeom(i);
                int lane = (vMode == MODE_MIX) ? (i % lanes) : 0;
                int top = laneTop(lane, laneH, laneGap);
                float bx = xOf(g[0]);
                float rw = Math.max(dp(2), g[1] / span * availW);
                if (x >= bx && x <= bx + rw && y >= top && y <= top + laneH) return i;
            }
            return -1;
        }

        private int decideMode(int i, float x) {
            if (i == vSel) {
                int[] g = blockGeom(i);
                Seg s = data.get(i);
                float span = viewMsSpan();
                float rw = g[1] / span * (getWidth() - padPx() * 2);
                if (rw > dp(56)) {
                    float inX = xOf(g[0] + s.effStart());
                    float outX = xOf(g[0] + s.effEnd());
                    float th = dp(24);
                    if (Math.abs(x - inX) <= th) return TRIM_L;
                    if (Math.abs(x - outX) <= th) return TRIM_R;
                }
                return SCRUB;
            }
            return BODY;
        }

        /** 把剪辑游标精确设置到指定片段的指定毫秒处。 */
        void setScrub(int index, int localMs) {
            if (index != vSel || index < 0 || index >= data.size()) return;
            Seg s = data.get(index);
            int v = Math.max(0, Math.min(s.durMs, localMs));
            scrubMs = v;
            if (editPlayer != null && editPlayer.isActive()) editPlayer.seek(v);
            onScrubMoved(v);
            invalidate();
        }

        /** 点按波形: 选中命中的片段, 并把剪辑游标定位到点击处, 立刻从该点开始播放。 */
        private void onTimelineTapSegment(int index, float rawX) {
            if (index < 0 || index >= data.size()) return;
            Seg s = data.get(index);
            int[] g = blockGeom(index);
            int local = (int) (msOf(rawX) - g[0]);
            local = Math.max(0, Math.min(s.durMs, local));
            int from = Math.max(s.effStart(), Math.min(s.effEnd(), local));
            boolean switched = selected != index;
            if (switched) select(index);
            setScrub(index, from);
            if (s.effEnd() - from < 40) from = s.effStart();
            if (s.effEnd() > from) {
                globalPlayer.stop();
                editPlayer.play(s.pcm, from, s.effEnd(), false);
            }
        }

        /** 点按是否命中某片段右上角的删除角标。 */
        private boolean hitDeleteBadge(float x, float y, int i) {
            if (i < 0 || i >= data.size()) return false;
            int[] g = blockGeom(i);
            int pad = padPx();
            int avail = Math.max(1, getWidth() - pad * 2);
            float rw = Math.max(dp(2), g[1] / viewMsSpan() * avail);
            if (rw < dp(40)) return false;
            int lane = (vMode == MODE_MIX) ? (i % laneCount()) : 0;
            int laneH = laneHeight(dp(6));
            int top = laneTop(lane, laneH, dp(6));
            float bx = xOf(g[0]);
            float cx = bx + rw - dp(12), cy = top + dp(12);
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= dp(16) * dp(16);
        }

        private void applyTrim(int i, int mode, float x) {
            Seg s = data.get(i);
            int[] g = blockGeom(i);
            int local = (int) (msOf(x) - g[0]);
            if (mode == TRIM_L) {
                int end = s.effEnd();
                if (local > end - 80) local = end - 80;
                if (local < 0) local = 0;
                s.startMs = local;
            } else {
                int start = s.effStart();
                if (local < start + 80) local = start + 80;
                if (local > s.durMs) local = s.durMs;
                s.endMs = local;
            }
            // 边拖边听: 左手柄从新起点起播, 右手柄从新终点前 3s 播到终点
            previewHandleBoundary(s, mode == TRIM_L);
            invalidate();
        }

        private float dist(MotionEvent e) {
            if (e.getPointerCount() < 2) return 1f;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (data.isEmpty()) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    pinching = false;
                    moved = false;
                    pendingUndo = snapshot();
                    downX = e.getX();
                    downY = e.getY();
                    dragSpanMs = viewMsSpan();
                    int hit = hitIndex(e.getX(), e.getY());
                    dragIndex = hit;
                    downHit = hit;
                    if (hit >= 0) {
                        dragOrigOffset = data.get(hit).offsetMs;
                        touchMode = decideMode(hit, e.getX());
                        if (touchMode == SCRUB) {
                            // 选中片段: 放大后拖动=平移时间, 未放大拖动=调整顺序/起始; 点按=从该点起播
                            touchMode = (zoom > 1.01f) ? PAN : BODY;
                        } else if (touchMode == BODY && zoom > 1.01f) {
                            touchMode = PAN;
                        }
                        if (touchMode == TRIM_L || touchMode == TRIM_R) {
                            previewHandleBoundary(data.get(hit), touchMode == TRIM_L);
                        }
                    } else {
                        touchMode = (zoom > 1.01f) ? PAN : NONE;
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (e.getPointerCount() >= 2) {
                        pinching = true;
                        touchMode = NONE;
                        dragIndex = -1;
                        pinchStartDist = dist(e);
                        pinchStartZoom = zoom;
                        pinchFocusX = (e.getX(0) + e.getX(1)) / 2f;
                        pinchFocusMs = msOf(pinchFocusX);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (pinching && e.getPointerCount() >= 2) {
                        float f = dist(e) / pinchStartDist;
                        zoom = Math.max(1f, Math.min(MAX_ZOOM, pinchStartZoom * f));
                        int pad = padPx();
                        int avail = Math.max(1, getWidth() - pad * 2);
                        scrollMs = pinchFocusMs - (pinchFocusX - pad) / avail * viewMsSpan();
                        clampScroll();
                        invalidate();
                        return true;
                    }
                    if (Math.abs(e.getX() - downX) > dp(4) || Math.abs(e.getY() - downY) > dp(4)) {
                        moved = true;
                    }
                    if (touchMode == PAN) {
                        float dx = e.getX() - downX;
                        if (Math.abs(dx) > dp(2)) { moved = true; }
                        int pad = padPx();
                        int avail = Math.max(1, getWidth() - pad * 2);
                        scrollMs -= dx / avail * viewMsSpan();
                        downX = e.getX();
                        clampScroll();
                        invalidate();
                        return true;
                    }
                    if (dragIndex < 0) return true;
                    if (Math.abs(e.getX() - downX) > dp(4)) { moved = true; }
                    if (!moved) return true;

                    if (touchMode == TRIM_L || touchMode == TRIM_R) {
                        applyTrim(dragIndex, touchMode, e.getX());
                    } else if (touchMode == BODY) {
                        if (vMode == MODE_MIX) {
                            int pad = padPx();
                            int avail = Math.max(1, getWidth() - pad * 2);
                            float deltaMs = (e.getX() - downX) / avail * dragSpanMs;
                            int nv = Math.max(0, dragOrigOffset + (int) deltaMs);
                            data.get(dragIndex).offsetMs = nv;
                            selected = dragIndex;
                            markLayoutDirty();
                            invalidate();
                        } else {
                            int target = orderIndexAt(e.getX());
                            if (target >= 0 && target != dragIndex && target < segs.size()) {
                                Seg mv = segs.remove(dragIndex);
                                segs.add(target, mv);
                                dragIndex = target;
                                selected = target;
                                data = new ArrayList<>(segs);
                                markLayoutDirty();
                                invalidate();
                                rebuildList();
                            }
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (e.getPointerCount() <= 2) pinching = false;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (touchMode == TRIM_L || touchMode == TRIM_R) stopHandlePreview();
                    if (!moved && downHit >= 0 && !pinching) {
                        if (hitDeleteBadge(e.getX(), e.getY(), downHit)) {
                            // 点中某片段的删除角标 -> 直接删除该选区(可撤销)
                            pendingUndo = null;
                            deleteSegmentObject(data.get(downHit));
                        } else {
                            // 点按波形: 命中哪段就选哪段, 并立刻从该时间点起播
                            onTimelineTapSegment(downHit, e.getX());
                        }
                    } else if (moved && dragIndex >= 0 && !pinching) {
                        if (touchMode == TRIM_L || touchMode == TRIM_R) {
                            rebuildList();
                            rebuildTimeline();
                            rebuildEditor();
                        } else if (touchMode == BODY) {
                            rebuildList();
                            rebuildTimeline();
                        }
                    }
                    commitPendingUndo();
                    dragIndex = -1;
                    downHit = -1;
                    touchMode = NONE;
                    pinching = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                default:
                    return true;
            }
        }

        private int orderIndexAt(float x) {
            int pad = padPx();
            float span = viewMsSpan();
            int availW = Math.max(1, getWidth() - pad * 2);
            int cursor = 0;
            for (int i = 0; i < segs.size(); i++) {
                int dur = Math.max(1, segs.get(i).durMs);
                float bx = xOf(cursor);
                float rw = Math.max(dp(2), dur / span * availW);
                if (x < bx + rw / 2f) return i;
                cursor += dur;
            }
            return segs.size() - 1;
        }

    }

    private int barColor(int i) {
        int[] pal = {AppColors.gradientStart(), 0xFF7C3AED, AppColors.gradientEnd(), 0xFFA78BFA};
        return pal[i % pal.length];
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ==================== 播放器 ====================

    /**
     * 低延迟播放器。
     *
     * <p>关键设计（针对点击波形后播放卡顿/延迟/不同步的修复）：
     * <ul>
     *   <li><b>常驻单线程 + 复用 AudioTrack</b>：不再每次点击都新建线程与 AudioTrack，
     *       消除 100~300ms 的设备初始化延迟。</li>
     *   <li><b>代际令牌 gen</b>：每次 play/stop 递增 gen，旧会话在检查到 gen 变化后立即退出，
     *       绝不回写 running/触发 onFinished，杜绝"新播放被旧线程的 finally 掐停"这一竞态。</li>
     *   <li><b>播放头位置</b>：用 {@link AudioTrack#getPlaybackHeadPosition()} 换算当前毫秒，
     *       而不是写指针位置，保证时间线播放头与真实出声严格同步（写指针会超前一个缓冲区）。</li>
     *   <li><b>排空缓冲</b>：自然结束/循环回绕前等待缓冲播完，避免尾部被硬切。</li>
     * </ul>
     */
    private final class Player {
        private final Object lock = new Object();
        private volatile AudioTrack track;        // 常驻复用
        private Thread thread;
        private volatile boolean threadStarted = false;
        private volatile boolean released = false;

        private volatile boolean running = false; // 有正在进行的播放会话
        private volatile boolean paused = false;
        private volatile int posMs = 0;
        private volatile int seekToMs = -1;
        private volatile byte[] data;
        private volatile int regionStartMs = 0;
        private volatile int regionEndMs = 0;
        private volatile boolean loop = false;
        private volatile long gen = 0;

        private Runnable onFinished;
        private Runnable stateListener;
        private final Runnable onTick;

        Player(Runnable onTick, Runnable onFinished) {
            this.onTick = onTick;
            this.onFinished = onFinished;
        }

        void setStateListener(Runnable r) { this.stateListener = r; }

        boolean isActive() { return running; }
        int positionMs() { return posMs; }

        private void ensureThread() {
            if (threadStarted || released) return;
            threadStarted = true;
            thread = new Thread(this::loop, "ls-amix-play");
            thread.start();
        }

        void play(byte[] pcm, int fromMs, int toMs, boolean loop) {
            if (released) return;
            if (pcm == null || pcm.length < 2) { stop(); return; }
            int dur = TtsVoiceSender.pcmDurationMs(pcm);
            synchronized (lock) {
                this.data = pcm;
                this.loop = loop;
                this.regionStartMs = Math.max(0, Math.min(fromMs, dur));
                int to = (toMs <= 0) ? dur : Math.min(toMs, dur);
                if (to <= regionStartMs) to = dur;
                this.regionEndMs = to;
                this.posMs = regionStartMs;
                this.seekToMs = -1;
                this.paused = false;
                this.gen++;
                this.running = true;
                lock.notifyAll();
            }
            ensureThread();
            dispatchState();
        }

        void updateRegion(int startMs, int endMs) {
            regionStartMs = Math.max(0, startMs);
            regionEndMs = Math.max(regionStartMs + 1, endMs);
        }

        void seek(int ms) {
            posMs = Math.max(0, ms);
            seekToMs = posMs;
            if (timeline != null) ui.post(timeline::invalidate);
        }

        void pause() {
            paused = true;
            setTrackPlaying(false);
            dispatchState();
        }

        void resume() {
            if (!running) return;
            paused = false;
            setTrackPlaying(true);
            dispatchState();
        }

        void stop() {
            synchronized (lock) {
                running = false;
                paused = false;
                seekToMs = -1;
                gen++;
                lock.notifyAll();
            }
            AudioTrack t = track;
            if (t != null) {
                try { t.pause(); } catch (Throwable ignored) {}
                try { t.flush(); } catch (Throwable ignored) {}
            }
            dispatchState();
        }

        /** 页面关闭时释放底层资源。 */
        void release() {
            stop();
            released = true;
            AudioTrack t = track;
            track = null;
            if (t != null) {
                try { t.stop(); } catch (Throwable ignored) {}
                try { t.release(); } catch (Throwable ignored) {}
            }
            Thread th = thread;
            if (th != null) th.interrupt();
        }

        private void setTrackPlaying(boolean play) {
            AudioTrack t = track;
            if (t == null) return;
            try { if (play) t.play(); else t.pause(); } catch (Throwable ignored) {}
        }

        private void dispatchState() {
            ui.post(() -> {
                if (stateListener != null) stateListener.run();
                onTick.run();
            });
        }

        private void dispatchFinished() {
            ui.post(() -> { if (onFinished != null) onFinished.run(); });
        }

        private boolean ensureTrack() {
            AudioTrack t = track;
            if (t != null && t.getState() == AudioTrack.STATE_INITIALIZED) return true;
            if (t != null) {
                try { t.release(); } catch (Throwable ignored) {}
                track = null;
            }
            try {
                int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) minBuf = SAMPLE_RATE; // 兜底
                // 低延迟: 约 120ms 缓冲(且不小于系统最小缓冲)
                int bufBytes = Math.max(minBuf, SAMPLE_RATE * 2 * 120 / 1000);
                AudioTrack nt = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (nt.getState() != AudioTrack.STATE_INITIALIZED) {
                    try { nt.release(); } catch (Throwable ignored) {}
                    return false;
                }
                track = nt;
                return true;
            } catch (Throwable ex) {
                LogWriter.log(TAG, "audiotrack init err: " + ex);
                return false;
            }
        }

        private void loop() {
            final int chunk = 2304; // 48ms @24k/16bit
            final byte[] buf = new byte[chunk];
            while (!released) {
              try {
                long myGen;
                synchronized (lock) {
                    while (!running && !released) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (released) return;
                    myGen = gen;
                }
                if (!ensureTrack()) {
                    boolean mine;
                    synchronized (lock) { mine = (gen == myGen); if (mine) { running = false; paused = false; } }
                    if (mine) { dispatchState(); dispatchFinished(); }
                    continue;
                }
                AudioTrack t = track;
                final byte[] d = data;
                int startB = regionStartMs * BYTES_PER_MS;
                int off = startB;
                int sk = seekToMs;
                if (sk >= 0) { off = Math.max(0, sk) * BYTES_PER_MS; seekToMs = -1; }
                int baseMs = off / BYTES_PER_MS;
                posMs = baseMs;
                int writtenFrames = 0;

                // 干净开始: 清空上一会话残留
                try { t.pause(); } catch (Throwable ignored) {}
                try { t.flush(); } catch (Throwable ignored) {}
                try { t.play(); } catch (Throwable ignored) {}

                boolean naturalEnd = false;
                boolean superseded = false;
                int endB = Math.min(regionEndMs * BYTES_PER_MS, d.length);
                if (endB <= startB) endB = d.length;

                while (true) {
                    if (released) return;
                    if (gen != myGen || !running) { superseded = true; break; }
                    if (seekToMs >= 0) {
                        off = Math.max(0, seekToMs) * BYTES_PER_MS;
                        seekToMs = -1;
                        baseMs = off / BYTES_PER_MS;
                        posMs = baseMs;
                        writtenFrames = 0;
                        try { t.pause(); t.flush(); t.play(); } catch (Throwable ignored) {}
                        continue;
                    }
                    if (paused) {
                        try { Thread.sleep(12); } catch (InterruptedException e) { return; }
                        continue;
                    }
                    endB = Math.min(regionEndMs * BYTES_PER_MS, d.length);
                    if (endB <= startB) endB = d.length;
                    if (off < startB || off > endB) off = startB;
                    if (off >= endB) {
                        // 等缓冲播完再决定 循环 / 结束, 避免尾部被硬切
                        drain(t, myGen, writtenFrames, baseMs);
                        if (gen != myGen || !running || released) { superseded = true; break; }
                        if (loop) {
                            off = startB;
                            baseMs = off / BYTES_PER_MS;
                            posMs = baseMs;
                            writtenFrames = 0;
                            try { t.pause(); t.flush(); t.play(); } catch (Throwable ignored) {}
                            continue;
                        }
                        naturalEnd = true;
                        break;
                    }
                    int n = Math.min(buf.length, endB - off);
                    if (n <= 0) { naturalEnd = true; break; }
                    System.arraycopy(d, off, buf, 0, n);
                    int written = t.write(buf, 0, n);
                    if (written <= 0) { naturalEnd = true; break; }
                    off += written;
                    writtenFrames += written / 2;
                    posMs = headPosMs(t, baseMs);
                    ui.post(onTick);
                }

                boolean endedMine;
                synchronized (lock) {
                    endedMine = (gen == myGen);
                    if (endedMine) { running = false; paused = false; }
                }
                if (superseded || !endedMine) continue; // 新会话已接管, 不动它的状态
                try { t.pause(); } catch (Throwable ignored) {}
                try { t.flush(); } catch (Throwable ignored) {}
                dispatchState();
                if (naturalEnd) dispatchFinished();
              } catch (Throwable ex) {
                // 任何底层异常都不允许线程死亡(否则后续播放永久卡死)
                LogWriter.log(TAG, "player loop err: " + ex);
                synchronized (lock) { running = false; paused = false; }
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
              }
            }
        }

        private int headPosMs(AudioTrack t, int baseMs) {
            int head = 0;
            try { head = t.getPlaybackHeadPosition(); } catch (Throwable ignored) {}
            int ms = baseMs + (int) ((long) head * 1000L / SAMPLE_RATE);
            if (ms < baseMs) ms = baseMs;
            int cap = regionEndMs;
            return ms > cap ? cap : ms;
        }

        /** 等待已写入的帧真正播放完毕(最多 ~1.5s), 期间保持播放头刷新。 */
        private void drain(AudioTrack t, long myGen, int writtenFrames, int baseMs) {
            long deadline = System.currentTimeMillis() + 1500;
            while (gen == myGen && running && !released) {
                int head = 0;
                try { head = t.getPlaybackHeadPosition(); } catch (Throwable ignored) {}
                posMs = (baseMs + (int) ((long) head * 1000L / SAMPLE_RATE) > regionEndMs)
                        ? regionEndMs : baseMs + (int) ((long) head * 1000L / SAMPLE_RATE);
                ui.post(onTick);
                if (head >= writtenFrames) break;
                if (System.currentTimeMillis() > deadline) break;
                try { Thread.sleep(12); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void onGlobalTick() {
        if (timeline != null) {
            timeline.setPlayhead(globalPlayer.isActive() ? globalPlayer.positionMs() : -1);
        }
        if (playAllBtn != null) playAllBtn.setText(globalPlayer.isActive() ? "停止试听" : "试听全部");
    }

    private void onGlobalFinished() {
        if (timeline != null) timeline.setPlayhead(-1);
        if (playAllBtn != null) playAllBtn.setText("试听全部");
    }

    private void onEditTick() {
        if (timeline != null) {
            timeline.setEditPlayhead(editPlayer.positionMs(), editPlayer.isActive());
        }
        updatePlayIcon();
    }

    private void onEditFinished() {
        if (timeline != null) timeline.setEditPlayhead(0, false);
        updatePlayIcon();
    }

    // ==================== 添加片段 ====================

    private void onAddFile() {
        Activity act = activityOf();
        if (act == null) { toast("无法打开文件选择器"); return; }
        ChatFooterLongPressMenu.startAudioPick(act, path -> {
            if (path == null || path.isEmpty()) return;
            final String p = path;
            final String nm = new java.io.File(p).getName();
            showBusy("正在解码 " + nm + " …");
            new Thread(() -> {
                final byte[] pcm = TtsVoiceSender.decodeAudioToPcm(p, (cur, total) ->
                        ui.post(() -> setBusyText("正在解码 " + nm + " … " + cur + "%")));
                if (pcm == null || pcm.length == 0) {
                    ui.post(() -> { hideBusy(); toast("解码失败, 请换一个音频文件"); });
                    return;
                }
                ui.post(() -> setBusyText("正在分析波形 …"));
                final Seg s = new Seg(nm, TYPE_FILE, pcm); // 波形分析放后台, 避免主线程卡顿
                fillEnvs(s);
                ui.post(() -> {
                    hideBusy();
                    segs.add(s);
                    rebuildList();
                    rebuildTimeline();
                    select(segs.size() - 1);
                    toast("已添加: " + nm);
                });
            }, "ls-amix-decode").start();
        });
    }

    private void onAddTts() {
        final EditText et = M3Page.input(ctx, "输入要合成的文字");
        et.setSingleLine(false);
        et.setMaxLines(4);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, dp(8), p, dp(4));
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(ctx)
                .setTitle("文字转语音")
                .setView(box)
                .setPositiveButton("生成并加入", (d, w) -> {
                    String text = et.getText() == null ? "" : et.getText().toString().trim();
                    if (text.isEmpty()) { toast("请输入文字"); return; }
                    showBusy("正在合成语音 …");
                    new Thread(() -> {
                        final byte[] pcm = TtsVoiceSender.synthesizeTextToPcm(text);
                        if (pcm == null || pcm.length == 0) {
                            ui.post(() -> { hideBusy(); toast("合成失败, 请重试"); });
                            return;
                        }
                        String n = text.length() > 12 ? text.substring(0, 12) + "…" : text;
                        final Seg s = new Seg(n, TYPE_TTS, pcm); // 波形分析放后台
                        fillEnvs(s);
                        ui.post(() -> {
                            hideBusy();
                            segs.add(s);
                            rebuildList();
                            rebuildTimeline();
                            select(segs.size() - 1);
                            toast("已加入文字转语音");
                        });
                    }, "ls-amix-tts").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void onAddRec() {
        if (recording) { stopRecording(true); return; }
        try {
            int minBuf = AudioRecord.getMinBufferSize(REC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = REC_SAMPLE_RATE;
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, REC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                toast("麦克风不可用");
                recorder.release();
                recorder = null;
                return;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "record init err: " + t);
            toast("无法开始录音(缺少录音权限)");
            return;
        }

        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        recBuffer = buf;

        final TextView time = new TextView(ctx);
        time.setText("00:00");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setTextColor(AppColors.onSurface());
        time.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(16), 0, dp(4));
        box.addView(time);

        final long start = System.currentTimeMillis();
        final AlertDialog recDialog = new AlertDialog.Builder(ctx)
                .setTitle("录制录音")
                .setView(box)
                .setNegativeButton("停止并加入", null)
                .setPositiveButton("取消", null)
                .create();

        recording = true;
        recorder.startRecording();
        final boolean[] saved = {false};
        recDialog.setOnDismissListener(d -> { if (!saved[0]) stopRecording(false); });
        recDialog.show();
        recDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            byte[] pcm = stopRecording(true);
            saved[0] = true;
            recDialog.dismiss();
            if (pcm != null && pcm.length > 0) {
                Seg s = new Seg("录音 " + fmtMs(TtsVoiceSender.pcmDurationMs(pcm)), TYPE_REC, pcm);
                segs.add(s);
                rebuildList();
                rebuildTimeline();
                select(segs.size() - 1);
                toast("已加入录音");
                new Thread(() -> {
                    fillEnvs(s);
                    ui.post(() -> { if (timeline != null) timeline.invalidate(); });
                }, "ls-amix-env").start();
            } else {
                toast("录音为空");
            }
        });
        recDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            saved[0] = true;
            stopRecording(false);
            recDialog.dismiss();
        });

        final int maxRecBytes = REC_SAMPLE_RATE * 2 * 60 * 15; // 约 15 分钟, 防内存无上限增长
        final boolean[] warned = {false};
        new Thread(() -> {
            byte[] chunk = new byte[REC_SAMPLE_RATE];
            while (recording) {
                int n = recorder == null ? -1 : recorder.read(chunk, 0, chunk.length);
                if (n < 0) break;
                if (n > 0) {
                    if (buf.size() < maxRecBytes) buf.write(chunk, 0, n);
                    else if (!warned[0]) {
                        warned[0] = true;
                        ui.post(() -> toast("录音已达 15 分钟上限, 请手动停止"));
                    }
                }
                final long el = System.currentTimeMillis() - start;
                ui.post(() -> time.setText(fmtMs((int) el)));
            }
        }, "ls-amix-rec").start();
    }

    private byte[] stopRecording(boolean keep) {
        byte[] out = null;
        if (recording) {
            recording = false;
            AudioRecord r = recorder;
            recorder = null;
            if (r != null) {
                try { r.stop(); } catch (Throwable ignored) {}
                try { r.release(); } catch (Throwable ignored) {}
            }
        }
        try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        if (keep && recBuffer != null && recBuffer.size() > 0) {
            out = recBuffer.toByteArray();
        }
        recBuffer = null;
        return out;
    }

    // ==================== 导出 ====================

    private byte[] buildOutputPcm() {
        if (segs.isEmpty()) return null;
        if (mode == MODE_STITCH) {
            List<byte[]> parts = new ArrayList<>();
            for (Seg s : segs) {
                byte[] e = s.effective();
                if (e != null && e.length > 0) parts.add(e);
            }
            return TtsVoiceSender.concatPcm(parts);
        }
        List<byte[]> clips = new ArrayList<>();
        List<Integer> offs = new ArrayList<>();
        List<Float> gains = new ArrayList<>();
        int totalSamples = 0;
        for (Seg s : segs) {
            byte[] e = s.effective();
            if (e == null || e.length == 0) continue;
            int samples = e.length / 2;
            int offSamples = (int) ((long) s.offsetMs * SAMPLE_RATE / 1000);
            clips.add(e);
            offs.add(offSamples);
            gains.add(s.gain);
            totalSamples = Math.max(totalSamples, offSamples + samples);
        }
        if (clips.isEmpty()) return null;
        return TtsVoiceSender.mixPcm(clips, offs, gains, totalSamples);
    }

    private void doExport() {
        if (exporting) return;
        if (globalPlayer.isActive()) globalPlayer.stop();
        if (editPlayer.isActive()) editPlayer.stop();
        if (segs.isEmpty()) { toast("还没有可用片段"); return; }
        if (talker == null || talker.isEmpty()) { toast("无法获取当前聊天对象"); return; }
        try {
            String live = ChatFooterLongPressMenu.resolveLiveTalker();
            LogWriter.log(TAG, "doExport talker=" + talker + " liveTalker=" + live
                    + (live != null && !live.equals(talker) ? "  [MISMATCH live!=target]" : ""));
        } catch (Throwable ignored) {}

        final int fakeDurationMs = WmPrefs.getInt("voice_fake_duration_sec", 1) * 1000;
        exporting = true;
        showBusy("正在编码并发送 …");
        new Thread(() -> {
            // 拼接/混音放后台线程, 避免长音频在主线程构建导致 ANR
            final byte[] out = buildOutputPcm();
            if (out == null || out.length == 0) {
                ui.post(() -> { exporting = false; hideBusy(); toast("还没有可用片段"); });
                return;
            }
            boolean ok = TtsVoiceSender.sendRawPcmVoice(talker, out, fakeDurationMs,
                    (cur, total) -> ui.post(() -> setBusyText("正在发送 " + cur + "/" + total + " …")));
            ui.post(() -> {
                exporting = false;
                hideBusy();
                if (ok) {
                    toast("已发送语音");
                    if (dialog != null) dialog.dismiss();
                } else {
                    toast("发送失败, 请重试");
                }
            });
        }, "ls-amix-send").start();
    }

    // ==================== 精确裁剪 ====================

    /**
     * 按**毫秒**精确裁剪 24kHz/mono/16bit PCM。
     *
     * <p>直接用字节偏移 {@code ms * BYTES_PER_MS} 计算并做偶数字节对齐,
     * 避免经 float 秒换算产生的毫秒级误差, 保证分割点 / 起点 / 终点"零误差"。
     * 返回的片段起止都落在采样点上, 多段拼接后总长严格等于原区间。</p>
     */
    private static byte[] cutPcmMs(byte[] pcm, int beginMs, int endMs) {
        if (pcm == null || pcm.length == 0) return new byte[0];
        int b = Math.max(0, beginMs) * BYTES_PER_MS;
        int e = Math.min(endMs, pcm.length / BYTES_PER_MS) * BYTES_PER_MS;
        if (b % 2 != 0) b++;
        if (e % 2 != 0) e++;
        if (e > pcm.length) e = pcm.length - (pcm.length % 2);
        if (b >= e) return new byte[0];
        return java.util.Arrays.copyOfRange(pcm, b, e);
    }

    // ==================== 波形包络 ====================

    /**
     * 计算并归一化 峰值 + 有效值(RMS) 双层包络; RMS 决定内层亮芯高低, 真实跟随音量大小。
     * 峰值与 RMS 在<b>同一次遍历</b>内完成, 避免对大文件做两遍扫描。
     */
    private static void fillEnvs(Seg s) {
        if (s == null) return;
        byte[] pcm = s.pcm;
        if (pcm == null || pcm.length < 2) { s.env = new float[0]; s.envPeak = new float[0]; return; }
        int samples = pcm.length / 2;
        int columns = Math.min(ENV_COLUMNS, Math.max(8, samples));
        float[] pkEnv = new float[columns];
        float[] rmsEnv = new float[columns];
        double step = (double) samples / columns;
        double maxPk = 1e-9, maxRms = 1e-9;
        for (int c = 0; c < columns; c++) {
            int s0 = (int) (c * step);
            int s1 = (int) ((c + 1) * step);
            if (s1 <= s0) s1 = s0 + 1;
            if (s1 > samples) s1 = samples;
            int pk = 0;
            double sum = 0;
            for (int i = s0; i < s1; i++) {
                int v = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
                int a = v < 0 ? -v : v;
                if (a > pk) pk = a;
                sum += (double) v * v;
            }
            float pv = (float) (pk / 32768.0);
            float rv = (float) (Math.sqrt(sum / Math.max(1, s1 - s0)) / 32768.0);
            pkEnv[c] = pv;
            rmsEnv[c] = rv;
            if (pv > maxPk) maxPk = pv;
            if (rv > maxRms) maxRms = rv;
        }
        for (int c = 0; c < columns; c++) {
            double p = Math.pow(pkEnv[c] / maxPk, 0.85);
            if (p < 0.04) p = 0.04; else if (p > 1) p = 1;
            pkEnv[c] = (float) p;
            double r = Math.pow(rmsEnv[c] / maxRms, 0.65);
            if (r < 0.04) r = 0.04; else if (r > 1) r = 1;
            rmsEnv[c] = (float) r;
        }
        s.envPeak = pkEnv;
        s.env = rmsEnv;
    }

    /**
     * 从父片段的包络中截取子区间对应的列(O(1), 供分割时复用, 避免重新扫描整段音频)。
     * 保持父子相对电平一致, 分割不会重置音量观感。
     */
    private static float[] sliceEnv(float[] parent, int aMs, int bMs, int durMs) {
        if (parent == null || parent.length == 0 || durMs <= 0) return new float[0];
        int a = (int) ((long) aMs * parent.length / durMs);
        int b = (int) Math.ceil((double) bMs * parent.length / durMs);
        if (a < 0) a = 0;
        if (a > parent.length) a = parent.length;
        if (b <= a) b = a + 1;
        if (b > parent.length) b = parent.length;
        return java.util.Arrays.copyOfRange(parent, a, b);
    }

    // ==================== 忙碌提示 ====================

    private AlertDialog busy;
    private TextView busyText;

    private void showBusy(String msg) {
        hideBusy();
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextColor(AppColors.onSurface());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(24), dp(22), dp(24), dp(22));
        busy = new AlertDialog.Builder(ctx).setView(tv).setCancelable(false).create();
        busy.show();
        this.busyText = tv;
    }

    private void setBusyText(String msg) {
        if (busyText != null) busyText.setText(msg);
    }

    private void hideBusy() {
        if (busy != null) {
            try { busy.dismiss(); } catch (Throwable ignored) {}
            busy = null;
            busyText = null;
        }
    }

    // ==================== 小控件 ====================

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private TextView smallButton(String text, Runnable onClick) {
        TextView tv = outlined(text);
        tv.setOnClickListener(v -> onClick.run());
        tv.setPadding(dp(4), dp(11), dp(4), dp(11));
        return tv;
    }

    private TextView smallDangerButton(String text, Runnable onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.error());
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(AppColors.errorContainer());
        tv.setBackground(bg);
        tv.setPadding(dp(4), dp(11), dp(4), dp(11));
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    /** 按钮区上方居中的圆形渐变播放图标。 */
    private TextView playIconButton() {
        TextView tv = new TextView(ctx);
        tv.setText("▶");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{AppColors.gradientStart(), AppColors.gradientEnd()});
        bg.setShape(GradientDrawable.OVAL);
        tv.setBackground(bg);
        int sz = dp(58);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updatePlayIcon() {
        if (playIconBtn == null) return;
        playIconBtn.setText(editPlayer != null && editPlayer.isActive() ? "■" : "▶");
    }

    private TextView outlined(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.primary());
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(AppColors.surfaceContainerLow());
        bg.setStroke(dp(1), AppColors.outlineVariant());
        tv.setBackground(bg);
        tv.setPadding(dp(8), dp(10), dp(8), dp(10));
        return tv;
    }

    private View iconButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.primary());
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(AppColors.surfaceContainerHigh());
        tv.setBackground(bg);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    private View dangerButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.error());
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(AppColors.errorContainer());
        tv.setBackground(bg);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView valueLabel(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.primary());
        return tv;
    }

    private View fieldRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setTextColor(AppColors.onSurfaceVariant());
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(l);
        row.addView(value);
        return row;
    }

    private GradientDrawable borderBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(AppColors.primaryContainer());
        bg.setStroke(dp(1), AppColors.primary());
        return bg;
    }

    // ==================== 工具 ====================

    private Activity activityOf() {
        Context c = ctx;
        while (c != null) {
            if (c instanceof Activity) return (Activity) c;
            if (c instanceof android.content.ContextWrapper) c = ((android.content.ContextWrapper) c).getBaseContext();
            else break;
        }
        return null;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private static String fmtMs(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        int deci = (ms % 1000) / 100;
        return String.format(java.util.Locale.US, "%d:%02d.%d", total / 60, total % 60, deci);
    }

    /** 毫秒级精确显示 m:ss.mmm, 用于剪辑区间与游标。 */
    private static String fmtMs3(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d.%03d", total / 60, total % 60, ms % 1000);
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
