package jp.factor.overlay;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

public class FloatingService extends Service {
    WindowManager wm; LinearLayout bar, menu; ScrollView menuScroll; TextView summary,stats;WindowManager.LayoutParams statsParams;FactorFrames.Overlay frames; LinearLayout row;View barGrip;boolean barOpen,edgeRight,edgeStyle;
    WindowManager.LayoutParams barParams, summaryParams;
    Handler handler=new Handler(); boolean expanded=false;
    AlertDialog details;LinearLayout detailTabs,detailBody;String detailLabel="元因子",detailSignature="";
    Runnable ticker=new Runnable(){public void run(){showToggle();if(summary!=null)updateSummary(true);if(details!=null&&details.isShowing())renderDetails();if(stats!=null)stats.setText(CaptureService.performance());handler.postDelayed(this,500);}};
    /** The running state the bar was last built for: ▶ 開始 and ■ 停止 swap by rebuilding it. */
    Boolean barShows;
    void showToggle(){if(row!=null&&!Boolean.valueOf(CaptureService.running).equals(barShows))rebuildBar();}
    /**
     * (Re)builds the ☰ and 開始/停止 in the shape chosen in the app's settings (see FloatingBar), and hooks up the
     * dragging and the taps. The ball and the edge handle show the ☰ and 開始/停止 only while opened.
     */
    void rebuildBar(){
        if(destroyed||lostOverlay())return;
        if(row==null)return;boolean running=CaptureService.running;barShows=running;
        String style=FloatingBar.style(this);edgeStyle="edge".equals(style);
        row.removeAllViews();
        Rect screen=wm.getMaximumWindowMetrics().getBounds();
        // The ball opens away from the nearer side, so what comes out stays on screen.
        boolean onRight=edgeStyle?edgeRight:"ball".equals(style)&&barParams!=null&&barParams.x+barAX>screen.width()/2;
        var built=FloatingBar.build(this,style,FloatingBar.opening(this,style),FloatingBar.color(this),FloatingBar.opacity(this),FloatingBar.size(this),
            running,barOpen||expanded,onRight,barStatus(running),
            ()->{expanded=!expanded;buildMenu();},this::toggleReading,
            ()->{barOpen=!barOpen;if(!barOpen&&expanded){expanded=false;buildMenu();}else rebuildBar();});
        row.addView(built.view());barGrip=built.grip();
        ((LinearLayout.LayoutParams)row.getLayoutParams()).gravity=onRight?Gravity.END:Gravity.START;
        // The ball or the handle stays where it is while what it opens grows around it.
        int ax=built.anchorX(),ay=built.anchorY();
        if(expanded&&onRight){built.view().measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);ax+=Ui.dp(this,280)-built.view().getMeasuredWidth();}
        if(barParams!=null){barParams.x+=barAX-ax;barParams.y+=barAY-ay;}
        barAX=ax;barAY=ay;barRestX=built.restX();barRestY=built.restY();
        if(barParams!=null){drag(barGrip,bar,barParams,"bar",true);fitBar();}
    }
    /** Where the ball or the handle is in the bar now and when closed (see FloatingBar.Built). */
    int barAX,barAY,barRestX,barRestY;
    /** The line at the top of the edge's wide drawer: reading or not, and the preset in use. */
    CharSequence barStatus(boolean running){
        String name="";try{name=Store.selected(this).optString("name");}catch(RuntimeException e){}
        var s=new android.text.SpannableStringBuilder("● "+(running?"認識中":"停止中")+(name.isEmpty()?"":"　"+name));
        s.setSpan(new android.text.style.ForegroundColorSpan(running?FloatingBar.STOP:FloatingBar.GO),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
    /** 停止 only pauses: the screen share stays connected so the next 開始 skips the consent dialog. */
    void toggleReading(){
        if(CaptureService.running)CaptureService.pause(this);
        // No game launch here: the button sits over the game, and relaunching a foreground Unity app can freeze its rendering.
        else if(CaptureService.connected)CaptureService.resume(this);
        else startActivity(new Intent(this,MainActivity.class).putExtra("capture",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    /** The bar is the size of what it shows (the menu's width while open), and an edge handle sits against its edge. */
    void fitBar(){
        barParams.width=expanded?Ui.dp(this,280):WindowManager.LayoutParams.WRAP_CONTENT;
        if(edgeStyle){
            Rect b=wm.getMaximumWindowMetrics().getBounds();
            bar.measure(View.MeasureSpec.makeMeasureSpec(b.width(),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            int w=expanded?barParams.width:bar.getMeasuredWidth();barParams.x=edgeRight?b.width()-w:0;
        }
        if(bar.getParent()!=null)wm.updateViewLayout(bar,barParams);
    }
    /** The shape, colour or opacity changed in the app's settings: the bar follows at once. */
    final SharedPreferences.OnSharedPreferenceChangeListener barSettings=(prefs,key)->{
        if("barStyle".equals(key)||"capsuleColor".equals(key)||"capsuleOpacity".equals(key)||"barScale".equals(key)||"ballOpen".equals(key)||"edgeOpen".equals(key))handler.post(this::rebuildBar);
        // 枠／文字・線, switched in the app's settings while this runs.
        if("factorMarks".equals(key)||"skillMarks".equals(key))handler.post(this::refreshFrames);
    };
    /** Whether the floating window is up: the settings restart it when the theme changes. */
    static volatile boolean shown;
    @Override public IBinder onBind(Intent i){return null;}
    /** What is drawn over the game follows the app's theme too, unless that is switched off (see Ui.themed). */
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(Ui.themed(base,true));}
    @Override public void onCreate(){super.onCreate();setTheme(R.style.AppTheme);Ui.palette(this);startForeground(11,Ui.notification(this,"フローティング操作",FloatingService.class,11));shown=true;wm=getSystemService(WindowManager.class);bar=Ui.column(this);bar.setPadding(0,0,0,0);
        // Only the expanded menu has a background; the control bar stays transparent.
        bar.setBackgroundColor(Color.TRANSPARENT);
        row=new LinearLayout(this);bar.addView(row);
        edgeRight=Store.prefs(this).getBoolean("edgeRight",false);
        rebuildBar();Store.prefs(this).registerOnSharedPreferenceChangeListener(barSettings);
        // The mode switched by itself: the menu and the results follow, and the switch is said on screen.
        // Switched by itself: the screen it switched to is a new one to read - the factor list at a run's end,
        // or the skill list of the next run - so what that mode had is saved to the history and cleared.
        CaptureService.modeSwitched=()->handler.post(()->{
            String mode=CaptureService.skillMode(this)?Store.SKILL:Store.FACTOR;boolean had=CaptureService.hasResults(mode);
            ResultHistory.save(this,mode);CaptureService.resetResults(mode);
            // The factor list closes a run: the next horse's aptitudes are read afresh, in either mode (a run can start
            buildMenu();refreshSummary();refreshFrames();
            bubble((Store.SKILL.equals(mode)?"スキルモード":"因子モード")+(had?"（前の結果をリセットしました）":""));});
        // The frames go in first, so every other window - the bar, its menu, the results, the dialogs - sits above them.
        refreshFrames();
        barParams=params("bar",128);
        // A tap anywhere else - on the game - folds the opened ball, handle or menu back up.
        barParams.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        bar.setOnTouchListener((v,e)->{
            if(e.getAction()!=MotionEvent.ACTION_OUTSIDE)return false;
            if(!(barOpen||expanded))return false;
            barOpen=false;if(expanded){expanded=false;buildMenu();}else rebuildBar();return true;
        });
        wm.addView(bar,barParams);drag(barGrip,bar,barParams,"bar",true);buildMenu();refreshSummary();refreshStats();handler.post(ticker);
    }
    WindowManager.LayoutParams params(String key,int width){var p=new WindowManager.LayoutParams(Ui.dp(this,width),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.x=Store.prefs(this).getInt(key+"X",0);p.y=Store.prefs(this).getInt(key+"Y",Ui.dp(this,key.equals("bar")?120:key.equals("stats")?420:220));return p;}
    /**
     * Dragging a floating window, and tapping it. This listener consumes the touches, so a long press has to be
     * recognised here too - the view's own long-click listener never sees anything.
     */
    void drag(View grip,View window,WindowManager.LayoutParams p,String key,boolean click){drag(grip,window,p,key,click,null);}
    /** The bar's position is saved as where it sits closed, so it comes back there however it was left open. */
    int restShift(String key,boolean x){return !key.equals("bar")?0:x?barAX-barRestX:barAY-barRestY;}
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
        boolean swiped,movingEdge;Runnable edgeHold;
        /**
         * The edge handle: dragged up and down along its edge; a swipe inwards (to the right on the left edge, to the
         * left on the right edge) opens it and a swipe back closes it; dragged across more than half the screen it
         * moves to the other edge.
         */
        boolean edgeMove(float dx,float dy,float rawX){
            if(!edgeStyle||grip!=barGrip)return false;
            Rect b=wm.getMaximumWindowMetrics().getBounds();int swipe=Ui.dp(FloatingService.this,32);
            float inward=edgeRight?-dx:dx;
            // Only a long press moves it: an ordinary drag is the swipe that opens and closes it.
            if(!movingEdge){if(edgeHold!=null){handler.removeCallbacks(edgeHold);edgeHold=null;}}
            if(!movingEdge&&!swiped&&Math.abs(dx)>Math.abs(dy)&&Math.abs(dx)>swipe){
                if(inward>0&&!barOpen){swiped=true;barOpen=true;rebuildBar();}
                else if(inward<0&&barOpen){swiped=true;barOpen=false;if(expanded){expanded=false;buildMenu();}else rebuildBar();}
                return true;
            }
            if(!movingEdge)return true;
            if(Math.abs(dx)>=b.width()/2f){boolean right=rawX>b.width()/2f;if(right!=edgeRight){edgeRight=right;Store.prefs(FloatingService.this).edit().putBoolean("edgeRight",right).apply();rebuildBar();}}
            p.y=Math.max(0,Math.min(b.height()-window.getHeight(),sy+(int)dy));fitBar();return true;
        }
        public boolean onTouch(View v,android.view.MotionEvent e){switch(e.getAction()){
        case MotionEvent.ACTION_DOWN:if(v==summary)summaryDragging=true;x=e.getRawX();y=e.getRawY();sx=p.x;sy=p.y;moved=false;swiped=false;movingEdge=false;down=android.os.SystemClock.uptimeMillis();startHold(v);
            if(edgeStyle&&grip==barGrip){edgeHold=()->{movingEdge=true;edgeHold=null;v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);};handler.postDelayed(edgeHold,400);}
            return true;
        case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>Ui.dp(FloatingService.this,8)){if(!moved)stopHold();moved=true;}if(moved&&edgeMove(dx,dy,e.getRawX()))return true;if(moved){Rect b=wm.getMaximumWindowMetrics().getBounds();p.x=Math.max(0,Math.min(b.width()-window.getWidth(),sx+(int)dx));p.y=Math.max(0,Math.min(b.height()-window.getHeight(),sy+(int)dy));wm.updateViewLayout(window,p);}return true;
        case MotionEvent.ACTION_UP:summaryDragging=false;stopHold();if(edgeHold!=null){handler.removeCallbacks(edgeHold);edgeHold=null;}
            // A long press that did not move is not a tap either.
            if(movingEdge){movingEdge=false;Store.prefs(FloatingService.this).edit().putInt(key+"Y",p.y+restShift(key,false)).apply();return true;}
            long now=android.os.SystemClock.uptimeMillis();
            // A quick sideways flick on the result window switches between factors and skills; the window goes
            // back where it was. A slower drag, or a mostly vertical one, just moves the window as before.
            float fx=e.getRawX()-x,fy=e.getRawY()-y;
            // Against a screen edge the window cannot move that way at all, so a sideways swipe there switches however slow it was.
            boolean pinned=p.x==sx;
            if(moved&&v==summary&&(now-down<=FLICK_MS||pinned)&&Math.abs(fx)>=Ui.dp(FloatingService.this,48)&&Math.abs(fx)>Math.abs(fy)*2){
                p.x=sx;p.y=sy;wm.updateViewLayout(window,p);
                switchMode(CaptureService.skillMode(FloatingService.this)?Store.FACTOR:Store.SKILL);
                return true;
            }
            Store.prefs(FloatingService.this).edit().putInt(key+"X",p.x+restShift(key,true)).putInt(key+"Y",p.y+restShift(key,false)).apply();
            if(moved&&v==summary){
                summaryHomeX=p.x;summaryRight=p.x+window.getWidth()>=wm.getCurrentWindowMetrics().getBounds().width()-Ui.dp(FloatingService.this,8);
                Store.prefs(FloatingService.this).edit().putBoolean("summaryRight",summaryRight).apply();
            }
            if(moved||swiped||!click||fired)return true;
            // Held past the start of the countdown but let go before the end: cancelled, and not a tap either.
            if(now-down>=500&&v.hasOnLongClickListeners())return true;
            if(doubleTap==null){v.performClick();return true;}
            if(now-lastTap<400){if(pending!=null)handler.removeCallbacks(pending);pending=null;lastTap=0;doubleTap.run();return true;}
            lastTap=now;pending=v::performClick;handler.postDelayed(pending,400);
            return true;
        case MotionEvent.ACTION_CANCEL:summaryDragging=false;stopHold();if(edgeHold!=null){handler.removeCallbacks(edgeHold);edgeHold=null;}movingEdge=false;return true;
        }return false;}});}
    void buildMenu(){if(destroyed||lostOverlay())return;Ui.palette(this);if(menuScroll!=null){bar.removeView(menuScroll);menuScroll=null;}rebuildBar();if(!expanded)return;menu=Ui.column(this);menu.setBackgroundColor(Color.TRANSPARENT);menu.setPadding(Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,4));
        // The two kinds of preset are listed separately: in skill support only skill presets make sense.
        boolean skillMode=CaptureService.skillMode(this);
        boolean hofMode=CaptureService.hofMode(this);
        boolean factorMode=!skillMode&&!hofMode;
        String kind=Store.FACTOR;
        // Full width, or the weighted buttons inside collapse to nothing and the row cannot be tapped.
        LinearLayout modes=new LinearLayout(this);modes.setBackground(Ui.rounded(this,Ui.SURFACE,22));modes.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));
        menu.addView(modes,new LinearLayout.LayoutParams(-1,-2));
        modes.addView(modeButton("因子",Store.FACTOR,factorMode),new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));
        modes.addView(modeButton("スキル",Store.SKILL,skillMode),new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));
        modes.addView(modeButton("殿堂入り",CaptureService.HOF_MODE,hofMode),new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));
        JSONArray all=new JSONArray();JSONArray stored=Store.all(this);
        for(int i=0;i<stored.length();i++)if(Store.kindOf(stored.optJSONObject(i)).equals(kind))all.put(stored.optJSONObject(i));
        String[] names=new String[all.length()];int selected=0;JSONObject current=Store.selected(this,kind);
        for(int i=0;i<all.length();i++){names[i]=all.optJSONObject(i).optString("name");if(current!=null&&current.optString("id").equals(all.optJSONObject(i).optString("id")))selected=i;}
        if(names.length>0&&factorMode){
            String heading="プリセット";
            TextView kindLabel=new TextView(this);kindLabel.setText(heading);kindLabel.setTextColor(Ui.MUTED);kindLabel.setTextSize(12);
            kindLabel.setPadding(Ui.dp(this,4),Ui.dp(this,12),0,Ui.dp(this,6));menu.addView(kindLabel);
            // The one in use as a field; tapping it lists the others.
            TextView field=new TextView(this);field.setText(names[selected]);field.setTextColor(Ui.INK);field.setTextSize(15);field.setSingleLine();
            field.setEllipsize(android.text.TextUtils.TruncateAt.END);field.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            field.setGravity(Gravity.CENTER_VERTICAL);field.setPadding(Ui.dp(this,14),0,Ui.dp(this,10),0);
            var arrow=getDrawable(android.R.drawable.arrow_down_float).mutate();arrow.setTint(Ui.MUTED);field.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,arrow,null);
            field.setBackground(Ui.ripple(this,Ui.rounded(this,Ui.SURFACE,14),14));menu.addView(field,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
            int at=selected;
            field.setOnClickListener(v->{
                AlertDialog d=new AlertDialog.Builder(this).setTitle(heading).setSingleChoiceItems(names,at,(dialog,n)->{
                    dialog.dismiss();
                    String chosen=all.optJSONObject(n).optString("id");
                    JSONObject now=Store.selected(FloatingService.this,kind);
                    if(now!=null&&chosen.equals(now.optString("id")))return;
                    Store.select(FloatingService.this,chosen);refreshSummary();refreshFrames();buildMenu();
                }).setNegativeButton("キャンセル",null).create();
                Ui.show(d,true);
            });
        }
        // Making and editing presets happens on the app's own screen, where there is room for it; over the game
        // only the choice matters, so the menu stays short enough to read at a glance.
        if(skillMode)skillSettings(menu,this::buildMenu);
        // The menu itself covers the rows, and these settings change what is drawn over them, so it folds away
        // first: the small settings window is then the only thing between the user and the game.
        java.util.List<View> tiles=new java.util.ArrayList<>();
        // 殿堂入りモード: the horses kept, and what the gestures do too, as buttons.
        if(hofMode){
            tiles.add(menuTile(android.R.drawable.ic_menu_gallery,"保存した子を見る",false,()->{expanded=false;buildMenu();
                startActivity(new Intent(this,HallOfFameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}));
            tiles.add(menuTile(android.R.drawable.ic_menu_save,"今の分で保存",false,CaptureService::saveHallOfFameNow));
            tiles.add(menuTile(android.R.drawable.ic_menu_revert,"撮り直す",false,()->{CaptureService.retakeHallOfFame();Ui.toast(this,"殿堂入りウマ娘を最初から撮り直します");updateSummary(true);}));
        }
        tiles.add(menuTile(android.R.drawable.ic_menu_preferences,"表示設定",false,()->{expanded=false;buildMenu();settings();}));
        if(!hofMode)tiles.add(menuTile(android.R.drawable.ic_menu_revert,skillMode?"読み取りリセット":"結果をリセット",false,this::resetCurrent));
        // What is used now and then goes under その他, so the menu stays short over the game.
        java.util.List<String> extras=new java.util.ArrayList<>();java.util.List<Runnable> actions=new java.util.ArrayList<>();
        extras.add("1つ前の結果を呼び出す");actions.add(this::restorePrevious);
        extras.add("結果の履歴");actions.add(this::showHistory);
        if(CaptureService.connected){extras.add("画面共有を切る");actions.add(()->{stopService(new Intent(this,CaptureService.class));expanded=false;buildMenu();});}
        if(!extras.isEmpty())tiles.add(menuTile(android.R.drawable.ic_menu_more,"その他",false,()->{
            AlertDialog d=new AlertDialog.Builder(this).setItems(extras.toArray(new String[0]),(dialog,n)->actions.get(n).run()).setNegativeButton("閉じる",null).create();
            Ui.show(d,true);}));
        tiles.add(menuTile(android.R.drawable.ic_lock_power_off,"終了",true,()->{stopService(new Intent(this,CaptureService.class));stopSelf();}));
        // Two to a row; an odd one out takes the whole row.
        GridLayout grid=new GridLayout(this);grid.setColumnCount(2);
        var gridLp=new LinearLayout.LayoutParams(-1,-2);gridLp.topMargin=Ui.dp(this,12);menu.addView(grid,gridLp);
        for(int i=0;i<tiles.size();i++){
            boolean wide=i==tiles.size()-1&&i%2==0;
            var lp=new GridLayout.LayoutParams(GridLayout.spec(i/2),GridLayout.spec(i%2,wide?2:1,1f));lp.width=0;lp.height=Ui.dp(this,56);
            lp.setMargins(i%2==0?0:Ui.dp(this,4),0,i%2==0&&!wide?Ui.dp(this,4):0,Ui.dp(this,8));grid.addView(tiles.get(i),lp);
        }
        fitMenu();
    }
    /**
     * What a tap, two taps and a long press on the result window do.
     * gesture can mean "reset" whether factors or skills are being read.
     */
    static final String[] GESTURES={"none","details","reset","toggle","menu"
        ,"hofRetake","hofSave"
    };
    static final String[] GESTURE_NAMES={"何もしない","詳細を開く","読み取りをリセット","認識の開始／停止","メニューを開く"
        ,"殿堂入りを撮り直す","殿堂入りを今の分で保存"
    };
    static final String[] GESTURE_KEYS={"gestureTap","gestureDouble","gestureLong"};
    static final String[] GESTURE_LABELS={"タップ","2回タップ","長押し"};
    /** Reading factors and reading skills call for different things, so each mode keeps its own gestures. */
    static final String[] FACTOR_DEFAULTS={"details","reset","none"};
    static final String[] SKILL_DEFAULTS={"details","reset","none"};
    static String gestureKey(String mode,int which){return GESTURE_KEYS[which]+"_"+mode;}
    static String gestureDefault(String mode,int which){
        if(HOF.equals(mode))return HOF_DEFAULTS[which];
        return (Store.SKILL.equals(mode)?SKILL_DEFAULTS:FACTOR_DEFAULTS)[which];
    }
    /** While 殿堂入りウマ娘の詳細 is being taken (or its images wait to be sent), the gestures are its own. */
    static final String HOF="hof";
    static final String[] HOF_DEFAULTS={"none","hofRetake","hofSave"};

    /** How long the long press counts down before it acts. */
    static final int HOLD_SECONDS=3;
    /** Shown in place of the results while a long press counts down; null otherwise. */
    String holdNotice;
    String gestureAction(int which){
        String mode=CaptureService.skillMode(this)?Store.SKILL:Store.FACTOR;
        if(CaptureService.hofActive()||CaptureService.hofMode(this))mode=HOF;
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
            case "reset" -> {resetCurrent();Ui.toast(this,"リセットしました");}
            case "toggle" -> {if(CaptureService.running)CaptureService.pause(this);else if(CaptureService.connected)CaptureService.resume(this);}
            case "menu" -> {expanded=!expanded;buildMenu();}
            case "hofRetake" -> {CaptureService.retakeHallOfFame();Ui.toast(this,"殿堂入りウマ娘を最初から撮り直します");updateSummary(true);}
            case "hofSave" -> CaptureService.saveHallOfFameNow();
            default -> {}
        }
    }

    /** Recognition reads either the factor list or the skill list; the button row shows which is on. */
    /** One side of the 因子 | スキル switch: the one in use is a raised pill. */
    TextView modeButton(String label,String mode,boolean active){
        TextView button=new TextView(this);button.setText(label);button.setGravity(Gravity.CENTER);button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));button.setTextColor(active?Ui.ACCENT:Ui.MUTED);
        if(active)button.setBackground(Ui.rounded(this,Ui.CARD,19));
        button.setOnClickListener(v->{if(!active)switchMode(mode);});
        return button;
    }
    /** How quick a sideways flick on the result window has to be to switch modes rather than move the window. */
    static final long FLICK_MS=300;
    /** Switches what is read - the menu's mode buttons and the flick on the result window both come here. */
    void switchMode(String mode){
        // The results of both modes stay: switching only changes which one is read and shown.
        Store.prefs(this).edit().putString("mode",mode).commit();
        buildMenu();refreshSummary();refreshFrames();
        bubble(Store.SKILL.equals(mode)?"スキルモード":CaptureService.HOF_MODE.equals(mode)?"殿堂入りモード":"因子モード");
    }
    /** The bubble on screen now, so a quick second switch replaces it rather than stacking another. */
    private TextView bubble;
    /**
     * A large word in the middle of the screen that fades out after a moment: which mode a flick switched to. It
     * lets every touch through, so the game can be played while it shows.
     */
    /**
     * The permission to draw over other apps taken back while this runs: the next window added would throw and take
     * the whole app down. Everything stops instead, as when ended from the menu.
     */
    private boolean overlayLost;
    /** Set in onDestroy: callbacks that arrive after it (a fetch, a dialog) find the windows gone and do nothing. */
    private boolean destroyed;
    boolean lostOverlay(){
        if(android.provider.Settings.canDrawOverlays(this))return false;
        if(!destroyed&&!overlayLost){overlayLost=true;Ui.toast(this,"「他のアプリの上に重ねて表示」が許可されていないので終了します");stopService(new Intent(this,CaptureService.class));stopSelf();}
        return true;
    }
    void bubble(String text){
        if(destroyed||lostOverlay())return;
        if(bubble!=null){bubble.animate().cancel();try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
        TextView view=new TextView(this);view.setText(text);view.setTextSize(22);view.setTextColor(Color.WHITE);
        view.setTypeface(null,Typeface.BOLD);view.setGravity(Gravity.CENTER);
        int padX=Ui.dp(this,28),padY=Ui.dp(this,16);view.setPadding(padX,padY,padX,padY);
        var shape=new android.graphics.drawable.GradientDrawable();shape.setColor(0xcc202b2c);shape.setCornerRadius(Ui.dp(this,24));
        view.setBackground(shape);
        var p=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.CENTER;
        // Touches pass only through windows faint enough (Android 12+): together with the frames' window under it
        // (this app's windows add up), at most the system's limit. Otherwise the game would get no taps there.
        float most=getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch();
        float under=frames!=null&&frames.getVisibility()==View.VISIBLE&&frames.getParent()!=null?((WindowManager.LayoutParams)frames.getLayoutParams()).alpha:0f;
        p.alpha=Math.max(0f,Math.min(most,1-(1-most)/(1-under)));
        wm.addView(view,p);bubble=view;
        view.animate().alpha(0f).setStartDelay(700).setDuration(400).withEndAction(()->{
            if(bubble==view){try{wm.removeView(view);}catch(Exception ignored){}bubble=null;}
        }).start();
    }
    /**
     * Skill support: the event, the running style, and the skills read so far. The plan is worked out when the
     * button is pressed, with the points left at that moment, and its skills are framed on screen until bought.
     */
    /**
     * The skill details, in two tabs: スキル, the skills read so far, and 設定, the same settings as the menu's. The
     * tab last looked at is the one that opens. In 評価試験 a third, 適性, comes first: only the aptitudes, so the
     * window is short enough to set them beside the game's own screen.
     */
    void renderSkillDetails(){
        var preset=Store.selected(this,Store.SKILL);
        var rows=CaptureService.skillsInOrder();
        int tab=Store.prefs(this).getInt("skillDetailTab",0);
        int[] order={0,1};
        if(tab>=order.length)tab=0;
        boolean tools=CaptureService.usesPlan(this),levelsShown=CaptureService.usesSkillSet(this);
        JSONObject set=levelsShown?Store.selected(this):null;
        String signature=tab+"/"+CaptureService.skillFrameMode(this)+rows.toString()+CaptureService.ownedPt+CaptureService.planned+String.valueOf(preset)+String.valueOf(set);
        // The stats and aptitudes read by themselves show on the 適性 tab in every mode (scoreVersion moves).
        if(signature.equals(detailSignature))return;detailSignature=signature;
        detailTabs.removeAllViews();detailBody.removeAllViews();
        LinearLayout switcher=new LinearLayout(this);switcher.setBackground(Ui.rounded(this,Ui.SURFACE,22));switcher.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));
        String[] tabs={"スキル","設定","適性"};int tabWidth=Ui.dp(this,order.length>2?100:140);
        for(int n:order){int shown=tab;
            TextView t=new TextView(this);t.setText(tabs[n]);t.setGravity(Gravity.CENTER);t.setTextSize(14);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            t.setTextColor(n==tab?Ui.ACCENT:Ui.MUTED);if(n==tab)t.setBackground(Ui.rounded(this,Ui.CARD,19));
            t.setOnClickListener(v->{if(n!=shown){Store.prefs(this).edit().putInt("skillDetailTab",n).apply();detailSignature="";renderDetails();}});
            switcher.addView(t,new LinearLayout.LayoutParams(tabWidth,Ui.dp(this,38)));
        }
        detailTabs.addView(switcher);
        if(tab==1){skillSettings(detailBody,()->{detailSignature="";renderDetails();if(expanded)buildMenu();});return;}
        var gains=java.util.Map.<String,Double>of();var banned=java.util.Set.<String>of();var plan=java.util.Set.<String>of();
        var levels=levelsShown?Store.rules(this):java.util.Map.<String,java.util.List<Store.Rule>>of();
        // Each skill rated as the plan sees it: bought from what is owned now, so its price takes along the unowned
        // ones below it and its gain is what it adds over the owned one it replaces (the same as under its name).
        java.util.List<CharSequence> take=new java.util.ArrayList<>(),rest=new java.util.ArrayList<>(),
                                     owned=new java.util.ArrayList<>(),unknown=new java.util.ArrayList<>();
        for(var row:rows){
            String key=Store.normalize(row.name());Double gain=gains.get(key);
            String price=row.acquired()?"取得済":row.pt()>0?row.pt()+"pt":"pt不明";
            String rate="";
            // The preset's level as a bar of its colour in front of the name.
            var line=new android.text.SpannableStringBuilder();
            int level=Store.registered(levels,row.name());int color=level>=0&&level<=Store.PRIORITY_MAX?FactorFrames.color(level):0;
            if(color!=0){line.append("▌ ");line.setSpan(new android.text.style.ForegroundColorSpan(color),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            line.append(row.name()).append("  ").append(price).append(rate);
            if(banned.contains(key))continue;
            if(row.acquired())owned.add(line);
            else if(plan.contains(row.name()))take.add(line);
            else if(tools&&gain==null)unknown.add(line);
            else rest.add(line);
        }
        if(tools){section("取る（枠を表示中）",take);section("候補",rest);section("効率データなし",unknown);}
        else section("読み取ったスキル",rest);
        section("取得済",owned);
        if(rows.isEmpty()){TextView empty=new TextView(this);empty.setText("スキル取得画面を一度スクロールすると、ここに一覧が出ます。");empty.setTextColor(Ui.MUTED);detailBody.addView(empty);}
    }
    /**
     * The skill screen's settings: 動作モード, then what it uses - the event and running style (with the efficiency data), the preset
     * (the factor preset: see CaptureService.skillMark). The same fields in the menu and in the details' 設定 tab;
     * refresh rebuilds whichever shows them.
     */
    void skillSettings(LinearLayout into,Runnable refresh){
        int mode=CaptureService.skillFrameMode(this);
        into.addView(settingField("動作モード",CaptureService.FRAME_MODES[mode],null,()->chooseFrameMode(refresh)));
        if(CaptureService.usesSkillSet(this)){
            JSONObject set=Store.selected(this);
            JSONArray entries=set==null?null:set.optJSONArray("entries");
            into.addView(settingField("プリセット（因子と共通）",set==null?"未作成（タップして作成）":set.optString("name"),entries==null?null:entries.length()+"件",()->choosePreset(refresh)));
        }
    }
    TextView fieldLabel(String text){
        TextView label=new TextView(this);label.setText(text);label.setTextColor(Ui.MUTED);label.setTextSize(12);
        label.setPadding(Ui.dp(this,4),Ui.dp(this,12),0,Ui.dp(this,6));return label;
    }
    /** A heading and a field showing the value, which opens a choice when tapped. */
    View settingField(String heading,String value,String note,Runnable tap){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(fieldLabel(heading));
        TextView field=new TextView(this);field.setTextColor(Ui.INK);field.setTextSize(15);field.setSingleLine();
        var text=new android.text.SpannableStringBuilder(value);
        if(note!=null){int at=text.length();text.append("   ").append(note);
            text.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED),at,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.RelativeSizeSpan(.8f),at,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        field.setText(text);field.setEllipsize(android.text.TextUtils.TruncateAt.END);field.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        field.setGravity(Gravity.CENTER_VERTICAL);field.setPadding(Ui.dp(this,14),0,Ui.dp(this,10),0);
        var arrow=getDrawable(android.R.drawable.arrow_down_float).mutate();arrow.setTint(Ui.MUTED);field.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,arrow,null);
        field.setBackground(Ui.ripple(this,Ui.rounded(this,Ui.SURFACE,14),14));field.setOnClickListener(v->tap.run());
        box.addView(field,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        return box;
    }
    void chooseFrameMode(Runnable refresh){
        java.util.List<Integer> modes=new java.util.ArrayList<>();
        for(int i=0;i<CaptureService.FRAME_MODES.length;i++)if(CaptureService.FRAME_MODES[i]!=null)modes.add(i);
        CharSequence[] items=new CharSequence[modes.size()];
        for(int n=0;n<items.length;n++){int i=modes.get(n);
            var text=new android.text.SpannableStringBuilder(CaptureService.FRAME_MODES[i]).append("\n");int at=text.length();
            text.append(CaptureService.FRAME_MODE_NOTES[i]);
            text.setSpan(new android.text.style.RelativeSizeSpan(.78f),at,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED),at,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[n]=text;
        }
        AlertDialog d=new AlertDialog.Builder(this).setTitle("動作モード").setSingleChoiceItems(items,modes.indexOf(CaptureService.skillFrameMode(this)),(dialog,n)->{
            dialog.dismiss();
            // Another mode plans in other units on another basis: the plan made in the last one goes.
            if(modes.get(n)!=CaptureService.skillFrameMode(this)){CaptureService.planned=java.util.Set.of();CaptureService.planRanks=java.util.Map.of();
                CaptureService.planLevels=java.util.Map.of();CaptureService.planSpent=null;detailSignature="";}
            Store.prefs(this).edit().putInt("skillFrameMode",modes.get(n)).apply();
            refreshSummary();refreshFrames();refresh.run();
        }).setNegativeButton("キャンセル",null).create();
        Ui.show(d,true);
    }
    /**
     * The preset, from the skill screen: the same factor presets as on the factor screen, since a white factor is
     * the skill of its name. Making and editing one is offered here too, so the skill screen need not be left.
     */
    void choosePreset(Runnable refresh){
        JSONArray all=Store.all(this);java.util.List<JSONObject> presets=new java.util.ArrayList<>();
        for(int i=0;i<all.length();i++)if(Store.FACTOR.equals(Store.kindOf(all.optJSONObject(i))))presets.add(all.optJSONObject(i));
        JSONObject current=Store.selected(this);
        java.util.List<String> items=new java.util.ArrayList<>();java.util.List<Runnable> actions=new java.util.ArrayList<>();
        Runnable changed=()->{refreshSummary();refreshFrames();refresh.run();};
        for(JSONObject preset:presets){
            boolean on=current!=null&&current.optString("id").equals(preset.optString("id"));
            items.add((on?"✓ ":"　 ")+preset.optString("name"));
            actions.add(()->{Store.select(this,preset.optString("id"));changed.run();});
        }
        if(current!=null){items.add("✎ 「"+current.optString("name")+"」を編集");actions.add(()->Ui.edit(this,true,changed));}
        items.add("＋ 新規作成");actions.add(()->Ui.newPreset(this,true,changed));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("プリセット（因子と共通）").setItems(items.toArray(new String[0]),(dialog,n)->actions.get(n).run())
            .setNegativeButton("閉じる",null).create();
        Ui.show(d,true);
    }
    void section(String title,java.util.List<? extends CharSequence> items){
        if(items.isEmpty())return;
        TextView heading=new TextView(this);heading.setText(title+"（"+items.size()+"）");heading.setTextSize(17);heading.setTypeface(null,Typeface.BOLD);
        heading.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,4));detailBody.addView(heading);
        for(CharSequence item:items){TextView row=new TextView(this);row.setText(item);row.setTextSize(14);row.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,4));detailBody.addView(row);}
    }
    /** Resets what the current mode has read, saving it to the result history first. */
    void resetCurrent(){
        String mode=CaptureService.skillMode(this)?Store.SKILL:Store.FACTOR;
        ResultHistory.save(this,mode);CaptureService.resetResults(mode);
        refreshSummary();refreshFrames();
    }
    /** The current mode's saved results, newest first: looked at, and put back to read on from. */
    void showHistory(){
        String mode=CaptureService.skillMode(this)?Store.SKILL:Store.FACTOR;
        var entries=ResultHistory.of(this,mode);
        if(entries.isEmpty()){Ui.toast(this,"まだ履歴がありません（リセットしたときに保存されます）");return;}
        String[] items=new String[entries.size()];
        for(int i=0;i<items.length;i++)items[i]=entries.get(i).when()+"　"+entries.get(i).summary();
        AlertDialog d=new AlertDialog.Builder(this).setTitle((Store.SKILL.equals(mode)?"スキル":"因子")+"の結果の履歴")
            .setItems(items,(dialog,n)->showHistoryEntry(entries.get(n))).setNegativeButton("閉じる",null).create();
        Ui.show(d,true);
    }
    void showHistoryEntry(ResultHistory.Entry entry){
        TextView text=new TextView(this);text.setText(ResultHistory.text(entry));text.setTextColor(Ui.INK);text.setTextSize(14);text.setTextIsSelectable(true);
        text.setPadding(Ui.dp(this,20),Ui.dp(this,8),Ui.dp(this,20),Ui.dp(this,8));
        ScrollView scroll=new ScrollView(this);scroll.addView(text);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(entry.when()+"の結果").setView(scroll)
            .setPositiveButton("この結果を呼び出す",(dialog,w)->restoreResult(entry)).setNegativeButton("閉じる",null).create();
        Ui.show(d,true);
    }
    /** Puts a saved result back; what the mode held is saved first, so nothing is lost either way. */
    void restoreResult(ResultHistory.Entry entry){
        ResultHistory.restore(this,entry);refreshSummary();refreshFrames();if(details!=null&&details.isShowing()){detailSignature="";renderDetails();}
        Ui.toast(this,entry.when()+" の結果を呼び出しました。続きから読み取れます");
    }
    void restorePrevious(){
        String mode=CaptureService.skillMode(this)?Store.SKILL:Store.FACTOR;
        var entry=ResultHistory.previous(this,mode);
        if(entry==null){Ui.toast(this,"呼び出せる前の結果がありません");return;}
        restoreResult(entry);
    }
    /** Two buttons side by side, each half the menu's width. */
    /** A button of the menu: its icon in a soft circle and its name, on a tile. danger is for 終了, in red. */
    View menuTile(int icon,String name,boolean danger,Runnable action){
        int color=danger?(Ui.dark(this)?0xfff06e6e:0xffd03a3a):Ui.ACCENT;
        LinearLayout tile=new LinearLayout(this);tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0);
        tile.setBackground(Ui.ripple(this,Ui.rounded(this,Ui.SURFACE,14),14));tile.setOnClickListener(v->action.run());
        android.widget.ImageView image=new android.widget.ImageView(this);var drawable=getDrawable(icon).mutate();drawable.setTint(color);image.setImageDrawable(drawable);
        int pad=Ui.dp(this,7);image.setPadding(pad,pad,pad,pad);
        // A pale tint of the red behind 終了, the same way as the teal behind the others - never a dark disc.
        image.setBackground(Ui.rounded(this,danger?(Ui.dark(this)?0xff3c2224:0xfffbe3e3):Ui.ICON_BG,16));
        tile.addView(image,new LinearLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32)));
        TextView label=new TextView(this);label.setText(name);label.setTextColor(danger?color:Ui.INK);label.setTextSize(13);label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));label.setPadding(Ui.dp(this,8),0,0,0);
        tile.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        return tile;
    }
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
        if(destroyed||lostOverlay())return;   // a fetch or dialog finishing after onDestroy: its views are gone from the window
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
        // A rounded card under the bar, with a hairline so it stands apart from a game of the same shade.
        menuScroll=new ScrollView(this);menuScroll.addView(menu);menuScroll.setVerticalScrollBarEnabled(false);
        var card=Ui.rounded(this,Ui.CARD,20);card.setStroke(Ui.dp(this,1),Ui.LINE);menuScroll.setBackground(card);menuScroll.setClipToOutline(true);
        var cardLp=new LinearLayout.LayoutParams(-1,Math.max(0,Math.min(wanted,room)));cardLp.topMargin=Ui.dp(this,8);
        bar.addView(menuScroll,cardLp);
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
        if(destroyed||lostOverlay())return;
        // Skill support is built around the frames - they are what says which rows were read and which to buy -
        // so there they are always on, whatever the factor screen's own setting says.
        boolean on=Store.prefs(this).getBoolean("factorFrames",true)||CaptureService.skillMode(this);
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
        if(destroyed||lostOverlay())return;
        if(stats!=null){wm.removeView(stats);stats=null;}
        if(!Store.prefs(this).getBoolean("showStats",false))return;
        stats=new TextView(this);stats.setTextSize(11);stats.setTextColor(Color.WHITE);stats.setBackgroundColor(0xb0000000);
        stats.setPadding(Ui.dp(this,8),Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,6));stats.setText(CaptureService.performance());
        statsParams=params("stats",1);statsParams.width=WindowManager.LayoutParams.WRAP_CONTENT;
        wm.addView(stats,statsParams);drag(stats,stats,statsParams,"stats",false);
    }
    void refreshSummary(){if(destroyed||lostOverlay())return;if(summary!=null){wm.removeView(summary);summary=null;}
        // A new window starts empty: the text last shown has to be put in again, even if it is the same.
        shownSummary=null;var prefs=Store.prefs(this);if(!prefs.getBoolean("showSummary",true))return;
        OutlinedTextView outlined=new OutlinedTextView(this);summary=outlined;summary.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));summary.setTextSize(prefs.getInt("fontSize",14));summary.setTextColor(prefs.getInt("textColor",Color.WHITE));
        outlined.setOutline(prefs.getInt("outlineColor",Color.BLACK),prefs.getBoolean("outline",true)?Ui.dp(this,prefs.getInt("outlineWidth",3)):0);
        int bg=prefs.getInt("bgColor",Color.BLACK);int alpha=prefs.getBoolean("transparent",false)?prefs.getInt("opacity",128):255;
        summary.setBackgroundColor(Color.argb(alpha,Color.red(bg),Color.green(bg),Color.blue(bg)));summaryParams=params("summary",1);summaryHomeX=summaryParams.x;summaryRight=prefs.getBoolean("summaryRight",false);updateSummary(false);wm.addView(summary,summaryParams);summary.setOnClickListener(v->gesture(0));
        summary.setOnLongClickListener(v->{gesture(2);return true;});
        drag(summary,summary,summaryParams,"summary",true,()->gesture(1));
    }
    /** The summary text last put in the window, before its marks were turned into colours. */
    String shownSummary;
    /** The result window under a finger: the ticker leaves its place alone (it would put it back where it was). */
    boolean summaryDragging;
    /** The summary with each marked glyph (see CaptureService.MARK) painted in its frame's colour. */
    static CharSequence frameColored(String text){
        if(text.indexOf(CaptureService.MARK)<0)return text;
        android.text.SpannableStringBuilder b=new android.text.SpannableStringBuilder();
        for(int i=0;i<text.length();i++){
            char ch=text.charAt(i);
            if(ch==CaptureService.MARK&&i+2<text.length()){
                int color=FactorFrames.color(text.charAt(i+1)-'0'),start=b.length();
                b.append(text.charAt(i+2));
                if(color!=0)b.setSpan(new FillColor(color),start,b.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i+=2;
            }else b.append(ch);
        }
        return b;
    }
    /** Colours a glyph's fill only: the outline pass of OutlinedTextView draws with a stroke and keeps the outline colour. */
    static final class FillColor extends android.text.style.CharacterStyle{
        final int color;FillColor(int color){this.color=color;}
        @Override public void updateDrawState(android.text.TextPaint paint){if(paint.getStyle()==Paint.Style.FILL)paint.setColor(color);}
    }
    /** Where the result window was put by hand, and whether against the right edge: it grows away from that edge. */
    int summaryHomeX;boolean summaryRight;
    void updateSummary(boolean attached){
        // With the mode switching by itself, a mode that has read nothing yet (after a reset, through the rest of a
        // run) shows nothing: the window only comes back once there is something read to show.
        boolean nothingYet=Store.prefs(this).getBoolean("autoMode",true)&&!CaptureService.hasResults(CaptureService.skillMode(this)?Store.SKILL:Store.FACTOR);
        // 殿堂入り: shown while its screen is up (or its images wait to be sent); in its mode otherwise, like the others
        // with the mode switching by itself, nothing until there is something to show.
        if(CaptureService.hofActive())nothingYet=false;else if(CaptureService.hofMode(this))nothingYet=Store.prefs(this).getBoolean("autoMode",true);
        String text=holdNotice!=null?holdNotice:nothingYet?"":CaptureService.summary(this);if(!text.equals(shownSummary)){shownSummary=text;summary.setText(frameColored(text));}
        summary.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);if(text.isEmpty())return;
        Rect bounds=wm.getCurrentWindowMetrics().getBounds();
        summary.measure(View.MeasureSpec.makeMeasureSpec(bounds.width(),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int width=summary.getMeasuredWidth();
        // Where it was left, not where a longer text last pushed it; left against the right edge, it keeps to it.
        int x=summaryRight?bounds.width()-width:Math.max(0,Math.min(summaryHomeX,bounds.width()-width));
        int y=Math.max(0,Math.min(summaryParams.y,bounds.height()-summary.getMeasuredHeight()));
        if(summaryDragging){x=summaryParams.x;y=summaryParams.y;}
        if(summaryParams.width!=width||summaryParams.x!=x||summaryParams.y!=y){
            summaryParams.width=width;summaryParams.x=x;summaryParams.y=y;
            android.util.Log.d("FactorSummary","結果ウィンドウ w="+width+" x="+x+" y="+y+" 文字「"+text+"」");
            if(attached)wm.updateViewLayout(summary,summaryParams);
        }
    }
    /**
     * child in a frame that, held still for the long-press time, takes the touch over and moves the window with
     * the finger (a short buzz says so) until it is lifted; a tap or a scroll goes to the child as always.
     */
    FrameLayout holdToMove(View child,Window[] window,Rect bounds,int width){
        int slop=ViewConfiguration.get(this).getScaledTouchSlop(),lowest=Ui.dp(this,120);
        FrameLayout frame=new FrameLayout(this){
            boolean moving;float downX,downY;int startX,startY;
            final Runnable hold=()->{if(window[0]==null)return;moving=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                var p=window[0].getAttributes();startX=p.x;startY=p.y;};
            @Override public boolean onInterceptTouchEvent(MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN->{moving=false;downX=e.getRawX();downY=e.getRawY();postDelayed(hold,ViewConfiguration.getLongPressTimeout());}
                    case MotionEvent.ACTION_MOVE->{if(!moving&&Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>slop)removeCallbacks(hold);}
                    case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->removeCallbacks(hold);
                }
                return moving;
            }
            @Override public boolean onTouchEvent(MotionEvent e){
                // Touches nothing inside took (the title) come here from the start: kept, so a hold there moves too.
                if(!moving){
                    switch(e.getActionMasked()){
                        case MotionEvent.ACTION_DOWN->{downX=e.getRawX();downY=e.getRawY();removeCallbacks(hold);postDelayed(hold,ViewConfiguration.getLongPressTimeout());return true;}
                        case MotionEvent.ACTION_MOVE->{if(Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>slop)removeCallbacks(hold);return true;}
                        default->{removeCallbacks(hold);return true;}
                    }
                }
                if(window[0]==null)return true;
                var p=window[0].getAttributes();
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_MOVE->{
                        p.x=Math.max(0,Math.min(bounds.width()-width,startX+(int)(e.getRawX()-downX)));
                        p.y=Math.max(0,Math.min(bounds.height()-lowest,startY+(int)(e.getRawY()-downY)));window[0].setAttributes(p);}
                    case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{moving=false;
                        Store.prefs(getContext()).edit().putInt("detailsX",p.x).putInt("detailsY",p.y).apply();}
                }
                return true;
            }
        };
        frame.addView(child);return frame;
    }
    void showDetails(){Ui.palette(this);
        if(details!=null&&details.isShowing())return;
        LinearLayout content=Ui.column(this);HorizontalScrollView tabsScroll=new HorizontalScrollView(this);detailTabs=new LinearLayout(this);tabsScroll.addView(detailTabs);content.addView(tabsScroll);
        // The window is as tall as what it shows, up to the height it always had: a short list left a tall empty
        // window over the game. The list scrolls within that height.
        Rect bounds=wm.getMaximumWindowMetrics().getBounds();int tallest=Math.min(Ui.dp(this,440),bounds.height()-Ui.dp(this,200));
        ScrollView rows=new ScrollView(this){@Override protected void onMeasure(int w,int h){
            super.onMeasure(w,MeasureSpec.makeMeasureSpec(tallest,MeasureSpec.AT_MOST));}};
        detailBody=Ui.column(this);rows.addView(detailBody);content.addView(rows,new LinearLayout.LayoutParams(-1,-2));
        // Held for a moment anywhere - its title or its list - the window can be dragged where it does not cover
        // what is wanted on the game (the aptitudes on 育成完了確認, say). Where it was left is where it opens next time.
        TextView title=new TextView(this);title.setText(CaptureService.skillMode(this)?"スキル":"取得した因子");title.setTextColor(Ui.INK);
        title.setTextSize(20);title.setTypeface(null,Typeface.BOLD);title.setPadding(Ui.dp(this,24),Ui.dp(this,20),Ui.dp(this,24),Ui.dp(this,8));
        Window[] held=new Window[1];int width=Math.min(Ui.dp(this,360),bounds.width()-Ui.dp(this,24));
        details=new AlertDialog.Builder(this).setCustomTitle(holdToMove(title,held,bounds,width)).setView(holdToMove(content,held,bounds,width))
            .setPositiveButton("閉じる",null).create();Ui.show(details,true);
        var window=details.getWindow();held[0]=window;
        window.setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setGravity(Gravity.TOP|Gravity.START);
        var prefs=Store.prefs(this);var at=window.getAttributes();
        at.x=Math.min(prefs.getInt("detailsX",(bounds.width()-width)/2),bounds.width()-width);
        at.y=Math.min(prefs.getInt("detailsY",bounds.height()/3),bounds.height()-Ui.dp(this,120));window.setAttributes(at);
        detailSignature="";renderDetails();
    }
    void renderDetails(){
        if(destroyed||lostOverlay())return;
        if(CaptureService.skillMode(this)){renderSkillDetails();return;}
        // When the chosen page is gone, open the first page that exists (e.g. only 再獲得因子2 was read), not an empty 元因子.
        var data=CaptureService.snapshot();if(!data.containsKey(detailLabel))detailLabel=data.isEmpty()?"元因子":data.keySet().iterator().next();
        String signature=data.toString()+String.valueOf(Store.selected(this))+detailLabel;
        if(signature.equals(detailSignature))return;detailSignature=signature;detailTabs.removeAllViews();
        for(String label:data.keySet()){Button tab=Ui.button(this,label,()->{detailLabel=label;detailSignature="";renderDetails();});tab.setTextColor(label.equals(detailLabel)?Ui.ACCENT:Ui.MUTED);tab.setSelected(label.equals(detailLabel));detailTabs.addView(tab);}
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

    void settings(){Ui.palette(this);
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
                button.setTextColor(tab.equals(settingsTab)?Ui.ACCENT:Ui.MUTED);
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
        // The window sits low, so the keyboard for a typed number covered all of it: while the keyboard is up
        // the window moves up out of its way, and goes back to where it was when the keyboard goes.
        int[] home={p.y};
        window.getDecorView().setOnApplyWindowInsetsListener((v,insets)->{
            int covered=insets.getInsets(WindowInsets.Type.ime()).bottom;
            if(!insets.isVisible(WindowInsets.Type.ime())){if(p.y!=home[0]){p.y=home[0];window.setAttributes(p);}}
            else{
                if(covered>0){p.y=Math.max(0,p.y-covered-Ui.dp(this,8));window.setAttributes(p);}
                View focused=v.findFocus();
                if(focused!=null)v.postDelayed(()->focused.requestRectangleOnScreen(new Rect(0,0,focused.getWidth(),focused.getHeight()),false),150);
            }
            return v.onApplyWindowInsets(insets);
        });
        grip.setOnTouchListener(new View.OnTouchListener(){float x,y;int sx,sy;
            public boolean onTouch(View v,MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN: x=e.getRawX();y=e.getRawY();sx=p.x;sy=p.y;return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x=Math.max(0,Math.min(screen.width()-Ui.dp(FloatingService.this,330),sx+(int)(e.getRawX()-x)));
                        p.y=Math.max(0,Math.min(screen.height()-Ui.dp(FloatingService.this,120),sy+(int)(e.getRawY()-y)));
                        window.setAttributes(p);return true;
                    case MotionEvent.ACTION_UP:
                        home[0]=p.y;Store.prefs(FloatingService.this).edit().putInt("settingsX",p.x).putInt("settingsY",p.y).apply();return true;
                }
                return false;
            }});
    }

    /** What is drawn over the game: the frames around the factors. */
    void frameSettings(LinearLayout content){
        var prefs=Store.prefs(this);
        Switch factorFrames=new Switch(this);factorFrames.setText("因子に枠を付ける（優先度の順に 赤・橙・黄・緑・青・藍・紫、条件未達は灰）");
        factorFrames.setChecked(prefs.getBoolean("factorFrames",true));content.addView(factorFrames);
        factorFrames.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("factorFrames",on).apply();refreshFrames();});
        Ui.section(content,"行に重ねる表示（スキル）");
        Switch showNames=new Switch(this);showNames.setText("認識したスキル名を表示する");showNames.setChecked(prefs.getBoolean("showSkillNames",false));content.addView(showNames);
        showNames.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("showSkillNames",on).apply();CaptureService.showNames=on;});
        slider(content,"文字サイズ",0,80,"nameTextSize",38,this::refreshFrames);
        slider(content,"上下位置",0,100,"rowTextDrop",58,this::refreshFrames);
        slider(content,"左右位置",-100,200,"nameTextShift",0,this::refreshFrames);
        colors(content,"文字色","rowTextColor",0xff202b2c,this::refreshFrames);
        colors(content,"縁取りの色","rowOutlineColor",Color.WHITE,this::refreshFrames);
        slider(content,"縁取りの太さ",0,40,"rowOutlineWidth",14,this::refreshFrames);
    }

    /** Which action each gesture on the result window runs. */
    void gestureSettings(LinearLayout content){
        // Only the mode being used is shown: the other one's settings are noise while adjusting this one.
        boolean skill=CaptureService.skillMode(this);
        gestureGroup(content,skill?"スキルモード":"因子モード",skill?Store.SKILL:Store.FACTOR);
        gestureGroup(content,"殿堂入りウマ娘の保存中",HOF);
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
        TextView frequency=new TextView(this);frequency.setText("毎秒の文字認識目標回数（枠はこれとは別に画面に合わせて動きます）");content.addView(frequency);
        EditText rate=new EditText(this);rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);rate.setSingleLine();
        rate.setText(prefs.getString("recognitionRateText",CaptureService.DEFAULT_RATE));content.addView(rate);
        content.addView(Ui.button(this,"文字認識の回数を適用",()->{try{String value=CycleLimit.validate(rate.getText().toString());prefs.edit().putString("recognitionRateText",value).apply();Ui.toast(this,"認識回数を保存しました");}catch(Exception e){rate.setError("1以上の整数を入力してください");}}));
        Switch showStats=new Switch(this);showStats.setText("処理状況をフローティング表示（回/秒・処理時間など）");showStats.setChecked(prefs.getBoolean("showStats",false));content.addView(showStats);
        showStats.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("showStats",on).apply();refreshStats();});
        Switch auto=new Switch(this);auto.setText("因子画面とスキル取得画面を見分けて、モードを自動で切り替える");
        auto.setChecked(prefs.getBoolean("autoMode",true));content.addView(auto);
        auto.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("autoMode",on).apply());
        Ui.section(content,"推論");
        // Two automatic choices, both measuring every thread count: the one using the least CPU (the default, for
        // heat) or the fastest. A number pins it. Applied from the next cycle.
        TextView threadsLabel=new TextView(this);threadsLabel.setText("推論スレッド（標準の推論方式のとき）");content.addView(threadsLabel);
        int[] threadOptions=FactorVision.threadOptions(Runtime.getRuntime().availableProcessors());String[] threadNames=new String[threadOptions.length+2];
        threadNames[0]="自動・発熱抑制重視（おすすめ）";threadNames[1]="自動・最速重視";
        for(int i=0;i<threadOptions.length;i++)threadNames[i+2]=threadOptions[i]+" スレッド";
        Spinner threads=Ui.spinner(this,threadNames);int savedThreads=prefs.getInt("inferenceThreads",0);
        int shown=savedThreads==ThreadTuner.AUTO_FASTEST?1:0;for(int i=0;i<threadOptions.length;i++)if(threadOptions[i]==savedThreads)shown=i+2;
        threads.setSelection(shown);content.addView(threads);
        threads.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int n,long id){
                int chosen=n==0?0:n==1?ThreadTuner.AUTO_FASTEST:threadOptions[n-2];
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
    void colors(LinearLayout content,String title,String key,int def,Runnable changed){TextView label=new TextView(this);label.setText(title);content.addView(label);int[] colors={Color.WHITE,Color.BLACK,0xffff4568,0xffffbf24,0xff28bbbc,0xff277ddd,0xff202b2c};Spinner spinner=Ui.spinner(this,new String[]{"白","黒","赤","黄","青緑","青","濃い灰"});/* 濃い灰: the row labels' default, which must be in the list or the spinner's first call writes 白 */int value=Store.prefs(this).getInt(key,def);for(int i=0;i<colors.length;i++)if(colors[i]==value)spinner.setSelection(i);content.addView(spinner);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
        public void onItemSelected(AdapterView<?> p,View v,int n,long id){
            if(Store.prefs(FloatingService.this).getInt(key,def)==colors[n])return;   // the spinner fires once by itself
            Store.prefs(FloatingService.this).edit().putInt(key,colors[n]).apply();changed.run();}});}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"STOP".equals(i.getAction())){stopService(new Intent(this,CaptureService.class));stopSelf();}return START_NOT_STICKY;}
    /** Drops what the capture service would otherwise call back into this finished service. */
    void clearListeners(){
        CaptureService.modeSwitched=null;
    }
    @Override public void onDestroy(){
        destroyed=true;shown=false;clearListeners();handler.removeCallbacksAndMessages(null);Store.prefs(this).unregisterOnSharedPreferenceChangeListener(barSettings);if(details!=null)details.dismiss();
        for(View v:new View[]{bar,summary,stats,frames})if(v!=null&&v.getParent()!=null)try{wm.removeView(v);}catch(IllegalArgumentException ignored){}
        bar=null;summary=null;stats=null;frames=null;expanded=false;super.onDestroy();}
}
