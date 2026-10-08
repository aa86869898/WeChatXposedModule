package com.leshao.v3.hook

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object EventBus {

    enum class Event {
        LABEL_CREATED,
        LABEL_DELETED,
        LABEL_RENAMED,
        CONTACT_LABEL_CHANGED,
        LABELS_SYNCED,
        AUTO_RULE_EXECUTED
    }

    interface Listener {
        fun onEvent(event: Event, data: Any?)
    }

    private val listeners = ConcurrentHashMap<Event, MutableList<Listener>>()

    @JvmStatic
    fun subscribe(event: Event, listener: Listener) {
        listeners.computeIfAbsent(event) { CopyOnWriteArrayList() }.add(listener)
    }

    @JvmStatic
    fun unsubscribe(event: Event, listener: Listener) {
        val list = listeners[event]
        if (list != null) list.remove(listener)
    }

    @JvmStatic
    fun post(event: Event, data: Any?) {
        val list = listeners[event]
        if (list != null) {
            for (l in list) {
                try {
                    l.onEvent(event, data)
                } catch (ignored: Throwable) {}
            }
        }
    }
}