package com.leshao.v3.hook;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class EventBus {

    public enum Event {
        LABEL_CREATED,
        LABEL_DELETED,
        LABEL_RENAMED,
        CONTACT_LABEL_CHANGED,
        LABELS_SYNCED,
        AUTO_RULE_EXECUTED,
    }

    public interface Listener {
        void onEvent(Event event, Object data);
    }

    private static final Map<Event, List<Listener>> listeners = new ConcurrentHashMap<>();

    public static void subscribe(Event event, Listener listener) {
        listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public static void unsubscribe(Event event, Listener listener) {
        List<Listener> list = listeners.get(event);
        if (list != null) list.remove(listener);
    }

    public static void post(Event event, Object data) {
        List<Listener> list = listeners.get(event);
        if (list != null) {
            for (Listener l : list) {
                try { l.onEvent(event, data); } catch (Throwable ignored) {}
            }
        }
    }
}
