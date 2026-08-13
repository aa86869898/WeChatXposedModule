package com.leshao.v3.hook.model;

public class AutoRule {
    public enum MatchType {
        REMARK_CONTAINS,
        NICKNAME_CONTAINS,
        USERNAME_MATCHES,
        IN_CHATROOM,
        REGION_EQUALS,
        LABEL_NAME_MATCHES,
    }

    public int ruleId;
    public String ruleName;
    public MatchType matchType;
    public String matchValue;
    public int targetLabelId;
    public boolean enabled = true;
    public long createTime;
    public long lastRunTime;
    public int matchCount;

    public AutoRule() {}

    public AutoRule(int ruleId, String ruleName, MatchType matchType,
                    String matchValue, int targetLabelId) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.matchType = matchType;
        this.matchValue = matchValue;
        this.targetLabelId = targetLabelId;
        this.createTime = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "Rule#" + ruleId + " [" + ruleName + "] " + matchType + "='" + matchValue
            + "' -> label:" + targetLabelId;
    }
}
