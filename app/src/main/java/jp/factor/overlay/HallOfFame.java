package jp.factor.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 殿堂入りウマ娘の詳細, kept as two long images like umacapture's: for the スキル and 継承 tabs, the top of the
 * dialog - the stats, the aptitudes and the tabs - over its whole list, the list made of its cards (HofCompose).
 * Recognised by itself, whatever is being read: three tabs right under the aptitudes, one of them green (育成情報 is
 * recognised but not taken).
 *
 * Nothing is placed by measuring the screen: what is on it is read (HofCompose builds each tab's list from the names
 * read, screen after screen), and how far down the list has come is the scroll bar's. Each tab is taken from the very
 * top of its list at rest - a tab whose first screen is not at the top waits until it is scrolled up there, and a
 * list pulled past its top (springing back) is not at rest - and is whole once the bar's thumb reaches the bottom
 * with the list built up all the way (every screen laid over the one before). The tab's top is taken again while its
 * list scrolls (the finger is on the list), so no tap effect is over it.
 *
 * Each horse - its epithet, name, evaluation, stats and aptitudes - is kept on the phone (files/halloffame), so it is
 * known again any day after: one half taken goes on where it was left, one saved is not saved twice. What was taken
 * goes once its images are saved; the images go once sent; the record of the horse stays.
 *
 * Places are shares of the screen measured on the S25 (1080×2340). Worker thread only, but for what the result window
 * and the gestures ask (synchronized).
 */
final class HallOfFame {
    static final String[] TABS={"スキル","継承","育成情報"};
    // The dialog: its top (title) down to the tabs, and the list under them; the tab bar's middle and each tab's centre.
    static final double HEAD_TOP=258/2340.0,HEAD_BOTTOM=1072/2340.0,LIST_TOP=1075/2340.0,LIST_BOTTOM=1880/2340.0,TAB_Y=1037/2340.0;
    static final double LEFT=14/1080.0,RIGHT=1066/1080.0;
    /** Inside each tab but clear of its white label (the label would read as white on the green tab too). */
    static final double[] TAB_X={75/1080.0,410/1080.0,756/1080.0};
    // The scroll bar: its column, and the track the thumb runs along (top to bottom, the thumb's colour 137,135,153).
    static final double BAR_X=1041/1080.0,TRACK_TOP=1106/2340.0,TRACK_BOTTOM=1853/2340.0;
    /** The stats row and the aptitudes: with the epithet, the name and the evaluation, they tell one horse from another. */
    static final double STATS_TOP=725/2340.0,STATS_BOTTOM=775/2340.0,APT_TOP=830/2340.0,APT_BOTTOM=975/2340.0;
    /** A horse half taken and not touched for this long is let go (its pieces take megabytes). */
    static final long FORGET_MS=30L*24*60*60*1000;

    /**
     * One tab as it is being taken: its top taken (and its height), begun (from the top of its list at rest), how far
     * down the list has come (the scroll bar's place, 0-1, on screens laid over the list), whole (to the bottom).
     */
    static final class Tab {
        int headH;boolean head,begun,whole;double reached;
        int percent(){return whole?100:begun?Math.max(0,Math.min(99,(int)Math.floor(reached*100))):0;}
        /** The tab was opened scrolled down: it waits to be taken from the top. */
        boolean needsTop;
    }

    /** One horse, as kept in its folder (meta.json, the tabs' pieces, the saved images). */
    static final class Session {
        String key="";File dir;Tab[] tabs=newTabs();String name="",epithet="";boolean saved,sent;long savedAt,touched;
        List<Factor> factors=List.of();List<String> uris=List.of();
        /** The aptitudes as read off the dialog (芝 → A, …, in screen order), and the unique skill's level (0: not read). */
        Map<String,String> aptitudes=new LinkedHashMap<>();int uniqueLevel;
    }
    private File root;
    private Session cur=new Session();
    /** The stats and aptitudes as last identified, and as now on screen when they changed (identify must follow). */
    private long curLook=0,pendingLook;private boolean needsIdentity;
    private long lastSeen;private int streak;
    private boolean wantsRecheck;
    private static Tab[] newTabs(){return new Tab[]{new Tab(),new Tab(),new Tab()};}

    /** Where the horses are kept; horses half taken and long untouched are let go, and images long unsent. */
    synchronized void init(Context c){
        root=new File(c.getFilesDir(),"halloffame");root.mkdirs();
        File ext=c.getExternalFilesDir(null);if(ext!=null)LOG=new File(ext,"hof_log.txt");
        File[] dirs=root.listFiles();if(dirs==null)return;long now=System.currentTimeMillis();
        for(File d:dirs){
            // A record that does not read is left as it is (it may be read from its copy, or mended): never deleted for it.
            JSONObject m=readMeta(d);if(m==null){if(files(d).length==0)d.delete();else log("記録が読めないフォルダ（消さずに残す）："+d.getName());continue;}
            if(now-m.optLong("touched")<FORGET_MS)continue;
            if(!m.optBoolean("saved")){log("保存されないまま30日たったので消す："+d.getName()+" "+m.optString("name"));delete(d);continue;}
            for(File f:files(d))if(!kept(f.getName()))f.delete();   // long unsent: the record stays
        }
    }

    // ---- the record (meta.json): written whole or not at all, the one before kept beside it, and a log of what happened

    /** One lock for every read-and-write of a record, from the capture and from the 台帳 alike. */
    static final Object META_LOCK=new Object();
    /**
     * A horse's record, written so a stop half way leaves the old one: into a file beside it, then put in its place.
     * A copy is kept as meta.bak - the record as last written, except that a copy saying saved is never replaced by
     * one saying not saved for the same horse: read in place of a record gone bad, it must not have the horse retaken
     * or let go (see readMeta, init).
     */
    static void writeMeta(File dir,JSONObject m)throws java.io.IOException{
        synchronized(META_LOCK){
            File meta=new File(dir,"meta.json"),tmp=new File(dir,"meta.json.tmp"),bak=new File(dir,"meta.bak");
            Files.write(tmp.toPath(),m.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(),meta.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            JSONObject was=readJson(bak);
            boolean keep=was!=null&&was.optBoolean("saved")&&!m.optBoolean("saved")&&was.optString("key").equals(m.optString("key"));
            if(!keep)Files.copy(meta.toPath(),bak.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    /** A horse's record: meta.json, or its copy when it does not read - the record mended from the copy then; null when neither reads. */
    static JSONObject readMeta(File dir){
        synchronized(META_LOCK){
            File meta=new File(dir,"meta.json"),bak=new File(dir,"meta.bak");
            JSONObject m=readJson(meta);if(m!=null)return m;
            m=readJson(bak);
            if(m!=null)try{File tmp=new File(dir,"meta.json.tmp");Files.copy(bak.toPath(),tmp.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp.toPath(),meta.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    log("記録が読めないので控えから直した："+dir.getName()+" "+m.optString("name"));}
                catch(Exception e){log("記録が読めず、控えから直せもしない："+dir.getName()+" "+e);}
            return m;
        }
    }
    private static JSONObject readJson(File f){
        try{if(!f.exists())return null;return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception e){return null;}
    }
    /** What happened to the records, for finding out why one went: hof_log.txt in the app's folder on the phone's storage. */
    static volatile File LOG;
    static final long LOG_MAX=300_000;
    static void log(String what){
        try{android.util.Log.d("FactorHof",what);}catch(RuntimeException ignored){}   // not there in unit tests
        File f=LOG;if(f==null)return;
        synchronized(LOG_LOCK){
            try{if(f.length()>LOG_MAX){byte[] all=Files.readAllBytes(f.toPath());Files.write(f.toPath(),Arrays.copyOfRange(all,all.length/2,all.length));}
                String line=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.JAPAN).format(new Date())+" "+what+"\n";
                Files.write(f.toPath(),line.getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}catch(Exception ignored){}
        }
    }
    private static final Object LOG_LOCK=new Object();

    /** Which tab is open (0-2), or -1 when this is not the screen: one tab green, the other two white. */
    static int tabOf(Bitmap frame,int w,int h){
        int y=(int)(TAB_Y*h),green=-1;
        for(int i=0;i<3;i++){int c=frame.getPixel((int)(TAB_X[i]*w),y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            boolean isGreen=g>150&&g>r+40&&g>b+60,white=r>200&&g>200&&b>200;
            if(isGreen){if(green>=0)return -1;green=i;}else if(!white)return -1;}
        return green;
    }
    /** The scroll bar's thumb: {top, length} in screen pixels, or null without one (the list fits the screen). */
    static int[] thumb(Bitmap frame,int w,int h){
        int x=(int)(BAR_X*w),from=(int)(TRACK_TOP*h)-4,to=(int)(TRACK_BOTTOM*h)+4,top=-1,end=-1;
        for(int y=from;y<to;y++){int c=frame.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            boolean on=Math.abs(r-137)<16&&Math.abs(g-135)<16&&Math.abs(b-153)<16;
            if(on){if(top<0)top=y;end=y+1;}}
        return top<0||end-top<10?null:new int[]{top,end-top};
    }
    /**
     * The worker hands every frame here first. True while this screen is up: then nothing else reads it (its 継承
     * list looks like the factor list). wantsRecheck: the same frame is to be looked at again, to see it stood still.
     */
    synchronized boolean handle(Bitmap frame,int w,int h){
        wantsRecheck=false;
        int tab=tabOf(frame,w,h);
        place=null;
        if(tab<0){streak=0;return false;}
        if(++streak<2){wantsRecheck=true;return true;}
        lastSeen=System.currentTimeMillis();
        // Another horse (or the first): nothing is taken until its epithet, name and evaluation are read (identify).
        long look=lookHash(frame,w,h);
        if(look!=curLook){
            // The same new look twice before it counts: one frame mid-change is not another horse.
            if(look!=pendingLook){pendingLook=look;needsIdentity=false;wantsRecheck=true;return true;}
            needsIdentity=true;wantsRecheck=true;return true;}
        // No horse (one read as nothing, see unread; or let go from the 台帳): nothing is taken until another look.
        if(cur.key.isEmpty()||cur.saved||tab>=TAKEN)return true;
        Tab t=cur.tabs[tab];long now=System.currentTimeMillis();
        int[] th=thumb(frame,w,h);int trackTop=(int)(TRACK_TOP*h),track=(int)(TRACK_BOTTOM*h)-trackTop;
        double pos=th==null||track<=th[1]?0:Math.max(0,Math.min(1,(th[0]-trackTop)/(double)(track-th[1])));
        int bar=th==null?-1:th[0];boolean moved=tab!=placeTab||bar!=placeBar;
        if(moved){stillSince=now;placeTab=tab;placeBar=bar;}
        long still=now-stillSince;
        if(!t.begun){
            // The top at rest: pulled past it, the list springs back, and a list taken then was read a few rows low.
            // So the tab begins on a frame at the top that the bar has stood still on, with the list's first row
            // where it is at rest (18 down on the skill tab, the 因子 banner 26 on 継承).
            if(pos>.005){t.needsTop=true;return true;}
            t.needsTop=false;
            int first=firstRow(frame,w,h),rest=(int)Math.round((tab==0?18:26)*w/1080.0);
            if(still<150||first<0||Math.abs(first-rest)>3*w/1080.0+1)return true;
            t.begun=true;
            if(cur.dir!=null)try{Bitmap head=Bitmap.createBitmap(frame,(int)(LEFT*w),(int)(HEAD_TOP*h),(int)(RIGHT*w)-(int)(LEFT*w),(int)(HEAD_BOTTOM*h)-(int)(HEAD_TOP*h));
                Files.write(new File(cur.dir,"head"+tab+".webp").toPath(),Ui.webp(head));t.headH=head.getHeight();t.head=true;head.recycle();}catch(Exception ignored){}
            android.util.Log.d("FactorHof","tab"+tab+" begun");
        }
        place=new Place(tab,pos,th==null,still);
        // The tab's top (the horse, its stats and aptitudes, the tabs), taken again while its list scrolls: the finger
        // is on the list then, so no tap effect is over the top - the one from pressing the tab has long gone.
        if(moved&&t.head&&cur.dir!=null&&now-lastHead>=HEAD_MS){lastHead=now;
            try{Bitmap head=Bitmap.createBitmap(frame,(int)(LEFT*w),(int)(HEAD_TOP*h),(int)(RIGHT*w)-(int)(LEFT*w),(int)(HEAD_BOTTOM*h)-(int)(HEAD_TOP*h));
                Files.write(new File(cur.dir,"head"+tab+".webp").toPath(),Ui.webp(head));head.recycle();}catch(Exception ignored){}}
        return true;
    }
    synchronized boolean wantsRecheck(){return wantsRecheck;}
    private int debugCount;
    /**
     * The five stat numbers as pixels (coarsely): another horse shows other numbers. Only the numbers - the rank
     * badges beside them sparkle, and a hash over them changed every frame, so the horse seemed to change too.
     */
    static final double STAT_NUMBER_LEFT=.108,STAT_NUMBER_STEP=.19,STAT_NUMBER_WIDTH=.112;
    private static long lookHash(Bitmap frame,int w,int h){
        long hash=1125899906842597L;int y0=(int)(STATS_TOP*h),y1=(int)(STATS_BOTTOM*h);
        for(int k=0;k<5;k++){int x0=(int)((STAT_NUMBER_LEFT+STAT_NUMBER_STEP*k)*w),x1=x0+(int)(STAT_NUMBER_WIDTH*w);
            for(int y=y0;y<y1;y+=4)for(int x=x0;x<x1;x+=5){int c=frame.getPixel(x,y);hash=hash*31+(((c>>16)&255)+((c>>8)&255)+(c&255)>380?1:0);}}
        return hash;
    }
    synchronized boolean needsIdentity(){return needsIdentity;}
    /**
     * The horse on screen could not be read (no name, no evaluation): given up for this look. No horse is current, so
     * nothing of it is taken and nothing written into the horse before it; another look is identified afresh.
     */
    synchronized void unread(){cur=new Session();curLook=pendingLook;needsIdentity=false;log("殿堂入りの子を読めないので、この画面では撮らない");}
    /**
     * The horse on screen now, read by the caller (epithet, name, evaluation): with the stats and aptitudes it is the
     * key, so two of the same character - even with the same stats - are kept apart. Taken up from its folder when
     * it was seen before, any day.
     */
    synchronized void identify(String epithet,String name,String evaluation,String stats){
        // The epithet is left out of the key: it is read a little differently each time; the name, the evaluation and
        // the stats are enough to tell two horses apart. It is kept for the folder name.
        String key=Store.letters(name)+"|"+evaluation.replaceAll("[^0-9]","")+"|"+stats;
        if(!key.equals(cur.key)){cur=load(key);log("子を見分けた："+key+" → "+cur.dir.getName()+(cur.saved?"（保存済み）":"（未保存）"));
            if(name!=null&&!name.isEmpty()&&cur.name.isEmpty())cur.name=name;
            if(cur.epithet.isEmpty()&&epithet!=null)cur.epithet=epithet.replaceAll("[\\[\\]［］]","").trim();save(cur);}
        curLook=pendingLook;needsIdentity=false;
    }

    // ---- kept on the phone

    private File dirOf(String key){
        long h=1125899906842597L;for(char ch:key.toCharArray())h=31*h+ch;
        return new File(root==null?new File(System.getProperty("java.io.tmpdir")):root,Long.toHexString(h));
    }
    private Session load(String key){
        Session s=new Session();s.key=key;s.dir=dirOf(key);s.dir.mkdirs();
        try{JSONObject m=readMeta(s.dir);
            if(m==null){
                // Nothing reads, yet the folder has what a saved horse keeps: it is not taken for a new one (which
                // would write it over as not saved).
                if(new File(s.dir,"layout.json").exists()||thumb(s.dir)!=null){s.saved=true;s.savedAt=System.currentTimeMillis();log("記録が読めないが保存済みの中身があるので保存済みとして扱う："+s.dir.getName());}
                return s;}
            if(!key.equals(m.optString("key"))){log("フォルダの記録が別の子："+s.dir.getName()+" "+m.optString("key")+" ≠ "+key);return s;}
            // What was taken goes with the app (the list is built as it is read): a horse not saved is taken again.
            s.name=m.optString("name");s.epithet=m.optString("epithet");s.factors=factorsOf(m.optJSONArray("factors"));s.uris=strings(m.optJSONArray("uris"));s.saved=m.optBoolean("saved");s.sent=m.optBoolean("sent");s.savedAt=m.optLong("savedAt");
            s.aptitudes=aptitudesOf(m.optJSONObject("aptitudes"));s.uniqueLevel=m.optInt("uniqueLevel");
        }catch(Exception ignored){}
        return s;
    }
    /** Set by retake only: the one write that may take a saved horse back to not saved. */
    private boolean retaking;
    private void save(Session s){
        if(s.dir==null)return;
        try{s.touched=System.currentTimeMillis();
            // A saved horse stays saved, whatever this session thinks (read before it was, or not read at all): only a
            // retake takes it back. What was saved with it is kept too.
            if(!s.saved&&!retaking){JSONObject was=readMeta(s.dir);
                if(was!=null&&was.optBoolean("saved")&&s.key.equals(was.optString("key"))){
                    log("保存済みを未保存で書こうとしたので止めた："+s.dir.getName()+" "+was.optString("name"));
                    s.saved=true;s.sent=was.optBoolean("sent");s.savedAt=was.optLong("savedAt");
                    if(s.uris.isEmpty())s.uris=strings(was.optJSONArray("uris"));if(s.factors.isEmpty())s.factors=factorsOf(was.optJSONArray("factors"));}}
            JSONObject m=new JSONObject().put("key",s.key).put("name",s.name).put("epithet",s.epithet).put("factors",json(s.factors)).put("uris",new JSONArray(s.uris)).put("saved",s.saved).put("sent",s.sent).put("savedAt",s.savedAt).put("touched",s.touched);
            JSONObject a=new JSONObject();for(var e:s.aptitudes.entrySet())a.put(e.getKey(),e.getValue());m.put("aptitudes",a).put("uniqueLevel",s.uniqueLevel);
            writeMeta(s.dir,m);
        }catch(Exception e){log("記録を書けず："+s.dir.getName()+" "+e);}
    }
    private static File[] files(File d){File[] f=d.listFiles();return f==null?new File[0]:f;}
    /**
     * Names set right afterwards (the list of horses came after the horse was taken): each horse kept whose name or
     * epithet canon gives otherwise gets it, with its key and folder, and its images in the gallery go to the folder
     * of the right name. canon: {epithet, name} to {epithet, name}.
     */
    synchronized void fixNames(Context c,java.util.function.UnaryOperator<String[]> canon){
        if(root==null)init(c);
        File[] dirs=root.listFiles();if(dirs==null)return;
        for(File d:dirs){
            // The horse being taken keeps its key until it is saved: its images are being made under that key
            // (HofCompose), and renamed now they would be lost. It is renamed once saved (saveHallOfFame).
            if(d.equals(cur.dir)&&!cur.saved)continue;
            try{JSONObject m=readMeta(d);if(m==null)continue;
                String name=m.optString("name"),ep=m.optString("epithet"),key=m.optString("key");if(name.isEmpty())continue;
                String[] to=canon.apply(new String[]{ep,name});if(to[0].equals(ep)&&to[1].equals(name))continue;
                int bar=key.indexOf('|');String newKey=bar<0?key:Store.letters(to[1])+key.substring(bar);
                File target=newKey.equals(key)?d:dirOf(newKey);
                if(!target.equals(d)&&(target.exists()||!d.renameTo(target)))continue;   // kept already under the right name
                moveGallery(c,strings(m.optJSONArray("uris")),name,ep,to[1],to[0]);
                m.put("key",newKey).put("name",to[1]).put("epithet",to[0]);
                writeMeta(target,m);
                if(d.equals(cur.dir)){cur.key=newKey;cur.dir=target;cur.name=to[1];cur.epithet=to[0];}
                log("名前を付け直した："+name+" "+ep+" → "+to[1]+" "+to[0]+"（"+d.getName()+" → "+target.getName()+"、保存済み="+m.optBoolean("saved")+"）");
            }catch(Exception ignored){}
        }
    }
    /** The saved images moved from the old name's folder to the new one's (the app's own images: no permission needed). */
    private static void moveGallery(Context c,List<String> uris,String oldName,String oldEp,String name,String ep){
        String from="/"+clean(oldName)+"/"+clean(oldEp.isEmpty()?"称号不明":oldEp)+"/",to="/"+clean(name)+"/"+clean(ep.isEmpty()?"称号不明":ep)+"/";
        var r=c.getContentResolver();String column=android.provider.MediaStore.Images.Media.RELATIVE_PATH;
        for(String u:uris){if(u==null||u.isEmpty()||u.equals("null"))continue;
            try{android.net.Uri uri=android.net.Uri.parse(u);
                try(var q=r.query(uri,new String[]{column},null,null,null)){
                    if(q==null||!q.moveToFirst())continue;String path=q.getString(0);if(path==null||!path.contains(from))continue;
                    android.content.ContentValues v=new android.content.ContentValues();v.put(column,path.replace(from,to));r.update(uri,v,null,null);}
            }catch(Exception e){android.util.Log.d("FactorHof","gallery move: "+e);}
        }
    }
    private static void delete(File d){for(File f:files(d))f.delete();d.delete();}
    /**
     * Drops what older versions took (pieces, grey copies). The tabs' tops stay, with the lists (layout.json): the
     * images can be made again from them (CaptureService.remakeHallOfFame).
     */
    private static void dropTaken(Session s){
        if(s.dir==null)return;
        for(File f:files(s.dir)){String n=f.getName();if(n.startsWith("piece")||n.startsWith("gray"))f.delete();}
    }
    /** What is kept of a horse for good: its record, its tabs' tops and lists, a small picture of its stats. */
    static boolean kept(String name){return name.equals("meta.json")||name.equals("meta.bak")||name.equals("layout.json")||name.startsWith("head")||name.startsWith("thumb.");}
    /** This horse's folder (null before one is identified). */
    synchronized File dir(){return cur.dir;}

    /** The first row of the list (down x 500 of 1052) off the grey background; -1 with none near its top. */
    static int firstRow(Bitmap frame,int w,int h){
        int x=(int)(LEFT*w)+(int)(500*(RIGHT-LEFT)*w/1052.0),y0=(int)(LIST_TOP*h);
        for(int y=2;y<200*w/1080;y++){int c=frame.getPixel(x,y0+y);
            if(Math.abs(((c>>16)&255)-242)+Math.abs(((c>>8)&255)-242)+Math.abs((c&255)-242)>=15)return y;}
        return -1;
    }
    /**
     * A frame on a tab begun: its tab, the scroll bar's place (0-1; noBar: the list fits the screen), and how long
     * the bar has stood where it is (ms).
     */
    record Place(int tab,double pos,boolean noBar,long stillMs) {}
    private Place place;private int placeTab=-1,placeBar=-2;private long stillSince,lastHead;
    static final long HEAD_MS=300;
    /** The place of the frame last handled; null when it is not on the skill or 継承 list, or the tab is not begun. */
    synchronized Place place(){return place;}
    /** A frame at place p was laid over its tab's list (HofCompose): the list has come down that far, to the end at the bottom. */
    synchronized void reached(Place p){
        if(p==null||p.tab()>=TAKEN||cur.saved)return;Tab t=cur.tabs[p.tab()];
        boolean more=p.pos()>t.reached+.001||!t.whole&&(p.noBar()||p.pos()>.995);
        t.reached=Math.max(t.reached,p.pos());if(p.noBar()||p.pos()>.995)t.whole=true;
        if(more)android.util.Log.d("FactorHof","tab"+p.tab()+" "+t.percent()+"%");
    }


    /** Each tab's share seen so far, 0-100 (a saved horse is whole). */
    /** The tabs taken: スキル and 継承 (育成情報 is not). */
    static final int TAKEN=2;
    synchronized int[] percents(){return cur.saved?new int[]{100,100}:new int[]{cur.tabs[0].percent(),cur.tabs[1].percent()};}
    synchronized boolean complete(){for(int i=0;i<TAKEN;i++){Tab t=cur.tabs[i];if(t.percent()<100||!t.head)return false;}return true;}
    /** On screen lately, or saved and not sent yet: the result window shows this instead of the results. */
    synchronized boolean active(){long now=System.currentTimeMillis();return now-lastSeen<4000||cur.saved&&!cur.sent&&now-cur.savedAt<10*60*1000;}
    synchronized String name(){return cur.name;}
    /** How tall the copied top of tab i is in its image (where its list starts). */
    synchronized int headHeight(int i){return cur.tabs[i].headH;}
    /**
     * The gallery folder of this horse, under 殿堂入り: キャラ名 / 称号 / 評価点 and when it was saved, so it is found by
     * walking down from the character - キタサンブラック/真打・慶鶴之志/39124_20261001_1530.
     */
    synchronized String folder(String stamp){
        return "殿堂入り/"+clean(cur.name.isEmpty()?"ウマ娘":cur.name)+"/"+clean(cur.epithet.isEmpty()?"称号不明":cur.epithet)+"/"
            +clean((evaluation().isEmpty()?"評価点不明":evaluation())+"_"+stamp);
    }
    private static String clean(String s){return s.replaceAll("[\\\\/:*?\"<>|]","_").trim();}
    /** The evaluation as read (digits), or "". */
    synchronized String evaluation(){String[] k=cur.key.split("[|]");return k.length>1?k[1]:"";}
    synchronized boolean saved(){return cur.saved;}
    /** The horse's aptitudes (all ten) and its unique skill's level, for the チャンミ判定: read once, kept with it. */
    synchronized boolean needsAptitudes(){return cur.dir!=null&&cur.aptitudes.size()<10;}
    synchronized boolean needsLevel(){return cur.dir!=null&&cur.uniqueLevel<=0;}
    synchronized void aptitudes(Map<String,String> read){if(cur.dir==null||read==null||read.size()<10||read.equals(cur.aptitudes))return;cur.aptitudes=new LinkedHashMap<>(read);save(cur);}
    synchronized void uniqueLevel(int level){if(cur.dir==null||level<1||level>6||level==cur.uniqueLevel)return;cur.uniqueLevel=level;save(cur);}
    static Map<String,String> aptitudesOf(JSONObject o){Map<String,String> out=new LinkedHashMap<>();
        if(o!=null)for(Iterator<String> it=o.keys();it.hasNext();){String k=it.next();out.put(k,o.optString(k));}return out;}
    synchronized boolean sent(){return cur.sent;}
    /** Sent: its images go, its record stays (so it is still known as saved). */
    synchronized void markSent(){cur.sent=true;if(cur.dir!=null)for(File f:files(cur.dir))if(f.getName().startsWith("saved"))f.delete();save(cur);}
    /**
     * The horse in dir sent - the one on screen when sending began. Another may be on screen by the time the sending
     * ends: that one is not touched, and the sent one's record is changed in its folder.
     */
    synchronized void markSent(File dir){
        if(dir==null)return;
        if(dir.equals(cur.dir)){markSent();return;}
        for(File f:files(dir))if(f.getName().startsWith("saved"))f.delete();
        try{JSONObject m=readMeta(dir);if(m!=null)writeMeta(dir,m.put("sent",true));}catch(Exception e){log("送信済みにできず："+e.getClass().getSimpleName());}
    }
    /** The saved images, read back from the horse's folder (null where a tab had none). */
    synchronized List<byte[]> savedImages(){
        List<byte[]> out=new ArrayList<>();
        for(int i=0;i<TAKEN;i++){File f=cur.dir==null?null:saved(cur.dir,i);try{out.add(f!=null?Files.readAllBytes(f.toPath()):null);}catch(Exception e){out.add(null);}}
        return out;
    }
    /**
     * A horse let go from the 台帳: its folder goes; when it is the horse on screen, that one is let go too and
     * identified again from the screen (taken afresh, as the 台帳 says), rather than written into a folder that is gone.
     */
    /**
     * The card book parts kept for this horse alone - its rainbow unique skill (sr|…|key) and its icons (hi|key|…),
     * named after its key - from its kept lists. The parts any horse shares stay.
     */
    static List<String> ownParts(File dir){
        Set<String> out=new LinkedHashSet<>();
        JSONObject m=readMeta(dir);String key=m==null?"":m.optString("key");if(key.isEmpty())return List.of();
        try{collectKeys(new JSONObject(new String(Files.readAllBytes(new File(dir,"layout.json").toPath()),StandardCharsets.UTF_8)),key,out);}catch(Exception ignored){}
        return List.copyOf(out);
    }
    private static void collectKeys(Object o,String horse,Set<String> out){
        if(o instanceof JSONObject j){for(Iterator<String> i=j.keys();i.hasNext();){String k=i.next();Object v=j.opt(k);
            if(k.equals("key")&&v instanceof String s&&(s.startsWith("sr|")&&s.endsWith("|"+horse)||s.startsWith("hi|"+horse+"|")))out.add(s);
            else collectKeys(v,horse,out);}}
        else if(o instanceof JSONArray a)for(int i=0;i<a.length();i++)collectKeys(a.opt(i),horse,out);
    }
    synchronized void forget(File dir){
        synchronized(META_LOCK){for(File f:files(dir))f.delete();dir.delete();}
        if(cur.dir!=null&&cur.dir.equals(dir)){cur=new Session();curLook=0;pendingLook=0;needsIdentity=false;log("撮影中の子が台帳から消されたので、見分け直す："+dir.getName());}
    }
    /** Starts this horse over: its tabs are taken again from nothing. */
    synchronized void retake(){cur.tabs=newTabs();cur.saved=false;cur.sent=false;if(cur.dir!=null){log("撮り直す："+cur.dir.getName()+" "+cur.name);for(File f:files(cur.dir)){String n=f.getName();if(!n.equals("meta.json")&&!n.equals("meta.bak"))f.delete();}}
        retaking=true;try{save(cur);}finally{retaking=false;}}

    /** The top of tab i's image (the dialog over its list), as last taken; null with none. */
    synchronized Bitmap headBitmap(int i){
        if(cur.dir==null||!cur.tabs[i].head)return null;
        try{File f=head(cur.dir,i);if(f==null)return null;byte[] b=Files.readAllBytes(f.toPath());return BitmapFactory.decodeByteArray(b,0,b.length);}catch(Exception e){return null;}
    }
    /** A horse's files kept as WebP now, as PNG or JPEG before: the one there, or null. */
    static File head(File dir,int tab){return either(dir,"head"+tab,".webp",".jpg");}
    static File saved(File dir,int tab){return either(dir,"saved"+tab,".webp",".png");}
    static File thumb(File dir){return either(dir,"thumb",".webp",".jpg");}
    private static File either(File dir,String base,String now,String before){File a=new File(dir,base+now);if(a.exists())return a;File b=new File(dir,base+before);return b.exists()?b:null;}
    synchronized String sessionKey(){return cur.key;}
    /** Saved to the gallery (where: uris): the images are kept with the record, a small picture of the stats for the list, what was taken goes. */
    synchronized void markSaved(List<byte[]> images,List<String> uris,List<Factor> factors){
        cur.saved=true;cur.savedAt=System.currentTimeMillis();cur.uris=List.copyOf(uris);cur.factors=List.copyOf(factors);
        log("保存した："+(cur.dir==null?"（フォルダなし）":cur.dir.getName())+" "+cur.name+" "+cur.key);
        if(cur.dir!=null&&images.get(0)!=null)try{
            // The stats and aptitudes, from the top of the first image (the dialog's top is 258-1072 of 2340 on screen).
            var o=new BitmapFactory.Options();o.inSampleSize=2;Bitmap all=BitmapFactory.decodeByteArray(images.get(0),0,images.get(0).length,o);
            int top=(int)((560-258)*all.getWidth()/1052.0),bottom=(int)((1000-258)*all.getWidth()/1052.0);
            Bitmap t=Bitmap.createBitmap(all,0,top,all.getWidth(),Math.min(all.getHeight(),bottom)-top);all.recycle();
            Bitmap small=Bitmap.createScaledBitmap(t,360,360*t.getHeight()/t.getWidth(),true);t.recycle();
            Files.write(new File(cur.dir,"thumb.webp").toPath(),Ui.webp(small));small.recycle();
        }catch(Exception ignored){}
        if(cur.dir!=null){for(int i=0;i<images.size();i++)if(images.get(i)!=null)try{Files.write(new File(cur.dir,"saved"+i+".webp").toPath(),images.get(i));}catch(Exception ignored){}
            dropTaken(cur);}
        save(cur);
    }

    // ---- the factors of the 継承 image, for searching (HallOfFameActivity)

    /** One factor of the horse (who 0) or of the parent it came from (who 1, 2), with its stars. */
    record Factor(String name,int stars,int who) {}
    static JSONArray json(List<Factor> fs){JSONArray a=new JSONArray();for(Factor f:fs)a.put(new JSONArray().put(f.name()).put(f.stars()).put(f.who()));return a;}
    static List<Factor> factorsOf(JSONArray a){List<Factor> out=new ArrayList<>();
        for(int i=0;a!=null&&i<a.length();i++){JSONArray f=a.optJSONArray(i);if(f!=null)out.add(new Factor(f.optString(0),f.optInt(1),f.optInt(2)));}return out;}
    static List<String> strings(JSONArray a){List<String> out=new ArrayList<>();for(int i=0;a!=null&&i<a.length();i++)out.add(a.optString(i));return out;}
    /** The 継承 image's chips: two columns, three star places under each name (shares of its width, from the S25). */
    static final double[] FACTOR_COLUMNS={396/1052.0,811/1052.0};
    static final double STAR_STEP=36/1052.0,TEXT_LEFT=50/1052.0,TEXT_RIGHT=210/1052.0;
    static boolean gold(int c){int r=(c>>16)&255,g=(c>>8)&255,b=c&255;return r>235&&g>190&&b<130;}
    /**
     * The chips of the 継承 image: {column, y of its stars, stars}, found by the first star place, which is gold on
     * every chip (★1 at least). from: where the list starts (below the copied top).
     */
    static List<int[]> chips(Bitmap image,int from){
        List<int[]> out=new ArrayList<>();int w=image.getWidth(),h=image.getHeight(),step=(int)Math.round(STAR_STEP*w);
        for(int col=0;col<2;col++){int cx=(int)Math.round(FACTOR_COLUMNS[col]*w),x=cx-step,start=-1;
            for(int y=from;y<h;y++){boolean on=gold(image.getPixel(x,y));
                if(on&&start<0)start=y;
                if(!on&&start>=0){int mid=(start+y)/2;if(y-start>=8){int n=0;for(int k=-1;k<=1;k++)if(gold(image.getPixel(cx+k*step,mid)))n++;out.add(new int[]{col,mid,n});}start=-1;}}}
        out.sort(Comparator.comparingInt((int[] c)->c[1]).thenComparingInt(c->c[0]));
        return out;
    }
    /** The result window's text: whose, and each tab's share (green when whole, yellow while under way). */
    synchronized String summary(){
        int[] p=percents();String name=cur.name;boolean saved=cur.saved,sent=cur.sent;
        StringBuilder s=new StringBuilder(saved?"殿堂入りウマ娘を保存しました":"殿堂入りウマ娘を保存中");
        if(!name.isEmpty())s.append("（").append(name).append("）");s.append('\n');
        for(int i=0;i<TAKEN;i++){String part=(i>0?"　":"")+TABS[i]+" "+(cur.tabs[i].needsTop&&p[i]==0?"↑一番上から":p[i]+"%");char level=p[i]>=100?'3':p[i]>0?'2':0;
            for(char ch:part.toCharArray())if(level!=0&&ch!='　')s.append(CaptureService.MARK).append(level).append(ch);else s.append(ch);}
        if(saved)s.append('\n').append("ギャラリーに保存済み");
        return s.toString();
    }
}
