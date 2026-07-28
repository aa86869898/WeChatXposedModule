import com.tencent.mm.ui.chatting.viewitems.dq
import com.tencent.mm.ui.chatting.component.so
import com.tencent.mm.ui.chatting.v0
import com.tencent.mm.storage.e9

// ============================================================
// 微信语音消息自动播放插件
// 收到语音气泡消息时自动播放，无需手动点击
// ============================================================

// 追踪最后播放的消息ID，避免重复播放
lastPlayedMsgId = 0L

log("[VoiceAutoPlay] 插件已加载，等待语音消息...")

/**
 * Hook ChattingItemVoice.c(View, fd5.d, e9)
 * —— 语音气泡视图绑定方法（每次语音消息出现在屏幕上时触发）
 *
 * 检测到新语音消息后自动调用 SceneVoicePlayer.I() 播放
 */
hookMethodAfter(dq, "c", param -> {
    try {
        // args: [View, fd5.d (ChattingContext), e9 (消息对象)]
        if (param.args.length < 3) return

        msg = (e9) param.args[2]
        if (msg == null) return

        chattingContext = param.args[1]  // fd5.d

        // === 1. 过滤：只处理语音消息 (type == 34) ===
        if (msg.getType() != 34) return

        // === 2. 过滤：不处理自己发送的消息 ===
        // dm.c8.field_isSend: 1=自己发的, 0=收到的
        try {
            isSend = getIntField(msg, "field_isSend")
            if (isSend == 1) return
        } catch (e) {
            // 字段获取失败则继续（兼容不同微信版本）
        }

        // === 3. 去重：避免重复播放同一消息 ===
        msgId = msg.getMsgId()
        if (msgId == lastPlayedMsgId) return

        // === 4. 检查消息是否正在发送中 (M0() == 5) ===
        if (msg.M0() == 5) return

        // === 5. 获取 VoiceComponent (com.tencent.mm.ui.chatting.component.so) ===
        // 通过 ChattingContext.c.a(接口类) 获取
        manager = getObjectField(chattingContext, "c")
        if (manager == null) return

        q2Class = findClassOrNull("zc5.q2", hostLoader)
        if (q2Class == null) {
            log("[VoiceAutoPlay] 未找到 zc5.q2")
            return
        }

        voiceComp = callMethod(manager, "a", q2Class)
        if (voiceComp == null) {
            log("[VoiceAutoPlay] 未获取到 VoiceComponent")
            return
        }

        soComp = (so) voiceComp

        // === 6. 获取 SceneVoicePlayer (com.tencent.mm.ui.chatting.v0) ===
        player = soComp.n0()
        if (player == null) {
            log("[VoiceAutoPlay] 未获取到 SceneVoicePlayer")
            return
        }

        // === 7. 避免打断当前播放 ===
        if (player.o()) {
            return  // 正在播放中，不打断
        }

        // === 8. 自动播放 ===
        lastPlayedMsgId = msgId
        player.I(msg, false)
        log("[VoiceAutoPlay] ✅ 自动播放语音 msgId=$msgId")

    } catch (e) {
        log("[VoiceAutoPlay] ❌ 异常: $e")
    }
})
