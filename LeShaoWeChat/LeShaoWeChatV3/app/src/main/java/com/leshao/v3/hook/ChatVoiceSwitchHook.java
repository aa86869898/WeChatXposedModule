package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.IconLoader;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.ContactSelectorView;
import com.leshao.v3.ui.TTSPageView;
import com.leshao.v3.wm.utils.WmReflect;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信聊天输入框上方注入 "音色切换" 与 "万群转发" 按钮（渐变流体糖果霓虹风格）
 * 多方案兜底：
 *   方案A：Hook MMEditText 构造函数
 *   方案B：Hook TextView.onTextChanged 兜底捕获输入框
 *   方案C：Hook EditText 构造函数兜底
 *   方案D：View.onAttachedToWindow 兜底
 *   方案E：Activity onResume 主动扫描
 */
public final class ChatVoiceSwitchHook {

    private static final String TAG = "ChatVoiceSwitchHook";
    private static final String MM_EDIT_TEXT = "com.tencent.mm.ui.widget.MMEditText";
    private static final String MAX_HEIGHT_SCROLL = "com.tencent.mm.view.MaxHeightScrollView";

    // 糖果霓虹渐变（粉 → 霓虹粉 → 紫 → 青）
    private static final int[] NEON_GRADIENT = new int[]{
            0xFFFF94C2, 0xFFFF10F0, 0xFF7B2FF7, 0xFF36D1E8
    };

    // 原文字 12 → 放大 = 14sp
    private static final int BTN_TEXT_SIZE = 14;
    private static final long INJECT_DELAY_MS = 600;
    private static final int MAX_RETRY = 10;

    // 用 view tag 防止同一输入框重复注入，同时避免多方案竞争
    private static final Object PENDING_TAG = new Object();
    private static final Object INJECTED_TAG = new Object();

    private ChatVoiceSwitchHook() {
    }

    public static void init(final ClassLoader loader) {
        hookMMEditText(loader, 0);
        hookGenericEditText();
        hookEditTextConstructors();
        hookOnAttachedToWindow();
        hookActivityResume(loader);
    }

    // ==================== 方案A：Hook MMEditText 构造器 ====================

    private static void hookMMEditText(final ClassLoader loader, final int attempt) {
        try {
            Class<?> editClazz = findClassIfExists(MM_EDIT_TEXT, loader);
            if (editClazz == null) {
                retryHook(loader, attempt);
                return;
            }
            for (Constructor<?> c : editClazz.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LogWriter.log(TAG, "方案A: MMEditText 构造触发");
                        scheduleInject(param.thisObject);
                    }
                });
            }
            LogWriter.log(TAG, "方案A: MMEditText Hook 已挂载");
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案A hook 异常: " + t);
            retryHook(loader, attempt);
        }
    }

    private static Class<?> findClassIfExists(String className, ClassLoader loader) {
        try {
            return XposedHelpers.findClass(className, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void retryHook(final ClassLoader loader, final int attempt) {
        if (attempt > MAX_RETRY) {
            LogWriter.log(TAG, "方案A 重试超限");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hookMMEditText(loader, attempt + 1);
            }
        }, 2000);
    }

    // ==================== 方案B：通用捕获（任何版本都有效） ====================

    private static void hookGenericEditText() {
        try {
            XposedBridge.hookAllMethods(TextView.class, "onTextChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object tv = param.thisObject;
                    if (tv instanceof EditText && isMMEditText((View) tv)) {
                        LogWriter.log(TAG, "方案B: 捕获到输入框 " + tv.getClass().getName());
                        scheduleInject((View) tv);
                    }
                }
            });
            LogWriter.log(TAG, "方案B: TextView.onTextChanged 兜底已挂载");
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B 挂载失败: " + t);
        }
    }

    // ==================== 方案C：Hook EditText 构造函数兜底 ====================

    private static void hookEditTextConstructors() {
        try {
            XposedBridge.hookAllConstructors(EditText.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) return;
                    if (isMMEditText((View) param.thisObject)) {
                        LogWriter.log(TAG, "方案C: EditText 构造触发 " + param.thisObject.getClass().getName());
                        scheduleInject(param.thisObject);
                    }
                }
            });
            LogWriter.log(TAG, "方案C: EditText 构造 Hook 已挂载");
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案C 挂载失败: " + t);
        }
    }

    // ==================== 方案D：onAttachedToWindow 兜底 ====================

    private static void hookOnAttachedToWindow() {
        try {
            XposedBridge.hookAllMethods(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) return;
                    if (isMMEditText((View) param.thisObject)) {
                        LogWriter.log(TAG, "方案D: onAttachedToWindow 捕获 " + param.thisObject.getClass().getName());
                        scheduleInject((View) param.thisObject);
                    }
                }
            });
            LogWriter.log(TAG, "方案D: View.onAttachedToWindow 兜底已挂载");
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案D 挂载失败: " + t);
        }
    }

    // ==================== 方案E：Activity onResume 主动扫描兜底 ====================

    private static void hookActivityResume(final ClassLoader loader) {
        try {
            Class<?> activityCls = XposedHelpers.findClass("android.app.Activity", loader);
            XposedBridge.hookAllMethods(activityCls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Activity act = (Activity) param.thisObject;
                    if (act == null) return;
                    if (!isChatPage(act)) return;
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            scanAndInject(act);
                        }
                    }, 1200);
                }
            });
            LogWriter.log(TAG, "方案E: Activity.onResume 兜底已挂载");
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案E 挂载失败: " + t);
        }
    }

    private static boolean isChatPage(Activity act) {
        String name = act.getClass().getName().toLowerCase();
        // 默认允许注入，仅排除已知非聊天页面
        if (name.contains("luckymoney")) return false;
        if (name.contains("redpacket") || name.contains("collection")) return false;
        if (name.contains("setting") || name.contains("profile")
            || name.contains("plugin") || name.contains("webview")) return false;
        return true;
    }

    private static void scanAndInject(Activity act) {
        try {
            View decor = act.getWindow().getDecorView();
            if (decor == null) return;
            View target = findMMEditText(decor);
            if (target != null) {
                LogWriter.log(TAG, "方案E: 扫描到输入框 " + target.getClass().getName());
                scheduleInject(target);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案E 扫描异常: " + t);
        }
    }

    private static View findMMEditText(View view) {
        if (isMMEditText(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findMMEditText(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== 注入 ====================

    private static boolean isMMEditText(View view) {
        if (view == null) return false;
        return MM_EDIT_TEXT.equals(view.getClass().getName());
    }

    private static void scheduleInject(final Object editObj) {
        if (editObj == null) return;
        final View edit = (View) editObj;
        if (!isMMEditText(edit)) return;

        Context ctx = edit.getContext();
        if (ctx == null) return;
        Activity act = getActivityFromContext(ctx);
        if (act == null || !isChatPage(act)) return;

        if (edit.getContext().getSharedPreferences("wm_prefs", 0)
                .getBoolean("input_buttons", true) == false) return;

        // 确保注入目标不是弹窗中的输入框
        try {
            View root = edit.getRootView();
            if (root != null) {
                String rootCls = root.getClass().getName();
                if (rootCls.contains("Popup") || rootCls.contains("Dialog")
                    || rootCls.contains("popup") || rootCls.contains("dialog")) {
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // 多方案竞争防护：PENDING 表示已有方案在排队注入
        if (edit.getTag() == PENDING_TAG || edit.getTag() == INJECTED_TAG) return;
        edit.setTag(PENDING_TAG);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (edit.getTag() == INJECTED_TAG) return;
                    Context ctx = edit.getContext();
                    if (ctx == null) return;
                    View row = createButtonRow(ctx);
                    if (injectAt(edit, row)) {
                        edit.setTag(INJECTED_TAG);
                        LogWriter.log(TAG, "注入成功!");
                    } else {
                        edit.setTag(null);
                        LogWriter.log(TAG, "注入失败: 找不到合适的父容器");
                    }
                } catch (Throwable t) {
                    edit.setTag(null);
                    LogWriter.log(TAG, "注入异常: " + t);
                }
            }
        }, INJECT_DELAY_MS);
    }

    /** 多级兜底注入：返回是否成功 */
    private static boolean injectAt(View edit, View row) {
        // 尝试1：MaxHeightScrollView 祖先 → 其父 RelativeLayout → 祖父 LinearLayout
        View mhs = findAncestor(edit, MAX_HEIGHT_SCROLL, 8);
        if (mhs != null) {
            ViewParent rel = mhs.getParent();
            if (rel != null && rel.getParent() instanceof LinearLayout) {
                LinearLayout grand = (LinearLayout) rel.getParent();
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                grand.addView(row, grand.indexOfChild((View) rel));
                return true;
            }
            if (rel instanceof ViewGroup) {
                ViewGroup relVg = (ViewGroup) rel;
                row.setLayoutParams(new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                relVg.addView(row, relVg.indexOfChild(mhs));
                return true;
            }
        }
        // 尝试2：输入框行的父级 LinearLayout
        ViewParent rowContainer = edit.getParent();
        if (rowContainer != null && rowContainer.getParent() instanceof LinearLayout) {
            LinearLayout grand = (LinearLayout) rowContainer.getParent();
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            grand.addView(row, grand.indexOfChild((View) rowContainer));
            return true;
        }
        // 尝试3：输入框祖父容器，插到最前面
        ViewParent pp = edit.getParent() == null ? null : edit.getParent().getParent();
        if (pp instanceof ViewGroup) {
            ViewGroup ppVg = (ViewGroup) pp;
            row.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ppVg.addView(row, 0);
            return true;
        }
        return false;
    }

    private static View findAncestor(View view, String className, int maxDepth) {
        ViewParent node = view.getParent();
        int depth = 0;
        while (node != null && depth < maxDepth) {
            if (node.getClass().getName().equals(className)) {
                return (View) node;
            }
            node = node.getParent();
            depth++;
        }
        return null;
    }

    // ==================== UI ====================

    private static android.widget.HorizontalScrollView createButtonRow(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (5 * density),
                0, (int) (5 * density));

        // 音色：文字蓝色 + 音色列表图标（细边框背景）
        TextView btn = createBlueButton(ctx, "音色", IconLoader.IC_VOICE_LIST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTtsPage(ctx);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, (int) (6 * density), 0);
        btn.setLayoutParams(lp);
        row.addView(btn);

        // 群发：文字蓝色 + 定时群发图标（细边框背景），点击进入乐少万群定时群发
        TextView schedBtn = createBlueButton(ctx, "群发", IconLoader.IC_SCHEDULE_SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScheduledSend(ctx);
            }
        });
        LinearLayout.LayoutParams lpSched = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSched.setMargins(0, 0, (int) (6 * density), 0);
        schedBtn.setLayoutParams(lpSched);
        row.addView(schedBtn);

        // 助手：文字蓝色 + 细边框背景，点击打开功能面板
        TextView masterBtn = createBlueButton(ctx, "助手", IconLoader.IC_SCHEDULE_SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMasterPanel(ctx, v);
            }
        });
        LinearLayout.LayoutParams lpMaster = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpMaster.setMargins(0, 0, (int) (6 * density), 0);
        masterBtn.setLayoutParams(lpMaster);
        row.addView(masterBtn);

        // 语音：文字蓝色 + 细边框背景，点击打开音频选择面板
        TextView mp3Btn = createBlueButton(ctx, "语音", IconLoader.IC_SCHEDULE_SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.leshao.v3.ChatFooterLongPressMenu.showPanelStatic(v);
            }
        });
        mp3Btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(mp3Btn);

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(0, 0, 0, 0);
        scroll.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** 蓝色文字 + 图标 + 细边框背景的按钮（糖果霓虹风格） */
    private static TextView createBlueButton(final Context ctx, String text,
                                             final int iconId, View.OnClickListener listener) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(BTN_TEXT_SIZE);
        btn.setTextColor(AppColors.accent());
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);

        float density = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setStroke((int)(1 * density), AppColors.accent());
        bg.setCornerRadius((int)(4 * density));
        btn.setBackground(bg);

        // 图标（若加载失败则仅显示文字）
        IconLoader.setCompoundLeft(btn, IconLoader.load(ctx, iconId, 20));

        btn.setPadding((int) (7 * density), (int) (7 * density),
                (int) (7 * density), (int) (7 * density));
        btn.setOnClickListener(listener);
        return btn;
    }

    /** 定时群发：模块联系人选择器勾选群 + 输入内容 + 定时 → 发送 */
    private static void openScheduledSend(Context ctx) {
        final Activity act = getActivityFromContext(ctx);
        if (act == null) {
            LogWriter.log(TAG, "定时群发: 无法获取 Activity");
            return;
        }
        try {
            // 使用模块联系人选择器（MODE_ALL）支持好友+群聊
            ContactSelectorView.show(act, false, ContactSelectorView.MODE_ALL, selected -> {
                if (selected == null || selected.isEmpty()) {
                    Toast.makeText(act, "未选择联系人", Toast.LENGTH_SHORT).show();
                    return;
                }
                final List<String> rooms = new ArrayList<>();
                for (com.leshao.v3.model.ContactCard c : selected) rooms.add(c.username);

                final EditText et = new EditText(act);
                et.setHint("输入要定时群发的内容");
                et.setMinLines(2);

                final long[] triggerMs = {0};
                final TextView timeLabel = new TextView(act);
                timeLabel.setText("发送时间: 立即发送");
                timeLabel.setTextSize(13);
                timeLabel.setTextColor(AppColors.text2());
                timeLabel.setPadding(0, dp(act, 8), 0, 0);

                Button timeBtn = new Button(act);
                timeBtn.setText("选择定时时间");
                timeBtn.setTextSize(13);
                timeBtn.setAllCaps(false);
                timeBtn.setTextColor(AppColors.WHITE_TEXT);
                GradientDrawable tbBg = new GradientDrawable();
                tbBg.setColor(AppColors.accent());
                tbBg.setCornerRadius(dp(act, 8));
                timeBtn.setBackground(tbBg);
                timeBtn.setOnClickListener(v -> showDateTimePicker(act, triggerMs, timeLabel));

                LinearLayout content = new LinearLayout(act);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(act, 20), 0, dp(act, 20), 0);
                content.addView(et);
                content.addView(timeLabel);
                content.addView(timeBtn);

                new AlertDialog.Builder(act)
                        .setTitle("定时群发到 " + rooms.size() + " 个联系人")
                        .setView(content)
                        .setPositiveButton("发送", (d, w) -> {
                            String msg = et.getText().toString().trim();
                            if (msg.isEmpty()) {
                                Toast.makeText(act, "内容不能为空", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            ClassLoader cl = ContextManager.getClassLoader();
                            if (cl == null) {
                                Toast.makeText(act, "ClassLoader 不可用", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (triggerMs[0] > 0 && triggerMs[0] > System.currentTimeMillis()) {
                                long delay = triggerMs[0] - System.currentTimeMillis();
                                new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        WmReflect.broadcastRooms(cl, rooms, msg);
                                    } catch (Throwable t) {
                                        LogWriter.log(TAG, "定时群发执行异常: " + t.getMessage());
                                    }
                                }, delay);
                                Toast.makeText(act, "已定时, " + delay / 1000 + " 秒后发送到 " + rooms.size() + " 个群", Toast.LENGTH_SHORT).show();
                            } else {
                                WmReflect.broadcastRooms(cl, rooms, msg);
                                Toast.makeText(act, "已发送到 " + rooms.size() + " 个群", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "定时群发异常: " + t.getMessage());
            Toast.makeText(act, "定时群发暂不可用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 日期+时间选择器（复用微信内置风格） */
    private static void showDateTimePicker(final Activity act, final long[] result, final TextView label) {
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(act, (view, year, month, dayOfMonth) -> {
            final int y = year, mo = month, d = dayOfMonth;
            new android.app.TimePickerDialog(act, (tv, hour, minute) -> {
                cal.set(y, mo, d, hour, minute, 0);
                result[0] = cal.getTimeInMillis();
                label.setText("发送时间: " + new java.text.SimpleDateFormat("MM-dd HH:mm")
                        .format(new java.util.Date(result[0])));
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private static int dp(Context ctx, int d) {
        return (int) (d * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void openTtsPage(Context ctx) {
        Activity act = getActivityFromContext(ctx);
        if (act == null) {
            LogWriter.log(TAG, "无法获取 Activity");
            return;
        }
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            TTSPageView.showTtsCubeDialog(act, act, density);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开音色选择失败: " + t.getMessage());
        }
    }

    private static void openMasterPanel(Context ctx, View anchor) {
        Activity act = getActivityFromContext(ctx);
        if (act == null) return;
        try {
            com.leshao.v3.wm.hook.WmChatHook.showPanelInline(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开乐少大师面板失败: " + t.getMessage());
        }
    }

    private static Activity getActivityFromContext(Context ctx) {
        if (ctx instanceof Activity) return (Activity) ctx;
        if (ctx instanceof android.content.ContextWrapper) {
            Context base = ((android.content.ContextWrapper) ctx).getBaseContext();
            while (base != null) {
                if (base instanceof Activity) return (Activity) base;
                if (base instanceof android.content.ContextWrapper) {
                    base = ((android.content.ContextWrapper) base).getBaseContext();
                } else {
                    break;
                }
            }
        }
        return null;
    }
}
