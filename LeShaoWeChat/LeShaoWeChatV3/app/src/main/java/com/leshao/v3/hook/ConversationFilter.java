package com.leshao.v3.hook;

import android.view.View;
import android.widget.AbsListView;
import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;
import java.lang.reflect.Field;
import android.os.Handler;

public class ConversationFilter {

    private static final String TAG = "ConvFilter";

    private static boolean sFilterActive = false;
    private static String sFilterRule = ""; // "group" / "friend" / "service" / "label:xxx"
    private static Set<String> sAllowedUsernames = Collections.emptySet();
    private static List<Integer> sFilteredPositions = Collections.emptyList();
    private static Map<Integer, Integer> sUnreadByLabel = new HashMap<>();

    private static Object sAdapter;
    private static Object sHeaderAdapter;
    private static View sConvList;
    private static boolean sHookInstalled = false;
    private static boolean sIsRecyclerView = false;
    // 数据层（按 聊天分组_新.md）：po5.u.q(zs3.s1) / jo5.f.a(List) 双路径
    private static Object sRecyclerAdapter;
    private static List<Object> sFullCache = Collections.emptyList();
    private static volatile boolean sDataLayerReady = false;
    private static volatile boolean sUseDataLayer = false;
    private static volatile boolean sDataPathHooksInstalled = false;
    private static final Handler sUnreadHandler = new Handler(android.os.Looper.getMainLooper());
    private static long sLastBadgeRefresh = 0;
    private static long sLastUnreadScan = 0;
    private static final long UNREAD_SCAN_INTERVAL = 2000;
    private static int sGetCountCall = 0;
    private static int sGetViewCall = 0;
    private static volatile boolean sJustAppliedFilter = false;
    // 切换分类标签时直接回顶，避免用旧锚点 setSelectionFromTop 造成可见跳动
    private static volatile boolean sResetToTop = false;
    // 锚定：首个可见会话的 username（跨过滤切换时 position 会错位，username 不会）+ 该行 top
    private static String sAnchorUsername;
    private static int sAnchorRawPos = -1;
    private static int sAnchorChildTop = 0;

    public static void install(ClassLoader cl, Object adapterInstance, View convList) {
        if (adapterInstance == null) { LogWriter.log(TAG, "adapter null"); return; }

        // 8.0.78+ 会话列表为 RecyclerView，需要走 RecyclerView 适配路径
        if (convList != null && convList.getClass().getName().contains("RecyclerView")) {
            LogWriter.log(TAG, "convList is RecyclerView (" + convList.getClass().getSimpleName() + "), using RecyclerView filter path");
            sIsRecyclerView = true;
        }

        // 允许在已安装后用真正的 convList 更新 sConvList（fallback 可能先以 null 安装）
        if (convList != null) sConvList = convList;
        if (sHookInstalled) { LogWriter.log(TAG, "already installed"); return; }

        if (sConvList == null) { LogWriter.log(TAG, "convList null at install, layoutChildren hook deferred"); }

        // 数据层双路径 Hook（按 聊天分组_新.md：po5.u.q(zs3.s1) 新路径 / jo5.f.a(List) 旧路径）
        try {
            installDataPathHooks(cl);
        } catch (Throwable e) {
            LogWriter.log(TAG, "installDataPathHooks err: " + e.getMessage());
        }

        // 解包 HeaderViewListAdapter，获取真实 adapter
        Object realAdapter = adapterInstance;
        try {
            if (adapterInstance.getClass().getName().equals("android.widget.HeaderViewListAdapter")) {
                sHeaderAdapter = adapterInstance;
                realAdapter = XposedHelpers.callMethod(adapterInstance, "getWrappedAdapter");
                LogWriter.log(TAG, "unwrapped HeaderViewListAdapter -> " + (realAdapter != null ? realAdapter.getClass().getName() : "null"));
            }
        } catch (Throwable ignored) {}
        if (realAdapter == null) { LogWriter.log(TAG, "realAdapter null after unwrap"); return; }

        // 防御: realAdapter 不是 Adapter 实例时跳过
        if (!(realAdapter instanceof android.widget.Adapter)) {
            LogWriter.log(TAG, "realAdapter is not an Adapter: " + realAdapter.getClass().getName());
            return;
        }

        sAdapter = realAdapter;

        if (sIsRecyclerView) {
            installRecyclerViewHooks(cl);
        } else {
            installListViewHooks();
        }
    }

    private static void installListViewHooks() {
        try {
            // 直接钩 HeaderViewListAdapter（Android 框架类，ListView 直接调用它的 getCount/getView）
            // fh5.w0 的 hookAllMethods 在 LSPosed 下无法钩到继承/重写的方法
            XposedBridge.hookAllMethods(android.widget.HeaderViewListAdapter.class, "getCount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sFilterActive && !sUseDataLayer && param.thisObject == sHeaderAdapter) {
                            java.util.ArrayList<?> headers = (java.util.ArrayList<?>) XposedHelpers.getObjectField(param.thisObject, "mHeaderViewInfos");
                            java.util.ArrayList<?> footers = (java.util.ArrayList<?>) XposedHelpers.getObjectField(param.thisObject, "mFooterViewInfos");
                            int hfCount = (headers != null ? headers.size() : 0) + (footers != null ? footers.size() : 0);
                            param.setResult(hfCount + sFilteredPositions.size());
                            if (++sGetCountCall % 20 == 1) {
                                LogWriter.log(TAG, "getCount hooked: " + (hfCount + sFilteredPositions.size()));
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(android.widget.HeaderViewListAdapter.class, "getView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sFilterActive && !sUseDataLayer && param.thisObject == sHeaderAdapter) {
                            int position = (int) param.args[0];
                            java.util.ArrayList<?> headers = (java.util.ArrayList<?>) XposedHelpers.getObjectField(param.thisObject, "mHeaderViewInfos");
                            int numHeaders = headers != null ? headers.size() : 0;
                            if (position >= numHeaders) {
                                int dataPos = position - numHeaders;
                                if (dataPos >= 0 && dataPos < sFilteredPositions.size()) {
                                    param.args[0] = numHeaders + sFilteredPositions.get(dataPos);
                                    if (++sGetViewCall % 20 == 1) {
                                        LogWriter.log(TAG, "getView hooked: " + dataPos + "->" + sFilteredPositions.get(dataPos));
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // notifyDataSetChanged: fh5.w0 没重写，钩 BaseAdapter
            XposedBridge.hookAllMethods(android.widget.BaseAdapter.class, "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject == sAdapter) {
                            long now = System.currentTimeMillis();
                            if (now - sLastBadgeRefresh > 5000) {
                                sLastBadgeRefresh = now;
                                scanUnreadCounts();
                            }
                        }
                    } catch (Throwable e) {
                        LogWriter.log("ConvFilter", "cb err: " + e);
                    }
                }
            });

            // layoutChildren 内部会调用 handleDataChanged -> lookForSelectablePosition(0,true)
            // 在 Android 14 上 lookForSelectablePosition 已移除，需要直接拦截 layoutChildren
            // 过滤刚应用时按锚定恢复滚动位置（用户期望：切标签后保持原滚动位置，不跳回顶部）
            try {
                java.lang.reflect.Method lc = android.widget.AbsListView.class.getDeclaredMethod("layoutChildren");
                XposedBridge.hookMethod(lc, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sJustAppliedFilter && param.thisObject == sConvList) {
                            try {
                                restoreAnchorPosition((AbsListView) param.thisObject);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
                LogWriter.log(TAG, "layoutChildren hook installed");
            } catch (Throwable e) {
                LogWriter.log(TAG, "layoutChildren hook failed: " + e.getMessage());
            }

            sHookInstalled = true;
            LogWriter.log(TAG, "ListView hooks ok: HeaderViewListAdapter");
        } catch (Throwable e) {
            LogWriter.log(TAG, "install fail: " + e.getMessage());
        }
    }

    private static void installRecyclerViewHooks(ClassLoader cl) {
        try {
            // Hook RecyclerView.Adapter.getItemCount for our adapter instances
            XposedBridge.hookAllMethods(androidx.recyclerview.widget.RecyclerView.Adapter.class, "getItemCount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sFilterActive && !sUseDataLayer && param.thisObject == sAdapter) {
                            param.setResult(sFilteredPositions.size());
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Hook onBindViewHolder to map filtered position to real position
            XposedBridge.hookAllMethods(androidx.recyclerview.widget.RecyclerView.Adapter.class, "onBindViewHolder", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sFilterActive && !sUseDataLayer && param.thisObject == sAdapter) {
                            int position = (int) param.args[1];
                            if (position >= 0 && position < sFilteredPositions.size()) {
                                param.args[1] = sFilteredPositions.get(position);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Hook onViewRecycled for unread count scanning trigger
            XposedBridge.hookAllMethods(androidx.recyclerview.widget.RecyclerView.Adapter.class, "onViewRecycled", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject == sAdapter) {
                            long now = System.currentTimeMillis();
                            if (now - sLastBadgeRefresh > 5000) {
                                sLastBadgeRefresh = now;
                                scanUnreadCounts();
                            }
                        }
                    } catch (Throwable e) {
                        LogWriter.log("ConvFilter", "onViewRecycled cb err: " + e);
                    }
                }
            });

            // Hook RecyclerView.scrollToPosition for anchor restore
            XposedBridge.hookAllMethods(androidx.recyclerview.widget.RecyclerView.class, "scrollToPosition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sJustAppliedFilter && param.thisObject == sConvList) {
                            // Let the original call proceed; we restore anchor in afterHookedMethod
                        }
                    } catch (Throwable ignored) {}
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (sJustAppliedFilter && param.thisObject == sConvList) {
                            restoreAnchorPositionRecyclerView((androidx.recyclerview.widget.RecyclerView) param.thisObject);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Hook smoothScrollToPosition for anchor restore
            XposedBridge.hookAllMethods(androidx.recyclerview.widget.RecyclerView.class, "smoothScrollToPosition", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (sJustAppliedFilter && param.thisObject == sConvList) {
                            restoreAnchorPositionRecyclerView((androidx.recyclerview.widget.RecyclerView) param.thisObject);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            sHookInstalled = true;
            LogWriter.log(TAG, "RecyclerView hooks installed (getItemCount/onBindViewHolder/scrollToPosition)");
        } catch (Throwable e) {
            LogWriter.log(TAG, "installRecyclerViewHooks fail: " + e.getMessage());
        }
    }

    // ================================================================
    // 数据层双路径 Hook（按 聊天分组_新.md）
    //   新路径(RecyclerView): po5.u.q(zs3.s1) after -> s1.a 为全量 jo5.z
    //   旧路径(ListView):     jo5.f.a(List)   before -> 参数即全量 jo5.z
    // ================================================================

    private static void installDataPathHooks(ClassLoader cl) {
        if (sDataPathHooksInstalled) return;
        // ---- 旧路径: jo5.f.a(List) before ----
        try {
            Class<?> jo5f = XposedHelpers.findClass("jo5.f", cl);
            XposedBridge.hookAllMethods(jo5f, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof List) {
                                sFullCache = new ArrayList<>((List<?>) param.args[i]);
                                sDataLayerReady = true;
                                LogWriter.log(TAG, "jo5.f.a: cached " + sFullCache.size() + " useDataLayer=" + sUseDataLayer);
                                if (sFilterActive && sUseDataLayer) {
                                    param.args[i] = buildFilteredDataList();
                                    LogWriter.log(TAG, "jo5.f.a: -> filtered " + ((List<?>) param.args[i]).size());
                                }
                            }
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "jo5.f.a hook err: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "dataPath jo5.f.a(List) hooked");
        } catch (Throwable e) {
            LogWriter.log(TAG, "jo5.f.a not found: " + e.getMessage());
        }

        // ---- 新路径: po5.u.q(zs3.s1) after ----
        try {
            Class<?> po5u = XposedHelpers.findClass("po5.u", cl);
            XposedBridge.hookAllMethods(po5u, "q", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 1) return;
                        Object s1 = param.args[0];
                        List<?> full = dataListFromS1(s1);
                        if (full != null) {
                            sRecyclerAdapter = param.thisObject;
                            sFullCache = new ArrayList<>(full);
                            sDataLayerReady = true;
                            LogWriter.log(TAG, "po5.u.q: cached " + sFullCache.size() + " useDataLayer=" + sUseDataLayer);
                        }
                        if (sFilterActive && sUseDataLayer) {
                            Object i = XposedHelpers.getObjectField(param.thisObject, "I");
                            if (i != null) {
                                Object r = XposedHelpers.getObjectField(i, "r");
                                if (r instanceof List) {
                                    List<Object> dst = (List<Object>) r;
                                    dst.clear();
                                    dst.addAll(buildFilteredDataList());
                                    XposedHelpers.callMethod(param.thisObject, "notifyDataSetChanged");
                                    LogWriter.log(TAG, "po5.u.q: I.r rebuilt -> " + dst.size());
                                }
                            }
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "po5.u.q hook err: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "dataPath po5.u.q(zs3.s1) hooked");
        } catch (Throwable e) {
            LogWriter.log(TAG, "po5.u.q not found: " + e.getMessage());
        }
        sDataPathHooksInstalled = true;
    }

    private static List<?> dataListFromS1(Object s1) {
        try {
            if (s1 == null) return null;
            java.lang.reflect.Field f = s1.getClass().getDeclaredField("a");
            f.setAccessible(true);
            Object v = f.get(s1);
            return (v instanceof List) ? (List<?>) v : null;
        } catch (Throwable ignored) {
            try { return (s1 instanceof List) ? (List<?>) s1 : null; } catch (Throwable e) { return null; }
        }
    }

    /** 依据当前 sFilterRule / sAllowedUsernames 从 sFullCache 构建过滤后列表。
     *  判断基于 jo5.z.d(k4).i1() 用户名（文档核心），失败时节向 kindOf/scanKind */
    private static List<Object> buildFilteredDataList() {
        List<Object> out = new ArrayList<>();
        if (sFullCache == null || sFullCache.isEmpty()) return out;
        for (Object item : sFullCache) {
            if (item == null) continue;
            String username = usernameOf(item);
            if (username != null) {
                if (matchesRule(username)) out.add(item);
            } else {
                // fallback: 对象树扫描
                char kind = kindOf(item);
                if (ruleMatchesKind(kind)) out.add(item);
            }
        }
        return out;
    }

    private static boolean ruleMatchesKind(char kind) {
        if ("group".equals(sFilterRule)) return kind == 'G';
        if ("service".equals(sFilterRule)) return kind == 'S';
        if ("friend".equals(sFilterRule)) return (kind == 'F' || kind == 'X');
        return sAllowedUsernames != null && !sAllowedUsernames.isEmpty();
    }

    private static boolean matchesRule(String username) {
        if (sFilterRule == null || sFilterRule.isEmpty()) return true;
        if (sFilterRule.startsWith("label:")) {
            return sAllowedUsernames.contains(username);
        }
        if ("group".equals(sFilterRule)) {
            return username.endsWith("@chatroom") || username.endsWith("@im.chatroom")
                    || username.endsWith("@lbsroom");
        }
        if ("service".equals(sFilterRule)) {
            return username.startsWith("gh_") || username.contains("officialaccounts")
                    || username.equals("weixin");
        }
        if ("friend".equals(sFilterRule)) {
            return !(username.endsWith("@chatroom") || username.endsWith("@im.chatroom")
                    || username.endsWith("@lbsroom"))
                    && !username.startsWith("gh_") && !username.contains("officialaccounts")
                    && !username.equals("weixin") && !username.equals("filehelper")
                    && !username.startsWith("service_");
        }
        return sAllowedUsernames.contains(username);
    }

    private static String usernameOf(Object item) {
        try {
            Object k4 = XposedHelpers.getObjectField(item, "d");
            if (k4 == null) return null;
            Object u = XposedHelpers.callMethod(k4, "i1");
            return (u instanceof String) ? (String) u : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void restoreAnchorPositionRecyclerView(androidx.recyclerview.widget.RecyclerView rv) {
        if (rv == null) return;
        try {
            androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (lm == null) return;

            if (sResetToTop) {
                rv.scrollToPosition(0);
                return;
            }

            int target = -1;
            if (sAnchorUsername != null) {
                target = findPositionByUsername(null, sAnchorUsername);
                if (target >= 0) {
                    target -= headerCountOf(sAdapter);
                }
            }
            if (target < 0 && sAnchorRawPos >= 0) {
                target = sAnchorRawPos;
            }
            if (target < 0) {
                rv.scrollToPosition(0);
                return;
            }
            int itemCount = rv.getAdapter() != null ? rv.getAdapter().getItemCount() : 0;
            if (target >= itemCount) target = Math.max(0, itemCount - 1);

            if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                ((androidx.recyclerview.widget.LinearLayoutManager) lm).scrollToPositionWithOffset(target, sAnchorChildTop);
                LogWriter.log(TAG, "restoreAnchor RV -> pos=" + target + " offset=" + sAnchorChildTop + " user=" + sAnchorUsername);
            } else {
                rv.scrollToPosition(target);
                LogWriter.log(TAG, "restoreAnchor RV -> scrollToPosition=" + target + " user=" + sAnchorUsername);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "restoreAnchor RV err: " + e.getMessage());
        }
    }

    private static Field sDataListField = null;
    private static Object sDataOwner = null;

    @SuppressWarnings("unchecked")
    private static java.util.List<?> dataListOf() {
        try {
            if (sDataListField != null && sDataOwner != null) {
                Object v = sDataListField.get(sDataOwner);
                if (v instanceof java.util.List && ((java.util.List<?>) v).size() > 0) {
                    return (java.util.List<?>) v;
                }
                sDataListField = null;
                sDataOwner = null;
            }
            // Primary: direct sAdapter.q.d access (same path as scanUnreadCounts)
            try {
                Object q = XposedHelpers.getObjectField(sAdapter, "q");
                if (q != null) {
                    Class<?> qClass = q.getClass();
                    java.lang.reflect.Field dField = null;
                    while (qClass != null) {
                        try {
                            dField = qClass.getDeclaredField("d");
                            break;
                        } catch (NoSuchFieldException e) {
                            qClass = qClass.getSuperclass();
                        }
                    }
                    if (dField != null) {
                        dField.setAccessible(true);
                        Object d = dField.get(q);
                        if (d instanceof java.util.List && ((java.util.List<?>) d).size() > 0) {
                            sDataListField = dField;
                            sDataOwner = q;
                            LogWriter.log(TAG, "dataList direct: q.d size=" + ((java.util.List<?>) d).size());
                            return (java.util.List<?>) d;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // Fallback: deep search
            java.util.List<Object> visited = new java.util.ArrayList<>();
            Field best = findDataListFieldDeep(sAdapter, visited, 0);
            if (best != null) {
                sDataListField = best;
                sDataOwner = findOwnerDeep(sAdapter, best);
                LogWriter.log(TAG, "dataList discover: " + (sDataOwner != null ? sDataOwner.getClass().getName() : "null") + "." + best.getName() + " size=" + listSizeDeep(best, sDataOwner));
                Object v = best.get(sDataOwner);
                if (v instanceof java.util.List) return (java.util.List<?>) v;
            }
            LogWriter.log(TAG, "dataList discover: NOT FOUND");
        } catch (Throwable e) {
            LogWriter.log(TAG, "dataListOf err: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private static Field findDataListFieldDeep(Object obj, java.util.List<Object> visited, int depth) {
        if (obj == null || depth > 4 || !visited.add(obj)) return null;
        Field best = null;
        int bestScore = -1;
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); } catch (Throwable ignored) {}
                    Object v;
                    try { v = f.get(obj); } catch (Throwable ignored) { continue; }
                    if (!(v instanceof java.util.List)) continue;
                    java.util.List<?> l = (java.util.List<?>) v;
                    int size = l.size();
                    if (size <= 0) continue;
                    int sampleScore = 0;
                    int sample = Math.min(size, 6);
                    for (int i = 0; i < sample; i++) {
                        Object it = l.get(i);
                        if (it == null) continue;
                        if (it.getClass().getName().startsWith("com.tencent.mm")) {
                            sampleScore += Math.min(5, it.getClass().getDeclaredFields().length);
                        }
                    }
                    int score = Math.min(size, 100) * 100 + sampleScore;
                    if (score > bestScore) { bestScore = score; best = f; }
                }
            }
            if (best == null && depth < 4) {
                for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        if (ft == Object.class || ft.isPrimitive() || ft == String.class) continue;
                        if (java.util.List.class.isAssignableFrom(ft) || java.util.Map.class.isAssignableFrom(ft)) continue;
                        if (android.widget.Adapter.class.isAssignableFrom(ft)) continue;
                        try { f.setAccessible(true); } catch (Throwable ignored) {}
                        Object inner;
                        try { inner = f.get(obj); } catch (Throwable ignored) { continue; }
                        if (inner == null || inner == obj) continue;
                        Field innerBest = findDataListFieldDeep(inner, visited, depth + 1);
                        if (innerBest != null) return innerBest;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return best;
    }

    private static Object findOwnerDeep(Object root, Field target) {
        if (target.getDeclaringClass().isInstance(root)) {
            try {
                Object v = target.get(root);
                if (v instanceof java.util.List) return root;
            } catch (Throwable ignored) {}
        }
        try {
            for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> ft = f.getType();
                    if (ft == Object.class || ft.isPrimitive() || ft == String.class) continue;
                    if (java.util.List.class.isAssignableFrom(ft) || java.util.Map.class.isAssignableFrom(ft)) continue;
                    try { f.setAccessible(true); } catch (Throwable ignored) {}
                    Object inner = f.get(root);
                    if (inner == null) continue;
                    Object deep = findOwnerDeep(inner, target);
                    if (deep != null) return deep;
                }
            }
        } catch (Throwable ignored) {}
        return root;
    }

    private static int listSizeDeep(Field f, Object owner) {
        try { Object v = f.get(owner); return (v instanceof java.util.List) ? ((java.util.List<?>) v).size() : 0; }
        catch (Throwable ignored) { return 0; }
    }

    /** 会话行类型: G=群聊 / S=服务号 / F=好友。扫描 item 对象树里的标识串判断，兼容任意混淆结构 */
    private static char kindOf(Object item) {
        if (item == null) return 'X';
        try { return scanKind(item, new HashSet<Object>(), 0); }
        catch (Throwable ignored) { return 'X'; }
    }

    private static char scanKind(Object o, Set<Object> seen, int depth) {
        if (o == null || depth > 4) return 'X';
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character) return 'X';
        if (o instanceof android.graphics.drawable.Drawable || o instanceof android.graphics.Bitmap) return 'X';
        if (!seen.add(o)) return 'X';
        char fallback = 'X';
        try {
            if (o instanceof java.util.Map) {
                for (Object e : ((java.util.Map<?, ?>) o).values()) {
                    char k = scanKind(e, seen, depth + 1);
                    if (k == 'G') return 'G';
                    if (k == 'S' && fallback == 'X') fallback = 'S';
                }
            } else if (o instanceof java.util.Collection) {
                for (Object e : (java.util.Collection<?>) o) {
                    char k = scanKind(e, seen, depth + 1);
                    if (k == 'G') return 'G';
                    if (k == 'S' && fallback == 'X') fallback = 'S';
                }
            } else if (o.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(o);
                for (int i = 0; i < len && i < 32; i++) {
                    char k = scanKind(java.lang.reflect.Array.get(o, i), seen, depth + 1);
                    if (k == 'G') return 'G';
                    if (k == 'S' && fallback == 'X') fallback = 'S';
                }
            } else {
                for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        try { f.setAccessible(true); } catch (Throwable ignored) {}
                        Object v;
                        try { v = f.get(o); } catch (Throwable ignored) { continue; }
                        if (v instanceof String) {
                            String s = (String) v;
                            if (s != null && s.endsWith("@chatroom") && !s.endsWith("@im.chatroom")) return 'G';
                            if (s != null && (s.startsWith("gh_")
                                    || s.equals("weixin")
                                    || s.contains("officialaccounts"))) {
                                if (fallback == 'X') fallback = 'S';
                            }
                        } else if (v != null && !v.getClass().getName().startsWith("java.")
                                && !v.getClass().getName().startsWith("android.") && depth < 3) {
                            char k = scanKind(v, seen, depth + 1);
                            if (k == 'G') return 'G';
                            if (k == 'S' && fallback == 'X') fallback = 'S';
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    /** 返回会话行可标识串：群=@chatroom、服务=gh_ 前缀串、普通好友=null（用于锚定/诊断） */
    private static String idOf(Object item) {
        if (item == null) return null;
        try { return scanId(item, new HashSet<Object>(), 0); }
        catch (Throwable ignored) { return null; }
    }

    private static String scanId(Object o, Set<Object> seen, int depth) {
        if (o == null || depth > 4) return null;
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) return null;
        if (!seen.add(o)) return null;
        try {
            if (o instanceof java.util.Map) {
                for (Object e : ((java.util.Map<?, ?>) o).values()) { String s = scanId(e, seen, depth + 1); if (s != null) return s; }
            } else if (o instanceof java.util.Collection) {
                for (Object e : (java.util.Collection<?>) o) { String s = scanId(e, seen, depth + 1); if (s != null) return s; }
            } else {
                for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        try { f.setAccessible(true); } catch (Throwable ignored) {}
                        Object v;
                        try { v = f.get(o); } catch (Throwable ignored) { continue; }
                        if (v instanceof String) {
                            String s = (String) v;
                            if (s != null && s.endsWith("@chatroom") && !s.endsWith("@im.chatroom")) return s;
                            if (s != null && s.startsWith("gh_")) return s;
                        } else if (v != null && !v.getClass().getName().startsWith("java.")
                                && !v.getClass().getName().startsWith("android.") && depth < 3) {
                            String s = scanId(v, seen, depth + 1);
                            if (s != null) return s;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void applyFilter(int labelId, String labelName) {
        LogWriter.log(TAG, "apply " + labelId + " name=" + labelName);
        if (labelId <= 0) { clearFilter(); return; }

        // 必须在修改过滤状态前捕获锚点（当前列表可能仍处于上一过滤状态）
        captureAnchor();

        // Determine filter rule based on virtual label id (null-safe, 避免自定义标签名含关键词误判)
        if (labelId == ChatGroupHook.LABEL_ID_GROUP) {
            sFilterRule = "group";
        } else if (labelId == ChatGroupHook.LABEL_ID_SERVICE) {
            sFilterRule = "service";
        } else if (labelId == ChatGroupHook.LABEL_ID_FRIEND) {
            sFilterRule = "friend";
        } else {
            // Regular label: use contacts list
            sFilterRule = "label:" + labelName;
            List<String> contacts = ChatGroupHook.getContactsByLabelId(labelId);
            LogWriter.log(TAG, "contacts " + (contacts != null ? contacts.size() : -1));
            sAllowedUsernames = new HashSet<>(contacts != null ? contacts : Collections.emptyList());
        }

        sJustAppliedFilter = true;
        sResetToTop = true;
        sFilterActive = true;

        // 数据层优先（文档核心）：已缓存全量且数据层 hook 生效时直接重建数据列表（新路径 I.r /
        // 旧路径 jo5.f.a 参数替换），并关闭 View 层 position 兜底避免双重过滤；
        // 数据层未就绪时才回退 View 层 position 映射。
        boolean useDataLayer = sDataLayerReady && sFullCache != null && !sFullCache.isEmpty();
        sUseDataLayer = useDataLayer;
        boolean applied = false;
        if (useDataLayer) {
            sFilteredPositions = Collections.emptyList();
            applied = applyDataLayerNow();
            if (!applied && sRecyclerAdapter == null) {
                // 旧路径：依赖 jo5.f.a before-hook 在 notifyDataSetChanged 时替换参数
                applied = true;
            }
        }
        if (!applied) {
            sUseDataLayer = false;
            // 兜底：View 层 position 映射（数据层未就绪/未缓存时）
            if (sFilterRule.startsWith("label:")) {
                if (sAllowedUsernames.isEmpty()) {
                    LogWriter.log(TAG, "empty contacts - showing empty list");
                } else {
                    buildFilteredPositions();
                }
            } else {
                buildFilteredByRule();
            }
        }

        notifyAdapterChanged();
        restoreScrollLater();
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            sJustAppliedFilter = false;
            sResetToTop = false;
        }, 800);
        LogWriter.log(TAG, "ON rule=" + sFilterRule + " filtered=" + sFilteredPositions.size() + " dataLayer=" + applied);
    }

    /** 数据层立即应用过滤：新路径重建 zs3.c0.I.r，旧路径触发 jo5.f.a 重新入数据。返回是否走数据层 */
    private static boolean applyDataLayerNow() {
        try {
            if (sRecyclerAdapter != null) {
                Object i = XposedHelpers.getObjectField(sRecyclerAdapter, "I");
                if (i != null) {
                    Object r = XposedHelpers.getObjectField(i, "r");
                    if (r instanceof List) {
                        List<Object> dst = (List<Object>) r;
                        dst.clear();
                        dst.addAll(buildFilteredDataList());
                        try { XposedHelpers.callMethod(sRecyclerAdapter, "notifyDataSetChanged"); } catch (Throwable ignored) {}
                        LogWriter.log(TAG, "dataLayer apply: I.r rebuilt -> " + dst.size());
                        return true;
                    }
                }
            }
            // 旧路径：jo5.f.a(List) before-hook 会在下次数据回调时替换参数；
            // 此处通过 notifyDataSetChanged 触发 adapter 刷新（若 jo5.f.a 未触发则由 View 层兜底）
            return false;
        } catch (Throwable e) {
            LogWriter.log(TAG, "applyDataLayerNow err: " + e.getMessage());
            return false;
        }
    }

    /** 记录当前列表滚动锚点：ListView 原始 position（含 header）+ 首个可见 child 的 top。
     *  恢复时 setSelectionFromTop(rawPos, childTop)，保证过滤前后视觉位置不跳（顶部=顶部、中部=中部）。
     *  必须在任何状态变更（sFilterActive/sFilteredPositions）之前调用。
     *  getChildAt(0) 在顶部下拉刷新等场景可能是已滚出屏幕的复用 view(top<0)，
     *  因此扫描第一个 top>=0 的真正可见 child 作为锚点。 */
    private static void captureAnchor() {
        int oldRaw = sAnchorRawPos;
        sAnchorRawPos = -1;
        sAnchorChildTop = 0;
        sAnchorUsername = null;
        View v = sConvList;
        if (v == null) { LogWriter.log(TAG, "captureAnchor: sConvList null"); return; }
        try {
            AbsListView lv = (AbsListView) v;
            int raw = lv.getFirstVisiblePosition();
            int childTop = 0;
            String username = null;
            try {
                if (lv.getChildCount() > 0) {
                    childTop = lv.getChildAt(0).getTop();
                    if (childTop < 0) {
                        for (int i = 0; i < lv.getChildCount(); i++) {
                            View c = lv.getChildAt(i);
                            if (c != null && c.getTop() >= 0) {
                                childTop = c.getTop();
                                raw = lv.getFirstVisiblePosition() + i;
                                break;
                            }
                        }
                    }
                    // 取锚定行对应的会话 username（header 位置跳过）
                    username = usernameAtPosition(lv, raw);
                }
            } catch (Throwable ignored) {}
            sAnchorRawPos = raw;
            sAnchorChildTop = childTop;
            sAnchorUsername = username;
            LogWriter.log(TAG, "captureAnchor raw=" + raw + " childTop=" + childTop
                + " user=" + username + " rule=" + sFilterRule + " (old=" + oldRaw + ")");
        } catch (Throwable e) {
            LogWriter.log(TAG, "captureAnchor err: " + e.getMessage());
        }
    }

    /** 取 ListView 某 raw position 对应会话的标识(群=@chatroom/服务=gh_)；header/无效位置返回 null
     *  过滤激活时须经 sFilteredPositions 映射到底层索引（getView 才有映射，getItem 没有） */
    private static String usernameAtPosition(AbsListView lv, int rawPos) {
        try {
            Object adapter = lv.getAdapter();
            if (adapter == null) return null;
            int headerCount = headerCountOf(adapter);
            int dataPos = rawPos - headerCount;
            if (dataPos < 0) return null;
            Object item = null;
            if (sFilterActive && sFilteredPositions != null
                    && dataPos < sFilteredPositions.size()) {
                item = itemAt(sFilteredPositions.get(dataPos));
            } else {
                item = itemAt(dataPos);
            }
            if (item == null) return null;
            return idOf(item);
        } catch (Throwable ignored) {}
        return null;
    }

    /** 从底层 sAdapter 的会话数据列表取第 idx 个数据项（8.0.78 自适应反射） */
    private static Object itemAt(int idx) {
        try {
            if (sAdapter == null || idx < 0) return null;
            java.util.List<?> dataList = dataListOf();
            if (dataList == null || idx >= dataList.size()) return null;
            return dataList.get(idx);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int headerCountOf(Object adapter) {
        try {
            Object h = XposedHelpers.getObjectField(adapter, "mHeaderViewInfos");
            if (h instanceof List) return ((List<?>) h).size();
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 过滤后：按锚定 rawPos 恢复滚动位置（clamp 到新列表范围），保持视觉原位 */
    private static void restoreScrollLater() {
        final View v = sConvList;
        if (v == null) return;
        v.post(() -> {
            try { restoreAnchorPosition((AbsListView) v); } catch (Throwable ignored) {}
        });
        v.postDelayed(() -> {
            try { restoreAnchorPosition((AbsListView) v); } catch (Throwable ignored) {}
        }, 150);
    }

    /** 依据锚定信息恢复 AbsListView 滚动位置（兼容 layoutChildren 回调）
     *  优先按 username 定位（跨过滤切换 position 会错位）；username 不在当前列表时回退顶部/raw */
    private static void restoreAnchorPosition(AbsListView lv) {
        if (lv == null) return;
        int count;
        try {
            count = lv.getCount();
        } catch (Throwable ignored) {
            count = 0;
        }
        if (count <= 0) return;

        if (sResetToTop) {
            try { lv.setSelection(0); } catch (Throwable ignored) {}
            return;
        }

        int target = -1;
        if (sAnchorUsername != null) {
            int pos = findPositionByUsername(lv, sAnchorUsername);
            if (pos >= 0) target = pos;
        }
        if (target < 0 && sAnchorRawPos >= 0) {
            target = sAnchorRawPos;
        }
        if (target < 0) {
            // username 已不在当前列表（跨分类过滤）且无 raw 锚点 -> 顶部
            LogWriter.log(TAG, "restoreAnchor: user absent, goto top user=" + sAnchorUsername);
            try { lv.setSelection(0); } catch (Throwable ignored) {}
            return;
        }
        if (target >= count) target = count - 1;
        try {
            lv.setSelectionFromTop(target, sAnchorChildTop);
            LogWriter.log(TAG, "restoreAnchor -> pos=" + target + " (raw=" + sAnchorRawPos
                + ") childTop=" + sAnchorChildTop + " user=" + sAnchorUsername);
        } catch (Throwable ignored) {}
    }

    /** 在当前列表（含过滤映射）中查找会话标识对应的 raw position；找不到返回 -1
     *  过滤激活时返回 headerCount + 过滤索引；否则返回 headerCount + 底层索引 */
    private static int findPositionByUsername(AbsListView lv, String username) {
        if (username == null) return -1;
        try {
            Object adapter = lv.getAdapter();
            if (adapter == null) return -1;
            int headerCount = headerCountOf(adapter);
            java.util.List<?> dataList = dataListOf();
            if (sFilterActive && sFilteredPositions != null) {
                for (int i = 0; i < sFilteredPositions.size(); i++) {
                    Object item = itemAt(sFilteredPositions.get(i));
                    if (item == null) continue;
                    try {
                        Object u = idOf(item);
                        if (u instanceof String && username.equals(u)) {
                            return headerCount + i;
                        }
                    } catch (Throwable ignored) {}
                }
            } else {
                if (dataList == null) return -1;
                for (int i = 0; i < dataList.size(); i++) {
                    Object item = dataList.get(i);
                    if (item == null) continue;
                    try {
                        Object u = idOf(item);
                        if (u instanceof String && username.equals(u)) {
                            return headerCount + i;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    public static void clearFilter() {
        if (!sFilterActive) return;
        // 必须在清空状态前捕获锚点（当前列表仍处于过滤状态）
        captureAnchor();
        sFilterActive = false;
        sFilterRule = "";
        sAllowedUsernames = Collections.emptySet();
        sFilteredPositions = Collections.emptyList();
        sJustAppliedFilter = true;
        sResetToTop = true;

        // 数据层优先：恢复全量缓存，关闭数据层过滤；否则走 View 层兜底
        boolean useDataLayer = sDataLayerReady && sFullCache != null && !sFullCache.isEmpty();
        sUseDataLayer = useDataLayer;
        boolean restored = false;
        if (useDataLayer) {
            sFilteredPositions = Collections.emptyList();
            restored = restoreFullCacheNow();
            if (!restored && sRecyclerAdapter == null) {
                // 旧路径：jo5.f.a 在下次回调时以全量恢复（sFilterActive=false 不替换参数）
                restored = true;
            }
        }
        notifyAdapterChanged();
        restoreScrollLater();
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            sJustAppliedFilter = false;
            sResetToTop = false;
        }, 800);
        LogWriter.log(TAG, "OFF dataLayer=" + restored);
    }

    /** 数据层清除过滤：新路径重建 I.r 为全量缓存，旧路径返回 false 走 View 层兜底 */
    private static boolean restoreFullCacheNow() {
        try {
            if (sRecyclerAdapter != null) {
                Object i = XposedHelpers.getObjectField(sRecyclerAdapter, "I");
                if (i != null) {
                    Object r = XposedHelpers.getObjectField(i, "r");
                    if (r instanceof List) {
                        List<Object> dst = (List<Object>) r;
                        dst.clear();
                        dst.addAll(new ArrayList<>(sFullCache));
                        try { XposedHelpers.callMethod(sRecyclerAdapter, "notifyDataSetChanged"); } catch (Throwable ignored) {}
                        LogWriter.log(TAG, "dataLayer clear: I.r restored -> " + dst.size());
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable e) {
            LogWriter.log(TAG, "restoreFullCacheNow err: " + e.getMessage());
            return false;
        }
    }

    public static int getUnreadForLabel(int labelId) {
        Integer c = sUnreadByLabel.get(labelId);
        return c != null ? c : 0;
    }

    static boolean scanUnreadCounts() {
        long now = System.currentTimeMillis();
        if (now - sLastUnreadScan < UNREAD_SCAN_INTERVAL) return false;
        sLastUnreadScan = now;
        try {
            if (sAdapter == null) return false;
            java.util.List<?> dataList = dataListOf();
            if (dataList == null || dataList.isEmpty()) return false;

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
            if (changed) {
                LogWriter.log(TAG, "unread changed: " + totalScanned + " items, " + totalWithUnread
                    + " unread, G=" + groupUnread + " F=" + friendUnread + " S=" + serviceUnread);
            }
            return changed;
        } catch (Throwable e) {
            LogWriter.log(TAG, "scanUnread: " + e.getMessage());
            return false;
        }
    }

    private static void buildFilteredByRule() {
        if (sAdapter == null) { LogWriter.log(TAG, "sAdapter null buildFilteredByRule"); return; }
        List<Integer> positions = new ArrayList<>();
        try {
            java.util.List<?> dataList = dataListOf();
            if (dataList.isEmpty()) { LogWriter.log(TAG, "dataList empty"); return; }

            LogWriter.log(TAG, "scan " + dataList.size() + " items by rule " + sFilterRule);
            int dumpCount = 0;
            for (int i = 0; i < dataList.size(); i++) {
                Object x = dataList.get(i);
                if (x == null) continue;
                // 优先用会话 username(d.i1) 精确分类（与数据层口径一致），
                // 仅在取不到 username 时才退回反射扫描，避免把好友误判成群/服务。
                String username = usernameOf(x);
                char kind;
                boolean match;
                if (username != null) {
                    match = matchesRule(username);
                    kind = username.endsWith("@chatroom") || username.endsWith("@im.chatroom")
                            || username.endsWith("@lbsroom") ? 'G'
                         : (username.startsWith("gh_") || username.contains("officialaccounts")
                            || username.equals("weixin")) ? 'S' : 'F';
                } else {
                    kind = kindOf(x);
                    match = ruleMatchesKind(kind);
                }
                if (match) {
                    positions.add(i);
                    if (dumpCount < 5) {
                        LogWriter.log(TAG, " match[" + dumpCount + "]=" + (username != null ? username : idOf(x)) + " kind=" + kind);
                        dumpCount++;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "build err: " + e.getMessage());
        }
        sFilteredPositions = positions;
        LogWriter.log(TAG, "rule " + sFilterRule + " -> filtered=" + positions.size());
    }

    private static void buildFilteredPositions() {
        if (sAdapter == null) { LogWriter.log(TAG, "sAdapter null buildFilteredPositions"); return; }
        List<Integer> positions = new ArrayList<>();
        try {
            java.util.List<?> dataList = dataListOf();
            if (dataList == null || dataList.isEmpty()) { LogWriter.log(TAG, "dataList null/empty"); return; }

            LogWriter.log(TAG, "scan " + dataList.size() + " items by set");
            for (int i = 0; i < dataList.size(); i++) {
                Object x = dataList.get(i);
                if (x == null) continue;
                try {
                    Object k4 = XposedHelpers.getObjectField(x, "d");
                    if (k4 == null) continue;
                    String username = (String) XposedHelpers.callMethod(k4, "i1");
                    if (username != null && sAllowedUsernames.contains(username)) {
                        positions.add(i);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "build err: " + e.getMessage());
        }
        sFilteredPositions = positions;
    }

    private static void notifyAdapterChanged() {
        try {
            Object target = sHeaderAdapter != null ? sHeaderAdapter : sAdapter;
            LogWriter.log(TAG, "notifyAdapterChanged target=" + (target != null ? target.getClass().getSimpleName() : "null") + " active=" + sFilterActive + " filtered=" + sFilteredPositions.size());
            if (target == null) return;
            // 防御: 仅对 Adapter 实例调用 notifyDataSetChanged，避免对 LinearLayout 等非法目标抛错
            if (!(target instanceof android.widget.Adapter)) {
                LogWriter.log(TAG, "notifyAdapterChanged skip: target is not Adapter");
                return;
            }
            if (target.getClass().getName().equals("android.widget.HeaderViewListAdapter")) {
                Object wrapped = XposedHelpers.callMethod(target, "getWrappedAdapter");
                if (wrapped instanceof android.widget.Adapter) {
                    XposedHelpers.callMethod(wrapped, "notifyDataSetChanged");
                    LogWriter.log(TAG, "notifyAdapterChanged via wrapped adapter OK");
                }
            } else {
                XposedHelpers.callMethod(target, "notifyDataSetChanged");
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "notifyAdapterChanged err: " + e.getMessage());
        }
    }

    /* Returns all usernames matching a built-in label rule, or null if not built-in */
    public static List<String> getUsernamesForBuiltInLabel(int labelId) {
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
            java.util.List<?> dataList = dataListOf();
            if (dataList == null || dataList.isEmpty()) return;
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