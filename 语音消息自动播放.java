/*
 * 语音消息自动播放
 * 好友发来语音消息时自动点击播放，无需手动点击
 * 
 * 原理：
 * 微信语音播放由 VoiceComponent (so) 统一管理。
 * 进入聊天界面时 VoiceComponent.y() 初始化播放器 v0，
 * 语音气泡由 h5.m() 渲染并绑定点击事件到 v0。
 * 
 * 本模块分三层：
 *   第1层：Hook VoiceComponent.y() → 播放器就绪后自动触发
 *   第2层：Hook h5.m() → 检测新语音气泡，自动模拟点击
 *   第3层：直接操作 v0 播放器 → 最底层兜底
 * 
 * 支持配置：
 *   - 仅播放收到的语音（跳过自己发的）
 *   - 已播放过的自动跳过
 *   - 公众号消息自动跳过
 *   - 可设置是否自动连播下一条
 */

// ============ 配置 ============
autoPlayEnabled = true          // 总开关
onlyReceived = true              // 仅播放收到的语音（不播自己发的）
skipPlayed = true                // 跳过已播放的
skipBrand = true                 // 跳过公众号
autoContinueNext = false         // 播放完后自动播放下一条
playDelay = 600                  // 延迟毫秒（等待UI渲染）

// 已播放记录（防止重复播放）
playedMsgIds = new java.util.HashSet()

// ============ 第1层：Hook VoiceComponent (so) ============
// so.y() = resetAutoPlay，在进入聊天时初始化播放器
// so.E() = 组件初始化，设置消息观察者

voiceCompClass = findClass()
    .pkg("com.tencent.mm.ui.chatting.component")
    .usingStrings("MicroMsg.ChattingUI.VoiceComponent", "MicroMsg.AutoPlay")
    .single()

if (voiceCompClass != null) {
    
    // Hook y() = resetAutoPlay 之后 → 播放器已就绪
    hookAllMethodsAfter(voiceCompClass, "y", param -> {
        try {
            if (!autoPlayEnabled) return
            
            comp = param.thisObject
            if (comp == null) return
            
            // 获取聊天对象 username
            chatCtx = getObjectField(comp, "d")  // fd5.d
            if (chatCtx == null) return
            username = callMethod(chatCtx, "x")
            if (username == null) return
            
            // 跳过公众号
            if (skipBrand) {
                try {
                    y3Class = findClass("com.tencent.mm.storage.y3")
                    isBrand = callStaticMethod(y3Class, "H4", username)
                    if (isBrand != null && isBrand) return
                } catch (e) {}
            }
            
            log("[AutoVoice] 进入聊天: $username，准备自动播放")
            
            // 延迟等待语音气泡渲染完成
            mainHandler = new android.os.Handler(hostContext.getMainLooper())
            mainHandler.postDelayed(() -> {
                try {
                    autoPlayLatestVoice(comp, username)
                } catch (e) {
                    log("[AutoVoice] 自动播放异常: $e")
                }
            }, playDelay)
            
        } catch (e) {
            log("[AutoVoice] y() Hook 错误: $e")
        }
    })
    
    // Hook l0(e9) = 语音消息点击处理
    // 拦截以记录播放状态
    hookAllMethodsBefore(voiceCompClass, "l0", param -> {
        try {
            msg = param.args[0]  // e9
            if (msg != null) {
                msgId = callMethod(msg, "getMsgId")
                playedMsgIds.add(String.valueOf(msgId))
            }
        } catch (e) {}
    })
    
    log("[AutoVoice] 第1层 VoiceComponent Hook 已挂载")
}

// ============ 第2层：Hook 语音气泡渲染 (h5.m) ============
// h5.m(g0, d, ye5.d, String) → 语音气泡绑定到列表

viewitems_h5 = findClass("com.tencent.mm.ui.chatting.viewitems.h5")

if (viewitems_h5 != null) {
    hookAllMethodsAfter(viewitems_h5, "m", param -> {
        try {
            if (!autoPlayEnabled) return
            
            // param.args[2] = ye5.d (adapter)
            adapter = param.args[2]
            if (adapter == null) return
            
            // adapter.d = 消息容器, adapter.d.b = e9 (消息)
            msgContainer = getObjectField(adapter, "d")
            if (msgContainer == null) return
            msg = getObjectField(msgContainer, "b")
            if (msg == null) return
            
            // 检查是否为语音消息
            isVoice = callMethod(msg, "d3")
            if (isVoice == null || !isVoice) return
            
            // 获取发送者
            sender = callMethod(msg, "N0")
            
            // 获取自己的 username
            myUsername = null
            try {
                kernelH = findClass("com.tencent.mm.kernel.h")
                myUsername = callStaticMethod(kernelH, "x")
            } catch (e) {}
            
            // 跳过自己发的
            if (onlyReceived && myUsername != null && sender != null 
                && sender.equals(myUsername)) {
                return
            }
            
            // 获取消息 ID
            msgId = callMethod(msg, "getMsgId")
            msgIdStr = String.valueOf(msgId)
            
            // 跳过已播放的
            if (skipPlayed && playedMsgIds.contains(msgIdStr)) {
                return
            }
            
            // 获取 holder (g5)，模拟点击 clickArea
            holder = param.args[0]  // g5
            if (holder == null) return
            
            clickArea = getObjectField(holder, "clickArea")
            if (clickArea == null) return
            
            // 标记为新语音，等待自动播放
            log("[AutoVoice] 发现新语音消息: msgId=$msgIdStr, sender=$sender")
            
            // 将 msgId 和 clickArea 保存到附加字段
            setAdditionalField(comp, "pending_voice_msgId", msgIdStr)
            setAdditionalField(comp, "pending_voice_clickArea", clickArea)
            
        } catch (e) {
            log("[AutoVoice] h5.m Hook 错误: $e")
        }
    })
    
    log("[AutoVoice] 第2层 语音气泡 Hook 已挂载")
}

// ============ 第3层：直接操作 v0 播放器 ============

playerClass = findClass("com.tencent.mm.ui.chatting.viewitems.v0")

if (playerClass != null) {
    // Hook v0.t() = 播放方法，记录日志
    hookAllMethodsBefore(playerClass, "t", param -> {
        try {
            player = param.thisObject
            msgId = getLongField(player, "i")
            log("[AutoVoice] 播放器开始播放: msgId=$msgId")
        } catch (e) {}
    })
    
    // Hook v0.onClick = 点击事件
    hookAllMethodsBefore(playerClass, "onClick", param -> {
        try {
            player = param.thisObject
            msgId = getLongField(player, "i")
            log("[AutoVoice] 语音气泡被点击: msgId=$msgId")
            playedMsgIds.add(String.valueOf(msgId))
        } catch (e) {}
    })
    
    log("[AutoVoice] 第3层 播放器 Hook 已挂载")
}

// ============ 核心：自动播放最新语音 ============

autoPlayLatestVoice(comp, username) {
    try {
        // 方法A：通过 pending 保存的 clickArea 触发点击
        clickArea = getAdditionalField(comp, "pending_voice_clickArea")
        msgIdStr = getAdditionalField(comp, "pending_voice_msgId")
        
        if (clickArea != null && msgIdStr != null) {
            if (!playedMsgIds.contains(msgIdStr)) {
                log("[AutoVoice] 方法A：模拟点击语音气泡 -> $msgIdStr")
                clickArea.performClick()
                playedMsgIds.add(msgIdStr)
                
                // 清理附加字段
                setAdditionalField(comp, "pending_voice_clickArea", null)
                setAdditionalField(comp, "pending_voice_msgId", null)
                return
            }
        }
        
        // 方法B：通过播放器直接调用 t()
        player = callMethod(comp, "n0")
        if (player == null) return
        
        isPlaying = callMethod(player, "o")
        if (isPlaying) return  // 已在播放
        
        // 检查是否有待播放的语音
        isAlive = callMethod(player, "m")
        if (isAlive == null || !isAlive) return
        
        // 尝试直接触发播放
        currentMsgId = getLongField(player, "i")
        if (currentMsgId > 0 && !playedMsgIds.contains(String.valueOf(currentMsgId))) {
            log("[AutoVoice] 方法B：直接调用播放器 -> $currentMsgId")
            callMethod(player, "t")
            playedMsgIds.add(String.valueOf(currentMsgId))
        }
        
    } catch (e) {
        log("[AutoVoice] autoPlayLatestVoice 错误: $e")
    }
}

// ============ 辅助：连播下一条 ============

// 注册播放结束监听（通过 AudioPlayerEvent）
audioEventClass = findClass()
    .pkg("com.tencent.mm.autogen.events")
    .usingStrings("AudioPlayerEvent")
    .single()

if (audioEventClass != null && autoContinueNext) {
    hookAllMethodsAfter(audioEventClass, "e", param -> {
        try {
            if (!autoPlayEnabled || !autoContinueNext) return
            
            // 播放完成后查找下一条未读语音
            log("[AutoVoice] 语音播放完成，查找下一条...")
            // 延迟后触发下一轮自动播放
            mainHandler = new android.os.Handler(hostContext.getMainLooper())
            mainHandler.postDelayed(() -> {
                // 重新触发扫描
                playedMsgIds.clear()  // 清除记录，允许重新扫描
            }, 500)
        } catch (e) {}
    })
    log("[AutoVoice] 连播模式已启用")
}

// ============ 配置方法（供悬浮球调用）============

/*
 * 切换自动播放开关
 */
toggleAutoPlay() {
    autoPlayEnabled = !autoPlayEnabled
    status = autoPlayEnabled ? "已开启" : "已关闭"
    log("[AutoVoice] 自动播放 $status")
    toast("语音自动播放 $status")
}

/*
 * 设置播放延迟（毫秒）
 */
setPlayDelay(ms) {
    playDelay = ms
    log("[AutoVoice] 播放延迟已设置为: ${ms}ms")
}

log("[AutoVoice] 语音消息自动播放全部就绪")
log("[AutoVoice] 配置: 仅接收=$onlyReceived 跳过已播放=$skipPlayed 跳过公众号=$skipBrand")
toast("语音自动播放已就绪")
