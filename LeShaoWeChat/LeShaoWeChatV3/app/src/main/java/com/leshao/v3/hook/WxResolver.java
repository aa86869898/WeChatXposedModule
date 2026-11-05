package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XposedHelpers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信类动态解析中枢（v3.0.460 起，替代 hooks 中的全部硬编码 findClass("com.tencent.mm.*")）。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>功能键驱动</b>：hooks 只传语义键（如 {@code "msg_info"}、{@code "auto_play_scheduler"}），
 *       不出现任何微信类名字面量；</li>
 *   <li><b>DexKit 锚点优先</b>：每个键绑定一个或多个「稳定锚点」（字符串/方法签名/接口名），
 *       在微信进程内用 DexKit 动态定位，混淆类名每版漂移均不受影响；</li>
 *   <li><b>回退仅集中在注册表</b>：兜底类名只允许出现在本类的注册表常量中，不允许散落在 hooks 里。</li>
 * </ul>
 */
public final class WxResolver {

    private static final String TAG = "WxResolver";

    /** 解析器：给定类加载器，返回微信类；无法定位返回 null。 */
    public interface Resolver {
        Class<?> resolve(ClassLoader cl);
    }

    private static final Map<String, Resolver> REGISTRY = new ConcurrentHashMap<>();

    private WxResolver() {}

    /** 注册功能键到解析器（模块启动/首次使用时调用一次）。 */
    public static void register(String key, Resolver resolver) {
        if (key != null && resolver != null) {
            REGISTRY.put(key, resolver);
        }
    }

    /** 按功能键取类；未注册或解析失败返回 null。 */
    public static Class<?> get(String key, ClassLoader cl) {
        Resolver r = REGISTRY.get(key);
        if (r == null) {
            LogWriter.log(TAG, "get(" + key + "): not registered");
            return null;
        }
        try {
            Class<?> c = r.resolve(cl);
            if (c != null) {
                LogWriter.log(TAG, "get(" + key + ") -> " + c.getName());
            }
            return c;
        } catch (Throwable t) {
            LogWriter.log(TAG, "get(" + key + ") failed: " + t);
            return null;
        }
    }

    /** 便捷：静态方法调用（反射），失败返回 null。 */
    public static Object callStatic(String key, ClassLoader cl, String method, Object... args) {
        Class<?> c = get(key, cl);
        if (c == null) return null;
        try {
            return XposedHelpers.callStaticMethod(c, method, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "callStatic(" + key + "." + method + ") failed: " + t);
            return null;
        }
    }

    /**
     * 便捷：单锚点字符串解析器（最常用）。
     * 优先用 DexKit 在进程内查找「方法体引用该字符串」的类；找不到时可用兜底类名。
     */
    public static Resolver byString(final String anchor, final String... fallbacks) {
        return cl -> {
            // 版本分流：8.0.78 及更早（455 旧字符串目标版本）直接走旧类名兜底，跳过 8.0.79 新锚点。
            if (WeChatVersion.isOld()) {
                return findFallback(cl, fallbacks);
            }
            if (anchor != null && !anchor.isEmpty()) {
                try {
                    java.util.List<String> names = DexKitHelper.findMethodDeclClassByString(cl, anchor);
                    if (names != null && !names.isEmpty()) {
                        Class<?> matched = preferFallback(cl, names, fallbacks);
                        if (matched != null) return matched;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    java.util.List<String> classes = DexKitHelper.findClassesByString(cl, anchor);
                    if (classes != null && !classes.isEmpty()) {
                        Class<?> matched = preferFallback(cl, classes, fallbacks);
                        if (matched != null) return matched;
                    }
                } catch (Throwable ignored) {
                }
            }
            return findFallback(cl, fallbacks);
        };
    }

    /** 在候选类名中优先选择与兜底名精确/后缀匹配的类，否则取第一个非空候选。 */
    private static Class<?> preferFallback(ClassLoader cl, java.util.List<String> names, String[] fallbacks) {
        if (names == null || names.isEmpty()) return null;
        if (fallbacks != null) {
            for (String fb : fallbacks) {
                if (fb == null || fb.isEmpty()) continue;
                for (String n : names) {
                    if (n == null) continue;
                    if (fb.equals(n) || n.endsWith("." + fb) || n.replace('$', '.').equals(fb.replace('$', '.'))) {
                        try {
                            return XposedHelpers.findClass(n, cl);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        for (String n : names) {
            if (n == null || n.isEmpty()) continue;
            try {
                return XposedHelpers.findClass(n, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 多锚点字符串解析器：要求单个方法同时引用全部锚点，唯一性更高。 */
    public static Resolver byStrings(final String[] anchors, final String... fallbacks) {
        return cl -> {
            // 版本分流：旧版微信直接走旧类名兜底。
            if (WeChatVersion.isOld()) {
                return findFallback(cl, fallbacks);
            }
            if (anchors != null && anchors.length > 0) {
                try {
                    org.luckypray.dexkit.query.FindClass q = org.luckypray.dexkit.query.FindClass.create()
                            .excludePackages("com.leshao")
                            .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                    .addMethod(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .usingStrings(java.util.Arrays.asList(anchors))));
                    final org.luckypray.dexkit.result.ClassDataList[] holder = new org.luckypray.dexkit.result.ClassDataList[1];
                    DexKitHelper.withWechatBridge(cl, bridge -> {
                        holder[0] = bridge.findClass(q);
                        return null;
                    });
                    if (holder[0] != null && !holder[0].isEmpty()) {
                        return XposedHelpers.findClass(holder[0].get(0).getName(), cl);
                    }
                } catch (Throwable ignored) {
                }
            }
            return findFallback(cl, fallbacks);
        };
    }

    /** 按方法名定位类：方法名在该版本中出现的声明类集合，优先命中包含兜底类名的候选。 */
    public static Resolver byMethod(final String methodName, final String... fallbacks) {
        return cl -> {
            // 版本分流：旧版微信直接走旧类名兜底。
            if (WeChatVersion.isOld()) {
                return findFallback(cl, fallbacks);
            }
            if (methodName != null && !methodName.isEmpty()) {
                try {
                    java.util.List<String> names = DexKitHelper.findMethodDeclClassByString(cl, methodName);
                    if (names != null && !names.isEmpty()) {
                        if (fallbacks != null && fallbacks.length > 0) {
                            for (String fb : fallbacks) {
                                if (fb == null || fb.isEmpty()) continue;
                                for (String n : names) {
                                    if (fb.equals(n) || n.endsWith("." + fb)) {
                                        return XposedHelpers.findClass(n, cl);
                                    }
                                }
                            }
                        }
                        return XposedHelpers.findClass(names.get(0), cl);
                    }
                } catch (Throwable ignored) {
                }
            }
            return findFallback(cl, fallbacks);
        };
    }

    /** 直接按类名解析（仅允许用于版本内稳定存在的类；类名集中在本注册表）。 */
    public static Resolver byName(final String... names) {
        return cl -> findFallback(cl, names);
    }

    /** 按未混淆类名子串动态定位（如 "ImproveDataUIC"、"JsApiSetClipboardDataWC"）。
     *  优先返回完全等于 fallbacks 中任一类的类名；否则跳过 $ 内部/合成类后取第一个，防止
     *  DexKit 命中 {类}$synthetic$1 之类内部类（如 ImproveDataUIC$commentUpdateListener$1 导致 M7 找不到）。 */
    public static Resolver byClassNameContains(final String substring, final String... fallbacks) {
        return cl -> {
            // 版本分流：旧版微信直接走旧类名兜底。
            if (WeChatVersion.isOld()) {
                return findFallback(cl, fallbacks);
            }
            if (substring != null && !substring.isEmpty()) {
                try {
                    org.luckypray.dexkit.query.FindClass q = org.luckypray.dexkit.query.FindClass.create()
                            .excludePackages("com.leshao")
                            .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                    .className(substring, org.luckypray.dexkit.query.enums.StringMatchType.Contains, false));
                    final org.luckypray.dexkit.result.ClassDataList[] holder = new org.luckypray.dexkit.result.ClassDataList[1];
                    DexKitHelper.withWechatBridge(cl, bridge -> {
                        holder[0] = bridge.findClass(q);
                        return null;
                    });
                    if (holder[0] != null && !holder[0].isEmpty()) {
                        String chosen = null;
                        // ① 完全等于任一 fallback（权威类名）
                        if (fallbacks != null) {
                            for (String fb : fallbacks) {
                                for (int i = 0; i < holder[0].size(); i++) {
                                    String n = holder[0].get(i).getName();
                                    if (fb != null && fb.equals(n)) { chosen = n; break; }
                                }
                                if (chosen != null) break;
                            }
                        }
                        // ② 非 $ 内部类中的第一个
                        if (chosen == null) {
                            for (int i = 0; i < holder[0].size(); i++) {
                                String n = holder[0].get(i).getName();
                                if (n != null && !n.contains("$")) { chosen = n; break; }
                            }
                        }
                        if (chosen == null) chosen = holder[0].get(0).getName();
                        return XposedHelpers.findClass(chosen, cl);
                    }
                } catch (Throwable ignored) {
                }
            }
            return findFallback(cl, fallbacks);
        };
    }

    /** 委托给另一功能键的解析器（用于同类异名/兼容映射）。 */
    public static Resolver alias(final String targetKey) {
        return cl -> get(targetKey, cl);
    }

    /** 便捷：先走已注册的 DexKitHelper getter（旧版功能键），再走锚点。 */
    public static Resolver fromDexKit(final String getterKey, final String anchor, final String... fallbacks) {
        return cl -> {
            Class<?> c = DexKitHelperClassBridge.resolveGetter(getterKey, cl);
            if (c != null) return c;
            return byString(anchor, fallbacks).resolve(cl);
        };
    }

    private static Class<?> findFallback(ClassLoader cl, String[] fallbacks) {
        if (fallbacks != null) {
            for (String fb : fallbacks) {
                if (fb == null || fb.isEmpty()) continue;
                try {
                    Class<?> c = XposedHelpers.findClass(fb, cl);
                    LogWriter.log(TAG, "findFallback " + fb + (WeChatVersion.isOld() ? " [OLD_VER]" : "") + " -> " + c.getName());
                    return c;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 统一入口：按类名（通常是历史硬编码字面量）解析。
     * 若该类名有注册的 DexKit 锚点解析器，则走动态解析；否则直接加载。
     * hooks 中所有 loadClass/findClass 字面量均应改经此入口，保证锚点优先、字面量仅作回退。
     */
    /** 已记录过 fallback 失败的类名（每个类名只记一次，避免旧版本类名反复刷屏）。 */
    private static final java.util.Set<String> sLoggedFallbackFailures =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static Class<?> resolve(ClassLoader cl, String className) {
        if (cl == null || className == null) return null;
        if (className.startsWith("com.tencent.mm.")) {
            String key = className.substring("com.tencent.mm.".length()).replace('.', '_');
            Resolver r = REGISTRY.get(key);
            if (r != null) {
                try {
                    Class<?> c = r.resolve(cl);
                    if (c != null) return c;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return XposedHelpers.findClass(className, cl);
        } catch (Throwable t) {
            if (sLoggedFallbackFailures.add(className)) {
                LogWriter.log(TAG, "resolve(" + className + ") fallback failed: " + t.getMessage());
            }
            return null;
        }
    }

    /** 包内辅助：把 DexKitHelper 的 String getter 转成 Class。 */
    private static final class DexKitHelperClassBridge {
        private DexKitHelperClassBridge() {}

        static Class<?> resolveGetter(String key, ClassLoader cl) {
            String name = null;
            switch (key) {
                case "j1": name = DexKitHelper.getJ1ServiceClass(); break;
                case "voice_api": name = DexKitHelper.getVoiceApiClass(); break;
                case "e9": name = DexKitHelper.getE9ClassName(); break;
                case "a21": name = DexKitHelper.getA21ClassName(); break;
                case "avatar": name = DexKitHelper.getAvatarHelperClass(); break;
                case "label_storage": name = DexKitHelper.getLabelStorageClass(); break;
                case "conv_adapter": name = DexKitHelper.getConvListListAdapterClass(); break;
                case "p06": name = DexKitHelper.getP06ClassName(); break;
                case "db_opener": name = DexKitHelper.getDbOpenerClass(); break;
                case "imei": name = DexKitHelper.getImeiClassName(); break;
                case "cso_loader": name = DexKitHelper.getCsoLoaderClass(); break;
                case "contact_storage": name = DexKitHelper.getContactStorageClass(); break;
                case "chat_open": name = DexKitHelper.getChatOpenClass(); break;
                case "conv_scroll": name = DexKitHelper.getConvScrollClass(); break;
                case "conv_longpress": name = DexKitHelper.getConvLongPressClass(); break;
                case "conv_menu": name = DexKitHelper.getConvMenuClass(); break;
                case "action_bar": name = DexKitHelper.getActionBarCustomAreaClass(); break;
                case "label_provider": name = DexKitHelper.getLabelStorageProviderClass(); break;
                case "pinyin_util": name = DexKitHelper.getPinyinUtilClass(); break;
                case "service_locator": name = DexKitHelper.getServiceLocatorClass(); break;
                case "media_path": name = DexKitHelper.getMediaPathServiceClass(); break;
                case "clipboard_jsapi": name = DexKitHelper.getClipboardJsApiClass(); break;
                default: break;
            }
            if (name == null || name.isEmpty()) return null;
            try {
                return XposedHelpers.findClass(name, cl);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}