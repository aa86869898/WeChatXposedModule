package com.leshao.v3.model;

import java.util.Objects;

public class Contact {
    public final String wxid;
    public final String nickname;
    public final String remarkName;
    public final String alias;
    public final int type;
    public int sex;
    public final long createTime;
    public int verifyFlag;
    public int chatroomFlag;

    public Contact(String wxid, String nickname, String remarkName, String alias, int type) {
        this(wxid, nickname, remarkName, alias, type, 0, 0);
    }

    public Contact(String wxid, String nickname, String remarkName, String alias, int type, int sex, long createTime) {
        this.wxid = wxid != null ? wxid : "";
        this.nickname = nickname != null ? nickname : "";
        this.remarkName = remarkName != null ? remarkName : "";
        this.alias = alias != null ? alias : "";
        this.type = type;
        this.sex = sex;
        this.createTime = createTime;
        this.verifyFlag = 0;
        this.chatroomFlag = 0;
    }

    public boolean isGroup() {
        return wxid != null && wxid.endsWith("@chatroom");
    }

    public boolean isMale() { return sex == 1; }
    public boolean isFemale() { return sex == 2; }

    public String displayName() {
        if (remarkName != null && !remarkName.isEmpty()) return remarkName;
        if (alias != null && !alias.isEmpty() && !alias.startsWith("wxid_")) return alias;
        if (nickname != null && !nickname.isEmpty()) return nickname;
        return wxid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Contact)) return false;
        return Objects.equals(wxid, ((Contact) o).wxid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(wxid);
    }

    @Override
    public String toString() {
        return "Contact{wxid=" + wxid + ", name=" + displayName() + "}";
    }

    public static Contact empty() {
        return new Contact("", "", "", "", 0);
    }
}
