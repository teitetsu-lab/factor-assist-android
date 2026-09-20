package jp.factor.overlay;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

public class FloatingService extends Service {
    WindowManager wm; LinearLayout bar, menu; ScrollView menuScroll; TextView summary,stats;WindowManager.LayoutParams statsParams;FactorFrames.Overlay frames; Button toggle;
    WindowManager.LayoutParams barParams, summaryParams;
    Handler handler=new Handler(); boolean expanded=false;
    AlertDialog details;LinearLayout detailTabs,detailBody;String detailLabel="元因子",detailSignature="";
    Runnable ticker=new Runnable(){public void run(){if(toggle!=null)toggle.setText(CaptureService.running?"停止":"開始");if(summary!=null)updateSummary(true);if(details!=null&&details.isShowing())renderDetails();if(stats!=null)stats.setText(CaptureService.performance());handler.postDelayed(this,500);}};
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();startForeground(11,Ui.notification(this,"フローティング操作",FloatingService.class,11));wm=getSystemService(WindowManager.class);bar=Ui.column(this);bar.setPadding(0,0,0,0);
        // Only the expanded menu has a background; the control bar stays transparent.
        bar.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout row=new LinearLayout(this);bar.addView(row);
        Button burger=Ui.button(this,"☰",()->{expanded=!expanded;buildMenu();});row.addView(burger,new LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,48)));
        // 停止 only pauses: the screen share stays connected so the next 開始 skips the consent dialog.
        toggle=Ui.button(this,"開始",()->{
            if(CaptureService.running)CaptureService.pause(this);
            // No game launch here: the button sits over the game, and relaunching a foreground Unity app can freeze its rendering.
            else if(CaptureService.connected)CaptureService.resume(this);
            else startActivity(new Intent(this,MainActivity.class).putExtra("capture",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });row.addView(toggle,new LinearLayout.LayoutParams(Ui.dp(this,76),Ui.dp(this,48)));
        barParams=params("bar",128);wm.addView(bar,barParams);drag(burger,bar,barParams,"bar",true);buildMenu();refreshSummary();refreshStats();refreshFrames();handler.post(ticker);
    }
    WindowManager.LayoutParams params(String key,int width){var p=new WindowManager.LayoutParams(Ui.dp(this,width),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.x=Store.prefs(this).getInt(key+"X",0);p.y=Store.prefs(this).getInt(key+"Y",Ui.dp(this,key.equals("bar")?120:key.equals("stats")?420:220));return p;}
    void drag(View grip,View window,WindowManager.LayoutParams p,String key,boolean click){grip.setOnTouchListener(new View.OnTouchListener(){float x,y;int sx,sy;boolean moved;public boolean onTouch(View v,android.view.MotionEvent e){switch(e.getAction()){
        case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();sx=p.x;sy=p.y;moved=false;return true;
        case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>Ui.dp(FloatingService.this,8))moved=true;if(moved){Rect b=wm.getMaximumWindowMetrics().getBounds();p.x=Math.max(0,Math.min(b.width()-window.getWidth(),sx+(int)dx));p.y=Math.max(0,Math.min(b.height()-window.getHeight(),sy+(int)dy));wm.updateViewLayout(window,p);}return true;
        case MotionEvent.ACTION_UP:Store.prefs(FloatingService.this).edit().putInt(key+"X",p.x).putInt(key+"Y",p.y).apply();if(!moved&&click)v.performClick();return true;
        case MotionEvent.ACTION_CANCEL:return true;
        }return false;}});}
    void buildMenu(){if(menuScroll!=null){bar.removeView(menuScroll);menuScroll=null;}barParams.width=Ui.dp(this,expanded?280:128);wm.updateViewLayout(bar,barParams);if(!expanded)return;menu=Ui.column(this);
        JSONArray all=Store.all(this);String[] names=new String[all.length()];int selected=0;JSONObject current=Store.selected(this);
        for(int i=0;i<all.length();i++){names[i]=all.optJSONObject(i).optString("name");if(current!=null&&current.optString("id").equals(all.optJSONObject(i).optString("id")))selected=i;}
        Spinner presets=Ui.spinner(this,names);presets.setSelection(selected);menu.addView(presets);presets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){Store.select(FloatingService.this,all.optJSONObject(n).optString("id"));}});
        menu.addView(Ui.button(this,"プリセット編集",()->Ui.edit(this,true,()->{buildMenu();refreshSummary();})));
        // A new preset is selected on creation and opened straight in the editor.
        menu.addView(Ui.button(this,"新規プリセット",()->Ui.newPreset(this,true,()->{buildMenu();refreshSummary();Ui.edit(this,true,()->{buildMenu();refreshSummary();});})));
        menu.addView(Ui.button(this,"プリセット取り込み",()->startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        menu.addView(Ui.button(this,"表示設定",this::settings));
        menu.addView(Ui.button(this,"結果をリセット",()->CaptureService.resetResults()));
        if(CaptureService.connected)menu.addView(Ui.button(this,"画面共有を切る",()->{stopService(new Intent(this,CaptureService.class));expanded=false;buildMenu();}));
        menu.addView(Ui.button(this,"フローティングを終了",()->{stopService(new Intent(this,CaptureService.class));stopSelf();}));
        fitMenu();
    }
    /**
     * The menu scrolls within the screen height left below the button row, so every item stays reachable in
     * landscape too. When that space is too small, the whole bar moves up first.
     */
    void fitMenu(){
        Rect screen=wm.getCurrentWindowMetrics().getBounds();int row=Ui.dp(this,48),margin=Ui.dp(this,8);
        menu.measure(View.MeasureSpec.makeMeasureSpec(barParams.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int wanted=menu.getMeasuredHeight(),room=screen.height()-barParams.y-row-margin;
        int enough=Math.min(wanted,Math.max(Ui.dp(this,240),screen.height()/2));
        if(room<enough){barParams.y=Math.max(0,screen.height()-row-margin-enough);room=screen.height()-barParams.y-row-margin;}
        menuScroll=new ScrollView(this);menuScroll.addView(menu);
        bar.addView(menuScroll,new LinearLayout.LayoutParams(-1,Math.max(0,Math.min(wanted,room))));
        wm.updateViewLayout(bar,barParams);
    }
    /** Keeps a floating window fully on screen, e.g. after rotating to landscape. */
    void keepOnScreen(View window,WindowManager.LayoutParams p){
        if(window==null||p==null||window.getParent()==null)return;Rect screen=wm.getCurrentWindowMetrics().getBounds();
        int w=window.getWidth()>0?window.getWidth():p.width,h=window.getHeight()>0?window.getHeight():Ui.dp(this,48);
        int x=Math.max(0,Math.min(p.x,screen.width()-w)),y=Math.max(0,Math.min(p.y,screen.height()-h));
        if(x!=p.x||y!=p.y){p.x=x;p.y=y;wm.updateViewLayout(window,p);}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);
        // After the rotation settles: re-fit the open menu, then pull every window back inside the new screen.
        handler.post(()->{if(expanded)buildMenu();keepOnScreen(bar,barParams);keepOnScreen(summary,summaryParams);keepOnScreen(stats,statsParams);});
    }
    /** The processing figures (cycles per second, stage timings…) in their own small draggable window, toggled in 表示設定. */
    /** Full-screen, touch-through layer for the factor frames, toggled in 表示設定. */
    void refreshFrames(){
        if(frames!=null){wm.removeView(frames);frames=null;}
        if(!Store.prefs(this).getBoolean("factorFrames",false))return;
        var p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Android 12+ blocks touches to the app below a full-screen overlay that is more opaque than this.
        p.alpha=Math.min(.75f,getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch());
        frames=new FactorFrames.Overlay(this);wm.addView(frames,p);
    }
    void refreshStats(){
        if(stats!=null){wm.removeView(stats);stats=null;}
        if(!Store.prefs(this).getBoolean("showStats",false))return;
        stats=new TextView(this);stats.setTextSize(11);stats.setTextColor(Color.WHITE);stats.setBackgroundColor(0xb0000000);
        stats.setPadding(Ui.dp(this,8),Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,6));stats.setText(CaptureService.performance());
        statsParams=params("stats",1);statsParams.width=WindowManager.LayoutParams.WRAP_CONTENT;
        wm.addView(stats,statsParams);drag(stats,stats,statsParams,"stats",false);
    }
    void refreshSummary(){if(summary!=null){wm.removeView(summary);summary=null;}var prefs=Store.prefs(this);if(!prefs.getBoolean("showSummary",true))return;
        OutlinedTextView outlined=new OutlinedTextView(this);summary=outlined;summary.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));summary.setTextSize(prefs.getInt("fontSize",14));summary.setTextColor(prefs.getInt("textColor",Color.WHITE));
        outlined.setOutline(prefs.getInt("outlineColor",Color.BLACK),prefs.getBoolean("outline",true)?Ui.dp(this,prefs.getInt("outlineWidth",3)):0);
        int bg=prefs.getInt("bgColor",Color.BLACK);int alpha=prefs.getBoolean("transparent",false)?prefs.getInt("opacity",128):255;
        summary.setBackgroundColor(Color.argb(alpha,Color.red(bg),Color.green(bg),Color.blue(bg)));summaryParams=params("summary",1);updateSummary(false);wm.addView(summary,summaryParams);summary.setOnClickListener(v->showDetails());drag(summary,summary,summaryParams,"summary",true);
    }
    void updateSummary(boolean attached){
        String text=CaptureService.summary(this);if(!text.contentEquals(summary.getText()))summary.setText(text);
        summary.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);if(text.isEmpty())return;
        Rect bounds=wm.getCurrentWindowMetrics().getBounds();
        summary.measure(View.MeasureSpec.makeMeasureSpec(bounds.width(),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int width=summary.getMeasuredWidth();
        int x=Math.max(0,Math.min(summaryParams.x,bounds.width()-width));
        int y=Math.max(0,Math.min(summaryParams.y,bounds.height()-summary.getMeasuredHeight()));
        if(summaryParams.width!=width||summaryParams.x!=x||summaryParams.y!=y){
            summaryParams.width=width;summaryParams.x=x;summaryParams.y=y;
            if(attached)wm.updateViewLayout(summary,summaryParams);
        }
    }
    void showDetails(){
        if(details!=null&&details.isShowing())return;
        LinearLayout content=Ui.column(this);HorizontalScrollView tabsScroll=new HorizontalScrollView(this);detailTabs=new LinearLayout(this);tabsScroll.addView(detailTabs);content.addView(tabsScroll);
        ScrollView rows=new ScrollView(this);detailBody=Ui.column(this);rows.addView(detailBody);content.addView(rows,new LinearLayout.LayoutParams(-1,0,1));
        details=new AlertDialog.Builder(this).setTitle("取得した因子").setView(content).setPositiveButton("閉じる",null).create();Ui.show(details,true);
        Rect bounds=wm.getMaximumWindowMetrics().getBounds();details.getWindow().setLayout(Math.min(Ui.dp(this,360),bounds.width()-Ui.dp(this,24)),Math.min(Ui.dp(this,560),bounds.height()-Ui.dp(this,80)));
        detailSignature="";renderDetails();
    }
    void renderDetails(){
        // When the chosen page is gone, open the first page that exists (e.g. only 再獲得因子2 was read), not an empty 元因子.
        var data=CaptureService.snapshot();if(!data.containsKey(detailLabel))detailLabel=data.isEmpty()?"元因子":data.keySet().iterator().next();
        String signature=data.toString()+String.valueOf(Store.selected(this))+detailLabel;
        if(signature.equals(detailSignature))return;detailSignature=signature;detailTabs.removeAllViews();
        for(String label:data.keySet()){Button tab=Ui.button(this,label,()->{detailLabel=label;detailSignature="";renderDetails();});tab.setTextColor(label.equals(detailLabel)?0xff008b8b:Color.DKGRAY);tab.setSelected(label.equals(detailLabel));detailTabs.addView(tab);}
        detailBody.removeAllViews();var factors=data.getOrDefault(detailLabel,java.util.Map.<String,Integer>of());var rules=Store.rules(this);
        var order=CaptureService.places.ordered(detailLabel,factors.keySet());
        for(String tier:new String[]{"必須","重要","必要","本育成で取得可能","条件未達","未設定"}){
            java.util.List<String> items=new java.util.ArrayList<>();for(String name:order)if(tier.equals(Store.tier(rules,name,factors.get(name))))items.add(name+"  ☆"+factors.get(name));
            if(items.isEmpty()&&!java.util.Arrays.asList("必須","重要","必要").contains(tier))continue;
            TextView heading=new TextView(this);heading.setText(tier+"（"+items.size()+"）");heading.setTextSize(17);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,4));detailBody.addView(heading);
            for(String item:items){TextView row=new TextView(this);row.setText(item);row.setTextSize(15);row.setPadding(0,Ui.dp(this,5),0,Ui.dp(this,5));row.setTextIsSelectable(true);detailBody.addView(row);}
        }
    }
    void settings(){LinearLayout content=Ui.column(this);ScrollView scroll=new ScrollView(this);scroll.addView(content);var prefs=Store.prefs(this);
        TextView frequency=new TextView(this);frequency.setText("毎秒の認識目標回数");content.addView(frequency);
        EditText rate=new EditText(this);rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);rate.setSingleLine();rate.setText(prefs.getString("recognitionRateText",CaptureService.DEFAULT_RATE));content.addView(rate);
        content.addView(Ui.button(this,"認識回数を適用",()->{try{String value=CycleLimit.validate(rate.getText().toString());prefs.edit().putString("recognitionRateText",value).apply();Ui.toast(this,"認識回数を保存しました");}catch(Exception e){rate.setError("1以上の整数を入力してください");}}));
        Switch xnnpack=new Switch(this);xnnpack.setText("XNNPACKで推論（実験的：速いがスクロール中の確定が遅れる）");xnnpack.setChecked(prefs.getBoolean("xnnpack",false));content.addView(xnnpack);
        xnnpack.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("xnnpack",on).apply());
        // 自動 measures every thread count and keeps using the fastest; a number pins it. Applied from the next cycle.
        TextView threadsLabel=new TextView(this);threadsLabel.setText("推論スレッド（標準の推論方式のとき）");content.addView(threadsLabel);
        int[] threadOptions=FactorVision.threadOptions(Runtime.getRuntime().availableProcessors());String[] threadNames=new String[threadOptions.length+1];threadNames[0]="自動（おすすめ）";
        for(int i=0;i<threadOptions.length;i++)threadNames[i+1]=threadOptions[i]+" スレッド";
        Spinner threads=Ui.spinner(this,threadNames);int savedThreads=prefs.getInt("inferenceThreads",0);threads.setSelection(savedThreads<=threadOptions.length?savedThreads:0);content.addView(threads);
        threads.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){prefs.edit().putInt("inferenceThreads",n==0?0:threadOptions[n-1]).apply();}});
        Switch enabled=new Switch(this);enabled.setText("結果フローティング表示");enabled.setChecked(prefs.getBoolean("showSummary",true));content.addView(enabled);enabled.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("showSummary",on).apply();refreshSummary();});
        Switch showStats=new Switch(this);showStats.setText("処理状況をフローティング表示（回/秒・処理時間など）");showStats.setChecked(prefs.getBoolean("showStats",false));content.addView(showStats);
        showStats.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("showStats",on).apply();refreshStats();});
        Switch factorFrames=new Switch(this);factorFrames.setText("因子に枠を付ける（必須 赤 / 重要 橙 / 必要 青 / 本育成 緑 / 条件未達 灰）");factorFrames.setChecked(prefs.getBoolean("factorFrames",false));content.addView(factorFrames);
        factorFrames.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("factorFrames",on).apply();refreshFrames();});
        slider(content,"文字サイズ",10,30,"fontSize",14);
        colors(content,"文字色","textColor",Color.WHITE);colors(content,"背景色","bgColor",Color.BLACK);
        Switch outline=new Switch(this);outline.setText("文字の縁取り");outline.setChecked(prefs.getBoolean("outline",true));content.addView(outline);outline.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("outline",on).apply();refreshSummary();});
        colors(content,"縁取りの色","outlineColor",Color.BLACK);slider(content,"縁取りの太さ",1,8,"outlineWidth",3);
        Switch transparent=new Switch(this);transparent.setText("背景を透過");transparent.setChecked(prefs.getBoolean("transparent",false));content.addView(transparent);transparent.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("transparent",on).apply();refreshSummary();});slider(content,"背景の不透明度",0,255,"opacity",128);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("表示設定").setView(scroll).setPositiveButton("閉じる",null).create();Ui.show(d,true);d.getWindow().setLayout(Ui.dp(this,330),Ui.dp(this,540));
    }
    void slider(LinearLayout content,String name,int min,int max,String key,int def){TextView label=new TextView(this);int value=Store.prefs(this).getInt(key,def);label.setText(name+"："+value);content.addView(label);SeekBar bar=new SeekBar(this);bar.setMin(min);bar.setMax(max);bar.setProgress(value);content.addView(bar);bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(user){Store.prefs(FloatingService.this).edit().putInt(key,n).apply();label.setText(name+"："+n);refreshSummary();}}});}
    void colors(LinearLayout content,String title,String key,int def){TextView label=new TextView(this);label.setText(title);content.addView(label);int[] colors={Color.WHITE,Color.BLACK,0xffff4568,0xffffbf24,0xff28bbbc,0xff277ddd};Spinner spinner=Ui.spinner(this,new String[]{"白","黒","赤","黄","青緑","青"});int value=Store.prefs(this).getInt(key,def);for(int i=0;i<colors.length;i++)if(colors[i]==value)spinner.setSelection(i);content.addView(spinner);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){Store.prefs(FloatingService.this).edit().putInt(key,colors[n]).apply();refreshSummary();}});}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"STOP".equals(i.getAction())){stopService(new Intent(this,CaptureService.class));stopSelf();}return START_NOT_STICKY;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(details!=null)details.dismiss();if(bar!=null)wm.removeView(bar);if(summary!=null)wm.removeView(summary);if(stats!=null)wm.removeView(stats);if(frames!=null)wm.removeView(frames);super.onDestroy();}
}
