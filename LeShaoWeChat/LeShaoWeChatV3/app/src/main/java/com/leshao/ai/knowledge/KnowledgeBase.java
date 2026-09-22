package com.leshao.ai.knowledge;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 简易知识库。
 *
 * <p>以 {term, text} 知识条目形式存储，可 add/remove/search，
 * 检索时按 query 与 term/text 的相关度打分并返回最相关的若干条拼成 context。
 * 持久化为磁盘 JSON。使用线程安全列表。</p>
 */
public class KnowledgeBase {

    /** 默认文件名。 */
    public static final String DEFAULT_FILE = "leshao_ai/knowledge.json";

    /** 最大 context 拼接长度（字符）。 */
    private static final int MAX_CONTEXT_LENGTH = 2000;

    /** 知识条目类。 */
    public static class KnowledgeItem {
        /** 键（主题/术语）。 */
        public final String term;
        /** 内容文本。 */
        public final String text;

        public KnowledgeItem(String term, String text) {
            this.term = term;
            this.text = text;
        }
    }

    private final File file;
    /** 知识条目列表。 */
    private final CopyOnWriteArrayList<KnowledgeItem> items = new CopyOnWriteArrayList<>();

    /**
     * @param hostDataDir 宿主应用 data 目录
     */
    public KnowledgeBase(String hostDataDir) {
        this(new File(hostDataDir, DEFAULT_FILE));
    }

    /**
     * @param file 自定义文件路径
     */
    public KnowledgeBase(File file) {
        this.file = file;
    }

    /** 添加一条知识。重复 term 会覆盖。 */
    public synchronized boolean add(String term, String text) {
        if (TextUtils.isEmpty(term)) {
            return false;
        }
        if (!TextUtils.isEmpty(text)) {
            text = text.trim();
        }
        // 覆盖同 term 的旧条目
        items.removeIf(it -> it.term != null && it.term.equals(term));
        items.add(new KnowledgeItem(term, text));
        return true;
    }

    /** 添加一条知识（条目对象形式）。 */
    public synchronized boolean add(KnowledgeItem item) {
        return item != null && add(item.term, item.text);
    }

    /** 移除指定 term 的知识。 */
    public synchronized boolean remove(String term) {
        return items.removeIf(it -> it.term != null && it.term.equals(term));
    }

    /** 按 term 精确查找。 */
    public KnowledgeItem get(String term) {
        for (KnowledgeItem it : items) {
            if (it.term != null && it.term.equals(term)) {
                return it;
            }
        }
        return null;
    }

    /**
     * 文本搜索：返回所有在 term 或 text 中包含 query 的条目（子串匹配）。
     *
     * @param query 检索词
     * @return 命中的条目列表
     */
    public List<KnowledgeItem> search(String query) {
        List<KnowledgeItem> result = new ArrayList<>();
        if (TextUtils.isEmpty(query)) {
            return result;
        }
        String q = query.toLowerCase();
        for (KnowledgeItem it : items) {
            boolean hit = (it.term != null && it.term.toLowerCase().contains(q))
                    || (it.text != null && it.text.toLowerCase().contains(q));
            if (hit) {
                result.add(it);
            }
        }
        return result;
    }

    /**
     * 检索最相关的至多 k 条知识（按 term/text 与 query 的相关度打分排序）。
     *
     * @param query 检索词
     * @param k     返回条数上限
     * @return 按相关度降序的条目列表
     */
    public List<KnowledgeItem> retrieve(String query, int k) {
        if (TextUtils.isEmpty(query) || items.isEmpty()) {
            return new ArrayList<>();
        }
        final String q = query.toLowerCase();
        List<KnowledgeItem> scored = new ArrayList<>();
        for (KnowledgeItem it : items) {
            if (score(it, q) > 0) {
                scored.add(it);
            }
        }
        scored.sort(new Comparator<KnowledgeItem>() {
            @Override
            public int compare(KnowledgeItem a, KnowledgeItem b) {
                return Integer.compare(score(b, q), score(a, q));
            }
        });
        int limit = Math.min(k, scored.size());
        return new ArrayList<>(scored.subList(0, limit));
    }

    /** 计算单条知识与 query 的相关度得分。 */
    private int score(KnowledgeItem it, String qLower) {
        int s = 0;
        if (it.term != null) {
            String t = it.term.toLowerCase();
            if (t.equals(qLower)) {
                s += 100;              // term 完全相等，最高分
            } else if (qLower.contains(t)) {
                s += 60;               // term 是 query 的一部分
            } else if (t.contains(qLower)) {
                s += 40;               // query 是 term 的一部分
            }
        }
        if (it.text != null && it.text.toLowerCase().contains(qLower)) {
            s += 30;
        }
        return s;
    }

    /**
     * 检索并拼接为 LLM 可用的 context 字符串。
     *
     * @param query 检索词
     * @param k     返回条数上限
     * @return 按行组织的 context，均超过总长度上限时自动截断
     */
    public String retrieveAsContext(String query, int k) {
        List<KnowledgeItem> list = retrieve(query, k);
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[知识库参考]\n");
        for (KnowledgeItem it : list) {
            sb.append("• ").append(it.term);
            if (!TextUtils.isEmpty(it.text)) {
                sb.append(": ").append(it.text);
            }
            sb.append('\n');
            if (sb.length() > MAX_CONTEXT_LENGTH) {
                sb.setLength(MAX_CONTEXT_LENGTH);
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    /** 知识条目总数。 */
    public int size() {
        return items.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** 返回全部条目副本。 */
    public List<KnowledgeItem> all() {
        return new ArrayList<>(items);
    }

    /** 清空知识库。 */
    public synchronized void clear() {
        items.clear();
    }

    /** 持久化到磁盘。 */
    public synchronized boolean persist() {
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            JSONArray arr = new JSONArray();
            for (KnowledgeItem it : items) {
                JSONObject obj = new JSONObject();
                obj.put("term", it.term);
                obj.put("text", it.text);
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("items", arr);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | org.json.JSONException e) {
            return false;
        }
    }

    /** 从磁盘加载。 */
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
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) {
                return;
            }
            List<KnowledgeItem> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    loaded.add(new KnowledgeItem(
                            obj.optString("term", ""),
                            obj.optString("text", "")
                    ));
                }
            }
            items.clear();
            items.addAll(loaded);
        } catch (IOException | org.json.JSONException e) {
            // 解析失败保留内存内容
        }
    }
}