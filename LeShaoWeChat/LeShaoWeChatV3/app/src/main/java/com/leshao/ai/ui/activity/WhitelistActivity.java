package com.leshao.ai.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.ai.data.AiDataProvider;
import com.leshao.ai.util.Whitelist;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.SettingRow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 白名单管理页（会话级）。
 * <p>
 * 白名单语义为<b>会话 talker</b>（wxid / 群 id）：名单非空时，
 * AI 仅响应名单内会话（文档 §16.5 决策链）。数据经
 * {@link AiDataProvider} 与微信进程同步。
 * <p>
 * 「从微信会话导入」读取微信侧转储的会话列表（{@code /sessions}），
 * 多选加入，避免手动输入群 id。
 * <p>
 * v986: 界面由旧 XML 主题重写为程序化 M3（M3Page 组件 + AppColors 动态色），
 * 与模块其余页面统一，支持深色模式。
 */
public class WhitelistActivity extends Activity {

    /** AiDataProvider authority（与 manifest 一致）。 */
    private static final String AUTHORITY = "com.leshao.v3.aiconfig";
    private static final Uri SESSIONS_URI = Uri.parse("content://" + AUTHORITY + "/sessions");

    private EditText editAddName;
    private LinearLayout listContainer;
    private View emptyView;

    /** 白名单数据源（模块 files = Provider 数据源）。 */
    private Whitelist whitelist;

    /** talker 列表（数据）。 */
    private final List<String> items = new ArrayList<>();
    /** talker -> 显示名（来自导入的微信会话）。 */
    private final Map<String, String> nameCache = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppColors.refresh();

        whitelist = new Whitelist(getFilesDir().getParent());
        whitelist.load();

        View contentView = buildContentView();
        setContentView(contentView);
        InsetsUtil.applyActivityInsets(this, contentView);
        reloadItems();
        loadSessionNames();
    }

    // ==================== M3 界面 ====================

    private View buildContentView() {
        LinearLayout root = M3Page.root(this);
        root.addView(M3Page.title(this, "白名单管理"));
        root.addView(M3Page.note(this,
                "名单非空时, 仅名单内会话自动回复; 名单外会话在被@时仍会回复。"
                        + "点条目两次确认删除。"));

        // ---- 手动添加 ----
        root.addView(M3Page.section(this, "添加会话", "输入 wxid / 群 id"));
        LinearLayout cardAdd = M3Page.card(this);
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        editAddName = M3Page.input(this, "wxid 或 群 id");
        editAddName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        addRow.addView(editAddName);
        ModernButton btnAdd = new ModernButton(this, "添加", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(-2, -2);
        lpAdd.setMargins(dp(8), 0, 0, 0);
        btnAdd.setLayoutParams(lpAdd);
        btnAdd.onClick(this::addCurrentInput);
        addRow.addView(btnAdd);
        cardAdd.addView(addRow);

        // 键盘“完成”也触发添加
        editAddName.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addCurrentInput();
                    return true;
                }
                return false;
            }
        });
        editAddName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                btnAdd.setEnabled(!TextUtils.isEmpty(s.toString().trim()));
            }
        });
        root.addView(cardAdd);

        // ---- 从会话导入 ----
        root.addView(M3Page.section(this, "批量导入", "从微信会话列表多选加入"));
        LinearLayout cardImport = M3Page.card(this);
        M3Page.appendClickRow(cardImport, this, "📥", "从微信会话导入", "多选会话加入白名单",
                this::showImportDialog);
        root.addView(cardImport);

        // ---- 已选列表 ----
        root.addView(M3Page.section(this, "已选会话", null));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        emptyView = M3Page.empty(this, "📋",
                "当前名单为空, 全部会话均自动回复(不受白名单限制)。");
        root.addView(emptyView);

        ScrollView sv = M3Page.scroll(this, root);
        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        InsetsUtil.transparentWindow(this);
        return sv;
    }

    private void addCurrentInput() {
        String talker = editAddName.getText() == null ? ""
                : editAddName.getText().toString().trim();
        if (TextUtils.isEmpty(talker)) {
            Toast.makeText(this, "请输入会话 id", Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.contains(talker)) {
            editAddName.setText("");
            return;
        }
        items.add(talker);
        whitelist.add(talker);
        editAddName.setText("");
        notifyChanged();
    }

    /** 从微信会话列表多选加入白名单（M3 自绘多选对话框）。 */
    private void showImportDialog() {
        List<String[]> sessions = readSessionsFromProvider();
        if (sessions.isEmpty()) {
            Toast.makeText(this, "未读取到会话, 请先在微信内打开会话列表后重试",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final String[] talkers = new String[sessions.size()];
        final String[] labels = new String[sessions.size()];
        final boolean[] checked = new boolean[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            String[] s = sessions.get(i);
            talkers[i] = s[0];
            labels[i] = s[1];
            checked[i] = items.contains(s[0]);
        }

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackground(CandyUi.dialogBg(this));
        InsetsUtil.clipRounded(dialogRoot);
        int pad = dp(16);
        dialogRoot.setPadding(pad, pad, pad, pad);
        dialogRoot.addView(M3Page.title(this, "从微信会话导入"));

        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        sv.addView(listBox);
        dialogRoot.addView(sv);

        for (int i = 0; i < talkers.length; i++) {
            final int idx = i;
            listBox.addView(M3Page.checkRow(this, "💬", labels[i], talkers[i], checked[i],
                    on -> checked[idx] = on));
        }

        ModernButton btnCancel = new ModernButton(this, "取消", ModernButton.STYLE_GHOST);
        ModernButton btnOk = new ModernButton(this, "确定", ModernButton.STYLE_PRIMARY);
        dialogRoot.addView(M3Page.buttonRow(this, btnCancel, btnOk));

        AlertDialog dlg = new AlertDialog.Builder(this, dialogTheme())
                .setView(dialogRoot)
                .setCancelable(true)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        btnCancel.onClick(dlg::dismiss);
        btnOk.onClick(() -> {
            for (int i = 0; i < talkers.length; i++) {
                if (checked[i] && !items.contains(talkers[i])) {
                    items.add(talkers[i]);
                    whitelist.add(talkers[i]);
                } else if (!checked[i] && items.contains(talkers[i])) {
                    items.remove(talkers[i]);
                    whitelist.remove(talkers[i]);
                }
            }
            notifyChanged();
            whitelist.save();
            AiDataProvider.pushRefresh(this);
            dlg.dismiss();
        });
        dlg.show();
    }

    /** 读取 Provider 中转储的微信会话列表。 */
    private List<String[]> readSessionsFromProvider() {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getContentResolver().query(SESSIONS_URI, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("json");
                if (idx >= 0 && !c.isNull(idx)) {
                    String json = c.getString(idx);
                    if (!TextUtils.isEmpty(json)) {
                        JSONObject root = new JSONObject(json);
                        JSONArray arr = root.optJSONArray("sessions");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) {
                                    continue;
                                }
                                String talker = o.optString("talker", "");
                                String name = o.optString("name", talker);
                                if (!talker.isEmpty()) {
                                    nameCache.put(talker, name);
                                    out.add(new String[]{talker, name});
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "未读取到会话, 请稍后重试", Toast.LENGTH_SHORT).show();
        }
        return out;
    }

    /** 进入页面时先拉一次显示名缓存（列表展示用）。 */
    private void loadSessionNames() {
        readSessionsFromProvider();
    }

    private void reloadItems() {
        items.clear();
        items.addAll(whitelist.list());
        notifyChanged();
    }

    /** talker -> 展示文本：显示名（无则用 talker）。 */
    private String labelOf(String talker) {
        String name = nameCache.get(talker);
        if (name == null || name.isEmpty() || name.equals(talker)) {
            return talker;
        }
        return name;
    }

    private void notifyChanged() {
        listContainer.removeAllViews();
        final String[] pendingDelete = {null};
        for (String talker : items) {
            SettingRow row = M3Page.checkRow(this, "✅", labelOf(talker),
                    talker + (talker.endsWith("@chatroom") ? "  (群)" : ""), true, null);
            listContainer.addView(row);
            row.setOnClickListener(v -> {
                if (!talker.equals(pendingDelete[0])) {
                    pendingDelete[0] = talker;
                    Toast.makeText(this, "再次点击确认删除 " + talker, Toast.LENGTH_SHORT).show();
                    return;
                }
                items.remove(talker);
                whitelist.remove(talker);
                whitelist.save();
                AiDataProvider.pushRefresh(this);
                notifyChanged();
            });
        }
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private int dialogTheme() {
        return AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void save() {
        boolean ok = whitelist.save();
        // 通知微信进程同步白名单（广播直带 payload）
        AiDataProvider.pushRefresh(this);
        Toast.makeText(this, ok ? "白名单已保存" : "保存失败", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        save();
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面时持久化
        save();
    }
}
