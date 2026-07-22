package com.leshao.wechat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import de.robv.android.xposed.XposedBridge;

public class Utils {
    private static final ExecutorService ex = Executors.newCachedThreadPool();
    private static final Handler mh = new Handler(Looper.getMainLooper());
    private static float density = -1;

    // ============ 文件日志系统 ============
    private static File logFile;
    private static final String LOG_NAME = "leshao_v21_log.txt";
    private static final int MAX_LOG_SIZE = 512 * 1024; // 512KB 自动轮转
    private static boolean logReady = false;

    /** 早期初始化日志（handleLoadPackage阶段即可调用，不需要Context） */
    public static void initLogEarly(String pkg) {
        if (logReady) return;
        try {
            File dir = new File("/data/user/0/" + pkg + "/files/leshao_logs");
            dir.mkdirs();
            logFile = new File(dir, LOG_NAME);
            logReady = true;
            writeFileLog("═══ 乐少助手 v2.1 日志启动(early) ═══");
        } catch (Throwable t) {}
    }

    /** 初始化日志文件（在拿到微信Context后调用） */
    public static void initLog(Context ctx) {
        if (logReady) return;
        try {
            File dir = new File(ctx.getFilesDir(), "leshao_logs");
            dir.mkdirs();
            logFile = new File(dir, LOG_NAME);
            logReady = true;
            xlog("═══ 乐少助手 v2.1 日志启动 ═══");
            xlog("微信版本: " + MainHook.wxVersionName + " (" + MainHook.wxVersion + ")");
            xlog("模块版本: v2.1.0 (code=210)");
            xlog("日志路径: " + logFile.getAbsolutePath());
        } catch (Exception e) {}
    }

    /** 写入文件日志（同时输出到 XposedBridge） */
    public static void xlog(String msg) {
        XposedBridge.log("LeShaoWeChat: " + msg);
        writeFileLog(msg);
    }

    /** 仅文件日志（不输出到 Xposed） */
    public static void flog(String msg) {
        writeFileLog(msg);
    }

    /** 写入文件日志的核心实现 */
    private static void writeFileLog(final String msg) {
        if (!logReady || logFile == null) return;
        try {
            // 检查文件大小，超过512KB自动轮转
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                File bak = new File(logFile.getParent(), LOG_NAME + ".old");
                bak.delete();
                logFile.renameTo(bak);
            }

            FileWriter fw = new FileWriter(logFile, true);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date());
            fw.write(ts + " " + msg + "\n");
            fw.close();
        } catch (Exception e) {}
    }

    /** 获取日志文件路径 */
    public static String getLogPath() {
        return logFile != null ? logFile.getAbsolutePath() : "(未初始化)";
    }

    /** 读取日志文件内容 */
    public static String readLog() {
        if (logFile == null || !logFile.exists()) return "(无日志)";
        try {
            BufferedReader br = new BufferedReader(new FileReader(logFile));
            StringBuilder sb = new StringBuilder();
            String line; int lines = 0;
            while ((line = br.readLine()) != null && lines < 200) {
                sb.append(line).append("\n"); lines++;
            }
            br.close();
            if (lines >= 200) sb.append("... (更多日志省略，完整日志见: ").append(logFile.getAbsolutePath()).append(")");
            return sb.toString();
        } catch (Exception e) { return "(读取失败: " + e.getMessage() + ")"; }
    }

    /** 清空日志 */
    public static void clearLog() {
        try { if (logFile != null) { logFile.delete(); xlog("日志已清空"); } } catch (Exception e) {}
    }

    // ============ Toast / 线程 / HTTP / 工具（保持不变） ============

    public static void log(String s) { XposedBridge.log("LeShaoWeChat: "+s); }
    public static void t(Context c, String s) { mh.post(new Runnable() { public void run() { try { Toast.makeText(c,s,Toast.LENGTH_SHORT).show(); } catch(Exception e){} } }); }
    public static void tL(Context c, String s) { mh.post(new Runnable() { public void run() { try { Toast.makeText(c,s,Toast.LENGTH_LONG).show(); } catch(Exception e){} } }); }
    public static void rM(Runnable r) { mh.post(r); }
    public static void rMD(Runnable r, long ms) { mh.postDelayed(r, ms); }
    public static void rB(Runnable r) { ex.execute(r); }

    public static String httpGet(String url) {
        try{ HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent","Mozilla/5.0");
            if(c.getResponseCode()!=200){flog("HTTP-GET "+c.getResponseCode()+" "+url.substring(0,Math.min(60,url.length())));c.disconnect();return null;}
            String r=rs(c.getInputStream());c.disconnect();return r;
        }catch(Exception e){flog("HTTP-GET 异常: "+e.getMessage());return null;}
    }
    public static String httpGetH(String url, String[][] hdrs) {
        try{ HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent","Mozilla/5.0");
            if(hdrs!=null)for(String[] h:hdrs)c.setRequestProperty(h[0],h[1]);
            if(c.getResponseCode()!=200){flog("HTTP-GETH "+c.getResponseCode()+" "+url.substring(0,Math.min(60,url.length())));c.disconnect();return null;}
            String r=rs(c.getInputStream());c.disconnect();return r;
        }catch(Exception e){flog("HTTP-GETH 异常: "+e.getMessage());return null;}
    }
    public static String httpPost(String url, String body, String ct) {
        try{ HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestMethod("POST");
            c.setDoOutput(true);c.setRequestProperty("Content-Type",ct!=null?ct:"application/json");
            OutputStream os=c.getOutputStream();os.write(body.getBytes("UTF-8"));os.flush();os.close();
            if(c.getResponseCode()!=200){flog("HTTP-POST "+c.getResponseCode()+" "+url.substring(0,Math.min(60,url.length())));c.disconnect();return null;}
            String r=rs(c.getInputStream());c.disconnect();return r;
        }catch(Exception e){flog("HTTP-POST 异常: "+e.getMessage());return null;}
    }
    public static boolean dl(String url, String path) {
        try{ HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setRequestMethod("GET");
            if(c.getResponseCode()!=200){flog("DL "+c.getResponseCode()+" "+url.substring(0,Math.min(60,url.length())));c.disconnect();return false;}
            File f=new File(path);f.getParentFile().mkdirs();
            InputStream is=c.getInputStream();FileOutputStream fos=new FileOutputStream(f);
            byte[] b=new byte[8192];int n;while((n=is.read(b))!=-1)fos.write(b,0,n);
            fos.close();is.close();c.disconnect();flog("DL 完成: "+path.substring(Math.max(0,path.length()-40)));
            return f.exists();
        }catch(Exception e){flog("DL 异常: "+e.getMessage());return false;}
    }
    private static String rs(InputStream is) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
        while((n=is.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8");
    }
    public static String ft(long ms){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(ms));}
    public static String fts(long ms){return new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(ms));}
    public static String ue(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    public static int dp(Context ctx,int dp){if(density<=0&&ctx!=null)density=ctx.getResources().getDisplayMetrics().density;if(density<=0)density=3.0f;return (int)(dp*density+0.5f);}
    public static String stt(String t){if(t==null)return"";return t.replaceAll("<[^>]+>","").replaceAll("&[a-z]+;","").replaceAll("\\s+"," ").trim();}
    public static int pc(String hex){return android.graphics.Color.parseColor("#"+hex);}
    private static String vdCache;
    public static String vdPath(){
        if(vdCache!=null)return vdCache;
        try{
            vdCache="/data/user/0/com.tencent.mm/files/leshao_voice";
            new File(vdCache).mkdirs();
        }catch(Throwable e){}
        return vdCache;
    }
}
