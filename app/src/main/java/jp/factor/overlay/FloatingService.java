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
        // The frames go in first, so every other window - the bar, its menu, the results, the dialogs - sits above them.
        refreshFrames();
        barParams=params("bar",128);wm.addView(bar,barParams);drag(burger,bar,barParams,"bar",true);buildMenu();refreshSummary();refreshStats();handler.post(ticker);
    }
    WindowManager.LayoutParams params(String key,int width){var p=new WindowManager.LayoutParams(Ui.dp(this,width),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.x=Store.prefs(this).getInt(key+"X",0);p.y=Store.prefs(this).getInt(key+"Y",Ui.dp(this,key.equals("bar")?120:key.equals("stats")?420:220));return p;}
    /**
     * Dragging a floating window, and tapping it. This listener consumes the touches, so a long press has to be
     * recognised here too - the view's own long-click listener never sees anything.
     */
    void drag(View grip,View window,WindowManager.LayoutParams p,String key,boolean click){drag(grip,window,p,key,click,null);}
    /**
     * Dragging a floating window, and the taps on it. This listener consumes the touches, so the gestures are
     * recognised here rather than by the view: a tap opens, two taps do doubleTap, a long press long-clicks.
     * When there is a doubleTap the single tap waits a moment first, to see whether a second one follows.
     */
    void drag(View grip,View window,WindowManager.LayoutParams p,String key,boolean click,Runnable doubleTap){
        grip.setOnTouchListener(new View.OnTouchListener(){float x,y;int sx,sy;boolean moved,fired;long down,lastTap;Runnable pending,hold;
        /**
         * A long press counts down on the result window and only acts once the count runs out with the finger
         * still down: the long press may do something that cannot be undone, such as a reset, and no dialog asks first.
         */
        void startHold(View v){
            fired=false;
            if(!click||!v.hasOnLongClickListeners()||v!=summary)return;
            String action=gestureName(2);if(action==null)return;
            hold=new Runnable(){int left=HOLD_SECONDS;public void run(){
                if(left==0){holdNotice=null;hold=null;fired=true;updateSummary(true);v.performLongClick();return;}
                holdNotice=action+"まで "+left+"\n（指を離すと中止）";left--;updateSummary(true);handler.postDelayed(this,1000);}};
            handler.postDelayed(hold,500);
        }
        void stopHold(){if(hold!=null){handler.removeCallbacks(hold);hold=null;}if(holdNotice!=null){holdNotice=null;updateSummary(true);}}
        public boolean onTouch(View v,android.view.MotionEvent e){switch(e.getAction()){
        case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();sx=p.x;sy=p.y;moved=false;down=android.os.SystemClock.uptimeMillis();startHold(v);return true;
        case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>Ui.dp(FloatingService.this,8)){if(!moved)stopHold();moved=true;}if(moved){Rect b=wm.getMaximumWindowMetrics().getBounds();p.x=Math.max(0,Math.min(b.width()-window.getWidth(),sx+(int)dx));p.y=Math.max(0,Math.min(b.height()-window.getHeight(),sy+(int)dy));wm.updateViewLayout(window,p);}return true;
        case MotionEvent.ACTION_UP:stopHold();
            long now=android.os.SystemClock.uptimeMillis();
            Store.prefs(FloatingService.this).edit().putInt(key+"X",p.x).putInt(key+"Y",p.y).apply();
            if(moved||!click||fired)return true;
            // Held past the start of the countdown but let go before the end: cancelled, and not a tap either.
            if(now-down>=500&&v.hasOnLongClickListeners())return true;
            if(doubleTap==null){v.performClick();return true;}
            if(now-lastTap<400){if(pending!=null)handler.removeCallbacks(pending);pending=null;lastTap=0;doubleTap.run();return true;}
            lastTap=now;pending=v::performClick;handler.postDelayed(pending,400);
            return true;
        case MotionEvent.ACTION_CANCEL:stopHold();return true;
        }return false;}});}
    void buildMenu(){if(menuScroll!=null){bar.removeView(menuScroll);menuScroll=null;}barParams.width=Ui.dp(this,expanded?280:128);wm.updateViewLayout(bar,barParams);if(!expanded)return;menu=Ui.column(this);
        String kind=Store.FACTOR;
        JSONArray all=new JSONArray();JSONArray stored=Store.all(this);
        for(int i=0;i<stored.length();i++)if(Store.kindOf(stored.optJSONObject(i)).equals(kind))all.put(stored.optJSONObject(i));
        String[] names=new String[all.length()];int selected=0;JSONObject current=Store.selected(this,kind);
        for(int i=0;i<all.length();i++){names[i]=all.optJSONObject(i).optString("name");if(current!=null&&current.optString("id").equals(all.optJSONObject(i).optString("id")))selected=i;}
        if(names.length>0){
            TextView kindLabel=new TextView(this);kindLabel.setText("プリセット");menu.addView(kindLabel);
            Spinner presets=Ui.spinner(this,names);presets.setSelection(selected);menu.addView(presets);
            // A spinner fires this once by itself as soon as it is laid out. Acting on that call would rebuild
            // the menu, which builds another spinner, which fires again: the menu would flicker for ever and no
            // button could be tapped. Only a selection that actually changes the preset counts.
            presets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
                public void onItemSelected(AdapterView<?> p,View v,int n,long id){
                    String chosen=all.optJSONObject(n).optString("id");
                    JSONObject now=Store.selected(FloatingService.this,kind);
                    if(now!=null&&chosen.equals(now.optString("id")))return;
                    Store.select(FloatingService.this,chosen);refreshSummary();refreshFrames();
                }});
        }
        // Making and editing presets happens on the app's own screen, where there is room for it; over the game
        // only the choice matters, so the menu stays short enough to read at a glance.
        // The menu itself covers the rows, and these settings change what is drawn over them, so it folds away
        // first: the small settings window is then the only thing between the user and the game.
        menu.addView(pair(Ui.button(this,"表示設定",()->{expanded=false;buildMenu();settings();}),
            Ui.button(this,"結果をリセット",()->{CaptureService.resetResults();refreshSummary();refreshFrames();})));
        // What is used now and then goes under その他, so the menu stays short over the game.
        java.util.List<String> extras=new java.util.ArrayList<>();java.util.List<Runnable> actions=new java.util.ArrayList<>();
        if(CaptureService.connected){extras.add("画面共有を切る");actions.add(()->{stopService(new Intent(this,CaptureService.class));expanded=false;buildMenu();});}
        Button quit=Ui.button(this,"終了",()->{stopService(new Intent(this,CaptureService.class));stopSelf();});
        if(extras.isEmpty())menu.addView(quit);
        else menu.addView(pair(Ui.button(this,"その他…",()->{
            AlertDialog d=new AlertDialog.Builder(this).setItems(extras.toArray(new String[0]),(dialog,n)->actions.get(n).run()).setNegativeButton("閉じる",null).create();
            Ui.show(d,true);}),quit));
        fitMenu();
    }
    /**
     * What a tap, two taps and a long press on the result window do.
     */
    static final String[] GESTURES={"none","details","reset","toggle","menu"
    };
    static final String[] GESTURE_NAMES={"何もしない","詳細を開く","読み取りをリセット","認識の開始／停止","メニューを開く"
    };
    static final String[] GESTURE_KEYS={"gestureTap","gestureDouble","gestureLong"};
    static final String[] GESTURE_LABELS={"タップ","2回タップ","長押し"};
    static final String[] FACTOR_DEFAULTS={"details","reset","none"};
    static String gestureKey(String mode,int which){return GESTURE_KEYS[which]+"_"+mode;}
    static String gestureDefault(String mode,int which){
        return FACTOR_DEFAULTS[which];
    }

    /** How long the long press counts down before it acts. */
    static final int HOLD_SECONDS=3;
    /** Shown in place of the results while a long press counts down; null otherwise. */
    String holdNotice;
    String gestureAction(int which){
        String mode=Store.FACTOR;
        return Store.prefs(this).getString(gestureKey(mode,which),gestureDefault(mode,which));
    }
    /** What a gesture does, for the countdown; null when it does nothing. */
    String gestureName(int which){
        int i=java.util.Arrays.asList(GESTURES).indexOf(gestureAction(which));
        return i<=0?null:GESTURE_NAMES[i];
    }
    void gesture(int which){
        String action=gestureAction(which);
        switch(action){
            case "details" -> showDetails();
            case "reset" -> {CaptureService.resetResults();refreshSummary();refreshFrames();Ui.toast(this,"リセットしました");}
            case "toggle" -> {if(CaptureService.running)CaptureService.pause(this);else if(CaptureService.connected)CaptureService.resume(this);}
            case "menu" -> {expanded=!expanded;buildMenu();}
            default -> {}
        }
    }

    /** Two buttons side by side, each half the menu's width. */
    LinearLayout pair(Button left,Button right){
        LinearLayout row=new LinearLayout(this);
        row.addView(left,new LinearLayout.LayoutParams(0,-2,1));row.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        return row;
    }


    /**
     * The menu scrolls within the screen height left below the button row, so every item stays reachable in
     * landscape too. When that space is too small, the whole bar moves up first.
     */
    void fitMenu(){
        var metrics=wm.getCurrentWindowMetrics();
        // The status and navigation bars take their share of the screen: without counting them the bottom of
        // the menu (終了) went under the navigation bar, out of reach even scrolled to the end.
        var bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
        Rect whole=metrics.getBounds();Rect screen=new Rect(0,0,whole.width(),whole.height()-bars.top-bars.bottom);
        int row=Ui.dp(this,48),margin=Ui.dp(this,8);
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
        boolean on=Store.prefs(this).getBoolean("factorFrames",false);
        // Overlay windows stack in the order they are added. The frames window is added once, before the control
        // bar, and then only reloaded and shown or hidden: added again it would cover the menu and the dialogs.
        if(frames!=null){frames.reload();frames.setVisibility(on?View.VISIBLE:View.GONE);return;}
        var p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Android 12+ blocks touches to the app below a full-screen overlay that is more opaque than this.
        p.alpha=Math.min(.75f,getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch());
        frames=new FactorFrames.Overlay(this);frames.setVisibility(on?View.VISIBLE:View.GONE);wm.addView(frames,p);
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
        summary.setBackgroundColor(Color.argb(alpha,Color.red(bg),Color.green(bg),Color.blue(bg)));summaryParams=params("summary",1);updateSummary(false);wm.addView(summary,summaryParams);summary.setOnClickListener(v->gesture(0));
        summary.setOnLongClickListener(v->{gesture(2);return true;});
        drag(summary,summary,summaryParams,"summary",true,()->gesture(1));
    }
    void updateSummary(boolean attached){
        String text=holdNotice!=null?holdNotice:CaptureService.summary(this);if(!text.contentEquals(summary.getText()))summary.setText(text);
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
        for(String tier:new String[]{"必須","重要","必要","本育成で取得可能","条件未達","除外","未設定"}){
            java.util.List<String> items=new java.util.ArrayList<>();boolean numbered=Store.detailed(this);
            for(String name:order)if(tier.equals(Store.tier(rules,name,factors.get(name)))){
                int priority=Store.priority(rules,name,factors.get(name));
                items.add((numbered&&priority>=0&&priority<=Store.PRIORITY_MAX?priority+"  ":"")+name+"  ☆"+factors.get(name));}
            if(items.isEmpty()&&!java.util.Arrays.asList("必須","重要","必要").contains(tier))continue;
            TextView heading=new TextView(this);heading.setText(tier+"（"+items.size()+"）");heading.setTextSize(17);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,4));detailBody.addView(heading);
            for(String item:items){TextView row=new TextView(this);row.setText(item);row.setTextSize(15);row.setPadding(0,Ui.dp(this,5),0,Ui.dp(this,5));row.setTextIsSelectable(true);detailBody.addView(row);}
        }
    }
    /** Which page of the settings was open last, so reopening lands where the user left off. */
    String settingsTab="枠と行";
    static final String[] SETTINGS_TABS={"枠と行","結果表示","操作","認識"};

    void settings(){
        LinearLayout content=Ui.column(this);
        TextView grip=new TextView(this);grip.setText("⋮⋮  ここを押したまま動かすと、このウィンドウを移動できます");
        grip.setTextSize(12);grip.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,8));content.addView(grip);
        LinearLayout tabs=new LinearLayout(this);content.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);scroll.addView(body);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Runnable[] build=new Runnable[1];
        build[0]=()->{
            tabs.removeAllViews();body.removeAllViews();
            for(String tab:SETTINGS_TABS){
                Button button=Ui.button(this,tab,()->{if(!tab.equals(settingsTab)){settingsTab=tab;build[0].run();}});
                button.setTextColor(tab.equals(settingsTab)?0xff008b8b:Color.DKGRAY);
                tabs.addView(button,new LinearLayout.LayoutParams(0,-2,1));
            }
            switch(settingsTab){
                case "結果表示" -> summarySettings(body);
                case "操作" -> gestureSettings(body);
                case "認識" -> recognitionSettings(body);
                default -> frameSettings(body);
            }
        };
        build[0].run();
        AlertDialog d=new AlertDialog.Builder(this).setTitle("表示設定").setView(content).setPositiveButton("閉じる",null).create();Ui.show(d,true);
        // Sliders change what is drawn over the game, so the game has to stay visible while they are dragged:
        // the dialog sits at the bottom, half height, and does not dim what is behind it.
        var window=d.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setLayout(Ui.dp(this,330),Math.min(Ui.dp(this,420),wm.getCurrentWindowMetrics().getBounds().height()/2));
        // Placed where it was left last time, and moved by dragging its grip: the rows being adjusted may be
        // anywhere on screen, so the window has to get out of the way.
        var p=window.getAttributes();p.gravity=Gravity.TOP|Gravity.LEFT;
        Rect screen=wm.getCurrentWindowMetrics().getBounds();
        p.x=Store.prefs(this).getInt("settingsX",Math.max(0,(screen.width()-Ui.dp(this,330))/2));
        p.y=Store.prefs(this).getInt("settingsY",Math.max(0,screen.height()-Ui.dp(this,440)));
        window.setAttributes(p);
        grip.setOnTouchListener(new View.OnTouchListener(){float x,y;int sx,sy;
            public boolean onTouch(View v,MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN: x=e.getRawX();y=e.getRawY();sx=p.x;sy=p.y;return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x=Math.max(0,Math.min(screen.width()-Ui.dp(FloatingService.this,330),sx+(int)(e.getRawX()-x)));
                        p.y=Math.max(0,Math.min(screen.height()-Ui.dp(FloatingService.this,120),sy+(int)(e.getRawY()-y)));
                        window.setAttributes(p);return true;
                    case MotionEvent.ACTION_UP:
                        Store.prefs(FloatingService.this).edit().putInt("settingsX",p.x).putInt("settingsY",p.y).apply();return true;
                }
                return false;
            }});
    }

    /** What is drawn over the game: the frames around the factors. */
    void frameSettings(LinearLayout content){
        var prefs=Store.prefs(this);
        Switch factorFrames=new Switch(this);factorFrames.setText("因子に枠を付ける（優先度の順に 赤・橙・黄・緑・青・藍・紫、条件未達は灰）");
        factorFrames.setChecked(prefs.getBoolean("factorFrames",false));content.addView(factorFrames);
        factorFrames.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("factorFrames",on).apply();refreshFrames();});
    }

    /** Which action each gesture on the result window runs. */
    void gestureSettings(LinearLayout content){
        gestureGroup(content,"結果ウィンドウ",Store.FACTOR);
        TextView note=new TextView(this);note.setTextSize(12);
        note.setText("結果のフローティング表示に対する操作です。2回タップを使うと、1回タップの反応が0.4秒ほど遅れます。長押しは"+HOLD_SECONDS+"秒のカウントダウンが0になるまで押し続けると実行され、途中で離すと中止します。");
        content.addView(note);
    }
    void gestureGroup(LinearLayout content,String title,String mode){
        var prefs=Store.prefs(this);
        Ui.section(content,title);
        for(int i=0;i<GESTURE_KEYS.length;i++){
            final int which=i;final String key=gestureKey(mode,i),fallback=gestureDefault(mode,i);
            TextView label=new TextView(this);label.setText(GESTURE_LABELS[i]);content.addView(label);
            Spinner chooser=Ui.spinner(this,GESTURE_NAMES);
            String now=prefs.getString(key,fallback);
            for(int j=0;j<GESTURES.length;j++)if(GESTURES[j].equals(now))chooser.setSelection(j);
            content.addView(chooser);
            chooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
                public void onItemSelected(AdapterView<?> p,View v,int n,long id){
                    if(GESTURES[n].equals(prefs.getString(key,fallback)))return;
                    prefs.edit().putString(key,GESTURES[n]).apply();}});
        }
    }

    /** The floating result window: whether it shows at all, and how its text looks. */
    void summarySettings(LinearLayout content){
        var prefs=Store.prefs(this);
        Switch enabled=new Switch(this);enabled.setText("結果フローティング表示");enabled.setChecked(prefs.getBoolean("showSummary",true));content.addView(enabled);
        enabled.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("showSummary",on).apply();refreshSummary();});
        slider(content,"文字サイズ",10,30,"fontSize",14);
        colors(content,"文字色","textColor",Color.WHITE);colors(content,"背景色","bgColor",Color.BLACK);
        Switch outline=new Switch(this);outline.setText("文字の縁取り");outline.setChecked(prefs.getBoolean("outline",true));content.addView(outline);
        outline.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("outline",on).apply();refreshSummary();});
        colors(content,"縁取りの色","outlineColor",Color.BLACK);slider(content,"縁取りの太さ",1,8,"outlineWidth",3);
        Switch transparent=new Switch(this);transparent.setText("背景を透過");transparent.setChecked(prefs.getBoolean("transparent",false));content.addView(transparent);
        transparent.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("transparent",on).apply();refreshSummary();});
        slider(content,"背景の不透明度",0,255,"opacity",128);
    }

    /** How hard the app reads the screen, and what it reports about itself. */
    void recognitionSettings(LinearLayout content){
        var prefs=Store.prefs(this);
        TextView frequency=new TextView(this);frequency.setText("毎秒の認識目標回数");content.addView(frequency);
        EditText rate=new EditText(this);rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);rate.setSingleLine();
        rate.setText(prefs.getString("recognitionRateText",CaptureService.DEFAULT_RATE));content.addView(rate);
        content.addView(Ui.button(this,"認識回数を適用",()->{try{String value=CycleLimit.validate(rate.getText().toString());prefs.edit().putString("recognitionRateText",value).apply();Ui.toast(this,"認識回数を保存しました");}catch(Exception e){rate.setError("1以上の整数を入力してください");}}));
        Switch showStats=new Switch(this);showStats.setText("処理状況をフローティング表示（回/秒・処理時間など）");showStats.setChecked(prefs.getBoolean("showStats",false));content.addView(showStats);
        showStats.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("showStats",on).apply();refreshStats();});
        Ui.section(content,"推論");
        Switch xnnpack=new Switch(this);xnnpack.setText("XNNPACKで推論（実験的：速いがスクロール中の確定が遅れる）");xnnpack.setChecked(prefs.getBoolean("xnnpack",false));content.addView(xnnpack);
        xnnpack.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("xnnpack",on).apply());
        // 自動 measures every thread count and keeps using the fastest; a number pins it. Applied from the next cycle.
        TextView threadsLabel=new TextView(this);threadsLabel.setText("推論スレッド（標準の推論方式のとき）");content.addView(threadsLabel);
        int[] threadOptions=FactorVision.threadOptions(Runtime.getRuntime().availableProcessors());String[] threadNames=new String[threadOptions.length+1];threadNames[0]="自動（おすすめ）";
        for(int i=0;i<threadOptions.length;i++)threadNames[i+1]=threadOptions[i]+" スレッド";
        Spinner threads=Ui.spinner(this,threadNames);int savedThreads=prefs.getInt("inferenceThreads",0);
        threads.setSelection(savedThreads<=threadOptions.length?savedThreads:0);content.addView(threads);
        threads.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int n,long id){
                int chosen=n==0?0:threadOptions[n-1];
                if(chosen==prefs.getInt("inferenceThreads",0))return;   // the spinner fires once by itself
                prefs.edit().putInt("inferenceThreads",chosen).apply();}});
    }

    void slider(LinearLayout content,String name,int min,int max,String key,int def){slider(content,name,min,max,key,def,this::refreshSummary);}
    /** A slider with the number beside it, which can also be typed in: fine values are hard to hit by dragging. */
    void slider(LinearLayout content,String name,int min,int max,String key,int def,Runnable changed){
        var prefs=Store.prefs(this);int value=prefs.getInt(key,def);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);content.addView(row,new LinearLayout.LayoutParams(-1,-2));
        TextView label=new TextView(this);label.setText(name+"（"+min+"〜"+max+"）");row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        EditText number=new EditText(this);number.setSingleLine();number.setGravity(Gravity.END);
        number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|(min<0?android.text.InputType.TYPE_NUMBER_FLAG_SIGNED:0));
        number.setText(String.valueOf(value));row.addView(number,new LinearLayout.LayoutParams(Ui.dp(this,72),-2));
        SeekBar bar=new SeekBar(this);bar.setMin(min);bar.setMax(max);bar.setProgress(value);content.addView(bar);
        boolean[] typing={false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
            public void onProgressChanged(SeekBar s,int n,boolean user){if(!user)return;prefs.edit().putInt(key,n).apply();typing[0]=true;number.setText(String.valueOf(n));typing[0]=false;changed.run();}});
        number.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence c,int a,int b,int d){}public void onTextChanged(CharSequence c,int a,int b,int d){}
            public void afterTextChanged(android.text.Editable e){
                if(typing[0])return;
                int n;try{n=Integer.parseInt(e.toString().trim());}catch(NumberFormatException x){return;}
                if(n<min||n>max){number.setError(min+"〜"+max);return;}
                prefs.edit().putInt(key,n).apply();bar.setProgress(n);changed.run();
            }});
    }
    void colors(LinearLayout content,String title,String key,int def){colors(content,title,key,def,this::refreshSummary);}
    void colors(LinearLayout content,String title,String key,int def,Runnable changed){TextView label=new TextView(this);label.setText(title);content.addView(label);int[] colors={Color.WHITE,Color.BLACK,0xffff4568,0xffffbf24,0xff28bbbc,0xff277ddd};Spinner spinner=Ui.spinner(this,new String[]{"白","黒","赤","黄","青緑","青"});int value=Store.prefs(this).getInt(key,def);for(int i=0;i<colors.length;i++)if(colors[i]==value)spinner.setSelection(i);content.addView(spinner);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
        public void onItemSelected(AdapterView<?> p,View v,int n,long id){
            if(Store.prefs(FloatingService.this).getInt(key,def)==colors[n])return;   // the spinner fires once by itself
            Store.prefs(FloatingService.this).edit().putInt(key,colors[n]).apply();changed.run();}});}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"STOP".equals(i.getAction())){stopService(new Intent(this,CaptureService.class));stopSelf();}return START_NOT_STICKY;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(details!=null)details.dismiss();if(bar!=null)wm.removeView(bar);if(summary!=null)wm.removeView(summary);if(stats!=null)wm.removeView(stats);if(frames!=null)wm.removeView(frames);super.onDestroy();}
}
