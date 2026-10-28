package jp.factor.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The 殿堂入り images of the skill and 継承 tabs, made while they are taken. Of what is on screen only the top over
 * the tabs is used as it is (HallOfFame takes it while the list scrolls); below the tabs the image is laid out the
 * way the game lays out the list, on its grey within its white edges: every card drawn from the card book (CardBook:
 * the blank card of its kind, its name, a chip's stars), and on 継承 also the 因子 banner, the 継承元 heading and the
 * horses' icons (kept in the card book the same way) and the line down the left, drawn.
 *
 * No place is measured off the screen. Each screen read is turned into its rows of cards, top to bottom, and the list
 * is built up from those: a screen is laid over the list where the names agree (a row is the same row when what is
 * read in it is the same), and whatever it shows past the list's end is added after it. What each card is, is what it
 * was read as most often. 継承 is always the same shape - the horse, the 継承元 heading, the first parent, the second -
 * and each horse's group begins with its row of a blue (stats) and a pink (aptitude) chip, so the groups come from
 * the names too; the banner, the heading and the icons are only things laid on a row (the one under them on the
 * screen), never steps of their own, so a screen that missed one does not shift the list. Where each goes in the
 * image follows from its place in the list alone (S25, 1052 wide):
 * - スキル: two columns of cards 89 tall, the first row 18 down, a row every 111;
 * - 継承: the 因子 banner 26 down (36 tall); the horse's chips from 88 down, a row every 88.6; each next horse's
 *   group 150 under the last row before it; the 継承元 heading's rule 45 over the first parent's first row; each
 *   horse's icon from 30 over its first row (180 tall); the line from 124 under the horse's own first row to 89
 *   under the second parent's.
 * A thing not clean yet is not drawn: the frames on screen show which to stop on, and the images are saved once all
 * are. What comes back from a screen says how each thing on it stands, for its frame and for the card book.
 */
final class HofCompose {
    /**
     * A thing on a screen, in the list as the screen shows it: col 0 and 1 the card columns, 2 the 継承 tab's other
     * things (the banner, a heading, an icon); its box, and - when read surely - its name, stars and parts.
     */
    record Seen(int col,Rect card,String name,int stars,CardBook.Shown shown) {}
    /** A thing of the list: what it was read as, voted on; its parts' clean takes once the card book has them. */
    private static final class Rec {
        final int col;final Rect box;String name;int stars;
        /** Its parts as read, placed against its own top (0). */
        CardBook.Shown shown;
        Bitmap[] takes;Rect drawn;
        /** The files of the takes it is drawn from (CardBook): what it was made of, for カードを確認. */
        String[] files;
        final Map<String,Integer> votes=new HashMap<>();final Map<String,Seen> byKey=new HashMap<>();
        Rec(int col,Rect box){this.col=col;this.box=new Rect(box);}
        boolean clean(){return takes!=null;}
        void forget(){if(takes!=null)for(Bitmap b:takes)b.recycle();takes=null;files=null;}
        /** What it is, for laying a screen over the list: name and stars; null while not read. */
        String what(){return name==null?null:name+"|"+stars;}
    }
    /** What a reading says the thing is: its name and its parts' keys (an icon is the same name with other keys). */
    private static String voteKey(Seen x){
        StringBuilder b=new StringBuilder(x.name()).append('|').append(x.stars());
        if(x.shown()!=null)for(CardBook.Card p:x.shown().parts())b.append('|').append(p.key());
        return b.toString();
    }
    /** A row of the list: its cards (by column), and the things laid on it on 継承 (over it on screen). */
    private static final class Entry {
        final Rec[] cards=new Rec[2];Rec banner,heading,icon;
        List<Rec> recs(){List<Rec> out=new ArrayList<>();for(Rec r:new Rec[]{banner,heading,icon,cards[0],cards[1]})if(r!=null)out.add(r);return out;}
        /** The first row of a horse's group: a blue chip (a stats factor) on its left. */
        boolean starts(){Rec r=cards[0];if(r==null||r.shown==null)return false;String k=r.shown.parts().get(0).key();return k.equals("fc|blue")||k.equals("fb|blue");}
    }
    /** A row of a screen, with the things over it there: as Entry, as read there. */
    private record Step(Seen[] cards,Seen[] laid,int top) {}
    /** One tab's list, and its image below its top as it is being made. */
    private static final class Sheet {
        final List<Entry> list=new ArrayList<>();
        Bitmap image;Canvas canvas;int width;Rect line;int bottom;
    }
    /** The list's grey; its white edges, left and right (10 of 1052 each, the inner pixel a shade darker). */
    static final int BG=0xfff2f2f2,EDGE=0xfffafafa,EDGE_INNER=0xfff5f5f5;
    /** The line down 継承's left (x 51-61 of 1052): its grey, and its edges' lighter one. */
    static final int LINE=0xffc4c4c4,LINE_EDGE=0xffd1d1d1;
    /** The layout (S25, 1052 wide; see the class comment). */
    static final double SKILL_FIRST=18,SKILL_PITCH=111,SKILL_CARD=89,SKILL_END=18;
    static final double BANNER_TOP=26,CHIP_FIRST=88,CHIP_PITCH=88.6,GROUP_STEP=150,RULE_OVER=45,HEADING_OVER=16,ICON_OVER=30,
        LINE_FROM=124,LINE_TO=89,CHIP_CARD=83,CHIP_END=30;
    private String session="";
    private final Sheet[] sheets={new Sheet(),new Sheet()};
    private final Paint paint=new Paint();

    /** The horse being taken: another one starts both sheets over. */
    synchronized void begin(String key){if(!key.equals(session)){clear();session=key;publishLeft();}}
    synchronized void clear(){
        for(int i=0;i<sheets.length;i++){Sheet s=sheets[i];if(s.image!=null)s.image.recycle();for(Entry e:s.list)for(Rec r:e.recs())r.forget();sheets[i]=new Sheet();}
        session="";publishLeft();
    }
    /**
     * What is left to take, by tab, as it was after the last change: the result window asks every half second on
     * the main thread, and asking under the lock it waited while a list was being drawn or an image made.
     */
    private record Left(String key,int[] byTab) {}
    private volatile Left left=new Left("",null);
    /** Holding the lock: after anything that changes what is clean. */
    private void publishLeft(){
        int[] n=new int[sheets.length];for(int t=0;t<sheets.length;t++)for(Entry e:sheets[t].list)for(Rec r:e.recs())if(!r.clean())n[t]++;
        left=new Left(session,n);
    }
    /** remainingByTab without waiting for the lock (the main thread's): as of the last change; null for another horse. */
    int[] remainingByTabNow(String key){Left l=left;return key.equals(l.key())&&l.byTab()!=null?l.byTab().clone():null;}
    synchronized boolean has(String key,int tab){return key.equals(session)&&tab<sheets.length&&!sheets[tab].list.isEmpty();}

    /** How a thing on the screen stands: not read yet, or as the card book has it (CardBook.NONE, DIRTY, CLEAN). */
    static final int UNREAD=-1;
    /** A thing on the screen and how it stands; agrees: read as what it was voted to be (its parts may be taken). */
    record Look(Seen seen,int state,boolean agrees) {}
    /** What a screen gave: how each thing on it stands, and whether it was laid over the list (the list goes on through it). */
    record Result(List<Look> looks,boolean joined) {}

    /** A screen of tab read (list: the list as on screen; seen: what is on it), laid over the tab's list. */
    synchronized Result onSeen(Context c,int tab,Bitmap list,List<Seen> seen){
        List<Look> looks=new ArrayList<>();
        if(tab<0||tab>=sheets.length)return new Result(looks,false);
        Sheet s=sheets[tab];s.width=list.getWidth();double u=s.width/1052.0;
        List<Step> steps=steps(seen,u);
        int at=s.list.isEmpty()?0:lay(s.list,steps);
        boolean joined=at!=Integer.MIN_VALUE;
        Map<Seen,Rec> recOf=new HashMap<>();
        if(joined)for(int i=0;i<steps.size();i++){
            int j=at+i;if(j<0)continue;                   // above the list's top: nothing there
            Step st=steps.get(i);
            Entry e;if(j<s.list.size())e=s.list.get(j);else{e=new Entry();s.list.add(e);}
            for(int col=0;col<2;col++)if(st.cards()[col]!=null){if(e.cards[col]==null)e.cards[col]=new Rec(col,st.cards()[col].card());recOf.put(st.cards()[col],e.cards[col]);}
            for(Seen x:st.laid()){if(x==null)continue;
                if("因子".equals(x.name())){if(e.banner==null)e.banner=new Rec(2,x.card());recOf.put(x,e.banner);}
                else if("継承元".equals(x.name())){if(e.heading==null)e.heading=new Rec(2,x.card());recOf.put(x,e.heading);}
                else{if(e.icon==null)e.icon=new Rec(2,x.card());recOf.put(x,e.icon);}}
        }
        for(Seen x:seen){
            Rec r=recOf.get(x);String k=null,best=null;
            if(r!=null&&x.name()!=null){k=voteKey(x);r.votes.merge(k,1,Integer::sum);r.byKey.put(k,x);
                for(var e:r.votes.entrySet())if(best==null||e.getValue()>r.votes.get(best))best=e.getKey();
                Seen top=r.byKey.get(best);
                // Read as something else most often now: that is what it is.
                if(!top.name().equals(r.name)||top.stars()!=r.stars||r.shown==null&&top.shown()!=null||r.shown!=null&&top.shown()!=null&&!sameKeys(r.shown,top.shown())){
                    r.name=top.name();r.stars=top.stars();r.shown=top.shown()==null?null:moved(top.shown(),-top.card().top);r.forget();
                    // Its old picture goes too, or the sheet keeps the misread card (refresh skips a place already drawn).
                    if(r.drawn!=null){if(s.canvas!=null)erase(s,r.drawn);r.drawn=null;}}
            }
            if(r!=null&&!r.clean()&&r.shown!=null&&CardBook.state(c,r.shown)==CardBook.CLEAN)take(c,r);
            int state;
            if(r!=null)state=r.shown==null?UNREAD:r.clean()?CardBook.CLEAN:CardBook.state(c,r.shown);
            else state=x.shown()==null?UNREAD:CardBook.state(c,x.shown());
            looks.add(new Look(x,state,k!=null&&k.equals(best)&&x.shown()!=null));
        }
        if(joined)refresh(c,tab);
        logLeft();publishLeft();
        return new Result(looks,joined);
    }
    /**
     * A screen's rows, top to bottom (cards within 40 of each other's top make one row), each with the things over it:
     * a thing goes on the nearest row whose top is under its own by at most 150 (an icon's row is 30 under it, the
     * heading's first parent row 61, the banner's first row 62); one with no row under it there is left for a
     * screen that shows its row.
     */
    private static List<Step> steps(List<Seen> seen,double u){
        List<Seen> sorted=new ArrayList<>(seen);sorted.sort(Comparator.comparingInt((Seen x)->x.card().top).thenComparingInt(Seen::col));
        List<Step> rows=new ArrayList<>();
        for(Seen x:sorted){if(x.col()==2)continue;
            Step last=rows.isEmpty()?null:rows.get(rows.size()-1);
            if(last!=null&&Math.abs(x.card().top-last.top())<40*u&&last.cards()[x.col()]==null)last.cards()[x.col()]=x;
            else{Seen[] cards=new Seen[2];cards[x.col()]=x;rows.add(new Step(cards,new Seen[3],x.card().top));}
        }
        for(Seen x:sorted){if(x.col()!=2)continue;
            Step under=null;for(Step r:rows){int d=r.top()-x.card().top;if(d>=0&&d<=150*u&&(under==null||d<under.top()-x.card().top))under=r;}
            if(under==null)continue;
            int slot="因子".equals(x.name())?0:"継承元".equals(x.name())?1:2;under.laid()[slot]=x;
        }
        return rows;
    }
    /**
     * Where the screen's rows lie over the list: the place of its first row in the list (it may be past the list's
     * end, the rows then added) where its cards agree best with the list's, counted card by card: there, a card read
     * otherwise than the list has it (a misread, on the screen or in the list's vote so far) is outnumbered at least
     * four to one by cards read the same. A wrong place has its cards nearly all otherwise, so it never comes close;
     * and a card the list has wrong is still read on the screens laid over it, until it is outvoted. MIN_VALUE when
     * nowhere does.
     */
    private static int lay(List<Entry> list,List<Step> steps){
        int best=Integer.MIN_VALUE,bestScore=0;
        for(int at=-(steps.size()-1);at<=list.size();at++){
            int same=0,other=0;
            for(int i=0;i<steps.size();i++){int j=at+i;if(j<0||j>=list.size())continue;
                int[] m=agree(list.get(j),steps.get(i));same+=m[0];other+=m[1];}
            int score=same-4*other;
            if(same>0&&other*4<=same&&score>bestScore){bestScore=score;best=at;}
        }
        return best;
    }
    /** How a screen's row and the list's agree, card by card: {the same, read otherwise}; cards not read on one side are not counted. */
    private static int[] agree(Entry e,Step st){
        int same=0,other=0;
        for(int col=0;col<2;col++){Rec r=e.cards[col];Seen x=st.cards()[col];
            if(r==null||x==null||r.what()==null||x.name()==null)continue;
            if(r.what().equals(x.name()+"|"+x.stars()))same++;else other++;}
        return new int[]{same,other};
    }
    /** How many things of the two tabs are not drawn yet (not clean in the card book, or not read). */
    synchronized int remaining(String key){int[] n=remainingByTab(key);return n==null?-1:n[0]+n[1];}
    /** The same for each tab ({スキル, 継承}); null for another horse. */
    synchronized int[] remainingByTab(String key){
        if(!key.equals(session))return null;
        int[] n=new int[sheets.length];for(int t=0;t<sheets.length;t++)for(Entry e:sheets[t].list)for(Rec r:e.recs())if(!r.clean())n[t]++;return n;
    }
    private String lastLeft="";
    /** What is not drawn yet, to the log when it changes: tab, its place in the list (step, column), and what it is. */
    private void logLeft(){
        StringBuilder b=new StringBuilder();
        for(int t=0;t<sheets.length;t++){List<Entry> list=sheets[t].list;
            for(int i=0;i<list.size();i++)for(Rec r:list.get(i).recs())if(!r.clean())
                b.append(" [").append(t==0?"スキル":"継承").append(' ').append(i+1).append("段目").append(r.col==2?"の上":r.col==0?"左":"右")
                 .append(' ').append(r.name==null?"未読":r.shown==null?r.name+"(未読)":r.name).append(']');}
        String left=b.toString();if(!left.equals(lastLeft)){lastLeft=left;android.util.Log.d("FactorHof","まだ描けていない："+(left.isEmpty()?" なし":left));}
    }
    /** Both tabs made, every thing on them drawn. */
    synchronized boolean ready(Context c,String key){
        if(!has(key,0)||!has(key,1))return false;
        for(int t=0;t<sheets.length;t++){for(Entry e:sheets[t].list)for(Rec r:e.recs())if(!r.clean()&&r.shown!=null&&CardBook.state(c,r.shown)==CardBook.CLEAN)take(c,r);refresh(c,t);}
        publishLeft();
        return remaining(key)==0;
    }
    /** The tab's image: its top (head) over its list; WebP. Null with nothing taken. */
    byte[] finish(Context c,String key,int tab,Bitmap head){
        Bitmap all;
        synchronized(this){
            if(!has(key,tab))return null;
            Sheet s=sheets[tab];
            for(Entry e:s.list)for(Rec r:e.recs())if(!r.clean()&&r.shown!=null&&CardBook.state(c,r.shown)==CardBook.CLEAN)take(c,r);
            refresh(c,tab);publishLeft();if(s.image==null)return null;
            int w=s.image.getWidth();
            all=Bitmap.createBitmap(w,head.getHeight()+s.bottom,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(all);
            canvas.drawColor(BG);canvas.drawBitmap(head,new Rect(0,0,head.getWidth(),head.getHeight()),new Rect(0,0,w,head.getHeight()),null);
            canvas.drawBitmap(s.image,new Rect(0,0,w,s.bottom),new Rect(0,head.getHeight(),w,head.getHeight()+s.bottom),null);
            int total=0,clean=0;for(Entry e:s.list)for(Rec r:e.recs()){total++;if(r.clean())clean++;}
            android.util.Log.d("FactorHof","composed tab"+tab+": "+s.list.size()+" rows, "+total+" things, "+clean+" drawn");
        }
        // Coded outside the lock: it is the slow part (a tall image, losslessly), and the sheets are not needed for it.
        try{return Ui.webp(all);}finally{all.recycle();}
    }
    /** The factors of the 継承 list in its order, each with whose it is - the horse's own first, then the parents'. */
    synchronized List<HallOfFame.Factor> factors(String key){
        List<HallOfFame.Factor> out=new ArrayList<>();if(!has(key,1))return out;
        int group=-1;List<Entry> list=sheets[1].list;
        for(int i=0;i<list.size();i++){Entry e=list.get(i);if(i==0||e.starts())group++;
            for(Rec r:e.cards)if(r!=null&&r.name!=null)out.add(new HallOfFame.Factor(r.name,r.stars,Math.max(0,Math.min(2,group))));}
        return out;
    }
    // ---- kept with the horse, to make its images again (CaptureService.remakeHallOfFame)

    /** The two lists as they stand: each row's cards and the things on it, what each is and its parts. */
    synchronized JSONObject save()throws JSONException{
        JSONArray tabs=new JSONArray();
        for(Sheet s:sheets){JSONArray rows=new JSONArray();
            for(Entry e:s.list)rows.put(new JSONObject().put("cards",new JSONArray().put(rec(e.cards[0])).put(rec(e.cards[1])))
                .put("banner",rec(e.banner)).put("heading",rec(e.heading)).put("icon",rec(e.icon)));
            tabs.put(new JSONObject().put("width",s.width).put("rows",rows));}
        return new JSONObject().put("version",1).put("tabs",tabs);
    }
    /** The lists kept (save) for the horse key, to make its images from: finish as when they were taken. */
    static HofCompose restore(String key,JSONObject o)throws JSONException{
        HofCompose out=new HofCompose();out.session=key;JSONArray tabs=o.getJSONArray("tabs");
        for(int t=0;t<out.sheets.length&&t<tabs.length();t++){JSONObject tab=tabs.getJSONObject(t);Sheet s=out.sheets[t];s.width=tab.getInt("width");
            JSONArray rows=tab.getJSONArray("rows");
            for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);Entry e=new Entry();JSONArray cards=r.getJSONArray("cards");
                e.cards[0]=rec(cards.opt(0));e.cards[1]=rec(cards.opt(1));e.banner=rec(r.opt("banner"));e.heading=rec(r.opt("heading"));e.icon=rec(r.opt("icon"));
                s.list.add(e);}}
        return out;
    }
    private static Object rec(Rec r)throws JSONException{
        if(r==null)return JSONObject.NULL;
        JSONObject o=new JSONObject().put("col",r.col).put("box",rect(r.box)).put("name",r.name==null?JSONObject.NULL:r.name).put("stars",r.stars);
        if(r.shown!=null){JSONArray parts=new JSONArray();
            for(int i=0;i<r.shown.parts().size();i++){CardBook.Card p=r.shown.parts().get(i);JSONArray mask=new JSONArray();for(Rect m:p.mask())mask.put(rect(m));
                JSONObject part=new JSONObject().put("key",p.key()).put("box",rect(p.box())).put("mask",mask);
                if(r.files!=null&&i<r.files.length&&r.files[i]!=null)part.put("file",r.files[i]);
                parts.put(part);}
            o.put("shown",new JSONObject().put("box",rect(r.shown.box())).put("parts",parts));}
        return o;
    }
    private static Rec rec(Object v)throws JSONException{
        if(!(v instanceof JSONObject o))return null;
        Rec r=new Rec(o.getInt("col"),rect(o.getJSONArray("box")));r.name=o.isNull("name")?null:o.getString("name");r.stars=o.optInt("stars");
        JSONObject sh=o.optJSONObject("shown");
        if(sh!=null){List<CardBook.Card> parts=new ArrayList<>();JSONArray ps=sh.getJSONArray("parts");
            for(int i=0;i<ps.length();i++){JSONObject p=ps.getJSONObject(i);List<Rect> mask=new ArrayList<>();JSONArray m=p.getJSONArray("mask");for(int k=0;k<m.length();k++)mask.add(rect(m.getJSONArray(k)));
                parts.add(new CardBook.Card(p.getString("key"),rect(p.getJSONArray("box")),mask));}
            r.shown=new CardBook.Shown(rect(sh.getJSONArray("box")),parts);}
        return r;
    }
    private static JSONArray rect(Rect r){return new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom);}
    private static Rect rect(JSONArray a)throws JSONException{return new Rect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3));}

    /** Every part the two images were made of (for CardBook.prune once they are saved). */
    synchronized List<CardBook.Card> parts(String key){
        List<CardBook.Card> out=new ArrayList<>();if(!key.equals(session))return out;
        for(Sheet s:sheets)for(Entry e:s.list)for(Rec r:e.recs())if(r.shown!=null)out.addAll(r.shown.parts());
        return out;
    }

    // ---- the layout

    /** Where each thing goes (its box), the line, and where the list ends. */
    private record Layout(Map<Rec,Rect> at,Rect line,int bottom) {}
    private Layout layout(int tab){
        Sheet s=sheets[tab];double u=s.width/1052.0;Map<Rec,Rect> at=new HashMap<>();
        if(tab==0){int k=0;
            for(Entry e:s.list){int top=(int)Math.round((SKILL_FIRST+k*SKILL_PITCH)*u);for(Rec r:e.cards)if(r!=null)at.put(r,box(r,top));k++;}
            int bottom=(int)Math.round((SKILL_FIRST+Math.max(0,k-1)*SKILL_PITCH+SKILL_CARD+SKILL_END)*u);
            return new Layout(at,null,bottom);
        }
        // 継承: the horse's group, then each parent's, each begun by its blue and pink row.
        List<Double> firsts=new ArrayList<>();List<Rec> icons=new ArrayList<>();Rec banner=null,heading=null;
        double y=CHIP_FIRST*u;
        for(int i=0;i<s.list.size();i++){Entry e=s.list.get(i);
            boolean starts=i==0||e.starts();
            if(i>0)y+=(starts?GROUP_STEP:CHIP_PITCH)*u;
            if(starts){firsts.add(y);icons.add(e.icon);}
            else if(e.icon!=null&&!icons.isEmpty()&&icons.get(icons.size()-1)==null)icons.set(icons.size()-1,e.icon);
            if(e.banner!=null&&banner==null)banner=e.banner;if(e.heading!=null&&heading==null)heading=e.heading;
            for(Rec r:e.cards)if(r!=null)at.put(r,box(r,(int)Math.round(y)));
        }
        if(banner!=null)at.put(banner,box(banner,(int)Math.round(BANNER_TOP*u)));
        if(heading!=null&&firsts.size()>1)at.put(heading,box(heading,(int)Math.round(firsts.get(1)-(RULE_OVER+HEADING_OVER)*u)));
        for(int i=0;i<icons.size();i++)if(icons.get(i)!=null)at.put(icons.get(i),box(icons.get(i),(int)Math.round(firsts.get(i)-ICON_OVER*u)));
        Rect line=null;
        if(!firsts.isEmpty()){int from=(int)Math.round(firsts.get(0)+LINE_FROM*u),to=(int)Math.round(firsts.size()>2?firsts.get(2)+LINE_TO*u:y+CHIP_CARD*u);
            if(to>from)line=new Rect((int)(51*u),from,(int)(61*u)+1,to);}
        int bottom=(int)Math.round(y+(CHIP_CARD+CHIP_END)*u);
        return new Layout(at,line,bottom);
    }
    private static Rect box(Rec r,int top){return new Rect(r.box.left,top,r.box.right,top+r.box.height());}

    // ---- drawing

    /** Brings the tab's image up to the layout: each clean thing drawn where it goes (moved there when it was elsewhere). */
    private void refresh(Context c,int tab){
        Sheet s=sheets[tab];if(s.width<=0)return;
        Layout l=layout(tab);ensure(s,l.bottom);s.bottom=l.bottom;
        // The line first, the things over it; a line that changed takes the things on it with it.
        boolean lineMoved=!Objects.equals(l.line,s.line);
        if(lineMoved){if(s.line!=null)erase(s,s.line);s.line=l.line;if(l.line!=null)drawLine(s,l.line);}
        // Every old place cleared before anything is drawn: cleared one by one, a thing moving down would wipe what
        // was just drawn below it. What still stands where something was cleared is drawn again too.
        List<Rec> todo=new ArrayList<>();List<Rect> erased=new ArrayList<>();int pad=(int)Math.round(12*s.width/1052.0);
        for(Entry e:s.list)for(Rec r:e.recs()){Rect to=l.at.get(r);if(!r.clean()||to==null)continue;
            boolean onLine=lineMoved&&r.drawn!=null&&l.line!=null&&Rect.intersects(r.drawn,l.line);
            if(to.equals(r.drawn)&&!onLine)continue;
            if(r.drawn!=null&&!to.equals(r.drawn)){erase(s,r.drawn);Rect gone=new Rect(r.drawn);gone.inset(-pad,-pad);erased.add(gone);}
            todo.add(r);
        }
        if(!erased.isEmpty())for(Entry e:s.list)for(Rec r:e.recs()){
            if(todo.contains(r)||!r.clean()||r.drawn==null||l.at.get(r)==null)continue;
            for(Rect gone:erased)if(Rect.intersects(gone,r.drawn)){todo.add(r);break;}
        }
        for(Rec r:todo){Rect to=l.at.get(r);
            for(int i=0;i<r.takes.length;i++){Rect b=r.shown.parts().get(i).box();s.canvas.drawBitmap(r.takes[i],b.left,to.top+b.top,null);}
            r.drawn=new Rect(to);
        }
    }
    /**
     * The clean takes of a thing's parts, to draw from (kept until the horse is done), and their files; none when any
     * is missing. A name's letters are as wide as they were taken.
     */
    private static void take(Context c,Rec r){
        CardBook.Shown shown=r.shown;Bitmap[] out=new Bitmap[shown.parts().size()];String[] files=new String[out.length];
        for(int i=0;i<out.length;i++){CardBook.Card p=shown.parts().get(i);int w=CardBook.free(p.key())?-1:p.box().width();
            out[i]=CardBook.cleanTake(c,p.key(),w,p.box().height());
            java.io.File f=CardBook.cleanFile(c,p.key(),w,p.box().height());files[i]=f==null?null:f.getName();
            if(out[i]==null){for(Bitmap b:out)if(b!=null)b.recycle();return;}}
        r.takes=out;r.files=files;
    }
    /** Back to the grey, within the white edges, the line where it runs. */
    /**
     * A part thrown away from the card book (カードを確認): whatever was made of it lets go of its takes and its
     * picture, so it is taken again from the screen rather than kept from the part just thrown away.
     */
    synchronized void forgetPart(String key){
        for(Sheet s:sheets)for(Entry e:s.list)for(Rec r:e.recs()){
            if(r.shown==null)continue;boolean uses=false;for(CardBook.Card p:r.shown.parts())if(p.key().equals(key))uses=true;
            if(!uses)continue;
            r.forget();if(r.drawn!=null){if(s.canvas!=null)erase(s,r.drawn);r.drawn=null;}
        }
        publishLeft();
    }
    private void erase(Sheet s,Rect r){
        double u=s.width/1052.0;int e=(int)Math.round(10*u),w=s.width;
        paint.setColor(BG);s.canvas.drawRect(r,paint);
        paint.setColor(EDGE);s.canvas.drawRect(0,r.top,e,r.bottom,paint);s.canvas.drawRect(w-e,r.top,w,r.bottom,paint);
        paint.setColor(EDGE_INNER);s.canvas.drawRect(e,r.top,e+1,r.bottom,paint);s.canvas.drawRect(w-e-1,r.top,w-e,r.bottom,paint);
        if(s.line!=null&&!r.equals(s.line)&&Rect.intersects(r,s.line)){Rect part=new Rect(s.line);part.top=Math.max(part.top,r.top);part.bottom=Math.min(part.bottom,r.bottom);drawLine(s,part);}
    }
    private void drawLine(Sheet s,Rect l){
        paint.setColor(LINE_EDGE);s.canvas.drawRect(l.left,l.top,l.left+1,l.bottom,paint);s.canvas.drawRect(l.right-1,l.top,l.right,l.bottom,paint);
        paint.setColor(LINE);s.canvas.drawRect(l.left+1,l.top,l.right-1,l.bottom,paint);
    }
    /** Room for the list down to height, all grey within its white edges: the sheet grows. */
    private void ensure(Sheet s,int height){
        int w=s.width;
        if(s.image!=null&&s.image.getWidth()==w&&s.image.getHeight()>=height)return;
        Bitmap bigger=Bitmap.createBitmap(w,Math.max(height+800,s.image==null?3000:s.image.getHeight()*3/2),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bigger);canvas.drawColor(BG);
        double u=w/1052.0;int e=(int)Math.round(10*u),H=bigger.getHeight();
        paint.setColor(EDGE);canvas.drawRect(0,0,e,H,paint);canvas.drawRect(w-e,0,w,H,paint);
        paint.setColor(EDGE_INNER);canvas.drawRect(e,0,e+1,H,paint);canvas.drawRect(w-e-1,0,w-e,H,paint);
        if(s.image!=null){canvas.drawBitmap(s.image,0,0,null);s.image.recycle();}
        s.image=bigger;s.canvas=canvas;
    }
    private static boolean sameKeys(CardBook.Shown a,CardBook.Shown b){
        if(a.parts().size()!=b.parts().size())return false;
        for(int i=0;i<a.parts().size();i++)if(!a.parts().get(i).key().equals(b.parts().get(i).key()))return false;return true;
    }
    private static CardBook.Shown moved(CardBook.Shown shown,int dy){
        if(shown==null)return null;
        Rect box=new Rect(shown.box());box.offset(0,dy);List<CardBook.Card> parts=new ArrayList<>();
        for(CardBook.Card p:shown.parts()){Rect b=new Rect(p.box());b.offset(0,dy);List<Rect> mask=new ArrayList<>();
            for(Rect m:p.mask()){Rect mm=new Rect(m);mm.offset(0,dy);mask.add(mm);}parts.add(new CardBook.Card(p.key(),b,mask,p.blank()));}
        return new CardBook.Shown(box,parts);
    }
}
