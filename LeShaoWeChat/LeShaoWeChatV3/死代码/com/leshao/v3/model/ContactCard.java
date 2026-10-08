package com.leshao.v3.model;

public class ContactCard {
    public String username;
    public String nickname;
    public String alias;
    public String conRemark;
    public String pyInitial;
    public String quanPin;
    public String conRemarkPYFull;
    public int type;
    public int showHead;
    public int imgFlag;
    public String contactLabelIds;
    public long createTime;

    public enum Category {
        FRIEND, GROUP, OFFICIAL, SYSTEM, FILE_HELPER, OTHER
    }

    public Category category;

    public String displayName() {
        if (conRemark != null && !conRemark.isEmpty()) return conRemark;
        if (nickname != null && !nickname.isEmpty()) return nickname;
        if (alias != null && !alias.isEmpty()) return alias;
        return username;
    }

    public String sortKey() {
        String src = (conRemarkPYFull != null && !conRemarkPYFull.isEmpty())
                ? conRemarkPYFull : quanPin;
        if (src != null && !src.isEmpty()) return src.toUpperCase();
        String name = displayName();
        if (name != null && !name.isEmpty()) return name.substring(0, 1).toUpperCase();
        return "#";
    }

    public boolean isFriend() { return category == Category.FRIEND; }
    public boolean isGroup()  { return category == Category.GROUP; }

    public static Category classify(String username, int type) {
        if ("filehelper".equals(username)) return Category.FILE_HELPER;
        if (username != null && username.endsWith("@chatroom")) return Category.GROUP;
        if (username != null && username.startsWith("gh_")) return Category.OFFICIAL;
        if (type == 33 || type == 2049) return Category.SYSTEM;
        // 微信 j4.t()+j4.m() 官方定义: bit0=1 联系人, bit3=8 黑名单, bit5=32 公众号, bit6=64 企业微信
        if ((type & 1) != 0 && (type & 32) == 0 && (type & 8) == 0 && (type & 64) == 0) return Category.FRIEND;
        if ((type & 32) != 0) return Category.OFFICIAL;
        return Category.OTHER;
    }
}
