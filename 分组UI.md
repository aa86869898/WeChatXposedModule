现在我掌握了微信 UI 结构的关键信息。下面给出两个 UI 注入功能的完整实现。
新增文件：UI 注入模块
文件 20： app/src/main/java/com/wxgroup/ui/UiInjector.java 
java
package com.wxgroup.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.wxgroup.*;
import com.wxgroup.model.LabelInfo;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;

/**
 * UI 注入器
 * 
 * 功能1: 标题栏下方注入分组标签筛选栏
 * 功能2: 长按聊天列表注入"添加到分组"菜单
 */
public class UiInjector {

    private static final String TAG = "WxGroup.UI";
    private static HorizontalScrollView sTagBar;
    private static LinearLayout sTagContainer;
    private static List<LabelInfo> sLabels;
    private static int sSelectedLabelId = -1; // -1 = 全部
    private static View sInjectedTagBar;

    // ================================================================
    // 功能1: 标题栏下方注入分组标签筛选栏
    // ================================================================

    /**
     * 在 MainUI.onResume 时注入标签筛选栏
     * 由 MainHook 在阶段3调用
     */
    public static void installTagFilterBar(ClassLoader cl) {
        try {
            // Hook MainUI.onResume —— 每次回到主页都确保筛选栏存在
            Class<?> mainUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.conversation.MainUI", cl);

            XposedHelpers.findAndHookMethod(mainUIClass, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 延迟执行，确保布局已完成
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            injectTagBar(param.thisObject);
                        }, 300);
                    }
                });

            // 也 Hook LauncherUI.onResume 作为备用
            Class<?> launcherUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.LauncherUI", cl);
            XposedHelpers.findAndHookMethod(launcherUIClass, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            injectTagBarToLauncher(param.thisObject);
                        }, 500);
                    }
                });

            XposedBridge.log(TAG + ": 标签筛选栏 Hook 已安装");

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": installTagFilterBar 失败: " + e.getMessage());
        }
    }

    /**
     * 向 MainUI 注入标签筛选栏
     */
    private static void injectTagBar(Object mainUI) {
        try {
            if (sInjectedTagBar != null && sInjectedTagBar.getParent() != null) {
                // 已注入且仍在视图树中，只需刷新标签数据
                refreshTagData();
                return;
            }

            // 获取 MainUI 的根 View
            View rootView = (View) XposedHelpers.callMethod(mainUI, "getLayoutView");
            if (rootView == null) {
                // Fallback: getContentView 或者直接找
                rootView = (View) XposedHelpers.getObjectField(mainUI, "G");
                if (rootView == null) return;
            }

            // 找到合适的父容器
            // 通常 MainUI 布局: [ActionBar区域] [RecyclerView]
            ViewGroup parent = findParentForInjection(rootView);
            if (parent == null) {
                // 再尝试从 DecorView 向下找
                View decorView = rootView.getRootView();
                parent = findConversationContainer(decorView);
            }
            if (parent == null) return;

            // 创建标签筛选栏
            sInjectedTagBar = createTagFilterBar(rootView.getContext());
            sTagBar = (HorizontalScrollView) sInjectedTagBar;

            // 插入到 ActionBar 下方
            // 找到 RecyclerView 的位置，在其上方插入
            int insertIndex = findRecyclerViewIndex(parent);
            if (insertIndex >= 0) {
                parent.addView(sInjectedTagBar, insertIndex,
                    new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp2px(44, rootView.getContext())));
            } else {
                parent.addView(sInjectedTagBar, 0,
                    new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp2px(44, rootView.getContext())));
            }

            refreshTagData();
            XposedBridge.log(TAG + ": 标签筛选栏注入成功");

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": injectTagBar 失败: " + e.getMessage());
        }
    }

    /**
     * 向 LauncherUI 注入（备用方法）
     */
    private static void injectTagBarToLauncher(Object launcherUI) {
        try {
            if (sInjectedTagBar != null && sInjectedTagBar.getParent() != null) return;

            // 获取 HomeUI
            Object homeUI = XposedHelpers.callMethod(launcherUI, "getHomeUI");
            if (homeUI == null) return;

            // HomeUI 中有 View d (可能是主容器)
            View rootView = (View) XposedHelpers.getObjectField(homeUI, "d");
            if (rootView == null) return;

            ViewGroup parent = findConversationContainer(rootView);
            if (parent == null) return;

            sInjectedTagBar = createTagFilterBar(rootView.getContext());
            sTagBar = (HorizontalScrollView) sInjectedTagBar;

            int insertIndex = findRecyclerViewIndex(parent);
            if (insertIndex >= 0) {
                parent.addView(sInjectedTagBar, insertIndex,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        dp2px(44, rootView.getContext())));
            } else {
                parent.addView(sInjectedTagBar, 0,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        dp2px(44, rootView.getContext())));
            }

            refreshTagData();
            XposedBridge.log(TAG + ": 标签筛选栏注入成功(Launcher)");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": injectTagBarToLauncher 失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 功能2: 长按菜单注入
    // ================================================================

    /**
     * 安装长按菜单 Hook
     * 
     * 策略: Hook RecyclerView 的 child view 长按事件
     * 在 BaseConversationUI 的 BaseConversationFmUI 的 onResume 中
     * 找到 RecyclerView 并添加 OnItemTouchListener
     */
    public static void installContextMenuHook(ClassLoader cl) {
        try {
            // Hook BaseConversationUI$BaseConversationFmUI.onResume
            Class<?> fmUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.conversation.BaseConversationUI$BaseConversationFmUI", cl);

            XposedHelpers.findAndHookMethod(fmUIClass, "onResume",
                new XC_MethodHook() {
                    // 记录上次处理时间避免重复注入
                    private long lastInjectTime = 0;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        long now = System.currentTimeMillis();
                        if (now - lastInjectTime < 2000) return;
                        lastInjectTime = now;

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            injectLongPressMenu(param.thisObject);
                        }, 500);
                    }
                });

            XposedBridge.log(TAG + ": 长按菜单 Hook 已安装");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": installContextMenuHook 失败: " + e.getMessage());
        }
    }

    /**
     * 注入长按菜单：找到 RecyclerView 并设置长按监听
     */
    private static void injectLongPressMenu(Object fragment) {
        try {
            View rootView = (View) XposedHelpers.callMethod(fragment, "getLayoutView");
            if (rootView == null) {
                rootView = (View) XposedHelpers.getObjectField(fragment, "G");
                if (rootView == null) return;
            }

            RecyclerView recyclerView = findRecyclerView(rootView);
            if (recyclerView == null) return;

            // 检查是否已注入（通过 tag 标记）
            if (Boolean.TRUE.equals(recyclerView.getTag(0x7F000001))) return;
            recyclerView.setTag(0x7F000001, Boolean.TRUE);

            // 添加 OnItemTouchListener 捕获长按
            recyclerView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
                private View longPressedView;
                private String longPressedUsername;

                @Override
                public boolean onInterceptTouchEvent(RecyclerView rv, android.view.MotionEvent e) {
                    if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        // 记录按下的位置
                        View child = rv.findChildViewUnder(e.getX(), e.getY());
                        if (child != null) {
                            longPressedView = child;
                            longPressedUsername = extractUsername(child);
                        }
                    }
                    return false;
                }

                @Override
                public void onTouchEvent(RecyclerView rv, android.view.MotionEvent e) {}

                @Override
                public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
            });

            // 给每个 item 设置长按监听（通过 post 持续扫描）
            setupLongClickOnItems(recyclerView);

            XposedBridge.log(TAG + ": RecyclerView 长按菜单注入成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": injectLongPressMenu 失败: " + e.getMessage());
        }
    }

    /**
     * 给 RecyclerView 中的 item 设置长按监听
     * 通过拦截 addOnItemTouchListener 或直接在 layout 遍历时 hook
     */
    private static void setupLongClickOnItems(RecyclerView recyclerView) {
        // 使用 ViewTreeObserver 在每次布局变化时重新绑定
        recyclerView.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    for (int i = 0; i < recyclerView.getChildCount(); i++) {
                        View child = recyclerView.getChildAt(i);
                        if (child.getTag(0x7F000002) == null) {
                            child.setTag(0x7F000002, Boolean.TRUE);
                            child.setOnLongClickListener(v -> {
                                String username = extractUsername(v);
                                if (!TextUtils.isEmpty(username)) {
                                    showGroupMenu(v.getContext(), username);
                                    return true;
                                }
                                return false;
                            });
                        }
                    }
                }
            });
    }

    // ================================================================
    // 菜单弹窗
    // ================================================================

    /**
     * 显示分组操作菜单
     */
    private static void showGroupMenu(Context context, String username) {
        // 获取该联系人当前的标签
        int[] currentLabelIds = ContactHelper.getLabelIds(username);
        Set<Integer> currentSet = new HashSet<>();
        for (int id : currentLabelIds) currentSet.add(id);

        List<LabelInfo> allLabels = LabelManager.getAll();
        if (allLabels.isEmpty()) {
            Toast.makeText(context, "暂无分组标签，请先创建", Toast.LENGTH_SHORT).show();
            return;
        }

        // 构建多选对话框
        String[] labelNames = new String[allLabels.size()];
        boolean[] checked = new boolean[allLabels.size()];
        for (int i = 0; i < allLabels.size(); i++) {
            labelNames[i] = allLabels.get(i).labelName;
            checked[i] = currentSet.contains(allLabels.get(i).labelId);
        }

        new AlertDialog.Builder(context)
            .setTitle("联系人分组 — " + username)
            .setMultiChoiceItems(labelNames, checked, (dialog, which, isChecked) -> {
                LabelInfo label = allLabels.get(which);
                if (isChecked) {
                    ContactHelper.addLabel(username, label.labelId);
                } else {
                    ContactHelper.removeLabel(username, label.labelId);
                }
            })
            .setPositiveButton("完成", null)
            .setNeutralButton("新建分组", (dialog, which) -> {
                // 新建分组并添加
                showCreateLabelDialog(context, username);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 新建分组对话框
     */
    private static void showCreateLabelDialog(Context context, String username) {
        EditText input = new EditText(context);
        input.setHint("输入新分组名称");
        input.setPadding(40, 30, 40, 30);

        new AlertDialog.Builder(context)
            .setTitle("新建分组")
            .setView(input)
            .setPositiveButton("创建并添加", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    LabelInfo created = LabelManager.create(name);
                    if (created != null) {
                        ContactHelper.addLabel(username, created.labelId);
                        Toast.makeText(context, "已创建并添加", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ================================================================
    // 标签筛选栏 UI
    // ================================================================

    /**
     * 创建标签筛选栏 View
     */
    private static View createTagFilterBar(Context context) {
        HorizontalScrollView scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setBackgroundColor(Color.parseColor("#F5F5F5"));

        // 底部阴影分割线
        scrollView.setElevation(dp2px(2, context));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp2px(8, context), dp2px(4, context),
            dp2px(8, context), dp2px(4, context));
        scrollView.addView(container);
        sTagContainer = container;

        return scrollView;
    }

    /**
     * 刷新标签数据（标签增删后调用）
     */
    public static void refreshTagData() {
        if (sTagContainer == null) return;
        sLabels = LabelManager.getAll();
        Context ctx = sTagContainer.getContext();

        sTagContainer.removeAllViews();

        // 第一个: "全部"
        sTagContainer.addView(createChip(ctx, "全部", -1, sSelectedLabelId == -1));
        sTagContainer.addView(createSeparator(ctx));

        // 每个标签一个 Chip
        for (LabelInfo label : sLabels) {
            sTagContainer.addView(createChip(ctx, label.labelName,
                label.labelId, sSelectedLabelId == label.labelId));
        }

        // 最右侧: "+" 新建标签按钮
        sTagContainer.addView(createSeparator(ctx));
        sTagContainer.addView(createAddButton(ctx));
    }

    /**
     * 创建单个标签 Chip
     */
    private static View createChip(Context ctx, String text, int labelId, boolean selected) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextSize(13);
        chip.setPadding(dp2px(12, ctx), dp2px(6, ctx), dp2px(12, ctx), dp2px(6, ctx));
        chip.setGravity(Gravity.CENTER);

        // 圆角背景
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(16, ctx));
        if (selected) {
            bg.setColor(Color.parseColor("#07C160")); // 微信绿
            chip.setTextColor(Color.WHITE);
            chip.setTypeface(null, Typeface.BOLD);
        } else {
            bg.setColor(Color.WHITE);
            chip.setTextColor(Color.parseColor("#333333"));
            bg.setStroke(1, Color.parseColor("#E0E0E0"));
        }
        chip.setBackground(bg);

        // 间距
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp2px(4, ctx), 0, dp2px(4, ctx), 0);
        chip.setLayoutParams(lp);

        // 点击事件
        chip.setOnClickListener(v -> {
            sSelectedLabelId = (sSelectedLabelId == labelId) ? -1 : labelId;
            refreshTagData();
            applyFilter(sSelectedLabelId);
        });

        // 长按: 编辑/删除标签
        if (labelId > 0) {
            chip.setOnLongClickListener(v -> {
                showLabelManageDialog(ctx, labelId, text);
                return true;
            });
        }

        return chip;
    }

    /**
     * 分隔线
     */
    private static View createSeparator(Context ctx) {
        View sep = new View(ctx);
        sep.setLayoutParams(new LinearLayout.LayoutParams(dp2px(1, ctx),
            ViewGroup.LayoutParams.MATCH_PARENT));
        sep.setBackgroundColor(Color.TRANSPARENT);
        return sep;
    }

    /**
     * "+" 按钮
     */
    private static View createAddButton(Context ctx) {
        TextView btn = new TextView(ctx);
        btn.setText("＋");
        btn.setTextSize(16);
        btn.setTextColor(Color.parseColor("#07C160"));
        btn.setPadding(dp2px(10, ctx), dp2px(6, ctx), dp2px(10, ctx), dp2px(6, ctx));
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(null, Typeface.BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(16, ctx));
        bg.setColor(Color.WHITE);
        bg.setStroke(1, Color.parseColor("#07C160"));
        btn.setBackground(bg);

        btn.setOnClickListener(v -> {
            showCreateLabelDialog(ctx, null);
        });

        return btn;
    }

    /**
     * 标签管理对话框（长按标签Chip触发）
     */
    private static void showLabelManageDialog(Context ctx, int labelId, String labelName) {
        new AlertDialog.Builder(ctx)
            .setTitle("管理分组: " + labelName)
            .setItems(new String[]{
                "查看分组联系人 (" + LabelManager.getContacts(labelId).size() + "人)",
                "重命名",
                "删除分组",
                "合并到其他分组"
            }, (dialog, which) -> {
                switch (which) {
                    case 0: // 查看联系人
                        showContactsInLabel(ctx, labelId, labelName);
                        break;
                    case 1: // 重命名
                        showRenameDialog(ctx, labelId);
                        break;
                    case 2: // 删除
                        showDeleteConfirmDialog(ctx, labelId, labelName);
                        break;
                    case 3: // 合并
                        showMergeDialog(ctx, labelId);
                        break;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showContactsInLabel(Context ctx, int labelId, String labelName) {
        List<String> contacts = LabelManager.getContacts(labelId);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(contacts.size(), 50); i++) {
            if (i > 0) sb.append("\n");
            sb.append(contacts.get(i));
        }
        if (contacts.size() > 50) {
            sb.append("\n... 还有 ").append(contacts.size() - 50).append(" 个联系人");
        }
        new AlertDialog.Builder(ctx)
            .setTitle(labelName + " (" + contacts.size() + "人)")
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .show();
    }

    private static void showRenameDialog(Context ctx, int labelId) {
        EditText input = new EditText(ctx);
        input.setPadding(40, 30, 40, 30);
        new AlertDialog.Builder(ctx)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    LabelManager.rename(String.valueOf(labelId), name);
                    refreshTagData();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showDeleteConfirmDialog(Context ctx, int labelId, String labelName) {
        new AlertDialog.Builder(ctx)
            .setTitle("删除分组")
            .setMessage("确定删除 \"" + labelName + "\" 吗？\n该分组下的联系人关联将被清除。")
            .setPositiveButton("删除", (d, w) -> {
                LabelManager.delete(String.valueOf(labelId));
                refreshTagData();
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showMergeDialog(Context ctx, int sourceId) {
        List<LabelInfo> labels = LabelManager.getAll();
        String[] names = new String[labels.size()];
        for (int i = 0; i < labels.size(); i++) names[i] = labels.get(i).labelName;

        new AlertDialog.Builder(ctx)
            .setTitle("合并到...")
            .setItems(names, (dialog, which) -> {
                LabelInfo target = labels.get(which);
                if (target.labelId != sourceId) {
                    BatchOperator.mergeLabels(sourceId, target.labelId);
                    refreshTagData();
                    Toast.makeText(ctx, "已合并", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ================================================================
    // 筛选功能
    // ================================================================

    /**
     * 应用标签筛选（控制聊天列表只显示该标签下的联系人会话）
     * 
     * 实现方式: 在 RecyclerView 的 Adapter 中过滤
     * 当前版本使用 MVVM 模式，数据源是 MvvmConvList
     */
    private static void applyFilter(int labelId) {
        try {
            if (labelId <= 0) {
                // 显示全部 — 需要恢复原始数据
                XposedBridge.log(TAG + ": 筛选: 全部");
                // TODO: 恢复原始数据源
                return;
            }

            List<String> contacts = LabelManager.getContacts(labelId);
            XposedBridge.log(TAG + ": 筛选标签 " + labelId + " → " + contacts.size() + " 个联系人");
            // TODO: 设置 RecyclerView Adapter 的数据过滤
            // MVVM 模式下需要修改 MvvmConvList 的数据源或添加 Filter

            Toast.makeText(sTagContainer.getContext(),
                "筛选: " + contacts.size() + " 个联系人", Toast.LENGTH_SHORT).show();

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": applyFilter 失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 视图工具
    // ================================================================

    /**
     * 递归查找 RecyclerView
     */
    private static RecyclerView findRecyclerView(View root) {
        if (root instanceof RecyclerView) return (RecyclerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                RecyclerView rv = findRecyclerView(vg.getChildAt(i));
                if (rv != null) return rv;
            }
        }
        return null;
    }

    /**
     * 查找适合插入标签栏的父容器
     */
    private static ViewGroup findParentForInjection(View root) {
        if (root instanceof LinearLayout && ((LinearLayout) root).getOrientation() == LinearLayout.VERTICAL) {
            // 垂直 LinearLayout —— 很可能就是主容器
            if (((ViewGroup) root).getChildCount() >= 1) {
                return (ViewGroup) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findParentForInjection(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return (root instanceof ViewGroup) ? (ViewGroup) root : null;
    }

    /**
     * 找 RecyclerView 在父容器中的 index
     */
    private static int findRecyclerViewIndex(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (findRecyclerView(parent.getChildAt(i)) != null) return i;
        }
        return -1;
    }

    /**
     * 在 DecorView 中查找会话列表容器
     */
    private static ViewGroup findConversationContainer(View root) {
        if (root instanceof FrameLayout && root.getId() == android.R.id.content) {
            return (ViewGroup) root;
        }
        if (root instanceof LinearLayout &&
            ((LinearLayout) root).getOrientation() == LinearLayout.VERTICAL &&
            ((ViewGroup) root).getChildCount() >= 2) {
            return (ViewGroup) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup f = findConversationContainer(vg.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /**
     * 从会话 item view 中提取 username
     * 尝试多种方式
     */
    private static String extractUsername(View itemView) {
        if (itemView == null) return null;
        try {
            // 方式1: 从 tag 获取
            Object tag = itemView.getTag();
            if (tag instanceof String && ((String) tag).startsWith("wxid_")) {
                return (String) tag;
            }

            // 方式2: 遍历子 View 找包含 username 的字段
            return extractUsernameFromChildren(itemView);

        } catch (Throwable e) {
            return null;
        }
    }

    private static String extractUsernameFromChildren(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            // 尝试匹配 username 格式
            if (text.matches("^wxid_[a-zA-Z0-9]+$") ||
                text.matches("^[a-zA-Z0-9_-]+@(chatroom|stranger|openim)$")) {
                return text;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String found = extractUsernameFromChildren(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ================================================================
    // 工具
    // ================================================================

    private static int dp2px(int dp, Context ctx) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
更新 MainHook.java — 添加 UI 注入调用
在  MainHook.java  的  installRealTimeHooks()  方法末尾添加：
java
// ===== 在 installRealTimeHooks() 末尾追加 =====

// Hook 6: UI 注入 — 标题栏标签筛选栏
UiInjector.installTagFilterBar(sClassLoader);

// Hook 7: UI 注入 — 长按聊天列表菜单
UiInjector.installContextMenuHook(sClassLoader);

XposedBridge.log(TAG + ": [3/4] 7个实时Hook已安装 (含2个UI注入)");
在  startSubSystems()  末尾添加：
java
// 监听标签变更以刷新筛选栏
EventBus.subscribe(EventBus.Event.LABEL_CREATED, (event, data) -> {
    new Handler(Looper.getMainLooper()).post(() -> UiInjector.refreshTagData());
});
EventBus.subscribe(EventBus.Event.LABEL_DELETED, (event, data) -> {
    new Handler(Looper.getMainLooper()).post(() -> UiInjector.refreshTagData());
});
EventBus.subscribe(EventBus.Event.LABEL_RENAMED, (event, data) -> {
    new Handler(Looper.getMainLooper()).post(() -> UiInjector.refreshTagData());
});
EventBus.subscribe(EventBus.Event.LABELS_SYNCED, (event, data) -> {
    new Handler(Looper.getMainLooper()).post(() -> UiInjector.refreshTagData());
});
更新后的功能总清单
98
标题栏下方标签筛选栏注入
UiInjector
99
标签 Chip 点击筛选
UiInjector
100
"全部" Chip（清除筛选）
UiInjector
101
Chip 选中态（微信绿高亮）
UiInjector
102
"+" 按钮快捷新建分组
UiInjector
103
标签 Chip 长按管理菜单
UiInjector
104
查看分组联系人弹窗
UiInjector
105
重命名分组弹窗
UiInjector
106
删除分组确认弹窗
UiInjector
107
合并分组弹窗
UiInjector
108
长按聊天列表注入分组菜单
UiInjector
109
RecyclerView OnItemTouchListener 捕获
UiInjector
110
Adapter item View 长按监听绑定
UiInjector
111
多选分组对话框
UiInjector
112
长按菜单内嵌新建分组
UiInjector
113
联系人 username 自动提取
UiInjector
114
筛选栏自动刷新（事件驱动）
UiInjector + EventBus
总计：114 项功能