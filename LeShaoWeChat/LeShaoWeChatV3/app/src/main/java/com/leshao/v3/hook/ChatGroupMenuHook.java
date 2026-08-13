package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.SubPageActivity;
import com.leshao.v3.hook.model.LabelInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatGroupMenuHook {

    private static final String TAG = "ChatGroupMenuHook";
    private static final int MENU_GROUP_MANAGER = 90201;
    private static final int MENU_QUICK_ADD = 90202;

    public static void hook(ClassLoader cl) {
        log("=== ChatGroupMenuHook.hook() 开始 ===");

        try {
            Class<?> launcherUI = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl);
            log("LauncherUI found: " + launcherUI.getName());

            XposedBridge.hookAllMethods(launcherUI, "onCreateOptionsMenu",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Menu menu = (Menu) param.args[0];
                            if (menu == null) return;
                            menu.add(0, MENU_GROUP_MANAGER, 0, "分组管理");
                            log("onCreateOptionsMenu: 已添加「分组管理」菜单");
                        } catch (Throwable e) {
                            log("onCreateOptionsMenu error: " + e.getMessage());
                        }
                    }
                });
            log("LauncherUI.onCreateOptionsMenu Hook 已安装");

            XposedBridge.hookAllMethods(launcherUI, "onOptionsItemSelected",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            MenuItem item = (MenuItem) param.args[0];
                            if (item.getItemId() != MENU_GROUP_MANAGER) return;

                            Activity act = (Activity) param.thisObject;
                            showGroupMenuDialog(act);
                            param.setResult(true);
                        } catch (Throwable e) {
                            log("onOptionsItemSelected error: " + e.getMessage());
                        }
                    }
                });
            log("LauncherUI.onOptionsItemSelected Hook 已安装");
        } catch (Throwable e) {
            log("LauncherUI Hook 失败: " + e.getMessage());
            tryFallbackHooks(cl);
        }

        log("=== ChatGroupMenuHook.hook() 完成 ===");
    }

    private static void tryFallbackHooks(ClassLoader cl) {
        String[] candidates = {
            "com.tencent.mm.ui.HomeUI",
            "com.tencent.mm.ui.MMLauncherUI",
            "com.tencent.mm.ui.MainUI"
        };
        for (String cn : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(cn, cl);
                XposedBridge.hookAllMethods(cls, "onCreateOptionsMenu",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Menu menu = (Menu) param.args[0];
                                if (menu == null) return;
                                menu.add(0, MENU_GROUP_MANAGER, 0, "分组管理");
                                log("Fallback " + cn + ": 已添加菜单");
                            } catch (Throwable e) {
                                log("fallback error: " + e.getMessage());
                            }
                        }
                    });
                XposedBridge.hookAllMethods(cls, "onOptionsItemSelected",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                MenuItem item = (MenuItem) param.args[0];
                                if (item.getItemId() != MENU_GROUP_MANAGER) return;
                                Activity act = (Activity) param.thisObject;
                                showGroupMenuDialog(act);
                                param.setResult(true);
                            } catch (Throwable e) {
                                log("fallback item error: " + e.getMessage());
                            }
                        }
                    });
                log("Fallback Hook success: " + cn);
                return;
            } catch (Throwable ignored) {}
        }
        log("所有 Fallback Hook 均失败");
    }

    private static void showGroupMenuDialog(Activity act) {
        List<LabelInfo> allLabels = ChatGroupHook.getAllLabels();

        String[] items = allLabels.isEmpty()
            ? new String[]{"创建第一个分组", "进入分组管理"}
            : new String[]{"全部标签 (" + allLabels.size() + ")", "进入分组管理"};

        new AlertDialog.Builder(act)
            .setTitle("分组管理")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0 && allLabels.isEmpty()) {
                        showCreateFirstLabelDialog(act);
                    } else if (which == 0) {
                        showQuickViewLabelsDialog(act, allLabels);
                    } else {
                        try {
                            Intent intent = new Intent(act, SubPageActivity.class);
                            intent.putExtra("title", "聊天分组管理");
                            intent.putExtra("pageId", 14);
                            act.startActivity(intent);
                        } catch (Throwable e) {
                            log("启动SubPageActivity失败: " + e.getMessage());
                            android.widget.Toast.makeText(act, "请从乐少主界面进入", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showCreateFirstLabelDialog(Activity act) {
        final android.widget.EditText input = new android.widget.EditText(act);
        input.setHint("输入分组名称");

        new AlertDialog.Builder(act)
            .setTitle("创建分组")
            .setView(input)
            .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        LabelInfo created = ChatGroupHook.createLabel(name);
                        String msg = created != null ? "已创建: " + name : "创建失败";
                        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show();
                        log("快速创建标签: " + name + " result=" + (created != null));
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showQuickViewLabelsDialog(Activity act, List<LabelInfo> labels) {
        String[] names = new String[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            names[i] = labels.get(i).labelName + " (" + labels.get(i).contacts.size() + "人)";
        }

        new AlertDialog.Builder(act)
            .setTitle("所有分组 (" + labels.size() + ")")
            .setItems(names, null)
            .setPositiveButton("管理分组", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        Intent intent = new Intent(act, SubPageActivity.class);
                        intent.putExtra("title", "聊天分组管理");
                        intent.putExtra("pageId", 14);
                        act.startActivity(intent);
                    } catch (Throwable e) {
                        log("启动SubPageActivity失败: " + e.getMessage());
                    }
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private static void log(String msg) {
        LogWriter.log(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
