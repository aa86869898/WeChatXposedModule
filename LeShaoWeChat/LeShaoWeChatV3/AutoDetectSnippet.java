/**
 * 全自动微信R8混淆方法识别 — 代码片段
 * 直接粘贴进 DexKitCacheBridge.waitKernelInit() 回调内部使用
 *
 * 使用步骤：
 * 1. 替换 YOUR_FEATURE_STRING 为实际特征字符串
 * 2. 替换 MethodMatcher 参数（paramCount / paramTypes / returnType / modifiers）
 * 3. 替换 REAL_BUSINESS_HOOK 为实际业务 XC_MethodHook
 * 4. 替换 FEATURE_ENABLED  为你的功能启用标志变量
 * 5. 替换 TAG  为你的日志标签
 * 6. 外部上层代码触发微信业务后，本段自动识别目标Method并安装正式hook
 *
 * 禁止写死任何微信混淆类名/方法名，全自动适配。
 */

// ==================== 0. 主进程检查 ====================
if (lpparam == null || !lpparam.packageName.equals(lpparam.processName)) {
    // 子进程直接跳过
    return;
}

// ==================== 1. 构建 MethodMatcher 多条件组合 ====================
// 注意：usingStrings 内的特征字符串由你手动替换
MethodMatcher matcher = MethodMatcher.create()
    .usingStrings("YOUR_FEATURE_STRING") // TODO: 替换为实际特征字符串
    .paramCount(2)                        // TODO: 替换为实际参数数量
    .paramTypes("java.lang.String", "int") // TODO: 替换为实际参数类型列表
    .returnType("void")                   // TODO: 替换为实际返回类型，不关心可去掉此行
    .modifiers(java.lang.reflect.Modifier.PUBLIC); // TODO: 替换为实际修饰符，不关心可去掉此行

// ==================== 2. 收集候选 MethodResult 集合 ====================
java.util.List<MethodData> candidates = bridge.findMethod(
    FindMethod.create().matcher(matcher)
);

if (candidates == null || candidates.isEmpty()) {
    LogWriter.log(TAG, "autoDetect: 候选方法为空，功能禁用");
    FEATURE_ENABLED = false; // TODO: 替换为你的功能启用标志
    return;
}
LogWriter.log(TAG, "autoDetect: 找到 " + candidates.size() + " 个候选方法");

// ==================== 3. 获取运行时 Method 对象，过滤 null ====================
final java.util.List<java.lang.reflect.Method> runtimeMethods = new java.util.ArrayList<>();
for (MethodData md : candidates) {
    if (md == null) continue;
    try {
        java.lang.reflect.Method m = md.getMethodInstance(lpparam.classLoader);
        if (m != null) {
            runtimeMethods.add(m);
            LogWriter.log(TAG, "autoDetect: 候选 -> " + md.getClassName() + "." + md.getName()
                + "(" + (md.getParamTypeNames() != null ? md.getParamTypeNames().toString() : "") + ")");
        }
    } catch (Throwable e) {
        LogWriter.log(TAG, "autoDetect: getMethodInstance 失败 " + md.getClassName() + "." + md.getName()
            + " : " + e.getMessage());
    }
}

if (runtimeMethods.isEmpty()) {
    LogWriter.log(TAG, "autoDetect: 全部 getMethodInstance 返回 null，功能禁用");
    FEATURE_ENABLED = false; // TODO: 替换为你的功能启用标志
    return;
}
LogWriter.log(TAG, "autoDetect: " + runtimeMethods.size() + " 个运行时Method就绪");

// ==================== 4. 批量临时 hook 全部候选 + 标记变量 ====================
// 标记变量：记录实际被调用的目标Method
final java.util.concurrent.atomic.AtomicReference<java.lang.reflect.Method> identifiedRef =
    new java.util.concurrent.atomic.AtomicReference<>(null);
// Unhook 对象集合，用于后续解除全部临时hook
final java.util.List<XC_MethodHook.Unhook> tempHooks = new java.util.ArrayList<>();
// 防止重复处理的原子标志
final java.util.concurrent.atomic.AtomicBoolean detectionDone =
    new java.util.concurrent.atomic.AtomicBoolean(false);

for (java.lang.reflect.Method m : runtimeMethods) {
    try {
        XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // 仅首次命中时记录目标Method
                if (identifiedRef.compareAndSet(null, param.method)) {
                    LogWriter.log(TAG, "autoDetect: 命中目标 -> "
                        + param.method.getDeclaringClass().getName() + "." + param.method.getName()
                        + "(" + java.util.Arrays.toString(param.args) + ")");
                }
            }
        });
        tempHooks.add(unhook);
    } catch (Throwable e) {
        LogWriter.log(TAG, "autoDetect: hook失败 " + m.getDeclaringClass().getName() + "." + m.getName()
            + " : " + e.getMessage());
    }
}

if (tempHooks.isEmpty()) {
    LogWriter.log(TAG, "autoDetect: 全部临时hook安装失败，功能禁用");
    FEATURE_ENABLED = false; // TODO: 替换为你的功能启用标志
    return;
}
LogWriter.log(TAG, "autoDetect: " + tempHooks.size() + " 个临时hook已安装，等待外部业务触发...");

// ==================== 5. 超时兜底 + 识别后清理 ====================
final long timeoutMs = 15000L; // 15秒超时，可自行调整
final long startTime = System.currentTimeMillis();

new Thread(new Runnable() {
    @Override
    public void run() {
        // 轮询等待识别结果，最多等 timeoutMs 毫秒
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (identifiedRef.get() != null || detectionDone.get()) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }

        java.lang.reflect.Method target = identifiedRef.get();

        if (target != null && !detectionDone.getAndSet(true)) {
            // ==================== 识别成功 ====================
            LogWriter.log(TAG, "autoDetect: 识别成功 -> "
                + target.getDeclaringClass().getName() + "." + target.getName());

            // 遍历移除全部临时hook
            int removed = 0;
            for (XC_MethodHook.Unhook u : tempHooks) {
                try {
                    u.unhook();
                    removed++;
                } catch (Throwable ignored) {
                }
            }
            tempHooks.clear();
            LogWriter.log(TAG, "autoDetect: 已移除 " + removed + " 个临时hook");

            // 只保留正式业务Xposed hook
            try {
                XposedBridge.hookMethod(target, REAL_BUSINESS_HOOK); // TODO: 替换为你的业务hook
                LogWriter.log(TAG, "autoDetect: 正式业务hook已安装 -> "
                    + target.getDeclaringClass().getName() + "." + target.getName());
            } catch (Throwable e) {
                LogWriter.log(TAG, "autoDetect: 正式hook安装失败: " + e.getMessage());
                FEATURE_ENABLED = false; // TODO: 替换为你的功能启用标志
            }
        } else if (!detectionDone.getAndSet(true)) {
            // ==================== 识别超时 ====================
            LogWriter.log(TAG, "autoDetect: 识别超时(" + timeoutMs + "ms)，功能禁用");

            // 清理全部临时hook
            int removed = 0;
            for (XC_MethodHook.Unhook u : tempHooks) {
                try {
                    u.unhook();
                    removed++;
                } catch (Throwable ignored) {
                }
            }
            tempHooks.clear();
            LogWriter.log(TAG, "autoDetect: 已清理 " + removed + " 个临时hook");

            FEATURE_ENABLED = false; // TODO: 替换为你的功能启用标志
        }
    }
}, "autoDetect-timeout").start();