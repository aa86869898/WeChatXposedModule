package com.leshao.ai.util;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 白名单管理类。
 *
 * <p>管理允许 AI 自动回复的群 id / 联系人 id，持久化为磁盘 JSON。
 * 使用 {@link CopyOnWriteArraySet} 保证读取时不被并发修改干扰。</p>
 */
public class Whitelist {

    /** 默认文件名。 */
    public static final String DEFAULT_FILE = "leshao_ai/whitelist.json";

    private final File file;
    private final Set<String> ids = new CopyOnWriteArraySet<>();

    /**
     * @param hostDataDir 宿主应用 data 目录
     */
    public Whitelist(String hostDataDir) {
        this(new File(hostDataDir, DEFAULT_FILE));
    }

    /**
     * @param file 自定义文件路径
     */
    public Whitelist(File file) {
        this.file = file;
    }

    /**
     * 从磁盘加载白名单。
     */
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
            JSONObject obj = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            JSONArray arr = obj.optJSONArray("ids");
            if (arr != null) {
                ids.clear();
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i);
                    if (!TextUtils.isEmpty(id)) {
                        ids.add(id);
                    }
                }
            }
        } catch (IOException | org.json.JSONException e) {
            // 解析失败保留内存内容
        }
    }

    /**
     * 保存白名单到磁盘。
     *
     * @return 成功返回 true
     */
    public synchronized boolean save() {
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            JSONObject obj = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String id : ids) {
                arr.put(id);
            }
            obj.put("ids", arr);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | org.json.JSONException e) {
            return false;
        }
    }

    /** 添加一个 id。重复添加幂等。 */
    public synchronized boolean add(String id) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        return ids.add(id);
    }

    /** 批量添加。返回实际新增数量。 */
    public synchronized int addAll(List<String> list) {
        int added = 0;
        if (list != null) {
            for (String id : list) {
                if (add(id)) {
                    added++;
                }
            }
        }
        return added;
    }

    /** 移除一个 id。 */
    public synchronized boolean remove(String id) {
        return id != null && ids.remove(id);
    }

    /** 是否包含某 id。 */
    public boolean contains(String id) {
        return id != null && ids.contains(id);
    }

    /** 清空白名单。 */
    public synchronized void clear() {
        ids.clear();
    }

    /** 获取白名单数量。 */
    public int size() {
        return ids.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** 返回白名单列表（复制副本，可安全遍历修改）。 */
    public List<String> list() {
        return new ArrayList<>(ids);
    }

    /** 返回白名单 set 视图（不可直接修改底层）。 */
    public Set<String> asSet() {
        return Collections.unmodifiableSet(ids);
    }

    /** 群 id 集合（以 @chatroom 结尾的视为群）。 */
    public List<String> groupIds() {
        List<String> groups = new ArrayList<>();
        for (String id : ids) {
            if (id != null && id.endsWith("@chatroom")) {
                groups.add(id);
            }
        }
        return groups;
    }

    /** 联系人（非群）id 集合。 */
    public List<String> contactIds() {
        List<String> contacts = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.endsWith("@chatroom")) {
                contacts.add(id);
            }
        }
        return contacts;
    }

    /** 去重并返回所有已包含 id 的排序副本。 */
    public List<String> sorted() {
        List<String> list = new ArrayList<>(new LinkedHashSet<>(ids));
        Collections.sort(list);
        return list;
    }
}
