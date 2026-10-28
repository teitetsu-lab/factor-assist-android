package jp.factor.overlay;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.Log;
import android.view.WindowManager;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class CaptureService extends Service {
    static final String DEFAULT_RATE="10";
    /** running: recognising now. connected: the screen share is alive, possibly paused, so resuming needs no consent dialog. */
    static volatile boolean running=false, connected=false;
    static volatile String message="停止中";
    /** Rows of the last recognised frame for the factor frames overlay; null when there is nothing to frame. */
    static volatile FactorFrames.Snapshot frames;
    /** False while the shared app is hidden (e.g. switched away), so frames never float over another app. */
    static volatile boolean contentVisible=true;
    /** When the last frame arrived from the screen share (elapsedRealtime ms); 0 before the first. For the status display. */
    static volatile long lastImageAt;
    static void pause(Context c){c.startService(new Intent(c,CaptureService.class).setAction("PAUSE"));}
    static void resume(Context c){c.startService(new Intent(c,CaptureService.class).setAction("RESUME"));}
    static final CycleStats stats=new CycleStats();
    /** Frames the screen share delivered: the display sends one only when the screen changes, so this caps the cycles. */
    static final CycleStats arrivals=new CycleStats();
    static String performance(){
        long at=lastImageAt;
        String share="共有画面："+(contentVisible?"表示中":"非表示")+" / 最後の画面："+(at==0?"まだ無し":String.format(Locale.JAPAN,"%.1f秒前",(SystemClock.elapsedRealtime()-at)/1000.0));
        return "状態："+message+"\n"+share+"\n"+stats.text(SystemClock.elapsedRealtimeNanos())+"\n"+arrivalText()+"\n"+stageText();
    }
    static final Map<String,Map<String,Integer>> results=new LinkedHashMap<>();
    /** Where each factor sits in its page, so results are listed as in the game (see PagePlaces). */
    static final PagePlaces places=new PagePlaces();
    static volatile long resultGeneration=0;
    /**
     * Skill support. A skill is taken and given back freely, so nothing accumulates: every row that is read
     * overwrites what was known about it. One scroll through the list therefore leaves the whole screen's
     * worth of skills, each with the price after the hint discount and whether it is already owned.
     */
    record Skill(String name,int pt,boolean acquired,int place) {}
    static final Map<String,Skill> skills=new LinkedHashMap<>();
    /** The names on screen in the last frame, for the ban picker. */
    static volatile List<String> visibleSkills=List.of();
    /** 現在のスキルPt, read from the top of the screen; -1 until it has been read. */
    static volatile int ownedPt=-1;
    /** What the plan says to buy: framed on screen until it is bought or the plan is worked out again. */
    static volatile Set<String> planned=Set.of();
    static boolean skillMode(Context c){return Store.SKILL.equals(Store.prefs(c).getString("mode",Store.FACTOR));}
    /** 殿堂入りモード: 殿堂入りウマ娘の詳細 is taken (HallOfFame), nothing else is read. */
    static final String HOF_MODE="hof";
    static boolean hofMode(Context c){return HOF_MODE.equals(Store.prefs(c).getString("mode",Store.FACTOR));}
    /** The menu and the result window follow a switch into or out of 殿堂入りモード (see FloatingService). */
    static volatile Runnable hofSwitched;
    /**
     * The ○ a ◎ row stands for, else null: the row shows ◎ once its ○ is bought, and the ○ read before is dropped
     * from the list (readSkills), so it is owned without a row of its own.
     */
    static String circleOf(String name){String n=name.trim();return n.endsWith("◎")?n.substring(0,n.length()-1)+"○":null;}
    static synchronized List<Skill> skillsInOrder(){
        List<Skill> all=new ArrayList<>(skills.values());
        all.sort(Comparator.comparingInt(Skill::place));
        return all;
    }
    static synchronized void resetResults(){resultGeneration++;results.clear();places.clear();
    }
    /**
     * Clears one mode's results only: factors and skills are kept apart, so switching modes - by hand or by
     * itself - or resetting one never touches the other (see ResultHistory, which saves them first).
     */
    /** Whether a mode has read anything yet. */
    static synchronized boolean hasResults(String mode){return Store.SKILL.equals(mode)?!skills.isEmpty():!results.isEmpty();}
    static synchronized void resetResults(String mode){
        resultGeneration++;
        if(Store.SKILL.equals(mode)){skills.clear();skillPlaces.clear();visibleSkills=List.of();planned=Set.of();planRanks=Map.of();planLevels=Map.of();planSpent=null;ownedPt=-1;}
        else{results.clear();places.clear();
        }
    }
    /** Told when the mode switched by itself, to rebuild the menu and say so on screen. */
    static volatile Runnable modeSwitched;
    /** Frames in a row showing the other screen's card list and not this mode's own. */
    private int sightings;
    static final int SWITCH_AFTER=3;
    /**
     * Switching between factor and skill reading by itself (自動切り替え, on unless turned off). Only the card lists
     * decide it - each is found on its own screen and not on the other (ScreenKindTest); the older whole-frame
     * factor detection also fires on the skill screen, so it has no say. The other screen has to be seen
     * SWITCH_AFTER frames in a row while this mode's own cards are not, so a passing likeness does not switch it.
     * True when it switched.
     */
    boolean autoSwitch(Bitmap frame,int w,int h,boolean skill,boolean cards){
        if(cards||!Store.prefs(this).getBoolean("autoMode",true)){sightings=0;return false;}
        // Only this mode's list rows are copied each cycle: the other screen's rows are copied here, when looked for.
        if(skill)copyRows(frame,w,(int)(h*FactorVision.LIST_TOP),(int)(h*FactorVision.LIST_BOTTOM));
        else copyRows(frame,w,(int)(h*SkillVision.COPY_TOP),(int)(h*SkillVision.COPY_BOTTOM));
        boolean other=skill?FactorVision.detectCards(pixels,w,h,(int)(h*FactorVision.LIST_TOP),(int)(h*FactorVision.LIST_BOTTOM))!=null
                           :SkillVision.fromCards(pixels,w,h,(int)(h*SkillVision.COPY_TOP),(int)(h*SkillVision.COPY_BOTTOM))!=null;
        sightings=other?sightings+1:0;
        if(sightings<SWITCH_AFTER)return false;
        sightings=0;
        // The mode switched to starts afresh (see FloatingService: its results go to the history first).
        Store.prefs(this).edit().putString("mode",skill?Store.FACTOR:Store.SKILL).commit();
        frames=null;candidate="";stable=0;confirmations.clear();settle.forget();skillHits.clear();skillTop=Integer.MIN_VALUE;
        Runnable told=modeSwitched;if(told!=null)told.run();
        return true;
    }
    /** Puts saved results back as the mode's results, to read on from (ResultHistory). No chip images come back. */
    static synchronized void restore(String mode,org.json.JSONObject data){
        resetResults(mode);
        if(Store.SKILL.equals(mode)){
            org.json.JSONArray list=data.optJSONArray("skills");
            for(int i=0;list!=null&&i<list.length();i++){org.json.JSONObject s=list.optJSONObject(i);
                skills.put(s.optString("name"),new Skill(s.optString("name"),s.optInt("pt",-1),s.optBoolean("acquired"),s.optInt("place",i)));}
            // Kept with places (newer history): they go back to the list's places too, so rows read on fit in.
            Map<String,Integer> at=new HashMap<>();boolean placed=false;
            for(int i=0;list!=null&&i<list.length();i++){org.json.JSONObject s=list.optJSONObject(i);if(s.has("place")){placed=true;at.put(s.optString("name"),s.optInt("place"));}}
            if(placed)skillPlaces.set(Store.SKILL,at);
            ownedPt=data.optInt("ownedPt",-1);
        }else{
            org.json.JSONObject pages=data.optJSONObject("pages");
            if(pages!=null)for(Iterator<String> i=pages.keys();i.hasNext();){String page=i.next();org.json.JSONObject f=pages.optJSONObject(page);
                Map<String,Integer> bag=new LinkedHashMap<>();for(Iterator<String> n=f.keys();n.hasNext();){String name=n.next();bag.put(name,f.optInt(name));}
                results.put(page,bag);}
            org.json.JSONObject at=data.optJSONObject("places");
            if(at!=null)for(Iterator<String> i=at.keys();i.hasNext();){String page=i.next();org.json.JSONObject f=at.optJSONObject(page);if(f==null)continue;
                Map<String,Integer> where=new HashMap<>();for(Iterator<String> n=f.keys();n.hasNext();){String name=n.next();where.put(name,f.optInt(name));}
                places.set(page,where);}
        }
    }
    static synchronized Map<String,Map<String,Integer>> snapshot(){
        Map<String,Map<String,Integer>> copy=new LinkedHashMap<>();
        if(results.containsKey("元因子"))copy.put("元因子",new LinkedHashMap<>(results.get("元因子")));
        for(int n=1;n<=9;n++){String label="再獲得因子"+n;if(results.containsKey(label))copy.put(label,new LinkedHashMap<>(results.get(label)));}
        return copy;
    }
    static String summary(Context c){
        if(hofActive())return hof.summary()+hofLeft();
        if(hofMode(c))return "殿堂入りモード\n殿堂入りウマ娘の詳細を開くと、スキルと継承のタブを撮りためます";
        if(skillMode(c))return skillSummary(c);
        StringBuilder s=new StringBuilder();var rules=Store.rules(c);for(var entry:snapshot().entrySet()){
        s.append(s.length()>0?"\n":"").append(entry.getKey()).append('\n').append(countSummary(entry.getValue(),rules,Store.detailed(c)));
    }
        // Never empty: an empty text hides the window, and it is also where a sideways flick switches to skills.
        return s.length()>0?s.toString():"因子モード　まだ読み取っていません";}
    /** Skill mode: the skills read, and - with the efficiency data - the event, the points left and the plan. */
    static String skillSummary(Context c){
        StringBuilder s=new StringBuilder();
        s.append("スキルモード　");
        int read,owned=0;synchronized(CaptureService.class){read=skills.size();for(Skill skill:skills.values())if(skill.acquired())owned++;}
        s.append("認識 ").append(read).append("件");
        if(owned>0)s.append("（取得済 ").append(owned).append("）");
        return s.toString();
    }
    /**
     * Marks a glyph of the summary for the result window to paint in a frame's colour: MARK, the priority as one
     * digit, then the glyph (see FloatingService.frameColored). Nothing else in the summary uses this character.
     */
    static final char MARK='\uE000';
    static String countSummary(Map<String,Integer> factors,Map<String,List<Store.Rule>> rules){return countSummary(factors,rules,false);}
    /**
     * How many factors of a page fall at each level, short enough for the floating window. A plain preset gives
     * 必3 重2 要1 本2 未5 - the letter of each tier in its frame's colour; a detailed one gives 0:1 1:2 4:2 - each
     * level in use, its number in that level's colour, and its count. The counts keep the window's own text colour. 未 counts
     * the factors not in the preset; a registered factor short of stars is in none of them.
     */
    static String countSummary(Map<String,Integer> factors,Map<String,List<Store.Rule>> rules,boolean detailed){
        StringBuilder s=new StringBuilder();int unset=0;
        if(!detailed){
            String[] letters={"必","重","要","本"};int[] counts=new int[Store.TIERS.length];
            for(var factor:factors.entrySet()){
                String tier=Store.tier(rules,factor.getKey(),factor.getValue());
                if("未設定".equals(tier)){unset++;continue;}
                int i=Arrays.asList(Store.TIERS).indexOf(tier);if(i>=0)counts[i]++;
            }
            for(int i=0;i<letters.length;i++)s.append(MARK).append((char)('0'+i*Store.PER_TIER)).append(letters[i]).append(counts[i]).append(' ');
        }else{
            int[] levels=new int[Store.PRIORITY_MAX];
            for(var factor:factors.entrySet()){
                int priority=Store.priority(rules,factor.getKey(),factor.getValue());
                if(priority==Store.NONE)unset++;
                else if(priority>=0&&priority<Store.PRIORITY_MAX)levels[priority]++;
            }
            for(int i=0;i<levels.length;i++)if(levels[i]>0)s.append(MARK).append((char)('0'+i)).append((char)('0'+i)).append(':').append(levels[i]).append(' ');
        }
        return s.append("未").append(unset).toString();
    }

    // Per-stage timings. 見出し待ち is only what is left of the ML Kit header OCR after the factor inference finished.
    static final String[] STAGES={"取得","画素","行検出","因子推論","見出し待ち","名前確認","そのほか"};
    static final double[] stageMs=new double[STAGES.length];static long headerOcr,headerCached,noRows,noHeader;
    static volatile FactorVision activeVision;static volatile String titleKnown="なし",lastOcrText="";
    /** The models the last cycle ran, and what to call them in the display. */
    static volatile ModelRunner activeRunner;static volatile String activeModels="因子推論";
    static synchronized void resetStages(){Arrays.fill(stageMs,0);headerOcr=0;headerCached=0;noRows=0;noHeader=0;FactorVision v=activeVision;if(v!=null)v.resetCounters();ModelRunner r=activeRunner;if(r!=null)r.resetCounters();}
    /** The stages timed this cycle, so what is left over shows as そのほか (worker only). */
    private static long cycleStaged;
    static synchronized void stage(int i,long nanos){cycleStaged+=nanos;double ms=nanos/1e6;stageMs[i]=stageMs[i]==0?ms:stageMs[i]*.9+ms*.1;}
    static synchronized void headerUsed(boolean cached){if(cached)headerCached++;else headerOcr++;}
    static String arrivalText(){return String.format(Locale.JAPAN,"画面の更新（直近5秒）：%.1f 回/秒",arrivals.rate(SystemClock.elapsedRealtimeNanos(),5));}
    static synchronized String stageText(){
        StringBuilder s=new StringBuilder("段階別（移動平均）");
        for(int i=0;i<STAGES.length;i++)s.append(i%3==0?"\n":" / ").append(STAGES[i]).append(String.format(Locale.JAPAN," %.1fms",stageMs[i]));
        s.append(String.format(Locale.JAPAN,"\n見出しOCR実行：%d / %d 回\n行が見つからない %d 回 / 見出しが読めない %d 回\n見出しの記憶：%s\nOCRが最後に読んだ文字：「%s」",headerOcr,headerOcr+headerCached,noRows,noHeader,titleKnown,lastOcrText));
        ModelRunner r=activeRunner;
        if(r!=null)s.append(String.format(Locale.JAPAN,"\n推論 新規 %d / 再利用 %d 枚",r.inferred,r.reused)).append("\n").append(r.splitText(activeModels)).append("\n").append(r.threadText());
        FactorVision v=activeVision;
        if(v!=null&&r==v.runner)s.append(String.format(Locale.JAPAN,"\n±1再推論 %d / %d 行",v.retriedRows,v.rows));
        return s.toString();
    }
    /** The title text box on a white margin: text touching the image edge is what ML Kit most often fails to detect. */
    static Bitmap ocrInput(Bitmap frame,int[] box){return ocrInput(frame,box,0);}
    /**
     * attempt 0 is the normal read. Retries on a still screen must read it differently, since the same pixels give
     * the same miss: 1 doubles the size, 2 widens the box, 3 does both.
     */
    static Bitmap ocrInput(Bitmap frame,int[] box,int attempt){
        int[] b=box.clone();
        if(attempt>=2){int bw=b[2]-b[0],bh=b[3]-b[1];b[0]=Math.max(0,b[0]-bw/4);b[2]=Math.min(frame.getWidth(),b[2]+bw/4);b[1]=Math.max(0,b[1]-bh/3);b[3]=Math.min(frame.getHeight(),b[3]+bh/3);}
        float scale=attempt%2==1?2f:1f;int pad=32,w=Math.round((b[2]-b[0])*scale),h=Math.round((b[3]-b[1])*scale);
        Bitmap out=Bitmap.createBitmap(w+pad*2,h+pad*2,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(out);canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(frame,new Rect(b[0],b[1],b[2],b[3]),new Rect(pad,pad,pad+w,pad+h),new Paint(Paint.FILTER_BITMAP_FLAG));return out;
    }
    /** Title OCR retries on one unchanged screen before waiting for the next frame. */
    static final int HEADER_RETRIES=3;
    // ML Kit keeps misreading 因 in the title (図, 困, 圏 seen on the S25: 再獲得図子4 on every frame of page 4),
    // so the title is recognised without that character. Only the title text box is read, never factor names.
    static final Pattern REACQUIRED=Pattern.compile("獲得.{0,2}子?([1-9])"), ORIGINAL=Pattern.compile("元.子");
    static String titleLabel(String line){
        String name=Store.normalize(line);Matcher m=REACQUIRED.matcher(name);
        if(m.find())return "再獲得因子"+m.group(1);
        return ORIGINAL.matcher(name).find()?"元因子":"";
    }
    static String headerLabel(Text text){
        String label="";
        for(var block:text.getTextBlocks())for(var line:block.getLines()){String l=titleLabel(line.getText());if(!l.isEmpty())label=l;}
        return label;
    }

    // Frames are received and copied on a dedicated thread, not the main thread the overlay draws on.
    final HandlerThread captureThread=new HandlerThread("FactorCapture");
    Handler handler;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    /** Modules.installed when the models were loaded. */
    private int modelsLoaded=Modules.installed;
    /** Worker only: the models closed, to be loaded again when next needed. */
    void closeModels(){
        activeVision=null;activeRunner=null;
        if(vision!=null)try{vision.close();}catch(Exception ignored){}vision=null;
        if(skillVision!=null)skillVision.close();skillVision=null;
    }
    /** Runs on the worker unless the capture has ended: a callback arriving after onDestroy finds it shut down. */
    void onWorker(Runnable r){if(closed)return;try{worker.execute(r);}catch(java.util.concurrent.RejectedExecutionException ignored){}}
    MediaProjection projection;VirtualDisplay display;ImageReader reader;TextRecognizer headerReader;FactorVision vision;
    boolean busy=false;volatile boolean closed=false,paused=false,freshStart=false;long last=0;int width,height;String candidate="";int stable=0;
    final Map<String,Integer> confirmations=new HashMap<>();
    long workerGeneration=-1;
    // frame is what the worker reads while busy; it is only written on the capture thread while busy is false.
    Bitmap frame;int[] pixels=new int[0];long cycles;
    /** The newest capture, held uncopied until a cycle starts (capture thread only). */
    Image held;int frameW,frameH;
    /** Worker only: which title OCR variant the next read of the same screen uses. */
    int headerAttempt;
    /** Shape of the current page title, so an OCR miss on an unchanged title keeps the page (see TitleMask). */
    final TitleMask titles=new TitleMask();
    /** Rows are only counted once the page has stopped moving (see PageSettle). */
    final PageSettle settle=new PageSettle();
    /** Set by the worker when confident rows are still one read short of confirmation. */
    volatile boolean recheck;
    /** Stops new cycles; the one in progress still finishes, but registers nothing (see process). */
    void pauseNow(){if(projection!=null&&!paused){paused=true;running=false;frames=null;message="一時停止中";handler.post(()->{handler.removeCallbacks(pumpTask);handler.removeCallbacks(reattach);detached=false;releaseHeld();recheck=false;if(display!=null)display.setSurface(null);});}}
    /** The running service, for pauseAndSettle. */
    static volatile CaptureService instance;
    /**
     * Pauses and waits (up to timeoutMs) until the cycle in progress has finished, so nothing is added to the
     * results after this returns. Recognition runs on one worker thread, so an empty task queued behind it marks
     * the moment it is done. Call off the main thread.
     */
    static void pauseAndSettle(long timeoutMs){
        CaptureService s=instance;if(s==null)return;s.pauseNow();
        try{s.worker.submit(()->{}).get(timeoutMs,TimeUnit.MILLISECONDS);}catch(Exception ignored){}
    }
    @Override public void onCreate(){super.onCreate();instance=this;Calibration.current=Calibration.load(this);DrawPlaces.current=DrawPlaces.load(this);captureThread.start();handler=new Handler(captureThread.getLooper());
        hof.init(this);
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        // Pausing keeps the projection token; only the frames stop, by detaching the reader surface.
        if(projection==null&&("PAUSE".equals(intent.getAction())||"RESUME".equals(intent.getAction()))){stopSelf();return START_NOT_STICKY;}
        if("PAUSE".equals(intent.getAction())){Log.d("FactorCapture","一時停止");pauseNow();return START_NOT_STICKY;}
        if("RESUME".equals(intent.getAction())){Log.d("FactorCapture","再開 projection="+(projection!=null)+" paused="+paused+" busy="+busy+" 表示="+contentVisible);if(projection!=null&&paused){paused=false;freshStart=true;stats.reset(SystemClock.elapsedRealtimeNanos());arrivals.reset(SystemClock.elapsedRealtimeNanos());resetStages();running=true;message="認識中";listSeenAt=SystemClock.elapsedRealtime();handler.post(()->{detached=false;if(display!=null&&reader!=null)display.setSurface(reader.getSurface());
                resumedAt=SystemClock.elapsedRealtime();handler.removeCallbacks(resumeCheck);handler.postDelayed(resumeCheck,2500);});}return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try {
            startForeground(12,Ui.notification(this,"因子を認識中",CaptureService.class,12));
            Intent grant=intent.getParcelableExtra("grant",Intent.class);
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result",Activity.RESULT_CANCELED),grant);
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){stopSelf();}
                @Override public void onCapturedContentResize(int w,int h){if(w!=width||h!=height)resize(w,h);}
                @Override public void onCapturedContentVisibilityChanged(boolean visible){contentVisible=visible;Log.d("FactorCapture","共有画面の表示："+visible);}
            },handler);
            headerReader=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            Rect bounds=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();width=bounds.width();height=bounds.height();
            int[] taken=captureSize(width,height,CAPTURE_ACROSS);
            openReader();display=projection.createVirtualDisplay("FactorCapture",taken[0],taken[1],getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
            stats.reset(SystemClock.elapsedRealtimeNanos());arrivals.reset(SystemClock.elapsedRealtimeNanos());resetStages();connected=true;running=false;paused=true;
            // Reading starts at once, unless the settings say to wait for 開始 on the floating bar (認識開始を押したとき):
            // then the game can be set up first without the app reading, and heating the phone, in the meantime.
            if(startsReading(this)){paused=false;freshStart=true;running=true;message="認識中";}
            else{display.setSurface(null);message="待機中（開始で読み取り）";}
        } catch(Exception e){message="開始失敗："+e.getClass().getSimpleName();Ui.toast(this,message);stopSelf();}
        return START_NOT_STICKY;
    }
    /** Whether 認識開始 starts reading straight away, or connects and waits for 開始 (a setting; reading by default). */
    static boolean startsReading(Context c){return Store.prefs(c).getBoolean("startReading",true);}
    /**
     * After 再開 the screen is taken again; once no frame had come at all (another game played meanwhile), and
     * nothing was read. A frame comes at once when the surface is set, so none in 2.5 s means the reader is stuck:
     * it is made anew.
     */
    private long resumedAt;
    final Runnable resumeCheck=()->{
        if(closed||paused||display==null||lastImageAt>=resumedAt)return;
        Log.d("FactorCapture","再開後に画面が届かないので、受け取り口を作り直す "+width+"x"+height);
        detached=false;display.setSurface(null);releaseHeld();if(reader!=null)reader.close();openReader();
        int[] taken=captureSize(width,height,CAPTURE_ACROSS);
        display.resize(taken[0],taken[1],getResources().getDisplayMetrics().densityDpi);display.setSurface(reader.getSurface());
    };
    void resize(int w,int h){
        if(closed||w<=0||h<=0)return;width=w;height=h;int[] taken=captureSize(w,h,CAPTURE_ACROSS);
        Log.d("FactorCapture","共有画面の大きさ："+w+"x"+h+"（受け取り "+taken[0]+"x"+taken[1]+"）"+(paused?"（一時停止中）":""));
        if(display!=null){handler.removeCallbacks(reattach);detached=false;display.setSurface(null);releaseHeld();if(reader!=null)reader.close();openReader();display.resize(taken[0],taken[1],getResources().getDisplayMetrics().densityDpi);if(!paused)display.setSurface(reader.getSurface());}
    }
    /**
     * The screen is taken at about 720 across (its short side) whatever the phone. The models read crops 16px high,
     * which a name gives with room to spare at 720 (26px of it, 39px at 1080), and the titles' OCR still gets its
     * text large enough; every pixel taken beyond that is only more for the phone to scale, copy and read - several
     * times more on a QHD or 4K screen. The phone shrinks its mirror of the screen to this size. Places across the
     * screen are shares of the width throughout, and the frames are drawn scaled back to the screen (FactorFrames).
     */
    static final int CAPTURE_ACROSS=720;
    /** w×h shrunk, its shape kept, until its short side is at most across; a screen no bigger is taken as it is. */
    static int[] captureSize(int w,int h,int across){
        int shorter=Math.min(w,h);if(shorter<=across)return new int[]{w,h};
        double scale=across/(double)shorter;
        return new int[]{w<=h?across:(int)Math.round(w*scale),h<w?across:(int)Math.round(h*scale)};
    }
    void openReader(){
        // One image held by us plus the ones in flight between the display and the listener.
        int[] taken=captureSize(width,height,CAPTURE_ACROSS);
        reader=ImageReader.newInstance(taken[0],taken[1],PixelFormat.RGBA_8888,3);
        // The display only sends a frame when the screen changes, so a frame that arrives while the worker is busy
        // may be the last one for a long time (the list just stopped scrolling). The newest one is always kept, but
        // only held: it is copied when a cycle starts, so a lower cycle rate also means fewer full-screen copies.
        reader.setOnImageAvailableListener(source->{
            Image image;try{image=source.acquireLatestImage();}catch(Exception e){message="画面取得待ち";return;}
            if(image==null)return;
            long now=SystemClock.elapsedRealtime();if(resumedAt>0&&lastImageAt<resumedAt)Log.d("FactorCapture","再開後の最初の画面："+(now-resumedAt)+"ms");
            lastImageAt=now;arrivals.add(SystemClock.elapsedRealtimeNanos(),0);
            if(closed||paused){image.close();return;}
            releaseHeld();held=image;
            // A frame that is not read now - the worker is busy, or the next read is not due yet under the reads-a-
            // second limit - still moves the frames (see followColumn). So they follow the list at the rate the
            // screen changes, while the full read runs at the rate set.
            if(hofMode(this))hofFollow(image);else
            if(busy||!readDue())followColumn(image);
            idleDetach();
            pump();
        },handler);
    }

    /** Where the rows were on the newest frame, found from one column only (see followColumn), and when it was captured. */
    record Rows(long time,List<Integer> ys,List<int[]> boxes,int pitch) {}
    private volatile Rows latestRows;
    /** Capture time of the frame the worker's cycle is reading. */
    private volatile long cycleFrameTime;
    /**
     * Capture thread only. Moves the frames onto the rows of a frame that will not be read in full (the worker is
     * busy, or the next read is not due). Only the column the cards' edges are found in is read, straight from the image, so this
     * costs a couple of thousand pixels: the frames then follow the list at the rate the screen changes rather
     * than at the rate frames are fully read.
     */
    private int[] followBuffer=new int[0];
    void followColumn(Image image){
        FactorFrames.Snapshot last=frames;
        if(hofMode(this))return;   // 殿堂入り: its frames follow the list there (hofFollow)
        if(last==null||last.marks().isEmpty())return;
        try{
            double scanX=FactorVision.CARD_SCAN_X,top=FactorVision.LIST_TOP,bottom=FactorVision.LIST_BOTTOM;
            boolean skill=skillMode(this);
            if(skill){scanX=SkillVision.CARD_SCAN_X;top=SkillVision.COPY_TOP;bottom=SkillVision.COPY_BOTTOM;}
            int w=image.getWidth(),h=image.getHeight();
            var plane=image.getPlanes()[0];var buffer=plane.getBuffer();int rowStride=plane.getRowStride(),pixelStride=plane.getPixelStride();
            int x=(int)Math.rint(scanX*w),from=(int)(h*top),to=(int)(h*bottom);
            int[] column=followBuffer.length==h?followBuffer:(followBuffer=new int[h]);
            for(int y=from;y<to;y++){int i=y*rowStride+x*pixelStride;column[y]=0xff000000|(buffer.get(i)&255)<<16|(buffer.get(i+1)&255)<<8|(buffer.get(i+2)&255);}
            List<int[]> runs=CardList.edgesOfColumn(column,w,from,to);
            if(runs.size()<2)return;
            List<Integer> heights=new ArrayList<>(),steps=new ArrayList<>();
            for(int[] run:runs)heights.add(run[1]-run[0]);
            for(int i=1;i<runs.size();i++)steps.add(runs.get(i)[0]-runs.get(i-1)[0]);
            int unit=FactorVision.median(heights),pitch=FactorVision.median(steps);
            // The cards do not move sideways: their left edge is taken from the frames already drawn.
            int left=-1;for(var m:last.marks())if(m.box()!=null){left=m.box()[0];break;}
            List<Integer> ys=new ArrayList<>();List<int[]> boxes=new ArrayList<>();
            for(int[] run:runs){
                ys.add(skill?(int)Math.rint(run[0]+SkillVision.CARD_ROW_Y*unit):run[0]+unit/2);
                boxes.add(left<0?null:new int[]{left,run[0],w-left,run[1]});
            }
            latestRows=new Rows(image.getTimestamp(),ys,boxes,pitch);
            var moved=FactorFrames.follow(last,ys,boxes,pitch,w,h,last.rowHalf(),last.labelX());
            if(moved!=null)frames=moved;
        }catch(Exception ignored){}
    }
    /**
     * Worker only. Shows frames drawn from this cycle's frame. When the capture thread has since found the rows on
     * a newer frame, they are moved there first: drawn as they are, they would jump back to where the list was.
     */
    void publish(FactorFrames.Snapshot snapshot){
        if(closed||paused){frames=null;return;}   // a cycle finishing after the pause: its frames are not shown
        Rows newer=latestRows;
        if(snapshot!=null&&newer!=null&&newer.time()>cycleFrameTime){
            var moved=FactorFrames.follow(snapshot,newer.ys(),newer.boxes(),newer.pitch(),snapshot.frameWidth(),snapshot.frameHeight(),snapshot.rowHalf(),snapshot.labelX());
            if(moved!=null)snapshot=moved;
        }
        frames=snapshot;
    }
    /** The game's rows were just read, so its screen is being shared and shown: see where this is called. */
    static void showContent(){if(!contentVisible){contentVisible=true;Log.d("FactorCapture","共有画面の表示：行が読めたので表示中に戻す");}}
    void releaseHeld(){if(held!=null){held.close();held=null;}}
    final Runnable pumpTask=this::pump;
    /** Capture thread only. Starts the next cycle on the newest frame, or re-reads the unchanged frame while rows await confirmation. */
    /** Whether pump would start a read now, as far as the reads-a-second limit goes. */
    boolean readDue(){return SystemClock.elapsedRealtimeNanos()-last>=readInterval();}
    /**
     * Most of a run shows neither list (the stat screens, races, events), and looking for one there at the full rate was
     * most of the work done. Once no list has been seen for IDLE_AFTER_MS, reading slows to three a second (待機)
     * and the screen is taken only that often (see idleDetach); the first list seen brings the full rate back.
     */
    static final long IDLE_AFTER_MS=1500,IDLE_NANOS=1_000_000_000L/3;
    private volatile long listSeenAt;
    boolean idle(){
        return SystemClock.elapsedRealtime()-listSeenAt>IDLE_AFTER_MS;
    }
    long readInterval(){long set=CycleLimit.intervalNanos(Store.prefs(this).getString("recognitionRateText",DEFAULT_RATE));return idle()?Math.max(set,IDLE_NANOS):set;}
    /** Worker: a list is on screen. Leaving 待機 takes the screen at every change again at once. */
    void listSeen(){boolean was=idle();listSeenAt=SystemClock.elapsedRealtime();if(was)handler.post(reattach);}
    /**
     * Whether to look for the rows the older way (the whole frame) when the cards were not found: on a device whose
     * cards were never found, always; else only on a list screen, where a card missed while scrolling may be found
     * that way. Off the lists, in 待機, it was a whole-frame copy and search for nothing, several times a second.
     */
    boolean olderWay(boolean cardsSeen){return !cardsSeen||!idle();}
    private volatile boolean factorCardsSeen,skillCardsSeen;
    /**
     * Capture thread. In 待機 the screen is let go right after a frame is taken, and taken back one idle interval
     * later: while let go the system does not draw the copy of the screen at all, rather than drawing it for every
     * change of the game and having it thrown away. Taken back, it draws the current screen at once.
     */
    private boolean detached;
    final Runnable reattach=()->{if(!detached)return;detached=false;if(!closed&&!paused&&display!=null&&reader!=null)display.setSurface(reader.getSurface());};
    void idleDetach(){
        if(detached||closed||paused||display==null||!idle())return;
        detached=true;display.setSurface(null);
        handler.removeCallbacks(reattach);handler.postDelayed(reattach,IDLE_NANOS/1_000_000);
    }
    void pump(){
        if(closed||paused||busy)return;
        // An adjustable upper bound, not a frame queue or a promised frame rate.
        long now=SystemClock.elapsedRealtimeNanos(),interval=readInterval();
        // Rounded up to the millisecond the handler counts in, so it never fires early - and never a whole extra
        // millisecond late, as the old "+1" made it on every cycle.
        if(now-last<interval){handler.removeCallbacks(pumpTask);handler.postDelayed(pumpTask,(last+interval-now+999_999)/1_000_000);return;}
        long grabbed;boolean reread;
        if(held!=null){
            Image image=held;held=null;
            try{
                var plane=image.getPlanes()[0];int padded=plane.getRowStride()/plane.getPixelStride();
                if(frame==null||frame.getWidth()!=padded||frame.getHeight()!=image.getHeight())frame=Bitmap.createBitmap(padded,image.getHeight(),Bitmap.Config.ARGB_8888);
                // Row padding stays in the bitmap; the worker reads only the w×h region, so no cropped copy is needed.
                frame.copyPixelsFromBuffer(plane.getBuffer());frameW=image.getWidth();frameH=image.getHeight();
                cycleFrameTime=image.getTimestamp();
            }catch(Exception e){message="画面取得待ち";return;}
            finally{image.close();}
            grabbed=SystemClock.elapsedRealtimeNanos()-now;reread=false;
        }
        // No new frame means the screen is standing still: reading it again confirms rows seen once (cheap, thanks
        // to the inference cache) or retries a title OCR that missed.
        else if(recheck&&frame!=null){recheck=false;grabbed=0;reread=true;}
        else return;
        // The next cycle is due one interval after this one was due, not after it happened to start: starting a
        // millisecond or two late each time then does not add up (at 30 a second it had come to 28-29). After a
        // cycle that ran long, or a pause, the count starts again from now.
        last=last>0&&now-last<2*interval?last+interval:now;
        busy=true;long generation=resultGeneration;Bitmap target=frame;int w=frameW,h=frameH;boolean again=reread;
        try{worker.execute(()->process(target,w,h,generation,now,grabbed,again));}catch(Exception e){busy=false;}
    }
    /** A model or name list: the downloaded set when there is one, else the bundled one (see Modules). */
    byte[] asset(String name)throws Exception{return Modules.read(this,name);}
    void process(Bitmap frame,int w,int h,long generation,long started,long grabbed,boolean reread){
        Task<Text> pending=null;Bitmap headerImage=null;
        try {
            recheck=false;  // cleared first so an early return can never leave a re-read loop behind
            if(!reread)headerAttempt=0;
            if(closed||generation!=resultGeneration)return;
            // After a pause the screen may be anywhere, so half-confirmed rows from before it must not count.
            if(workerGeneration!=generation||freshStart){workerGeneration=generation;freshStart=false;candidate="";stable=0;confirmations.clear();settle.forget();recentNames.clear();
                // The skill screen's own half-confirmed rows, likewise: after a reset each row needs two fresh reads again.
                skillHits.clear();skillTop=Integer.MIN_VALUE;
                // New models installed since these were loaded (Modules.installed): loaded again from this cycle on.
                if(modelsLoaded!=Modules.installed){modelsLoaded=Modules.installed;closeModels();}}
            // The inference mode can be flipped in the display settings while running, for side-by-side timing.
            int threadSetting=Store.prefs(this).getInt("inferenceThreads",0);
            stage(0,grabbed);  // every mode, so a skill cycle's grab is not counted as そのほか
            // 殿堂入りウマ娘の詳細 is taken whatever the mode, and nothing else reads it (see HallOfFame).
            if(hof.handle(frame,w,h)){if(!hofMode(this)&&Store.prefs(this).getBoolean("autoMode",true))toMode(HOF_MODE);listSeen();recheck=hof.wantsRecheck();hofRead(frame,w,h);hallOfFameAfter(frame,w,h);return;}
            if(hofMode(this)){frames=null;leaveHallOfFame(frame,w,h);return;}
            if(skillMode(this)){readSkills(frame,w,h,generation,threadSetting);return;}
            if(vision==null){var set=Modules.readSet(this,"factor.onnx","factor_rank.onnx","labels.json");
                vision=new FactorVision(set.get("factor.onnx"),set.get("factor_rank.onnx"),new String(set.get("labels.json"),StandardCharsets.UTF_8));activeVision=vision;resetStages();}
            vision.runner.tuner.setThreads(threadSetting);
            activeRunner=vision.runner;activeModels="因子推論";

            long t0=SystemClock.elapsedRealtimeNanos();
            // Only the page title and the list are copied out; everything else on screen is never looked at.
            int[] titleBox=TitleMask.box(w,h);
            int listFrom=(int)(h*FactorVision.LIST_TOP),listTo=(int)(h*FactorVision.LIST_BOTTOM);
            startCopy(w,h);copyRows(frame,w,titleBox[1],titleBox[3]);copyRows(frame,w,listFrom,listTo);
            long t1=SystemClock.elapsedRealtimeNanos();stage(1,t1-t0);
            FactorVision.Layout layout=FactorVision.detectCards(pixels,w,h,listFrom,listTo);boolean cards=layout!=null;
            if(cards)listSeen();
            // No cards there (another device, another layout): the whole frame, and the older way - see olderWay.
            if(layout==null&&olderWay(factorCardsSeen)){copyRows(frame,w,0,h);layout=FactorVision.detect(pixels,w,h);}
            if(cards)factorCardsSeen=true;
            if(autoSwitch(frame,w,h,false,cards))return;
            long t2=SystemClock.elapsedRealtimeNanos();stage(2,t2-t1);
            if(layout==null){frames=null;candidate="";stable=0;confirmations.clear();settle.forget();recentNames.clear();synchronized(CaptureService.class){noRows++;}return;}
            // Rows of the game are on this frame, so the game is on screen: the frames show again even if the
            // "visible again" callback never came (the app then read on but drew nothing until restarted).
            showContent();
            // The page title: an unchanged title is recognised by its shape; anything else, including every page switch, needs OCR.
            byte[] mask=TitleMask.of(pixels,w,h);
            String known=titles.match(mask);boolean cached=known!=null;
            // ML Kit runs on its own executor, so a needed title OCR overlaps the factor inference below.
            // It reads just the title text box: the stylised 因子選択 logo, arrows and page dots only confuse it.
            if(!cached){headerImage=ocrInput(frame,TitleMask.box(w,h),headerAttempt);pending=headerReader.process(InputImage.fromBitmap(headerImage,0));}
            // The frames move onto the rows now, before the models run, so they keep up with a scrolling list.
            if(Store.prefs(this).getBoolean("factorFrames",true)){
                List<int[]> boxes=new ArrayList<>();for(int y:layout.ys())boxes.add(FactorVision.frameOf(layout,y,w));
                FactorFrames.Snapshot early=FactorFrames.follow(frames,layout.ys(),boxes,layout.pitch(),w,h,0,FactorFrames.letterX(layout,w));
                if(early!=null)publish(early);
            }
            List<FactorVision.Reading> readings=vision.read(pixels,w,h,layout);
            long t3=SystemClock.elapsedRealtimeNanos();stage(3,t3-t2);
            // Stars are counted by colour now; where the old star model says otherwise, the log says so.
            String starsDiffer=vision.lastStarDisagreement;
            if(starsDiffer!=null){vision.lastStarDisagreement=null;Log.d("FactorStars",starsDiffer);}
            String label;
            if(cached)label=known;
            // Bounded like every other OCR wait: a recogniser that never answers must not stop recognition for good.
            else{Text text=Tasks.await(pending,2,TimeUnit.SECONDS);String raw=text.getText().replace('\n',' ').trim();lastOcrText=raw.length()>24?raw.substring(0,24)+"…":raw;
                label=headerLabel(text);titles.learn(label,mask);if(titles.current()!=null)titleKnown=titles.current();
                Log.d("FactorTitle","OCR「"+raw+"」 -> "+(label.isEmpty()?"(読めない)":label)+" / 記憶 "+titleKnown);}
            headerUsed(cached);stage(4,SystemClock.elapsedRealtimeNanos()-t3);
            // An unreadable header (about a third of cycles on the S25) only skips this frame. Resetting here kept
            // breaking the two-in-a-row confirmation; a real page change still resets once the next header differs.
            // Frames follow every read row, even when this frame's title was unreadable.
            if(Store.prefs(this).getBoolean("factorFrames",true)){
                var rules=presetRules();boolean detailed=presetDetailed;List<FactorFrames.Mark> marks=new ArrayList<>();
                for(var row:readings)if(row.nameConfidence()>=FactorVision.NAME_MIN&&row.starConfidence()>=FactorVision.STAR_MIN){int priority=Store.priority(rules,row.name(),row.stars());boolean met=priority!=Store.UNMET;
                    int level=met?priority:Store.registered(rules,row.name());
                    marks.add(new FactorFrames.Mark(row.y(),level,met,null,FactorVision.frameOf(layout,row.y(),w),FactorFrames.WHOLE,FactorFrames.levelTag(level,detailed),null));}
                publish(new FactorFrames.Snapshot(w,h,marks,0,FactorFrames.letterX(layout,w)));
            }else frames=null;
            if(label.isEmpty()){
                synchronized(CaptureService.class){noHeader++;}
                // A still screen sends no new frame, so a missed title would never be read again: retry a few times,
                // each with a different OCR input, within the normal cycle interval.
                if(!cached&&headerAttempt<HEADER_RETRIES){headerAttempt++;recheck=true;}
                return;
            }
            // Another page: its rows are other factors, even where one sits at the same place and reads the same to the model.
            if(label.equals(candidate))stable++;else{candidate=label;stable=1;confirmations.clear();recentNames.clear();}
            // Mid swipe the new title is already showing while the previous page's rows are still sliding past.
            // Those rows must not be counted under the new page, so only a frame that matches the previous one counts.
            // The star column as measured on this frame: the fixed one the crops use never moves (FactorVision.Layout).
            if(!settle.settled(mask,layout.measuredStarX(),layout.pitch())){confirmations.clear();recheck=true;return;}
            // Rows the model is only fairly sure of are read again as text and matched against every factor name
            readings=checkFactorNames(frame,w,h,layout,readings);
            // After the names are checked, so a row the model misread is kept in the card book under its right name.
            List<PagePlaces.Row> placed=new ArrayList<>();
            for(var row:readings)if(row.nameConfidence()>=FactorVision.NAME_MIN&&row.starConfidence()>=FactorVision.STAR_MIN)placed.add(new PagePlaces.Row(row.name(),row.y()));
            places.record(label,placed);
            Set<String> present=new HashSet<>();Map<String,Integer> accepted=new LinkedHashMap<>();
            for(var row:readings){
                if(row.nameConfidence()<FactorVision.NAME_MIN||row.starConfidence()<FactorVision.STAR_MIN)continue;
                String key=row.name()+":"+row.stars();present.add(key);
                int hits=confirmations.getOrDefault(key,0)+1;confirmations.put(key,Math.min(2,hits));
                if(hits>=2&&stable>=2)accepted.put(row.name(),row.stars());
            }
            confirmations.keySet().retainAll(present);
            recheck=accepted.size()<present.size();
            // A cycle that finishes after a pause registers nothing: what was paused on (e.g. for a send) must not change.
            if(!closed&&!paused&&stable>=2&&!accepted.isEmpty())synchronized(CaptureService.class){if(generation==resultGeneration)results.computeIfAbsent(label,k->new LinkedHashMap<>()).putAll(accepted);}
            if(!closed&&!paused)message="認識中："+label;   // a cycle ending after a pause must not undo its 一時停止中
        }catch(Exception e){message="認識エラー："+e.getClass().getSimpleName();}
        finally{
            long end=SystemClock.elapsedRealtimeNanos();stats.add(end,end-started);
            synchronized(CaptureService.class){long rest=end-started-cycleStaged;cycleStaged=0;stage(STAGES.length-1,Math.max(0,rest));cycleStaged=0;}
            // A header bitmap still being read by an unfinished OCR task is left to the GC.
            if(headerImage!=null&&(pending==null||pending.isComplete()))headerImage.recycle();
            if(++cycles%50==0)Log.d("FactorCapture",arrivalText()+" 処理 "+String.format(Locale.JAPAN,"%.1f",stats.rate(SystemClock.elapsedRealtimeNanos(),5))+" 回/秒 "+stageText().replace('\n',' '));
            handler.post(()->{busy=false;pump();});
            // Something was taken for the card book while the list had not looked the same long enough: read again
            // once it may have (a still screen sends no new frame), when no effect can be over it.
            if(settleWanted){settleWanted=false;handler.postDelayed(()->{recheck=true;pump();},CardBook.SETTLE_MS+5);}
        }
    }
    /**
     * In 殿堂入りモード off its screen, nothing is read but whether the factor list or the skill list is up: seen
     * SWITCH_AFTER frames in a row, the mode switches to it, as factor and skill reading switch between each other.
     */
    private int factorSightings,skillSightings;
    void leaveHallOfFame(Bitmap frame,int w,int h){
        if(!Store.prefs(this).getBoolean("autoMode",true))return;
        startCopy(w,h);
        int fFrom=(int)(h*FactorVision.LIST_TOP),fTo=(int)(h*FactorVision.LIST_BOTTOM),sFrom=(int)(h*SkillVision.COPY_TOP),sTo=(int)(h*SkillVision.COPY_BOTTOM);
        copyRows(frame,w,fFrom,fTo);copyRows(frame,w,sFrom,sTo);
        boolean factor=FactorVision.detectCards(pixels,w,h,fFrom,fTo)!=null,skill=SkillVision.fromCards(pixels,w,h,sFrom,sTo)!=null;
        factorSightings=factor?factorSightings+1:0;skillSightings=skill?skillSightings+1:0;
        if(factorSightings>=SWITCH_AFTER){factorSightings=0;skillSightings=0;listSeen();toMode(Store.FACTOR);Runnable told=modeSwitched;if(told!=null)told.run();}
        else if(skillSightings>=SWITCH_AFTER){factorSightings=0;skillSightings=0;listSeen();toMode(Store.SKILL);Runnable told=modeSwitched;if(told!=null)told.run();}
    }
    /** Switches the mode by itself, starting its reading afresh. */
    void toMode(String mode){
        Store.prefs(this).edit().putString("mode",mode).commit();
        frames=null;candidate="";stable=0;confirmations.clear();settle.forget();skillHits.clear();skillTop=Integer.MIN_VALUE;sightings=0;
        if(HOF_MODE.equals(mode)){Runnable told=hofSwitched;if(told!=null)told.run();}
    }
    /** 殿堂入りウマ娘の詳細, taken as three long images (see HallOfFame). */
    static final HallOfFame hof=new HallOfFame();
    static boolean hofActive(){return hof.active();}
    /** How many frames in a row the horse on the 殿堂入り screen read as nothing (worker only). */
    private int blankIdentity;
    /** Worker: the horse's name for the result window and the files, once; the images saved once all three are whole. */
    void hallOfFameAfter(Bitmap frame,int w,int h){
        // Another horse on screen: its epithet and name (right of the portrait) and its evaluation (under it).
        // Read as nothing before (the screen mid-change, or not this screen after all): tried again, every fifth frame.
        if(hof.needsIdentity()&&blankIdentity>0&&cycles%5!=0){recheck=true;return;}
        if(!hof.needsIdentity())blankIdentity=0;
        // The stats by umacapture's status_value model (StatVision), sure of all five or not taken. A new horse is
        // noticed on the first frame of its slide in, which is not yet the details screen: on 20 switches the
        // model had nothing to read 16 times there, though every frame after the slide (about 0.2s) read right.
        // So the next frames are waited for, and only after STAT_WAITS of them are the stats read as text.
        int[] byModel=null;
        if(hof.needsIdentity()){String epithet="",name="",evaluation="",stats="";
            // The horse and its outfit, from its portrait by umacapture's character model; read as text only
            // without the model (no downloaded modules) or when it is unsure.
            CharaVision.Outfit outfit=null;
            try{CharaVision cv=hofChara();if(cv!=null)outfit=cv.read(frame,w,h);}catch(Exception e){Log.d("FactorCapture","キャラ認識："+e.getClass().getSimpleName());}
            try{if(outfit!=null){name=outfit.name();epithet=outfit.epithet();}
                else{Bitmap crop=Bitmap.createBitmap(frame,(int)(.44*w),(int)(380/2340.0*h),(int)(.42*w),(int)(95/2340.0*h));
                String text=Tasks.await(headerReader.process(InputImage.fromBitmap(crop,0)),2,TimeUnit.SECONDS).getText();crop.recycle();
                for(String line:text.split("\n")){String l=line.trim();if(l.contains("[")||l.contains("［")||l.contains("]"))epithet=l;else if(l.length()>name.length())name=l;}}
                Bitmap score=Bitmap.createBitmap(frame,(int)(.09*w),(int)(600/2340.0*h),(int)(.22*w),(int)(55/2340.0*h));
                evaluation=Tasks.await(headerReader.process(InputImage.fromBitmap(score,0)),2,TimeUnit.SECONDS).getText();score.recycle();
                // The five stats, each number right of its rank badge: by umacapture's status_value model (StatVision)
                // when it is sure of all five, else read as text.
                StringBuilder all=new StringBuilder();
                if(byModel!=null)for(int k=0;k<5;k++)all.append(k>0?",":"").append(byModel[k]);
                else for(int k=0;k<5;k++){Bitmap c=Bitmap.createBitmap(frame,(int)((HallOfFame.STAT_NUMBER_LEFT+HallOfFame.STAT_NUMBER_STEP*k)*w),(int)(HallOfFame.STATS_TOP*h),(int)(HallOfFame.STAT_NUMBER_WIDTH*w),(int)((HallOfFame.STATS_BOTTOM-HallOfFame.STATS_TOP)*h));
                    Bitmap big=Bitmap.createScaledBitmap(c,c.getWidth()*2,c.getHeight()*2,true);c.recycle();
                    String t=Tasks.await(headerReader.process(InputImage.fromBitmap(big,0)),2,TimeUnit.SECONDS).getText().replaceAll("[^0-9]","");big.recycle();
                    if(t.length()>4)t=t.substring(t.length()-4);all.append(k>0?",":"").append(t);}
                stats=all.toString();
            }catch(Exception ignored){}
            // Read text varies a little from look to look (真打書之き, 真打·鶴之志 …): set to the outfit it names, so
            // the same horse always gets the same key.
            if(name.isEmpty()&&evaluation.isEmpty()){
                // Not a horse: nothing is kept under an empty key (a folder of nothing was, before).
                // Three times nothing: given up for this look (a still screen would be read over and over).
                if(++blankIdentity>=3){blankIdentity=0;hof.unread();return;}
                if(blankIdentity==1)Log.d("FactorCapture","殿堂入りの子を読めず（名前も評価点も空）：読み直します");recheck=true;return;}
            blankIdentity=0;
            hof.identify(epithet,name,evaluation,stats);recheck=true;}
        // Saved the moment both tabs are whole and every card on them is clean.
        if(hof.complete()&&!hof.saved()&&compose.ready(this,hof.sessionKey()))saveHallOfFame(true);
    }
    /**
     * Keeps the skill and 継承 images in the gallery, as they were made while taken (HofCompose): each under its tab's
     * top, taken while its list was scrolled. auto: said as done by itself.
     */
    void saveHallOfFame(boolean auto){
        // Saved already, or nothing taken of this horse: saving again would write its record over with no images,
        // factors or lists (the parts are cleared once saved), and that cannot be undone.
        if(hof.saved()){if(!auto)new Handler(Looper.getMainLooper()).post(()->Ui.toast(this,"この子は保存済みです"));return;}
        String key=hof.sessionKey();
        if(!compose.has(key,0)&&!compose.has(key,1)){new Handler(Looper.getMainLooper()).post(()->Ui.toast(this,"保存できる画像がありません"));return;}
        List<byte[]> images=new ArrayList<>();int n=0;List<String> uris=new ArrayList<>();
        for(int tab=0;tab<2;tab++){byte[] png=null;
            try{Bitmap head=hof.headBitmap(tab);if(head!=null)try{png=compose.finish(this,key,tab,head);}finally{head.recycle();}}
            catch(Exception e){Log.d("FactorCapture","殿堂入りの組み立てに失敗："+e.getClass().getSimpleName());}
            images.add(png);}
        List<HallOfFame.Factor> factors=compose.factors(key);
        // One folder a horse, its images in it, under its character and epithet (HallOfFame.folder).
        String folder=hof.folder(new java.text.SimpleDateFormat("yyyyMMdd_HHmm",Locale.JAPAN).format(new java.util.Date()));
        try{for(int i=0;i<images.size();i++){if(images.get(i)==null){uris.add("");continue;}
            uris.add(String.valueOf(Ui.saveImage(this,folder,(i+1)+"_"+HallOfFame.TABS[i]+".webp",images.get(i))));n++;}}
        catch(Exception e){Log.d("FactorCapture","殿堂入りの保存に失敗："+e.getClass().getSimpleName());}
        Log.d("FactorCapture","殿堂入りの因子 "+factors.size()+"件："+factors);
        // Made but not one put in the gallery (storage full…): not marked saved, so it can be saved again.
        boolean made=false;for(byte[] png:images)if(png!=null)made=true;
        if(made&&n==0){new Handler(Looper.getMainLooper()).post(()->Ui.toast(this,"ギャラリーへの保存に失敗しました（空き容量を確認して、もう一度保存してください）"));return;}
        hof.markSaved(images,uris,factors);
        // The lists, kept with the horse: its images can be made again from them (remakeHallOfFame).
        try{java.io.File dir=hof.dir();if(dir!=null)java.nio.file.Files.write(new java.io.File(dir,"layout.json").toPath(),compose.save().toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){Log.d("FactorCapture","殿堂入りの並びを残せず："+e.getClass().getSimpleName());}
        // Its name set right now that it is saved: its folder, images and lists move with it (HallOfFame.fixNames).
        HallOfFameActivity.saved();
        // Saved: the card book keeps, of each part, only the takes that made it clean.
        CardBook.prune(this,compose.parts(key));compose.clear();
        String said=n==0?"保存できる画像がありません":(auto?"そろったので、":"")+n+"枚をギャラリーの「Pictures/因子アシスト/"+folder+"」に保存しました";
        new Handler(Looper.getMainLooper()).post(()->Ui.toast(this,said));
    }
    /** What the images still lack, for the result window: the cards (and headings, icons) not taken clean yet. */
    static String hofLeft(){
        // Without waiting on the images being made (HofCompose.remainingByTabNow): this runs on the main thread.
        if(hof.saved())return "";int[] n=compose.remainingByTabNow(hof.sessionKey());if(n==null||n[0]+n[1]==0)return "";
        return "\nあと"+(n[0]+n[1])+"枚（スキル"+n[0]+"・継承"+n[1]+"）　枠が出ているところで止めてください";
    }
    private CharaVision charaVision;private boolean charaMissing;
    /** umacapture's character model, from the downloaded modules; null without them. */
    CharaVision hofChara(){
        if(charaVision==null&&!charaMissing)
            try{var set=Modules.readSet(this,"character.onnx","labels.json");charaVision=new CharaVision(set.get("character.onnx"),new String(set.get("labels.json"),StandardCharsets.UTF_8));}
            catch(Exception e){charaMissing=true;Log.d("FactorCapture","キャラ認識のモデルがありません："+e.getClass().getSimpleName());}
        return charaVision;
    }
    /** This cycle's list has looked the same for CardBook.SETTLE_MS (listSettled); when the last frame came. */
    private volatile boolean frameSettled;private volatile long frameArrivedMs;
    private int[] listLook;private long listSameSince;
    /**
     * Whether the list (that part of the frame) has looked the same, frame to frame, for CardBook.SETTLE_MS: nothing
     * on it moved, so no effect is over its cards. Only the list counts - the rest of the screen (the horse, the
     * background) moves all the time. Every other pixel each way, as brightness; a shade or two of difference is the same.
     */
    boolean listSettled(Bitmap frame,int left,int top,int right,int bottom){
        int w=right-left;if(w<=0||bottom<=top)return false;int[] row=new int[w];int[] look=new int[((w+1)/2)*((bottom-top+1)/2)];int n=0;
        for(int y=top;y<bottom;y+=2){frame.getPixels(row,0,w,left,y,w,1);for(int x=0;x<w;x+=2)look[n++]=lum(row[x]);}
        long now=SystemClock.elapsedRealtime();boolean same=listLook!=null&&listLook.length==look.length;
        for(int i=0;same&&i<look.length;i++)if(Math.abs(look[i]-listLook[i])>4)same=false;
        if(!same){listLook=look;listSameSince=now;return false;}
        return now-listSameSince>=CardBook.SETTLE_MS;
    }
    /** Something was taken off a frame not settled yet: it is to be read again once it has. */
    private volatile boolean settleWanted;
    /**
     * A horse's images made again (HallOfFameActivity): from its lists and its tabs' tops, kept when it was saved, and
     * the card book as it is now - put in place of the images in the gallery (and of the copies kept with the record).
     * What it says, for the screen.
     */
    static String remakeHallOfFame(Context c,java.io.File dir){
        try{
            java.io.File layout=new java.io.File(dir,"layout.json");if(!layout.exists())return "この子は並びが残っていないので、作り直せません（撮り直してください）";
            org.json.JSONObject meta=HallOfFame.readMeta(dir);if(meta==null)return "この子の記録が読めないので、作り直せません";
            String key=meta.optString("key");List<String> uris=HallOfFame.strings(meta.optJSONArray("uris"));
            HofCompose made=HofCompose.restore(key,new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(layout.toPath()),StandardCharsets.UTF_8)));
            int done=0,left=0;
            try{for(int tab=0;tab<HallOfFame.TAKEN;tab++){
                java.io.File headFile=HallOfFame.head(dir,tab);if(headFile==null)continue;
                Bitmap head=android.graphics.BitmapFactory.decodeFile(headFile.getPath());if(head==null)continue;
                byte[] png;try{png=made.finish(c,key,tab,head);}finally{head.recycle();}
                if(png==null)continue;
                // The gallery's image replaced by the new one, as WebP (one saved as PNG before goes; the new one takes its name and folder).
                if(tab<uris.size()&&!uris.get(tab).isEmpty()){uris.set(tab,String.valueOf(replaceInGallery(c,android.net.Uri.parse(uris.get(tab)),png)));meta.put("uris",new org.json.JSONArray(uris));}
                java.io.File old=HallOfFame.saved(dir,tab);if(old!=null&&old.getName().endsWith(".png"))old.delete();
                java.nio.file.Files.write(new java.io.File(dir,"saved"+tab+".webp").toPath(),png);
                done++;}
                left=Math.max(0,made.remaining(key));
                synchronized(HallOfFame.META_LOCK){org.json.JSONObject now=HallOfFame.readMeta(dir);if(now!=null){now.put("uris",meta.optJSONArray("uris"));HallOfFame.writeMeta(dir,now);}}
                // What the images are made of now, for カードを確認.
                java.nio.file.Files.write(layout.toPath(),made.save().toString().getBytes(StandardCharsets.UTF_8));}
            finally{made.clear();}
            if(done==0)return "作り直せる画像がありません（撮り直してください）";
            return done+"枚を作り直しました"+(left>0?"（綺麗なカードがまだ無いところが"+left+"か所あります）":"");
        }catch(Exception e){return "作り直せませんでした："+e.getClass().getSimpleName();}
    }
    /**
     * A gallery image put in place of uri: in its folder, under its name as .webp. A WebP one is written over; one
     * saved before (PNG) is deleted and a new one inserted. The image's uri now.
     */
    static android.net.Uri replaceInGallery(Context c,android.net.Uri uri,byte[] webp)throws Exception{
        var resolver=c.getContentResolver();String name=null,path=null,type=null;
        try(var q=resolver.query(uri,new String[]{android.provider.MediaStore.Images.Media.DISPLAY_NAME,android.provider.MediaStore.Images.Media.RELATIVE_PATH,android.provider.MediaStore.Images.Media.MIME_TYPE},null,null,null)){
            if(q!=null&&q.moveToFirst()){name=q.getString(0);path=q.getString(1);type=q.getString(2);}}
        if(name==null){return Ui.saveImage(c,"殿堂入り","作り直し_"+System.currentTimeMillis()+".webp",webp);}
        if("image/webp".equals(type)){try(var out=resolver.openOutputStream(uri,"wt")){if(out!=null)out.write(webp);}return uri;}
        String base=name.replaceAll("\\.[^.]+$","");String sub=path==null?"":path.replaceFirst("^"+android.os.Environment.DIRECTORY_PICTURES+"/因子アシスト/?","").replaceAll("/$","");
        resolver.delete(uri,null,null);
        return Ui.saveImage(c,sub,base+".webp",webp);
    }
    /** The 殿堂入り images as they are made (see HofCompose). */
    static final HofCompose compose=new HofCompose();
    /** This horse taken again from nothing. */
    /** On the worker, after the cycle in hand: one begun before would write its tab's progress into the new one. */
    static void retakeHallOfFame(){Runnable r=()->{hof.retake();compose.clear();};CaptureService s=instance;if(s!=null&&!s.closed)s.onWorker(r);else r.run();}
    /** How long the list must stand still before its cards are taken for the card book, and again while it stays (ms). */
    /**
     * Taken for the card book on every frame read while the list stands still - as fast as the reading is set - and
     * a part is clean once two takes agree: tap effects move all the time they are there, so two frames alike have
     * none over them (and when no new frame comes at all, nothing on the screen is moving).
     */
    static final long HOF_STILL_MS=1;
    /**
     * Worker only. The 殿堂入り screen's skill and 継承 lists, read as the other modes read theirs: every frame read
     * (at the rate set) on a tab begun (HallOfFame.place), its cards and other things read once (hofSeen) and that one
     * reading used for everything -
     * - the list and the images being made (HofCompose: laid over the list by the names, voted on, drawn once clean;
     *   how far down the list has come, the scroll bar's - HallOfFame.reached);
     * - the frames: black where a thing is not read yet, red where the card book has nothing of it, yellow while it
     *   has only takes that may have an effect over them, none once clean - placed for this frame and moved with
     *   the list between reads (hofFollow);
     * - the card book: on every frame on which the scroll bar stands where it stood on the frame before, what was read
     *   as what it was voted to be is taken (see HOF_STILL_MS).
     */
    void hofRead(Bitmap frame,int w,int h){
        HallOfFame.Place pl=hof.place();
        if(pl==null){frames=null;return;}
        compose.begin(hof.sessionKey());
        int x0=(int)(HallOfFame.LEFT*w),y0=(int)(HallOfFame.LIST_TOP*h),y1=(int)(HallOfFame.LIST_BOTTOM*h);Bitmap list=null;
        List<HofCompose.Look> looks;
        try{list=Bitmap.createBitmap(frame,x0,y0,(int)(HallOfFame.RIGHT*w)-x0,y1-y0);
            HofCompose.Result res=compose.onSeen(this,pl.tab(),list,hofSeen(list,pl.tab()));looks=res.looks();
            if(res.joined())hof.reached(pl);
            boolean left=false;for(HofCompose.Look l:looks)if(l.state()!=CardBook.CLEAN)left=true;
            frameSettled=listSettled(list,0,0,list.getWidth(),list.getHeight());
            if(left&&pl.stillMs()>=HOF_STILL_MS){
                List<CardBook.Shown> take=new ArrayList<>();for(HofCompose.Look l:looks)if(l.agrees()&&l.state()!=CardBook.CLEAN)take.add(l.seen().shown());
                // Each frame taken once; one not settled yet is read again once it has (no effect over it then).
                if(!take.isEmpty()){CardBook.collect(this,list,take,cycleFrameTime,frameSettled);if(!frameSettled)settleWanted=true;}}
            // Just stopped: the same frame read again, now as still, to be taken.
            if(left&&pl.stillMs()<HOF_STILL_MS)recheck=true;
        }catch(Exception e){Log.d("FactorCapture","殿堂入りの読み取り："+e.getClass().getSimpleName());frames=null;return;}
        finally{if(list!=null)list.recycle();}
        // Placed for this frame, then moved by how far the list has gone on since (hofFollow): drawn as they are,
        // they would jump back to where the list was.
        int since=hofMovedSince(cycleFrameTime);
        List<FactorFrames.Mark> marks=new ArrayList<>();
        for(HofCompose.Look l:looks){if(l.state()==CardBook.CLEAN)continue;
            var r=l.seen().card();int[] box={x0+r.left,y0+r.top-since,x0+r.right,y0+r.bottom-since};
            if(box[1]<y0||box[3]>y1)continue;
            int colour=l.state()==HofCompose.UNREAD?FactorFrames.PLAIN:l.state()==CardBook.NONE?0:2;
            marks.add(new FactorFrames.Mark((box[1]+box[3])/2,colour,true,null,box));}
        frames=marks.isEmpty()?null:new FactorFrames.Snapshot(w,h,marks,0,0);
    }
    /**
     * The skill tab's cards, for the card book: two columns of cards, each known by its text (name, and Lv for the
     * unique skill). Shares of the image's width from the S25 (1052 wide): cards 28-516 and 531-1018, 89 tall, the
     * name starting 77 in.
     */
    /** The factor and skill models for the 殿堂入り screen's cards, loaded as the factor and skill modes load them. */
    FactorVision hofFactorVision()throws Exception{
        if(vision==null){var set=Modules.readSet(this,"factor.onnx","factor_rank.onnx","labels.json");
            vision=new FactorVision(set.get("factor.onnx"),set.get("factor_rank.onnx"),new String(set.get("labels.json"),StandardCharsets.UTF_8));activeVision=vision;resetStages();}
        return vision;
    }
    SkillVision hofSkillVision()throws Exception{
        if(skillVision==null){var set=Modules.readSet(this,"skill.onnx","labels.json");
            skillVision=new SkillVision(set.get("skill.onnx"),new String(set.get("labels.json"),StandardCharsets.UTF_8),Store.prefs(this).getInt("inferenceThreads",0));}
        return skillVision;
    }
    /** The pixels of box in image, and the crop of them the models take (outWidth wide). */
    private record Cut(int[] pixels,int width,int height,byte[] crop) {}
    private static Cut cut(Bitmap image,int left,int top,int right,int bottom,int outWidth){
        if(left<0||top<0||right>image.getWidth()||bottom>image.getHeight()||right<=left||bottom<=top)return null;
        int bw=right-left,bh=bottom-top;int[] px=new int[bw*bh];image.getPixels(px,0,bw,left,top,bw,bh);
        byte[] crop=FactorVision.crop(px,bw,bh,0,0,bw,bh,outWidth,false);return crop==null?null:new Cut(px,bw,bh,crop);
    }
    /**
     * The skill tab's cards, found as the factor and skill modes find theirs: down a line through each column's cards
     * (x 500 and 1000 of 1052 on the S25), where a card is a run off the grey background (242) 89 tall; the columns'
     * cards are 26-518 and 529-1021 across. Each card's name is then where umacapture's skill_tab puts it, a fixed
     * place from the card's top (98-496 and 600-998 across, 30-60 down), and read by the skill model; a card the
     * model is unsure of is seen without a name, to be read on another screen. Kept of each in the card book: its
     * icon and name (a skill's icon is always the same) - a strip from just before the icon to short of the level -
     * and the blank card of its kind with that strip painted over; a horse's own unique skill (its rainbow card, with
     * its level) whole, for this horse only.
     */
    List<HofCompose.Seen> skillSeen(Bitmap image,int from)throws Exception{
        int w=image.getWidth(),h=image.getHeight();double u=w/1052.0;
        int[] left={(int)(26*u),(int)(529*u)},scan={(int)(500*u),(int)(1000*u)},nameLeft={(int)(98*u),(int)(600*u)};
        int cardW=(int)(492*u),nameW=(int)(398*u);
        List<int[]> cards=new ArrayList<>();List<Cut> cuts=new ArrayList<>();
        for(int col=0;col<2;col++)for(int[] run:cardRuns(image,scan[col],from,h,(int)(80*u),(int)(100*u))){
            if(run[0]+(int)(89*u)>h)continue;
            Cut c=cut(image,nameLeft[col],run[0]+(int)(30*u),nameLeft[col]+nameW,run[0]+(int)(60*u),SkillVision.NAME_WIDTH);
            if(c!=null){cards.add(new int[]{col,run[0]});cuts.add(c);}
        }
        List<HofCompose.Seen> out=new ArrayList<>();if(cuts.isEmpty())return out;
        SkillVision sv=hofSkillVision();List<byte[]> crops=new ArrayList<>();for(Cut c:cuts)crops.add(c.crop());
        var named=sv.names(crops);
        for(int i=0;i<cards.size();i++){
            int col=cards.get(i)[0],l=left[col],top=cards.get(i)[1];
            var box=new android.graphics.Rect(l,top,l+cardW,top+(int)(89*u));
            String name=null;CardBook.Shown shown=null;
            if(named.get(i).confidence()>=SkillVision.NAME_MIN){Cut c=cuts.get(i);
                name=sv.ringChecked(c.pixels(),c.width(),c.height(),new int[]{0,0,c.width(),c.height()},named.get(i).name());
                String kind=cardKind(image,l,top,u);
                if(kind.equals("rainbow"))shown=new CardBook.Shown(box,List.of(new CardBook.Card("sr|"+name+"|"+hof.sessionKey(),box)));
                else shown=skillShown(image,box,u,name,kind);
            }
            out.add(new HofCompose.Seen(col,box,name,0,shown));
        }
        return out;
    }
    /**
     * The factor names of chips (HallOfFame.chips), read by the factor model from where umacapture's factor_tab puts
     * a chip's name: 148 left to 186 right of its column's middle, 46 to 16 over its stars (S25, 1052 wide). Null
     * where the model is unsure.
     */
    List<String> chipNames(Bitmap image,List<int[]> chips)throws Exception{
        int w=image.getWidth();double u=w/1052.0;String[] out=new String[chips.size()];
        List<byte[]> crops=new ArrayList<>();List<Integer> at=new ArrayList<>();
        for(int i=0;i<chips.size();i++){int[] c=chips.get(i);int cx=(int)Math.round(HallOfFame.FACTOR_COLUMNS[c[0]]*w);
            Cut cut=cut(image,cx-(int)(148*u),c[1]-(int)(46*u),cx+(int)(186*u),c[1]-(int)(16*u),168);
            if(cut!=null){crops.add(cut.crop());at.add(i);}}
        if(!crops.isEmpty()){FactorVision fv=hofFactorVision();fv.runner.begin();
            try{var r=fv.runner.infer(FactorVision.NAME_MODEL,crops);
                for(int j=0;j<crops.size();j++)if(r.confidence()[j]>=FactorVision.NAME_MIN)out[at.get(j)]=fv.labels.getString((int)r.indices()[j]);}
            finally{fv.runner.end();}}
        return Arrays.asList(out);
    }
    /**
     * A skill card's kind, by its colour in the margin over its name (150 and 440 in, 12 down): white (most, and
     * inherited ones), pink, gold, or the rainbow of a horse's own unique skill. The same skill comes in more than one
     * (an inherited unique is white, or pink), so the kind is part of what a card is.
     */
    static String cardKind(Bitmap image,int left,int top,double u){
        int y=Math.min(image.getHeight()-1,top+(int)(12*u));
        int a=image.getPixel(left+(int)(150*u),y),b=image.getPixel(left+(int)(440*u),y);
        int ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255,br=(b>>16)&255,bb=b&255;
        if(ag>ar+30&&ab>ar+30&&br>bb)return "rainbow";      // light blue on the left, pink on the right
        if(ar>=245&&ab>ag&&ag<230)return "pink";
        if(ar>=245&&ag>ab+40)return "gold";
        return "white";
    }
    /** Runs down column x (from..to) off the grey background, min..max tall: {top, bottom}. */
    static List<int[]> cardRuns(Bitmap image,int x,int from,int to,int min,int max){
        List<int[]> out=new ArrayList<>();int start=-1;
        int[] col=new int[Math.max(0,to-from)];image.getPixels(col,0,1,x,from,1,to-from);
        for(int i=0;i<=col.length;i++){
            boolean on=i<col.length&&Math.abs(((col[i]>>16)&255)-242)+Math.abs(((col[i]>>8)&255)-242)+Math.abs((col[i]&255)-242)>=15;
            if(on&&start<0)start=i;
            if(!on&&start>=0){if(i-start>=min&&i-start<=max)out.add(new int[]{from+start,from+i});start=-1;}
        }
        return out;
    }
    /**
     * A factor chip as the card book keeps it, in two parts: its name - a strip of the chip from just before the name
     * to its end, the same whatever the stars - and its stars, which only differ by the chip's colour and how many
     * are gold, so a name is collected once and the stars few times. Chip: 406 wide about its column, from 63 over
     * its stars to 20 under (the stars' points and shadow reach 18); the name 50-14 over them, the three stars 56 either side (S25, 1052 wide).
     */
    static CardBook.Shown factorShown(Bitmap image,int[] chip,String name,String kind){
        int w=image.getWidth();double u=w/1052.0;int cx=(int)Math.round(HallOfFame.FACTOR_COLUMNS[chip[0]]*w),y=chip[1];
        var stars=new android.graphics.Rect(cx-(int)(56*u),y-(int)(14*u),cx+(int)(56*u),y+(int)(20*u));
        var chipBox=new android.graphics.Rect(cx-(int)(206*u),y-(int)(63*u),cx+(int)(206*u),y+(int)(20*u));
        int l=cx-(int)(150*u),t=chipBox.top+(int)(16*u),b=chipBox.top+(int)(50*u);
        int[] span=CardBook.letters(image,l,t,chipBox.right-(int)(6*u),b,chipBox.top+(int)(10*u));if(span==null)return null;
        var letters=new android.graphics.Rect(l,t,span[0]+(int)(6*u),b);int fillEnd=span[1]+(int)(8*u);
        var fill=new android.graphics.Rect(l,t,fillEnd,b);
        // Over the ball and the letters only: the stars hang under them.
        var blank=new CardBook.Blank((int)(10*u),-1);
        return new CardBook.Shown(chipBox,List.of(new CardBook.Card("fc|"+kind,chipBox,List.of(fill,stars),blank),new CardBook.Card("ft|"+name,letters),new CardBook.Card("fs|"+kind+"|"+chip[2],stars)));
    }
    /**
     * A skill card (not a rainbow one) as the card book keeps it: the common card of its kind - one card taken, its
     * icon and letters filled in with its own colour - its icon (8-65 across, 12-79 down on the S25; a skill's icon is
     * always the same), and its letters (from 79 across, 31-56 down), as wide as they are. Row 8, over the icon, has
     * nothing on it on a card of any colour. Null where no letters are found.
     */
    static CardBook.Shown skillShown(Bitmap image,android.graphics.Rect box,double u,String name,String kind){
        int l=box.left,top=box.top;
        int tl=l+(int)(74*u),tt=top+(int)(26*u),tb=top+(int)(62*u);
        int[] span=CardBook.letters(image,tl,tt,l+(int)(470*u),tb,top+(int)(8*u));if(span==null)return null;
        var icon=new android.graphics.Rect(l+(int)(5*u),top+(int)(8*u),l+(int)(70*u),top+(int)(82*u));
        var letters=new android.graphics.Rect(tl,tt,span[0]+(int)(6*u),tb);int fillEnd=span[1]+(int)(8*u);
        var fill=new android.graphics.Rect(tl-(int)(2*u),tt,fillEnd,tb);
        // Over the icon and the letters (under them is the card's edge): the card's colour runs the same down it.
        var blank=new CardBook.Blank((int)(8*u),-1);
        return new CardBook.Shown(box,List.of(new CardBook.Card("sc|"+kind,box,List.of(icon,fill),blank),new CardBook.Card("si|"+name,icon),new CardBook.Card("st|"+kind+"|"+name,letters)));
    }
    private static Map<String,String> chipKinds;private static FactorCatalog chipKindsOf;
    /**
     * A factor chip's colour, by what kind of factor it is (umacapture's factor_info, as the factor list has it):
     * blue the stats (青因子), pink the aptitudes (赤因子), green a unique skill's, white every other - not by the colour
     * on screen, which a frame caught mid effect can get wrong.
     */
    static synchronized String chipKind(Context context,String name){
        FactorCatalog catalog=Ui.catalog(context);
        if(chipKinds==null||chipKindsOf!=catalog){Map<String,String> kinds=new HashMap<>();chipKindsOf=catalog;
            for(FactorCatalog.Factor f:catalog.factors){String c=f.category();
                kinds.put(f.name(),c.equals("青因子")?"blue":c.equals("赤因子")?"pink":c.equals("固有スキル因子")?"green":"white");}
            chipKinds=kinds;}
        return chipKinds.getOrDefault(name,"white");
    }
    /** The cards wholly on the list as the screen shows it (tab 0 skills, 1 factors), named or not. */
    List<HofCompose.Seen> hofSeen(Bitmap list,int tab)throws Exception{
        if(tab==0)return skillSeen(list,0);
        int h=list.getHeight(),w=list.getWidth();double u=w/1052.0;
        List<int[]> chips=HallOfFame.chips(list,0);List<String> names=chipNames(list,chips);List<HofCompose.Seen> out=new ArrayList<>();
        for(int i=0;i<chips.size();i++){int[] c=chips.get(i);int cx=(int)Math.round(HallOfFame.FACTOR_COLUMNS[c[0]]*w);
            var box=new android.graphics.Rect(cx-(int)(206*u),c[1]-(int)(63*u),cx+(int)(206*u),c[1]+(int)(20*u));
            if(box.top<0||box.bottom>h)continue;
            out.add(new HofCompose.Seen(c[0],box,names.get(i),c[2],names.get(i)==null?null:factorShown(list,c,names.get(i),chipKind(this,names.get(i)))));}
        out.addAll(decorSeen(list,out));
        return out;
    }
    private static int lum(int p){return (((p>>16)&255)*3+((p>>8)&255)*6+(p&255))/10;}
    /**
     * The 継承 tab's things besides its chips, on the list as the screen shows it (S25, 1052 wide):
     * - the 因子 banner: green across the list (down x 120 and x 500 both), 30-48 tall; taken 36 tall from its top;
     * - a 継承元 heading: its rule, a faint grey line across 2-8 tall, found down x 300, 600 and 900 together, with the
     *   heading's dark letters at 84-161 beside it; the heading is taken 16 over the rule to 18 under it;
     * - a horse's icon: a run of colour left of the chips (x 50-170) at least 80 tall (gaps of up to 8 rows in it -
     *   the face's white - do not end it), taken by the chip row beside its top: 30 over it to 150 under, to x 190;
     *   each horse's icon is its own, so it is known by the horse and the chips beside its top.
     * The banner and the headings look the same on every horse, and are kept in the card book like the cards.
     */
    List<HofCompose.Seen> decorSeen(Bitmap list,List<HofCompose.Seen> chips){
        int w=list.getWidth(),h=list.getHeight();double u=w/1052.0;List<HofCompose.Seen> out=new ArrayList<>();
        // The banner is green across (33-1012): down x 120 (past its 因子, left of every chip) as well as x 500 - a
        // green chip (a unique skill's), with its name across it, makes runs as short at x 500 alone.
        int[] at500=new int[h],at30=new int[h];list.getPixels(at500,0,1,(int)(500*u),0,1,h);list.getPixels(at30,0,1,(int)(120*u),0,1,h);
        java.util.function.IntPredicate greenAt=p->((p>>8)&255)>((p>>16)&255)+40&&((p>>8)&255)>(p&255)+40;
        int bannerTop=-1,bannerEnd=-1;
        for(int y=0,start=-1;y<=h;y++){
            boolean green=y<h&&greenAt.test(at500[y])&&greenAt.test(at30[y]);
            if(green&&start<0)start=y;
            if(!green&&start>=0){int len=y-start;
                if(len>=30*u&&len<=48*u&&start>0&&y<h){bannerTop=start;bannerEnd=y;var box=new android.graphics.Rect(0,start,w,start+(int)(36*u));
                    out.add(new HofCompose.Seen(2,box,"因子",0,new CardBook.Shown(box,List.of(new CardBook.Card("hb|因子",box)))));}
                start=-1;}
        }
        int[][] cols=new int[3][h];int[] xs={(int)(300*u),(int)(600*u),(int)(900*u)};
        for(int k=0;k<3;k++)list.getPixels(cols[k],0,1,xs[k],0,1,h);
        java.util.function.IntPredicate faint=y->{for(int[] c:cols){int d=242-lum(c[y]);if(d<2||d>25)return false;}return true;};
        java.util.function.IntPredicate bg=y->{for(int[] c:cols)if(Math.abs(242-lum(c[y]))>=2)return false;return true;};
        for(int y=2;y<h-2;y++){
            if(!faint.test(y)||!bg.test(y-1))continue;
            int start=y;while(y<h&&faint.test(y))y++;int len=y-start;
            if(len<2||len>8||y>=h||!bg.test(y))continue;
            boolean letters=false;
            for(int yy=Math.max(0,start-(int)(12*u));yy<Math.min(h,start+(int)(14*u))&&!letters;yy++)
                for(int x=(int)(84*u);x<(int)(161*u);x+=2){int p=list.getPixel(x,yy);if(((p>>16)&255)+((p>>8)&255)+(p&255)<450){letters=true;break;}}
            if(!letters)continue;
            var box=new android.graphics.Rect(0,start-(int)(16*u),w,start+(int)(18*u));if(box.top<0||box.bottom>h)continue;
            out.add(new HofCompose.Seen(2,box,"継承元",0,new CardBook.Shown(box,List.of(new CardBook.Card("hd|継承元",box)))));
        }
        int x0=(int)(50*u),x1=(int)(170*u),gap=(int)(8*u);int[] row=new int[x1-x0];
        for(int y=0,start=-1,last=-1;y<=h;y++){
            boolean colour=false;
            if(y<h&&!(y>=bannerTop&&y<bannerEnd)){list.getPixels(row,0,row.length,x0,y,row.length,1);
                for(int i=0;i<row.length;i+=3){int p=row[i],mx=Math.max((p>>16)&255,Math.max((p>>8)&255,p&255)),mn=Math.min((p>>16)&255,Math.min((p>>8)&255,p&255));if(mx-mn>60){colour=true;break;}}}
            if(colour){if(start<0)start=y;last=y;continue;}
            if(start>=0&&(y-last>gap||y==h)){int end=last+1;
                if(end-start>=80*u&&start>10*u&&end<h-10*u){
                    // Placed by the chip row beside its top, always the same way (its colour's run is a few rows
                    // longer or shorter frame to frame): from 30 over that row to 150 under.
                    List<HofCompose.Seen> beside=new ArrayList<>();
                    for(HofCompose.Seen c:chips)if(c.col()<2&&c.card().top>=start-40*u&&c.card().top<=start+70*u)beside.add(c);
                    if(beside.isEmpty()){start=-1;continue;}
                    int first=beside.get(0).card().top;for(HofCompose.Seen c:beside)first=Math.min(first,c.card().top);
                    final int rowTop=first;beside.removeIf(c->c.card().top>rowTop+20*u);
                    var box=new android.graphics.Rect(0,rowTop-(int)(30*u),(int)(190*u),rowTop+(int)(150*u));
                    if(box.top<0||box.bottom>h){start=-1;continue;}
                    beside.sort(Comparator.comparingInt(HofCompose.Seen::col));
                    StringBuilder key=new StringBuilder("hi|").append(hof.sessionKey());boolean named=!beside.isEmpty();
                    for(HofCompose.Seen c:beside){if(c.name()==null)named=false;key.append('|').append(c.name()).append(c.stars());}
                    out.add(new HofCompose.Seen(2,box,"icon",0,named?new CardBook.Shown(box,List.of(new CardBook.Card(key.toString(),box))):null));
                }
                start=-1;}
        }
        return out;
    }
    // ---- 殿堂入り: the frames moved at the rate the screen changes (capture thread), as followColumn does elsewhere

    private int[] hofProfile;private int hofThumb=-1,hofTotal;
    private final long[] hofTimes=new long[128];private final int[] hofTotals=new int[128];private int hofCount;
    /** A row's brightness, summed over 16 points across the list: what the list's moves are found by, frame to frame. */
    static final int HOF_POINTS=16;
    /** Off by more than this (summed brightness, per row): the list moved further than could be followed. */
    static final int HOF_LOST=HOF_POINTS*10*14;
    /**
     * Capture thread only. How far the list moved since the last frame: its rows' brightness (one number a row)
     * matched against the last frame's, near the move the scroll bar's thumb gives (the bar alone is off by a card's
     * part; the rows are what is exact, and near it they cannot be taken for the card above or below). The frames
     * drawn move by it; a move that matches nowhere takes them off until the worker places them again.
     */
    void hofFollow(Image image){
        try{
            int w=image.getWidth(),h=image.getHeight();
            var plane=image.getPlanes()[0];var buffer=plane.getBuffer();int rs=plane.getRowStride(),ps=plane.getPixelStride();
            int y0=(int)(HallOfFame.LIST_TOP*h),y1=(int)(HallOfFame.LIST_BOTTOM*h),rows=y1-y0;
            int xl=(int)(HallOfFame.LEFT*w)+40,xr=(int)(HallOfFame.RIGHT*w)-60;
            int[] p=new int[rows];
            for(int r=0;r<rows;r++){int sum=0,base=(y0+r)*rs;
                for(int k=0;k<HOF_POINTS;k++){int i=base+(xl+(xr-xl)*k/(HOF_POINTS-1))*ps;sum+=(buffer.get(i)&255)*3+(buffer.get(i+1)&255)*6+(buffer.get(i+2)&255);}
                p[r]=sum;}
            int bx=(int)(HallOfFame.BAR_X*w),tt=(int)(HallOfFame.TRACK_TOP*h),tb=(int)(HallOfFame.TRACK_BOTTOM*h),top=-1,end=-1;
            for(int y=tt-4;y<tb+4;y++){int i=y*rs+bx*ps,r=buffer.get(i)&255,g=buffer.get(i+1)&255,b=buffer.get(i+2)&255;
                if(Math.abs(r-137)<16&&Math.abs(g-135)<16&&Math.abs(b-153)<16){if(top<0)top=y;end=y+1;}}
            int thumb=top>=0&&end-top>=10?top:-1,length=end-top;
            int[] prev=hofProfile;int prevThumb=hofThumb;hofProfile=p;hofThumb=thumb;
            int shift=0;
            if(prev!=null&&prev.length==rows){
                int est=0,track=tb-tt;
                if(thumb>=0&&prevThumb>=0&&track>length){int content=(int)((long)rows*track/length);est=(int)Math.round((thumb-prevThumb)*(double)(content-rows)/(track-length));}
                long best=Long.MAX_VALUE;int at=est;
                for(int s=est-30;s<=est+30;s++){if(Math.abs(s)>rows-200)continue;
                    int r0=Math.max(0,-s),r1=Math.min(rows,rows-s);long sum=0;int n=0;
                    for(int r=r0;r<r1;r+=2){sum+=Math.abs(p[r]-prev[r+s]);n++;}
                    long mean=n==0?Long.MAX_VALUE:sum/n;if(mean<best){best=mean;at=s;}}
                if(best>HOF_LOST){FactorFrames.Snapshot last=frames;if(last!=null)frames=null;}
                else shift=at;
            }
            synchronized(hofTimes){hofTotal+=shift;int k=hofCount++%hofTimes.length;hofTimes[k]=image.getTimestamp();hofTotals[k]=hofTotal;}
            FactorFrames.Snapshot last=frames;
            if(last!=null&&shift!=0){
                List<FactorFrames.Mark> moved=new ArrayList<>();
                for(var m:last.marks()){int[] b=m.box();if(b==null)continue;int[] nb={b[0],b[1]-shift,b[2],b[3]-shift};
                    if(nb[1]<y0||nb[3]>y1)continue;
                    moved.add(new FactorFrames.Mark(m.y()-shift,m.priority(),m.met(),m.label(),nb,m.top(),m.tag(),m.rank(),m.price(),m.side()));}
                frames=moved.isEmpty()?null:new FactorFrames.Snapshot(last.frameWidth(),last.frameHeight(),moved,last.rowHalf(),last.labelX());
            }
        }catch(Exception ignored){}
    }
    /** How far the list has moved (hofFollow) since the frame captured at time: 0 when that frame is not on record. */
    int hofMovedSince(long time){
        synchronized(hofTimes){
            int n=Math.min(hofCount,hofTimes.length);
            for(int i=0;i<n;i++){int k=(hofCount-1-i)%hofTimes.length;if(hofTimes[k]<=time)return hofTotal-hofTotals[k];}
            return 0;
        }
    }
    /** Every factor name, normalised to itself, from the model's labels (read even when the factor model is not loaded). */
    Map<String,String> allFactorNames(){
        Map<String,String> names=factorNames();if(names!=null&&!names.isEmpty())return names;
        Map<String,String> out=new HashMap<>();
        try{var labels=new org.json.JSONObject(new String(Modules.read(this,"labels.json"),StandardCharsets.UTF_8)).getJSONArray("factor.name");
            for(int i=0;i<labels.length();i++){String n=labels.optString(i);if(!n.isEmpty())out.put(Store.normalize(n),n);}}catch(Exception ignored){}
        return out;
    }
    /** The factor a line read as text names: the same once normalised, else the nearest by edits (a fifth of its length at most). */
    static String closestName(String raw,Map<String,String> names){
        String t=Store.normalize(raw.replaceAll("^[○◯●〇・\\s]+","").trim());if(t.isEmpty())return null;
        String exact=names.get(t);if(exact!=null)return exact;
        String best=null;int bestD=Integer.MAX_VALUE;
        for(var e:names.entrySet()){String k=e.getKey();if(Math.abs(k.length()-t.length())>2)continue;int d=edits(k,t);if(d<bestD){bestD=d;best=e.getValue();}}
        return bestD<=Math.max(1,t.length()/5)?best:null;
    }
    static int edits(String a,String b){
        int[] prev=new int[b.length()+1],cur=new int[b.length()+1];for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){cur[0]=i;for(int j=1;j<=b.length();j++)cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));int[] t=prev;prev=cur;cur=t;}
        return prev[b.length()];
    }
    static void saveHallOfFameNow(){CaptureService s=instance;if(s!=null)s.onWorker(()->s.saveHallOfFame(false));}
    /** Every factor name the model can output, normalised to itself: the dictionary text readings are matched against. */
    private Map<String,String> factorNames;private FactorVision namesOf;
    Map<String,String> factorNames(){
        if(vision!=null&&(factorNames==null||namesOf!=vision)){
            Map<String,String> names=new HashMap<>();
            for(int i=0;i<vision.labels.length();i++){String name=vision.labels.optString(i);if(!name.isEmpty())names.put(Store.normalize(name),name);}
            factorNames=names;namesOf=vision;
        }
        return factorNames;
    }
    /** Below this the model's factor name is checked against a text reading of the same crop. */
    static final float FACTOR_CERTAIN=.995f;
    /** Text readings of factor-name crops, by crop signature: a name, or "" when the text matched no factor. */
    private final Map<Long,String> factorText=new LinkedHashMap<>(){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,String> eldest){return size()>256;}
    };
    private record RecentName(String name,long at){}
    static final long RECENT_MS=3000;
    /** Text readings by roughKey, reused for RECENT_MS. */
    private final Map<Long,RecentName> recentNames=new LinkedHashMap<>(){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,RecentName> eldest){return size()>64;}
    };
    /**
     * The model's name, the row's place, the crop's average colour and the rough shape of its text, all coarse
     * enough that sparkles do not change them. The shape is what tells a look-alike read under the same name
     * apart: how many pixels stand out from the card's colour in each of 16 columns (dark text on grey cards and
     * white text on coloured ones alike), counted in steps of 8.
     */
    private long roughKey(Bitmap frame,int[] box,FactorVision.Reading row){
        if(!ptFits(frame,box))return 0;
        long r=0,g=0,b=0,n=0;
        for(int y=box[1];y<box[3];y+=4)for(int x=box[0];x<box[2];x+=4){int c=pixelAt(frame,x,y,frameW);r+=(c>>16)&255;g+=(c>>8)&255;b+=c&255;n++;}
        if(n==0)return 0;
        long colour=((r/n)>>4)<<8|((g/n)>>4)<<4|((b/n)>>4);
        int mean=(int)((r*3+g*6+b)/n/10);int[] bands=new int[16];int width=box[2]-box[0];
        for(int y=box[1];y<box[3];y+=2)for(int x=box[0];x<box[2];x+=2){int c=pixelAt(frame,x,y,frameW);
            int luma=(((c>>16)&255)*3+((c>>8)&255)*6+(c&255))/10;if(Math.abs(luma-mean)>60)bands[(x-box[0])*16/width]++;}
        long shape=17;for(int band:bands)shape=shape*31+(band>>3);
        return (((row.name().hashCode()*31L+row.y()/6)*4099+colour)*1_000_003L+shape)|1;
    }
    /**
     * The settled rows the model was not certain of, read as text as well (all requests sent first, then collected)
     * and matched against every factor name (see NameMatch). A match replaces the model's name - the case this is
     * for is a name read as its look-alike with high confidence (存在感 as 桜花賞 at .98). No match leaves the
     * model's reading alone. A failed OCR is not remembered, so the unchanged crop is read again next time.
     */
    List<FactorVision.Reading> checkFactorNames(Bitmap frame,int w,int h,FactorVision.Layout layout,List<FactorVision.Reading> readings){
        long started=SystemClock.elapsedRealtimeNanos(),now=SystemClock.elapsedRealtime();
        List<int[]> boxes=new ArrayList<>();long[] rough=new long[readings.size()];
        String[] read=new String[readings.size()];
        for(int i=0;i<readings.size();i++){
            var row=readings.get(i);
            if(row.nameConfidence()>=FACTOR_CERTAIN){boxes.add(null);continue;}
            int[] box=FactorVision.nameBox(layout,row.y());
            // The page's sparkles change a few pixels every frame, so the exact signature below misses on a still
            // screen and the same rows went to OCR every cycle (about 35ms each). The same model answer at the same
            // place on the same card colour is taken as the same row for a while.
            rough[i]=roughKey(frame,box,row);
            RecentName recent=ptFits(frame,box)?recentNames.get(rough[i]):null;
            if(recent!=null&&now-recent.at()<RECENT_MS){read[i]=recent.name().isEmpty()?null:recent.name();boxes.add(null);continue;}
            boxes.add(box);
        }
        // By every pixel: some factor names are white on coloured cards, which an ink signature would not see.
        String[] byText=namesByText(frame,boxes,factorNames(),factorText,false);
        for(int i=0;i<byText.length;i++){
            if(boxes.get(i)==null)continue;
            read[i]=byText[i];
            if(rough[i]!=0)recentNames.put(rough[i],new RecentName(byText[i]==null?"":byText[i],now));
        }
        stage(5,SystemClock.elapsedRealtimeNanos()-started);
        List<FactorVision.Reading> out=new ArrayList<>(readings.size());
        for(int i=0;i<readings.size();i++){
            var row=readings.get(i);
            if(read[i]==null){out.add(row);continue;}
            if(!read[i].equals(row.name()))Log.d("FactorCheck",String.format(Locale.JAPAN,"%s(%.3f) → 文字では %s",row.name(),row.nameConfidence(),read[i]));
            out.add(new FactorVision.Reading(read[i],row.stars(),1f,row.starConfidence(),row.y()));
        }
        return out;
    }
    /** Worker only: the skill screen. Same two-frames-in-a-row confirmation as the factor list. */
    private SkillVision skillVision;
    private final Map<String,Integer> skillHits=new HashMap<>();
    /** The top row's position last frame, to tell a still list from a scrolling one. */
    private int skillTop=Integer.MIN_VALUE;

    /** Where each skill sits in the list. Static and cleared with the skill results, like the factors' places:
     *  kept across a reset, the last run's places would put a new skill on top of a known one and drop it. */
    static final PagePlaces skillPlaces=new PagePlaces();
    void readSkills(Bitmap frame,int w,int h,long generation,int threadSetting) throws Exception {
        // The thread count is found as for the factor models, unless one is fixed in the settings.
        if(skillVision==null){var set=Modules.readSet(this,"skill.onnx","labels.json");
            skillVision=new SkillVision(set.get("skill.onnx"),new String(set.get("labels.json"),StandardCharsets.UTF_8),threadSetting);}
        skillVision.runner.tuner.setThreads(threadSetting);
        activeRunner=skillVision.runner;activeModels="スキル推論";
        long t0=SystemClock.elapsedRealtimeNanos();
        // Only the list is copied out: the points owned above it are read from the frame itself, and only for a plan.
        int listFrom=(int)(h*SkillVision.COPY_TOP),listTo=(int)(h*SkillVision.COPY_BOTTOM);
        startCopy(w,h);copyRows(frame,w,listFrom,listTo);
        long t1=SystemClock.elapsedRealtimeNanos();stage(1,t1-t0);
        SkillVision.Layout layout=SkillVision.fromCards(pixels,w,h,listFrom,listTo);boolean cards=layout!=null;
        if(cards)listSeen();
        // No cards there (another device, another layout): the whole frame, and the older way - see olderWay.
        if(layout==null&&olderWay(skillCardsSeen)){copyRows(frame,w,0,h);layout=SkillVision.detect(pixels,w,h);}
        if(cards)skillCardsSeen=true;
        if(autoSwitch(frame,w,h,true,cards))return;
        long t2=SystemClock.elapsedRealtimeNanos();stage(2,t2-t1);
        if(layout==null){
            // Losing the layout for a frame wipes every frame on screen, so it is worth knowing when it happens.
            if(cycles%10==0){
                StringBuilder found=new StringBuilder();
                for(var icon:SkillVision.icons(pixels,w,h))
                    found.append(found.length()>0?" / ":"").append("cy").append(icon.cy()).append(" cx").append(icon.cx()).append(" 右").append(icon.right());
                Log.d("FactorSkill","行を検出できず： "+(found.length()>0?found:"アイコン無し"));
            }
            frames=null;skillHits.clear();visibleSkills=List.of();message="スキル画面を探しています";return;
        }
        showContent();
        int top=layout.ys().get(0);
        boolean moving=skillTop!=Integer.MIN_VALUE&&Math.abs(top-skillTop)>2;
        skillTop=top;
        // The frames move onto the rows now, before the model runs, so they keep up with a scrolling list.
        int rowHalf=(int)Math.rint(layout.pitch()*.39),labelX=(int)Math.rint(DrawPlaces.current.nameX*w);
        List<int[]> boxes=new ArrayList<>();for(int y:layout.ys())boxes.add(layout.frameOf(y));
        FactorFrames.Snapshot early=FactorFrames.follow(frames,layout.ys(),boxes,layout.pitch(),w,h,rowHalf,labelX);
        if(early!=null)publish(early);
        List<SkillVision.Reading> readings=skillVision.read(pixels,w,h,layout,!moving);
        stage(3,SystemClock.elapsedRealtimeNanos()-t2);
        // Names on this screen come in families - 逃げ/先行/差し けん制, スリップストリーム against テンアゲスタイル -
        // and squeezed into the model's 200x16 input they can look alike enough to be read wrongly with high
        // confidence. Anything short of certain is checked with the text recogniser, which sees the crop at its
        // own resolution; its reading is only taken when it matches a skill the model knows.
        // Every OCR this cycle needs is started before any is waited for - the prices, the points owned, then the
        // names to check - so the recogniser works through them back to back instead of one round trip at a time.
        long ocrStarted=SystemClock.elapsedRealtimeNanos();ocrCalls=0;
        if(!moving){
            readings=checkNames(frame,layout,readings);
        }
        // What the frames show (動作モード): the plan and rates from the efficiency data, the preset's levels, both, or neither.
        int frameMode=skillFrameMode(this);boolean useTools=frameMode==TOOLS_AND_PRESET||frameMode==TOOLS_ONLY||frameMode==EXAM;
        Set<String> plan=useTools?planned:Set.of();
        Map<String,List<Store.Rule>> levels=frameMode==TOOLS_AND_PRESET||frameMode==PRESET_ONLY?presetRules():Map.of();
        boolean detailed=presetDetailed;
        Map<String,Integer> ranks=plan.isEmpty()?Map.of():planRanks;
        List<String> seen=new ArrayList<>();List<PagePlaces.Row> placed=new ArrayList<>();List<FactorFrames.Mark> marks=new ArrayList<>();
        Map<String,Skill> accepted=new LinkedHashMap<>();
        Map<String,Double> gains=skillGains();
        // Trusting a name because it was read confidently a moment ago was tried and removed: the names on
        // this screen come in families (逃げ/先行/差し/追込 けん制), so a name proven on one row would be accepted
        // on another row it does not belong to. Only this row's own reading counts.
        // How far the list moved since the last frame, from the rows read surely on both: where the same name sits now.
        int shift=listShift(readings);
        List<Integer> nowYs=new ArrayList<>();List<String> nowNames=new ArrayList<>();
        for(var row:readings){
            // A frame says the row was found, whatever its name came out as: a row the model was unsure of this
            // frame (中距離コーナー○ swinging between ○ and ◎ while scrolling) kept losing its frame, which looked
            // like the row itself being lost. It gets the plain frame and no line under it, and is not taken.
            if(row.confidence()<SkillVision.NAME_MIN){
                // Its rate can still be shown, from what this row was on the last frame (see carried): display only.
                String was=carried(row.y(),shift,layout.pitch());
                String line=null;
                if(was!=null){
                    nowYs.add(row.y());nowNames.add(was);
                    if(useTools)synchronized(CaptureService.class){Skill known=skills.get(was);if(known!=null)line=labelFor(was,known.pt(),gains);}
                    else line=was;
                }
                marks.add(skillMark(frameMode,row.y(),was,was!=null&&plan.contains(was),!plan.isEmpty(),levels,detailed,ranks,line,layout.frameOf(row.y())));
                continue;
            }
            seen.add(row.name());placed.add(new PagePlaces.Row(row.name(),row.y()));
            int[] box=SkillVision.ptBox(layout.iconRight(),layout.pitch(),row.y());
            boolean owned=SkillVision.acquired(pixels,w,h,box);
            int pt=owned?0:-1;
            // While the list scrolls only the digit templates read prices; one they are unsure of keeps the price
            // read before, so the rate under it stays up instead of vanishing until the list stops.
            int shownPt=pt;
            if(pt<0)synchronized(CaptureService.class){Skill known=skills.get(row.name());if(known!=null&&known.pt()>0)shownPt=known.pt();}
            // Before a plan exists, a green frame on every row that was read says how far the reading got.
            // Once there is a plan, only the rows to buy are framed, in red: anything else on screen would
            // just be noise while working down the list.
            // The frame goes by the name the row shows now, bought or not: after ○ is bought the row shows ◎ and
            // takes ◎'s colour, and a skill bought keeps its colour instead of flickering as + is pressed and undone.
            boolean wanted=plan.contains(row.name());
            // A name the model is unsure of flips between the skills of its family from frame to frame while
            // the list moves. Writing that under the row just looks broken, so it waits for the check.
            boolean settled=!moving||row.confidence()>=NAME_CERTAIN;
            // Unsettled, the line goes by what this row was on the last frame, if that is known (see carried).
            String lineName=settled?row.name():carried(row.y(),shift,layout.pitch());
            if(lineName!=null){nowYs.add(row.y());nowNames.add(lineName);}
            int linePt=shownPt;
            if(lineName!=null&&!lineName.equals(row.name()))synchronized(CaptureService.class){Skill known=skills.get(lineName);linePt=known!=null?known.pt():-1;}
            String line=lineName==null?null:useTools?labelFor(lineName,linePt,gains):lineName;
            // The price read for this row, for the plan: shown by the price box, or on the line when shown as 「名前の下に線」
            // (see FactorFrames.priceBox). A skill already taken needs none.
            int price=!useTools||owned?FactorFrames.PRICE_NONE:shownPt>0?shownPt:FactorFrames.PRICE_UNKNOWN;
            marks.add(skillMark(frameMode,row.y(),lineName,wanted,!plan.isEmpty(),levels,detailed,ranks,line,layout.frameOf(row.y())).withPrice(price));
            int hits=skillHits.getOrDefault(row.name(),0)+1;skillHits.put(row.name(),Math.min(2,hits));
            // While the list moves the text recogniser is not run, so a name the model was merely fairly sure of
            // would go into the list unchecked - and a wrong one sticks, with the real skill missing. Only names
            // the model is certain of are taken while moving; the rest wait for the list to settle and be checked.
            if(hits>=2&&(!moving||row.confidence()>=NAME_CERTAIN))accepted.put(row.name(),new Skill(row.name(),pt,owned,row.y()));
        }
        // Which rows were read and which fell short: the split between "the row was not found at all" and
        // "the name was not confident enough" is what says whether the crop or the threshold needs adjusting.
        if(cycles%10==0){
            StringBuilder low=new StringBuilder();
            for(var row:readings)
                low.append(low.length()>0?" / ":"").append("y").append(row.y()).append(' ').append(row.name())
                   .append(String.format(Locale.JAPAN," %.2f",row.confidence()));
            Log.d("FactorSkill","枠 "+(layout.frames()!=null?"あり":"なし")+" / 行 "+layout.ys().size()+" / 読めた "+seen.size()+" / ピッチ "+layout.pitch()+" / アイコン右端 "+layout.iconRight()
                  +String.format(Locale.JAPAN," / OCR %d回 %.0fms",ocrCalls,(SystemClock.elapsedRealtimeNanos()-ocrStarted)/1e6)
                  +(low.length()>0?" / 各行： "+low:""));
        }
        lastRowYs=nowYs;lastRowNames=nowNames;
        skillHits.keySet().retainAll(seen);
        recheck=accepted.size()<seen.size();
        publish(new FactorFrames.Snapshot(w,h,marks,(int)Math.rint(layout.pitch()*.39),labelX));
        visibleSkills=List.copyOf(seen);
        boolean measured=skillPlaces.record(Store.SKILL,placed);
        // Every cycle that needed the recogniser: how many calls, and how long from the first request to the last answer.
        if(ocrCalls>0)Log.d("FactorOcr",String.format(Locale.JAPAN,"OCR %d回 %.0fms（1回あたり %.0fms）",ocrCalls,
            (SystemClock.elapsedRealtimeNanos()-ocrStarted)/1e6,(SystemClock.elapsedRealtimeNanos()-ocrStarted)/1e6/ocrCalls));
        if(!closed&&!paused&&!accepted.isEmpty())synchronized(CaptureService.class){
            if(generation!=resultGeneration)return;
            var places=skillPlaces.of(Store.SKILL);
            for(var skill:accepted.values()){
                Skill before=skills.get(skill.name());
                int pt=skill.pt()>0||skill.acquired()?skill.pt():before!=null?before.pt():-1;
                int place=places.getOrDefault(skill.name(),skill.place());
                // A name read wrongly earlier sits at this very place; the row can only hold one skill, so
                // whatever else claims this position makes way for what is being read now.
                // Only when this frame's places were measured: placed by a guess (no row in common with what was
                // seen), a row could land on a skill read right elsewhere and take it away.
                int near=(int)Math.rint(layout.pitch()*.4);
                if(measured)skills.entrySet().removeIf(other->!other.getKey().equals(skill.name())&&Math.abs(other.getValue().place()-place)<near);
                skills.put(skill.name(),new Skill(skill.name(),pt,skill.acquired(),place));
                // The row shows ◎ once its ○ is bought: a ○ still listed from before was bought.
                String n=skill.name().trim();if(n.endsWith("◎"))skills.remove(n.substring(0,n.length()-1)+"○");
            }
        }
        if(!closed&&!paused)message="スキル "+skills.size()+"件"+(ownedPt>=0?"／所持 "+ownedPt+"pt":"");
    }
    /**
     * The rows of the last skill frame whose name was known - read surely, or carried over - by where they were.
     * Worker only.
     */
    private List<Integer> lastRowYs=new ArrayList<>();private List<String> lastRowNames=new ArrayList<>();
    /**
     * How far the list moved since the last frame: the median change in y of the rows read surely on both frames,
     * matched by name; Integer.MIN_VALUE when no row gives it.
     */
    int listShift(List<SkillVision.Reading> readings){
        List<Integer> moves=new ArrayList<>();
        for(var row:readings){
            if(row.confidence()<NAME_CERTAIN)continue;
            int i=lastRowNames.indexOf(row.name());
            if(i>=0)moves.add(row.y()-lastRowYs.get(i));
        }
        return moves.isEmpty()?Integer.MIN_VALUE:FactorVision.median(moves);
    }
    /**
     * What the row at y was on the last frame, the list having moved by shift since: the name of the row that sat
     * a shift above it, within a quarter row; null when unknown. For the line under an unsure row only - such a
     * name is never taken into the list.
     */
    String carried(int y,int shift,int pitch){
        if(shift==Integer.MIN_VALUE)return null;
        for(int i=0;i<lastRowYs.size();i++)if(Math.abs(y-shift-lastRowYs.get(i))<pitch/4)return lastRowNames.get(i);
        return null;
    }
    /**
     * What to write under a skill's name: what it pays out for the points it costs, or null when that is not known
     * (no gain for it, or no price yet). The name itself is no longer written: it is read reliably now, and the
     * rate is what the line is for. Per 100 points rather than per point, because 0.0092 says nothing at a glance
     * and 0.92 does.
     */
    static String label(String name,int pt,Map<String,Double> gains) {
        Double gain=gains.get(Store.normalize(name));
        boolean known=gain!=null&&gain>0&&pt>0;
        if(showNames)return known?String.format(Locale.JAPAN,"%s  %.2f/100pt",name,gain/pt*100):name;
        return name;
    }
    /**
     * The line under a skill's name, as the plan sees buying it from what is owned now: the family members below it
     * that are not owned yet come along, so they add to what it costs (a gold skill's shown price already has them
     * in; a ◎'s is only its step), and what it gains is its value less that of the owned one it replaces, plus the
     * bonus of each one bought (評価試験). 恩返し 200 / 100pt, 極上 600 / 300pt; with 恩返し owned, 極上 400 / 200pt.
     */
    String labelFor(String name,int pt,Map<String,Double> gains){
        return label(name,pt,gains);
    }
    /** Settable: the name as read goes in front of the rate, to check ○ against ◎ on the screen. */
    static volatile boolean showNames;
    /** Skill name → the gain the efficiency data gives it, for the style the selected event is set to; none without it. */
    Map<String,Double> skillGains() {
        var prefs=Store.prefs(this);
        showNames=prefs.getBoolean("showSkillNames",false);
        return Map.of();
    }

    /**
     * 動作モード of the skill screen: what its frames and the line under each name come from. TOOLS_ONLY is how it
     * always was, and the default.
     */
    static final int TOOLS_AND_PRESET=0,TOOLS_ONLY=1,PRESET_ONLY=2,NEITHER=3,EXAM=4;
    // The first two use the efficiency data, which is not in every build: there only the last two are offered.
    static final String[] FRAME_MODES={null,null,"プリセットのみ","両方なし",null};
    static final String[] FRAME_MODE_NOTES={null,null,"因子と同じ。プリセットの色で枠","読めた行に緑の枠だけ"};
    static int skillFrameMode(Context c){return Store.prefs(c).getInt("skillFrameMode",PRESET_ONLY)==NEITHER?NEITHER:PRESET_ONLY;}
    static boolean usesTools(Context c){int m=skillFrameMode(c);return m==TOOLS_AND_PRESET||m==TOOLS_ONLY;}
    /** Whether the skill plan (the numbers and the colours it gives) is used in this 動作モード. */
    static boolean usesPlan(Context c){return usesTools(c)||skillFrameMode(c)==EXAM;}
    static boolean usesSkillSet(Context c){int m=skillFrameMode(c);return m==TOOLS_AND_PRESET||m==PRESET_ONLY;}
    /**
     * A skill row's frame. Its own colour is green for a row read before any plan, red for one to buy, and nothing
     * for one not to buy. With the preset too, the preset's level is the frame's top and its own colour the bottom;
     * with the preset only, the whole frame is the level, as on the factor screen. The preset is the factor preset:
     * a white factor is the skill of the same name, so what is registered to be taken in 本育成 is framed on the
     * skill screen as it is. name is null when the row's skill is not known yet.
     */
    static FactorFrames.Mark skillMark(int frameMode,int y,String name,boolean wanted,boolean planning,Map<String,List<Store.Rule>> levels,
                                       boolean detailed,Map<String,Integer> ranks,String line,int[] box){
        // The efficiency side: nothing until the name is read, then black; once a plan is worked out, the skills it
        // takes are coloured by where they stand in it (planLevels) and the rest stay black.
        int own=name==null?Store.NONE:wanted&&planning?planLevels.getOrDefault(name,0):FactorFrames.PLAIN;
        int level=name==null?Store.NONE:Store.registered(levels,name);if(level<0||level>Store.PRIORITY_MAX)level=Store.NONE;
        // The level written on the frame's top left, as the result window writes it: 必/重/要/本 for a plain preset,
        // 0-7 for a detailed one. 未 marks a skill the plain preset does not list.
        String tag=name==null?null:level==Store.NONE?(detailed?null:"未"):detailed?String.valueOf(level):TIER_LETTERS[Store.tierOf(level)];
        // Once a plan is worked out, every skill with a known rate is numbered on the bottom left, best rate first.
        Integer rank=planning&&name!=null?ranks.get(name):null;String order=rank==null?null:String.valueOf(rank);
        return switch(frameMode){
            case TOOLS_AND_PRESET -> new FactorFrames.Mark(y,own,true,line,box,level,tag,order);
            case PRESET_ONLY -> new FactorFrames.Mark(y,level,true,line,box,FactorFrames.WHOLE,tag,null);
            case NEITHER -> new FactorFrames.Mark(y,3,true,line,box);
            default -> new FactorFrames.Mark(y,own,true,line,box,FactorFrames.WHOLE,null,order);
        };
    }
    static final String[] TIER_LETTERS={"必","重","要","本"};
    /**
     * The plan's skills numbered 1, 2, 3… by what they pay out per point, best first: fixed when the plan is worked
     * out (see FloatingService.planWithOwnedPt), and shown while it is. Skills outside the plan get no number.
     */
    static volatile Map<String,Integer> planRanks=Map.of();
    /** The plan's skills, spread over the colours 0-6 by their rate, best first: the colour each is framed in. */
    static volatile Map<String,Integer> planLevels=Map.of();
    /** The points the plan uses and the points it leaves: {used, left}. */
    static volatile int[] planSpent;
    /** The selected factor preset's levels, parsed again only when a preset was saved or another chosen. */
    Map<String,List<Store.Rule>> presetRules(){
        var prefs=Store.prefs(this);
        long key=prefs.getString("data","").hashCode()*31L+prefs.getString(Store.key(Store.FACTOR),"").hashCode();
        if(key!=presetKey||presetCache==null){presetKey=key;presetCache=Store.rules(this);presetDetailed=Store.detailed(this);}
        return presetCache;
    }
    private Map<String,List<Store.Rule>> presetCache;private long presetKey;private boolean presetDetailed;

    /** Rows whose name the model was not certain about, re-read with the text recogniser. */
    List<SkillVision.Reading> checkNames(Bitmap frame,SkillVision.Layout layout,List<SkillVision.Reading> readings) {
        if(skillVision==null)return readings;
        List<int[]> boxes=new ArrayList<>();
        for(var row:readings)boxes.add(row.confidence()<NAME_CERTAIN?SkillVision.nameBox(layout.iconRight(),layout.pitch(),row.y()):null);
        // By the ink: the skill names are brown, and the sparkles passing over them do not count.
        String[] read=namesByText(frame,boxes,skillVision.byText,nameCache,true);
        List<SkillVision.Reading> out=new ArrayList<>();
        for(int i=0;i<readings.size();i++){var row=readings.get(i);out.add(read[i]==null?row:new SkillVision.Reading(read[i],1f,row.y()));}
        return out;
    }
    /**
     * The names in the given boxes as the text recogniser reads them, matched against a list of known names (see
     * NameMatch): for each box, the name, or null when there was no box, no match, or no answer. Shared by the
     * factor and the skill list. All the requests go out first and are then collected, so the recogniser works
     * through them back to back. Answers are remembered by the crop's signature (byInk: of its dark ink only), a
     * crop that matched nothing as "" so it is not read again; a failed OCR is not remembered, so an unchanged crop
     * is read again next time.
     */
    String[] namesByText(Bitmap frame,List<int[]> boxes,Map<String,String> names,Map<Long,String> cache,boolean byInk){
        int n=boxes.size();String[] read=new String[n];
        if(names==null||headerReader==null)return read;
        long[] keys=new long[n];List<Task<Text>> tasks=new ArrayList<>(Collections.nCopies(n,(Task<Text>)null));
        for(int i=0;i<n;i++){
            int[] box=boxes.get(i);
            if(box==null||!ptFits(frame,box))continue;
            keys[i]=byInk?inkSignature(frame,box):signature(frame,box);
            if(cache.containsKey(keys[i])){String known=cache.get(keys[i]);read[i]=known.isEmpty()?null:known;continue;}
            tasks.set(i,headerReader.process(InputImage.fromBitmap(ocrInput(frame,box),0)));ocrCalls++;
        }
        for(int i=0;i<n;i++){
            if(tasks.get(i)==null)continue;
            try{
                Text text=Tasks.await(tasks.get(i),2,TimeUnit.SECONDS);
                String match=NameMatch.match(text.getText(),names);
                cache.put(keys[i],match==null?"":match);read[i]=match;
            }catch(Exception ignored){}
        }
        return read;
    }
    /** OCR calls started this cycle, for the log (worker only). */
    private int ocrCalls;
    static boolean ptFits(Bitmap frame,int[] box){return box[0]>=0&&box[1]>=0&&box[2]<=frame.getWidth()&&box[3]<=frame.getHeight()&&box[2]>box[0]&&box[3]>box[1];}
    /** The model is left alone above this; below it the text recogniser gets a say. */
    static final float NAME_CERTAIN=.995f;
    private final Map<Long,String> nameCache=new LinkedHashMap<>(){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,String> eldest){return size()>256;}
    };

    /**
     * A signature of only the dark ink in a box - the brown digits of a price, the brown letters of a skill name.
     * The ヒントLv最大 tag sparkles right next to the price, and a signature of every pixel changed with each
     * sparkle, so the unchanged price was sent to OCR on every frame (58ms each, holding the skill screen to about
     * ten reads a second). Sparkles are light, so they leave this one alone.
     */
    private long inkSignature(Bitmap frame,int[] box){
        // From the pixels this cycle already copied out: Bitmap.getPixel per pixel, thousands of times a cycle,
        // was most of the skill screen's 26ms outside the model.
        int stride=frameW;
        long hash=1125899906842597L;int bits=0,count=0;
        for(int y=box[1];y<box[3];y+=2)for(int x=box[0];x<box[2];x+=2){
            int c=pixelAt(frame,x,y,stride),r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            // Anything clearly darker than the light cards and boxes: the brown text, and the orange of the points owned.
            bits=bits<<1|(r*3+g*6+b<2000?1:0);
            if(++count==31){hash=hash*31+bits;bits=0;count=0;}
        }
        return (hash*31+bits)*31+(box[2]-box[0])*7919L+(box[3]-box[1]);
    }
    /**
     * The rows of this cycle's frame copied into pixels, as {from, to} pairs. Rows outside them still hold an older
     * frame, so they are never read from pixels. Worker only.
     */
    private final List<int[]> copied=new ArrayList<>();
    /** Starts a cycle's copy: nothing of this frame is in pixels yet. */
    private void startCopy(int w,int h){if(pixels.length!=w*h)pixels=new int[w*h];copied.clear();}
    /** Copies rows from..to of the frame into pixels, at their own place (row y at y*w), unless already there. */
    private void copyRows(Bitmap frame,int w,int from,int to){
        from=Math.max(0,from);to=Math.min(pixels.length/Math.max(1,w),to);
        if(to<=from)return;
        for(int[] range:copied)if(range[0]<=from&&range[1]>=to)return;
        frame.getPixels(pixels,from*w,w,0,from,w,to-from);
        copied.add(new int[]{from,to});
    }
    /** A pixel of this cycle's frame: from the copied rows when they cover it, else from the bitmap itself. */
    private int pixelAt(Bitmap frame,int x,int y,int stride){
        if(stride>0&&x<stride)for(int[] range:copied)if(y>=range[0]&&y<range[1])return pixels[y*stride+x];
        return frame.getPixel(x,y);
    }
    private long signature(Bitmap frame,int[] box){
        long hash=1125899906842597L;int stride=frameW;
        for(int y=box[1];y<box[3];y+=3)for(int x=box[0];x<box[2];x+=3)hash=hash*31+pixelAt(frame,x,y,stride);
        return hash*31+(box[2]-box[0]);
    }
    @Override public void onDestroy(){
        if(instance==this)instance=null;
        closed=true;running=false;connected=false;frames=null;contentVisible=true;message="停止中";
        // Tear down on the capture thread so the frame listener never races a released reader.
        handler.post(()->{releaseHeld();if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();captureThread.quitSafely();});
        worker.execute(()->{if(headerReader!=null)headerReader.close();closeModels();});worker.shutdown();
        super.onDestroy();
    }
}
