package com.leshao.wechat;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import java.util.*;

public class ContactSelector {
    private static Set<String> selected = new HashSet<>();
    private static boolean isGroupTab = true;
    private static String scopeTitle = "范围";
    private static TextView tvCount;

    public interface OnScopeSaved { void onSaved(Set<String> selectedIds); }
    private static OnScopeSaved callback;

    public static void show(Context ctx, String title, Set<String> preSelected, OnScopeSaved cb) {
        scopeTitle = title;
        selected.clear();
        if (preSelected != null) selected.addAll(preSelected);
        callback = cb;
        isGroupTab = true;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 14), Utils.dp(ctx, 10), Utils.dp(ctx, 14), Utils.dp(ctx, 10));

        // 标题行: 范围:{title}  已选 X 个
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("范围：" + scopeTitle);
        tvTitle.setTextSize(15); tvTitle.setTextColor(ThemeEngine.thAccent());
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(tvTitle);

        tvCount = new TextView(ctx);
        tvCount.setText("已选 " + selected.size() + " 个");
        tvCount.setTextSize(12); tvCount.setTextColor(ThemeEngine.thText2());
        titleRow.addView(tvCount);
        root.addView(titleRow);
        root.addView(spacer(ctx, 6));

        // 标签切换栏: 群聊 / 好友
        final LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        final GradientDrawable tabActive = new GradientDrawable();
        tabActive.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        tabActive.setColors(new int[]{ThemeEngine.thAccent2(), ThemeEngine.thAccent()});
        tabActive.setCornerRadius(Utils.dp(ctx, 8));
        final GradientDrawable tabNormal = new GradientDrawable();
        tabNormal.setCornerRadius(Utils.dp(ctx, 8));
        tabNormal.setColor(ThemeEngine.thCard());

        final TextView tabGroup = new TextView(ctx);
        tabGroup.setText("群聊"); tabGroup.setGravity(Gravity.CENTER);
        ThemeEngine.styleClickableText(tabGroup);
        tabGroup.setTextColor(ThemeEngine.thWhite());
        tabGroup.setBackground(tabActive);
        tabGroup.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        final TextView tabFriend = new TextView(ctx);
        tabFriend.setText("好友"); tabFriend.setGravity(Gravity.CENTER);
        ThemeEngine.styleClickableText(tabFriend);
        tabFriend.setTextColor(ThemeEngine.thText2());
        tabFriend.setBackground(tabNormal);
        tabFriend.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        final LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        tabGroup.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            isGroupTab = true; tabGroup.setBackground(tabActive); tabGroup.setTextColor(ThemeEngine.thWhite());
            tabFriend.setBackground(tabNormal); tabFriend.setTextColor(ThemeEngine.thText2());
            refreshList(listContainer, ctx);
        }});
        tabFriend.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            isGroupTab = false; tabFriend.setBackground(tabActive); tabFriend.setTextColor(ThemeEngine.thWhite());
            tabGroup.setBackground(tabNormal); tabGroup.setTextColor(ThemeEngine.thText2());
            refreshList(listContainer, ctx);
        }});

        tabBar.addView(tabGroup);
        View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 8), -1)); tabBar.addView(sp);
        tabBar.addView(tabFriend);
        root.addView(tabBar);
        root.addView(spacer(ctx, 8));

        // 搜索输入框
        final EditText etSearch = new EditText(ctx);
        ThemeEngine.styleInput(etSearch);
        etSearch.setHint("搜索…");
        etSearch.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { refreshList(listContainer, ctx); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        root.addView(etSearch);
        root.addView(spacer(ctx, 8));

        // 列表
        final ScrollView sv = new ScrollView(ctx);
        sv.addView(listContainer);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(sv);
        root.addView(spacer(ctx, 8));

        // 底部按钮：全选 / 保存 / 关闭
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAll = makeSmallBtn(ctx, "全选");
        btnAll.setBackground(createGradientBg(ctx, ThemeEngine.thAccent2(), ThemeEngine.thAccent()));
        btnAll.setTextColor(ThemeEngine.thWhite());
        btnAll.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            selectAll(ctx); refreshList(listContainer, ctx);
        }});
        btnRow.addView(btnAll);
        addBtnSpacer(ctx, btnRow);

        Button btnInvert = makeSmallBtn(ctx, "反选");
        btnInvert.setBackground(createGradientBg(ctx, ThemeEngine.thRed(), Utils.pc("CC"+ThemeEngine.hexRed())));
        btnInvert.setTextColor(ThemeEngine.thWhite());
        btnInvert.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            invertSelection(ctx); refreshList(listContainer, ctx);
        }});
        btnRow.addView(btnInvert);
        addBtnSpacer(ctx, btnRow);

        Button btnSave = makeSmallBtn(ctx, "保存");
        btnSave.setBackground(ThemeEngine.createPrimaryBtnBg(ctx, 6));
        btnSave.setTextColor(ThemeEngine.thWhite());
        btnSave.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (callback != null) callback.onSaved(new HashSet<>(selected));
            safeDismiss(dlg);
        }});
        btnRow.addView(btnSave);
        addBtnSpacer(ctx, btnRow);

        Button btnClose = makeSmallBtn(ctx, "关闭");
        btnClose.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 6));
        btnClose.setTextColor(ThemeEngine.thText());
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });
        btnRow.addView(btnClose);
        root.addView(btnRow);

        refreshList(listContainer, ctx);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx); b.setView(root); b.setCancelable(true);
        final AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.92),
                        (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.78));
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    private static void refreshList(LinearLayout container, Context ctx) {
        container.removeAllViews();
        List<String[]> contacts = loadContacts(ctx);
        if (contacts.isEmpty()) {
            TextView empty = new TextView(ctx); empty.setText("(暂无数据)"); empty.setTextSize(12);
            empty.setTextColor(ThemeEngine.thText2()); empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Utils.dp(ctx, 20), 0, Utils.dp(ctx, 20));
            container.addView(empty);
            return;
        }
        for (final String[] c : contacts) {
            final String wxid = c[0]; final String name = c[1];
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Utils.dp(ctx, 4), 0, Utils.dp(ctx, 4));

            CheckBox cb = new CheckBox(ctx);
            cb.setChecked(selected.contains(wxid));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton btn, boolean checked) {
                    if (checked) selected.add(wxid); else selected.remove(wxid);
                    tvCount.setText("已选 " + selected.size() + " 个");
                }
            });
            row.addView(cb);

            // 头像占位
            View avatar = new View(ctx);
            avatar.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 28), Utils.dp(ctx, 28)));
            GradientDrawable avBg = new GradientDrawable();
            avBg.setCornerRadius(Utils.dp(ctx, 14)); avBg.setColor(ThemeEngine.thAccent2());
            avatar.setBackground(avBg);
            LinearLayout.LayoutParams avLp = (LinearLayout.LayoutParams) avatar.getLayoutParams();
            avLp.setMargins(0, 0, Utils.dp(ctx, 8), 0);
            row.addView(avatar);

            TextView tv = new TextView(ctx);
            tv.setText(name); tv.setTextSize(12); tv.setTextColor(ThemeEngine.thText());
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(tv);

            // 分隔线
            container.addView(row);
            View div = new View(ctx);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            div.setBackgroundColor(ThemeEngine.thBorder());
            container.addView(div);
        }
        tvCount.setText("已选 " + selected.size() + " 个");
    }

    private static List<String[]> loadContacts(Context ctx) {
        List<String[]> list = new ArrayList<>();
        ClassLoader cl = WeChatHooks.getCL();
        if (cl == null) {
            Utils.xlog("ContactSelector: ClassLoader is null! Falling back to nameCache");
            fillFromNameCache(list);
            return list;
        }

        // Strategy 0: WeKit方案 - SQL直读rcontact表
        try {
            List<String[]> dbContacts = WeChatHooks.getContactsFromDB();
            if (dbContacts != null && !dbContacts.isEmpty()) {
                Utils.xlog("ContactSelector: Strategy0 (SQL) OK, count=" + dbContacts.size());
                for (String[] entry : dbContacts) {
                    String wxid = entry[0];
                    if (wxid == null || wxid.isEmpty() || "filehelper".equals(wxid) || "weixin".equals(wxid)
                        || wxid.startsWith("gh_") || wxid.contains("@lbsroom") || wxid.contains("@openim"))
                        continue;
                    boolean isGrp = wxid.endsWith("@chatroom");
                    if (isGroupTab && !isGrp) continue;
                    if (!isGroupTab && isGrp) continue;
                    list.add(entry);
                }
                if (!list.isEmpty()) return list;
            }
        } catch (Throwable e) {
            Utils.xlog("ContactSelector: Strategy0 (SQL) failed: " + e.getMessage());
        }

        // Strategy 1: Contact plugin service → getContactList or getAllContacts
        try {
            Object cs = getContactService(cl);
            if (cs != null) {
                java.util.List<?> contacts = null;
                String methodUsed = null;
                for (String mn : new String[]{"getContactList", "getAllContacts", "getFriendList", "bKq"}) {
                    try {
                        contacts = (java.util.List<?>) de.robv.android.xposed.XposedHelpers.callMethod(cs, mn);
                        methodUsed = mn;
                        break;
                    } catch (Throwable e) {}
                }
                if (contacts != null && !contacts.isEmpty()) {
                    Utils.xlog("ContactSelector: Strategy1 OK, method=" + methodUsed + " count=" + contacts.size());
                    for (Object c : contacts) {
                        String[] entry = extractContactEntry(cl, c);
                        if (entry != null) list.add(entry);
                    }
                    if (!list.isEmpty()) return list;
                }
            }
        } catch (Throwable e) {
            Utils.xlog("ContactSelector: Strategy1 failed: " + e.getClass().getName() + ": " + e.getMessage());
        }

        // Strategy 2: com.tencent.mm.model.aj conversation storage → getAll()
        try {
            String[] ajMethods = {"getAs", "bJt", "aOJ", "aOM", "getResponse"};
            Object convStg = null;
            String ajMethodUsed = null;
            for (String mn : ajMethods) {
                try {
                    Class<?> ajCls = cl.loadClass("com.tencent.mm.model.aj");
                    convStg = de.robv.android.xposed.XposedHelpers.callStaticMethod(ajCls, mn);
                    ajMethodUsed = mn;
                    break;
                } catch (Throwable e) {}
            }
            if (convStg != null) {
                String[] getAllMethods = {"getAll", "bLw", "aOB", "values", "getMap"};
                Object allConvs = null;
                String getAllUsed = null;
                for (String mn : getAllMethods) {
                    try {
                        allConvs = de.robv.android.xposed.XposedHelpers.callMethod(convStg, mn);
                        getAllUsed = mn;
                        break;
                    } catch (Throwable e) {}
                }
                if (allConvs instanceof Map) {
                    Map<?, ?> convMap = (Map<?, ?>) allConvs;
                    Utils.xlog("ContactSelector: Strategy2 OK, aj." + ajMethodUsed + "()." + getAllUsed + "() count=" + convMap.size());
                    for (Object conv : convMap.values()) {
                        String[] entry = extractConvEntry(conv);
                        if (entry != null) list.add(entry);
                    }
                    if (!list.isEmpty()) return list;
                } else if (allConvs instanceof java.util.List) {
                    java.util.List<?> convList = (java.util.List<?>) allConvs;
                    Utils.xlog("ContactSelector: Strategy2 OK (List), count=" + convList.size());
                    for (Object conv : convList) {
                        String[] entry = extractConvEntry(conv);
                        if (entry != null) list.add(entry);
                    }
                    if (!list.isEmpty()) return list;
                } else if (allConvs instanceof Iterable) {
                    int cnt = 0;
                    for (Object conv : (Iterable<?>) allConvs) {
                        String[] entry = extractConvEntry(conv);
                        if (entry != null) list.add(entry);
                        cnt++;
                    }
                    Utils.xlog("ContactSelector: Strategy2 OK (Iterable), count=" + cnt);
                    if (!list.isEmpty()) return list;
                }
            }
        } catch (Throwable e) {
            Utils.xlog("ContactSelector: Strategy2 failed: " + e.getClass().getName() + ": " + e.getMessage());
        }

        // Strategy 3: Messaging plugin service → conversation storage → getAll()
        try {
            String[] msgPluginClasses = {
                "com.tencent.mm.plugin.messenger.foundation.a.n",
                "com.tencent.mm.plugin.messenger.foundation.a.m",
                "com.tencent.mm.plugin.messenger.foundation.a$n",
                "com.tencent.mm.plugin.messenger.foundation.a$m",
            };
            Class<?> kernelCls = WeChatHooks.findKernelClass();
            if (kernelCls == null) {
                Utils.xlog("ContactSelector: Strategy3 kernel class not found");
                throw new RuntimeException("kernel class not found");
            }
            Object msgSvc = null;
            for (String pcn : msgPluginClasses) {
                try {
                    Class<?> pcls = cl.loadClass(pcn);
                    msgSvc = de.robv.android.xposed.XposedHelpers.callStaticMethod(kernelCls, "ax", pcls);
                    break;
                } catch (Throwable e) {}
            }
            if (msgSvc != null) {
                Object convStg = null;
                for (String mn : new String[]{"getConversationStg", "bLx", "bLy", "aOC", "getConvStorage"}) {
                    try { convStg = de.robv.android.xposed.XposedHelpers.callMethod(msgSvc, mn); break; }
                    catch (Throwable e) {}
                }
                if (convStg != null) {
                    Object allConvs = null;
                    for (String mn : new String[]{"getAll", "bLw", "aOB", "values", "getMap"}) {
                        try { allConvs = de.robv.android.xposed.XposedHelpers.callMethod(convStg, mn); break; }
                        catch (Throwable e) {}
                    }
                    if (allConvs instanceof Map) {
                        Map<?, ?> convMap = (Map<?, ?>) allConvs;
                        Utils.xlog("ContactSelector: Strategy3 OK (Map), count=" + convMap.size());
                        for (Object conv : convMap.values()) {
                            String[] entry = extractConvEntry(conv);
                            if (entry != null) list.add(entry);
                        }
                        if (!list.isEmpty()) return list;
                    }
                }
            }
        } catch (Throwable e) {
            Utils.xlog("ContactSelector: Strategy3 failed: " + e.getClass().getName() + ": " + e.getMessage());
        }

        // Fallback: WeChatHooks nameCache
        if (list.isEmpty()) {
            Utils.xlog("ContactSelector: All strategies failed, falling back to nameCache");
            fillFromNameCache(list);
        }
        return list;
    }

    private static String[] extractContactEntry(ClassLoader cl, Object c) {
        try {
            String wxid = WeChatHooks.resolveObjWxid(c);
            if (wxid == null) {
                for (String mn : new String[]{"getUsername", "getWxid"}) {
                    try { wxid = (String) de.robv.android.xposed.XposedHelpers.callMethod(c, mn); if (wxid != null && !wxid.isEmpty()) break; }
                    catch (Throwable e) {}
                }
            }
            if (wxid == null || wxid.isEmpty() || "filehelper".equals(wxid) || "weixin".equals(wxid)
                || wxid.startsWith("gh_") || wxid.contains("@lbsroom") || wxid.contains("@openim"))
                return null;

            boolean isGrp = wxid.endsWith("@chatroom");
            if (isGroupTab && !isGrp) return null;
            if (!isGroupTab && isGrp) return null;

            String name = WeChatHooks.resolveObjName(c);
            if (name == null || name.isEmpty()) {
                for (String mn : new String[]{"getRemarkName", "getNickname", "getDisplayName", "getConRemark"}) {
                    try { name = (String) de.robv.android.xposed.XposedHelpers.callMethod(c, mn); if (name != null && !name.isEmpty()) break; }
                    catch (Throwable e) {}
                }
            }
            if (name == null || name.isEmpty()) name = wxid;
            return new String[]{wxid, name};
        } catch (Throwable e) { return null; }
    }

    private static String[] extractConvEntry(Object conv) {
        try {
            String wxid = WeChatHooks.resolveObjWxid(conv);
            if (wxid == null) {
                for (String mn : new String[]{"getUsername", "field_username", "bLp", "getTalker"}) {
                    try {
                        Object val = de.robv.android.xposed.XposedHelpers.callMethod(conv, mn);
                        if (val instanceof String) { wxid = (String) val; if (wxid != null && !wxid.isEmpty()) break; }
                    } catch (Throwable e) {}
                }
            }
            if (wxid == null || wxid.isEmpty() || "filehelper".equals(wxid) || "weixin".equals(wxid)
                || wxid.startsWith("gh_") || wxid.contains("@lbsroom") || wxid.contains("@openim"))
                return null;

            boolean isGrp = wxid.endsWith("@chatroom");
            if (isGroupTab && !isGrp) return null;
            if (!isGroupTab && isGrp) return null;

            String name = WeChatHooks.resolveObjName(conv);
            if (name == null || name.isEmpty()) {
                name = WeChatHooks.resolveSenderName(wxid, null);
            }
            if (wxid.equals(name) && isGrp) name = WeChatHooks.resolveGroupName(wxid);
            if (name == null || name.isEmpty()) name = wxid;
            return new String[]{wxid, name};
        } catch (Throwable e) { return null; }
    }

    private static void fillFromNameCache(List<String[]> list) {
        for (Map.Entry<String, String> e : WeChatHooks.getNameCache().entrySet()) {
            String wxid = e.getKey();
            if (wxid == null || wxid.contains("@@")) continue;
            boolean isGrp = wxid.endsWith("@chatroom");
            if (isGroupTab && !isGrp) continue;
            if (!isGroupTab && isGrp) continue;
            list.add(new String[]{wxid, e.getValue()});
        }
    }

    private static Object getContactService(ClassLoader cl) {
        try {
            Class<?> k = WeChatHooks.findKernelClass();
            if (k == null) { Utils.xlog("ContactSelector: kernel class not found!"); return null; }
            String[] contactClassNames = {
                "com.tencent.mm.plugin.contact.a$b",
                "com.tencent.mm.plugin.contact.a.b",
                "com.tencent.mm.plugin.contact.a$c",
                "com.tencent.mm.plugin.contact.a.c",
                "com.tencent.mm.plugin.contact.a",
            };
            for (String cn : contactClassNames) {
                try { return de.robv.android.xposed.XposedHelpers.callStaticMethod(k, "ax", cl.loadClass(cn)); }
                catch (Throwable e) {}
            }
        } catch (Throwable e) {
            Utils.xlog("ContactSelector: getContactService class lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static void selectAll(Context ctx) {
        List<String[]> contacts = loadContacts(ctx);
        for (String[] c : contacts) selected.add(c[0]);
    }

    private static void invertSelection(Context ctx) {
        List<String[]> contacts = loadContacts(ctx);
        Set<String> newSel = new HashSet<>();
        for (String[] c : contacts) if (!selected.contains(c[0])) newSel.add(c[0]);
        selected = newSel;
    }

    private static GradientDrawable createGradientBg(Context ctx, int c1, int c2) {
        GradientDrawable gd = new GradientDrawable();
        gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        gd.setColors(new int[]{c1, c2});
        gd.setCornerRadius(Utils.dp(ctx, 8));
        return gd;
    }

    private static Button makeSmallBtn(Context ctx, String text) {
        Button b = ThemeEngine.createBtn(ctx, text);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = Utils.dp(ctx, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private static void addBtnSpacer(Context ctx, LinearLayout row) {
        View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 4), -1)); row.addView(sp);
    }

    private static View spacer(Context ctx, int h) {
        View v = new View(ctx); v.setLayoutParams(new LinearLayout.LayoutParams(-1, Utils.dp(ctx, h))); return v;
    }

    private static AlertDialog dlg;
    private static void safeDismiss(AlertDialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception e) {}
    }
}
