package com.leshao.ai.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.ai.data.AiDataProvider;
import com.leshao.ai.util.Whitelist;
import com.leshao.v3.R;

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
 */
public class WhitelistActivity extends Activity {

    /** AiDataProvider authority（与 manifest 一致）。 */
    private static final String AUTHORITY = "com.leshao.v3.aiconfig";
    private static final Uri SESSIONS_URI = Uri.parse("content://" + AUTHORITY + "/sessions");

    private EditText editAddName;
    private Button btnAdd;
    private Button btnImport;
    private ListView listWhitelist;
    private TextView textEmpty;

    /** 白名单数据源（模块 files = Provider 数据源）。 */
    private Whitelist whitelist;

    /** talker 列表（数据）。 */
    private final List<String> items = new ArrayList<>();
    /** 展示文本（与 items 平行）。 */
    private final List<String> labels = new ArrayList<>();
    /** talker -> 显示名（来自导入的微信会话）。 */
    private final Map<String, String> nameCache = new LinkedHashMap<>();

    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        whitelist = new Whitelist(getFilesDir().getParent());
        whitelist.load();

        editAddName = findViewById(R.id.edit_add_name);
        btnAdd = findViewById(R.id.btn_add);
        btnImport = findViewById(R.id.btn_import);
        listWhitelist = findViewById(R.id.list_whitelist);
        textEmpty = findViewById(R.id.text_empty);

        adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels);
        listWhitelist.setAdapter(adapter);

        reloadItems();
        loadSessionNames();

        // 添加按钮
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addCurrentInput();
            }
        });

        // 从微信会话导入
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportDialog();
            }
        });

        // 键盘“完成”也触发添加
        editAddName.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId,
                                          android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    addCurrentInput();
                    return true;
                }
                return false;
            }
        });

        // 点击条目从白名单移除
        listWhitelist.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            String talker = items.remove(position);
            if (talker != null) {
                whitelist.remove(talker);
            }
            notifyChanged();
        });

        // 输入变化时更新按钮可用性
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
    }

    private void addCurrentInput() {
        String talker = editAddName.getText() == null ? ""
                : editAddName.getText().toString().trim();
        if (TextUtils.isEmpty(talker)) {
            Toast.makeText(this, R.string.whitelist_add_empty_toast, Toast.LENGTH_SHORT).show();
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

    /** 从微信会话列表多选加入白名单。 */
    private void showImportDialog() {
        List<String[]> sessions = readSessionsFromProvider();
        if (sessions.isEmpty()) {
            Toast.makeText(this, R.string.whitelist_import_empty_toast,
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String[] talkers = new String[sessions.size()];
        final String[] dialogLabels = new String[sessions.size()];
        final boolean[] checked = new boolean[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            String[] s = sessions.get(i);
            talkers[i] = s[0];
            dialogLabels[i] = s[1];
            checked[i] = items.contains(s[0]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.whitelist_import_title)
                .setMultiChoiceItems(dialogLabels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
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
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
            Toast.makeText(this, R.string.whitelist_import_empty_toast,
                    Toast.LENGTH_SHORT).show();
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

    /** talker -> 展示文本：显示名 (talker)。 */
    private String labelOf(String talker) {
        String name = nameCache.get(talker);
        if (name == null || name.isEmpty() || name.equals(talker)) {
            return talker;
        }
        return name + " (" + talker + ")";
    }

    private void notifyChanged() {
        labels.clear();
        for (String t : items) {
            labels.add(labelOf(t));
        }
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        listWhitelist.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
    }

    private void save() {
        boolean ok = whitelist.save();
        // 通知微信进程重新同步白名单
        try {
            Intent refresh = new Intent(AiDataProvider.ACTION_REFRESH_CONFIG);
            refresh.setPackage("com.tencent.mm");
            sendBroadcast(refresh);
        } catch (Throwable ignored) {
        }
        Toast.makeText(this,
                ok ? R.string.whitelist_saved_toast : R.string.whitelist_save_failed_toast,
                Toast.LENGTH_SHORT).show();
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
