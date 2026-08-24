package com.lspilot.voiceswitch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG="VoiceSwitchXposed";
    private final Set<View> h=new HashSet<>();
    private PopupWindow pw;

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if(!"com.tencent.mm".equals(lpp.packageName))return;
        Log.i(TAG,"loaded "+lpp.processName);
        try{
            Class<?> c=XposedHelpers.findClassIfExists("com.tencent.mm.pluginsdk.ui.chat.ChatFooter",lpp.classLoader);
            if(c==null){Log.e(TAG,"ChatFooter not found");return;}
            Log.i(TAG,"ChatFooter: "+c.getName());
            if(!hx(c))hc(c);
        }catch(Throwable t){Log.e(TAG,"Fatal",t);}
    }

    boolean hx(Class<?>c){try{XposedHelpers.findAndHookMethod(c,"x",View.class,String.class,new XC_MethodHook(){
        protected void afterHookedMethod(MethodHookParam p){String s=(String)p.args[1];
            if("chat_left_side_audio_btn".equals(s)||"chat_left_side_keyboard_btn".equals(s))inj((View)p.args[0]);}});
        Log.i(TAG,"[A]x hooked");return true;}catch(NoSuchMethodError e){Log.w(TAG,"[A]not found");return false;}}

    void hc(Class<?>c){XposedBridge.hookAllConstructors(c,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
        new Handler(Looper.getMainLooper()).postDelayed(()->{try{Field f=fd(p.thisObject.getClass(),"q");
            if(f!=null){f.setAccessible(true);inj((View)f.get(p.thisObject));}}catch(Throwable t){}},800);}});
        Log.i(TAG,"[B]ctors hooked");}

    void inj(View b){if(b==null)return;synchronized(h){if(h.contains(b))return;h.add(b);}
        Log.i(TAG,"Inject: "+b.getClass().getSimpleName());
        b.setOnLongClickListener(v->{sp(v);return true;});
        b.setOnTouchListener((v,e)->{if(e.getAction()==0)v.getParent().requestDisallowInterceptTouchEvent(true);return false;});}

    @SuppressLint("RtlHardcoded") void sp(View a){
        if(pw!=null&&pw.isShowing())pw.dismiss();
        Context c=a.getContext();
        int dp(int v){return(int)(v*c.getResources().getDisplayMetrics().density+0.5f);}
        int d1=dp(1),d8=dp(8),d10=dp(10),d12=dp(12),d14=dp(14),d16=dp(16),d20=dp(20),d130=dp(130),d240=dp(240);
        int CG=0xFFE8F5E9,CGT=0xFF4CAF50,CGY=0xFFFAFAFA,CDT=0xFF333333,CWH=0xFFFFFFFF,CLG=0xFFF5F5F5,CDV=0xFFE0E0E0,CBL=0xFF2196F3;

        LinearLayout r=new LinearLayout(c);r.setOrientation(0);r.setBackgroundColor(CLG);
        LinearLayout L=new LinearLayout(c);L.setOrientation(1);L.setBackgroundColor(CWH);L.setPadding(0,d8,0,d8);L.setMinimumWidth(d130);
        FrameLayout R=new FrameLayout(c);R.setPadding(d12,d8,d12,d8);

        TextView c0=new TextView(c);c0.setText("音频转语音");c0.setTextSize(14);c0.setPadding(d16,d14,d16,d14);c0.setGravity(Gravity.CENTER_VERTICAL);c0.setBackgroundColor(CG);c0.setTextColor(CGT);L.addView(c0);
        View dv0=new View(c);dv0.setBackgroundColor(CDV);dv0.setLayoutParams(new LinearLayout.LayoutParams(-1,d1));L.addView(dv0);
        TextView c1=new TextView(c);c1.setText("视频转语音");c1.setTextSize(14);c1.setPadding(d16,d14,d16,d14);c1.setGravity(Gravity.CENTER_VERTICAL);c1.setBackgroundColor(CGY);c1.setTextColor(CDT);L.addView(c1);
        View vd=new View(c);vd.setBackgroundColor(CDV);vd.setLayoutParams(new LinearLayout.LayoutParams(d1,-1));
        r.addView(L);r.addView(vd);r.addView(R);R.addView(bpA(c));

        c0.setOnClickListener(v->{c0.setBackgroundColor(CG);c0.setTextColor(CGT);c1.setBackgroundColor(CGY);c1.setTextColor(CDT);R.removeAllViews();R.addView(bpA(c));});
        c1.setOnClickListener(v->{c1.setBackgroundColor(CG);c1.setTextColor(CGT);c0.setBackgroundColor(CGY);c0.setTextColor(CDT);R.removeAllViews();R.addView(bpV(c));});

        pw=new PopupWindow(r,-2,-2,true);pw.setBackgroundDrawable(new ColorDrawable(0));pw.setElevation(d16);pw.setOutsideTouchable(true);
        int[]l=new int[2];a.getLocationOnScreen(l);pw.showAtLocation(a,Gravity.TOP|Gravity.START,l[0]-d8,l[1]-dp(420));Log.i(TAG,"Panel shown");}

    LinearLayout bpA(Context c){return bp(c,"音频转语音设置",new String[]{"实时语音转文字","长按录音模式","语音结束自动发送"},new boolean[]{true,true,false},"识别语言",new String[]{"普通话","粤语","英语","日语","韩语"},"开始语音转换",0xFF4CAF50);}

    LinearLayout bpV(Context c){return bp(c,"视频转语音设置",new String[]{"自动提取音频","后台自动转换","转换完成通知"},new boolean[]{true,false,true},"音频质量",new String[]{"标准(128kbps)","高清(256kbps)","无损(原始)"},"提取视频音频",0xFF2196F3);}

    LinearLayout bp(Context c,String title,String[]sw,boolean[]dv,String lbl,String[]items,String bt,int bc){int dp(int v){return(int)(v*c.getResources().getDisplayMetrics().density+0.5f);}
        LinearLayout p=new LinearLayout(c);p.setOrientation(1);p.setMinimumWidth(dp(240));p.setPadding(4,0,4,0);
        TextView t=new TextView(c);t.setText(title);t.setTextSize(16);t.setTextColor(0xFF333333);t.setPadding(0,0,0,dp(12));p.addView(t);
        Switch[]ss=new Switch[sw.length];
        for(int i=0;i<sw.length;i++){ss[i]=new Switch(c);ss[i].setText(sw[i]);ss[i].setTextSize(14);ss[i].setTextColor(0xFF333333);ss[i].setChecked(dv[i]);ss[i].setPadding(0,dp(8),0,dp(8));p.addView(ss[i]);if(i<sw.length-1){View v=new View(c);v.setBackgroundColor(0xFFE0E0E0);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));p.addView(v);}}
        LinearLayout sr=new LinearLayout(c);sr.setOrientation(0);sr.setGravity(Gravity.CENTER_VERTICAL);sr.setPadding(0,dp(10),0,dp(10));
        TextView sl=new TextView(c);sl.setText(lbl);sl.setTextSize(14);sl.setTextColor(0xFF333333);sr.addView(sl,new LinearLayout.LayoutParams(0,-2,1));
        Spinner sp=new Spinner(c);sp.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_item,items));sr.addView(sp);p.addView(sr);
        View dvB=new View(c);dvB.setBackgroundColor(0xFFE0E0E0);dvB.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));p.addView(dvB);
        Button b=new Button(c);b.setText(bt);b.setTextColor(0xFFFFFFFF);b.setBackgroundColor(bc);b.setPadding(dp(20),dp(10),dp(20),dp(10));
        b.setOnClickListener(v->{StringBuilder sb=new StringBuilder();sb.append(lbl+":").append(sp.getSelectedItem());for(int i=0;i<ss.length;i++)sb.append(" ").append(sw[i]).append(":").append(ss[i].isChecked());Toast.makeText(c,sb.toString(),Toast.LENGTH_LONG).show();});
        p.addView(b);return p;}

    Field fd(Class<?>c,String n){Class<?>cur=c;while(cur!=null&&cur!=Object.class){try{return cur.getDeclaredField(n);}catch(NoSuchFieldException e){cur=cur.getSuperclass();}}return null;}
}
