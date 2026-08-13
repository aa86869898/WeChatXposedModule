package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.hook.ContactChangeLog;
import com.leshao.v3.model.ContactChangeRecord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ContactChangeLogPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        List<ContactChangeRecord> records = ContactChangeLog.loadRecords();

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int)(16*d), (int)(16*d), (int)(16*d), (int)(24*d));

        TextView section = new TextView(ctx);
        section.setText("通讯录更新记录");
        section.setTextSize(13);
        section.setTextColor(AppColors.text2());
        section.setPadding(0, 0, 0, (int)(8*d));
        body.addView(section);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(AppColors.card());
        card.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));
        final LinearLayout cardRef = card;

        if (records.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无变更记录");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding((int)(16*d), (int)(30*d), (int)(16*d), (int)(30*d));
            card.addView(empty);
        } else {
            for (int i = 0; i < Math.min(records.size(), 200); i++) {
                if (i > 0) card.addView(candyDivider(ctx, d));
                card.addView(recordRow(ctx, d, records.get(i)));
            }
            if (records.size() > 200) {
                TextView more = new TextView(ctx);
                more.setText("... 仅显示最近 200 条记录 ...");
                more.setTextSize(12);
                more.setTextColor(AppColors.text2());
                more.setGravity(Gravity.CENTER);
                more.setPadding(0, (int)(12*d), 0, (int)(4*d));
                card.addView(more);
            }
        }

        body.addView(card);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(12*d), 0, 0);
        btnRow.setGravity(Gravity.CENTER);

        TextView clearBtn = new TextView(ctx);
        clearBtn.setText("清除记录");
        clearBtn.setTextSize(14);
        clearBtn.setTextColor(AppColors.accent());
        clearBtn.setPadding((int)(20*d), (int)(10*d), (int)(20*d), (int)(10*d));
        clearBtn.setPaintFlags(clearBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        clearBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(parentAct)
                .setTitle("确认清除")
                .setMessage("确定要清除所有通讯录变更记录吗？")
                .setPositiveButton("清除", (dialog, which) -> {
                    try {
                        ContactChangeLog.clearRecords();
                        // 原地刷新列表，避免 recreate() 重启整个微信设置 Activity
                        cardRef.removeAllViews();
                        TextView empty = new TextView(ctx);
                        empty.setText("暂无变更记录");
                        empty.setTextSize(14);
                        empty.setTextColor(AppColors.text2());
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding((int)(16*d), (int)(30*d), (int)(16*d), (int)(30*d));
                        cardRef.addView(empty);
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(ctx, "清除失败", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        });
        btnRow.addView(clearBtn);
        body.addView(btnRow);

        return body;
    }

    private static View recordRow(Context ctx, float d, ContactChangeRecord r) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
        String timeStr = sdf.format(new Date(r.time));

        TextView timeView = new TextView(ctx);
        timeView.setText(timeStr);
        timeView.setTextSize(11);
        timeView.setTextColor(AppColors.text2());
        timeView.setPadding(0, 0, (int)(10*d), 0);
        header.addView(timeView);

        TextView nameView = new TextView(ctx);
        nameView.setText(r.displayName());
        nameView.setTextSize(14);
        nameView.setTextColor(AppColors.text1());
        nameView.setTypeface(null, Typeface.BOLD);
        header.addView(nameView);

        row.addView(header);

        if (r.avatarChanged) {
            row.addView(avatarLine(ctx, d, r));
        }
        if (r.nicknameChanged) {
            row.addView(changeLine(ctx, d, "昵称", r.oldNickname, r.newNickname));
        }
        if (r.remarkChanged) {
            row.addView(changeLine(ctx, d, "备注", r.oldRemark, r.newRemark));
        }
        if (r.signatureChanged) {
            row.addView(changeLine(ctx, d, "签名", r.oldSignature, r.newSignature));
        }

        return row;
    }

    private static View changeLine(Context ctx, float d, String label, String oldVal, String newVal) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, (int)(4*d), 0, 0);

        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextSize(12);
        labelView.setTextColor(AppColors.accent());
        labelView.setPadding(0, 0, (int)(6*d), 0);
        line.addView(labelView);

        if (oldVal == null && newVal == null) {
            TextView valView = new TextView(ctx);
            valView.setText("已变更");
            valView.setTextSize(12);
            valView.setTextColor(AppColors.text2());
            line.addView(valView);
        } else {
            TextView oldView = new TextView(ctx);
            oldView.setText(oldVal != null ? oldVal : "(无)");
            oldView.setTextSize(12);
            oldView.setTextColor(AppColors.text2());
            line.addView(oldView);

            TextView arrow = new TextView(ctx);
            arrow.setText(" -> ");
            arrow.setTextSize(12);
            arrow.setTextColor(AppColors.text2());
            line.addView(arrow);

            TextView newView = new TextView(ctx);
            newView.setText(newVal != null ? newVal : "(无)");
            newView.setTextSize(12);
            newView.setTextColor(AppColors.text1());
            line.addView(newView);
        }

        return line;
    }

    private static View avatarLine(Context ctx, float d, ContactChangeRecord r) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, (int)(6 * d), 0, 0);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText("头像");
        label.setTextSize(12);
        label.setTextColor(AppColors.accent());
        label.setPadding(0, 0, (int)(6 * d), 0);
        line.addView(label);

        int avSize = (int)(32 * d);
        ImageView oldAv = makeAvatarView(ctx, avSize, d);
        ImageView newAv = makeAvatarView(ctx, avSize, d);

        loadAvatarAsync(oldAv, r.oldAvatarPath, avSize);
        loadAvatarAsync(newAv, r.newAvatarPath, avSize);

        line.addView(oldAv);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams((int)(8 * d), 0));
        line.addView(spacer);

        TextView arrow = new TextView(ctx);
        arrow.setText("→");
        arrow.setTextSize(12);
        arrow.setTextColor(AppColors.text2());
        arrow.setPadding(0, 0, (int)(8 * d), 0);
        line.addView(arrow);

        line.addView(newAv);

        return line;
    }

    private static ImageView makeAvatarView(Context ctx, int size, float d) {
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(size / 2f);
        bg.setColor(AppColors.divider());
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        return iv;
    }

    private static void loadAvatarAsync(ImageView iv, String path, int size) {
        if (path == null || path.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists()) return;
                Bitmap bm = BitmapFactory.decodeFile(path);
                if (bm != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bm, size, size, true);
                    iv.post(() -> iv.setImageBitmap(scaled));
                }
            } catch (Throwable ignored) {}
        }, "leshao-av-log").start();
    }

    private static View divider(Context ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(1*d)));
        v.setBackgroundColor(AppColors.divider());
        return v;
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
