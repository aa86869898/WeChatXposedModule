package com.leshao.v3.wm.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 朋友圈定时 — 复刻自微信大师 HomePlusHook
 */
public class WmHomeHook {

    public static void scheduledMoment(Activity act) {
        final EditText et = new EditText(act);
        et.setHint("朋友圈内容");
        et.setMinLines(2);
        final EditText dl = new EditText(act);
        dl.setHint("延迟秒数");
        dl.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.addView(et);
        ll.addView(dl);
        new AlertDialog.Builder(act).setTitle("定时朋友圈").setView(ll)
                .setPositiveButton("设置", (d, w) -> {
                    int sec;
                    try { sec = Integer.parseInt(dl.getText().toString().trim()); }
                    catch (Exception e) { sec = 300; }
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> toast(act, "定时朋友圈已触发"), sec * 1000L);
                    toast(act, "已设置" + sec + "秒后");
                }).setNegativeButton("取消", null).show();
    }

    static void toast(Activity a, String m) {
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show();
    }
}
