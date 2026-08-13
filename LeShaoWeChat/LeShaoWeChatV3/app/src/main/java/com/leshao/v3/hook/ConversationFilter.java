package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;
import android.os.Handler;

public class ConversationFilter {

    private static final String TAG = "ConvFilter";

    private static boolean sFilterActive = false;
    private static String sFilterRule = ""; // "group" / "friend" / "service" / "label:xxx"
    private static Set<String> sAllowedUsernames = Collections.emptySet();
    private static List<Integer> sFilteredPositions = Collections.emptyList();
    private static Map<Integer, Integer> sUnreadByLabel = new HashMap<>();

    private static Object sAdapter;
    private static boolean sHookInstalled = false;
    private static final Handler sUnreadHandler = new Handler(android.os.Looper.getMainLooper());
    private static long sLastBadgeRefresh = 0;

    public static void install(ClassLoader cl, Object adapterInstance) {
        if (sHookInstalled) { LogWriter.log(TAG, "already installed"); return; }
        if (adapterInstance == null) { LogWriter.log(TAG, "adapter null"); return; }

        sAdapter = adapterInstance;
        try {
            Class<?> adapterClass = XposedHelpers.findClass("fh5.w0", cl);

            XposedBridge.hookAllMethods(adapterClass, "getCount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sFilterActive && param.thisObject == sAdapter) {
                            param.setResult(sFilteredPositions.size());
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(adapterClass, "getView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sFilterActive && param.thisObject == sAdapter) {
                            Object arg = param.args[0];
                            int requestedPos = arg instanceof Integer ? (Integer) arg : (int) arg;
                            if (requestedPos >= 0 && requestedPos < sFilteredPositions.size()) {
                                param.args[0] = sFilteredPositions.get(requestedPos);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(adapterClass, "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == sAdapter) {
                        long now = System.currentTimeMillis();
                        if (now - sLastBadgeRefresh > 5000) {
                            sLastBadgeRefresh = now;
                            scanUnreadCounts(); // update counts only, no UI rebuild
                        }
                    }
                }
            });

            sHookInstalled = true;
            LogWriter.log(TAG, "hooks ok");
        } catch (Throwable e) {
            LogWriter.log(TAG, "install fail: " + e.getMessage());
        }
    }

    public static void applyFilter(int labelId, String labelName) {
        LogWriter.log(TAG, "apply " + labelId + " name=" + labelName);
        if (labelId <= 0) { clearFilter(); return; }

        // Determine filter rule based on label name (special virtual labels)
        if ("\u7FA4\u804A".equals(labelName)) {
            sFilterRule = "group";
            buildFilteredByRule();
        } else if (labelName.contains("\u670D\u52A1") || labelName.contains("\u516C\u4F17") || labelName.contains("\u8BA2\u9605")) {
            sFilterRule = "service";
            buildFilteredByRule();
        } else if ("\u597D\u53CB".equals(labelName)) {
            sFilterRule = "friend";
            buildFilteredByRule();
        } else {
            // Regular label: use contacts list
            sFilterRule = "label:" + labelName;
            List<String> contacts = ChatGroupHook.getContactsByLabelId(labelId);
            LogWriter.log(TAG, "contacts " + (contacts != null ? contacts.size() : -1));
            sAllowedUsernames = new HashSet<>(contacts != null ? contacts : Collections.emptyList());
            sFilteredPositions = new ArrayList<>();
            if (sAllowedUsernames.isEmpty()) {
                LogWriter.log(TAG, "empty contacts - showing empty list");
            } else {
                buildFilteredPositions();
            }
        }

        sFilterActive = true;
        scanUnreadCounts();
        notifyAdapterChanged();
        LogWriter.log(TAG, "ON rule=" + sFilterRule + " filtered=" + sFilteredPositions.size());
    }

    public static void clearFilter() {
        if (!sFilterActive) return;
        sFilterActive = false;
        sFilterRule = "";
        sAllowedUsernames = Collections.emptySet();
        sFilteredPositions = Collections.emptyList();
        scanUnreadCounts();
        notifyAdapterChanged();
        LogWriter.log(TAG, "OFF");
    }

    public static int getUnreadForLabel(int labelId) {
        Integer c = sUnreadByLabel.get(labelId);
        return c != null ? c : 0;
    }

    static boolean scanUnreadCounts() {
        try {
            if (sAdapter == null) return false;
            Object q = XposedHelpers.getObjectField(sAdapter, "q");
            if (q == null) return false;
            ArrayList<?> dataList = (ArrayList<?>) XposedHelpers.getObjectField(q, "d");
            if (dataList == null) return false;

            int groupUnread = 0, friendUnread = 0, serviceUnread = 0;
            int totalScanned = 0, totalWithUnread = 0;
            for (int i = 0; i < dataList.size(); i++) {
                Object x = dataList.get(i);
                if (x == null) continue;
                totalScanned++;
                int unread = 0;
                try { unread = XposedHelpers.getIntField(x, "field_unReadCount"); } catch (Throwable e1) {
                    try { Object k = XposedHelpers.getObjectField(x, "d"); if (k != null) unread = XposedHelpers.getIntField(k, "field_unReadCount"); } catch (Throwable ignored) {}
                }
                boolean muted = false;
                try { muted = XposedHelpers.getBooleanField(x, "field_isMuted"); } catch (Throwable e1) {
                    try { int s = XposedHelpers.getIntField(x, "field_status"); muted = (s & 1) != 0; } catch (Throwable e2) {
                        try { Object muteObj = XposedHelpers.getObjectField(x, "field_mute"); if (muteObj != null) muted = muteObj instanceof Boolean ? (Boolean) muteObj : (int) muteObj > 0; } catch (Throwable ignored) {}
                    }
                }
                if (muted) continue;
                Object k4 = XposedHelpers.getObjectField(x, "d");
                if (k4 == null) continue;
                String username = (String) XposedHelpers.callMethod(k4, "i1");
                if (username == null) continue;
                if (unread > 0) totalWithUnread++;

                if (username.endsWith("@chatroom") && !username.endsWith("@im.chatroom")) {
                    groupUnread = unread > 0 ? groupUnread + 1 : groupUnread;
                } else if (username.startsWith("gh_") || username.contains("officialaccounts")
                        || username.equals("weixin")) {
                    serviceUnread = unread > 0 ? serviceUnread + 1 : serviceUnread;
                } else if (!username.equals("filehelper") && !username.startsWith("service_")) {
                    friendUnread = unread > 0 ? friendUnread + 1 : friendUnread;
                }
            }
            int oldGroup = sUnreadByLabel.containsKey(10000) ? sUnreadByLabel.get(10000) : -1;
            int oldFriend = sUnreadByLabel.containsKey(10001) ? sUnreadByLabel.get(10001) : -1;
            int oldService = sUnreadByLabel.containsKey(10002) ? sUnreadByLabel.get(10002) : -1;
            sUnreadByLabel.put(10000, groupUnread);
            sUnreadByLabel.put(10001, friendUnread);
            sUnreadByLabel.put(10002, serviceUnread);
            boolean changed = (groupUnread != oldGroup || friendUnread != oldFriend || serviceUnread != oldService);
            LogWriter.log(TAG, "unread scan: " + totalScanned + " items, " + totalWithUnread
                + " unread, G=" + groupUnread + " F=" + friendUnread + " S=" + serviceUnread);
            return changed;
        } catch (Throwable e) {
            LogWriter.log(TAG, "scanUnread: " + e.getMessage());
            return false;
        }
    }

    private static void buildFilteredByRule() {
        List<Integer> positions = new ArrayList<>();
        try {
            Object q = XposedHelpers.getObjectField(sAdapter, "q");
            if (q == null) { LogWriter.log(TAG, "q null"); return; }

            ArrayList<?> dataList = (ArrayList<?>) XposedHelpers.getObjectField(q, "d");
            if (dataList == null) { LogWriter.log(TAG, "q.d null"); return; }

            LogWriter.log(TAG, "scan " + dataList.size() + " items by rule " + sFilterRule);
            int dumpCount = 0;
            for (int i = 0; i < dataList.size(); i++) {
                Object x = dataList.get(i);
                if (x == null) continue;
                Object k4 = XposedHelpers.getObjectField(x, "d");
                if (k4 == null) continue;
                String username = (String) XposedHelpers.callMethod(k4, "i1");
                if (username == null) continue;
                boolean match;
                if ("group".equals(sFilterRule)) {
                    match = username.endsWith("@chatroom") && !username.endsWith("@im.chatroom");
                } else if ("service".equals(sFilterRule)) {
                    match = username.startsWith("gh_")
                         || username.contains("officialaccounts")
                         || username.equals("weixin");
                } else if ("friend".equals(sFilterRule)) {
                    match = !username.endsWith("@chatroom")
                         && !username.startsWith("gh_")
                         && !username.contains("officialaccounts")
                         && !username.startsWith("service_")
                         && !username.equals("filehelper")
                         && !username.equals("weixin");
                } else {
                    match = false;
                }
                if (match) {
                    positions.add(i);
                    if (dumpCount < 5) {
                        LogWriter.log(TAG, " match[" + dumpCount + "]=" + username);
                        dumpCount++;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "build err: " + e.getMessage());
        }
        sFilteredPositions = positions;
    }

    private static void buildFilteredPositions() {
        List<Integer> positions = new ArrayList<>();
        try {
            Object q = XposedHelpers.getObjectField(sAdapter, "q");
            if (q == null) { LogWriter.log(TAG, "q null"); return; }

            ArrayList<?> dataList = (ArrayList<?>) XposedHelpers.getObjectField(q, "d");
            if (dataList == null) { LogWriter.log(TAG, "q.d null"); return; }

            LogWriter.log(TAG, "scan " + dataList.size() + " items by set");
            for (int i = 0; i < dataList.size(); i++) {
                Object x = dataList.get(i);
                if (x == null) continue;
                Object k4 = XposedHelpers.getObjectField(x, "d");
                if (k4 == null) continue;
                String username = (String) XposedHelpers.callMethod(k4, "i1");
                if (username != null && sAllowedUsernames.contains(username)) {
                    positions.add(i);
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "build err: " + e.getMessage());
        }
        sFilteredPositions = positions;
    }

    private static void notifyAdapterChanged() {
        try {
            XposedHelpers.callMethod(sAdapter, "notifyDataSetChanged");
        } catch (Throwable ignored) {}
    }

    /* Returns all usernames matching a built-in label rule, or null if not built-in */
    static List<String> getUsernamesForBuiltInLabel(int labelId) {
        String rule;
        if (labelId == 10000) rule = "group";
        else if (labelId == 10001) rule = "friend";
        else if (labelId == 10002) rule = "service";
        else return null;
        List<String> r = new ArrayList<>();
        try {
            if (sAdapter == null) return r;
            Object q = XposedHelpers.getObjectField(sAdapter, "q");
            if (q == null) return r;
            ArrayList<?> dataList = (ArrayList<?>) XposedHelpers.getObjectField(q, "d");
            if (dataList == null) return r;
            for (Object x : dataList) {
                if (x == null) continue;
                Object k4 = XposedHelpers.getObjectField(x, "d");
                if (k4 == null) continue;
                String username = (String) XposedHelpers.callMethod(k4, "i1");
                if (username == null) continue;
                boolean match;
                if ("group".equals(rule)) {
                    match = username.endsWith("@chatroom") && !username.endsWith("@im.chatroom");
                } else if ("service".equals(rule)) {
                    match = username.startsWith("gh_") || username.contains("officialaccounts")
                          || username.equals("weixin");
                } else {
                    match = !username.endsWith("@chatroom") && !username.startsWith("gh_")
                          && !username.contains("officialaccounts") && !username.startsWith("service_")
                          && !username.equals("filehelper")
                          && !username.equals("weixin");
                }
                if (match) r.add(username);
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getUsernamesBuiltIn: " + e.getMessage()); }
        return r;
    }

    /* Scan adapter data for contacts assigned to shadow labels, populate sShadowContactMap */
    static void scanContactsForShadowLabels(Set<Integer> shadowIds, Map<Integer, Set<String>> shadowMap) {
        try {
            if (sAdapter == null || shadowIds.isEmpty()) return;
            Object q = XposedHelpers.getObjectField(sAdapter, "q");
            if (q == null) return;
            ArrayList<?> dataList = (ArrayList<?>) XposedHelpers.getObjectField(q, "d");
            if (dataList == null) return;
            for (Object x : dataList) {
                if (x == null) continue;
                Object k4 = XposedHelpers.getObjectField(x, "d");
                if (k4 == null) continue;
                String username = (String) XposedHelpers.callMethod(k4, "i1");
                if (username == null) continue;
                int[] labelIds = ChatGroupHook.getContactLabelIds(username);
                for (int lid : labelIds) {
                    if (shadowIds.contains(lid)) {
                        shadowMap.computeIfAbsent(lid, k -> new HashSet<>()).add(username);
                    }
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "scanShadowContacts: " + e.getMessage()); }
    }
}