package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import com.leshao.v3.hook.*;
import com.leshao.v3.model.Contact;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.ContextManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 各功能模块的独立配置面板 (AlertDialog 形式)。
 * 所有配置均通过 SharedPreferences 持久化，由 HookConfig 读取生效。
 */
public class ConfigPanels {

    // ==================== ChatUICustom ====================

    public static void showChatUICustom(Activity act, SharedPreferences prefs) {
        ScrollView sv = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        int bgC = prefs.getInt("chat_bg_color", 0);
        int sendC = prefs.getInt("chat_bubble_send", 0);
        int recvC = prefs.getInt("chat_bubble_recv", 0);
        int rad = prefs.getInt("chat_bubble_radius", 0);
        int titleC = prefs.getInt("chat_title_color", 0);
        boolean hideNick = prefs.getInt("chat_hide_nickname", 0) == 1;

        EditText bgEt = addColorRow(act, root, "聊天背景色 (#AARRGGBB)", colorToHex(bgC));
        EditText sendEt = addColorRow(act, root, "发送气泡色", colorToHex(sendC));
        EditText recvEt = addColorRow(act, root, "接收气泡色", colorToHex(recvC));
        EditText titleEt = addColorRow(act, root, "标题栏颜色", colorToHex(titleC));
        EditText radEt = addIntRow(act, root, "气泡圆角 (dp)", String.valueOf(rad));
        LinearLayout hideRow = addSwitchRow(act, root, "隐藏昵称", hideNick);
        Switch hideSw = (Switch) hideRow.getChildAt(1);

        sv.addView(root);
        showDialog(act, "聊天界面UI定制", sv, () -> {
            try { prefs.edit().putInt("chat_bg_color", parseColor(bgEt.getText().toString())).apply(); } catch (Throwable e) {}
            try { prefs.edit().putInt("chat_bubble_send", parseColor(sendEt.getText().toString())).apply(); } catch (Throwable e) {}
            try { prefs.edit().putInt("chat_bubble_recv", parseColor(recvEt.getText().toString())).apply(); } catch (Throwable e) {}
            try { prefs.edit().putInt("chat_title_color", parseColor(titleEt.getText().toString())).apply(); } catch (Throwable e) {}
            try { prefs.edit().putInt("chat_bubble_radius", Integer.parseInt(radEt.getText().toString())).apply(); } catch (Throwable e) {}
            prefs.edit().putInt("chat_hide_nickname", hideSw.isChecked() ? 1 : 0).apply();
        });
    }

    // ==================== NotifyCustom ====================

    public static void showNotifyCustom(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        int mode = prefs.getInt("notify_priority_mode", 0);
        boolean avatar = prefs.getInt("notify_avatar", 1) == 1;
        String contacts = prefs.getString("notify_important_contacts", "");
        String phrases = prefs.getString("quick_reply_phrases", "好的|收到|稍等|在路上|马上到");

        Spinner modeSp = addSpinnerRow(act, root, "通知优先级", mode,
                new String[]{"关闭", "仅重要联系人", "全部联系人", "仅群聊"});

        LinearLayout avatarRow = addSwitchRow(act, root, "显示通知头像", avatar);
        Switch avatarSw = (Switch) avatarRow.getChildAt(1);

        Set<String> impSelected = new HashSet<>();
        if (!contacts.isEmpty()) for (String s : contacts.split(",")) impSelected.add(s.trim());
        addContactPickerRow(act, root, "重要联系人", impSelected);
        TextView impTv = addSelectedLabel(act, root, impSelected);

        EditText phrasesEt = addTextRow(act, root, "快捷回复短语 (|分隔)", phrases);

        showDialog(act, "通知自定义配置", new ScrollView(act) {{ addView(root); }}, () -> {
            prefs.edit().putInt("notify_priority_mode", modeSp.getSelectedItemPosition()).apply();
            prefs.edit().putInt("notify_avatar", avatarSw.isChecked() ? 1 : 0).apply();
            StringBuilder csb = new StringBuilder();
            for (String s : impSelected) { if (csb.length() > 0) csb.append(","); csb.append(s); }
            prefs.edit().putString("notify_important_contacts", csb.toString()).apply();
            prefs.edit().putString("quick_reply_phrases", phrasesEt.getText().toString().trim()).apply();
        });
    }

    // ==================== TypingIndicator ====================

    public static void showTypingIndicator(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        boolean showToast = prefs.getInt("typing_show_toast", 0) == 1;

        LinearLayout toastRow = addSwitchRow(act, root, "Toast 气泡提示", showToast);
        Switch toastSw = (Switch) toastRow.getChildAt(1);

        showDialog(act, "正在输入提示配置", new ScrollView(act) {{ addView(root); }}, () -> {
            prefs.edit().putInt("typing_show_toast", toastSw.isChecked() ? 1 : 0).apply();
        });
    }

    // ==================== ChatFooterEnhance ====================

    public static void showChatFooterEnhance(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        EditText maxLenEt = addIntRow(act, root, "最大文本长度 (默认50000)", "50000");
        EditText maxLinesEt = addIntRow(act, root, "最大输入行数 (默认10)", "10");

        showDialog(act, "底部栏增强配置", new ScrollView(act) {{ addView(root); }}, () -> {
            try { ChatFooterEnhance.maxTextLength = Integer.parseInt(maxLenEt.getText().toString()); } catch (Throwable e) {}
            try { ChatFooterEnhance.maxLines = Integer.parseInt(maxLinesEt.getText().toString()); } catch (Throwable e) {}
        });
    }

    // ==================== BatchMessage ====================

    public static void showBatchMessage(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        int curMax = prefs != null ? prefs.getInt("batch_max_select", 999) : 999;
        int curSplit = prefs != null ? prefs.getInt("forward_split_size", 50) : 50;
        EditText maxSelEt = addIntRow(act, root, "最大选择数 (默认999)", String.valueOf(curMax));
        EditText splitEt = addIntRow(act, root, "转发分批大小 (默认50)", String.valueOf(curSplit));

        showDialog(act, "批量消息配置", new ScrollView(act) {{ addView(root); }}, () -> {
            try {
                int v = Integer.parseInt(maxSelEt.getText().toString());
                BatchMessage.maxSelectCount = v;
                if (prefs != null) prefs.edit().putInt("batch_max_select", v).apply();
            } catch (Throwable e) {}
            try {
                int v = Integer.parseInt(splitEt.getText().toString());
                BatchMessage.forwardSplitSize = v;
                if (prefs != null) prefs.edit().putInt("forward_split_size", v).apply();
            } catch (Throwable e) {}
        });
    }

    // ==================== AutoRemark ====================

    public static void showAutoRemark(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        LinearLayout groupRow = addSwitchRow(act, root, "从群昵称自动填充", AutoRemark.autoFillFromGroupNick);
        Switch groupSw = (Switch) groupRow.getChildAt(1);

        LinearLayout cardRow = addSwitchRow(act, root, "从名片自动填充", AutoRemark.autoFillFromCard);
        Switch cardSw = (Switch) cardRow.getChildAt(1);

        showDialog(act, "自动备注配置", new ScrollView(act) {{ addView(root); }}, () -> {
            AutoRemark.autoFillFromGroupNick = groupSw.isChecked();
            AutoRemark.autoFillFromCard = cardSw.isChecked();
        });
    }

    // ==================== SearchEnhance ====================

    public static void showSearchEnhance(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        EditText maxEt = addIntRow(act, root, "最大搜索结果数 (默认500)", "500");

        showDialog(act, "搜索增强配置", new ScrollView(act) {{ addView(root); }}, () -> {
            try { SearchEnhance.maxResults = Integer.parseInt(maxEt.getText().toString()); } catch (Throwable e) {}
        });
    }

    // ==================== UnreadBadge ====================

    public static void showUnreadBadge(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        int style = prefs.getInt("badge_style", 0);
        int max = prefs.getInt("badge_max", 99);
        int bc = prefs.getInt("badge_color", AppColors.accent());

        Spinner styleSp = addSpinnerRow(act, root, "角标样式", style,
                new String[]{"默认", "仅红点", "精确数字", "99+模式", "自定义颜色"});

        EditText maxEt = addIntRow(act, root, "最大显示数", String.valueOf(max));
        EditText colorEt = addColorRow(act, root, "自定义颜色 (#AARRGGBB)", colorToHex(bc));

        showDialog(act, "未读角标配置", new ScrollView(act) {{ addView(root); }}, () -> {
            prefs.edit().putInt("badge_style", styleSp.getSelectedItemPosition()).apply();
            try { prefs.edit().putInt("badge_max", Integer.parseInt(maxEt.getText().toString())).apply(); } catch (Throwable e) {}
            try { prefs.edit().putInt("badge_color", parseColor(colorEt.getText().toString())).apply(); } catch (Throwable e) {}
            UnreadBadge.applyConfig(prefs);
        });
    }

    // ==================== TabCustom ====================

    public static void showTabCustom(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        EditText labelsEt = addTextRow(act, root, "标签名称 (|分隔，4个)", "微信|联系人|发现|我");

        LinearLayout customRow = addSwitchRow(act, root, "使用自定义名称", TabCustom.customLabels);
        Switch customSw = (Switch) customRow.getChildAt(1);

        // 隐藏 Tab: prefs "tab_visible" 存逗号分隔的 Tab 索引 (0=微信 1=联系人 2=发现 3=我)
        java.util.Set<Integer> hidden = new java.util.HashSet<>();
        String savedHidden = prefs != null ? prefs.getString("tab_visible", "") : "";
        if (savedHidden != null && !savedHidden.isEmpty()) {
            for (String s : savedHidden.split(",")) {
                try { hidden.add(Integer.parseInt(s.trim())); } catch (Throwable ignored) {}
            }
        }
        String[] tabNames = {"微信", "联系人", "发现", "我"};
        LinearLayout[] hiddenRows = new LinearLayout[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            hiddenRows[i] = addSwitchRow(act, root, "隐藏[" + tabNames[i] + "]", hidden.contains(idx));
        }

        showDialog(act, "底部Tab配置", new ScrollView(act) {{ addView(root); }}, () -> {
            TabCustom.customLabels = customSw.isChecked();
            String labels = labelsEt.getText().toString().trim();
            if (!labels.isEmpty()) {
                String[] parts = labels.split("\\|");
                if (parts.length >= 4) {
                    TabCustom.tabLabels = new String[]{parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim()};
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(parts[i].trim());
                    }
                    if (prefs != null) prefs.edit().putString("tab_labels", sb.toString()).apply();
                }
            }
            if (prefs != null) prefs.edit().putInt("tab_custom_labels", customSw.isChecked() ? 1 : 0).apply();

            // 隐藏 Tab
            StringBuilder hidSb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                boolean on = ((Switch) hiddenRows[i].getChildAt(1)).isChecked();
                if (on) {
                    if (hidSb.length() > 0) hidSb.append(",");
                    hidSb.append(i);
                }
            }
            if (prefs != null) prefs.edit().putString("tab_visible", hidSb.toString()).apply();
            if (hidSb.length() == 0) {
                TabCustom.hiddenTabs = new int[0];
            } else {
                String[] parts = hidSb.toString().split(",");
                int[] arr = new int[parts.length];
                for (int i = 0; i < parts.length; i++) arr[i] = Integer.parseInt(parts[i].trim());
                TabCustom.hiddenTabs = arr;
            }
        });
    }

    // ==================== ShakeCustom ====================

    public static void showShakeCustom(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        int action = prefs.getInt("shake_action", 3);

        Spinner actionSp = addSpinnerRow(act, root, "摇一摇动作", action,
                new String[]{"截图", "回顶部", "自定义广播", "仅记录", "快捷菜单"});

        showDialog(act, "摇一摇配置", new ScrollView(act) {{ addView(root); }}, () -> {
            prefs.edit().putInt("shake_action", actionSp.getSelectedItemPosition()).apply();
            ShakeCustom.applyConfig(prefs);
        });
    }

    // ==================== CallFeatures ====================

    public static void showCallFeatures(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        Set<String> selected = new HashSet<>(CallFeatures.autoAnswerList);
        addContactPickerRow(act, root, "自动接听白名单", selected);

        TextView tv = addSelectedLabel(act, root, selected);

        showDialog(act, "通话功能配置", new ScrollView(act) {{ addView(root); }}, () -> {
            CallFeatures.autoAnswerList.clear();
            CallFeatures.autoAnswerList.addAll(selected);
            StringBuilder sb = new StringBuilder();
            for (String s : selected) {
                if (sb.length() > 0) sb.append(",");
                sb.append(s);
            }
            prefs.edit().putString("ls_call_auto_list", sb.toString()).apply();
        });
    }

    // ==================== RedPacketAlert ====================

    public static void showRedAlert(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        boolean vibrate = prefs == null || prefs.getInt("rp_alert_vibrate", 1) == 1;
        boolean ring = prefs == null || prefs.getInt("rp_alert_ring", 1) == 1;
        LinearLayout vibrateRow = addSwitchRow(act, root, "强制震动", vibrate);
        LinearLayout ringRow = addSwitchRow(act, root, "强制响铃", ring);

        showDialog(act, "红包提醒配置", new ScrollView(act) {{ addView(root); }}, () -> {
            if (prefs != null) {
                prefs.edit().putInt("rp_alert_vibrate", ((Switch) vibrateRow.getChildAt(1)).isChecked() ? 1 : 0).apply();
                prefs.edit().putInt("rp_alert_ring", ((Switch) ringRow.getChildAt(1)).isChecked() ? 1 : 0).apply();
            }
        });
    }

    // ==================== SnsFeatures - time_edit ====================

    public static void showSnsTimeOffset(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        long offset = prefs.getLong("sns_time_offset_ms", -3600000);
        String hours = String.valueOf(offset / 3600000);

        EditText offsetEt = addIntRow(act, root, "时间偏移 (小时，负数为提前)", hours);

        showDialog(act, "发布时间偏移配置", new ScrollView(act) {{ addView(root); }}, () -> {
            try {
                long h = Long.parseLong(offsetEt.getText().toString());
                prefs.edit().putLong("sns_time_offset_ms", h * 3600000).apply();
            } catch (Throwable e) {}
        });
    }

    // ==================== ConvPrivacy ====================

    public static void showConvPrivacy(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        int level = prefs.getInt("conv_privacy_level", 1);
        boolean hideNotif = prefs.getBoolean("conv_hide_notification", true);
        boolean hideList = prefs.getBoolean("conv_hide_convlist", true);

        Set<String> hiddenContacts = new HashSet<>();
        String convList = prefs.getString("conv_privacy_list", "");
        if (!convList.isEmpty()) for (String s : convList.split(",")) hiddenContacts.add(s.trim());

        Spinner levelSp = addSpinnerRow(act, root, "隐私级别", level,
                new String[]{"关闭", "全局隐藏", "按联系人隐藏", "仅隐藏内容"});

        LinearLayout notifRow = addSwitchRow(act, root, "隐藏通知内容", hideNotif);
        Switch notifSw = (Switch) notifRow.getChildAt(1);

        LinearLayout listRow = addSwitchRow(act, root, "隐藏会话列表内容", hideList);
        Switch listSw = (Switch) listRow.getChildAt(1);

        addContactPickerRow(act, root, "按联系人隐藏 (级别选\"按联系人隐藏\"时生效)", hiddenContacts);
        TextView convTv = addSelectedLabel(act, root, hiddenContacts);

        showDialog(act, "会话隐私配置", new ScrollView(act) {{ addView(root); }}, () -> {
            prefs.edit().putInt("conv_privacy_level", levelSp.getSelectedItemPosition()).apply();
            prefs.edit().putBoolean("conv_hide_notification", notifSw.isChecked()).apply();
            prefs.edit().putBoolean("conv_hide_convlist", listSw.isChecked()).apply();
            StringBuilder sb = new StringBuilder();
            for (String s : hiddenContacts) { if (sb.length() > 0) sb.append(","); sb.append(s); }
            prefs.edit().putString("conv_privacy_list", sb.toString()).apply();
        });
    }

    // ==================== PrivacyFeatures - fingerprint lock ====================

    public static void showFingerprintLock(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        Set<String> locked = new HashSet<>();
        String saved = prefs.getString("fingerprint_locked_chats", "");
        if (!saved.isEmpty()) for (String s : saved.split(",")) locked.add(s.trim());

        addContactPickerRow(act, root, "需要指纹锁定的聊天联系人", locked);
        TextView tv = addSelectedLabel(act, root, locked);

        showDialog(act, "指纹锁定聊天配置", new ScrollView(act) {{ addView(root); }}, () -> {
            StringBuilder sb = new StringBuilder();
            for (String s : locked) { if (sb.length() > 0) sb.append(","); sb.append(s); }
            prefs.edit().putString("fingerprint_locked_chats", sb.toString()).apply();
        });
    }

    // ==================== FriendRequest ====================

    public static void showFriendRequest(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        boolean passAll = prefs.getInt("auto_pass_all", 0) == 1;
        String list = prefs.getString("auto_pass_list", "");
        Set<String> selected = new HashSet<>();
        if (!list.isEmpty()) for (String s : list.split(",")) selected.add(s.trim());

        LinearLayout passRow = addSwitchRow(act, root, "自动通过所有好友", passAll);
        Switch passSw = (Switch) passRow.getChildAt(1);

        addContactPickerRow(act, root, "自动通过白名单", selected);
        TextView tv = addSelectedLabel(act, root, selected);

        showDialog(act, "好友请求配置", new ScrollView(act) {{ addView(root); }}, () -> {
            prefs.edit().putInt("auto_pass_all", passSw.isChecked() ? 1 : 0).apply();
            StringBuilder sb = new StringBuilder();
            for (String s : selected) { if (sb.length() > 0) sb.append(","); sb.append(s); }
            prefs.edit().putString("auto_pass_list", sb.toString()).apply();
        });
    }

    // ==================== HideContactFields ====================

    public static void showHideContactFields(Activity act, SharedPreferences prefs) {
        float d = act.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));
        root.setBackgroundColor(AppColors.card());

        String[] keys = {"contact_info_mobile", "contact_info_region", "contact_info_source",
            "contact_info_alias", "contact_info_signature", "contact_info_remark",
            "contact_info_chatroom", "contact_info_linkedin"};
        String[] labels = {"手机号", "地区", "来源", "微信号", "签名", "备注名", "共同群聊", "领英"};
        boolean[] defaults = {true, true, true, false, false, false, false, false};

        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                View divider = new View(act);
                divider.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(1*d)));
                divider.setBackgroundColor(AppColors.divider());
                root.addView(divider);
            }
            root.addView(fieldCheck(act, d, keys[i], labels[i], defaults[i], prefs));
        }

        showDialog(act, "隐藏字段配置", new ScrollView(act) {{ addView(root); }}, () -> {
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                if (prefs.getBoolean("field_" + key, false)) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(key);
                }
            }
            prefs.edit().putString("hidden_fields_list", sb.toString()).apply();
        });
    }

    private static LinearLayout fieldCheck(Activity act, float d, String key, String label, boolean defVal, SharedPreferences prefs) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackgroundColor(AppColors.whiteCard());
        CheckBox cb = new CheckBox(act);
        cb.setText(label);
        cb.setTextSize(14);
        cb.setTextColor(AppColors.text1());
        cb.setChecked(prefs.getBoolean("field_" + key, defVal));
        cb.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("field_" + key, on).apply());
        row.addView(cb);
        return row;
    }

    // ==================== StickyEnhance ====================

    public static void showStickyEnhance(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));

        EditText maxEt = addIntRow(act, root, "最大置顶数量 (默认999)", String.valueOf(StickyEnhance.maxSticky));

        showDialog(act, "置顶增强配置", new ScrollView(act) {{ addView(root); }}, () -> {
            try { StickyEnhance.maxSticky = Integer.parseInt(maxEt.getText().toString()); } catch (Throwable e) {}
        });
    }

    // ==================== 联系人多选器 ====================

    private static void addContactPickerRow(Activity act, LinearLayout root, String label, Set<String> selected) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, dp(act, 8), 0, dp(act, 2));
        root.addView(tv);

        Button btn = new Button(act);
        btn.setText("从通讯录选择...");
        btn.setTextSize(13);
        btn.setPadding(dp(act, 10), dp(act, 6), dp(act, 10), dp(act, 6));
        btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        btn.setOnClickListener(v -> showContactPicker(act, selected));
        root.addView(btn);
    }

    private static TextView addSelectedLabel(Activity act, LinearLayout root, Set<String> selected) {
        TextView tv = new TextView(act);
        tv.setTextSize(12);
        tv.setTextColor(AppColors.text3());
        tv.setPadding(0, dp(act, 2), 0, dp(act, 6));
        updateContactLabel(tv, selected);
        root.addView(tv);
        return tv;
    }

    private static void updateContactLabel(TextView tv, Set<String> selected) {
        if (selected.isEmpty()) {
            tv.setText("未选择 (可手动输入wxid)");
            return;
        }
        StringBuilder sb = new StringBuilder("已选 ").append(selected.size()).append(" 人: ");
        int i = 0;
        for (String wxid : selected) {
            if (i++ > 4) { sb.append("..."); break; }
            sb.append(wxid);
            if (i < selected.size() && i <= 4) sb.append(", ");
        }
        tv.setText(sb.toString());
    }

    private static void showContactPicker(Activity act, Set<String> selected) {
        List<Contact> friends = new ArrayList<>();
        for (ContactCard c : ContactRepository.getFriends()) {
            friends.add(new Contact(c.username, c.displayName(), c.conRemark, c.alias, c.type));
        }
        for (ContactCard c : ContactRepository.getGroups()) {
            friends.add(new Contact(c.username, c.displayName(), c.conRemark, c.alias, c.type));
        }
        if (friends.isEmpty()) {
            Toast.makeText(act, "通讯录未加载，请先打开微信加载联系人", Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout listRoot = new LinearLayout(act);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 8));

        EditText filterEt = new EditText(act);
        filterEt.setHint("搜索联系人...");
        filterEt.setTextSize(13);
        filterEt.setPadding(dp(act, 10), dp(act, 6), dp(act, 10), dp(act, 6));
        android.graphics.drawable.GradientDrawable filtBg = new android.graphics.drawable.GradientDrawable();
        filtBg.setColor(AppColors.bg());
        filtBg.setCornerRadius((int)(6 * dp(act, 1)));
        filtBg.setStroke((int)(1.5f * dp(act, 1)), AppColors.candyPink());
        filterEt.setBackground(filtBg);
        listRoot.addView(filterEt);

        LinearLayout checkList = new LinearLayout(act);
        checkList.setOrientation(LinearLayout.VERTICAL);
        listRoot.addView(checkList);

        ScrollView sv = new ScrollView(act);
        sv.addView(listRoot);

        Runnable refresh = () -> {
            checkList.removeAllViews();
            String f = filterEt.getText().toString().toLowerCase().trim();
            for (Contact c : friends) {
                if (c == null || c.wxid == null) continue;
                String label = c.nickname != null ? c.nickname : c.wxid;
                if (!f.isEmpty() && !label.toLowerCase().contains(f) && !c.wxid.toLowerCase().contains(f)) continue;

                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(act, 2), 0, dp(act, 2));

                CheckBox cb = new CheckBox(act);
                cb.setText(label + " (" + c.wxid + ")");
                cb.setTextSize(13);
                cb.setChecked(selected.contains(c.wxid));
                cb.setOnCheckedChangeListener((v, on) -> {
                    if (on) selected.add(c.wxid); else selected.remove(c.wxid);
                });
                row.addView(cb);
                checkList.addView(row);
            }
        };

        filterEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { refresh.run(); }
        });

        refresh.run();

        new android.app.AlertDialog.Builder(act)
                .setTitle("选择联系人")
                .setView(sv)
                .setPositiveButton("确定", null)
                .show();
    }

    // ==================== 对话框与行组件工厂 ====================

    private static void showDialog(Activity act, String title, View content, Runnable onSave) {
        new android.app.AlertDialog.Builder(act)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        onSave.run();
                        Toast.makeText(act, "已保存(重启后生效)", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(act, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static EditText addColorRow(Activity act, LinearLayout root, String label, String value) {
        return addEditRow(act, root, label, value, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    private static EditText addIntRow(Activity act, LinearLayout root, String label, String value) {
        return addEditRow(act, root, label, value, InputType.TYPE_CLASS_NUMBER);
    }

    private static EditText addTextRow(Activity act, LinearLayout root, String label, String value) {
        return addEditRow(act, root, label, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }

    private static EditText addEditRow(Activity act, LinearLayout root, String label, String value, int inputType) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, dp(act, 8), 0, dp(act, 2));
        root.addView(tv);

        EditText et = new EditText(act);
        et.setText(value);
        et.setTextSize(14);
        et.setInputType(inputType);
        et.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
        android.graphics.drawable.GradientDrawable etBg = new android.graphics.drawable.GradientDrawable();
        etBg.setColor(AppColors.bg());
        etBg.setCornerRadius((int)(6 * dp(act, 1)));
        etBg.setStroke((int)(1.5f * dp(act, 1)), AppColors.candyPink());
        et.setBackground(etBg);
        root.addView(et);
        return et;
    }

    private static Spinner addSpinnerRow(Activity act, LinearLayout root, String label, int selected, String[] items) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, dp(act, 8), 0, dp(act, 2));
        root.addView(tv);

        Spinner sp = new Spinner(act);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(act, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setSelection(selected);
        root.addView(sp);
        return sp;
    }

    private static LinearLayout addSwitchRow(Activity act, LinearLayout root, String label, boolean checked) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(act, 8), 0, dp(act, 8));

        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tv);

        Switch sw = CandyUi.newSwitch(act);
        sw.setChecked(checked);
        row.addView(sw);

        root.addView(row);
        return row;
    }

    // ==================== 工具方法 ====================

    private static int parseColor(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) s = "FF" + s;
        return (int) Long.parseLong(s, 16);
    }

    private static String colorToHex(int color) {
        return String.format("#%08X", color);
    }

    private static int dp(Activity act, int dp) {
        return (int) (dp * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * d));
        lp.setMargins((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }
}
