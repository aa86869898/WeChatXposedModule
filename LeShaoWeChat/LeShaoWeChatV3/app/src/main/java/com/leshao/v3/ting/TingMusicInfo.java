package com.leshao.v3.ting;

public class TingMusicInfo {
    public String listenId;
    public String srcId;
    public String title;
    public String author;
    public String cover;
    public String dataUrl;
    public String webUrl;
    public String bizUsername;
    public int type;
    public String localPath;

    @Override
    public String toString() {
        return "TingMusicInfo{title=" + title + ", author=" + author
                + ", listenId=" + listenId + ", type=" + type
                + ", dataUrl=" + (dataUrl == null ? "null" : dataUrl.substring(0, Math.min(60, dataUrl.length())))
                + "}";
    }
}
