package com.leshao.v3.model;

import java.util.Objects;

public class Contact {
    public final String wxid;
    public final String nickname;
    public final String remarkName;
    public final String alias;
    public final int type;

    public Contact(String wxid, String nickname, String remarkName, String alias, int type) {
        this.wxid = wxid != null ? wxid : "";
        this.nickname = nickname != null ? nickname : "";
        this.remarkName = remarkName != null ? remarkName : "";
        this.alias = alias != null ? alias : "";
        this.type = type;
    }

    public boolean isGroup() {
        return wxid != null && wxid.endsWith("@chatroom");
    }

    public String displayName() {
        if (remarkName != null && !remarkName.isEmpty()) return remarkName;
        if (alias != null && !alias.isEmpty()) return alias;
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
