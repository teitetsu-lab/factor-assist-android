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
    static synchronized void resetResults(){resultGeneration++;results.clear();places.clear();}
    static synchronized Map<String,Map<String,Integer>> snapshot(){
        Map<String,Map<String,Integer>> copy=new LinkedHashMap<>();
        if(results.containsKey("元因子"))copy.put("元因子",new LinkedHashMap<>(results.get("元因子")));
        for(int n=1;n<=9;n++){String label="再獲得因子"+n;if(results.containsKey(label))copy.put(label,new LinkedHashMap<>(results.get(label)));}
        return copy;
    }
    static String summary(Context c){StringBuilder s=new StringBuilder();var rules=Store.rules(c);for(var entry:snapshot().entrySet()){
        s.append(s.length()>0?"\n":"").append(entry.getKey()).append('\n').append(countSummary(entry.getValue(),rules));
    }return s.toString();}
    static String countSummary(Map<String,Integer> factors,Map<String,List<Store.Rule>> rules){
        String[] tiers={"必須","重要","必要","未設定"};int[] counts=new int[tiers.length];
        for(var factor:factors.entrySet()){String tier=Store.tier(rules,factor.getKey(),factor.getValue());for(int i=0;i<tiers.length;i++)if(tiers[i].equals(tier))counts[i]++;}
        return "必須 "+counts[0]+" / 重要 "+counts[1]+" / 必要 "+counts[2]+" / 未設定 "+counts[3];
    }

    // Per-stage timings. 見出し待ち is only what is left of the ML Kit header OCR after the factor inference finished.
    static final String[] STAGES={"取得","画素","行検出","因子推論","見出し待ち"};
    static final double[] stageMs=new double[STAGES.length];static long headerOcr,headerCached,noRows,noHeader;
    static volatile FactorVision activeVision;static volatile String titleKnown="なし",lastOcrText="";
    static synchronized void resetStages(){Arrays.fill(stageMs,0);headerOcr=0;headerCached=0;noRows=0;noHeader=0;FactorVision v=activeVision;if(v!=null)v.resetCounters();}
    static synchronized void stage(int i,long nanos){double ms=nanos/1e6;stageMs[i]=stageMs[i]==0?ms:stageMs[i]*.9+ms*.1;}
    static synchronized void headerUsed(boolean cached){if(cached)headerCached++;else headerOcr++;}
    static synchronized String stageText(){
        StringBuilder s=new StringBuilder("段階別（移動平均）");
        for(int i=0;i<STAGES.length;i++)s.append(i%3==0?"\n":" / ").append(STAGES[i]).append(String.format(Locale.JAPAN," %.1fms",stageMs[i]));
        s.append(String.format(Locale.JAPAN,"\n見出しOCR実行：%d / %d 回\n行が見つからない %d 回 / 見出しが読めない %d 回\n見出しの記憶：%s\nOCRが最後に読んだ文字：「%s」",headerOcr,headerOcr+headerCached,noRows,noHeader,titleKnown,lastOcrText));
        FactorVision v=activeVision;
        if(v!=null)s.append("\n推論方式：").append(v.xnnpack?"XNNPACK":v.requestedXnnpack?"標準（XNNPACKは使用不可）":"標準")
            .append(String.format(Locale.JAPAN,"\n推論 新規 %d / 再利用 %d 枚\n±1再推論 %d / %d 行",v.inferred,v.reused,v.retriedRows,v.rows)).append("\n").append(v.splitText()).append("\n").append(v.threadText());
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
            stats.reset(SystemClock.elapsedRealtimeNanos());resetStages();running=true;connected=true;message="認識中";
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
            releaseHeld();held=image;pump();
        },handler);
    }
    void releaseHeld(){if(held!=null){held.close();held=null;}}
    final Runnable pumpTask=this::pump;
    /** Capture thread only. Starts the next cycle on the newest frame, or re-reads the unchanged frame while rows await confirmation. */
    void pump(){
        if(closed||paused||busy)return;
        // An adjustable upper bound, not a frame queue or a promised frame rate.
        long now=SystemClock.elapsedRealtimeNanos(),interval=CycleLimit.intervalNanos(Store.prefs(this).getString("recognitionRateText",DEFAULT_RATE));
        if(now-last<interval){handler.removeCallbacks(pumpTask);handler.postDelayed(pumpTask,(last+interval-now)/1_000_000+1);return;}
        long grabbed;boolean reread;
        if(held!=null){
            Image image=held;held=null;
            try{
                var plane=image.getPlanes()[0];int padded=plane.getRowStride()/plane.getPixelStride();
                if(frame==null||frame.getWidth()!=padded||frame.getHeight()!=image.getHeight())frame=Bitmap.createBitmap(padded,image.getHeight(),Bitmap.Config.ARGB_8888);
                // Row padding stays in the bitmap; the worker reads only the w×h region, so no cropped copy is needed.
                frame.copyPixelsFromBuffer(plane.getBuffer());frameW=image.getWidth();frameH=image.getHeight();
            }catch(Exception e){message="画面取得待ち";return;}
            finally{image.close();}
            grabbed=SystemClock.elapsedRealtimeNanos()-now;reread=false;
        }
        // No new frame means the screen is standing still: reading it again confirms rows seen once (cheap, thanks
        // to the inference cache) or retries a title OCR that missed.
        else if(recheck&&frame!=null){recheck=false;grabbed=0;reread=true;}
        else return;
        busy=true;last=now;long generation=resultGeneration;Bitmap target=frame;int w=frameW,h=frameH;boolean again=reread;
        try{worker.execute(()->process(target,w,h,generation,now,grabbed,again));}catch(Exception e){busy=false;}
    }
    byte[] asset(String name)throws Exception{try(var in=getAssets().open(name)){return in.readAllBytes();}}
    void process(Bitmap frame,int w,int h,long generation,long started,long grabbed,boolean reread){
        Task<Text> pending=null;Bitmap headerImage=null;
        try {
            recheck=false;  // cleared first so an early return can never leave a re-read loop behind
            if(!reread)headerAttempt=0;
            if(closed||generation!=resultGeneration)return;
            // After a pause the screen may be anywhere, so half-confirmed rows from before it must not count.
            if(workerGeneration!=generation||freshStart){workerGeneration=generation;freshStart=false;candidate="";stable=0;confirmations.clear();settle.forget();}
            // The inference mode can be flipped in the display settings while running, for side-by-side timing.
            boolean useXnnpack=Store.prefs(this).getBoolean("xnnpack",false);int threadSetting=Store.prefs(this).getInt("inferenceThreads",0);
            if(vision!=null&&vision.requestedXnnpack!=useXnnpack){vision.close();vision=null;}
            if(vision==null){vision=new FactorVision(asset("factor.onnx"),asset("factor_rank.onnx"),new String(asset("labels.json"),StandardCharsets.UTF_8),useXnnpack);activeVision=vision;resetStages();}
            if(vision.tuner!=null)vision.tuner.setThreads(threadSetting);
            stage(0,grabbed);
            long t0=SystemClock.elapsedRealtimeNanos();
            if(pixels.length!=w*h)pixels=new int[w*h];frame.getPixels(pixels,0,w,0,0,w,h);
            long t1=SystemClock.elapsedRealtimeNanos();stage(1,t1-t0);
            FactorVision.Layout layout=FactorVision.detect(pixels,w,h);
            long t2=SystemClock.elapsedRealtimeNanos();stage(2,t2-t1);
            if(layout==null){frames=null;candidate="";stable=0;confirmations.clear();settle.forget();synchronized(CaptureService.class){noRows++;}return;}
            // The page title: an unchanged title is recognised by its shape; anything else, including every page switch, needs OCR.
            byte[] mask=TitleMask.of(pixels,w,h);
            String known=titles.match(mask);boolean cached=known!=null;
            // ML Kit runs on its own executor, so a needed title OCR overlaps the factor inference below.
            // It reads just the title text box: the stylised 因子選択 logo, arrows and page dots only confuse it.
            if(!cached){headerImage=ocrInput(frame,TitleMask.box(w,h),headerAttempt);pending=headerReader.process(InputImage.fromBitmap(headerImage,0));}
            List<FactorVision.Reading> readings=vision.read(pixels,w,h,layout);
            long t3=SystemClock.elapsedRealtimeNanos();stage(3,t3-t2);
            String label;
            if(cached)label=known;
            else{Text text=Tasks.await(pending);String raw=text.getText().replace('\n',' ').trim();lastOcrText=raw.length()>24?raw.substring(0,24)+"…":raw;
                label=headerLabel(text);titles.learn(label,mask);if(titles.current()!=null)titleKnown=titles.current();
                Log.d("FactorTitle","OCR「"+raw+"」 -> "+(label.isEmpty()?"(読めない)":label)+" / 記憶 "+titleKnown);}
            headerUsed(cached);stage(4,SystemClock.elapsedRealtimeNanos()-t3);
            // An unreadable header (about a third of cycles on the S25) only skips this frame. Resetting here kept
            // breaking the two-in-a-row confirmation; a real page change still resets once the next header differs.
            // Frames follow every read row, even when this frame's title was unreadable.
            if(Store.prefs(this).getBoolean("factorFrames",false)){
                var rules=Store.rules(this);List<FactorFrames.Mark> marks=new ArrayList<>();
                for(var row:readings)if(row.nameConfidence()>=FactorVision.NAME_MIN&&row.starConfidence()>=FactorVision.STAR_MIN)marks.add(new FactorFrames.Mark(row.y(),Store.tier(rules,row.name(),row.stars())));
                frames=new FactorFrames.Snapshot(w,h,marks);
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
    @Override public void onDestroy(){
        if(instance==this)instance=null;
        closed=true;running=false;connected=false;frames=null;contentVisible=true;message="停止中";
        // Tear down on the capture thread so the frame listener never races a released reader.
        handler.post(()->{releaseHeld();if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();captureThread.quitSafely();});
        worker.execute(()->{activeVision=null;if(headerReader!=null)headerReader.close();if(vision!=null)try{vision.close();}catch(Exception ignored){}});worker.shutdown();
        super.onDestroy();
    }
}
