package com.leshao.wechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingService extends Service {
    private WindowManager wm;
    private View floatView;
    private static final String CHANNEL = "leshao_tts";
    private static boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "TTS播放", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("乐少助手TTS").setContentText("语音播放中").setSmallIcon(android.R.drawable.ic_media_play).build();
            startForeground(9999, n);
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    public static void show(Context ctx) {
        if (running) return;
        Intent i = new Intent(ctx, FloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
        running = true;
    }

    public static void hide(Context ctx) {
        ctx.stopService(new Intent(ctx, FloatingService.class));
        running = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (floatView != null) { wm.removeView(floatView); floatView = null; }
        int w = WindowManager.LayoutParams.WRAP_CONTENT;
        int h = WindowManager.LayoutParams.WRAP_CONTENT;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END; lp.x = 0; lp.y = 200;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(Utils.dp(this, 12), Utils.dp(this, 8), Utils.dp(this, 12), Utils.dp(this, 8));
        layout.setBackground(ThemeEngine.createPrimaryBtnBg(this, 12));

        TextView tv = new TextView(this);
        tv.setText("🎀 TTS播放中...");
        tv.setTextSize(12);
        tv.setTextColor(ThemeEngine.thWhite());
        layout.addView(tv);

        Button btn = ThemeEngine.createBtn(this, "停止");
        btn.setTextSize(10);
        btn.setBackground(ThemeEngine.createDangerBtnBg(this, 4));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { TTSManager.stopAll(); hide(FloatingService.this); }
        });
        layout.addView(btn);

        floatView = layout;
        wm.addView(floatView, lp);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (floatView != null) { wm.removeView(floatView); floatView = null; }
        super.onDestroy();
        running = false;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
