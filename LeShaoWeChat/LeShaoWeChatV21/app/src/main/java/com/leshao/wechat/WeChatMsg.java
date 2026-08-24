package com.leshao.wechat;

public class WeChatMsg {
    public static final int TYPE_TEXT=1, TYPE_IMAGE=3, TYPE_VOICE=34, TYPE_CARD=42;
    public static final int TYPE_VIDEO=43, TYPE_STICKER=47, TYPE_APPMSG=49, TYPE_VOIP=50;
    public static final int TYPE_SYSTEM=10002, TYPE_REDBAG=318767153, TYPE_TRANSFER=419430449;
    public final Object raw;
    public String talker, content, dbContent, senderWxid;
    public int type; public long createTime, msgId; public boolean isGroup;
    private WeChatMsg(Object r){this.raw=r;}

    public static WeChatMsg fromE9(Object e9){
        if(e9==null)return null;
        WeChatMsg m=new WeChatMsg(e9);
        try{
            m.type=ci(e9,"getType");
            m.talker=cs(e9,"I0");
            if(m.talker==null||m.talker.isEmpty())m.talker=cs(e9,"N0");
            m.dbContent=cs(e9,"S1");m.content=cs(e9,"j");
            if(m.content==null||m.content.isEmpty())m.content=m.dbContent;
            m.createTime=cl(e9,"getCreateTime");m.msgId=cl(e9,"getMsgId");
            m.isGroup=(m.talker!=null&&m.talker.endsWith("@chatroom"));
            m.senderWxid=extractSender(e9,m);
        }catch(Exception e){}
        return m;
    }
    private static String extractSender(Object e9,WeChatMsg m){
        if(!m.isGroup)return m.talker;
        try{
            String db=m.dbContent;
            if(db!=null&&db.contains("<fromusername>")){
                int s=db.indexOf("<fromusername>")+14;
                int e=db.indexOf("</fromusername>",s);
                if(e>s)return db.substring(s,e);
            }
            // 尝试多个字段获取sender
            for(String mn:new String[]{"getSendTalker","getSender","J1","L1","M1","h1","getSenderUserName"}){
                String r=cs(e9,mn);if(r!=null&&!r.isEmpty())return r;
            }
            // 字段扫描 - 查找wxid_或@chatroom字段
            try{
                for(java.lang.reflect.Field f:e9.getClass().getDeclaredFields()){
                    try{f.setAccessible(true);Object val=f.get(e9);
                        if(val instanceof String){String s=(String)val;
                            if((s.startsWith("wxid_")||s.endsWith("@im.wechat"))&&!s.equals(m.talker))return s;}
                    }catch(Throwable t2){}
                }
            }catch(Throwable t){}
            return m.talker;
        }catch(Exception ex){return m.talker;}
    }
    public boolean isText(){return type==TYPE_TEXT;} public boolean isImage(){return type==TYPE_IMAGE;}
    public boolean isVoice(){return type==TYPE_VOICE;} public boolean isCard(){return type==TYPE_CARD;}
    public boolean isVideo(){return type==TYPE_VIDEO||cb(raw,"isVideo");}
    public boolean isSticker(){return type==TYPE_STICKER;} public boolean isAppMsg(){return type==TYPE_APPMSG;}
    public boolean isRedBag(){return type==TYPE_REDBAG;} public boolean isTransfer(){return type==TYPE_TRANSFER;}
    public boolean isVoip(){return type==TYPE_VOIP;}
    public boolean isFileMsg(){return isAppMsg()&&dbContent!=null&&(dbContent.contains("<appattach>")||dbContent.contains("<appmsgattach>"));}
    public boolean isLocationMsg(){return isAppMsg()&&dbContent!=null&&dbContent.contains("<location ");}

    private static String cs(Object o,String m){
        try{java.lang.reflect.Method mt=o.getClass().getMethod(m);Object r=mt.invoke(o);return r!=null?r.toString():null;}
        catch(Throwable t){return null;}
    }
    private static int ci(Object o,String m){
        try{java.lang.reflect.Method mt=o.getClass().getMethod(m);Object r=mt.invoke(o);
            if(r instanceof Integer)return(Integer)r;if(r instanceof Long)return((Long)r).intValue();}
        catch(Throwable t){}return 0;
    }
    private static long cl(Object o,String m){
        try{java.lang.reflect.Method mt=o.getClass().getMethod(m);Object r=mt.invoke(o);
            if(r instanceof Long)return(Long)r;if(r instanceof Integer)return((Integer)r).longValue();}
        catch(Throwable t){}return 0;
    }
    private static boolean cb(Object o,String m){
        try{java.lang.reflect.Method mt=o.getClass().getMethod(m);Object r=mt.invoke(o);return r instanceof Boolean&&(Boolean)r;}
        catch(Throwable t){return false;}
    }
}
