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
    private static long sLastBadgeRefresh = 0;
    private static long sLastUnreadScan = 0;
    private static final long UNREAD_SCAN_INTERVAL = 2000;
    private static int sGetCountCall = 0;
    private static int sGetViewCall = 0;
    private static volatile boolean sJustAppliedFilter = false;
    // 切换分类标签时保持视觉位置（锚点恢复，v3.0.530 起不再强制回顶）
    private static volatile boolean sResetToTop = false;
    // 本次恢复是否已完成（单次恢复守卫，避免反复 setSelection 触发微信下拉小程序面板）
    private static volatile boolean sResetDone = false;
    // v3.0.530：捕获时的像素滚动偏移（computeVerticalScrollOffset），过滤后若像素偏移
    // 未变化则完全不动列表（索引重排不算跳动），只有真正漂移时才按锚点恢复
    private static volatile int sAnchorPixelOffset = 0;
    // 过滤代次：快速连续点击标签时，旧 800ms 定时器不得复位新一代的状态
    private static volatile int sFilterGeneration = 0;
    // 统一恢复去重：同一帧内所有触发源（restoreScrollLater / layoutChildren / scrollToPosition）只 post 一次恢复
    private static volatile boolean sRestorePosted = false;
    // RV 防重入：restoreAnchorPositionRecyclerView 内部 scrollToPosition 会再次触发钩子
    private static volatile boolean sRestoringRV = false;
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
        // v3.0.566: convList 可能为 null（微信 8.0.79 用 RecyclerView），改从 adapter 类型判断
        if (!sIsRecyclerView && adapterInstance != null
                && adapterInstance instanceof androidx.recyclerview.widget.RecyclerView.Adapter) {
            LogWriter.log(TAG, "adapter is RecyclerView.Adapter (" + adapterInstance.getClass().getName()
                    + "), using RecyclerView filter path");
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

        // 防御: realAdapter 不是预期 Adapter 实例时跳过
        if (!sIsRecyclerView && !(realAdapter instanceof android.widget.Adapter)) {
            LogWriter.log(TAG, "realAdapter is not an Adapter: " + realAdapter.getClass().getName());
            return;
        }
        if (sIsRecyclerView && !(realAdapter instanceof androidx.recyclerview.widget.RecyclerView.Adapter)) {
            LogWriter.log(TAG, "realAdapter is not RecyclerView.Adapter: " + realAdapter.getClass().getName());
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
            // 在 Android 14 上 lookForSelectablePosition 已移除，需要直接拦截 layoutChildren。
            // v3.0.530：切标签「保持视觉位置」（锚点恢复），不再强制回顶。过滤后微信按
            // 自己保存的滚动位置重排时索引会变（日志 getView hooked: 0->1 但 childTop 不变），
            // 因此用像素偏移（computeVerticalScrollOffset）判断视口是否真的漂移：
            // 像素偏移没变 -> 完全不动；偏移明显变化（微信把列表拉到别处）才 post 恢复。
            try {
                java.lang.reflect.Method lc = android.widget.AbsListView.class.getDeclaredMethod("layoutChildren");
                XposedBridge.hookMethod(lc, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sJustAppliedFilter || param.thisObject != sConvList) return;
                        if (sAnchorPixelOffset <= 0 && sAnchorRawPos <= 0) return; // 无有效锚点
                        try {
                            AbsListView lv = (AbsListView) param.thisObject;
                            int curOffset = listViewScrollOffset(lv);
                            // 偏移明显漂移（阈值 10px）才恢复；避免微小测量抖动反复 setSelection
                            if (Math.abs(curOffset - sAnchorPixelOffset) > 10) {
                                // 不能在 layoutChildren 期间同步 setSelection（会重入布局、
                                // 短列表被顶到边缘触发微信下拉面板），统一 post 到下一帧执行
                                postRestoreOnce();
                                LogWriter.log(TAG, "layoutChildren detect offset=" + curOffset
                                        + " anchor=" + sAnchorPixelOffset + ", post restore");
                            }
                        } catch (Throwable ignored) {}
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
                        if (sJustAppliedFilter && !sResetToTop && param.thisObject == sConvList && !sRestoringRV) {
                            sRestoringRV = true;
                            try {
                                restoreAnchorPositionRecyclerView((androidx.recyclerview.widget.RecyclerView) param.thisObject);
                            } finally {
                                sRestoringRV = false;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Hook smoothScrollToPosition for anchor restore
            XposedBridge.hookAllMethods(androidx.recyclerview.widget.RecyclerView.class, "smoothScrollToPosition", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (sJustAppliedFilter && !sResetToTop && param.thisObject == sConvList && !sRestoringRV) {
                            sRestoringRV = true;
                            try {
                                restoreAnchorPositionRecyclerView((androidx.recyclerview.widget.RecyclerView) param.thisObject);
                            } finally {
                                sRestoringRV = false;
                            }
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

    /**
     * 统一会话分类 —— 全模块唯一口径, 禁止各处自行拼接后缀判断。
     * G=群聊(@chatroom / @im.chatroom / @lbsroom)
     * S=服务号(gh_ / officialaccounts / weixin)
     * F=普通好友
     * X=系统/无法判定(filehelper / service_ / 空)
     */
    static char classifyUsername(String username) {
        if (username == null || username.isEmpty()) return 'X';
        if (username.endsWith("@chatroom")
                || username.endsWith("@im.chatroom")
                || username.endsWith("@lbsroom")) return 'G';
        if (username.startsWith("gh_")
                || username.contains("officialaccounts")
                || username.equals("weixin")) return 'S';
        if (username.equals("filehelper") || username.startsWith("service_")) return 'X';
        return 'F';
    }

    private static boolean matchesRule(String username) {
        if (sFilterRule == null || sFilterRule.isEmpty()) return true;
        if (sFilterRule.startsWith("label:")) {
            return sAllowedUsernames.contains(username);
        }
        char kind = classifyUsername(username);
        if ("group".equals(sFilterRule)) return kind == 'G';
        if ("service".equals(sFilterRule)) return kind == 'S';
        if ("friend".equals(sFilterRule)) return kind == 'F';
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

            // v3.0.530：不再强制回顶。像素偏移未变化 -> 视口本来就没动，完全不动列表。
            try {
                int curOffset = rv.computeVerticalScrollOffset();
                if (Math.abs(curOffset - sAnchorPixelOffset) <= 5) {
                    LogWriter.log(TAG, "restoreAnchorRV: pixel offset preserved ("
                            + curOffset + "), skip scroll");
                    return;
                }
            } catch (Throwable ignored) {}

            int target = -1;
            if (sAnchorUsername != null) {
                // RecyclerView 无 AbsListView, 直接按 adapter 数据索引查(不含 header);
                // 不能调用 findPositionByUsername(null,...) —— 旧实现内部 lv.getAdapter() 会 NPE。
                target = findDataPositionByUsername(sAnchorUsername);
            }
            if (target < 0 && sAnchorRawPos >= 0) {
                target = sAnchorRawPos;
            }
            if (target < 0) {
                // 无任何锚点信息 -> 完全不动列表（绝不强制回顶）
                LogWriter.log(TAG, "restoreAnchorRV: no anchor, stay still");
                return;
            }
            int itemCount = rv.getAdapter() != null ? rv.getAdapter().getItemCount() : 0;
            if (target >= itemCount) target = Math.max(0, itemCount - 1);

            if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                // 已在目标位置且偏移在阈值内 -> 完全不动列表（消除“已就位仍滚动”造成的抖动）
                try {
                    androidx.recyclerview.widget.LinearLayoutManager llm =
                            (androidx.recyclerview.widget.LinearLayoutManager) lm;
                    int firstPos = llm.findFirstVisibleItemPosition();
                    View firstChild = llm.getChildCount() > 0 ? llm.getChildAt(0) : null;
                    int top = firstChild != null ? llm.getDecoratedTop(firstChild) : 0;
                    if (firstPos == target && Math.abs(top - sAnchorChildTop) <= 5) {
                        LogWriter.log(TAG, "restoreAnchorRV: already at target, skip scroll (pos=" + target + ")");
                        return;
                    }
                } catch (Throwable ignored) {}
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
                            char k = classifyUsername(s);
                            if (k == 'G') return 'G';
                            if (k == 'S' && fallback == 'X') fallback = 'S';
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
                            char k = classifyUsername(s);
                            if (k == 'G' || k == 'S') return s;
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
        // v3.0.530：切标签「保持视觉位置」，绝不强制回顶（强制 setSelectionFromTop(0,0)
        // 会把列表顶到边缘触发微信下拉「最近/小程序面板」，造成页面上下跳动）
        sResetToTop = false;
        sResetDone = false;
        sRestorePosted = false; // 新一代恢复开始，允许重新 post
        sFilterActive = true;
        final int gen = ++sFilterGeneration;

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
            if (gen != sFilterGeneration) return;
            sJustAppliedFilter = false;
            sResetToTop = false; // 保持 false：切标签后不强制回顶
        }, 3000);
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
    // AbsListView.computeVerticalScrollOffset 是 protected，跨类调用走反射
    private static int listViewScrollOffset(AbsListView lv) {
        try {
            java.lang.reflect.Method m = AbsListView.class.getMethod("computeVerticalScrollOffset");
            m.setAccessible(true);
            return (Integer) m.invoke(lv);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static void captureAnchor() {
        int oldRaw = sAnchorRawPos;
        sAnchorRawPos = -1;
        sAnchorChildTop = 0;
        sAnchorUsername = null;
        sAnchorPixelOffset = 0;
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
                    // 首个可见行可能是 header（取不到 username），向后扫描可见行找有效锚点
                    if (username == null) {
                        int base = lv.getFirstVisiblePosition();
                        for (int i = 0; i < lv.getChildCount(); i++) {
                            View c = lv.getChildAt(i);
                            if (c == null) continue;
                            int pos = base + i;
                            String u = usernameAtPosition(lv, pos);
                            if (u != null) {
                                username = u;
                                raw = pos;
                                childTop = c.getTop();
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            sAnchorRawPos = raw;
            sAnchorChildTop = childTop;
            sAnchorUsername = username;
            try {
                // v3.0.530：像素偏移作为「视口是否移动」的判据（比 first 索引稳定，
                // 过滤后索引重排但像素不变时视为未移动）
                sAnchorPixelOffset = listViewScrollOffset(lv);
            } catch (Throwable ignored) {}
            boolean attached = false;
            int convCount = -1;
            try { attached = v.isAttachedToWindow(); } catch (Throwable ignored) {}
            try { convCount = lv.getCount(); } catch (Throwable ignored) {}
            LogWriter.log(TAG, "captureAnchor raw=" + raw + " childTop=" + childTop
                + " user=" + username + " rule=" + sFilterRule + " (old=" + oldRaw + ")"
                + " pixelOffset=" + sAnchorPixelOffset
                + " attached=" + attached + " count=" + convCount);
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
            // 用 usernameOf（d.i1）而不是 idOf（仅群/服务号），普通好友也能锚定
            return usernameOf(item);
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

    /** 过滤后：按锚定 rawPos 恢复滚动位置（clamp 到新列表范围），保持视觉原位。
     *  v3.0.524: 微信会在过滤后 ~2s 再次重排列表（日志 17.095 处仍触发 getCount），
     *  旧 0/150ms 两次恢复 + 800ms 窗口不足以覆盖。
     *  v3.0.526/527: 六次 setSelection(0) + layoutChildren 中同步 setSelection 会把短列表
     *  （服务分类仅 5 条，内容短于视口）反复顶到边缘，触发微信「下拉小程序面板」。
     *  v3.0.530: 改为「保持视觉位置」——切标签/恢复全量均不强制回顶，仅当列表漂移到
     *  锚点下方或同位置偏移漂移时做单次恢复，且一帧只 post 一次；列表已在锚点位置时
     *  完全不调用 setSelection，从根上消除页面上下跳动与下拉面板误触。 */
    private static void restoreScrollLater() {
        postRestoreOnce(80);
    }

    /** 统一恢复入口（一帧一次）：所有触发源共用，消除多 post 竞态抖动。
     *  sRestorePosted 在同一帧内去重；执行体在下一帧运行，避免在布局期间同步 setSelection。 */
    private static void postRestoreOnce() {
        postRestoreOnce(0);
    }

    private static void postRestoreOnce(long delayMs) {
        if (sRestorePosted) return;
        sRestorePosted = true;
        final View v = sConvList;
        if (v == null) {
            sRestorePosted = false;
            return;
        }
        if (delayMs > 0) {
            v.postDelayed(() -> runRestore(v), delayMs);
        } else {
            v.post(() -> runRestore(v));
        }
    }

    private static void runRestore(View v) {
        sRestorePosted = false;
        try {
            if (v instanceof AbsListView) {
                restoreAnchorPosition((AbsListView) v);
            } else if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                restoreAnchorPositionRecyclerView((androidx.recyclerview.widget.RecyclerView) v);
            }
        } catch (Throwable ignored) {}
    }

    /** 依据锚定信息恢复 AbsListView 滚动位置（兼容 layoutChildren 回调）
     *  优先按 username 定位（跨过滤切换 position 会错位）；username 不在当前列表时保持
     *  raw 位置（clamp 到新列表范围），保证视觉位置不跳——绝不强制回顶。
     *  v3.0.524: 微信会在过滤后 ~2s 再次重排列表（日志 17.095 处仍触发 getCount）。
     *  v3.0.530: 锚点落在 header 区（原位置就是顶部）时完全不动列表；列表因新列表更短
     *  而自然 clamp（first < raw）时也不恢复，消除「已就位仍 setSelection」造成的抖动，
     *  更不会把短列表顶到边缘触发微信下拉「最近/小程序面板」。 */
    private static void restoreAnchorPosition(AbsListView lv) {
        if (lv == null) return;
        int count;
        try {
            count = lv.getCount();
        } catch (Throwable ignored) {
            count = 0;
        }
        if (count <= 0) return;

        // v3.0.530：像素偏移未变化 -> 视口本来就没动（过滤后索引重排但像素不变），
        // 完全不动列表。这是消除「切标签跳动」的关键判据。
        try {
            int curOffset = listViewScrollOffset(lv);
            if (Math.abs(curOffset - sAnchorPixelOffset) <= 5) {
                LogWriter.log(TAG, "restoreAnchor: pixel offset preserved ("
                        + curOffset + "), skip setSelection");
                return;
            }
        } catch (Throwable ignored) {}

        int headerCount = 0;
        try {
            Object adapter = lv.getAdapter();
            if (adapter != null) headerCount = headerCountOf(adapter);
        } catch (Throwable ignored) {}

        // 锚点落在 header 区（原位置就是顶部）且没有有效会话锚点 -> 保持顶部，绝不多余 setSelection
        if (sAnchorUsername == null && sAnchorRawPos >= 0 && sAnchorRawPos < headerCount) {
            try {
                int first = lv.getFirstVisiblePosition();
                boolean atTop = first <= 0;
                if (atTop && lv.getChildCount() > 0) {
                    View c0 = lv.getChildAt(0);
                    if (c0 != null && Math.abs(c0.getTop()) > 5) atTop = false;
                }
                if (atTop) {
                    LogWriter.log(TAG, "restoreAnchor: header anchor, already top, skip setSelection");
                    return;
                }
            } catch (Throwable ignored) {}
            try { lv.setSelection(0); } catch (Throwable ignored) {}
            return;
        }

        int target = -1;
        if (sAnchorUsername != null) {
            int pos = findPositionByUsername(lv, sAnchorUsername);
            if (pos >= 0) target = pos;
        }
        if (target < 0 && sAnchorRawPos >= 0) {
            // username 已不在当前列表（跨分类过滤）或为空：保持原始 raw 位置（clamp），视觉不跳
            target = sAnchorRawPos;
        }
        if (target < 0) {
            // 无任何锚点信息 -> 完全不动列表
            LogWriter.log(TAG, "restoreAnchor: no anchor, stay still (user=" + sAnchorUsername + ")");
            return;
        }
        if (target >= count) target = count - 1;
        if (target < 0) return;
        // 已在目标位置且偏移在阈值内 -> 完全不动列表（消除“已就位仍 setSelection”造成的抖动）
        try {
            int first = lv.getFirstVisiblePosition();
            if (first == target && lv.getChildCount() > 0) {
                View c0 = lv.getChildAt(0);
                if (c0 != null && Math.abs(c0.getTop() - sAnchorChildTop) <= 5) {
                    LogWriter.log(TAG, "restoreAnchor: already at target, skip setSelection (pos=" + target + ")");
                    return;
                }
            }
        } catch (Throwable ignored) {}
        try {
            lv.setSelectionFromTop(target, sAnchorChildTop);
            LogWriter.log(TAG, "restoreAnchor -> pos=" + target + " (raw=" + sAnchorRawPos
                + ") childTop=" + sAnchorChildTop + " user=" + sAnchorUsername);
        } catch (Throwable ignored) {}
    }

    /** 在当前列表（含过滤映射）中查找会话标识对应的 raw position；找不到返回 -1
     *  过滤激活时返回 headerCount + 过滤索引；否则返回 headerCount + 底层索引 */
    private static int findPositionByUsername(AbsListView lv, String username) {
        int dataIdx = findDataPositionByUsername(username);
        if (dataIdx < 0) return -1;
        int headerCount = 0;
        try {
            // lv 可能为 null(RecyclerView 分支), 此时退回 sAdapter 计算 header 数
            Object adapter = lv != null ? lv.getAdapter() : sAdapter;
            if (adapter != null) headerCount = headerCountOf(adapter);
        } catch (Throwable ignored) {}
        return headerCount + dataIdx;
    }

    /** 在当前(过滤后)列表数据中查找 username 对应的数据索引(不含 header)；找不到返回 -1。
     *  与 lv 解耦, 供 ListView / RecyclerView 两条路径共用。 */
    private static int findDataPositionByUsername(String username) {
        if (username == null) return -1;
        try {
            if (sFilterActive && sFilteredPositions != null) {
                for (int i = 0; i < sFilteredPositions.size(); i++) {
                    Object item = itemAt(sFilteredPositions.get(i));
                    if (item == null) continue;
                    try {
                        Object u = usernameOf(item);
                        if (u instanceof String && username.equals(u)) return i;
                    } catch (Throwable ignored) {}
                }
            } else {
                java.util.List<?> dataList = dataListOf();
                if (dataList == null) return -1;
                for (int i = 0; i < dataList.size(); i++) {
                    Object item = dataList.get(i);
                    if (item == null) continue;
                    try {
                        Object u = usernameOf(item);
                        if (u instanceof String && username.equals(u)) return i;
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
        // v3.0.530：恢复全量同样保持视觉位置（锚点恢复），不强制回顶
        sResetToTop = false;
        sResetDone = false;
        sRestorePosted = false; // 新一代恢复开始，允许重新 post
        final int gen = ++sFilterGeneration;

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
            if (gen != sFilterGeneration) return;
            sJustAppliedFilter = false;
            sResetToTop = false; // 保持 false：恢复全量后不强制回顶
        }, 3000);
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

    /** v3.0.488: 强制刷新会话列表 adapter（系统消息落库后调用，让会话列表显示新消息/未读）。 */
    public static void refreshIfInstalled() {
        try {
            Object ad = sRecyclerAdapter != null ? sRecyclerAdapter : sAdapter;
            if (ad == null) {
                LogWriter.log(TAG, "refreshIfInstalled: adapter null");
                return;
            }
            XposedHelpers.callMethod(ad, "notifyDataSetChanged");
            LogWriter.log(TAG, "refreshIfInstalled: notified " + ad.getClass().getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "refreshIfInstalled err: " + t.getMessage());
        }
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

                switch (classifyUsername(username)) {
                    case 'G':
                        groupUnread = unread > 0 ? groupUnread + 1 : groupUnread;
                        break;
                    case 'S':
                        serviceUnread = unread > 0 ? serviceUnread + 1 : serviceUnread;
                        break;
                    case 'F':
                        friendUnread = unread > 0 ? friendUnread + 1 : friendUnread;
                        break;
                    default:
                        break;
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
                    kind = classifyUsername(username);
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
                char kind = classifyUsername(username);
                boolean match = ("group".equals(rule) && kind == 'G')
                        || ("service".equals(rule) && kind == 'S')
                        || ("friend".equals(rule) && kind == 'F');
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