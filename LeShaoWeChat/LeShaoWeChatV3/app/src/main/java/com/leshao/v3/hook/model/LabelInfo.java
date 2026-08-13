package com.leshao.v3.hook.model;

import java.util.ArrayList;
import java.util.List;

public class LabelInfo {
    public int labelId;
    public String labelName;
    public String labelPYFull;
    public String labelPYShort;
    public long createTime;
    public boolean isTemporary;
    public long lastUseTime;
    public List<String> contacts;

    public LabelInfo() {
        this.contacts = new ArrayList<>();
    }

    public LabelInfo(int labelId, String labelName) {
        this();
        this.labelId = labelId;
        this.labelName = labelName;
    }

    @Override
    public String toString() {
        return "[" + labelId + "] " + labelName
            + (isTemporary ? " (临时)" : "")
            + " — " + contacts.size() + "个联系人";
    }
}
