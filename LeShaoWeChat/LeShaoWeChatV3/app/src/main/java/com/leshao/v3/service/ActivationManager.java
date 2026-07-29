package com.leshao.v3.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.CRC32;

/**
 * 离线激活码系统
 * 格式: LS-{10 base62 metadata}-{8 hex verify}
 * metadata = levelIndex(1B) + expireHours(2B) + featureMask(4B) = 7 bytes
 * verify = MD5(metadata_bytes + wxid + SECRET)[0:4]
 */
public class ActivationManager {

    private static final String TAG = "ActivationManager";
    private static final String PREF_KEY_CODE   = "ls_act_code";
    private static final String PREF_KEY_LEVEL  = "ls_act_level";
    private static final String PREF_KEY_EXPIRE = "ls_act_expire";
    private static final String PREF_KEY_FEAT_MASK = "ls_act_feature_mask";
    private static final String PREF_KEY_WXID   = "ls_act_wxid";
    private static final String PREF_KEY_CRC    = "ls_act_crc";

    // 防破解: 备份文件路径
    private static final String BACKUP_FILE = "ls_activation.dat";

    // === 测试模式 (下个版本移除) ===
    private static final boolean TEST_MODE = true;
    private static final String TEST_CODE = "558800";

    // 管理员列表
    public static final String[] ADMIN_WXIDS = {
        "wxid_qf3ok46p08v922"
    };

    // Feature bits
    public static final int F_MASTER         = 0;
    public static final int F_QUOTE          = 1;
    public static final int F_TEXT           = 2;
    public static final int F_VOICE          = 3;
    public static final int F_IMAGE          = 4;
    public static final int F_VIDEO          = 5;
    public static final int F_LOCATION       = 6;
    public static final int F_REDBAG         = 7;
    public static final int F_TRANSFER       = 8;
    public static final int F_CARD           = 9;
    public static final int F_FILE           = 10;
    public static final int F_STICKER        = 11;
    public static final int F_GROUP          = 12;
    public static final int F_ANTI_RECALL    = 13;
    public static final int F_REDPACKET_GRAB = 14;
    public static final int F_AUTO_REPLY     = 15;
    public static final int F_KEYWORD_REPLY  = 16;
    public static final int F_VOICE_FORWARD  = 17;
    public static final int F_TYPING_HINT    = 18;
    public static final int F_FOOTER_ENHANCE = 19;
    public static final int F_CHAT_UI        = 20;
    public static final int F_BATCH_MSG      = 21;
    public static final int F_SCHEDULED_SEND = 22;
    public static final int F_AUTO_REMARK    = 23;
    public static final int F_DELETE_DETECT  = 24;
    public static final int F_CONTACT_EXPORT = 25;
    public static final int F_SNS_FEATURES   = 26;
    public static final int F_PRIVACY        = 27;
    public static final int F_DATA_BACKUP    = 28;
    public static final int F_AI_ASST        = 29;
    public static final int F_DEEPSEEK       = 30;
    public static final int F_DINGDONG       = 31;

    public static final String[] FEATURE_NAMES = {
        "播报总开关", "引用消息播报", "文字播报", "语音播报",
        "图片播报", "视频播报", "位置播报", "红包播报",
        "转账播报", "名片播报", "文件播报", "表情播报",
        "群聊播报", "防撤回", "红包秒抢",
        "自动回复", "关键词回复", "语音转发",
        "正在输入提示", "底部栏增强", "聊天UI定制",
        "批量群发", "定时发送", "自动备注",
        "删除检测", "通讯录导出", "朋友圈增强",
        "隐私安全", "数据备份", "AI助手",
        "DeepSeek", "叮咚金句"
    };

    private static final String SECRET = new String(new byte[]{
        0x4C, 0x65, 0x53, 0x68, 0x61, 0x6F, 0x32, 0x30,
        0x32, 0x35, 0x4B, 0x65, 0x79, 0x58, 0x58, 0x58
    });

    // ==================== 激活码生成 ====================

    /**
     * 生成激活码
     * @param wxid 绑定微信ID (空字符串表示不绑定，但实际要求绑定)
     * @param levelIndex 等级 (0=免费, 1=专业, 2=VIP, 可自定义更多)
     * @param expireHours 有效小时数，0=永久
     * @param featureMask 功能位掩码
     */
     public static String generateCode(String wxid, int levelIndex, int expireHours, int featureMask) {
        if (wxid == null || wxid.isEmpty()) return "";

        // 7 bytes metadata
        byte[] metadata = new byte[7];
        metadata[0] = (byte) (levelIndex & 0xFF);
        metadata[1] = (byte) ((expireHours >> 8) & 0xFF);
        metadata[2] = (byte) (expireHours & 0xFF);
        metadata[3] = (byte) ((featureMask >> 24) & 0xFF);
        metadata[4] = (byte) ((featureMask >> 16) & 0xFF);
        metadata[5] = (byte) ((featureMask >> 8) & 0xFF);
        metadata[6] = (byte) (featureMask & 0xFF);

        String payload = base62Encode(metadata);

        // verify = MD5(metadata + wxid + SECRET)[0:4] as hex
        byte[] verifyBytes = md5(concat(metadata, wxid.getBytes(StandardCharsets.UTF_8), SECRET.getBytes(StandardCharsets.UTF_8)));
        String verify = bytesToHex(verifyBytes, 4);

        return "LS-" + payload + "-" + verify;
    }

    // ==================== 激活码校验 ====================

    public static class ValidationResult {
        public boolean valid;
        public int levelIndex;
        public int expireHours;
        public int featureMask;
        public String levelName;
    }

    public static ValidationResult validate(String code, String wxid) {
        ValidationResult r = new ValidationResult();
        if (code == null || wxid == null) return r;

        // 测试激活码: 不绑定wxid, 所有功能永久有效
        if (TEST_MODE && TEST_CODE.equals(code)) {
            r.valid = true;
            r.levelIndex = 2;
            r.expireHours = 0;
            r.featureMask = 0xFFFFFFFF;
            r.levelName = "测试版";
            return r;
        }

        try {
            // 解析: LS-{payload}-{verify}
            if (!code.startsWith("LS-")) return r;
            int dash2 = code.lastIndexOf('-');
            if (dash2 < 0 || dash2 == 3) return r;

            String payload = code.substring(3, dash2);
            String verify  = code.substring(dash2 + 1);

            byte[] metadata = base62Decode(payload);
            if (metadata == null || metadata.length != 7) return r;

            // 校验 verify
            byte[] expected = md5(concat(metadata, wxid.getBytes(StandardCharsets.UTF_8), SECRET.getBytes(StandardCharsets.UTF_8)));
            String expectedHex = bytesToHex(expected, 4);
            if (!expectedHex.equalsIgnoreCase(verify)) return r;

            // 解析 metadata
            r.levelIndex = metadata[0] & 0xFF;
            r.expireHours = ((metadata[1] & 0xFF) << 8) | (metadata[2] & 0xFF);
            r.featureMask = ((metadata[3] & 0xFF) << 24) | ((metadata[4] & 0xFF) << 16)
                          | ((metadata[5] & 0xFF) << 8)  | (metadata[6] & 0xFF);
            r.levelName = levelName(r.levelIndex);

            r.valid = true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "validate err: " + t.getMessage());
        }
        return r;
    }

    // ==================== 存储 (防破解: 双写+CRC) ====================

     public static void saveActivation(Context ctx, String code, String wxid, int levelIndex, int expireHours, int featureMask) {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return;

        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(PREF_KEY_CODE, code);
        ed.putString(PREF_KEY_LEVEL, String.valueOf(levelIndex));
        ed.putString(PREF_KEY_EXPIRE, String.valueOf(expireHours));
        ed.putString(PREF_KEY_FEAT_MASK, String.valueOf(featureMask));
        ed.putString(PREF_KEY_WXID, wxid);

        // CRC 校验
        String data = code + "|" + wxid + "|" + levelIndex + "|" + expireHours + "|" + featureMask;
        long crc = crc32(data);
        ed.putString(PREF_KEY_CRC, String.valueOf(crc));
        ed.commit();

        // 防破解: 备份文件
        saveBackup(ctx, data, crc);
    }

    private static void saveBackup(Context ctx, String data, long crc) {
        try {
            File f = new File(ctx.getFilesDir(), BACKUP_FILE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write((crc + "\n" + data).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isActivated() {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return false;

        String code   = prefs.getString(PREF_KEY_CODE, "");
        String wxid   = prefs.getString(PREF_KEY_WXID, "");
        String levelS = prefs.getString(PREF_KEY_LEVEL, "");
        String expireS = prefs.getString(PREF_KEY_EXPIRE, "");
        String featS  = prefs.getString(PREF_KEY_FEAT_MASK, "");
        String crcS   = prefs.getString(PREF_KEY_CRC, "");

        if (code.isEmpty() || wxid.isEmpty()) return false;

        // CRC 校验
        String data = code + "|" + wxid + "|" + levelS + "|" + expireS + "|" + featS;
        long crc = crc32(data);
        try {
            if (crcS.isEmpty() || crc != Long.parseLong(crcS)) {
                // CRC 不一致，尝试从备份恢复
                Context ctx = ContextManager.getAppContext();
                if (ctx != null && !restoreFromBackup(ctx)) {
                    LogWriter.log(TAG, "CRC mismatch, backup restore failed");
                    return false;
                }
            }
        } catch (Throwable t) {
            return false;
        }

        // 检查过期
        try {
            int expireHours = Integer.parseInt(expireS);
            if (expireHours > 0) {
                String actTimeStr = prefs.getString("ls_act_time", "");
                if (!actTimeStr.isEmpty()) {
                    long actTime = Long.parseLong(actTimeStr);
                    long expireMs = actTime + expireHours * 3600000L;
                    if (System.currentTimeMillis() > expireMs) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return true;
    }

    private static boolean restoreFromBackup(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), BACKUP_FILE);
            if (!f.exists()) return false;
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                fis.read(bytes);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            int nl = content.indexOf('\n');
            if (nl < 0) return false;
            long crc = Long.parseLong(content.substring(0, nl).trim());
            String data = content.substring(nl + 1);
            if (crc32(data) != crc) return false;

            String[] parts = data.split("\\|", 5);
            if (parts.length != 5) return false;

            SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs == null) return false;
            prefs.edit()
                .putString(PREF_KEY_CODE, parts[0])
                .putString(PREF_KEY_WXID, parts[1])
                .putString(PREF_KEY_LEVEL, parts[2])
                .putString(PREF_KEY_EXPIRE, parts[3])
                .putString(PREF_KEY_FEAT_MASK, parts[4])
                .putString(PREF_KEY_CRC, String.valueOf(crc))
                .commit();
            LogWriter.log(TAG, "Restored from backup");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isFeatureEnabled(int featureBit) {
        if (!isActivated()) return false;
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return false;
        try {
            int mask = Integer.parseInt(prefs.getString(PREF_KEY_FEAT_MASK, "0"));
            return (mask & (1 << featureBit)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int getFeatureMask() {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return 0;
        try {
            return Integer.parseInt(prefs.getString(PREF_KEY_FEAT_MASK, "0"));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String getLevelName() {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return "未激活";
        try {
            int expireHours = Integer.parseInt(prefs.getString(PREF_KEY_EXPIRE, "0"));
            return derivedLevelName(expireHours);
        } catch (Throwable t) {
            return "未激活";
        }
    }

    /**
     * 根据过期时间推导会员等级名称:
     *   30天以下=体验会员, 30天=月度会员, 90天=季度会员, 365天=年度会员, >365天=永久会员
     */
    public static String derivedLevelName(int expireHours) {
        int expireDays = expireHours / 24;
        if (expireHours == 0 || expireDays > 365) return "永久会员";
        if (expireDays >= 365) return "年度会员";
        if (expireDays >= 90) return "季度会员";
        if (expireDays >= 30) return "月度会员";
        return "体验会员";
    }

    public static boolean isPermanentMember() {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return false;
        try {
            int expireHours = Integer.parseInt(prefs.getString(PREF_KEY_EXPIRE, "0"));
            return expireHours == 0 || (expireHours / 24) > 365;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int getLevelIndex() {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return -1;
        try {
            return Integer.parseInt(prefs.getString(PREF_KEY_LEVEL, "-1"));
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String getBoundWxid() {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return "";
        return prefs.getString(PREF_KEY_WXID, "");
    }

    public static boolean isTestMode() { return TEST_MODE; }

    public static boolean isAdmin(String wxid) {
        if (wxid == null) return false;
        for (String a : ADMIN_WXIDS) {
            if (wxid.equals(a)) return true;
        }
        return false;
    }

    /**
     * 检查指定功能页面是否被当前激活码授权。
     * 映射 pageId → 所需 featureMask bits，任一位为 1 即放行。
     * 无对应 feature bit 的页面(pageId=2/4/5)默认放行。
     */
    public static boolean isFeaturePageAllowed(int pageId) {
        if (TEST_MODE) return true;
        int mask = getFeatureMask();
        if (mask == 0xFFFFFFFF) return true;

        switch (pageId) {
            case 1:  // 聊天功能: 防撤回/自动回复/关键词/语音转发/输入/底部栏/聊天UI/批量群发/定时发送/自动备注/删除检测
                return isAnyBit(mask, F_ANTI_RECALL, F_AUTO_REPLY, F_KEYWORD_REPLY, F_VOICE_FORWARD,
                        F_TYPING_HINT, F_FOOTER_ENHANCE, F_CHAT_UI, F_BATCH_MSG, F_SCHEDULED_SEND, F_AUTO_REMARK, F_DELETE_DETECT);
            case 2:  // 主题美化: 基础功能，默认放行
            case 4:  // 群管理助手: 基础功能，默认放行
            case 5:  // 万群自动转发: 基础功能，默认放行
                return true;
            case 3:  // 联系人和群聊: 通讯录导出
                return isAnyBit(mask, F_CONTACT_EXPORT);
            case 6:  // 定时消息助手: 定时发送
                return isAnyBit(mask, F_SCHEDULED_SEND);
            case 7:  // AI智慧助手: AI助手/DeepSeek
                return isAnyBit(mask, F_AI_ASST, F_DEEPSEEK);
            case 8:  // TTS播报: 总开关或任意播报类型
                return isAnyBit(mask, F_MASTER, F_QUOTE, F_TEXT, F_VOICE, F_IMAGE, F_VIDEO,
                        F_LOCATION, F_REDBAG, F_TRANSFER, F_CARD, F_FILE, F_STICKER, F_GROUP);
            case 9:  // 红包转账: 红包秒抢
                return isAnyBit(mask, F_REDPACKET_GRAB);
            case 10: // 朋友圈增强
                return isAnyBit(mask, F_SNS_FEATURES);
            case 11: // 隐私安全
                return isAnyBit(mask, F_PRIVACY);
            case 12: // 数据备份
                return isAnyBit(mask, F_DATA_BACKUP);
            case 13: // 娱乐助手: 叮咚
                return isAnyBit(mask, F_DINGDONG);
            case 14: // 定时消息群发: 定时发送
                return isAnyBit(mask, F_SCHEDULED_SEND);
            default:
                return true;
        }
    }

    private static boolean isAnyBit(int mask, int... bits) {
        for (int bit : bits) {
            if ((mask & (1 << bit)) != 0) return true;
        }
        return false;
    }

    private static String levelName(int idx) {
        switch (idx) {
            case 0: return "体验会员";
            case 1: return "月度会员";
            case 2: return "季度会员";
            case 3: return "年度会员";
            case 4: return "永久会员";
            default: return "等级" + idx;
        }
    }

    // ==================== 工具方法 ====================

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE62_RADIX = 62;

    static String base62Encode(byte[] data) {
        // Convert bytes to BigInteger-like number and encode as base62
        StringBuilder sb = new StringBuilder();
        StringBuilder bin = new StringBuilder();
        for (byte b : data) {
            bin.append(String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0'));
        }
        // Work in chunks to avoid overflow
        java.math.BigInteger bi = new java.math.BigInteger(1, data);
        if (bi.equals(java.math.BigInteger.ZERO)) return "0";
        while (bi.compareTo(java.math.BigInteger.ZERO) > 0) {
            java.math.BigInteger[] div = bi.divideAndRemainder(java.math.BigInteger.valueOf(BASE62_RADIX));
            sb.append(BASE62.charAt(div[1].intValue()));
            bi = div[0];
        }
        return sb.reverse().toString();
    }

    static byte[] base62Decode(String s) {
        java.math.BigInteger bi = java.math.BigInteger.ZERO;
        for (int i = 0; i < s.length(); i++) {
            int idx = BASE62.indexOf(s.charAt(i));
            if (idx < 0) return null;
            bi = bi.multiply(java.math.BigInteger.valueOf(BASE62_RADIX))
                 .add(java.math.BigInteger.valueOf(idx));
        }
        byte[] bytes = bi.toByteArray();
        // Pad to 7 bytes
        if (bytes.length < 7) {
            byte[] padded = new byte[7];
            System.arraycopy(bytes, 0, padded, 7 - bytes.length, bytes.length);
            return padded;
        }
        if (bytes.length == 7) return bytes;
        // Trim leading zero byte
        if (bytes.length == 8 && bytes[0] == 0) {
            byte[] trimmed = new byte[7];
            System.arraycopy(bytes, 1, trimmed, 0, 7);
            return trimmed;
        }
        return null;
    }

    private static byte[] md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (Throwable t) {
            return new byte[16];
        }
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < bytes.length; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static long crc32(String data) {
        CRC32 crc = new CRC32();
        crc.update(data.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
