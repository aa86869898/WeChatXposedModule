package com.leshao.v3.hook;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.model.ContactCard;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.MainHook;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「自动扫码进群」—— 参照《微信群聊二维码_全自动检测加群_完整手册.md》及
 * 《自动进群失败_诊断报告.md》方案 A 实现。
 *
 * <p>链路（诊断报告方案 A，纯后台可靠）：后台监听群聊图片消息入库（f9.Bb）→
 * 提取本地图片路径（未落地则轮询等待微信自动下载，ex0.k0.k2 反查 + 缓存对象双通道）→
 * BitmapFactory 采样解码 → {@code com.tencent.mm.pluginsdk.ui.tools.v16.a.a(Bitmap,int[],boolean)}
 * 微信原生同步解码（不依赖 wk5.g0 实例、不依赖 RecogQBarOfImageFileEvent 事件订阅者）→
 * 结果元素 t16.i0.e 拿码值 → {@link #isGroupQr} 判定 → 发 DealQBarStrEvent 让微信走
 * A8Key(Cgi 106) 入群流程 → 自动点「加入群聊」确认 → 进群回执。</p>
 *
 * <p>保留手册原事件链作为辅助通道：hookScanResult（wk5.g0.b）/ 事件层 IEvent.e /
 * 日志层 Mars.xlog.Log / 码值总闸 v74.v.g，当微信自己产生群码时也能捕获。</p>
 *
 * <p>类名兼容策略：消息存储类走 {@link VersionCompat#findMsgStorageClass}（8.0.78 已验证），
 * v16.a / ex0.k0 为 3180 版本速查类名（诊断报告 §五），QBarStringHandler / ImageScanCodeManager
 * 走 DexKit 字符串锚点动态定位。autogen 事件类名称稳定。</p>
 *
 * <p>已内置风控：每日入群上限、随机延迟、同一群码只处理一次、白名单群过滤、
 * 自动点确认带 60s 触发保护窗（仅最近发过入群请求时才点，避免误点其它弹窗）。</p>
 */
public final class AutoGroupQrHook {

    public static final String TAG = "AutoGroupQr";

    private static final String K_TODAY = "gqr_today";
    private static final String K_TODAY_COUNT = "gqr_today_count";

    // 群二维码前缀（手册 §4.1，来自 com.tencent.mm.plugin.scanner.z0 b[]/e[]/f[]，硬编码）
    private static final String[] GROUP_PREFIX = {
            "https://weixin.qq.com/g/",      // 普通群（最常见）
            "http://weixin.qq.com/g/",
            "https://c.weixin.com/g/",       // 短链
            "http://c.weixin.com/g/",
            "https://work.weixin.qq.com/gm/",// 企业微信群
            "https://work.weixin.qq.com/m/",
    };

    // 自动点确认文案白名单 / 保护词（手册 §6.4(3)）
    private static final String[] JOIN_WORDS = {"加入群聊", "进入群聊", "加入该群", "确认加入", "加入群"};
    private static final String[] DENY_WORDS = {"支付", "转账", "收款", "输入密码", "好友验证"};
    // 需验证群申请页自动提交文案
    private static final String[] APPLY_WORDS = {"提交申请", "申请加入", "发送申请"};

    /** v3.0.509: 群链接 H5 页的「加入群聊」是 DOM 按钮，View 树遍历找不到，需注入 JS 点击。
     *  评分选中具体按钮（button/weui-btn 优先、面积小者胜），避免点到外层容器 DIV。 */
    private static final String JOIN_JS =
            "(function(){function best(d){var b=null;try{var es=d.querySelectorAll('button,a,div,span,p,uni-button,uni-view,[role=button]');" +
            "for(var i=0;i<es.length;i++){var e=es[i];var t=(e.innerText||e.textContent||'').replace(/\\s/g,'');" +
            "if(t.indexOf('\u52a0\u5165\u7fa4\u804a')<0&&t.indexOf('\u7533\u8bf7\u52a0\u5165')<0&&t.indexOf('\u8fdb\u5165\u7fa4\u804a')<0&&t.indexOf('\u52a0\u5165\u8be5\u7fa4')<0)continue;" +
            "var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;var s=0;var tg=(e.tagName||'').toLowerCase();" +
            "if(tg==='button'||tg==='uni-button'||e.getAttribute('role')==='button')s+=1000;" +
            "if((e.className||'').indexOf('weui-btn')>=0||(e.className||'').indexOf('btn')>=0)s+=500;" +
            "s-=Math.round(r.width*r.height);if(!b||s>b[0])b=[s,e];}}catch(x){}return b?b[1]:null;}" +
            "function clickDoc(d){try{var e=best(d);if(e){e.click();return true;}}catch(x){}return false;}" +
            "try{if(clickDoc(document))return 'clicked';var fs=document.querySelectorAll('iframe');" +
            "for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d&&clickDoc(d))return 'clicked-iframe';}catch(e){}}" +
            "}catch(e){}return 'notfound';})();";

    /** v3.0.541: 查找「进入群聊/加入群聊」按钮并返回其位置（CSS 坐标）+ 视口信息。
     *  格式 left,top,width,height|TAG|class|iw=innerWidth|ih=innerHeight|dpr=devicePixelRatio；
     *  iframe 内坐标已加 iframe 偏移；找不到返回 notfound。
     *  评分策略：优先 button/uni-button/a/[role=button]（+1000）、class 含 weui-btn/btn（+500），
     *  再减面积分（r.width*r.height），确保选中具体按钮而非含「进入群聊」文案的外层容器 DIV。
     *  v3.0.541: 附加 innerWidth/devicePixelRatio，Java 侧用 WebView 实际宽度/CSS 视口宽
     *  计算真实坐标比例（WebView.getScale() 在 8.0.79 返回 3.18 错误值，导致点击落空）。 */
    private static final String FIND_BTN_JS =
            "(function(){function best(d){var b=null;try{var es=d.querySelectorAll('button,a,div,span,p,uni-button,uni-view,[role=button]');" +
            "for(var i=0;i<es.length;i++){var e=es[i];var t=(e.innerText||e.textContent||'').replace(/\\s/g,'');" +
            "if(t.indexOf('\u52a0\u5165\u7fa4\u804a')<0&&t.indexOf('\u7533\u8bf7\u52a0\u5165')<0&&t.indexOf('\u8fdb\u5165\u7fa4\u804a')<0&&t.indexOf('\u52a0\u5165\u8be5\u7fa4')<0)continue;" +
            "var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;var s=0;var tg=(e.tagName||'').toLowerCase();" +
            "if(tg==='button'||tg==='uni-button'||e.getAttribute('role')==='button')s+=1000;" +
            "if((e.className||'').indexOf('weui-btn')>=0||(e.className||'').indexOf('btn')>=0)s+=500;" +
            "s-=Math.round(r.width*r.height);if(!b||s>b[0])b=[s,e];}}catch(x){}return b?b[1]:null;}" +
            "function meta(){try{return '|iw='+Math.round(window.innerWidth)+'|ih='+Math.round(window.innerHeight)+'|dpr='+(window.devicePixelRatio||1);}catch(x){return '';}}" +
            "function find(d){try{var e=best(d);if(e){var r=e.getBoundingClientRect();" +
            "return Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+','+Math.round(r.height)+'|'+e.tagName+'|'+String(e.className||'').substring(0,80);}}catch(x){return '';}}" +
            "function findIframe(f,d){try{var e=best(d);if(e){var fr=f.getBoundingClientRect();var r=e.getBoundingClientRect();" +
            "return Math.round(r.left+(fr?fr.left:0))+','+Math.round(r.top+(fr?fr.top:0))+','+Math.round(r.width)+','+Math.round(r.height)+'|'+e.tagName+'|'+String(e.className||'').substring(0,80);}}catch(x){return '';}}" +
            "var s=find(document);if(s)return s+meta();var fs=document.querySelectorAll('iframe');" +
            "for(var j=0;j<fs.length;j++){try{var f=fs[j];var d=f.contentDocument;if(d){s=findIframe(f,d);if(s)return 'iframe:'+s+meta();}}catch(e){}}" +
            "return 'notfound';})();";

    /** v3.0.509: 点击后检查按钮状态：返回 TAG:class（前 100 字符），gone 表示按钮已消失（已触发跳转）。
     *  同样用评分选中具体按钮，避免匹配到外层容器 DIV 而误判「按钮还在」。 */
    private static final String CHECK_BTN_JS =
            "(function(){function best(d){var b=null;try{var es=d.querySelectorAll('button,a,div,span,p,uni-button,uni-view,[role=button]');" +
            "for(var i=0;i<es.length;i++){var e=es[i];var t=(e.innerText||e.textContent||'').replace(/\\s/g,'');" +
            "if(t.indexOf('\u52a0\u5165\u7fa4\u804a')<0&&t.indexOf('\u7533\u8bf7\u52a0\u5165')<0&&t.indexOf('\u8fdb\u5165\u7fa4\u804a')<0&&t.indexOf('\u52a0\u5165\u8be5\u7fa4')<0)continue;" +
            "var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;var s=0;var tg=(e.tagName||'').toLowerCase();" +
            "if(tg==='button'||tg==='uni-button'||e.getAttribute('role')==='button')s+=1000;" +
            "if((e.className||'').indexOf('weui-btn')>=0||(e.className||'').indexOf('btn')>=0)s+=500;" +
            "s-=Math.round(r.width*r.height);if(!b||s>b[0])b=[s,e];}}catch(x){}return b?b[1]:null;}" +
            "function st(d){try{var e=best(d);if(e)return e.tagName+':'+String(e.className||'').substring(0,100);}catch(x){}return '';}" +
            "var s=st(document);if(s)return s;var fs=document.querySelectorAll('iframe');" +
            "for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d){s=st(d);if(s)return 'iframe:'+s;}}catch(e){}}" +
            "return 'gone';})();";

    /** v3.0.507: 遍历主文档+iframe，列出所有可见且带文案的可点元素（标签|文案|坐标|属性）。
     *  用于实机查看「进入群聊」按钮的 DOM 真身和位置，最多输出 2500 字符。 */
    private static final String DUMP_DOM_JS =
            "(function(){function esc(s){return (s||'').replace(/[|;]/g,' ').replace(/\\s+/g,'').substring(0,24);}" +
            "function walk(d,out){try{var es=d.querySelectorAll('button,a,div,span,p,uni-button,uni-view,[role=button],input');" +
            "for(var i=0;i<es.length;i++){var e=es[i];var r=e.getBoundingClientRect();" +
            "if(r.width<2||r.height<2)continue;var t=esc(e.innerText||e.textContent);" +
            "if(!t)continue;var oc=e.getAttribute('onclick');var href=e.getAttribute('href');" +
            "out.push(e.tagName+'|'+t+'|'+Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+'x'+Math.round(r.height)+'|oc='+(oc?1:0)+(href?'|'+href.substring(0,40):''));}" +
            "var fs=d.querySelectorAll('iframe');for(var j=0;j<fs.length;j++){try{var fd=fs[j].contentDocument;if(fd)walk(fd,out);}catch(e){}}}" +
            "catch(e){out.push('ERR:'+e.message);}return out;}" +
            "try{var o=[];walk(document,o);var all=o.join(';');return 'DOM('+o.length+'):'+all.substring(0,2500);}" +
            "catch(e){return 'DOM_ERR:'+e.message;}})();";

    private static final Pattern P_PATH = Pattern.compile("path=\"([^\"]+)\"");
    /** CDN 图片直链（msg 字段/XML 中提取）。 */
    private static final Pattern P_CDN_URL = Pattern.compile("https?://[^\"'\\s<]+");
    /** CDN aeskey（32 位 hex）。 */
    private static final Pattern P_AESKEY = Pattern.compile("aeskey=[\"']?([0-9a-fA-F]{32})");
    /** 微信缩略图虚拟路径前缀：ImgInfo2.thumbImgPath 占位符（非真实路径）。 */
    private static final String THUMBNAIL_PREFIX = "THUMBNAIL_DIRPATH://";
    /** 微信 image2 根目录缓存（如 .../MicroMsg/<account>/image2/），来自 pf3.a.b()（8.0.79）。 */
    private static volatile String sImage2Root = null;
    /** no3.u0（C2CImgPathFeatureService，实现 ec0.b）实例缓存，8.0.79 官方缩略图路径服务。 */
    private static volatile Object sImgPathSvc = null;
    /** 微信 ScanImageUtil（v16.a 或混淆替代类）及其静态解码方法，DexKit 定位后缓存。 */
    private static volatile Class<?> sScanImgUtilClass = null;
    private static volatile Method sScanImgUtilMethod = null;
    /** 8.0.79 新扫码引擎 v26.y0（WxFileDecodeQueue 单例，dex 字段名 h）及其同步解码方法 d(Context,List)。 */
    private static volatile Class<?> sFileDecodeClass = null;
    private static volatile Object sFileDecodeEngine = null;
    private static volatile Method sFileDecodeMethod = null;

    // 去重：同一群码进群成功前可重复触发（多消息天然重试），成功后永久去重 /
    // 同一消息只识别一次 / 同一群只记一次回执
    /** 进群成功记录：群码 → 成功时间。v3.0.544: 改为 10 秒短窗口，
     *  只防同批消息连发重复弹页；已加入群长期过滤由 isCodeAlreadyJoined（群码→群列表）负责，
     *  被踢出后重新扫码立即允许再次进群。 */
    private static final Map<String, Long> DONE_CODE = new ConcurrentHashMap<>();
    private static final long DONE_CODE_TTL = 10L * 1000L;
    private static final Set<Long> DONE_MSG = ConcurrentHashMap.newKeySet();
    private static final Set<String> DONE_ROOM = ConcurrentHashMap.newKeySet();
    /** 已安排过延迟重试的消息（ScanImageUtil 类未就绪时只补试一次）。 */
    private static final Set<Long> RETRY_MSG = ConcurrentHashMap.newKeySet();
    /** v3.0.538: 相同群码短时间去重（10s），避免同一批消息瞬间重复触发；超过窗口可重试。 */
    private static volatile String sLastCode = null;
    private static volatile long sLastCodeTime = 0L;
    /** v3.0.504: 已注入过 JS 的 WebView hashCode → 注入时间，60s 内不重复注入（防重建复用）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> sInjectedWebViews =
            new java.util.concurrent.ConcurrentHashMap<>();
    // v3.0.511: 提前安装类 hook 的防重/重试状态机。
    //   sXxxInstalling = 安装锁（防并发重复安装）；sXxxHooked = 已成功标志（失败可重试）。
    private static final java.util.concurrent.atomic.AtomicBoolean sDialogInstalling =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sDialogHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sFgInstalling =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sFgHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sMsgInstalling =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sMsgHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ============ v3.0.510 后台静默入群 ============
    // 微信是否有前台 Activity（Application ActivityLifecycleCallbacks 维护）
    private static volatile boolean sWeChatForeground = true;
    // 本次入群请求是否走静默模式（v3.0.538 起强制 true：纯后台加群，不打扰用户）
    private static volatile boolean sSilentJoin = true;
    // 静默模式有效期：超过 60s 未进群则自动失效，避免影响后续请求
    private static volatile long sSilentJoinUntil = 0L;

    /** 注册 Activity 生命周期追踪，维护 sWeChatForeground。 */
    private static void initForegroundTracker() {
        if (sFgHooked.get()) return;
        if (!sFgInstalling.compareAndSet(false, true)) return;
        try {
            android.content.Context app = ContextManager.getAppContext();
            if (app == null) return;
            app = app.getApplicationContext();
            if (!(app instanceof android.app.Application)) return;
            ((android.app.Application) app).registerActivityLifecycleCallbacks(
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityResumed(android.app.Activity a) { sWeChatForeground = true; }
                        @Override public void onActivityPaused(android.app.Activity a) { sWeChatForeground = false; }
                        @Override public void onActivityCreated(android.app.Activity a, Bundle b) {}
                        @Override public void onActivityStarted(android.app.Activity a) {}
                        @Override public void onActivityStopped(android.app.Activity a) {}
                        @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle b) {}
                        @Override public void onActivityDestroyed(android.app.Activity a) {}
                    });
            LogWriter.log(TAG, "前台追踪已注册 app=" + app.getClass().getName());
            sFgHooked.set(true);
        } catch (Throwable t) {
            LogWriter.log(TAG, "initForegroundTracker err: " + t);
        } finally {
            sFgInstalling.set(false);
        }
    }
    /** v3.0.507: 已装触摸探针的 WebView（identityHashCode），只装一次。 */
    private static final Set<Integer> sSpyWebViews = ConcurrentHashMap.newKeySet();

    private static volatile boolean sEnabled = false;
    private static volatile int sMaxPerDay = 5;
    private static volatile int sDelayMax = 15;
    private static volatile boolean sAutoApply = true;
    private static volatile String sWhitelist = "";
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;
    /** 最近一次发 DealQBarStrEvent 的时间戳：自动点确认的保护窗。 */
    private static volatile long sLastDealQBarTime = 0L;

    // ============ v3.0.513 重复点击/重复发送去重 ============
    // v3.0.538: 同一群码 10s 内只点击一次（防同一次页面多轮扫描重复点）；
    // v3.0.544: 成功进群后仅 10s 防同批连发；长期过滤由 isCodeAlreadyJoined 负责。
    // 同一群 10s 内只发送一次 addchatroommember。
    // 点击守卫在真正执行点击动作（clickText/clickJoin/JOIN_JS）前检查并占位；
    // 发送守卫在 task.b() 前检查，两条发送路径（JOIN-API 自动发送、joinGroupViaApi）共享。
    private static volatile String sCurrentCode = null;
    private static final Map<String, Long> CLICK_HISTORY = new ConcurrentHashMap<>();
    private static final Map<String, Long> SEND_HISTORY = new ConcurrentHashMap<>();

    private static boolean canJoinClick(String code) {
        if (code == null) code = sCurrentCode;
        if (code == null) return false;
        if (isDoneCode(code)) return false;   // v3.0.544: 仅 10s 短窗口防同批连发
        long now = System.currentTimeMillis();
        Long last = CLICK_HISTORY.get(code);
        if (last != null && now - last < 10000L) return false;
        CLICK_HISTORY.put(code, now);
        return true;
    }

    /** v3.0.544: 群码是否在「进群成功短窗口」内。仅 10 秒 TTL 防同批连发，
     *  超时后即使成功过也允许重新扫码进群（已加入群由 isCodeAlreadyJoined 拦截）。 */
    private static boolean isDoneCode(String code) {
        if (code == null) return false;
        Long done = DONE_CODE.get(code);
        if (done == null) return false;
        return System.currentTimeMillis() - done < DONE_CODE_TTL;
    }

    private static boolean canSendJoin(String room) {
        if (room == null) return false;
        long now = System.currentTimeMillis();
        Long last = SEND_HISTORY.get(room);
        if (last != null && now - last < 10000L) return false;
        SEND_HISTORY.put(room, now);
        return true;
    }

    private static final Random sRandom = new Random();
    // v3.0.540: 入群请求专用总线（dealQBarStr 必须不被扫描任务阻塞）。
    private static final ExecutorService sBus = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leshao-gqr-bus");
        t.setDaemon(true);
        return t;
    });
    // v3.0.540: 图片扫描/轮询专用线程池。原来 waitAndScan 占住 sBus 单线程长达 20s+，
    // 导致命中群码后 dealQBarStr 排队数分钟才执行 → 重复扫码、入群严重延迟。
    private static final ExecutorService sScanBus = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leshao-gqr-scan");
        t.setDaemon(true);
        return t;
    });
    /** msgId → e9 对象缓存（图片下载完成后，缓存对象可能被微信更新 content，作为反查兜底）。 */
    private static final Map<Long, Object> sMsgCache = new ConcurrentHashMap<>();

    /** v3.0.535: 临时原图文件表 msgId → 临时保存的群二维码原图（进群成功后删除）。
     *  只有 CDN 兜底下载的原图才进这张表；微信已落地的原图不属于本模块，不删除。 */
    private static final Map<Long, File> sTempFiles = new ConcurrentHashMap<>();

    /** v3.0.535: 最近一次触发入群流程的 msgId（进群成功后按它清理临时原图）。 */
    private static volatile long sCurrentMsgId = 0L;

    /** v3.0.539: 静默入群的 H5 页 Activity 与「已安排退后台」标记。
     *  点击成功确认后立即退后台；25s 超时保险退；只退一次。 */
    private static volatile Activity sSilentActivity = null;
    private static volatile boolean sSilentBackDone = false;

    private AutoGroupQrHook() {}

    /** 双通道日志：同时写模块文件 + LSPosed 日志，方便排查「最后按钮没点到」。 */
    private static void log2(String msg) {
        try {
            LogWriter.log(TAG, msg);
        } catch (Throwable ignored) {}
        try {
            XposedBridge.log("LeShaoV3/AutoGroupQr: " + msg);
        } catch (Throwable ignored) {}
    }

    // ==================== 配置 ====================

    /** 从 prefs 刷新运行时配置（开关/上限/延迟/自动申请/白名单）。 */
    public static void updateConfig() {
        try {
            sEnabled = AutoGroupQrConfig.isEnabled();
            sMaxPerDay = AutoGroupQrConfig.getMaxPerDay();
            sDelayMax = AutoGroupQrConfig.getDelayMax();
            sAutoApply = AutoGroupQrConfig.isAutoApply();
            sWhitelist = AutoGroupQrConfig.getWhitelist();
            LogWriter.log(TAG, "updateConfig enabled=" + sEnabled + " maxPerDay=" + sMaxPerDay
                    + " delayMax=" + sDelayMax + " autoApply=" + sAutoApply);
        } catch (Throwable t) {
            LogWriter.log(TAG, "updateConfig err: " + t.getMessage());
        }
    }

    // ==================== Hook 安装 ====================

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        updateConfig();
        if (sHooked) return;
        // ① 消息监听（f9.Bb）+ 确认页/静默退后台 hook 不依赖 DexKit：立即安装，
        //    避免错过启动早期/扫描期间的群图片消息与自动跳转。
        //    （实机教训：DexKit 全量扫描约 26s，若等扫描完再装，期间群图片全部漏掉，
        //      且 MMWebViewUI 跳转发生在 hook 就绪前 → 静默入群完全失效）
        Thread msgThread = new Thread(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    initForegroundTracker();
                    hookJoinDialog(cl);          // Activity/Dialog 确认页 + 静默退后台
                    hookMsgInsert(cl);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "early hooks attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-gqr-msg");
        msgThread.setDaemon(true);
        msgThread.start();
        // ② 其余依赖 DexKit 字符串锚点的 Hook（识别结果截胡/事件层/日志层/码值总闸/UI 回执）
        //    等扫描完成后再装。
        try {
            DexKitHelper.addPostScanCallback(() -> {
                Thread t = new Thread(() -> {
                    for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                        try {
                            installScanHooks(cl);
                            sHooked = true;
                            LogWriter.log(TAG, "scan hooks installed attempt=" + attempt);
                            return;
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "install attempt " + attempt + " failed: " + e.getMessage());
                            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        }
                    }
                }, "leshao-gqr-hook");
                t.setDaemon(true);
                t.start();
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook schedule err: " + t.getMessage());
        }
        // ③ v3.0.503 兜底：不依赖 DexKit postScanCallback（个别进程/时序下该回调可能不触发，
        //    导致 Dialog/Activity 自动点确认等②类钩子从未安装 → 识别成功但最后一步没发生）。
        //    8 秒后直接尝试一次完整安装；若 ② 已成功（sHooked=true）则静默跳过。
        Thread fallback = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
            if (sHooked) return;
            for (int attempt = 0; attempt < 5 && !sHooked; attempt++) {
                try {
                    installScanHooks(cl);
                    sHooked = true;
                    LogWriter.log(TAG, "scan hooks installed via fallback attempt=" + attempt);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "fallback install attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-gqr-fallback");
        fallback.setDaemon(true);
        fallback.start();
    }

    private static void installScanHooks(ClassLoader cl) throws Throwable {
        // v3.0.510: 注册前台追踪（静默入群用），尽早注册避免漏掉首帧
        initForegroundTracker();
        // DexKit 扫描完成后 Tinker 真实 CL 已就绪，补装一次消息监听（双保险，
        // 避免首次安装只挂到 base.apk 平行副本导致零捕获）
        hookMsgInsert(cl);
        hookScanResult(cl);
        hookRecogResultEvent(cl);
        hookQrLog(cl);
        hookQBarStringHandler(cl);
        hookJoinDialog(cl);
        hookLauncherUI(cl);
        hookVerifyUI(cl);
        hookJoinApi(cl);
        hookA8KeyResult(cl);
        hookMyRoomListSync();
        try { ensureScanImgUtil(cl); } catch (Throwable ignored) {}
        LogWriter.log(TAG, "installScanHooks done");
    }

    /** v3.0.546: hook 我的群列表 t73.n.c()，群列表刷新时同步清理已不存在的进群记录。
     *  退群/被移出群聊后，微信会刷新联系人数据库（rcontact），t73.n.c() 返回的
     *  「我的群列表」不再包含该群。每次列表刷新时对比 JoinRecordStore，
     *  发现记录的群已不在列表中 → 实时删除记录，保证重新扫码可再次进群。 */
    private static void hookMyRoomListSync() {
        try {
            // v3.0.547: 不再硬编码 t73.n（新版微信已变），用字符串锚点 DexKit 动态定位群成员/群列表逻辑类
            Class<?> c = null;
            try {
                List<String> clsNames = DexKitHelper.findClassesByString(sCl, "MicroMsg.ChatroomMembersLogic");
                for (String cn : clsNames) {
                    try { c = sCl.loadClass(cn); if (c != null) break; } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "hookMyRoomListSync: DexKit 定位失败: " + t);
            }
            if (c == null) {
                LogWriter.log(TAG, "hookMyRoomListSync: 未找到 ChatroomMembersLogic 类（群列表刷新清理降级，由扫码时 DB 自愈兜底）");
                return;
            }
            Method target = null;
            for (Method m : c.getDeclaredMethods()) {
                if ("c".equals(m.getName()) && Modifier.isStatic(m.getModifiers())
                        && List.class.isAssignableFrom(m.getReturnType())) {
                    target = m;
                    break;
                }
            }
            if (target == null) { LogWriter.log(TAG, "hookMyRoomListSync: t73.n.c 未找到"); return; }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object r = param.getResult();
                        if (!(r instanceof List)) return;
                        Set<String> live = new HashSet<>();
                        for (Object o : (List<?>) r) {
                            if (o instanceof String) live.add((String) o);
                        }
                        for (JoinRecordStore.Record rec : JoinRecordStore.getAll()) {
                            if (rec.room == null || rec.room.isEmpty() || live.contains(rec.room)) continue;
                            List<String> codes = JoinRecordStore.removeRoom(rec.room);
                            for (String code : codes) {
                                if (code != null && !code.isEmpty()) DONE_CODE.remove(code);
                            }
                            LogWriter.log(TAG, "★★ 群列表刷新：记录的群已不在列表中，实时清除进群记录 room="
                                    + rec.room + " codes=" + codes);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "已 hook 我的群列表 t73.n.c() 实时同步退群记录");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookMyRoomListSync err: " + t);
        }
    }

    /** v3.0.546: 退群/被移出群聊系统消息（type=10000）实时清理进群记录。
     *  被移出群聊或退群时，微信会收到 type=10000 系统消息，内容含
     *  「移出群聊/退出群聊/已将你移出」等关键词。此时立即删除 JoinRecordStore
     *  中该群的全部记录（群码→群映射）并清理 DONE_CODE，保证重新收到群码时能再次进群。 */
    private static void handleKickedSysMsg(String talker, Object msg) {
        try {
            String content = readContent(msg);
            if (content == null) content = "";
            boolean kicked = content.contains("移出群聊") || content.contains("退出群聊")
                    || content.contains("已将你移出") || content.contains("你已被移出")
                    || content.contains("将你移出") || content.contains("已退出群聊");
            if (!kicked) return;  // 其他系统消息（如进群成功）不处理
            List<String> codes = JoinRecordStore.removeRoom(talker);
            if (codes != null) {
                for (String code : codes) {
                    if (code != null && !code.isEmpty()) DONE_CODE.remove(code);
                }
            }
            LogWriter.log(TAG, "★★ 检测到退群/被移出群聊，实时清除进群记录 room=" + talker
                    + " codes=" + codes + " content=" + content);
        } catch (Throwable t) {
            LogWriter.log(TAG, "handleKickedSysMsg err: " + t);
        }
    }

    /** ① 后台消息监听：消息入库 hook（v3.0.468 起微信 8.0.7x 插入入口 = f9.Nb(e9,boolean)，
     *  旧名 Bb 已删除；Mb(e9) 是包装方法。方法名数组候选 + 字符串锚点兜底）。
     *  遍历所有候选 ClassLoader（Tinker 真实 CL / base CL / AppContext CL），
     *  避免只挂在一个平行副本类上导致零捕获（本次实机已踩坑）。 */
    private static void hookMsgInsert(ClassLoader cl) {
        if (sMsgHooked.get()) {
            LogWriter.log(TAG, "hookMsgInsert 已安装过，跳过（防重复 hook）");
            return;
        }
        if (!sMsgInstalling.compareAndSet(false, true)) return;
        try {
        int totalHooked = 0;
        String[] names = {"Nb", "Mb", "Bb", "insertMsgInfo", "processAddMsg"};
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> f9 = VersionCompat.findMsgStorageClass(loader);
                if (f9 == null) {
                    LogWriter.log(TAG, "消息存储类未找到 loader=" + HookUtil.loaderName(loader));
                    continue;
                }
                int hooked = 0;
                for (Method m : f9.getDeclaredMethods()) {
                    boolean nameHit = false;
                    for (String n : names) {
                        if (n.equals(m.getName())) { nameHit = true; break; }
                    }
                    if (!nameHit) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 1 || pts[0] == null) continue;
                    String p0 = pts[0].getName();
                    if (p0.endsWith(".e9")) { /* ok */ } else if (p0.contains("MsgInfo")) { /* ok */ } else {
                        continue;
                    }
                    final String fP0 = p0;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args.length < 1 || p.args[0] == null) return;
                                onNewMsg(p.args[0]);
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "onNewMsg cb err: " + t);
                            }
                        }
                    });
                    hooked++;
                    LogWriter.log(TAG, "已 hook 消息入库 " + m.getName() + "(" + pts.length + " args) p0=" + fP0
                            + " loader=" + HookUtil.loaderName(loader));
                }
                totalHooked += hooked;
            } catch (Throwable t) {
                LogWriter.log(TAG, "hookMsgInsert loader err: " + t.getMessage());
            }
        }
        LogWriter.log(TAG, "消息监听安装合计: " + totalHooked + " 个消息入库方法");
        sMsgHooked.set(true);
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookMsgInsert err: " + t.getMessage());
        } finally {
            sMsgInstalling.set(false);
        }
    }

    private static void onNewMsg(Object msg) {
        // 诊断报告要求：回调最前面打日志，确认 hook 真的进来
        LogWriter.log(TAG, "onNewMsg enter msg=" + (msg == null ? "null" : msg.getClass().getName()));
        if (!sEnabled) return;
        try {
            String talker = getTalker(msg);
            if (talker == null) { LogWriter.log(TAG, "onNewMsg talker=null, return"); return; }
            boolean isChatroom = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom");
            if (!isChatroom) {
                // 单聊/文件传输助手也可能收到群二维码图片，不再拦截，统一尝试识别
                LogWriter.log(TAG, "onNewMsg 非群聊图片(仍尝试识别) talker=" + talker);
            }
            int type = ((Number) XposedHelpers.callMethod(msg, "getType")).intValue();
            // v3.0.581: 只处理明确收到的消息(isSend==0)。读不到(-1)或自己发出的一律跳过，
            // 防止模块自发消息/本地插入消息触发扫码进群逻辑。
            int isSend = -1;
            try { isSend = (Integer) XposedHelpers.callMethod(msg, "z0"); } catch (Throwable ignored) {}
            if (isSend != 0) {
                if (type != 10000) return;
                // 系统提示(退群/被移出)仍需处理: 落到下面 handleKickedSysMsg
            }
            // v3.0.546: 检测退群/被移出群聊系统消息（type=10000），实时删除该群的进群记录，
            // 这样被踢后重新扫码必然允许再次进群（不依赖群列表缓存/群成员查询）。
            if (type == 10000) {
                handleKickedSysMsg(talker, msg);
                return;
            }
            if (type != 3) { LogWriter.log(TAG, "onNewMsg type=" + type + " 非图片, return"); return; }
            long msgId = getMsgId(msg);
            if (!inWhitelist(talker)) { LogWriter.log(TAG, "onNewMsg 白名单拦截 talker=" + talker); return; }
            if (!DONE_MSG.add(msgId)) return;                       // 去重
            // 缓存消息对象（≤200 条，图片落地轮询兜底用）
            if (sMsgCache.size() > 200) sMsgCache.clear();
            sMsgCache.put(msgId, msg);

            String path = getImgPath(msg);
            String realPath = resolveImgPath(sCl, path, msg);
            String origPath = toOriginalPathStrict(realPath);
            LogWriter.log(TAG, "★ 群图片消息 " + talker + " id=" + msgId + " path=" + path
                    + " real=" + realPath + " orig=" + origPath);
            if (origPath == null || !new File(origPath).exists()) {
                // 原图未落地（虚拟路径未解析 / 仅缩略图 th_ 已下载而原图未下载）：
                // 后台轮询等待微信下载原图，超时转 CDN 直链下载兜底。
                // 注意不能用 toOriginalPath 的回退缩略图版本判断 —— 缩略图存在不等于原图存在，
                // 拿缩略图去解码二维码必然失败（这是扫码进群"最后一步不点击"的根因）。
                LogWriter.log(TAG, "原图未落地，启动轮询等待(最多20s+CDN兜底): msgId=" + msgId);
                final long fMsgId = msgId;
                final String fTalker = talker;
                final String fUri = path;
                final Object fMsg = msg;
                sScanBus.execute(() -> waitAndScan(sCl, fTalker, fMsgId, fUri, fMsg));
                return;
            }
            final String fPath = realPath;
            final long fMsgId2 = msgId;
            final String fTalker2 = talker;
            sScanBus.execute(() -> scanImage(sCl, fTalker2, fMsgId2, fPath));
        } catch (Throwable t) {
            LogWriter.log(TAG, "onNewMsg err: " + t);
        }
    }

    /** 8.0.78 已验证：N0() 返回 talker（MessageHook/WanQunGroupHook 同款）；p0() 为手册样本版本兜底。 */
    private static String getTalker(Object msg) {
        try { return (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "p0"); } catch (Throwable ignored) {}
        return null;
    }

    /** 8.0.78 已验证：getMsgId() 返回 msgId（MessageHook/WanQunGroupHook 同款）；l0() 为手册样本版本兜底。 */
    private static long getMsgId(Object msg) {
        try { return ((Number) XposedHelpers.callMethod(msg, "getMsgId")).longValue(); } catch (Throwable ignored) {}
        try { return ((Number) XposedHelpers.callMethod(msg, "l0")).longValue(); } catch (Throwable ignored) {}
        return 0L;
    }

    /** 读取消息内容 XML（getContent → j() → 字段兜底）。 */
    /** v3.0.537: 读取消息内容 XML。
     *  ① getContent/j() 方法调用（8.0.78 已验证）；
     *  ② 反射遍历 String 字段：只要值含 cdnurl/aeskey/以 < 开头都视为消息内容（8.0.79 主 dex
     *  storage.e9 的 content 字段被 R8 混淆为 d/Y/Z，方法名 getContent 可能不存在）。 */
    private static String readContent(Object msg) {
        try { return (String) XposedHelpers.callMethod(msg, "getContent"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "j"); } catch (Throwable ignored) {}
        try {
            for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v == null) continue;
                    String s = String.valueOf(v);
                    if (s.length() > 0 && (s.startsWith("<") || s.contains("cdnurl") || s.contains("aeskey")
                            || s.contains("qpic.cn") || s.contains("wxqpic"))) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 从内容 XML 提取本地图片路径；只有 cdn 未落地时返回 null。 */
    static String extractPath(String xml) {
        if (xml == null) return null;
        Matcher m = P_PATH.matcher(xml);
        if (m.find()) {
            String p = m.group(1);
            if (p != null && !p.isEmpty()) return p;
        }
        return null;
    }

    /** ★ 3180 文档：x0() = field_imgPath + 内部兜底解析（最可靠，比正则解析 XML 更稳）。
     *  注意 x0() 第④兜底会拼一个路径返回（即使文件不存在），调用方必须 new File(path).exists() 校验。 */
    public static String getImgPath(Object msg) {
        if (msg == null) return null;
        try {
            String p = null;
            try { p = (String) XposedHelpers.callMethod(msg, "u0"); } catch (Throwable ignored) {}
            if (p == null || p.isEmpty()) {
                try { p = (String) XposedHelpers.callMethod(msg, "x0"); } catch (Throwable ignored) {}
            }
            if (p != null && !p.isEmpty()) return p;
        } catch (Throwable ignored) {}
        // 兜底：解析 XML
        String xml = readContent(msg);
        return extractPath(xml);
    }

    /** 把微信虚拟路径（THUMBNAIL_DIRPATH://th_<md5>）解析为真实文件路径；普通路径原样返回。
     *  8.0.79 逆向结论：THUMBNAIL_DIRPATH 是 ImgInfo2.thumbImgPath 的 VFS 占位符，
     *  真实路径 = image2根/<md5[0:2]>/<md5[2:4]>/th_<md5>。
     *  ① 官方 no3.u0（C2CImgPathFeatureService，ec0.b 实现）Hj(msg,pv5.e0.i,md5,"th_","",true,true) 最权威；
     *  ② 本地计算（pf3.a.b() 根）兜底。 */
    public static String resolveImgPath(ClassLoader cl, String path, Object msg) {
        if (path == null) return null;
        if (!path.startsWith(THUMBNAIL_PREFIX)) return path;
        String real = resolveByFeature(cl, msg, path);
        if (real != null && !real.isEmpty()) return real;
        return resolveByLocal(cl, path);
    }

    /** 无消息对象时的兼容重载（MsgReceiveHook 等场景）：本地路径直接返回，虚拟路径走本地兜底。 */
    public static String resolveImgPath(ClassLoader cl, String path) {
        return resolveImgPath(cl, path, null);
    }

    /** 反射微信官方 C2CImgPathFeatureService（no3.u0 → ec0.b 实现）：
     *  ni5.n0.c(no3.u0.class) → Hj(e9, pv5.e0.i, md5, "th_", "", true, true)。
     *  与微信 v51.l0.Q5 保存缩略图完全同链路，返回真实绝对路径。 */
    private static String resolveByFeature(ClassLoader cl, Object msg, String uri) {
        try {
            if (msg == null) return null;
            int idx = uri.indexOf("th_");
            if (idx < 0) return null;
            String hash = uri.substring(idx + 3);
            if (hash == null || hash.length() != 32) return null;
            // v3.0.566: 8.0.79 的 ni5.n0.c 要求先 initialize（否则抛 IllegalStateException）。
            // 微信主进程初始化 ni5.n0 是迟早的事，这里轮询等待初始化完成再取服务。
            Object svc = sImgPathSvc;
            if (svc == null) {
                Class<?> svcMgr = XposedHelpers.findClass("ni5.n0", cl);
                Throwable last = null;
                for (int attempt = 0; attempt < 10 && sImgPathSvc == null; attempt++) {
                    try {
                        svc = XposedHelpers.callStaticMethod(svcMgr, "c",
                                XposedHelpers.findClass("no3.u0", cl));
                        sImgPathSvc = svc;
                        LogWriter.log(TAG, "no3.u0 服务=" + (svc == null ? "null" : svc.getClass().getName()));
                        break;
                    } catch (Throwable t) {
                        last = t;
                        // 未初始化：等待微信完成 ni5.n0.d(...) 后重试
                        if (t.getMessage() != null
                                && t.getMessage().contains("please call initialize")) {
                            try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
                            continue;
                        }
                        LogWriter.log(TAG, "resolveByFeature 非初始化错误: " + t);
                        break;
                    }
                }
                if (sImgPathSvc == null && last != null
                        && !(last.getMessage() != null
                        && last.getMessage().contains("please call initialize"))) {
                    LogWriter.log(TAG, "resolveByFeature 取服务失败: " + last);
                }
                svc = sImgPathSvc;
            }
            if (svc == null) return null;
            Object biz = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("pv5.e0", cl), "i");
            Object real = XposedHelpers.callMethod(svc, "Hj", msg, biz, hash, "th_", "", true, true);
            if (real instanceof String && !((String) real).isEmpty()) {
                LogWriter.log(TAG, "no3.u0.Hj → " + real);
                return (String) real;
            }
            LogWriter.log(TAG, "no3.u0.Hj 返回空 uri=" + uri);
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveByFeature err: " + t);
        }
        return null;
    }

    /** 本地计算 image2 根：优先 ContextManager.getAppContext().getFilesDir() 遍历
     *  MicroMsg/<account>/image2（多开/分身也能拿到正确路径）；pf3.a.b()（8.0.79 VFS
     *  t7.b("image2") 官方根）兜底。再拼 md5 两级散列目录。 */
    private static String resolveByLocal(ClassLoader cl, String uri) {
        try {
            int idx = uri.indexOf("th_");
            if (idx < 0) return null;
            String hash = uri.substring(idx + 3);
            if (hash == null || hash.length() != 32) return null;
            String root = sImage2Root;
            if (root == null) {
                root = findImage2RootByContext();
                if (root == null) {
                    try {
                        Object r = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("pf3.a", cl), "b");
                        if (r instanceof String && !((String) r).isEmpty()) root = (String) r;
                    } catch (Throwable ignored) {}
                }
                if (root != null) {
                    sImage2Root = root.endsWith("/") ? root : root + "/";
                    LogWriter.log(TAG, "image2根=" + sImage2Root);
                }
            }
            if (root == null) return null;
            if (!root.endsWith("/")) root = root + "/";
            return root + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/th_" + hash;
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveByLocal err: " + t);
        }
        return null;
    }

    /** 通过微信应用 Context 遍历 MicroMsg/<account>/image2（对多开分身同样有效）。
     *  8.0.79 多开/容器化时 account 目录可能再包一层，递归两层查找。 */
    private static String findImage2RootByContext() {
        try {
            android.content.Context app = ContextManager.getAppContext();
            if (app == null) return null;
            // v3.0.566: 多路径扫描 image2 根，兼容主微信/系统分身/多开（filesDir 可能被沙箱化）。
            // 1) 应用 filesDir/MicroMsg（正常主微信）
            File mmDir = new File(app.getFilesDir(), "MicroMsg");
            String r = findImage2In(mmDir, 0);
            if (r != null) return r;
            // 2) 直接扫 filesDir 下所有 image2
            r = findImage2In(app.getFilesDir(), 3);
            if (r != null) return r;
            // 3) 常见多开/分身路径
            String[] extraRoots = {
                    "/data/user/0/com.tencent.mm/files/MicroMsg",
                    "/data/user/999/com.tencent.mm/files/MicroMsg",
                    "/data/data/com.tencent.mm/files/MicroMsg",
                    "/data/user/0/com.tencent.mm/files",
                    "/data/data/com.tencent.mm/files"
            };
            for (String root : extraRoots) {
                try {
                    File f = new File(root);
                    if (f.isDirectory()) {
                        r = findImage2In(f, 3);
                        if (r != null) return r;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String findImage2In(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 2) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            if (f.getName().equals("image2")) return f.getAbsolutePath();
            String r = findImage2In(f, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** 轮询等待图片下载落地。真实路径 = image2/<md5[0:2]>/<md5[2:4]>/th_<md5>，
     *  微信收消息后会自动下载缩略图（l51.m0.k 扩展），最多等 20s；
     *  未落地转 CDN 直链兜底（下载+解密）。
     *  消息反查 ex0.k0.k2 + 缓存对象双通道。 */
    private static void waitAndScan(ClassLoader cl, String talker, long msgId, String initialUri, Object msg) {
        String realPath = null;
        String lastThumb = null;   // 最近一次解析到的缩略图路径（CDN 落盘用）
        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            String p = queryImgPath(cl, talker, msgId);
            if (p == null || p.isEmpty()) p = initialUri;
            String real = resolveImgPath(cl, p, msg);
            lastThumb = real;
            // 严格模式：只有原图存在才算落地（缩略图存在但原图未下载时继续等）
            String orig = toOriginalPathStrict(real);
            if (orig != null && new File(orig).exists()) {
                realPath = orig;
                break;
            }
            // v3.0.536: 微信只下缩略图时（原图迟迟不落地），直接拿缩略图喂文件引擎。
            // 群二维码是规则图形，缩略图分辨率通常足够识别，不必死等原图。
            // v3.0.537: File.exists() 在多开/虚拟路径下可能误判，即使 exists=false 也在关键轮次强制试一次。
            if (real != null && (i == 0 || i == 5 || i == 10 || i == 15 || i == 19)
                    && (new File(real).exists() || i == 19)) {
                try {
                    List<String> codes = decodeFile(cl, real);
                    if (codes != null && !codes.isEmpty()) {
                        LogWriter.log(TAG, "缩略图文件引擎解码命中 msgId=" + msgId
                                + " path=" + real + " codes=" + codes);
                        handleCodes(talker, msgId, codes, real);
                        return;
                    }
                    LogWriter.log(TAG, "缩略图文件引擎空结果 msgId=" + msgId + "（继续等原图）");
                } catch (Throwable t) {
                    LogWriter.log(TAG, "缩略图解码 err: " + t);
                }
            }
            if (i == 5 || i == 10 || i == 15) {
                LogWriter.log(TAG, "图片原图仍未落地 msgId=" + msgId + " 已等 " + (i + 1) + "s uri=" + p
                        + " thumb=" + (real != null ? real : "null"));
            }
        }
        if (realPath != null) {
            LogWriter.log(TAG, "图片已落地 msgId=" + msgId + " path=" + realPath);
            scanImage(cl, talker, msgId, realPath);
            return;
        }
        LogWriter.log(TAG, "图片 20s 未落地，转 CDN 直链兜底 msgId=" + msgId);
        scanByCdn(cl, talker, msgId, msg, lastThumb);
    }

    /** 反查最新消息 XML 中的图片路径：优先 ex0.k0.k2（重新查 DB），失败用缓存对象。 */
    private static volatile boolean sEx0K0Logged = false;
    private static String queryImgPath(ClassLoader cl, String talker, long msgId) {
        try {
            Object msg = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("ex0.k0", cl), "k2", talker, msgId);
            if (msg != null) {
                String p = getImgPath(msg);
                if (p != null) return p;
            }
        } catch (Throwable t) {
            // ex0.k0 是旧版本类名，8.0.79 已不存在；仅提示一次，避免每条消息刷屏
            if (!sEx0K0Logged) {
                sEx0K0Logged = true;
                LogWriter.log(TAG, "ex0.k0.k2 反查失败（类已随版本移除，走缓存兜底）: " + t);
            }
        }
        Object cached = sMsgCache.get(msgId);
        if (cached != null) {
            return getImgPath(cached);
        }
        return null;
    }

    /** ② 同步解码：优先走 8.0.79 新引擎 v26.y0.d(Context,[path]) 直接解文件（不依赖 v16.a）；
     *  新引擎未就绪时回退 BitmapFactory 采样 + 旧 ScanImageUtil。纯后台可用。 */
    private static void scanImage(ClassLoader cl, String talker, long msgId, String path) {
        if (cl == null || path == null) return;
        String imgPath = toOriginalPathStrict(resolveImgPath(cl, path, null));
        if (imgPath == null || !new File(imgPath).exists()) {
            LogWriter.log(TAG, "scanImage 原图不存在: " + path);
            return;
        }
        try {
            List<String> codes = decodeFile(cl, imgPath);
            if (!codes.isEmpty()) {
                LogWriter.log(TAG, "文件引擎解码命中 msgId=" + msgId + " path=" + imgPath + " codes=" + codes);
                handleCodes(talker, msgId, codes, imgPath);
                return;
            }
            LogWriter.log(TAG, "文件引擎空结果 msgId=" + msgId + "，回退 Bitmap 路径");
            Bitmap bmp = decodeSampledBitmap(imgPath, 4096);
            if (bmp == null) {
                LogWriter.log(TAG, "Bitmap 解码失败: " + path);
                return;
            }
            scanBitmap(cl, talker, msgId, bmp, imgPath);
        } catch (Throwable t) {
            LogWriter.log(TAG, "scanImage err: " + t);
        }
    }

    /** 处理识别出的码值列表：命中群二维码则入群，否则记日志。 */
    private static void handleCodes(String talker, long msgId, List<String> codes, String src) {
        for (String code : codes) {
            if (isGroupQr(code)) {
                onGroupQr(code, talker, msgId, src);
                return;
            }
        }
        LogWriter.log(TAG, "未识别到群二维码 msgId=" + msgId + " codes=" + codes);
    }

    /** v3.0.535: 进群成功后删除临时保存的群二维码原图（用户要求：进群成功删除原图）。
     *  先按最近触发入群的 msgId 删除；再顺带清理超过 5 分钟的残留临时文件，防止异常路径残留。 */
    private static void cleanupTempFiles() {
        try {
            // v3.0.544: 点击成功 = 进群成功，记录短窗口去重（10s，防同批连发）
            if (sCurrentCode != null) DONE_CODE.put(sCurrentCode, System.currentTimeMillis());
            long cur = sCurrentMsgId;
            if (cur != 0L) {
                File f = sTempFiles.remove(cur);
                if (f != null && f.exists()) {
                    try {
                        boolean del = f.delete();
                        log2("进群成功，已删除临时原图 msgId=" + cur
                                + " path=" + f.getAbsolutePath() + " deleted=" + del);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "删除临时原图失败 msgId=" + cur + " err=" + t);
                    }
                }
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, File> e : sTempFiles.entrySet()) {
                File f = e.getValue();
                if (f != null && f.exists() && now - f.lastModified() > 5 * 60 * 1000L) {
                    try {
                        f.delete();
                        LogWriter.log(TAG, "清理过期临时原图 msgId=" + e.getKey()
                                + " path=" + f.getAbsolutePath());
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "cleanupTempFiles err: " + t);
        }
    }

    /** 微信 image2 目录中缩略图名为 th_<md5>，原图为同目录 <md5>（无前缀）。
     *  缩略图分辨率过低识别不出二维码，必须优先用原图。找不到原图时回退缩略图。 */
    private static String toOriginalPath(String thumbPath) {
        String strict = toOriginalPathStrict(thumbPath);
        return strict != null ? strict : thumbPath;
    }

    /** 严格模式：微信 image2 目录中缩略图名为 th_<md5>，原图为同目录 <md5>（无前缀）。
     *  仅当原图真实存在时返回原图路径；原图不存在返回 null（不回退缩略图）。
     *  群图片消息通常只先下载缩略图，原图需点击/显式下载才落地 —— 调用方必须用
     *  strict 判断，否则会拿缩略图识别导致二维码解码失败。 */
    private static String toOriginalPathStrict(String thumbPath) {
        if (thumbPath == null) return null;
        try {
            File f = new File(thumbPath);
            File dir = f.getParentFile();
            String name = f.getName();
            if (!name.startsWith("th_") || dir == null) return thumbPath;
            String md5 = name.substring(3);
            String[] candidates = {
                    new File(dir, md5).getAbsolutePath(),
                    new File(dir, md5 + ".dat").getAbsolutePath(),
                    new File(dir, md5 + ".jpg").getAbsolutePath(),
                    new File(dir, md5 + ".jpeg").getAbsolutePath(),
                    new File(dir, md5 + ".png").getAbsolutePath(),
                    new File(dir, md5 + ".webp").getAbsolutePath(),
                    new File(dir, md5 + ".gif").getAbsolutePath()
            };
            for (String cand : candidates) {
                if (new File(cand).exists() && new File(cand).length() > 0) {
                    LogWriter.log(TAG, "原图命中: " + cand);
                    return cand;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 统一识别入口：解码后的 Bitmap 投喂微信原生识别引擎。
     *  src 为本地路径或 "cdn"（仅日志用）。ScanImageUtil 未就绪时 5s 后重试一次。 */
    private static void scanBitmap(ClassLoader cl, String talker, long msgId, Bitmap bmp, String src) {
        if (cl == null || bmp == null) return;
        try {
            List<String> codes = decodeBitmap(cl, bmp);
            if (codes.isEmpty()) {
                // 新引擎兜底：Bitmap 写临时文件喂 v26.y0.d(Context,[path])（CDN 无本地文件场景）
                codes = decodeBitmapViaFile(cl, bmp);
            }
            if (codes.isEmpty() && !ensureScanImgUtil(cl) && !ensureFileDecodeEngine(cl) && RETRY_MSG.add(msgId)) {
                LogWriter.log(TAG, "ScanImageUtil 未就绪，5s 后重试 msgId=" + msgId);
                final Bitmap fBmp = bmp;
                final String fSrc = src;
                sScanBus.execute(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    scanBitmap(cl, talker, msgId, fBmp, fSrc);
                });
                return;
            }
            try { bmp.recycle(); } catch (Throwable ignored) {}
            handleCodes(talker, msgId, codes, src);
        } catch (Throwable t) {
            LogWriter.log(TAG, "scanBitmap err: " + t);
        }
    }

    /** CDN 兜底：Bitmap 写入临时 JPEG 文件，走 8.0.79 新文件解码引擎 v26.y0.d。 */
    private static List<String> decodeBitmapViaFile(ClassLoader cl, Bitmap bmp) {
        List<String> out = new ArrayList<>();
        File tmp = null;
        try {
            if (!ensureFileDecodeEngine(cl)) return out;
            File cacheDir = ContextManager.getAppContext().getCacheDir();
            tmp = File.createTempFile("leshao_qr_", ".jpg", cacheDir);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            fos.close();
            out = decodeFile(cl, tmp.getAbsolutePath());
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeBitmapViaFile err: " + t);
        } finally {
            if (tmp != null) { try { tmp.delete(); } catch (Throwable ignored) {} }
        }
        return out;
    }

    /** CDN 直链兜底：从消息对象提取 url+aeskey → 下载 → AES 解密 → Bitmap 投喂。 */
    /** v3.0.537: CDN 提取失败诊断（只打印一次，避免刷屏）。 */
    private static volatile boolean sCdnDiagLogged = false;

    private static void dumpCdnDiagnostic(Object msg, long msgId) {
        if (sCdnDiagLogged) return;
        sCdnDiagLogged = true;
        try {
            LogWriter.log(TAG, "CDN 提取失败诊断 msgId=" + msgId + " msgClass="
                    + (msg != null ? msg.getClass().getName() : "null"));
            if (msg == null) return;
            for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(msg);
                        String vs;
                        if (v == null) vs = "null";
                        else if (v instanceof String) vs = "\"" + String.valueOf(v).substring(0, Math.min(160, String.valueOf(v).length())) + "\"";
                        else vs = v.getClass().getSimpleName() + ":" + String.valueOf(v).substring(0, Math.min(80, String.valueOf(v).length()));
                        LogWriter.log(TAG, "  CDN诊断 " + f.getName() + "(" + f.getType().getSimpleName() + ") = " + vs);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "CDN 诊断 err: " + t);
        }
    }

    private static void scanByCdn(ClassLoader cl, String talker, long msgId, Object msg, String thumbPath) {
        try {
            if (msg == null) return;
            String[] cdn = extractCdnFromMsg(msg);
            if (cdn == null || cdn[0] == null || cdn[0].isEmpty()) {
                LogWriter.log(TAG, "CDN 兜底未找到 url/aeskey msgId=" + msgId);
                dumpCdnDiagnostic(msg, msgId);
                return;
            }
            LogWriter.log(TAG, "CDN 下载开始 msgId=" + msgId
                    + " urlLen=" + cdn[0].length()
                    + " hasKey=" + (cdn[1] != null && !cdn[1].isEmpty()));
            byte[] raw = downloadBytes(cdn[0]);
            if (raw == null || raw.length == 0) {
                LogWriter.log(TAG, "CDN 下载为空 msgId=" + msgId);
                return;
            }
            byte[] img = decryptCdnBytes(raw, cdn[1]);
            if (img == null || img.length == 0) {
                LogWriter.log(TAG, "CDN 解密为空 msgId=" + msgId);
                return;
            }
            // v3.0.535: 把解密后的原图临时保存到 cacheDir/leshao_gqr/<msgId>.jpg，
            // 仅用于本次扫码识别；进群成功后由 cleanupTempFiles() 删除。
            // （不再落盘 image2 正式目录 —— 用户要求临时保存，进群成功删除原图）
            File tempImg = null;
            try {
                File tmpDir = new File(ContextManager.getAppContext().getCacheDir(), "leshao_gqr");
                if (!tmpDir.exists()) tmpDir.mkdirs();
                tempImg = new File(tmpDir, msgId + ".jpg");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tempImg);
                try { fos.write(img); } finally { fos.close(); }
                if (tempImg.length() > 0) {
                    sTempFiles.put(msgId, tempImg);
                    LogWriter.log(TAG, "群二维码原图已临时保存: " + tempImg.getAbsolutePath()
                            + " len=" + tempImg.length());
                    // 优先用临时原图走文件引擎解码（与本地原图同一链路，最可靠）
                    try {
                        List<String> codes = decodeFile(cl, tempImg.getAbsolutePath());
                        if (codes != null && !codes.isEmpty()) {
                            LogWriter.log(TAG, "临时原图文件引擎解码命中 msgId=" + msgId + " codes=" + codes);
                            handleCodes(talker, msgId, codes, tempImg.getAbsolutePath());
                            return;
                        }
                        LogWriter.log(TAG, "临时原图文件引擎空结果 msgId=" + msgId + "，回退 Bitmap 路径");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "临时原图文件引擎解码 err: " + t);
                    }
                } else {
                    LogWriter.log(TAG, "临时保存失败（0 字节） msgId=" + msgId);
                    try { tempImg.delete(); } catch (Throwable ignored) {}
                    tempImg = null;
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "临时保存 err: " + t);
                tempImg = null;
            }
            Bitmap bmp = decodeBytes(img);
            if (bmp == null) {
                LogWriter.log(TAG, "CDN 解码失败 msgId=" + msgId
                        + " rawLen=" + raw.length
                        + " imgLen=" + img.length);
                return;
            }
            LogWriter.log(TAG, "CDN 图片解码成功 msgId=" + msgId
                    + " size=" + bmp.getWidth() + "x" + bmp.getHeight());
            scanBitmap(cl, talker, msgId, bmp, "cdn");
        } catch (Throwable t) {
            LogWriter.log(TAG, "scanByCdn err: " + t);
        }
    }

    /** 从消息对象提取 CDN 信息 [url, aeskey]：先读 content（XML），再遍历所有 String 字段。 */
    /** v3.0.537: 从消息对象提取 CDN 信息 [url, aeskey]。
     *  ① content XML（readContent 已增强）；
     *  ② 深度遍历 msg 对象图（String 字段 + Map/Bundle/List/数组/嵌套对象，最多 4 层），
     *  8.0.79 群图片消息的 CDN 直链可能不在 content 里，而在消息扩展/字段嵌套对象中。 */
    private static String[] extractCdnFromMsg(Object msg) {
        if (msg == null) return null;
        try {
            String xml = readContent(msg);
            if (xml != null) {
                String[] r = extractCdnFromXml(xml);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        try {
            String[] r = extractCdnDeep(msg, new HashSet<Object>(), 0);
            if (r != null) return r;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 深度遍历对象图找 CDN url/aeskey（防循环引用，seen 去重）。 */
    private static String[] extractCdnDeep(Object obj, Set<Object> seen, int depth) {
        if (obj == null || depth > 4) return null;
        if (obj instanceof String) return extractCdnFromXml((String) obj);
        if (!(obj instanceof CharSequence)) {
            if (seen.contains(obj)) return null;
            seen.add(obj);
        }
        if (obj instanceof Map) {
            for (Object v : ((Map<?, ?>) obj).values()) {
                String[] r = extractCdnDeep(v, seen, depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        if (obj instanceof List) {
            for (Object v : (List<?>) obj) {
                String[] r = extractCdnDeep(v, seen, depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        if (obj instanceof Bundle) {
            Bundle b = (Bundle) obj;
            for (String k : b.keySet()) {
                Object v = b.get(k);
                String[] r = extractCdnDeep(v, seen, depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        if (obj.getClass().isArray()) {
            int n = Array.getLength(obj);
            for (int i = 0; i < n; i++) {
                String[] r = extractCdnDeep(Array.get(obj, i), seen, depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    if (v instanceof String) {
                        String[] r = extractCdnFromXml((String) v);
                        if (r != null) return r;
                    } else if (depth < 3
                            && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof Character)
                            && !(v instanceof java.util.Date)) {
                        String[] r = extractCdnDeep(v, seen, depth + 1);
                        if (r != null) return r;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 从 XML/文本提取 CDN 图片直链 + aeskey。*/
    private static String[] extractCdnFromXml(String s) {
        if (s == null) return null;
        String url = null;
        Matcher mu = P_CDN_URL.matcher(s);
        while (mu.find()) {
            String u = mu.group(0);
            if (u.contains("&amp;")) u = u.replace("&amp;", "&");
            if (u.contains("mmbiz.qpic.cn") || u.contains("wxqpic") || u.contains("cdn")
                    || u.contains("qpic.cn") || u.contains("snsvideo") || u.contains("findermp")) {
                url = u;
                break;
            }
            if (url == null) url = u;
        }
        String aeskey = null;
        Matcher mk = P_AESKEY.matcher(s);
        if (mk.find()) aeskey = mk.group(1);
        if (url == null && aeskey == null) return null;
        return new String[]{url, aeskey};
    }

    /** 下载 URL 字节（10s 超时，最大 8MB）。 */
    private static byte[] downloadBytes(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        InputStream in = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                LogWriter.log(TAG, "CDN HTTP " + code + " url=" + urlStr.substring(0, Math.min(120, urlStr.length())));
                return null;
            }
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 8 * 1024 * 1024) break;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            LogWriter.log(TAG, "downloadBytes err: " + t.getMessage());
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
        }
    }

    /** CDN 密文解密：AES-128-CBC，key=aeskey 的 hex，IV=16 字节全零，PKCS7。
     *  已明文（图片 magic）直接返回；解密失败尝试跳过前 16 字节再解。 */
    private static byte[] decryptCdnBytes(byte[] raw, String aeskeyHex) {
        if (raw == null) return null;
        if (looksLikeImage(raw)) return raw;
        if (aeskeyHex == null || aeskeyHex.isEmpty()) return raw;
        byte[] key = hexToBytes(aeskeyHex);
        if (key == null || key.length != 16) return raw;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS7Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            byte[] out = c.doFinal(raw);
            if (looksLikeImage(out)) return out;
        } catch (Throwable t) {
            LogWriter.log(TAG, "AES 解密失败: " + t.getMessage());
        }
        try {
            if (raw.length > 16) {
                byte[] sub = new byte[raw.length - 16];
                System.arraycopy(raw, 16, sub, 0, sub.length);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS7Padding");
c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
                byte[] out = c.doFinal(sub);
                if (looksLikeImage(out)) return out;
            }
        } catch (Throwable ignored) {}
        return raw;
    }

    private static boolean looksLikeImage(byte[] d) {
        if (d == null || d.length < 8) return false;
        return (d[0] == (byte) 0xFF && d[1] == (byte) 0xD8)        // JPEG
                || (d[0] == 'P' && d[1] == 'N' && d[2] == 'G')     // PNG
                || (d[0] == 'G' && d[1] == 'I' && d[2] == 'F');    // GIF
    }

    /** hex 字符串 → 字节数组。 */
    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if ((h.length() & 1) != 0) return null;
        byte[] out = new byte[h.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                int hi = Character.digit(h.charAt(i * 2), 16);
                int lo = Character.digit(h.charAt(i * 2 + 1), 16);
                if (hi < 0 || lo < 0) return null;
                out[i] = (byte) ((hi << 4) | lo);
            }
        } catch (Throwable t) {
            return null;
        }
        return out;
    }

    /** 字节数组 → Bitmap（含采样，防 OOM）。 */
    private static Bitmap decodeBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            int sample = 1;
            while (opt.outWidth / sample > 2048 || opt.outHeight / sample > 2048) sample *= 2;
            opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeBytes err: " + t.getMessage());
            return null;
        }
    }

    /** 定位 8.0.79 新扫码引擎 v26.y0（WxFileDecodeQueue）并缓存单例与解码方法。
     *  dex 字段名 h=单例、方法 d(Context,List)=同步解码文件列表（directScanCodeForFile）。
     *  h3 结果字段 a（dex 名）=识别结果 List。 */
    private static boolean ensureFileDecodeEngine(ClassLoader cl) {
        if (sFileDecodeMethod != null && sFileDecodeEngine != null) return true;
        if (cl == null) return false;
        try {
            Class<?> c = XposedHelpers.findClass("v26.y0", cl);
            Object engine = XposedHelpers.getStaticObjectField(c, "h");
            Method m = c.getMethod("d", Context.class, List.class);
            sFileDecodeClass = c;
            sFileDecodeEngine = engine;
            sFileDecodeMethod = m;
            LogWriter.log(TAG, "WxFileDecodeQueue 引擎命中: v26.y0.h(Context,[path])");
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureFileDecodeEngine err: " + t);
        }
        return false;
    }

    /** 新引擎同步解码本地图片文件，返回码值列表。
     *  调用 v26.y0 单例的 d(Context, [path]) → List<h3>，h3 的 a 字段（dex 名）= 识别结果 List，
     *  逐元素用 extractCodes 提取码值（兼容新旧结果对象）。 */
    private static List<String> decodeFile(ClassLoader cl, String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isEmpty()) return out;
        try {
            if (!ensureFileDecodeEngine(cl)) return out;
            Context ctx = ContextManager.getAppContext();
            Object ret = sFileDecodeMethod.invoke(sFileDecodeEngine, ctx, Collections.singletonList(path));
            if (ret instanceof List) {
                for (Object h3 : (List<?>) ret) {
                    if (h3 == null) continue;
                    Object results = XposedHelpers.getObjectField(h3, "a");
                    if (results instanceof List) {
                        for (Object item : (List<?>) results) {
                            if (item == null) continue;
                            out.addAll(extractCodes(item));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeFile err: " + t + " path=" + path);
        }
        return out;
    }

    /** 定位微信同步解码工具类并缓存。
     *  ① 硬编码 v16.a（主 dex 直接命中）；② DexKit 锚点 MicroMsg.ScanImageUtil（Tinker/补丁场景兜底）。
     *  返回 true 表示解码方法已就绪。 */
    private static boolean ensureScanImgUtil(ClassLoader cl) {
        if (sScanImgUtilMethod != null) return true;
        if (cl == null) return false;
        if (sScanImgUtilClass == null) {
            try {
                Class<?> c = WxResolver.get("pluginsdk_ui_tools_v16_a", cl);
                if (c != null) {
                    try {
                        Method m = c.getMethod("a", Bitmap.class, int[].class, boolean.class);
                        if (Modifier.isStatic(m.getModifiers())) {
                            sScanImgUtilClass = c;
                            sScanImgUtilMethod = m;
                            LogWriter.log(TAG, "ScanImageUtil 硬编码命中(验证通过): " + c.getName());
                            return true;
                        }
                    } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "ScanImageUtil 硬编码类无解码方法，弃用走锚点: " + c.getName());
                }
            } catch (Throwable ignored) {}
        }
        if (sScanImgUtilClass == null) {
            try {
                List<String> classes = DexKitHelper.findClassesByString(cl, "MicroMsg.ScanImageUtil");
                if (classes != null) {
                    for (String cn : classes) {
                        for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                            try {
                                Method m = c.getMethod("a", Bitmap.class, int[].class, boolean.class);
                                if (Modifier.isStatic(m.getModifiers())) {
                                    sScanImgUtilClass = c;
                                    sScanImgUtilMethod = m;
                                    LogWriter.log(TAG, "ScanImageUtil 锚点命中: " + cn);
                                    return true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "ensureScanImgUtil(anchor) err: " + t);
            }
        }
        return sScanImgUtilMethod != null;
    }

    /** 同步解码 Bitmap，返回码值列表（ScanImageUtil.a 静态方法）。
     *  实测 3180：结果对象（t16.i0）字段映射已变化，e 字段不再是码值而是码类型名（如 QR_CODE），
     *  因此遍历对象所有 String 字段收集码值，兼容新旧版本。 */
    private static List<String> decodeBitmap(ClassLoader cl, Bitmap bmp) {
        List<String> out = new ArrayList<>();
        if (bmp == null) return out;
        try {
            if (!ensureScanImgUtil(cl)) return out;
            Method m = sScanImgUtilMethod;
            Object ret = m.invoke(null, bmp, new int[]{2}, false);
            if (ret instanceof List) {
                for (Object item : (List<?>) ret) {
                    if (item == null) continue;
                    out.addAll(extractCodes(item));
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeBitmap err: " + t);
        }
        return out;
    }

    /** 从单个解码结果对象提取码值字符串。
     *  3180 实测（WxQBarResult extends t16.i0）：f=二维码内容(URL/文本)、e=码类型名、
     *  g=原始字节、h=charset。优先取 f；f 为空时用 g+h 自行解码；最后遍历所有 String 字段兜底。 */
    private static List<String> extractCodes(Object item) {
        List<String> res = new ArrayList<>();
        try {
            Object v = XposedHelpers.getObjectField(item, "f");
            if (v instanceof String) addCode(res, (String) v);
        } catch (Throwable ignored) {}
        if (res.isEmpty()) {
            try {
                byte[] raw = (byte[]) XposedHelpers.getObjectField(item, "g");
                String charset = (String) XposedHelpers.getObjectField(item, "h");
                if (raw != null && raw.length > 0) {
                    String dec;
                    if (charset == null || charset.isEmpty() || "ANY".equals(charset)) {
                        dec = new String(raw, "UTF-8");
                    } else {
                        try {
                            dec = new String(raw, java.nio.charset.Charset.forName(charset));
                        } catch (Throwable t) {
                            dec = new String(raw, "UTF-8");
                        }
                    }
                    addCode(res, dec);
                }
            } catch (Throwable ignored) {}
        }
        if (res.isEmpty()) {
            for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(item);
                        if (v instanceof String) addCode(res, (String) v);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return res;
    }

    /** 收集疑似码值的字符串：排除空串、码类型名、纯单字符。 */
    private static void addCode(List<String> res, String s) {
        if (s == null || s.isEmpty()) return;
        if (s.equals("QR_CODE") || s.equals("WX_CODE")) return;
        if (s.length() < 4) return;
        if (!res.contains(s)) res.add(s);
    }

    /** 采样解码，避免大图 OOM（>2048px 缩放）。 */
    private static Bitmap decodeSampledBitmap(String path, int maxSize) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opt);
            int sample = 1;
            while (opt.outWidth / sample > maxSize || opt.outHeight / sample > maxSize) {
                sample *= 2;
            }
            opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeSampledBitmap err: " + t);
            return null;
        }
    }

    /** ③ 识别结果截胡（手册 §1.1 / §7 hookScanResult）：
     *  hook ImageScanCodeManager.b(...)（= 手册 wk5.g0.b），
     *  在 before 阶段读取 ImageQBarDataBean.d（码值）—— 这是「模块发 RecogQBarOfImageFileEvent
     *  后，微信原生解码结果回传」的必经出口。漏掉这一环，码值永远到不了模块。
     *  定位：DexKit 字符串锚点 MicroMsg.ImageScanCodeManager（+ doScanCode from decoder msgId 兜底）。 */
    private static void hookScanResult(ClassLoader cl) {
        try {
            List<String> classes = DexKitHelper.findClassesByString(cl, "MicroMsg.ImageScanCodeManager");
            if (classes == null || classes.isEmpty()) {
                classes = DexKitHelper.findClassesByString(cl, "doScanCode from decoder msgId");
            }
            if (classes == null || classes.isEmpty()) {
                LogWriter.log(TAG, "ImageScanCodeManager 锚点未命中");
                return;
            }
            boolean hooked = false;
            for (String cn : classes) {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    Class<?> beanCls = null;
                    try {
                        beanCls = WxResolver.get("plugin_scanner_ImageQBarDataBean", cl);
                    } catch (Throwable ignored) {}
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"b".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 2) continue;
                        // 参数中包含 ImageQBarDataBean（完整类名或含关键字）
                        boolean hasBean = false;
                        for (Class<?> pt : pts) {
                            if (pt == beanCls) { hasBean = true; break; }
                            if (pt.getName().contains("ImageQBarDataBean")) { hasBean = true; break; }
                        }
                        if (!hasBean) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (!sEnabled) return;
                                try {
                                    // 遍历参数找 ImageQBarDataBean 实例和 wk5.l0
                                    for (Object a : p.args) {
                                        if (a == null) continue;
                                        String an = a.getClass().getName();
                                        if (an.contains("ImageQBarDataBean")) {
                                            String code = (String) XposedHelpers.getObjectField(a, "d");
                                            int e = XposedHelpers.getIntField(a, "e");
                                            int f = XposedHelpers.getIntField(a, "f");
                                            LogWriter.log(TAG, "hookScanResult bean d=" + code
                                                    + " e=" + e + " f=" + f);
                                            if (isGroupQr(code)) onGroupQr(code);
                                        } else if (an.contains("wk5.l0")) {
                                            try {
                                                LogWriter.log(TAG, "hookScanResult l0 f=" + XposedHelpers.getIntField(a, "f")
                                                        + " g=" + XposedHelpers.getIntField(a, "g")
                                                        + " i=" + XposedHelpers.getIntField(a, "i")
                                                        + " n=" + XposedHelpers.getObjectField(a, "n"));
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "scanResult err: " + t);
                                }
                            }
                        });
                        hooked = true;
                        LogWriter.log(TAG, "已 hook 识别结果 " + c.getName() + "." + m.getName()
                                + " args=" + pts.length);
                    }
                }
            }
            if (!hooked) LogWriter.log(TAG, "ImageScanCodeManager.b 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookScanResult err: " + t.getMessage());
        }
    }

    /** ④ 事件层兜底（手册 §0.1 铁律「日志层+事件层双通道」）：
     *  hook IEvent.e()（同步 publish）before，当发布的是 RecogQBarOfImageFileResultEvent
     *  时直接读 g.b（ArrayList<String> 码值）。即使 wk5.g0.b 类名/锚点失效也能捕获识别结果。 */
    private static void hookRecogResultEvent(ClassLoader cl) {
        try {
            Class<?> iEvent = WxResolver.get("sdk_event_IEvent", cl);
            Method e = iEvent.getDeclaredMethod("e");
            XposedBridge.hookMethod(e, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sEnabled) return;
                    try {
                        Object ev = p.thisObject;
                        if (ev == null) return;
                        if (!ev.getClass().getName().contains("RecogQBarOfImageFileResultEvent")) return;
                        Object g = XposedHelpers.getObjectField(ev, "g");
                        if (g == null) return;
                        Object codes = XposedHelpers.getObjectField(g, "b");
                        if (!(codes instanceof java.util.List)) return;
                        for (Object o : (java.util.List<?>) codes) {
                            if (o instanceof String && isGroupQr((String) o)) {
                                onGroupQr((String) o);
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "recogResultEvent err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook 识别结果事件 IEvent.e");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookRecogResultEvent err: " + t.getMessage());
        }
    }

    /** ⑤ 日志层兜底（手册 §7 hookQrLog）：零类名依赖，TAG=MicroMsg.QBarStringHandler
     *  且 fmt 含 dealQBarString 时，从格式化参数拿码值。 */
    private static void hookQrLog(ClassLoader cl) {
        try {
            Method logI = XposedHelpers.findClass("com.tencent.mars.xlog.Log", cl)
                    .getDeclaredMethod("i", String.class, String.class, Object[].class);
            XposedBridge.hookMethod(logI, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sEnabled) return;
                    try {
                        if (!"MicroMsg.QBarStringHandler".equals(p.args[0])) return;
                        String fmt = (String) p.args[1];
                        if (fmt == null || !fmt.contains("dealQBarString")) return;
                        Object[] a = (Object[]) p.args[2];
                        if (a == null || a.length == 0) return;
                        String code = String.valueOf(a[0]);
                        if (isGroupQr(code)) onGroupQr(code);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "qrLog err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook 日志层兜底");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookQrLog err: " + t.getMessage());
        }
    }
    /** ⑥ 码值总闸（手册 §2.1 / §3.1）：QBarStringHandler.g(...) after 拿二维码原文。
     *  这是微信内部 DealQBarStrEvent 分发的落点，作为第二通道：
     *  当微信自己（扫码/长按/相册）产生群码时也能捕获并自动完成入群。 */
    private static void hookQBarStringHandler(ClassLoader cl) {
        try {
            List<String> classes = DexKitHelper.findClassesByString(cl, "MicroMsg.QBarStringHandler");
            if (classes == null || classes.isEmpty()) {
                classes = DexKitHelper.findClassesByString(cl, "[handleCode-dealQBarString]");
            }
            if (classes == null || classes.isEmpty()) {
                LogWriter.log(TAG, "QBarStringHandler 锚点未命中");
                return;
            }
            boolean hooked = false;
            for (String cn : classes) {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"g".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 5) continue;
                        if (pts[0] != Activity.class || pts[1] != String.class
                                || pts[2] != int.class || pts[3] != int.class || pts[4] != int.class) {
                            continue;
                        }
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                if (!sEnabled) return;
                                try {
                                    String code = (String) p.args[1];
                                    if (isGroupQr(code)) onGroupQr(code);
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "qbar g after err: " + t);
                                }
                            }
                        });
                        hooked = true;
                        LogWriter.log(TAG, "已 hook 码值总闸 " + c.getName() + "." + m.getName());
                    }
                }
            }
            if (!hooked) LogWriter.log(TAG, "QBarStringHandler.g 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookQBarStringHandler err: " + t.getMessage());
        }
    }

    /** v3.0.546: 判断群码对应的群是否已加入。
     *  通过进群成功时记录的「群码 → 群 username」映射 + BatchInviteManager.loadMyRooms()
     *  查询当前微信是否仍在该群。群还在 → 已加入，不重复扫码；群已退出/被移除 →
     *  允许重新扫码进群。
     *  v3.0.546: 退群/被移出群聊时由 handleKickedSysMsg + hookMyRoomListSync
     *  实时删除 JoinRecordStore 记录，因此这里无需再查群成员。 */
    /** 实时从 rcontact 表查询我的群列表（不依赖微信混淆类名，天然动态适配）。
     *  清缓存后重新读取；3 秒内未加载完成返回 null（调用方应放行，避免误跳过）。 */
    private static List<String> loadMyRoomsFromDb() {
        try {
            ContactRepository.refresh();
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                List<ContactCard> groups = ContactRepository.getGroups();
                if (groups != null && !groups.isEmpty()) {
                    List<String> rooms = new ArrayList<>();
                    for (ContactCard c : groups) {
                        if (c != null && c.username != null && !c.username.isEmpty()) rooms.add(c.username);
                    }
                    return rooms;
                }
                Thread.sleep(200);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadMyRoomsFromDb err: " + t);
        }
        return null;
    }

    private static boolean isCodeAlreadyJoined(String code) {
        if (code == null || code.isEmpty()) return false;
        try {
            String room = JoinRecordStore.getRoomByCode(code);
            if (room == null || room.isEmpty()) return false;
            // 用 rcontact 实时查询（不依赖缓存/微信类名），避免被踢/退群后仍误判"在群"而跳过
            List<String> rooms = loadMyRoomsFromDb();
            if (rooms == null) {
                // 实时查询失败：放行（允许重新扫码，宁可重复处理也不跳过）
                LogWriter.log(TAG, "群列表实时查询失败，放行扫码 room=" + room);
                return false;
            }
            if (rooms.contains(room)) {
                LogWriter.log(TAG, "已加入群聊，跳过扫码进群 room=" + room + " codeLen=" + code.length());
                return true;
            }
            // 实时确认已不在群：清除历史记录（自愈），允许重新扫码进群
            List<String> codes = JoinRecordStore.removeRoom(room);
            for (String code2 : codes) {
                if (code2 != null && !code2.isEmpty()) DONE_CODE.remove(code2);
            }
            LogWriter.log(TAG, "实时确认已退群，清除进群记录并允许重新扫码 room=" + room + " codes=" + codes);
        } catch (Throwable t) {
            LogWriter.log(TAG, "isCodeAlreadyJoined err: " + t);
        }
        return false;
    }

    /** v3.0.538: 群码触发去重。已成功进群的群码在 TTL 内跳过；已加入群（本地群列表
     *  仍存在）永久跳过；未成功前相同群码 10s 内不重复触发（防同批消息瞬间连发），
     *  超过窗口由下一条消息天然重试。 */
    private static boolean shouldProcessCode(String code) {
        if (isCodeAlreadyJoined(code)) return false;   // v3.0.543: 已加入的群不重复扫码
        if (isDoneCode(code)) return false;   // v3.0.542: 成功进群 5 分钟内不重复处理
        long now = System.currentTimeMillis();
        if (code.equals(sLastCode) && now - sLastCodeTime < 10000L) return false;
        sLastCode = code;
        sLastCodeTime = now;
        return true;
    }

    /** 微信自身识别链路命中群码（投喂后微信自己识别产生的回调，无需再投喂，
     *  只需刷新保护窗，供 hookJoinDialog 自动点「加入群聊」）。 */
    private static void onGroupQr(String code) {
        if (!isGroupQr(code)) return;
        if (!shouldProcessCode(code)) return;
        sLastDealQBarTime = System.currentTimeMillis();
        log2("微信链路命中群码: " + code + " 保护窗已刷新");
    }

    /** 模块自行解码命中群码：直接发 DealQBarStrEvent 走微信官方 A8Key 入群链路
     *  （8.0.79 中 com.tencent.mm.plugin.scanner.model.s.ExternRequestDealQBarStrHandler
     *   监听该事件，codeType=19(QR_CODE 群码) 时走 p84.v.g → A8Key 扫码入群，
     *   随后 hookJoinDialog 自动点「加入群聊」）。
     *  v3.0.502: 不再投喂 wk5.g0（该类已在 8.0.79 删除，triggerWxScan 必然失败）。 */
    private static void onGroupQr(String code, String talker, long msgId, String imgPath) {
        if (!isGroupQr(code)) return;
        if (!shouldProcessCode(code)) return;
        sCurrentMsgId = msgId;   // v3.0.535: 记录当前 msgId，进群成功后删除对应临时原图
        log2("★★★ 群二维码命中: " + code + " msgId=" + msgId + " talker=" + talker);
        sBus.execute(() -> dealQBarStr(code));
    }

    /** ④ 发 DealQBarStrEvent 让微信走 A8Key 入群（手册 §5 路径 A）。
     *  注意：微信事件分发内部会 new Handler，必须运行在主线程（后台线程会抛
     *  "Can't create handler inside thread ... Looper.prepare()"），
     *  因此整个发布流程切到主线程执行。 */
    private static void dealQBarStr(String code) {
        if (!sEnabled) return;
        if (!allowToday()) {
            LogWriter.log(TAG, "已达每日入群上限(" + sMaxPerDay + ")，跳过 " + code);
            return;
        }
        ClassLoader cl = sCl;
        if (cl == null) return;
        sCurrentCode = code;   // v3.0.513: 供点击/发送守卫识别当前群码
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object ev = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "com.tencent.mm.autogen.events.DealQBarStrEvent", cl));
                Object g = XposedHelpers.getObjectField(ev, "g");
                Activity act = MainHook.currentActivity();
                if (act == null) act = anyActivity();
                if (act != null) XposedHelpers.setObjectField(g, "b", act);
                else LogWriter.log(TAG, "dealQBarStr: 无可用 Activity，b 保持 null（可能导致微信 NPE）");
                XposedHelpers.setObjectField(g, "a", code);     // 码值（群链接 URL）
                XposedHelpers.setObjectField(g, "c", 19);       // codeType = QR_CODE 群码（j1.b(19)=true 走群码分支 p84.v.g）
                XposedHelpers.setObjectField(g, "d", 0);        // codeVersion
                XposedHelpers.setObjectField(g, "g", 2);       // from = 2（图片识别来源，handler 中 <0 也会默认 2）
                XposedHelpers.setObjectField(g, "i", 0);       // scene 保持默认
                XposedHelpers.callMethod(ev, "e");              // publish
                sLastDealQBarTime = System.currentTimeMillis();
                // v3.0.538 起强制后台静默：无论微信是否在前台，页面一出现立即退回后台，
                // H5 在后台继续加载/注入点击，用户无感知、不跳转页面。
                sSilentJoin = true;
                sSilentJoinUntil = System.currentTimeMillis() + 60000L;
                // v3.0.539: 新一次入群流程，复位静默退后台标记
                sSilentBackDone = false;
                sSilentActivity = null;
                LogWriter.log(TAG, "纯后台静默入群（强制） foreground=" + sWeChatForeground
                        + " 静默截止=" + sSilentJoinUntil);
                log2("已触发入群流程: " + code + " event=" + ev.getClass().getName() + " act=" + (act != null ? act.getClass().getName() : "null") + " 保护窗=" + sLastDealQBarTime);
            } catch (Throwable t) {
                LogWriter.log(TAG, "dealQBarStr err: " + t);
            }
        });
    }

    /** 获取任意存活 Activity：先取前台，失败则反射 ActivityThread.mActivities 兜底。
     *  后台收到群图片消息时前台可能无 Activity，但微信主界面实例通常仍在任务栈存活。 */
    private static Activity anyActivity() {
        Activity a = MainHook.currentActivity();
        if (a != null) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object activities = XposedHelpers.getObjectField(thread, "mActivities");
            if (activities instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) activities;
                for (Object value : map.values()) {
                    try {
                        Object act = XposedHelpers.getObjectField(value, "activity");
                        if (act instanceof Activity) return (Activity) act;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "anyActivity err: " + t);
        }
        return null;
    }

    /** ⑦ 自动点「加入群聊」：仅最近 60s 内发起过入群请求时才介入，避免误点。 */
    /**
     * ⑦ 自动点确认（v3.0.503 增强：Dialog 通道 + Activity 通道双保险）。
     * <p>实机教训：8.0.79 扫码加群确认 UI 不一定是 Dialog —— 实测日志显示 dealQBarStr
     * 触发后“微信跳转页面了但最后一步没发生”，说明确认页可能是 Activity（自定义 View/页面），
     * 只 hook Dialog.show 会漏。因此这里同时全局 hook Activity.onCreate/onResume，
     * 在最近 60s 发起过入群请求（sLastDealQBarTime 保护窗）时扫描页面文案自动点击，
     * 并打印 Activity 类名用于诊断确认页真实类型。</p>
     */
    private static void hookJoinDialog(ClassLoader cl) {
        // v3.0.511: 该 hook 只依赖系统 Activity/Dialog 类，可在启动早期立即安装
        //（不等待 DexKit），确保微信重启后第一时间就能静默退后台/自动点确认。
        if (sDialogHooked.get()) return;
        if (!sDialogInstalling.compareAndSet(false, true)) return;
        try {
        // ① Dialog 通道：任何 Dialog.show，含加群文案且无安全阀词则自动点确认。
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            XposedBridge.hookMethod(show, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!sEnabled) return;
                    long since = System.currentTimeMillis() - sLastDealQBarTime;
                    if (since > 60000L) return;
                    try {
                        Dialog d = (Dialog) p.thisObject;
                        if (d == null || d.getWindow() == null) return;
                        View decor = d.getWindow().getDecorView();
                        log2("Dialog.show 触发: " + d.getClass().getName() + " 距入群请求=" + since + "ms");
                        boolean matched = matchDialog(decor, JOIN_WORDS);
                        log2("  matchDialog=" + matched + " dialog=" + d.getClass().getName());
                        if (!matched) return;
                        decor.postDelayed(() -> clickJoin(d), 180);
                        log2("  已安排 clickJoin 180ms");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "dialog show err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook Dialog.show 自动点确认");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinDialog err: " + t.getMessage());
        }
        // ② Activity 通道：onCreate + onResume 后扫描页面文案（覆盖非 Dialog 确认页）。
        try {
            // v3.0.504: 基类 getDeclaredMethod 只 hook android.app.Activity 自身的方法，
            // 微信子类（如 MMWebViewUI）override 后不调 super 时钩子不触发（WmEntry 用
            // hookAllMethods 能打印 onResume 而本模块不能 → 确认页没扫到）。
            // 改用 hookAllMethods 覆盖全部子类 override 版本。
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity a = (Activity) p.thisObject;
                        if (a == null) return;
                        maybeAutoClickActivity(a, "onCreate");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "activity create err: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity a = (Activity) p.thisObject;
                        if (a == null) return;
                        maybeAutoClickActivity(a, "onResume");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "activity resume err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook Activity 加群确认页(onCreate/onResume, hookAllMethods)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookActivityConfirm err: " + t.getMessage());
        }
        sDialogHooked.set(true);
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinDialog err: " + t.getMessage());
        } finally {
            sDialogInstalling.set(false);
        }
    }

    /** 判断是否值得扫描当前 Activity：保护窗内 或 类名像加群/验证页。 */
    private static boolean isJoinConfirmCandidate(Activity a) {
        if (a == null) return false;
        // v3.0.539: 保护窗 60s→15s（多消息重试足够），避免正常打开微信时
        // LauncherUI/ChattingUI 被误判为确认页候选进入扫描流程。
        if (sEnabled && System.currentTimeMillis() - sLastDealQBarTime <= 15000L) return true;
        String cn = a.getClass().getName();
        if (cn == null) return false;
        String lower = cn.toLowerCase();
        return lower.contains("roomaccessverify") || lower.contains("verifyapplication")
                || lower.contains("joinchatroom") || lower.contains("chatroomjoin")
                || lower.contains("groupchatjoin") || lower.contains("verifychatroom")
                || lower.contains("chatroomverify") || lower.contains("qrinvite")
                || lower.contains("joinroom");
    }

    /** 对候选 Activity 延迟扫描并自动点击「加入群聊/提交申请」（带安全阀）。 */
    private static void maybeAutoClickActivity(final Activity a, final String reason) {
        try {
            if (!sEnabled) return;
            if (a == null || a.isFinishing()) return;
            if (!isJoinConfirmCandidate(a)) return;
            final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null) return;
            final String cn = a.getClass().getName();
            // v3.0.542: ChattingUI 出现 = 已进群成功。用户要求纯后台静默，因此不仅复位
            // 静默标志，还必须立即把微信任务退回后台，绝不跳到新群聊天页。
            if (cn.contains("ChattingUI")) {
                if (sSilentJoin) {
                    sSilentJoin = false;
                    // v3.0.543: 记录「群码 → 群 username」映射，供已加入群过滤。
                    // 只有静默扫码流程内出现的 ChattingUI 才关联当前群码，避免误关联。
                    try {
                        String room = null;
                        Intent it = a.getIntent();
                        if (it != null) {
                            room = it.getStringExtra("Chat_User");
                            if (room == null) room = it.getStringExtra("intent_chatroom_username");
                        }
                        if (room != null && room.endsWith("@chatroom") && sCurrentCode != null) {
                            JoinRecordStore.add(room, sCurrentCode);
                            LogWriter.log(TAG, "已记录群码映射 room=" + room);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "记录群码映射 err: " + t);
                    }
                    try {
                        if (!a.isFinishing()) {
                            boolean moved = a.moveTaskToBack(true);
                            LogWriter.log(TAG, "静默入群：ChattingUI 已出现立即退后台 moved=" + moved);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "ChattingUI 退后台 err: " + t);
                    }
                    LogWriter.log(TAG, "静默入群完成：ChattingUI 已出现，已退回后台");
                }
            }
            // v3.0.540 静默入群：群码 H5 页（MMWebViewUI）一出现立即退回后台，绝不让
            // 用户看到加群页面跳转。v3.0.539 改成"前台完成加载再退(25s 超时)"导致用户
            // 看到跳转页面，违背"纯后台静默"需求。退后台后 XWeb 仍可执行注入的 JS
            // （evaluateJavascript 不依赖前台渲染），FIND_BTN_JS 照常工作。
            if (sSilentJoin && System.currentTimeMillis() < sSilentJoinUntil) {
                String lc = cn.toLowerCase();
                boolean isWebPage = lc.contains("webview") || lc.contains("web_page")
                        || lc.contains("mmwebview") || cn.contains("MMWebViewUI");
                if (isWebPage) {
                    sSilentActivity = a;
                    if (!sSilentBackDone) {
                        sSilentBackDone = true;
                        // v3.0.541: 同步立即退后台（不再 post 下一帧）。onActivityCreated 回调时
                        // 窗口尚未真正绘制/显示，此刻 moveTaskToBack 用户在屏幕上完全看不到
                        // 加群 H5 页，杜绝「闪一下」。v3.0.540 用 post 导致 onResume 已执行、
                        // 页面已绘制一帧才退 → 用户看到闪屏。
                        try {
                            if (!a.isFinishing()) {
                                boolean moved = a.moveTaskToBack(true);
                                LogWriter.log(TAG, "静默入群：H5 页创建即退后台 activity=" + cn
                                        + " moved=" + moved);
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "静默退后台 err: " + t);
                        }
                        // 兜底：500ms 后若还在前台（个别 ROM 同步退失败），再退一次
                        final Runnable backTask = () -> {
                            try {
                                if (!a.isFinishing()) {
                                    boolean moved = a.moveTaskToBack(true);
                                    LogWriter.log(TAG, "静默入群：H5 页二次退后台 activity=" + cn
                                            + " moved=" + moved);
                                }
                            } catch (Throwable t2) {
                                LogWriter.log(TAG, "静默退后台2 err: " + t2);
                            }
                        };
                        new Handler(Looper.getMainLooper()).postDelayed(backTask, 500L);
                    }
                }
            }
            log2("确认页候选 [" + reason + "] activity=" + cn
                    + " 距入群请求=" + (System.currentTimeMillis() - sLastDealQBarTime) + "ms"
                    + " finishing=" + a.isFinishing());
            // onCreate 时页面尚未填充完，延迟 600ms；onResume 延迟 250ms。
            final long delay = "onCreate".equals(reason) ? 600L : 250L;
            decor.postDelayed(() -> {
                try {
                    // 安全阀：页面含支付/转账/好友验证等词则跳过，避免误点其它界面
                    if (hasDenyWord(decor)) {
                        LogWriter.log(TAG, "确认页含安全阀词，跳过自动点击 activity=" + cn);
                        return;
                    }
                    // v3.0.513: 同一群码只点击一次（点击成功才占位）
                    if (canJoinClick(sCurrentCode) && clickText(decor, JOIN_WORDS)) {
                        log2("★ 自动点击加入群聊 [" + reason + "] activity=" + cn);
                        return;
                    }
                    if (sAutoApply && clickText(decor, APPLY_WORDS)) {
                        LogWriter.log(TAG, "★ 自动提交申请 [" + reason + "] activity=" + cn);
                    }
                    // v3.0.504: H5 群链接页（MMWebViewUI）按钮在 DOM 里，注入 JS 点击。
                    // onResume 时页面可能未加载完，分多轮注入。
                    final Runnable injectOnce = () -> injectJoinJs(decor, cn);
                    decor.postDelayed(injectOnce, 900L);
                    decor.postDelayed(injectOnce, 2000L);
                    decor.postDelayed(injectOnce, 3500L);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "autoClickJoinActivity err: " + t);
                }
            }, delay);
        } catch (Throwable t) {
            LogWriter.log(TAG, "maybeAutoClickActivity err: " + t);
        }
    }

    /** 视图树中是否存在安全阀词（支付/转账/收款/输入密码/好友验证）。 */
    private static boolean hasDenyWord(View v) {
        if (v == null) return false;
        List<TextView> tvs = new ArrayList<>();
        collectText(v, tvs);
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            String s = c.toString();
            for (String bad : DENY_WORDS) {
                if (s.contains(bad)) return true;
            }
        }
        return false;
    }

    private static boolean matchDialog(View v, String[] words) {
        List<TextView> tvs = new ArrayList<>();
        collectText(v, tvs);
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            String s = c.toString();
            for (String bad : DENY_WORDS) {
                if (s.contains(bad)) return false;   // 安全阀：见到支付/转账等直接放弃
            }
        }
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            for (String w : words) {
                if (c.toString().contains(w)) return true;
            }
        }
        return false;
    }

    private static void clickJoin(Dialog d) {
        if (!canJoinClick(sCurrentCode)) {   // v3.0.513: 同一群码 60s 只点一次
            log2("clickJoin 已处理过该群码，跳过");
            return;
        }
        if (d == null || !d.isShowing()) {
            log2("clickJoin 跳过: d=" + d + " isShowing=" + (d != null && d.isShowing()));
            return;
        }
        try {
            View decor = d.getWindow().getDecorView();
            log2("clickJoin 开始 dialog=" + d.getClass().getName() + " decor=" + decor.getClass().getName());
            if (clickText(decor, JOIN_WORDS)) {
                log2("clickJoin 文案点击成功");
                cleanupTempFiles();   // v3.0.535: 已点击加入群聊 = 进群成功，删除临时原图
                return;
            }
            log2("clickJoin 文案未命中，尝试 android.R.id.button1");
            View btn = d.findViewById(android.R.id.button1);
            if (btn != null && btn.isClickable()) {
                boolean r = btn.performClick();
                log2("自动点击 button1 结果=" + r + " class=" + btn.getClass().getName());
                if (r) cleanupTempFiles();   // v3.0.535
            } else {
                log2("button1 不可用: btn=" + btn + " clickable=" + (btn != null && btn.isClickable()));
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "clickJoin err: " + t);
        }
    }

    /** 遍历视图树找文案命中且可点击的 TextView（或向上找可点击父级）执行点击。
     *  v3.0.506: 每步打双通道日志 + contentDescription 兜底。 */
    private static boolean clickText(View root, String[] words) {
        if (root == null) return false;
        List<TextView> tvs = new ArrayList<>();
        collectText(root, tvs);
        log2("clickText 扫描 " + tvs.size() + " 个 TextView root=" + root.getClass().getName());
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            String s = c.toString();
            for (String w : words) {
                if (!s.contains(w)) continue;
                log2("  命中文案: \"" + s + "\" word=" + w + " view=" + t.getClass().getName()
                        + " clickable=" + t.isClickable() + " hasListener=" + t.hasOnClickListeners());
                View target = t;
                if (!target.isClickable() && !target.hasOnClickListeners()) {
                    View cur = t;
                    int depth = 0;
                    while (cur != null) {
                        if (cur.isClickable() || cur.hasOnClickListeners()) {
                            target = cur;
                            log2("    向上找到可点击父级 depth=" + depth + " class=" + cur.getClass().getName());
                            break;
                        }
                        cur = (View) cur.getParent();
                        depth++;
                    }
                }
                if (target.isClickable() || target.hasOnClickListeners()) {
                    boolean r = target.performClick();
                    log2("  执行 performClick 结果=" + r + " target=" + target.getClass().getName());
                    return r;
                } else {
                    log2("  无法点击 target=" + target.getClass().getName() + "（自身及父级均无可点击）");
                }
            }
        }
        // 兜底：部分按钮无文字（只有图片/描述），TextView 扫不到
        try {
            log2("clickText TextView 未命中，检查 contentDescription");
            if (clickDesc(root, words)) {
                log2("clickText contentDescription 点击成功");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** contentDescription 命中文案时点击（按钮无文字只有描述的场景）。 */
    private static boolean clickDesc(View root, String[] words) {
        if (root == null) return false;
        try {
            CharSequence desc = root.getContentDescription();
            if (desc != null) {
                String s = desc.toString();
                for (String w : words) {
                    if (s.contains(w)) {
                        log2("  [desc] 命中 \"" + s + "\" view=" + root.getClass().getName());
                        View target = root;
                        while (target != null && !target.isClickable() && !target.hasOnClickListeners()) {
                            target = (View) target.getParent();
                        }
                        if (target != null && (target.isClickable() || target.hasOnClickListeners())) {
                            boolean r = target.performClick();
                            log2("  [desc] 执行点击结果=" + r + " target=" + target.getClass().getName());
                            return r;
                        }
                        log2("  [desc] 无点击目标");
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (clickDesc(g.getChildAt(i), words)) return true;
            }
        }
        return false;
    }

    private static void collectText(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectText(g.getChildAt(i), out);
            }
        }
    }

    /** v3.0.508: 递归查找 WebView（XWeb/系统 WebView）。
     *  群链接 weixin.qq.com/g/… 由 MMWebViewUI 承载，「进入群聊」是页面 DOM 按钮，
     *  View 树遍历找不到，必须注入 JS。
     *  实机教训：WebViewKeyboardLinearLayout 只是容器（类名含 WebView 但没有
     *  evaluateJavascript/loadUrl），旧代码命中后直接 return，真正 XWeb WebView
     *  在它的子级里，永远没被注入 → 用户只能手动点「进入群聊」。
     *  v3.0.507 实锤：JOIN_JS 的 .click() 返回 clicked 但微信 H5 按钮监听触摸事件，
     *  不响应合成 click。v3.0.508 改为 FIND_BTN_JS 取按钮坐标 → Java 侧向 WebView
     *  派发 MotionEvent 真实触摸（浏览器内核视为真实输入，isTrusted=true）。 */
    private static void injectJoinJs(View root, String cn) {
        if (root == null) return;
        try {
            if (isLikelyWebView(root)) {
                Integer h = System.identityHashCode(root);
                // v3.0.538: 60s 内不重复注入；超时后允许重新注入（页面重建/hashcode 复用场景）
                long now = System.currentTimeMillis();
                Long last = sInjectedWebViews.get(h);
                if (last != null && now - last < 60000L) return;
                sInjectedWebViews.put(h, now);
                // v3.0.507: 装触摸探针，用户手动点击时打印坐标+元素反查（诊断用）。
                installTouchSpy(root, cn);
                // v3.0.508: 找按钮坐标 → MotionEvent 真实点击 → 验证。
                startAutoJoin(root, cn);
                return; // 已命中真正 WebView，不再向下找
            }
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    injectJoinJs(g.getChildAt(i), cn);
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "injectJoinJs err: " + t);
        }
    }

    /** v3.0.542: 自动加入流程入口：JOIN_JS click() 轮询优先。
     *  8.0.79 实测：FIND_BTN 坐标换算正确（scale=3.18=1272/400）但 dispatchTouchEvent
     *  注入的 MotionEvent 不被 MMWebView 消费（TAP 三次按钮纹丝不动），而 JOIN_JS 的
     *  .click() 返回 clicked 后微信立即跳转进群。因此主路径改为 JOIN_JS 轮询，
     *  每 1s 重试直到页面渲染出按钮并点击成功；15 次仍 notfound 才退回坐标 TAP 兜底。 */
    private static void startAutoJoin(final View wv, final String cn) {
        injectJoinLoop(wv, cn, 0);
    }

    /** v3.0.542: JOIN_JS 注入轮询：clicked → 进群成功；notfound → 页面未渲染，1s 重试。 */
    private static void injectJoinLoop(final View wv, final String cn, final int attempt) {
        if (attempt > 15) {
            LogWriter.log(TAG, "JOIN_JS 15 次未点击成功，转 FIND_BTN 坐标 TAP 兜底 activity=" + cn);
            findJoinButton(wv, cn, 0);
            return;
        }
        try {
            Method m = wv.getClass().getMethod("evaluateJavascript",
                    String.class, android.webkit.ValueCallback.class);
            m.setAccessible(true);
            m.invoke(wv, JOIN_JS, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    try {
                        LogWriter.log(TAG, "JOIN_JS 返回: " + value + " activity=" + cn
                                + " attempt=" + attempt + " webview=" + wv.getClass().getName());
                        if (value != null && value.contains("clicked")) {
                            // v3.0.542: click() 已触发按钮（实测 8.0.79 有效）
                            LogWriter.log(TAG, "★ JOIN_JS 点击成功，等待微信跳转 activity=" + cn);
                            cleanupTempFiles();
                            backToBackgroundNow();
                            return;
                        }
                        // notfound：页面可能未加载完，1s 后重试
                        wv.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                injectJoinLoop(wv, cn, attempt + 1);
                            }
                        }, 1000L);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "injectJoinLoop callback err: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "injectJoinLoop err: " + t);
        }
    }

    /** v3.0.541: 注入 FIND_BTN_JS 查找加群按钮，最多重试 15 次（每次 1s），
     *  命中后按 innerWidth 修正坐标再真实点击。 */
    private static void findJoinButton(final View wv, final String cn, final int attempt) {
        if (attempt > 15) {
            // v3.0.538: 15 次仍找不到按钮，放弃本轮；相同群码 10s 窗口过后
            // 下一条同码消息会重新触发入群流程，形成天然重试。
            LogWriter.log(TAG, "FIND_BTN 15 次未找到按钮，放弃本轮（等待下一条消息自动重试） activity=" + cn);
            return;
        }
        try {
            Method m = wv.getClass().getMethod("evaluateJavascript",
                    String.class, android.webkit.ValueCallback.class);
            m.setAccessible(true);
            m.invoke(wv, FIND_BTN_JS, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    try {
                        if (value == null || value.contains("notfound") || value.length() < 5) {
                            // 页面可能未加载完：1s 后重试
                            wv.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    findJoinButton(wv, cn, attempt + 1);
                                }
                            }, 1000L);
                            return;
                        }
                        LogWriter.log(TAG, "FIND_BTN(" + cn + "): " + value);
                        String v = value.replace("\"", "").trim();
                        if (v.startsWith("iframe:")) v = v.substring(7);
                        String[] parts = v.split("\\|");
                        if (parts.length < 1) return;
                        String[] nums = parts[0].split(",");
                        if (nums.length < 4) return;
                        final float left = Float.parseFloat(nums[0]);
                        final float top = Float.parseFloat(nums[1]);
                        final float w = Float.parseFloat(nums[2]);
                        final float h = Float.parseFloat(nums[3]);
                        if (w <= 0f || h <= 0f) return;
                        // v3.0.541: 从 JS 返回值提取真实视口宽/高/dpr（WebView.getScale() 在
                        // 8.0.79 返回错误值 3.18，导致 TAP 落空点不到按钮）
                        float iw = 0f;
                        float ih = 0f;
                        float dpr = 0f;
                        for (int i = 3; i < parts.length; i++) {
                            String p = parts[i];
                            if (p.startsWith("iw=")) {
                                try { iw = Float.parseFloat(p.substring(3)); } catch (NumberFormatException ignored) {}
                            } else if (p.startsWith("ih=")) {
                                try { ih = Float.parseFloat(p.substring(3)); } catch (NumberFormatException ignored) {}
                            } else if (p.startsWith("dpr=")) {
                                try { dpr = Float.parseFloat(p.substring(3)); } catch (NumberFormatException ignored) {}
                            }
                        }
                        final float fIw = iw, fIh = ih, fDpr = dpr;
                        // 必须在主线程读 getWidth()（View 尺寸非线程安全），顺便统一在主线程 TAP
                        wv.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    float scale;
                                    // 首选：WebView 物理宽度 / CSS 视口宽（最准，兼容页面缩放）
                                    if (fIw > 0f && wv.getWidth() > 0) {
                                        scale = wv.getWidth() / fIw;
                                    } else if (fDpr > 0f) {
                                        scale = fDpr;
                                    } else {
                                        scale = getScale(wv);
                                    }
                                    if (scale <= 0f) scale = 1f;
                                    float cx = (left + w / 2f) * scale;
                                    float cy = (top + h / 2f) * scale;
                                    LogWriter.log(TAG, "FIND_BTN 坐标换算 css=(" + left + "," + top + ","
                                            + w + "x" + h + ") iw=" + fIw + " dpr=" + fDpr
                                            + " scale=" + scale + " → tap=(" + Math.round(cx) + ","
                                            + Math.round(cy) + ")");
                                    tapWebView(wv, cn, cx, cy);
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "findJoinButton tap err: " + t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "findJoinButton parse err: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "findJoinButton err: " + t);
        }
    }

    /** v3.0.508: 向 WebView 派发真实 MotionEvent（DOWN → 80ms → UP）。
     *  事件从内核层进入页面，isTrusted=true，Zepto tap / touchstart / click 全部响应。 */
    private static void tapWebView(final View wv, final String cn, final float x, final float y) {
        wv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now,
                            MotionEvent.ACTION_DOWN, x, y, 0);
                    wv.dispatchTouchEvent(down);
                    down.recycle();
                    LogWriter.log(TAG, "TAP_DOWN " + cn + " at " + Math.round(x) + "," + Math.round(y)
                            + " webview=" + wv.getClass().getName());
                    wv.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                long now2 = SystemClock.uptimeMillis();
                                MotionEvent up = MotionEvent.obtain(now2, now2,
                                        MotionEvent.ACTION_UP, x, y, 0);
                                wv.dispatchTouchEvent(up);
                                up.recycle();
                                LogWriter.log(TAG, "TAP_UP " + cn + " at " + Math.round(x) + "," + Math.round(y));
                                // 1.5s 后验证按钮状态：gone/loading=已触发，否则 JOIN_JS click() 兜底
                                wv.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        checkJoinState(wv, cn);
                                    }
                                }, 1500L);
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "tapWebView up err: " + t);
                            }
                        }
                    }, 80L);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "tapWebView err: " + t);
                }
            }
        });
    }

    /** v3.0.541: 点击后检查按钮状态。gone/loading → MotionEvent 已触发；按钮仍在 → 用
     *  修正后的坐标重新 TAP（最多 3 次），仍失败才 JOIN_JS click() 兜底。
     *  v3.0.540 前：按钮还在但 canJoinClick 因 10s 窗口返回 false → JOIN_JS 兜底被跳过，
     *  一旦首次 TAP 坐标算错就永久无补救。v3.0.541 解除该误拦。 */
    private static final Map<String, Integer> TAP_RETRY = new ConcurrentHashMap<>();
    private static final int MAX_TAP_RETRY = 3;

    private static void checkJoinState(final View wv, final String cn) {
        try {
            Method m = wv.getClass().getMethod("evaluateJavascript",
                    String.class, android.webkit.ValueCallback.class);
            m.setAccessible(true);
            m.invoke(wv, CHECK_BTN_JS, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    LogWriter.log(TAG, "CHECK_BTN(" + cn + "): " + value);
                    if (value == null) return;
                    if (value.contains("gone") || value.contains("loading")) {
                        // 已触发成功，等微信跳转
                        cleanupTempFiles();   // v3.0.535: H5 已点击进入群聊 = 进群成功，删除临时原图
                        // v3.0.539: 点击已确认触发，立即把微信任务退回后台（静默入群）
                        backToBackgroundNow();
                        return;
                    }
                    // 按钮仍在：先重试真实触摸（坐标已修正），再 JOIN_JS 兜底
                    String code = sCurrentCode;
                    int retry = 0;
                    if (code != null) {
                        Integer r = TAP_RETRY.get(code);
                        retry = r == null ? 0 : r;
                    }
                    if (retry < MAX_TAP_RETRY) {
                        if (code != null) TAP_RETRY.put(code, retry + 1);
                        LogWriter.log(TAG, "按钮仍在，重新定位坐标 TAP 重试 " + (retry + 1) + "/"
                                + MAX_TAP_RETRY + " activity=" + cn);
                        findJoinButton(wv, cn, 0);   // 重新取坐标（新 scale 算法）再真实点击
                        return;
                    }
                    LogWriter.log(TAG, "TAP 重试达上限，JOIN_JS click() 兜底 activity=" + cn);
                    tryInjectJs(wv, cn, JOIN_JS);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "checkJoinState err: " + t);
        }
    }

    /** v3.0.539: 静默入群点击成功后立即把微信任务退回后台（只退一次）。
     *  此时 H5 已触发加入请求，微信开始处理跳转，退后台用户无感知。 */
    private static void backToBackgroundNow() {
        try {
            Activity a = sSilentActivity;
            if (a != null && !a.isFinishing()) {
                boolean moved = a.moveTaskToBack(true);
                LogWriter.log(TAG, "静默入群：点击成功后立即退后台 activity="
                        + a.getClass().getName() + " moved=" + moved);
            }
            sSilentActivity = null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "backToBackgroundNow err: " + t);
        }
    }

    /** 判断是否真正的 WebView（有 JS 注入能力），而不是「类名带 WebView 的容器」。 */
    private static boolean isLikelyWebView(View v) {
        String cls = v.getClass().getName();
        if (cls.equals("android.webkit.WebView")) return true;
        if (cls.startsWith("com.tencent.xweb") || cls.startsWith("com.tencent.smtt")) return true;
        if (cls.contains("WebView") && !cls.contains("Layout")
                && !cls.contains("Container") && !cls.contains("Wrapper")
                && !cls.contains("ViewGroup")) {
            // 反射确认有 JS 注入方法，避免把容器当 WebView 提前 return
            try {
                v.getClass().getMethod("evaluateJavascript", String.class,
                        android.webkit.ValueCallback.class);
                return true;
            } catch (NoSuchMethodException e) {
                try {
                    v.getClass().getMethod("loadUrl", String.class);
                    return true;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return false;
    }

    /** 尝试向 WebView 注入 JOIN_JS，evaluateJavascript 失败则回退 loadUrl。 */
    private static boolean tryInjectJs(final View wv, final String cn, String js) {
        try {
            Method m = wv.getClass().getMethod("evaluateJavascript",
                    String.class, android.webkit.ValueCallback.class);
            m.setAccessible(true);
            // v3.0.507: 传 ValueCallback，把 JS 返回的 clicked/notfound 打出来，不再盲注。
            m.invoke(wv, js, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    LogWriter.log(TAG, "JOIN_JS 返回: " + value + " activity=" + cn
                            + " webview=" + wv.getClass().getName());
                }
            });
            LogWriter.log(TAG, "★ 注入 JS 点击加入群聊 [H5] activity=" + cn
                    + " webview=" + wv.getClass().getName());
            return true;
        } catch (Throwable t) {
            try {
                Method m2 = wv.getClass().getMethod("loadUrl", String.class);
                m2.setAccessible(true);
                m2.invoke(wv, "javascript:" + js);
                LogWriter.log(TAG, "★ 注入 JS(loadUrl) 点击加入群聊 [H5] activity=" + cn
                        + " webview=" + wv.getClass().getName());
                return true;
            } catch (Throwable t2) {
                LogWriter.log(TAG, "injectJoinJs 失败 " + wv.getClass().getName() + ": " + t2);
                return false;
            }
        }
    }

    // ================= v3.0.507 触摸探针 + DOM 反查 =================

    /** 在 WebView 上装 OnTouchListener：用户手动点击时记录坐标，并在抬手后
     *  用 elementFromPoint 反查该点元素 + dump 全页面可点元素清单。
     *  用于实机确认「进入群聊」按钮的 DOM 结构，定位 JOIN_JS 点击不到的原因。 */
    private static void installTouchSpy(final View wv, final String cn) {
        if (wv == null) return;
        final Integer h = System.identityHashCode(wv);
        if (sSpyWebViews.contains(h)) return;
        try {
            wv.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;

                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    try {
                        float x = ev.getX(), y = ev.getY();
                        int action = ev.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            downX = x;
                            downY = y;
                            LogWriter.log(TAG, "TOUCH_DOWN " + Math.round(x) + "," + Math.round(y)
                                    + " scale=" + getScale(v) + " activity=" + cn);
                        } else if (action == MotionEvent.ACTION_UP) {
                            LogWriter.log(TAG, "TOUCH_UP " + Math.round(x) + "," + Math.round(y)
                                    + " (down " + Math.round(downX) + "," + Math.round(downY) + ") activity=" + cn);
                            final int fx = Math.round(x), fy = Math.round(y);
                            // 等 200ms 让点击事件先执行，再反查元素
                            v.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    hitTest(wv, cn, fx, fy);
                                    dumpDom(wv, cn);
                                }
                            }, 200L);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "touchSpy err: " + t);
                    }
                    return false; // 不拦截，WebView 正常消费触摸
                }
            });
            sSpyWebViews.add(h);
            LogWriter.log(TAG, "★ 触摸探针已装 activity=" + cn + " webview=" + wv.getClass().getName());
            // 页面可能还在加载：自动 dump 两次 DOM，先看到按钮在不在、长什么样
            wv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dumpDom(wv, cn);
                }
            }, 2500L);
            wv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dumpDom(wv, cn);
                }
            }, 6000L);
        } catch (Throwable t) {
            LogWriter.log(TAG, "installTouchSpy err: " + t);
        }
    }

    /** 获取 WebView 缩放比例（CSS 坐标 = 触摸坐标 / scale）。 */
    private static float getScale(View wv) {
        try {
            Method m = wv.getClass().getMethod("getScale");
            m.setAccessible(true);
            Object r = m.invoke(wv);
            if (r instanceof Number) return ((Number) r).floatValue();
        } catch (Throwable ignored) {
        }
        return 1f;
    }

    /** 触摸点元素反查：elementFromPoint，若命中 iframe 则进 iframe 内部再查。 */
    private static void hitTest(final View wv, final String cn, int rawX, int rawY) {
        try {
            float s = getScale(wv);
            if (s <= 0f) s = 1f;
            final int cssX = Math.round(rawX / s), cssY = Math.round(rawY / s);
            String js = "(function(){function chain(e){var s=e,c='';while(s&&c.length<400){" +
                    "var cn='';try{cn=(typeof s.className==='string')?String(s.className).split(' ').slice(0,4).join('.'):'';}catch(ex){}" +
                    "c+='>'+(s.tagName?s.tagName:'?')+(s.id?'#'+s.id:'')+(cn?'.'+cn:'');s=s.parentElement;}return c;}" +
                    "function desc(e){var r=e.getBoundingClientRect();return e.tagName+'|'+(e.innerText||e.textContent||'').replace(/\\s/g,'').substring(0,40)+" +
                    "'|rect='+Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+'x'+Math.round(r.height)+'|chain='+chain(e);}" +
                    "try{var e=document.elementFromPoint(" + cssX + "," + cssY + ");if(!e)return 'HIT_NONE " + cssX + "," + cssY + "';" +
                    "if(e.tagName==='IFRAME'){try{var fr=e.getBoundingClientRect();var d=e.contentDocument;" +
                    "if(d){var i=d.elementFromPoint(" + cssX + "-Math.round(fr.left)," + cssY + "-Math.round(fr.top));if(i)return 'HIT_IFRAME:" + cssX + "," + cssY + "|frame='+desc(e)+'|inner='+desc(i);}}catch(ex){}" +
                    "}" +
                    "return 'HIT:" + cssX + "," + cssY + "|'+desc(e);}catch(e){return 'HIT_ERR:" + cssX + "," + cssY + "|'+e.message;}})();";
            final String fjs = js;
            Method m = wv.getClass().getMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class);
            m.setAccessible(true);
            m.invoke(wv, fjs, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    LogWriter.log(TAG, "HIT(" + cn + " touch=" + rawX + "," + rawY + " css=" + cssX + "," + cssY + "): " + value);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hitTest err: " + t);
        }
    }

    /** Dump 当前 DOM 可点元素清单（主文档 + iframe）。 */
    private static void dumpDom(final View wv, final String cn) {
        try {
            Method m = wv.getClass().getMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class);
            m.setAccessible(true);
            m.invoke(wv, DUMP_DOM_JS, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    LogWriter.log(TAG, "DOM(" + cn + "): " + value);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpDom err: " + t);
        }
    }

    /** ⑧ 进群回执：LauncherUI handleJump(Intent) after。 */
    private static void hookLauncherUI(ClassLoader cl) {
        try {
            Class<?> launcher = WxResolver.get("ui_LauncherUI", cl);
            boolean hooked = false;
            for (Method m : launcher.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1 || pts[0] != Intent.class) continue;
                if (m.getReturnType() != void.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Intent i = (Intent) p.args[0];
                            if (i == null) return;
                            String room = i.getStringExtra("enter_conversation_from_liteapp");
                            if (room == null) room = i.getStringExtra("Chat_User");
                            if (room != null && room.endsWith("@chatroom")) {
                                if (DONE_ROOM.add(room)) {
                                    LogWriter.log(TAG, "★★★ 已进群: " + room);
                                    // v3.0.516: 记录进群历史（设置页「查看进群记录」）
                                    try {
                                        JoinRecordStore.add(room);
                                    } catch (Throwable jre) {
                                        LogWriter.log(TAG, "JoinRecordStore err: " + jre.getMessage());
                                    }
                                    // v3.0.543: 静默扫码流程中，同时关联群码（已加入群过滤用）
                                    if (sSilentJoin && sCurrentCode != null) {
                                        try {
                                            JoinRecordStore.add(room, sCurrentCode);
                                            LogWriter.log(TAG, "已记录群码映射(LauncherUI) room=" + room);
                                        } catch (Throwable jre2) {
                                            LogWriter.log(TAG, "群码映射 LauncherUI err: " + jre2.getMessage());
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "launcher jump err: " + t);
                        }
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "已 hook LauncherUI.handleJump " + m.getName());
                break;
            }
            if (!hooked) LogWriter.log(TAG, "LauncherUI handleJump 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookLauncherUI err: " + t.getMessage());
        }
    }

    /** ⑨ 需验证群申请页：出现即记录群 ID，并自动点「提交申请」。 */
    private static void hookVerifyUI(ClassLoader cl) {
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!sEnabled || !sAutoApply) return;
                    try {
                        Activity a = (Activity) p.thisObject;
                        if (a == null) return;
                        String cn = a.getClass().getName();
                        if (cn == null || !cn.contains("RoomAccessVerifyApplicationByQrOrInvitationUI")) {
                            return;
                        }
                        String room = a.getIntent() != null
                                ? a.getIntent().getStringExtra("intent_chatroom_username") : null;
                        LogWriter.log(TAG, "⚠ 需验证群申请页 room=" + room);
                        final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
                        if (decor != null) {
                            decor.postDelayed(() -> {
                                if (clickText(decor, APPLY_WORDS)) {
                                    LogWriter.log(TAG, "已自动提交加群申请 room=" + room);
                                }
                            }, 500);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "verify ui err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook 需验证群申请页");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookVerifyUI err: " + t.getMessage());
        }
    }

    /** 诊断+自动点击：hook pe5.f.j 接口（微信加群 API 真实调用点：
     *  vf0.e.bj().j(room, members, ticket, localHistoryInfo)），
     *  捕获真实参数；最近 60s 发起过入群请求时直接调 task.a() 发送（等效点击「进入群聊」）。
     *  同时保留 ln.a.j / qn.m.<init> / vn.m.b / factory.c.a() 兜底诊断。 */
    private static void hookJoinApi(ClassLoader cl) {
        // v3.0.497: 微信 8.0.79 真实加群 API 宿主 = nf5.f 接口（实现 ln.a），j(String,List,String,Object)
        //   pe5.f 已删除；qn.n 是网络场景 NetSceneAddChatRoomMember（响应对象）非 API 宿主。
        Class<?> pe5f = null;
        try { pe5f = XposedHelpers.findClass("pe5.f", cl); } catch (Throwable ignored) {}
        if (pe5f == null) {
            try { pe5f = XposedHelpers.findClass("nf5.f", cl); } catch (Throwable ignored) {}
        }
        if (pe5f == null) {
            try { pe5f = XposedHelpers.findClass("ln.a", cl); } catch (Throwable ignored) {}
        }
        if (pe5f == null) {
            try {
                java.util.List<String> cands = com.leshao.v3.hook.DexKitHelper.findClassesByString(cl, "MicroMsg.NetSceneAddChatRoomMember");
                if (cands != null) {
                    for (String cn : cands) {
                        if (cn == null || !cn.startsWith("com.tencent.mm.") || cn.contains("$")) continue;
                        try { pe5f = XposedHelpers.findClass(cn, cl); break; } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (pe5f == null) {
            LogWriter.log(TAG, "hookJoinApi: 加群 API 类未定位（继续安装 ln.a/qn.m/factory.c 诊断钩子）");
        } else {
            for (Method m : pe5f.getDeclaredMethods()) {
                if (!"j".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 3) continue;
                // nf5.f 是接口：抽象方法无法 hook，且 hook 接口方法无意义（实际调用在实现类），跳过
                if (Modifier.isAbstract(m.getModifiers())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[JOIN-API] room=" + p.args[0]
                                    + " members=" + p.args[1]
                                    + " ticket=" + p.args[2]);
                            // ★ 最近 60s 发起过入群请求 → 自动点「进入群聊」：直接发送
                            Object task = p.getResult();
                            String joinRoom = p.args[0] == null ? null : String.valueOf(p.args[0]);
                            if (task != null
                                    && System.currentTimeMillis() - sLastDealQBarTime <= 60000L
                                    && canSendJoin(joinRoom)) {   // v3.0.513: 同一群 30s 只发一次
                                try {
                                    // v3.0.494: factory.c.a() 是取消，必须用 b() 发送！
                                    XposedHelpers.callMethod(task, "b");
                                    LogWriter.log(TAG, "已自动发送 addchatroommember（模拟点击进入群聊）");
                                    cleanupTempFiles();   // v3.0.535: 进群成功，删除临时原图
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "自动发送 err: " + t);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook 加群 API j args=" + m.getParameterTypes().length);
                break;
            }
        }
        try {
            Class<?> cls = XposedHelpers.findClass("ln.a", cl);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"j".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 3) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[JOIN] room=" + p.args[0]
                                    + " members=" + p.args[1]
                                    + " ticket=" + p.args[2]);
                        } catch (Throwable ignored) {}
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "已 hook ln.a.j args=" + m.getParameterTypes().length);
                break;
            }
            if (!hooked) LogWriter.log(TAG, "ln.a.j 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi err: " + t);
        }
        // 更精确：直接 hook NetSceneAddChatRoomMember <init>(room, members, ticket, extra)
        try {
            Class<?> qnm = XposedHelpers.findClass("qn.m", cl);
            for (java.lang.reflect.Constructor<?> m : qnm.getDeclaredConstructors()) {
                if (m.getParameterTypes().length < 3) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[★REAL-JOIN] room=" + p.args[0]
                                    + " members=" + p.args[1]
                                    + " ticket=" + p.args[2]);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook qn.m.<init> args=" + m.getParameterTypes().length);
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi qn.m err: " + t);
        }
        // 更可靠：hook vn.m.b（ChatRoomAddContactProcess，加群处理入口，签名 b(String,int)）
        try {
            Class<?> vnm = XposedHelpers.findClass("vn.m", cl);
            for (Method m : vnm.getDeclaredMethods()) {
                if (!"b".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1) continue;
                if (pts[0] != String.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[★JOIN-PROC] ticket=" + p.args[0]);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook vn.m.b");
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi vn.m err: " + t);
        }
        // 最兜底：hook roomsdk 任务执行器 factory.c.b()（真正发送方法，任意线程可调）
        // v3.0.494: 原 hook factory.c.a() 是「取消场景」方法，记录毫无意义，改为 b()。
        try {
            Class<?> facC = null;
            try { facC = XposedHelpers.findClass("com.tencent.mm.roomsdk.model.factory.c", cl); } catch (Throwable ignored) {}
            if (facC == null) {
                facC = WxResolver.get("roomsdk_model_factory_c", cl);
            }
            if (facC == null) {
                LogWriter.log(TAG, "hookJoinApi factory.c 类未定位");
            } else {
                for (Method m : facC.getDeclaredMethods()) {
                    if (!"b".equals(m.getName())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                LogWriter.log(TAG, "[SEND] factory.c.b() 发送 addchatroommember");
                            } catch (Throwable ignored) {}
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            // v3.0.535: 发送已执行 = 进群流程已发起，清理临时原图
                            cleanupTempFiles();
                        }
                    });
                    LogWriter.log(TAG, "已 hook factory.c.b()");
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi factory.c err: " + t);
        }
    }

    /** 诊断：hook NetSceneGetA8Key 的 N()（返回二维码 URL 解析出的群 username）。 */
    private static void hookA8KeyResult(ClassLoader cl) {
        try {
            Class<?> cls = WxResolver.get("modelsimple_k0", cl);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"N".equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[A8Key] username=" + p.getResult());
                        } catch (Throwable ignored) {}
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "已 hook k0.N()");
                break;
            }
            if (!hooked) LogWriter.log(TAG, "k0.N() 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookA8KeyResult err: " + t);
        }
    }

    /** 纯后台加群：调 addchatroommember（Cgi 120）。
     *  room=群 username（xxx@chatroom）、ticket=群二维码票据。
     *
     *  v3.0.494: 统一走与 BatchInviteManager 相同的微信原生链路：
     *    ni5.n0.c(dg0.e.class) → svc.yj(room) → factory.j(room, members, ticket, null)
     *    → task.b() 发送（factory.c.b() 内部 i1.q().b.g(scene) 入队）。
     *  ★ 绝不能调 task.a()：factory.c.a() 是「取消场景」—— b.d(scene) 出队 + 注销回调，
     *    之前调用 a() 导致扫码进群请求被直接取消，这就是扫码进群一直没反应的根因。
     *  旧链路 un.k.get() → j() 保留作兜底，同样使用 b() 发送。 */
    private static boolean joinGroupViaApi(ClassLoader cl, String roomUsername, String ticket) {
        try {
            Object task = null;
            // 首选：RoomServiceFactory 原生封装（与手动拉群同链路）
            try {
                Class<?> svcIf = XposedHelpers.findClass("dg0.e", cl);
                Object factory = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("ni5.n0", cl), "c", svcIf);
                if (factory != null) {
                    Object roomOp = XposedHelpers.callMethod(factory, "yj", roomUsername);
                    if (roomOp != null) {
                        task = XposedHelpers.callMethod(roomOp, "j", roomUsername,
                                java.util.Collections.singletonList(selfWxid(cl)), ticket, null);
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "joinGroupViaApi 新链路失败，转旧链路: " + t);
            }
            // 兜底：un.k 旧链路
            if (task == null) {
                Object api = XposedHelpers.callMethod(
                        XposedHelpers.newInstance(XposedHelpers.findClass("un.k", cl)), "get");
                if (api == null) {
                    LogWriter.log(TAG, "ln.a 实例为 null");
                    return false;
                }
                task = XposedHelpers.callMethod(api, "j", roomUsername,
                        java.util.Collections.singletonList(selfWxid(cl)), ticket, null);
            }
            if (task == null) {
                LogWriter.log(TAG, "addchatroommember 任务为 null");
                return false;
            }
            if (!canSendJoin(roomUsername)) {   // v3.0.513: 同一群 30s 只发一次
                LogWriter.log(TAG, "joinGroupViaApi 30s 内已发送过 room=" + roomUsername + "，跳过");
                return true;
            }
            XposedHelpers.callMethod(task, "b");
            LogWriter.log(TAG, "已调用 addchatroommember b() 发送 room=" + roomUsername
                    + " ticket=" + ticket + " self=" + selfWxid(cl));
            cleanupTempFiles();   // v3.0.535: 进群请求已发送 = 进群成功，删除临时原图
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "joinGroupViaApi err: " + t);
            return false;
        }
    }

    /** 获取当前登录账号 wxid。
     *  v3.0.533: b41.y1/gp0.j1 在 8.0.79 已删除 → 主路径改 StorageHub.selfWxid()
     *  （ConfigStorage q3.v(2,"") + SharedPreferences 兜底，已验证），旧路径保留兼容老版本。 */
    private static String selfWxid(ClassLoader cl) {
        try {
            String w = com.leshao.ai.hook.wechat.StorageHub.get().selfWxid();
            if (w != null && !w.isEmpty()) return w;
        } catch (Throwable ignored) {}
        try {
            Object self = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("b41.y1", cl), "u");
            if (self != null) return String.valueOf(self);
        } catch (Throwable ignored) {}
        try {
            Object self = XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(XposedHelpers.findClass("gp0.j1", cl), "x"), "d");
            if (self != null) return String.valueOf(self);
        } catch (Throwable ignored) {}
        try {
            String w = com.leshao.v3.model.ModuleConfig.getCurrentWxid();
            if (w != null && !w.isEmpty()) return w;
        } catch (Throwable ignored) {}
        return "";
    }

    /** 投喂图片给微信原生识别引擎（复刻长按识别：wk5.g0.h）。
     *  创建 wk5.g0(Activity, true, talker) → h(null, msgId, "", imgPath, bitmap,
     *  true, 2, true, callback) → 微信走 RecogQBarOfImageFileEvent → 解码 → DealQBarStrEvent → 加群。 */
    private static void triggerWxScan(ClassLoader cl, String talker, long msgId, String imgPath) {
        if (cl == null || imgPath == null || imgPath.isEmpty()) return;
        try {
            Activity act = anyActivity();
            if (act == null) {
                LogWriter.log(TAG, "triggerWxScan: 无可用 Activity，跳过投喂");
                return;
            }
            Object mgr = XposedHelpers.newInstance(
                    XposedHelpers.findClass("wk5.g0", cl),
                    act, true, talker == null ? "" : talker);
            Bitmap bmp = decodeSampledBitmap(imgPath, 4096);
            if (bmp == null) {
                LogWriter.log(TAG, "triggerWxScan: Bitmap 解码失败 " + imgPath);
                return;
            }
            Object callback = makeWk5nCallback(cl, mgr, talker, msgId, imgPath);
            XposedHelpers.callMethod(mgr, "h",
                    null,           // View（后台无锚点 View）
                    msgId,          // long msgId（必须真实值，走 ScanCodeInfo 加群分支）
                    "",             // 备用 path
                    imgPath,        // ★ 图片路径
                    bmp,            // Bitmap（非 null 避免截屏）
                    true,           // getCodePosition
                    2,              // ★ recognizeType=2（聊天图加群）
                    true,           // retry
                    callback);      // wk5.n 回调
            sLastDealQBarTime = System.currentTimeMillis();
            LogWriter.log(TAG, "已投喂微信识别图片 msgId=" + msgId + " path=" + imgPath);
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerWxScan err: " + t);
        }
    }

    /** 用动态代理实现 wk5.n 回调接口，微信识别结果回传后：
     *  ① 刷新保护窗；② 用 s6.a.a(event) 取微信解码引擎构造的 ImageQBarDataBean 列表
     *  （与微信 UI 层 w6.a 的 D2.a = s6.a.a(event) 完全一致），命中群码即调用
     *  wk5.g0.b(l0, bean, o) 让微信自己构造 DealQBarStrEvent 触发加群。 */
    private static Object makeWk5nCallback(ClassLoader cl, Object mgr, String talker, long msgId, String imgPath) {
        try {
            Class<?> iface = XposedHelpers.findClass("wk5.n", cl);
            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{iface},
                    (proxy, method, args) -> {
                        if ("a".equals(method.getName()) && args != null && args.length > 0) {
                            try {
                                Object ev = args[0];
                                String hit = null;   // 命中通道描述
                                Object bean = null;
                                // ① 主通道：s6.a.a(event) → ArrayList<ImageQBarDataBean>（微信构造，字段正确）
                                try {
                                    Object beanList = XposedHelpers.callStaticMethod(
                                            WxResolver.get("pluginsdk_ui_tools_s6", cl), "a", ev);
                                    if (beanList instanceof java.util.List) {
                                        for (Object b : (java.util.List<?>) beanList) {
                                            if (b == null) continue;
                                            Object c = XposedHelpers.getObjectField(b, "d");
                                            if (c instanceof String && isGroupQr((String) c)) {
                                                bean = b;
                                                hit = "s6.a.a";
                                                break;
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                // ② 对象图提取：遍历 event 及 g 字段对象图找 ImageQBarDataBean
                                if (bean == null) {
                                    bean = findGroupQrBeanInEvent(cl, ev);
                                    if (bean != null) hit = "对象图";
                                }
                                // ③ 兜底：event.g.b String 码列表 → 手动构造 bean
                                if (bean == null) {
                                    Object g = XposedHelpers.getObjectField(ev, "g");
                                    Object codes = g == null ? null : XposedHelpers.getObjectField(g, "b");
                                    if (codes instanceof java.util.List) {
                                        for (Object o : (java.util.List<?>) codes) {
                                            if (o instanceof String && isGroupQr((String) o)) {
                                                bean = newImageQBarDataBean(cl);
                                                if (bean != null) XposedHelpers.setObjectField(bean, "d", (String) o);
                                                hit = "event.g.b";
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (bean != null && hit != null) {
                                    String code = String.valueOf(XposedHelpers.getObjectField(bean, "d"));
                                    LogWriter.log(TAG, "wk5.n 结果回调命中群码 (" + hit + ")");
                                    onGroupQr(code);
                                    triggerDealQBar(mgr, code, bean, imgPath);
                                } else {
                                    LogWriter.log(TAG, "wk5.n 回调无可用 bean，跳过加群");
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
        } catch (Throwable t) {
            LogWriter.log(TAG, "makeWk5nCallback err: " + t);
            return null;
        }
    }

    /** 在 event 对象图中广度优先查找 ImageQBarDataBean（d 为群码 URL）。
     *  不依赖 s6 类名/方法签名，直接遍历 event 及其字段对象。 */
    private static Object findGroupQrBeanInEvent(ClassLoader cl, Object event) {
        if (cl == null || event == null) return null;
        try {
            Class<?> beanCls = WxResolver.get("plugin_scanner_ImageQBarDataBean", cl);
            java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
            java.util.Set<Object> visited = new java.util.HashSet<>();
            queue.add(event);
            while (!queue.isEmpty()) {
                Object obj = queue.poll();
                if (obj == null || visited.contains(obj)) continue;
                visited.add(obj);
                if (beanCls.isInstance(obj)) {
                    Object code = XposedHelpers.getObjectField(obj, "d");
                    if (code instanceof String && isGroupQr((String) code)) return obj;
                    continue;
                }
                if (obj instanceof java.util.List) {
                    for (Object item : (java.util.List<?>) obj) {
                        if (item != null && !visited.contains(item)) queue.add(item);
                    }
                }
                for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null && !visited.contains(v)) queue.add(v);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findGroupQrBeanInEvent err: " + t);
        }
        return null;
    }

    /** 复刻 ImageGalleryUI 上层回调：调用 wk5.g0.b(l0, ImageQBarDataBean, o)，
     *  让微信自己构造 DealQBarStrEvent（字段完整）→ 全局监听器 → v74.v.g → geta8key → 加群。
     *  bean 优先用微信解码引擎构造的实例（e/f 字段正确），否则手动构造。
     *  imgPath 写入 l0.m（→ DealQBarStrEvent.m → model.s bundle.path，缺失会打开扫一扫）。 */
    private static void triggerDealQBar(Object mgr, String code, Object bean, String imgPath) {
        ClassLoader cl = sCl;
        if (cl == null || mgr == null || code == null || bean == null) return;
        try {
            // ① wk5.l0 识别结果聚合体
            Object l0 = XposedHelpers.newInstance(XposedHelpers.findClass("wk5.l0", cl));
            List<String> codeList = new ArrayList<>();
            codeList.add(code);
            XposedHelpers.setObjectField(l0, "a", codeList);   // 码列表
            XposedHelpers.setIntField(l0, "i", 2);             // recognizeType=2
            XposedHelpers.setIntField(l0, "f", -1);             // scene 默认 37（加群）
            XposedHelpers.setIntField(l0, "g", -1);             // source 默认 4
            XposedHelpers.setBooleanField(l0, "d", false);     // 布尔透传
            if (imgPath != null) XposedHelpers.setObjectField(l0, "m", imgPath);  // ★ path → bundle
            // ② 确保 bean.d = 码值（若来自微信现成 bean，d 已经是码值，重复设置无副作用）
            XposedHelpers.setObjectField(bean, "d", code);
            // ★ 若 bean 是手动构造的（e/f 为 0），补上微信真实码场景/图片来源。
            //   hookScanResult 实测：微信长按识别群码时 e=19（码场景）、f=6（图片来源）。
            //   缺失时微信把 DealQBarStrEvent 当作未知来源 → 打开扫一扫而不是加群。
            try {
                if (XposedHelpers.getIntField(bean, "e") == 0) XposedHelpers.setIntField(bean, "e", 19);
                if (XposedHelpers.getIntField(bean, "f") == 0) XposedHelpers.setIntField(bean, "f", 6);
            } catch (Throwable ignored) {}
            // ③ wk5.o 回调（单方法接口，空实现）
            Object cb = makeWk5oCallback(cl);
            // ④ 调 b()
            XposedHelpers.callMethod(mgr, "b", l0, bean, cb);
            sLastDealQBarTime = System.currentTimeMillis();
            LogWriter.log(TAG, "已调用 wk5.g0.b 触发加群流程: " + code);
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerDealQBar err: " + t);
        }
    }

    /** wk5.o 回调接口动态代理（b() 参数，空实现即可）。 */
    private static Object makeWk5oCallback(ClassLoader cl) {
        try {
            Class<?> iface = XposedHelpers.findClass("wk5.o", cl);
            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{iface},
                    (proxy, method, args) -> null);
        } catch (Throwable t) {
            LogWriter.log(TAG, "makeWk5oCallback err: " + t);
            return null;
        }
    }

    /** 创建 ImageQBarDataBean 实例：优先无参构造，失败用 Unsafe.allocateInstance 绕过构造器。 */
    private static Object newImageQBarDataBean(ClassLoader cl) {
        Class<?> cls = WxResolver.get("plugin_scanner_ImageQBarDataBean", cl);
        try {
            return XposedHelpers.newInstance(cls);
        } catch (Throwable ignored) {}
        try {
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeCls.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            java.lang.reflect.Method allocate = unsafeCls.getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, cls);
        } catch (Throwable t) {
            LogWriter.log(TAG, "newImageQBarDataBean err: " + t);
            return null;
        }
    }

    // ==================== 工具 ====================

    /** 群二维码判定（手册 §4.1 唯一权威规则）。 */
    static boolean isGroupQr(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.trim();
        for (String p : GROUP_PREFIX) {
            if (t.startsWith(p)) return true;
        }
        return false;
    }

    /** 白名单过滤：空 = 全部群放行。 */
    private static boolean inWhitelist(String talker) {
        String wl = sWhitelist;
        if (wl == null || wl.isEmpty()) return true;
        for (String s : wl.split("[,，]")) {
            if (s.trim().equals(talker)) return true;
        }
        return false;
    }

    /** 每日入群上限（跨天自动重置）。 */
    private static synchronized boolean allowToday() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp == null) return true;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String last = sp.getString(K_TODAY, "");
            int count = sp.getInt(K_TODAY_COUNT, 0);
            if (!today.equals(last)) {
                sp.edit().putString(K_TODAY, today).putInt(K_TODAY_COUNT, 0).apply();
                count = 0;
            }
            if (count >= sMaxPerDay) return false;
            sp.edit().putInt(K_TODAY_COUNT, count + 1).apply();
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}
