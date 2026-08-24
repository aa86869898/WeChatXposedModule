package com.leshao.wechat;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

public class AiImageManager {
    private static final String IMAGE_API = "https://ark.cn-beijing.volces.com/api/v3/images/generations";
    private static final String VIDEO_API = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks";
    private static final String IMAGE_MODEL = "doubao-seedream-4-5-251128";
    private static final String VIDEO_MODEL = "doubao-seedance-2-0-260128";

    public static String generateImage(String prompt) {
        String apiKey = ModuleSettings.arkApiKey;
        if (apiKey == null || apiKey.isEmpty()) return "请先配置火山方舟 Key";
        if (prompt == null || prompt.isEmpty()) return "请输入图片描述";

        HttpURLConnection conn = null;
        try {
            if (prompt.length() > 800) prompt = prompt.substring(0, 800);

            JSONObject body = new JSONObject();
            body.put("model", IMAGE_MODEL);
            body.put("prompt", prompt);
            body.put("size", "2K");
            body.put("response_format", "url");
            body.put("sequential_image_generation", "disabled");
            body.put("stream", false);
            body.put("watermark", true);

            conn = (HttpURLConnection) new URL(IMAGE_API).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush(); os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn.getInputStream());
                JSONObject j = new JSONObject(resp);
                JSONArray data = j.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    String url = data.getJSONObject(0).optString("url");
                    if (url != null && !url.isEmpty()) {
                        // Download and save
                        String f = Utils.vdPath() + "/arkImg_" + System.currentTimeMillis() + ".png";
                        if (Utils.dl(url, f)) return f;
                        return url; // fallback: return URL
                    }
                }
                return "图片生成失败: 响应无数据";
            } else {
                String errBody = readStream(conn.getErrorStream());
                Utils.flog("ArkImage error " + code + ": " + errBody);
                return "图片生成失败: HTTP " + code;
            }
        } catch (Throwable e) {
            Utils.flog("ArkImage exception: " + e.getMessage());
            return "图片生成失败: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String generateVideo(String prompt) {
        String apiKey = ModuleSettings.arkApiKey;
        if (apiKey == null || apiKey.isEmpty()) return "请先配置火山方舟 Key";
        if (prompt == null || prompt.isEmpty()) return "请输入视频描述";

        try {
            // Step 1: Create video task
            String taskId = createVideoTask(apiKey, prompt);
            if (taskId == null || taskId.isEmpty()) return "视频任务创建失败";

            // Step 2: Poll for result
            for (int i = 0; i < 30; i++) {
                Thread.sleep(10000);
                String result = pollVideoTask(apiKey, taskId);
                if (result == null) continue; // still processing
                if (result.startsWith("ERROR:")) return "视频生成失败: " + result.substring(6);
                if (result.startsWith("DONE:")) {
                    String videoUrl = result.substring(5);
                    String f = Utils.vdPath() + "/arkVid_" + System.currentTimeMillis() + ".mp4";
                    if (Utils.dl(videoUrl, f)) return f;
                    return videoUrl;
                }
            }
            return "视频生成超时(5分钟)";
        } catch (Throwable e) {
            Utils.flog("ArkVideo exception: " + e.getMessage());
            return "视频生成失败: " + e.getMessage();
        }
    }

    private static String createVideoTask(String apiKey, String prompt) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", VIDEO_MODEL);
            JSONArray content = new JSONArray();
            JSONObject textItem = new JSONObject();
            textItem.put("type", "text");
            textItem.put("text", prompt);
            content.put(textItem);
            body.put("content", content);
            body.put("duration", 8);
            body.put("resolution", "720p");
            body.put("ratio", "16:9");

            conn = (HttpURLConnection) new URL(VIDEO_API).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(100000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush(); os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn.getInputStream());
                JSONObject j = new JSONObject(resp);
                return j.optString("id");
            } else {
                Utils.flog("ArkVideo create error " + code);
                return null;
            }
        } catch (Throwable e) {
            Utils.flog("ArkVideo create exception: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String pollVideoTask(String apiKey, String taskId) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(VIDEO_API + "/" + taskId).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn.getInputStream());
                JSONObject j = new JSONObject(resp);
                String status = j.optString("status");
                if ("completed".equals(status)) {
                    JSONObject ct = j.optJSONObject("content");
                    if (ct != null) {
                        String videoUrl = ct.optString("video_url");
                        if (videoUrl != null && !videoUrl.isEmpty()) {
                            return "DONE:" + videoUrl;
                        }
                    }
                    return "ERROR:响应无视频URL";
                } else if ("failed".equals(status) || "cancelled".equals(status)) {
                    return "ERROR:状态=" + status;
                }
                return null; // still processing
            }
            return "ERROR:HTTP " + code;
        } catch (Throwable e) {
            return "ERROR:" + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
