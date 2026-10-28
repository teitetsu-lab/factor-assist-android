package jp.factor.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clean card parts kept across horses, for the 殿堂入り images (HofCompose) and the factor mode's (FactorCompose): of
 * each kind of card one common card - one taken, its letters (and icon, stars) filled in with the card's own colour -
 * and of each name only its letters, and a skill's icon, a chip's stars; an image is the common card with those put on
 * it where they go. Each part keeps a few takes, from screens that stood still (collect). A part is clean once a take
 * was made while the list had looked the same for SETTLE_MS (nothing on it moved: no effect over it), or RUN takes of
 * different frames in a row agree; a part of a card that never stands still itself (gold, rainbow) after MOST takes
 * the one nearest what most of them show. The newest clean take is the one used. What the machine takes for clean can
 * still be dirty: discard throws a part away (the 殿堂入り screen's カードを確認), and it is taken again.
 */
final class CardBook {
    private CardBook() {}
    /**
     * A part in an image: what it is (its key), where, what of it is not its own (another part's place - left out
     * when takes are compared), and for a common card how that is filled in (else it is painted MASKED).
     */
    record Card(String key,Rect box,List<Rect> mask,Blank blank) {
        Card(String key,Rect box){this(key,box,List.of(),null);}
        Card(String key,Rect box,List<Rect> mask){this(key,box,mask,null);}
    }
    /**
     * How a common card is made of a take: its mask filled with the card's own colour, each column as it is on the
     * rows over and under the letters (and the ball or icon left of them) - nothing is drawn there on a card of any
     * colour - between them in step from the one to the other; under -1: as over (where the stars hang under the
     * letters). Rows within the card. Measured on the S25: 4 at most off a chip's colour.
     */
    record Blank(int over,int under) {}
    /** What a mask is painted with when it is not filled in. */
    static final int MASKED=0xffff00ff;
    /** A card as framed on the screen, and the parts it is kept as. */
    record Shown(Rect box,List<Card> parts) {}
    /** Takes kept of a part once the images it went into are saved (see prune); until then every take is kept. */
    static final int KEEP=3;
    /** How far a take may sit off the card's place (pixels). */
    static final int SLIDE=6;
    /** Close enough, per sampled pixel (brightness): to tell a card is the one kept, and for two takes to agree. */
    static final int NEAR=24,AGREE=16;
    /** What is known of a card: nothing kept, kept but none known clean, clean. */
    static final int NONE=0,DIRTY=1,CLEAN=2;
    /** The list looked the same, frame to frame, for this long: nothing on it moved, so nothing is over the cards (ms). */
    static final long SETTLE_MS=300;

    /** A name's letters: each take as wide as they were found on it. */
    static boolean free(String key){return key.startsWith("Ft|")||key.startsWith("ft|")||key.startsWith("st|");}
    /** A card that is never still itself: a gold card's sheen, the rainbow of a horse's own skill. */
    static boolean shimmers(String key){return key.startsWith("sr|")||key.endsWith("|gold")||key.contains("|gold|");}

    static File dir(Context c){return new File(c.getFilesDir(),"cardbook");}
    static File dir(Context c,String key)throws Exception{return new File(dir(c),hash(key));}
    private static final Map<String,Integer> states=new ConcurrentHashMap<>();
    /** The frame each part was last taken from, and whether it had settled: the same frame read again is not another take. */
    private static final Map<String,Long> lastFrame=new ConcurrentHashMap<>();
    private static final Map<String,Boolean> lastSettled=new ConcurrentHashMap<>();
    /** Takes kept of a part at the most, the newest. */
    static final int LIMIT=8;

    /** A take and whether it was of a frame that had settled. */
    record Take(Bitmap bitmap,File file,boolean settled) {}

    /** What is known of a card (NONE, DIRTY, CLEAN); w and h its size (w -1 for a name's letters). */
    static int state(Context c,String key,int w,int h){
        Integer known=states.get(key);if(known!=null)return known;
        int s=NONE;
        try{List<Take> t=load(dir(c,key),w,h);try{s=t.isEmpty()?NONE:clean(t,shimmers(key))>=0?CLEAN:DIRTY;}finally{recycle(t);}}
        catch(Exception ignored){}
        states.put(key,s);return s;
    }
    /** A shown card's: the least of its parts' (a chip whose name is clean but whose stars are not yet is not clean). */
    static int state(Context c,Shown shown){
        int s=CLEAN;for(Card p:shown.parts())s=Math.min(s,state(c,p.key(),free(p.key())?-1:p.box().width(),p.box().height()));return s;
    }

    /**
     * Takes from a screen that stands still (list: the list as on the screen, the cards' boxes in it; frame: which
     * frame it is - the same one read again gives nothing; settled: the list had looked the same for SETTLE_MS): each part
     * not yet clean gets this take, set where its newest take sits. None is thrown away here (see prune). How many
     * parts got one.
     */
    static int collect(Context c,Bitmap list,List<Shown> shown,long frame,boolean settled){
        int lw=list.getWidth(),lh=list.getHeight(),added=0;
        for(Shown s:shown)for(Card card:s.parts()){
            boolean free=free(card.key());
            Rect b=card.box();
            try{
                // A clean part is taken again off a settled frame only: taken again, the newest is used.
                int was=state(c,card.key(),free?-1:b.width(),b.height());if(was==CLEAN&&!settled)continue;
                // The same frame read again is no other take - unless it has settled since (then it is clean by itself).
                Long last=lastFrame.get(card.key());if(last!=null&&last==frame&&(!settled||Boolean.TRUE.equals(lastSettled.get(card.key()))))continue;
                File d=dir(c,card.key());List<Take> takes=load(d,free?-1:b.width(),b.height());
                try{
                    // Wholly on the screen (a part may touch its edge - an icon or the banner does; align keeps within it).
                    if(b.left<0||b.top<0||b.right>lw||b.bottom>lh||b.width()<8||b.height()<8)continue;
                    // Set where the newest take sits: the later a take, the likelier it is right.
                    int[] at={b.left,b.top};
                    if(!takes.isEmpty()){at=align(list,b,takes.get(takes.size()-1).bitmap(),card.mask());if(at==null)continue;}
                    Bitmap now=Bitmap.createBitmap(list,at[0],at[1],b.width(),b.height()).copy(Bitmap.Config.ARGB_8888,true);
                    if(!card.mask().isEmpty()){
                        List<Rect> rel=new ArrayList<>();for(Rect m:card.mask())rel.add(new Rect(m.left-b.left,m.top-b.top,m.right-b.left,m.bottom-b.top));
                        if(card.blank()!=null){if(!fill(now,rel,card.blank())){now.recycle();continue;}}
                        else{Canvas cv=new Canvas(now);android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(MASKED);for(Rect m:rel)cv.drawRect(m,paint);}
                    }
                    d.mkdirs();write(new File(d,(settled?"s":"k")+System.currentTimeMillis()+".webp"),now);now.recycle();
                    Files.write(new File(d,"key.txt").toPath(),card.key().getBytes(StandardCharsets.UTF_8));added++;
                    lastFrame.put(card.key(),frame);lastSettled.put(card.key(),settled);
                    // Only the newest few are kept: the later a take, the likelier it is right.
                    File[] fs=files(d);for(int i=0;i<fs.length-LIMIT;i++)fs[i].delete();
                }finally{recycle(takes);}
                // Clean before, clean still (a settled take is clean by itself): only the take used changes.
                if(was!=CLEAN)states.remove(card.key());
            }catch(Exception e){android.util.Log.d("FactorHof","collect "+card.key()+": "+e);}
        }
        if(added>0)android.util.Log.d("FactorHof","collected "+added+(settled?" (settled)":""));
        return added;
    }

    /** Fills a take's rects (within it) with the card's own colour (see Blank): false when its rows are not on it. */
    static boolean fill(Bitmap now,List<Rect> rects,Blank bl){
        int w=now.getWidth(),h=now.getHeight();int[] px=new int[w*h];now.getPixels(px,0,w,0,0,w,h);
        int[][] r=new int[rects.size()][];for(int i=0;i<r.length;i++){Rect m=rects.get(i);r[i]=new int[]{m.left,m.top,m.right,m.bottom};}
        if(!fill(px,w,h,r,bl))return false;
        now.setPixels(px,0,w,0,0,w,h);return true;
    }
    /** The same on pixels (ARGB, w×h), rects as {left, top, right, bottom}. */
    static boolean fill(int[] px,int w,int h,int[][] rects,Blank bl){
        if(bl.over()<0||bl.over()>=h||bl.under()>=h)return false;
        int[] over=Arrays.copyOfRange(px,bl.over()*w,bl.over()*w+w),under=bl.under()<0?over:Arrays.copyOfRange(px,bl.under()*w,bl.under()*w+w);
        double span=Math.max(1,bl.under()-bl.over());
        for(int[] m:rects)for(int y=Math.max(0,m[1]);y<Math.min(h,m[3]);y++){
            double f=bl.under()<0?0:Math.max(0,Math.min(1,(y-bl.over())/span));
            for(int x=Math.max(0,m[0]);x<Math.min(w,m[2]);x++){int a=over[x],b=under[x],out=0xff000000;
                for(int ch=0;ch<3;ch++){int sh=ch*8;out|=(int)Math.round(((a>>sh)&255)*(1-f)+((b>>sh)&255)*f)<<sh;}
                px[y*w+x]=out;}
        }
        return true;
    }
    /**
     * Where a card's letters end, in image: across left..right of rows top..bottom, a column with two pixels or more
     * off the card's colour - as it is on row ref (one with nothing on it) and the row's own shift from that - by over
     * LETTER (summed RGB). The red 指定 badge after a factor's name is not a letter: they end before it. {end of the
     * letters, end of the badge or the letters}; null with no letters.
     */
    static int[] letters(Bitmap image,int left,int top,int right,int bottom,int ref){
        int w=right-left,h=bottom-top;if(w<=0||h<=0||left<0||top<0||right>image.getWidth()||bottom>image.getHeight()||ref<0||ref>=image.getHeight())return null;
        int[] px=new int[w*h],r=new int[w];image.getPixels(px,0,w,left,top,w,h);image.getPixels(r,0,w,left,ref,w,1);
        int[] at=letters(px,r,w,h);return at==null?null:new int[]{left+at[0],left+at[1]};
    }
    /** The same on pixels: the rows looked across (ARGB, w×h) and the row with nothing on it; within them. */
    static int[] letters(int[] px,int[] r,int w,int h){
        int[] count=new int[w],red=new int[w];int[] d=new int[w];
        for(int y=0;y<h;y++){
            int[] v=new int[3];for(int ch=0;ch<3;ch++){int sh=ch*8;for(int x=0;x<w;x++)d[x]=((px[y*w+x]>>sh)&255)-((r[x]>>sh)&255);int[] s=d.clone();Arrays.sort(s);v[ch]=s[w/2];}
            for(int x=0;x<w;x++){int p=px[y*w+x],q=r[x],off=0;
                for(int ch=0;ch<3;ch++){int sh=ch*8;off+=Math.abs(((p>>sh)&255)-((q>>sh)&255)-v[ch]);}
                if(off>LETTER)count[x]++;
                if(((p>>16)&255)>235&&((p>>8)&255)<100&&(p&255)<150)red[x]++;}
        }
        int badge=-1,badgeEnd=-1;for(int x=0;x<w;x++)if(red[x]>2){if(badge<0)badge=x;badgeEnd=x+1;}
        int end=-1;for(int x=0;x<w;x++)if(count[x]>=2&&(badge<0||x<badge-8))end=x+1;
        if(end<0)return null;
        return new int[]{end,Math.max(end,badgeEnd)};
    }
    static final int LETTER=100;

    /**
     * After the images are saved: of each part they were made of, the takes that make it clean are kept - the
     * settled one, the RUN that agree, or the KEEP nearest what most show - and the rest go.
     */
    static void prune(Context c,Collection<Card> parts){
        Set<String> done=new HashSet<>();
        for(Card p:parts){if(!done.add(p.key()))continue;Rect b=p.box();
            try{List<Take> t=load(dir(c,p.key()),free(p.key())?-1:b.width(),b.height());
                try{Set<Integer> keep=keep(t,shimmers(p.key()));
                    if(!keep.isEmpty())for(int i=0;i<t.size();i++)if(!keep.contains(i))t.get(i).file().delete();}
                finally{recycle(t);}
                states.remove(p.key());
            }catch(Exception e){android.util.Log.d("FactorHof","prune "+p.key()+": "+e);}
        }
    }
    private static Set<Integer> keep(List<Take> t,boolean shimmer){
        Set<Integer> keep=new HashSet<>();
        for(int i=t.size()-1;i>=0&&keep.isEmpty();i--)if(t.get(i).settled())keep.add(i);
        if(keep.isEmpty()){int run=run(t);if(run>=0)for(int i=run-RUN+1;i<=run;i++)keep.add(i);}
        // A part that never stands still is clean only with MOST takes (clean): fewer kept, it would not be clean
        // again, and an image made again (remakeHallOfFame) would leave it out.
        if(keep.isEmpty()&&shimmer&&t.size()>MOST){long[] off=offMiddle(bitmaps(t));Integer[] order=new Integer[t.size()];for(int i=0;i<order.length;i++)order[i]=i;
            Arrays.sort(order,Comparator.comparingLong(i->off[i]));for(int i=0;i<MOST;i++)keep.add(order[i]);}
        return keep;
    }
    /** The clean take of a part, to draw (the caller recycles it); null while it has none. w -1 for a name's letters. */
    static Bitmap cleanTake(Context c,String key,int w,int h){
        try{List<Take> t=load(dir(c,key),w,h);
            try{int i=clean(t,shimmers(key));return i<0?null:t.get(i).bitmap().copy(Bitmap.Config.ARGB_8888,false);}finally{recycle(t);}}
        catch(Exception e){return null;}
    }
    /** The file of the clean take of a part (what an image was made of); null while it has none. */
    static File cleanFile(Context c,String key,int w,int h){
        try{List<Take> t=load(dir(c,key),w,h);
            try{int i=clean(t,shimmers(key));return i<0?null:t.get(i).file();}finally{recycle(t);}}
        catch(Exception e){return null;}
    }
    /** Throws a part away, every take of it: it is taken again the next time it is on screen. */
    static void discard(Context c,String key){
        try{File d=dir(c,key);File[] fs=d.listFiles();if(fs!=null)for(File f:fs)f.delete();d.delete();}catch(Exception ignored){}
        states.remove(key);lastFrame.remove(key);lastSettled.remove(key);
    }

    /** Takes in a row that must agree for a part to be clean; takes after which one that never stands still (a gold or rainbow card) is taken by the most. */
    static final int RUN=3,MOST=6;
    /**
     * The clean take, the newest that is: the later a take, the likelier it is right. One of a frame that had settled
     * (nothing on the screen moved after it, so no effect was over it); else the last of RUN takes in a row - each of
     * another frame - that all agree (tap effects move all the time they are there). A part that is never still itself
     * (shimmers) is, after MOST takes, the one nearest what most of them show. -1 while there is none.
     */
    static int clean(List<Take> takes,boolean shimmer){
        for(int i=takes.size()-1;i>=0;i--)if(takes.get(i).settled())return i;
        int run=run(takes);if(run>=0)return run;
        if(!shimmer||takes.size()<MOST)return -1;
        long[] off=offMiddle(bitmaps(takes));int best=off.length-1;for(int i=off.length-2;i>=0;i--)if(off[i]<off[best])best=i;return best;
    }
    /** The last of the newest RUN takes in a row that agree; -1 when none do. */
    private static int run(List<Take> takes){
        for(int i=takes.size()-RUN;i>=0;i--){boolean all=true;
            for(int a=i;a<i+RUN&&all;a++)for(int b=a+1;b<i+RUN&&all;b++)if(!agree(takes.get(a).bitmap(),takes.get(b).bitmap()))all=false;
            if(all)return i+RUN-1;}
        return -1;
    }
    /** Two takes the same but for a shade or two here and there (a list set a pixel apart is drawn a little differently). */
    static boolean agree(Bitmap a,Bitmap b){
        if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())return false;
        int[] x=sample(a,0,0,a.getWidth(),a.getHeight()),y=sample(b,0,0,b.getWidth(),b.getHeight());int n=0;
        for(int i=0;i<x.length;i++)if(Math.abs(x[i]-y[i])<AGREE)n++;return n>=.95*x.length;
    }

    /** The takes' files, oldest first. */
    private static File[] files(File d){
        File[] fs=d.listFiles((x,n)->(n.startsWith("c")||n.startsWith("k")||n.startsWith("s"))&&(n.endsWith(".webp")||n.endsWith(".png")));if(fs==null)return new File[0];
        Arrays.sort(fs,Comparator.comparingLong(File::lastModified).thenComparing(File::getName));return fs;
    }
    /**
     * The takes kept of this size, oldest first (w -1: any width - a name's letters, as wide as each was found); one
     * that will not open is dropped, one of another size is left as it is.
     */
    private static List<Take> load(File d,int w,int h){
        List<Take> out=new ArrayList<>();
        String key="";try{File k=new File(d,"key.txt");if(k.isFile())key=new String(Files.readAllBytes(k.toPath()),StandardCharsets.UTF_8);}catch(Exception ignored){}
        for(File f:files(d)){Bitmap b=BitmapFactory.decodeFile(f.getPath());if(b==null){f.delete();continue;}
            if(w>=0&&b.getWidth()!=w||b.getHeight()!=h){b.recycle();continue;}
            // A plain factor part taken off a card with a trainer ability (see FactorCompose.tainted).
            out.add(new Take(b,f,f.getName().startsWith("s")));}
        return out;
    }
    private static List<Bitmap> bitmaps(List<Take> t){List<Bitmap> out=new ArrayList<>();for(Take x:t)out.add(x.bitmap());return out;}
    private static void recycle(List<Take> t){for(Take x:t)x.bitmap().recycle();}
    private static void write(File f,Bitmap b)throws Exception{
        Files.write(f.toPath(),Ui.webp(b));
    }
    static String hash(String key)throws Exception{
        byte[] h=MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();
        for(int i=0;i<10;i++)s.append(String.format(Locale.ROOT,"%02x",h[i]));return s.toString();
    }
    private static int lum(int c){return (((c>>16)&255)*3+((c>>8)&255)*6+(c&255))/10;}
    /** Brightness, sampled every third pixel. */
    private static int[] sample(Bitmap b,int x,int y,int w,int h){
        int[] px=new int[w*h];b.getPixels(px,0,w,x,y,w,h);int[] out=new int[((w+2)/3)*((h+2)/3)];int n=0;
        for(int j=0;j<h;j+=3)for(int i=0;i<w;i+=3)out[n++]=lum(px[j*w+i]);return out;
    }
    /**
     * Where in the image (near box) the kept take fits best, its mask left out - over the width both have (a name's
     * letters may be found a little wider or narrower); null when it fits nowhere near (another card).
     */
    static int[] align(Bitmap image,Rect box,Bitmap kept,List<Rect> mask){
        int w=Math.min(box.width(),kept.getWidth()),h=Math.min(box.height(),kept.getHeight());int[] k=sample(kept,0,0,w,h);
        // The area around the box read once, as brightness; each place tried against it.
        int x0=Math.max(0,box.left-SLIDE),y0=Math.max(0,box.top-SLIDE),x1=Math.min(image.getWidth(),box.right+SLIDE),y1=Math.min(image.getHeight(),box.bottom+SLIDE);
        int aw=x1-x0,ah=y1-y0;if(aw<w||ah<h)return null;
        int[] l=new int[aw*ah];image.getPixels(l,0,aw,x0,y0,aw,ah);for(int i=0;i<l.length;i++)l[i]=lum(l[i]);
        int[] best=null;double bestShare=-1;
        for(int dy=-SLIDE;dy<=SLIDE;dy++)for(int dx=-SLIDE;dx<=SLIDE;dx++){
            int x=box.left+dx,y=box.top+dy;if(x<x0||y<y0||x+w>x1||y+h>y1)continue;
            int n=0,m=0,tried=0;
            for(int j=0;j<h;j+=3){int row=(y-y0+j)*aw+(x-x0);
                for(int i=0;i<w;i+=3){int kk=k[m++];if(masked(mask,box,i,j))continue;tried++;if(Math.abs(l[row+i]-kk)<NEAR)n++;}}
            double share=tried==0?0:n/(double)tried;if(share>bestShare){bestShare=share;best=new int[]{x,y};}}
        return bestShare>=.6?best:null;
    }
    private static boolean masked(List<Rect> mask,Rect box,int i,int j){
        for(Rect m:mask)if(box.left+i>=m.left&&box.left+i<m.right&&box.top+j>=m.top&&box.top+j<m.bottom)return true;return false;
    }
    /** For each take, how far it is from the middle value of all, summed over sampled pixels. */
    static long[] offMiddle(List<Bitmap> takes){
        int k=takes.size();int[][] s=new int[k][];
        for(int i=0;i<k;i++){Bitmap b=takes.get(i);s[i]=sample(b,0,0,b.getWidth(),b.getHeight());}
        int n=Integer.MAX_VALUE;for(int[] x:s)n=Math.min(n,x.length);
        long[] off=new long[k];int[] col=new int[k];
        for(int j=0;j<n;j++){for(int i=0;i<k;i++)col[i]=s[i][j];int[] sorted=col.clone();Arrays.sort(sorted);int m=sorted[k/2];
            for(int i=0;i<k;i++)off[i]+=Math.abs(col[i]-m);}
        return off;
    }
}
