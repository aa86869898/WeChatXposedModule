package com.leshao.ai.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分群聊记忆存储。
 *
 * <p>按 chatId 分组保存历史消息，每组独立维护一个环形上限（maxHistoryMessages）。
 * 不同群之间记忆互相隔离，互不干扰。持久化为磁盘 JSON。</p>
 */
public class ChatMemory {

    /** 默认文件名。 */
    public static final String DEFAULT_FILE = "leshao_ai/memory.json";

    private final File file;
    /** 每群的环形上限。 */
    private final int maxPerChat;
    /** chatId -> 历史消息列表（读写均加锁）。 */
    private final Map<String, List<ChatMessage>> buckets = new ConcurrentHashMap<>();

    /**
     * @param hostDataDir 宿主应用 data 目录
     * @param maxPerChat  每群保留的最大消息条数
     */
    public ChatMemory(String hostDataDir, int maxPerChat) {
        this(new File(hostDataDir, DEFAULT_FILE), maxPerChat);
    }

    /**
     * @param file       自定义文件路径
     * @param maxPerChat 每群保留的最大消息条数（至少 1）
     */
    public ChatMemory(File file, int maxPerChat) {
        this.file = file;
        this.maxPerChat = Math.max(1, maxPerChat);
    }

    /**
     * 追加一条消息到指定聊天。
     *
     * @param chatId 群/联系人 id
     * @param msg    消息
     */
    public synchronized void add(String chatId, ChatMessage msg) {
        if (chatId == null || msg == null) {
            return;
        }
        List<ChatMessage> list = buckets.computeIfAbsent(chatId, k -> new ArrayList<>());
        list.add(msg);
        trim(list);
    }

    /** 便捷方法：追加指定角色内容。 */
    public synchronized void add(String chatId, String role, String content) {
        add(chatId, new ChatMessage(role, content));
    }

    /** 裁剪列表到环形上限，保留最新消息。 */
    private void trim(List<ChatMessage> list) {
        while (list.size() > maxPerChat) {
            list.remove(0);
        }
    }

    /**
     * 获取某聊天最近的 n 条消息。
     *
     * @param chatId 聊天 id
     * @param n      条数，<=0 或超过上限时取上限
     * @return 按时间顺序的新消息列表副本
     */
    public synchronized List<ChatMessage> getRecent(String chatId, int n) {
        List<ChatMessage> all = buckets.get(chatId);
        if (all == null || all.isEmpty()) {
            return new ArrayList<>();
        }
        int take = n <= 0 ? maxPerChat : Math.min(n, all.size());
        int start = all.size() - take;
        return new ArrayList<>(all.subList(start, all.size()));
    }

    /** 获取某聊天全部历史消息副本。 */
    public synchronized List<ChatMessage> getAll(String chatId) {
        List<ChatMessage> all = buckets.get(chatId);
        return all == null ? new ArrayList<>() : new ArrayList<>(all);
    }

    /** 获取某聊天当前消息条数。 */
    public synchronized int count(String chatId) {
        List<ChatMessage> list = buckets.get(chatId);
        return list == null ? 0 : list.size();
    }

    /** 清空某聊天历史。 */
    public synchronized void clear(String chatId) {
        buckets.remove(chatId);
    }

    /** 清空全部聊天历史。 */
    public synchronized void clearAll() {
        buckets.clear();
    }

    /** 当前记录了多少个聊天分组。 */
    public int groupCount() {
        return buckets.size();
    }

    /** 获取所有有记录的 chatId。 */
    public synchronized List<String> chatIds() {
        return new ArrayList<>(buckets.keySet());
    }

    /** 为某聊天设置新的最大条数并裁剪（可选）。 */
    public synchronized void setMaxPerChat(int n) {
        // 本类 maxPerChat 由构造决定，若要动态调整可在此记录并裁剪
        // 因不可变设计，此处保留为对现有数据不做处理，由子类扩展。
    }

    /** 持久化全部内存到磁盘。 */
    public synchronized boolean persist() {
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            JSONObject root = new JSONObject();
            root.put("maxPerChat", maxPerChat);
            JSONObject chats = new JSONObject();
            for (Map.Entry<String, List<ChatMessage>> e : buckets.entrySet()) {
                JSONArray arr = new JSONArray();
                for (ChatMessage m : e.getValue()) {
                    arr.put(m.toJson());
                }
                chats.put(e.getKey(), arr);
            }
            root.put("chats", chats);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | org.json.JSONException e) {
            return false;
        }
    }

    /** 从磁盘加载记忆到内存（追加式，不清空已有）。 */
    public synchronized void load() {
        if (file == null || !file.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = in.read(data);
            if (read <= 0) {
                return;
            }
            JSONObject root = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            JSONObject chats = root.optJSONObject("chats");
            if (chats == null) {
                return;
            }
            java.util.Iterator<String> it = chats.keys();
            while (it.hasNext()) {
                String chatId = it.next();
                JSONArray arr = chats.optJSONArray(chatId);
                if (arr == null) {
                    continue;
                }
                List<ChatMessage> list = buckets.computeIfAbsent(chatId, k -> new ArrayList<>());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m != null) {
                        list.add(ChatMessage.fromJson(m));
                    }
                }
                trim(list);
            }
        } catch (IOException | org.json.JSONException e) {
            // 解析失败保留内存内容
        }
    }

    /** 重置记忆（清空并可选删除磁盘文件）。 */
    public synchronized void reset(boolean deleteFile) {
        clearAll();
        if (deleteFile && file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
