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
    static final String DEFAULT_RATE="30";
    /** running: recognising now. connected: the screen share is alive, possibly paused, so resuming needs no consent dialog. */
    static volatile boolean running=false, connected=false;
    static volatile String message="停止中";
    /** Rows of the last recognised frame for the factor frames overlay; null when there is nothing to frame. */
    static volatile FactorFrames.Snapshot frames;
    /** False while the shared app is hidden (e.g. switched away), so frames never float over another app. */
    static volatile boolean contentVisible=true;
    static void pause(Context c){c.startService(new Intent(c,CaptureService.class).setAction("PAUSE"));}
    static void resume(Context c){c.startService(new Intent(c,CaptureService.class).setAction("RESUME"));}
    static final CycleStats stats=new CycleStats();
    static String performance(){return "状態："+message+"\n"+stats.text(SystemClock.elapsedRealtimeNanos())+"\n"+stageText();}
    static final Map<String,Map<String,Integer>> results=new LinkedHashMap<>();
    /** Where each factor sits in its page, so results are listed as in the game (see PagePlaces). */
    static final PagePlaces places=new PagePlaces();
    static volatile long resultGeneration=0;
    static synchronized void resetResults(){resultGeneration++;results.clear();places.clear();
    }
    static synchronized Map<String,Map<String,Integer>> snapshot(){
        Map<String,Map<String,Integer>> copy=new LinkedHashMap<>();
        if(results.containsKey("元因子"))copy.put("元因子",new LinkedHashMap<>(results.get("元因子")));
        for(int n=1;n<=9;n++){String label="再獲得因子"+n;if(results.containsKey(label))copy.put(label,new LinkedHashMap<>(results.get(label)));}
        return copy;
    }
    static String summary(Context c){
        StringBuilder s=new StringBuilder();var rules=Store.rules(c);for(var entry:snapshot().entrySet()){
        s.append(s.length()>0?"\n":"").append(entry.getKey()).append('\n').append(countSummary(entry.getValue(),rules));
    }
        // Never empty: an empty text hides the window.
        return s.length()>0?s.toString():"まだ読み取っていません";}
    static String countSummary(Map<String,Integer> factors,Map<String,List<Store.Rule>> rules){
        String[] tiers={"必須","重要","必要","未設定"};int[] counts=new int[tiers.length];
        for(var factor:factors.entrySet()){String tier=Store.tier(rules,factor.getKey(),factor.getValue());for(int i=0;i<tiers.length;i++)if(tiers[i].equals(tier))counts[i]++;}
        return "必須 "+counts[0]+" / 重要 "+counts[1]+" / 必要 "+counts[2]+" / 未設定 "+counts[3];
    }

    // Per-stage timings. 見出し待ち is only what is left of the ML Kit header OCR after the factor inference finished.
    static final String[] STAGES={"取得","画素","行検出","因子推論","見出し待ち"};
    static final double[] stageMs=new double[STAGES.length];static long headerOcr,headerCached,noRows,noHeader;
    static volatile FactorVision activeVision;static volatile String titleKnown="なし",lastOcrText="";
    /** The models the last cycle ran, and what to call them in the display. */
    static volatile ModelRunner activeRunner;static volatile String activeModels="因子推論";
    static synchronized void resetStages(){Arrays.fill(stageMs,0);headerOcr=0;headerCached=0;noRows=0;noHeader=0;FactorVision v=activeVision;if(v!=null)v.resetCounters();ModelRunner r=activeRunner;if(r!=null)r.resetCounters();}
    static synchronized void stage(int i,long nanos){double ms=nanos/1e6;stageMs[i]=stageMs[i]==0?ms:stageMs[i]*.9+ms*.1;}
    static synchronized void headerUsed(boolean cached){if(cached)headerCached++;else headerOcr++;}
    static synchronized String stageText(){
        StringBuilder s=new StringBuilder("段階別（移動平均）");
        for(int i=0;i<STAGES.length;i++)s.append(i%3==0?"\n":" / ").append(STAGES[i]).append(String.format(Locale.JAPAN," %.1fms",stageMs[i]));
        s.append(String.format(Locale.JAPAN,"\n見出しOCR実行：%d / %d 回\n行が見つからない %d 回 / 見出しが読めない %d 回\n見出しの記憶：%s\nOCRが最後に読んだ文字：「%s」",headerOcr,headerOcr+headerCached,noRows,noHeader,titleKnown,lastOcrText));
        ModelRunner r=activeRunner;
        if(r!=null)s.append("\n推論方式：").append(r.modeText())
            .append(String.format(Locale.JAPAN,"\n推論 新規 %d / 再利用 %d 枚",r.inferred,r.reused)).append("\n").append(r.splitText(activeModels)).append("\n").append(r.threadText());
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
    void pauseNow(){if(projection!=null&&!paused){paused=true;running=false;frames=null;message="一時停止中";handler.post(()->{handler.removeCallbacks(pumpTask);releaseHeld();recheck=false;if(display!=null)display.setSurface(null);});}}
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
    @Override public void onCreate(){super.onCreate();instance=this;Calibration.current=Calibration.load(this);captureThread.start();handler=new Handler(captureThread.getLooper());}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        // Pausing keeps the projection token; only the frames stop, by detaching the reader surface.
        if(projection==null&&("PAUSE".equals(intent.getAction())||"RESUME".equals(intent.getAction()))){stopSelf();return START_NOT_STICKY;}
        if("PAUSE".equals(intent.getAction())){pauseNow();return START_NOT_STICKY;}
        if("RESUME".equals(intent.getAction())){if(projection!=null&&paused){paused=false;freshStart=true;stats.reset(SystemClock.elapsedRealtimeNanos());resetStages();running=true;message="認識中";handler.post(()->{if(display!=null&&reader!=null)display.setSurface(reader.getSurface());});}return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try {
            startForeground(12,Ui.notification(this,"因子を認識中",CaptureService.class,12));
            Intent grant=intent.getParcelableExtra("grant",Intent.class);
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result",Activity.RESULT_CANCELED),grant);
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){stopSelf();}
                @Override public void onCapturedContentResize(int w,int h){if(w!=width||h!=height)resize(w,h);}
                @Override public void onCapturedContentVisibilityChanged(boolean visible){contentVisible=visible;}
            },handler);
            headerReader=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            Rect bounds=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();width=bounds.width();height=bounds.height();
            openReader();display=projection.createVirtualDisplay("FactorCapture",width,height,getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
            // Connecting the screen share does not start reading: that waits for 開始 on the floating bar, so the
            // game can be set up first without the app reading, and heating the phone, in the meantime.
            stats.reset(SystemClock.elapsedRealtimeNanos());resetStages();connected=true;running=false;paused=true;
            display.setSurface(null);message="待機中（開始で読み取り）";
        } catch(Exception e){message="開始失敗："+e.getClass().getSimpleName();Ui.toast(this,message);stopSelf();}
        return START_NOT_STICKY;
    }
    void resize(int w,int h){
        if(closed||w<=0||h<=0)return;width=w;height=h;
        if(display!=null){display.setSurface(null);releaseHeld();if(reader!=null)reader.close();openReader();display.resize(w,h,getResources().getDisplayMetrics().densityDpi);if(!paused)display.setSurface(reader.getSurface());}
    }
    void openReader(){
        // One image held by us plus the ones in flight between the display and the listener.
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
        // The display only sends a frame when the screen changes, so a frame that arrives while the worker is busy
        // may be the last one for a long time (the list just stopped scrolling). The newest one is always kept, but
        // only held: it is copied when a cycle starts, so a lower cycle rate also means fewer full-screen copies.
        reader.setOnImageAvailableListener(source->{
            Image image;try{image=source.acquireLatestImage();}catch(Exception e){message="画面取得待ち";return;}
            if(image==null)return;
            if(closed||paused){image.close();return;}
            releaseHeld();held=image;
            // While the worker is busy with an earlier frame, this one still moves the frames: see followColumn.
            if(busy)followColumn(image);
            pump();
        },handler);
    }

    /** Where the rows were on the newest frame, found from one column only (see followColumn), and when it was captured. */
    record Rows(long time,List<Integer> ys,List<int[]> boxes,int pitch) {}
    private volatile Rows latestRows;
    /** Capture time of the frame the worker's cycle is reading. */
    private volatile long cycleFrameTime;
    /**
     * Capture thread only. Moves the frames onto the rows of a frame that arrived while the worker was still busy
     * with an earlier one. Only the column the cards' edges are found in is read, straight from the image, so this
     * costs a couple of thousand pixels: the frames then follow the list at the rate the screen changes rather
     * than at the rate frames are fully read.
     */
    void followColumn(Image image){
        FactorFrames.Snapshot last=frames;
        if(last==null||last.marks().isEmpty())return;
        try{
            double scanX=FactorVision.CARD_SCAN_X,top=FactorVision.LIST_TOP,bottom=FactorVision.LIST_BOTTOM;
            int w=image.getWidth(),h=image.getHeight();
            var plane=image.getPlanes()[0];var buffer=plane.getBuffer();int rowStride=plane.getRowStride(),pixelStride=plane.getPixelStride();
            int x=(int)Math.rint(scanX*w),from=(int)(h*top),to=(int)(h*bottom);
            int[] column=new int[h];
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
                ys.add(run[0]+unit/2);
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
        Rows newer=latestRows;
        if(snapshot!=null&&newer!=null&&newer.time()>cycleFrameTime){
            var moved=FactorFrames.follow(snapshot,newer.ys(),newer.boxes(),newer.pitch(),snapshot.frameWidth(),snapshot.frameHeight(),snapshot.rowHalf(),snapshot.labelX());
            if(moved!=null)snapshot=moved;
        }
        frames=snapshot;
    }
    void releaseHeld(){if(held!=null){held.close();held=null;}}
    final Runnable pumpTask=this::pump;
    /** Capture thread only. Starts the next cycle on the newest frame, or re-reads the unchanged frame while rows await confirmation. */
    void pump(){
        if(closed||paused||busy)return;
        // An adjustable upper bound, not a frame queue or a promised frame rate.
        long now=SystemClock.elapsedRealtimeNanos(),interval=CycleLimit.intervalNanos(Store.prefs(this).getString("recognitionRateText",DEFAULT_RATE));
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
            if(workerGeneration!=generation||freshStart){workerGeneration=generation;freshStart=false;candidate="";stable=0;confirmations.clear();settle.forget();
                }
            // The inference mode can be flipped in the display settings while running, for side-by-side timing.
            boolean useXnnpack=Store.prefs(this).getBoolean("xnnpack",false);int threadSetting=Store.prefs(this).getInt("inferenceThreads",0);
            if(vision!=null&&vision.runner.requestedXnnpack!=useXnnpack){vision.close();vision=null;}
            if(vision==null){vision=new FactorVision(asset("factor.onnx"),asset("factor_rank.onnx"),new String(asset("labels.json"),StandardCharsets.UTF_8),useXnnpack);activeVision=vision;resetStages();}
            if(vision.runner.tuner!=null)vision.runner.tuner.setThreads(threadSetting);
            activeRunner=vision.runner;activeModels="因子推論";
            stage(0,grabbed);
            long t0=SystemClock.elapsedRealtimeNanos();
            // Only the page title and the list are copied out; everything else on screen is never looked at.
            int[] titleBox=TitleMask.box(w,h);
            int listFrom=(int)(h*FactorVision.LIST_TOP),listTo=(int)(h*FactorVision.LIST_BOTTOM);
            startCopy(w,h);copyRows(frame,w,titleBox[1],titleBox[3]);copyRows(frame,w,listFrom,listTo);
            long t1=SystemClock.elapsedRealtimeNanos();stage(1,t1-t0);
            FactorVision.Layout layout=FactorVision.detectCards(pixels,w,h,listFrom,listTo);
            // No cards there (another device, another layout): the whole frame, and the older way.
            if(layout==null){copyRows(frame,w,0,h);layout=FactorVision.detect(pixels,w,h);}
            long t2=SystemClock.elapsedRealtimeNanos();stage(2,t2-t1);
            if(layout==null){frames=null;candidate="";stable=0;confirmations.clear();settle.forget();synchronized(CaptureService.class){noRows++;}return;}
            // The page title: an unchanged title is recognised by its shape; anything else, including every page switch, needs OCR.
            byte[] mask=TitleMask.of(pixels,w,h);
            String known=titles.match(mask);boolean cached=known!=null;
            // ML Kit runs on its own executor, so a needed title OCR overlaps the factor inference below.
            // It reads just the title text box: the stylised 因子選択 logo, arrows and page dots only confuse it.
            if(!cached){headerImage=ocrInput(frame,TitleMask.box(w,h),headerAttempt);pending=headerReader.process(InputImage.fromBitmap(headerImage,0));}
            // The frames move onto the rows now, before the models run, so they keep up with a scrolling list.
            if(Store.prefs(this).getBoolean("factorFrames",false)){
                List<int[]> boxes=new ArrayList<>();for(int y:layout.ys())boxes.add(FactorVision.frameOf(layout,y,w));
                FactorFrames.Snapshot early=FactorFrames.follow(frames,layout.ys(),boxes,layout.pitch(),w,h,0,0);
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
            if(Store.prefs(this).getBoolean("factorFrames",false)){
                var rules=Store.rules(this);List<FactorFrames.Mark> marks=new ArrayList<>();
                for(var row:readings)if(row.nameConfidence()>=FactorVision.NAME_MIN&&row.starConfidence()>=FactorVision.STAR_MIN){int priority=Store.priority(rules,row.name(),row.stars());boolean met=priority!=Store.UNMET;
                    marks.add(new FactorFrames.Mark(row.y(),met?priority:Store.registered(rules,row.name()),met,null,FactorVision.frameOf(layout,row.y(),w)));}
                publish(new FactorFrames.Snapshot(w,h,marks));
            }else frames=null;
            if(label.isEmpty()){
                synchronized(CaptureService.class){noHeader++;}
                // A still screen sends no new frame, so a missed title would never be read again: retry a few times,
                // each with a different OCR input, within the normal cycle interval.
                if(!cached&&headerAttempt<HEADER_RETRIES){headerAttempt++;recheck=true;}
                return;
            }
            if(label.equals(candidate))stable++;else{candidate=label;stable=1;confirmations.clear();}
            // Mid swipe the new title is already showing while the previous page's rows are still sliding past.
            // Those rows must not be counted under the new page, so only a frame that matches the previous one counts.
            if(!settle.settled(mask,layout.starX(),layout.pitch())){confirmations.clear();recheck=true;return;}
            // Rows the model is only fairly sure of are read again as text and matched against every factor name
            // - the dataset above still gets the model's own answers, so its misses stay visible there.
            readings=checkFactorNames(frame,w,h,layout,readings);
            List<PagePlaces.Row> placed=new ArrayList<>();
            for(var row:readings)if(row.nameConfidence()>=FactorVision.NAME_MIN&&row.starConfidence()>=FactorVision.STAR_MIN)placed.add(new PagePlaces.Row(row.name(),row.y()));
            places.record(label,placed);
            Set<String> present=new HashSet<>();Map<String,Integer> accepted=new LinkedHashMap<>();
            for(var row:readings){
                if(row.nameConfidence()<FactorVision.NAME_MIN||row.starConfidence()<FactorVision.STAR_MIN)continue;
                String key=row.name()+":"+row.stars();present.add(key);
                int hits=confirmations.getOrDefault(key,0)+1;confirmations.put(key,Math.min(2,hits));
                if(hits>=2&&stable>=2){accepted.put(row.name(),row.stars());
                }
            }
            confirmations.keySet().retainAll(present);
            recheck=accepted.size()<present.size();
            // A cycle that finishes after a pause registers nothing: what was paused on (e.g. for a send) must not change.
            if(!closed&&!paused&&stable>=2&&!accepted.isEmpty())synchronized(CaptureService.class){if(generation==resultGeneration)results.computeIfAbsent(label,k->new LinkedHashMap<>()).putAll(accepted);}
            message="認識中："+label;
        }catch(Exception e){message="認識エラー："+e.getClass().getSimpleName();}
        finally{
            long end=SystemClock.elapsedRealtimeNanos();stats.add(end,end-started);
            // A header bitmap still being read by an unfinished OCR task is left to the GC.
            if(headerImage!=null&&(pending==null||pending.isComplete()))headerImage.recycle();
            if(++cycles%50==0)Log.d("FactorCapture",stageText().replace('\n',' '));
            handler.post(()->{busy=false;pump();});
        }
    }
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
    /**
     * The settled rows the model was not certain of, read as text as well (all requests sent first, then collected)
     * and matched against every factor name (see NameMatch). A match replaces the model's name - the case this is
     * for is a name read as its look-alike with high confidence (存在感 as 桜花賞 at .98). No match leaves the
     * model's reading alone. A failed OCR is not remembered, so the unchanged crop is read again next time.
     */
    List<FactorVision.Reading> checkFactorNames(Bitmap frame,int w,int h,FactorVision.Layout layout,List<FactorVision.Reading> readings){
        List<int[]> boxes=new ArrayList<>();
        for(var row:readings)boxes.add(row.nameConfidence()<FACTOR_CERTAIN?FactorVision.nameBox(layout,row.y()):null);
        String[] read=namesByText(frame,boxes,factorNames(),factorText);
        List<FactorVision.Reading> out=new ArrayList<>(readings.size());
        for(int i=0;i<readings.size();i++){
            var row=readings.get(i);
            if(read[i]==null){out.add(row);continue;}
            if(!read[i].equals(row.name()))Log.d("FactorCheck",String.format(Locale.JAPAN,"%s(%.3f) → 文字では %s",row.name(),row.nameConfidence(),read[i]));
            out.add(new FactorVision.Reading(read[i],row.stars(),1f,row.starConfidence(),row.y()));
        }
        return out;
    }
    /**
     * The names in the given boxes as the text recogniser reads them, matched against a list of known names (see
     * NameMatch): for each box, the name, or null when there was no box, no match, or no answer. All the
     * requests go out first and are then collected, so the recogniser works through them back to back. Answers
     * are remembered by the crop's signature, a
     * crop that matched nothing as "" so it is not read again; a failed OCR is not remembered, so an unchanged crop
     * is read again next time.
     */
    String[] namesByText(Bitmap frame,List<int[]> boxes,Map<String,String> names,Map<Long,String> cache){
        int n=boxes.size();String[] read=new String[n];
        if(names==null||headerReader==null)return read;
        long[] keys=new long[n];List<Task<Text>> tasks=new ArrayList<>(Collections.nCopies(n,(Task<Text>)null));
        for(int i=0;i<n;i++){
            int[] box=boxes.get(i);
            if(box==null||!ptFits(frame,box))continue;
            keys[i]=signature(frame,box);
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
        worker.execute(()->{activeVision=null;if(headerReader!=null)headerReader.close();if(vision!=null)try{vision.close();}catch(Exception ignored){}});worker.shutdown();
        super.onDestroy();
    }
}
