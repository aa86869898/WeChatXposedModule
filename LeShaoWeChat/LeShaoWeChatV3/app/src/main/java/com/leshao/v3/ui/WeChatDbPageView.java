package com.leshao.v3.ui;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.model.ContactCard.Category;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.SegmentedControl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库直读 - 新版联系人选择器。
 * 数据源为 ContactRepository 内存缓存（日志证实 53 好友 / 105 群），
 * 不依赖 rawQuery 结果，保证选择器窗口可独立运行。
 * 页签仅保留 全部 / 好友 / 群聊 / 标签，服务号订阅号一律过滤不展示。
 * 标签页从联系人 contactLabelIds 聚合，性别按资料包结论展示。
 */
public final class WeChatDbPageView {

    private static final int TAB_ALL = 0;
    private static final int TAB_FRIEND = 1;
    private static final int TAB_GROUP = 2;
    private static final int TAB_LABEL = 3;

    private WeChatDbPageView() {}

    /** 页面状态：当前页签、已选集合、标签浏览位置、标签名映射。 */
    private static final class State {
        int tab = TAB_ALL;
        final Set<String> selected = new LinkedHashSet<>();
        String currentLabelId = null;
        Map<String, Long> recentTimes = new HashMap<>();
        final Map<String, String> labelNames = new LinkedHashMap<>();
        final EditText search;
        final LinearLayout listRoot;
        final TextView topInfo;

        State(EditText search, LinearLayout listRoot, TextView topInfo) {
            this.search = search;
            this.listRoot = listRoot;
            this.topInfo = topInfo;
        }
    }

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int p12 = (int) (12 * d);
        int p8 = (int) (8 * d);
        int p4 = (int) (4 * d);

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p12, p12, p12, (int) (24 * d));
        sv.addView(root);

        // 搜索框（顶部）
        final EditText search = new EditText(ctx);
        search.setHint("搜索名称 / 微信号 / 备注");
        search.setHintTextColor(AppColors.onSurfaceVariant());
        search.setTextSize(14);
        search.setTextColor(AppColors.onSurface());
        search.setSingleLine(true);
        search.setPadding(p12, (int) (10 * d), p12, (int) (10 * d));
        search.setBackground(CandyUi.inputBg(ctx));
        root.addView(search);

        // 页签
        final int friendCount = ContactRepository.getFriends().size();
        final int groupCount = ContactRepository.getGroups().size();
        SegmentedControl tabs = new SegmentedControl(ctx,
                new String[]{"全部",
                        "联系人(" + friendCount + ")",
                        "群聊(" + groupCount + ")",
                        "标签"}, TAB_ALL);
        LinearLayout tabBox = new LinearLayout(ctx);
        tabBox.setOrientation(LinearLayout.VERTICAL);
        tabBox.setPadding(0, p8, 0, p8);
        tabBox.addView(tabs);
        root.addView(tabBox);

        // 列表区域（固定高度约10行，内部滚动）
        final LinearLayout listRoot = new LinearLayout(ctx);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(0, p4, 0, p4);
        ScrollView listScroll = new ScrollView(ctx);
        listScroll.setFillViewport(true);
        listScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (10 * 52 * d)));
        listScroll.addView(listRoot);
        root.addView(listScroll);

        final TextView topInfo = PageKit.bodyText(ctx, "共 0 项，已选 0 项");
        topInfo.setPadding(0, p4, 0, p4);
        root.addView(topInfo);

        // 底部操作栏：取消 / 全选(取消勾选) / 确定
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, p8, 0, 0);

        final ModernButton cancelBtn = new ModernButton(ctx, "取消", ModernButton.STYLE_GHOST);
        final ModernButton toggleBtn = new ModernButton(ctx, "全选", ModernButton.STYLE_TEXT);
        final ModernButton okBtn = new ModernButton(ctx, "确定", ModernButton.STYLE_PRIMARY);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        btnLp.setMargins(p4, 0, p4, 0);
        bottom.addView(cancelBtn, btnLp);
        bottom.addView(toggleBtn, btnLp);
        bottom.addView(okBtn, btnLp);
        root.addView(bottom);

        // 初始化状态与刷新
        final State state = new State(search, listRoot, topInfo);
        loadLabelNames(ctx, state.labelNames);

        final Runnable refresh = () -> {
            List<ContactCard> vis = visibleCards(state);
            boolean allSelected = !vis.isEmpty() && state.selected.containsAll(idsOf(vis));
            toggleBtn.setText(allSelected ? "取消勾选" : "全选");
            refreshList(ctx, state);
        };

        tabs.setOnSegmentChangedListener((index, label) -> {
            state.tab = index;
            state.currentLabelId = null;
            refresh.run();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });

        cancelBtn.onClick(() -> {
            try {
                SubPageActivity.closePage(act);
            } catch (Throwable t1) {
                try { act.finish(); } catch (Throwable t2) {}
            }
        });

        toggleBtn.onClick(() -> {
            List<ContactCard> visible = visibleCards(state);
            if (visible.isEmpty()) return;
            Set<String> ids = idsOf(visible);
            boolean allSelected = !visible.isEmpty() && state.selected.containsAll(ids);
            if (allSelected) state.selected.removeAll(ids);
            else state.selected.addAll(ids);
            toggleBtn.setText(allSelected ? "全选" : "取消勾选");
            refresh.run();
        });

        okBtn.onClick(() -> {
            if (state.selected.isEmpty()) {
                Toast.makeText(ctx, "尚未选择任何项", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            List<ContactCard> all = allContacts();
            int i = 0;
            for (String wid : state.selected) {
                ContactCard c = findCard(all, wid);
                if (i++ > 0) sb.append('\n');
                sb.append(c != null ? c.displayName() : wid);
                if (c != null) {
                    sb.append("  ").append(c.username);
                    if (c.alias != null && !c.alias.isEmpty()) sb.append("  ").append(c.alias);
                }
            }
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setText(sb.toString());
                Toast.makeText(ctx, "已复制 " + state.selected.size() + " 项", Toast.LENGTH_SHORT).show();
                try {
                    SubPageActivity.closePage(act);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show();
            }
        });

        // 数据加载完成后刷新；已缓存则立即刷新
        ContactRepository.loadAsync(() -> act.runOnUiThread(() -> {
            state.recentTimes = loadRecentTimes();
            refresh.run();
        }));

        // 数据库实时刷新：每 3 秒重新读取会话表时间戳，有变化才重绘列表
        final Handler poller = new Handler(Looper.getMainLooper());
        final Runnable pollTask = new Runnable() {
            @Override public void run() {
                Map<String, Long> next = loadRecentTimes();
                if (!next.equals(state.recentTimes)) {
                    state.recentTimes = next;
                    refresh.run();
                }
                if (sv.isAttachedToWindow()) poller.postDelayed(this, 3000);
            }
        };
        sv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                poller.postDelayed(pollTask, 3000);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                poller.removeCallbacks(pollTask);
            }
        });

        return sv;
    }

    private static void refreshList(Context ctx, State state) {
        state.listRoot.removeAllViews();
        String query = state.search.getText().toString().toLowerCase().trim();

        if (state.tab == TAB_LABEL && state.currentLabelId == null) {
            renderLabelList(ctx, state, query);
            return;
        }

        if (state.tab == TAB_LABEL) {
            addBackRow(ctx, state.listRoot, "← 返回标签列表", v -> {
                state.currentLabelId = null;
                refreshList(ctx, state);
            });
        }

        List<ContactCard> cards = visibleCards(state);
        if (cards.isEmpty()) {
            addEmpty(ctx, state.listRoot, "无匹配项");
            state.topInfo.setText("共 0 项");
            return;
        }

        for (final ContactCard c : cards) {
            final String wid = c.username;
            final boolean checked = state.selected.contains(wid);
            state.listRoot.addView(buildRow(ctx, c, checked, () -> {
                if (state.selected.contains(wid)) state.selected.remove(wid);
                else state.selected.add(wid);
                refreshList(ctx, state);
            }));
        }
        state.topInfo.setText("当前 " + cards.size() + " 项，已选 " + state.selected.size() + " 项");
    }

    private static void renderLabelList(Context ctx, State state, String query) {
        Map<String, Integer> counts = labelCounts();
        if (counts.isEmpty()) {
            addEmpty(ctx, state.listRoot,
                    "联系人中没有标签数据（contactLabelIds 为空）。");
            state.topInfo.setText("标签聚合");
            return;
        }
        List<String> ids = new ArrayList<>(counts.keySet());
        Collections.sort(ids, (a, b) -> labelName(a, state.labelNames)
                .compareTo(labelName(b, state.labelNames)));
        int shown = 0;
        for (String id : ids) {
            String name = labelName(id, state.labelNames);
            if (!query.isEmpty() && !name.toLowerCase().contains(query)
                    && !id.contains(query)) continue;
            final int cnt = counts.get(id);
            final String fid = id;
            state.listRoot.addView(buildLabelRow(ctx, name + " (" + cnt + " 人)", v -> {
                state.currentLabelId = fid;
                refreshList(ctx, state);
            }));
            shown++;
        }
        if (shown == 0) {
            addEmpty(ctx, state.listRoot, "无匹配标签");
        }
        state.topInfo.setText("共 " + counts.size() + " 个标签，点击查看标签成员");
    }

    private static void addEmpty(Context ctx, LinearLayout listRoot, String text) {
        TextView empty = new TextView(ctx);
        empty.setText(text);
        empty.setTextSize(14);
        empty.setTextColor(AppColors.onSurfaceVariant());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (40 * ctx.getResources().getDisplayMetrics().density), 0, 0);
        listRoot.addView(empty);
    }

    private static void addBackRow(Context ctx, LinearLayout listRoot, String text,
                                   View.OnClickListener l) {
        TextView back = new TextView(ctx);
        back.setText(text);
        back.setTextSize(14);
        back.setTextColor(AppColors.primary());
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding((int) (12 * ctx.getResources().getDisplayMetrics().density),
                (int) (10 * ctx.getResources().getDisplayMetrics().density), 0, 0);
        back.setBackground(CandyUi.rowPressBg(ctx));
        back.setOnClickListener(l);
        listRoot.addView(back);
    }

    private static LinearLayout buildLabelRow(Context ctx, String text, View.OnClickListener l) {
        int p12 = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p12, (int) (10 * ctx.getResources().getDisplayMetrics().density), p12,
                (int) (10 * ctx.getResources().getDisplayMetrics().density));
        row.setBackground(CandyUi.rowPressBg(ctx));
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.onSurface());
        row.addView(tv);
        CandyUi.ripple(row, AppColors.SHAPE_MD_DP);
        row.setOnClickListener(l);
        return row;
    }

    private static LinearLayout buildRow(Context ctx, ContactCard c, boolean checked,
                                         Runnable onToggle) {
        int p8 = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        int p12 = (int) (12 * ctx.getResources().getDisplayMetrics().density);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p12, (int) (6 * ctx.getResources().getDisplayMetrics().density), p12,
                (int) (6 * ctx.getResources().getDisplayMetrics().density));
        row.setBackground(CandyUi.rowPressBg(ctx));

        ImageView cb = new ImageView(ctx);
        cb.setImageDrawable(makeCheckbox(ctx, checked));
        cb.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                (int) (28 * ctx.getResources().getDisplayMetrics().density),
                (int) (28 * ctx.getResources().getDisplayMetrics().density));
        cblp.setMargins(0, 0, p8, 0);
        row.addView(cb, cblp);

        int avatarSize = (int) (40 * ctx.getResources().getDisplayMetrics().density);
        ImageView avatar = new ImageView(ctx);
        String key = c.sortKey();
        String letter = (key != null && !key.isEmpty()) ? key.substring(0, 1) : "#";
        Bitmap letterBmp = letterAvatar(ctx, letter, avatarSize);
        avatar.setImageBitmap(letterBmp);
        AvatarHelper.loadAvatarAsync(avatar, c.username, avatarSize, letterBmp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMargins(0, 0, p12, 0);
        row.addView(avatar, alp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(c.displayName());
        name.setTextSize(14);
        name.setTextColor(AppColors.onSurface());
        textCol.addView(name);

        String sub = subTitle(c);
        if (sub != null && !sub.isEmpty()) {
            TextView subTv = new TextView(ctx);
            subTv.setText(sub);
            subTv.setTextSize(11);
            subTv.setTextColor(AppColors.onSurfaceVariant());
            textCol.addView(subTv);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        CandyUi.ripple(row, AppColors.SHAPE_MD_DP);
        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private static String subTitle(ContactCard c) {
        if (c.category == Category.GROUP) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (c.alias != null && !c.alias.isEmpty()) sb.append("微信号 ").append(c.alias);
        if (c.conRemark != null && !c.conRemark.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("备注 ").append(c.conRemark);
        }
        if (c.contactLabelIds != null && !c.contactLabelIds.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("标签 ").append(c.contactLabelIds);
        }
        return sb.toString();
    }

    /** 全部数据 = 好友 + 群聊，服务号订阅号不展示。 */
    private static List<ContactCard> allContacts() {
        List<ContactCard> all = new ArrayList<>();
        all.addAll(ContactRepository.getFriends());
        all.addAll(ContactRepository.getGroups());
        return all;
    }

    private static List<ContactCard> visibleCards(State state) {
        String query = state.search.getText().toString().toLowerCase().trim();
        List<ContactCard> src;
        if (state.tab == TAB_ALL) {
            src = allContacts();
        } else if (state.tab == TAB_GROUP) {
            src = ContactRepository.getGroups();
        } else if (state.tab == TAB_LABEL && state.currentLabelId != null) {
            src = cardsWithLabel(ContactRepository.getFriends(), state.currentLabelId);
        } else {
            src = ContactRepository.getFriends();
        }
        if (src == null) return Collections.emptyList();
        List<ContactCard> out = new ArrayList<>();
        for (ContactCard c : src) {
            if (query.isEmpty() || matchesFilter(c, query)) out.add(c);
        }
        // "全部"页签：按最近来消息时间降序（无会话记录的排最后，再按名称排序）
        if (state.tab == TAB_ALL) {
            final Map<String, Long> times = state.recentTimes;
            Collections.sort(out, (a, b) -> {
                long ta = times.containsKey(a.username) ? times.get(a.username) : -1;
                long tb = times.containsKey(b.username) ? times.get(b.username) : -1;
                if (ta != tb) return Long.compare(tb, ta);
                return a.displayName().compareToIgnoreCase(b.displayName());
            });
        }
        return out;
    }

    private static List<ContactCard> cardsWithLabel(List<ContactCard> src, String labelId) {
        List<ContactCard> out = new ArrayList<>();
        for (ContactCard c : src) {
            if (c.contactLabelIds == null) continue;
            for (String p : c.contactLabelIds.split(",")) {
                if (p.trim().equals(labelId)) { out.add(c); break; }
            }
        }
        return out;
    }

    private static Map<String, Integer> labelCounts() {
        Map<String, Integer> map = new LinkedHashMap<>();
        List<ContactCard> src = new ArrayList<>();
        src.addAll(ContactRepository.getFriends());
        for (ContactCard c : src) {
            if (c.contactLabelIds == null) continue;
            for (String p : c.contactLabelIds.split(",")) {
                String id = p.trim();
                if (id.isEmpty()) continue;
                Integer n = map.get(id);
                map.put(id, n == null ? 1 : n + 1);
            }
        }
        return map;
    }

    private static void loadLabelNames(Context ctx, Map<String, String> out) {
        try {
            if (!ContactRepository.isDbReady()) return;
            List<ContactRepository.DbRow> rows = ContactRepository.rawQuery(
                    "SELECT labelID, labelName FROM ContactLabel WHERE isTemporary = 0", null, 500);
            if (rows == null) return;
            for (ContactRepository.DbRow r : rows) {
                String id = valueOf(r, "labelID");
                String name = valueOf(r, "labelName");
                if (id != null && name != null && !id.isEmpty()) out.put(id.trim(), name);
            }
        } catch (Throwable ignored) {}
    }

    /** 读取会话表（conversation）各对象的最新来消息时间戳，用于"全部"页签排序。
     *  先探测列名（不同微信版本字段可能不同），再按时间倒序取前 500 个会话。 */
    private static Map<String, Long> loadRecentTimes() {
        Map<String, Long> map = new HashMap<>();
        try {
            if (!ContactRepository.isDbReady()) return map;
            List<ContactRepository.DbRow> cols = ContactRepository.rawQuery(
                    "PRAGMA table_info(conversation)", null, 200);
            if (cols == null || cols.isEmpty()) return map;
            String userCol = null, timeCol = null;
            for (ContactRepository.DbRow r : cols) {
                String name = valueOf(r, "name");
                if (name == null) continue;
                if (userCol == null && ("username".equalsIgnoreCase(name)
                        || "strTalker".equalsIgnoreCase(name)
                        || "talker".equalsIgnoreCase(name)
                        || "userName".equalsIgnoreCase(name))) {
                    userCol = name;
                } else if (timeCol == null && ("createTime".equalsIgnoreCase(name)
                        || "lastMsgTime".equalsIgnoreCase(name)
                        || "msgCreateTime".equalsIgnoreCase(name)
                        || "chatTime".equalsIgnoreCase(name))) {
                    timeCol = name;
                }
            }
            if (userCol == null || timeCol == null) return map;
            List<ContactRepository.DbRow> rows = ContactRepository.rawQuery(
                    "SELECT " + userCol + ", " + timeCol + " FROM conversation ORDER BY " + timeCol + " DESC",
                    null, 500);
            if (rows == null) return map;
            for (ContactRepository.DbRow r : rows) {
                String user = valueOf(r, userCol);
                if (user == null || user.isEmpty()) continue;
                String tv = valueOf(r, timeCol);
                if (tv == null || tv.isEmpty()) continue;
                try {
                    map.put(user, Long.parseLong(tv));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static String valueOf(ContactRepository.DbRow r, String col) {
        for (int i = 0; i < r.cols.length; i++) {
            if (col.equalsIgnoreCase(r.cols[i])) return r.vals[i];
        }
        return null;
    }

    private static String labelName(String id, Map<String, String> names) {
        String n = names.get(id);
        return n != null && !n.isEmpty() ? n : "标签#" + id;
    }

    private static boolean matchesFilter(ContactCard c, String q) {
        if (c.displayName() != null && c.displayName().toLowerCase().contains(q)) return true;
        if (c.username != null && c.username.toLowerCase().contains(q)) return true;
        if (c.alias != null && c.alias.toLowerCase().contains(q)) return true;
        if (c.conRemark != null && c.conRemark.toLowerCase().contains(q)) return true;
        return false;
    }

    private static Set<String> idsOf(List<ContactCard> cards) {
        Set<String> ids = new LinkedHashSet<>();
        for (ContactCard c : cards) ids.add(c.username);
        return ids;
    }

    private static ContactCard findCard(List<ContactCard> items, String wxid) {
        for (ContactCard c : items) {
            if (wxid.equals(c.username)) return c;
        }
        return null;
    }

    private static Drawable makeCheckbox(Context ctx, boolean checked) {
        int size = (int) (22 * ctx.getResources().getDisplayMetrics().density);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        if (checked) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setShader(new android.graphics.LinearGradient(0, 0, size, size,
                    AppColors.gradientColors(), null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, fill);
            Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
            check.setColor(Color.WHITE);
            check.setStrokeWidth((float) (2.2 * ctx.getResources().getDisplayMetrics().density));
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
            float cx = size * 0.32f, cy = size * 0.52f;
            float mx = size * 0.46f, my = size * 0.66f;
            float ex = size * 0.72f, ey = size * 0.35f;
            canvas.drawLine(cx, cy, mx, my, check);
            canvas.drawLine(mx, my, ex, ey, check);
        } else {
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(AppColors.outline());
            stroke.setStrokeWidth((float) (2 * ctx.getResources().getDisplayMetrics().density));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, stroke);
        }
        return new BitmapDrawable(ctx.getResources(), bm);
    }

    private static Bitmap letterAvatar(Context ctx, String letter, int size) {
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(size * 0.45f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint bgPaint = new Paint();
        bgPaint.setColor(AppColors.secondaryContainer());
        canvas.drawRoundRect(0, 0, size, size, size / 2f, size / 2f, bgPaint);
        float y = size / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(letter, size / 2f, y, paint);
        return bm;
    }
}