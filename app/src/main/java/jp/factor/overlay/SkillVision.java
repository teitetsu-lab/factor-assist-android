package jp.factor.overlay;

import ai.onnxruntime.*;
import java.util.*;
import org.json.*;

/**
 * Skill acquisition screen, ported from the PC skill_select_screen. Skills have no stars, so the anchor is the
 * coloured icon at the left of each row instead of the gold stars: the icons are found as connected components
 * on a downsampled saturation mask, the column that is most evenly spaced is the list, and the row pitch and the
 * icons' right edge give the crop for every name.
 *
 * Independent of Android, like FactorVision, so recorded frames can be tested.
 */
final class SkillVision implements AutoCloseable {
    record Icon(int cy,int cx,int right,int area) {}
    /** frames: each row's card as {left, top, right, bottom}, in the order of ys, when the cards were found; else null. */
    record Layout(int pitch,int iconRight,List<Integer> ys,List<int[]> frames) {
        Layout(int pitch,int iconRight,List<Integer> ys){this(pitch,iconRight,ys,null);}
        int[] frameOf(int y){
            if(frames==null)return null;
            int i=ys.indexOf(y);return i<0?null:frames.get(i);
        }
    }
    record Reading(String name,float confidence,int y) {}

    /** The PC tool's SKILL_ACCEPT_CONF: names are stricter than factors, because clipped rows misread at ~.90. */
    static final float NAME_MIN=.92f;
    /**
     * Name crop, as shares of the pitch from the icon's right edge. Sized as umacapture crops the names on the
     * スキル tab the model was trained on (skill_tab left_rect): the text fills ~70% of the height from ~18% down,
     * with a few pixels before it, 13.3 heights wide. The earlier crop (PC R_NAME_*) took in the card's lower edge
     * and squashed the text to ~7 of the 16 rows; read this way, a row shifted ±8px still reads at 1.00.
     */
    // Measured against the app's own row y and icon edge on the phone: the text runs from y-.313 to y-.168 pitches
    // and starts at iconRight+.174; the crop leaves ~6px before it and ~7px above it, as umacapture's does.
    static final double NAME_L=.141, NAME_R=2.821, NAME_CY=-.250, NAME_H=.202;
    static final int NAME_WIDTH=200;
    /**
     * The list's own area. Above LIST_TOP is the character art and the header; below LIST_BOTTOM the row is
     * partly under the 説明省略 and 昇順 buttons, where a half-covered name reads as some other skill entirely
     * (尻尾の滝登り came out as イニシアチブ). Those rows are left alone until they scroll clear.
     */
    static final double LIST_TOP=.34, LIST_BOTTOM=.80;
    /** Detection is done on every DS-th pixel of the left ICON_MAX_X of the frame; the icons are large. */
    static final int DS=3;
    static final double ICON_MAX_X=.5;

    /** The skill-name model, run as the factor models are (see ModelRunner). */
    final ModelRunner runner;
    final JSONArray labels;
    /** Every skill name the model knows, normalised, for checking an OCR reading against. */
    final Map<String,String> byText=new HashMap<>();
    volatile long rows;

    SkillVision(byte[] model,String labelJson) throws Exception {this(model,labelJson,0);}
    /** threads 0 lets the runner find the fastest thread count, as for the factor models; any other number fixes it. */
    SkillVision(byte[] model,String labelJson,int threads) throws Exception {
        labels=new JSONObject(labelJson).getJSONArray("skill.name");
        for(int i=0;i<labels.length();i++){String name=labels.getString(i);byText.putIfAbsent(Store.normalize(name),name);}
        runner=new ModelRunner(new int[]{NAME_WIDTH},model);
        runner.tuner.setThreads(threads);
    }

    /** Saturated enough to be a skill icon rather than the grey row behind it. */
    static boolean vivid(int color,int minimum) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        int high=Math.max(r,Math.max(g,b)),low=Math.min(r,Math.min(g,b));
        return high-low>minimum&&high>minimum;
    }
    /**
     * The app's own frames are captured along with the game, and their strokes run straight across the rows.
     * Left in the mask they cut the icons in two, both halves then fail the square test, and the row vanishes -
     * so drawing frames would destroy the very detection that puts them there. The PC tool masks its overlay
     * colours for the same reason.
     */
    static final int TOLERANCE=40;
    static boolean overlay(int color) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        for(int frame:FactorFrames.RAINBOW){
            if(frame==0)continue;
            if(Math.abs(r-((frame>>16)&255))<TOLERANCE&&Math.abs(g-((frame>>8)&255))<TOLERANCE&&Math.abs(b-(frame&255))<TOLERANCE)return true;
        }
        return Math.abs(r-((FactorFrames.GREY>>16)&255))<TOLERANCE&&Math.abs(g-((FactorFrames.GREY>>8)&255))<TOLERANCE&&Math.abs(b-(FactorFrames.GREY&255))<TOLERANCE;
    }

    /**
     * The green + button at the right of every row. Measured on the phone it is far steadier than the skill
     * icon: always the same size and the same x, whatever the skill is. It dims once the skill is taken, so
     * the test is loose about brightness and strict about shape.
     */
    static final double PLUS_BAND=.82;
    /** Name box from the + button, in pitch units: the same place the icon's right edge gives. */
    static final double PLUS_TO_ICON_RIGHT=4.33;
    static boolean plusGreen(int color) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        return g>70&&g>r+20&&g>b+25;
    }
    static List<Icon> plusButtons(int[] pixels,int width,int height) {
        int from=(int)(width*PLUS_BAND);
        int w=(width-from+DS-1)/DS,h=(height+DS-1)/DS;
        boolean[] mask=new boolean[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int color=pixels[Math.min(height-1,y*DS)*width+Math.min(width-1,from+x*DS)];
            mask[y*w+x]=plusGreen(color)&&!overlay(color);
        }
        List<int[]> boxes=new ArrayList<>();int[] queue=new int[w*h];
        for(int start=0;start<w*h;start++){
            if(!mask[start])continue;
            int read=0,end=1,minX=start%w,maxX=minX,top=start/w,bottom=top;queue[0]=start;mask[start]=false;
            while(read<end){int p=queue[read++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
                if(x>0&&mask[p-1]){mask[p-1]=false;queue[end++]=p-1;}if(x+1<w&&mask[p+1]){mask[p+1]=false;queue[end++]=p+1;}
                if(y>0&&mask[p-w]){mask[p-w]=false;queue[end++]=p-w;}if(y+1<h&&mask[p+w]){mask[p+w]=false;queue[end++]=p+w;}}
            boxes.add(new int[]{top,bottom+1,minX,maxX+1,end});
        }
        merge(boxes);
        List<Icon> found=new ArrayList<>();
        int minY=height>width?(int)(height*LIST_TOP):0;
        double least=Math.pow(width*.03,2)/(DS*(double)DS);
        for(int[] b:boxes){
            int boxHeight=b[1]-b[0],boxWidth=Math.max(1,b[3]-b[2]);
            double aspect=boxHeight/(double)boxWidth;
            if(b[4]<least||aspect<=.6||aspect>=1.7)continue;
            int cy=(b[0]+b[1])/2*DS;
            if(cy<minY)continue;
            found.add(new Icon(cy,(b[2]+b[3])/2*DS+from,b[3]*DS+from,b[4]*DS*DS));
        }
        return found;
    }

    /** The icon blobs of one frame, in full-resolution coordinates. */
    static List<Icon> icons(int[] pixels,int width,int height) {
        int limit=(int)(width*ICON_MAX_X);
        int w=(limit+DS-1)/DS,h=(height+DS-1)/DS;
        // Portrait (a phone) needs the looser threshold of the PC tool, and ignores the top third: the header
        // there is colourful, and the list never starts that high.
        int minimum=height>width?90:110;
        double areaScale=height>width?.22:.5, minHeight=height>width?h*.025:h*.035, maxHeight=height>width?h*.18:h*.16;
        int minY=height>width?(int)(height*LIST_TOP):0;
        boolean[] mask=new boolean[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int color=pixels[Math.min(height-1,y*DS)*width+Math.min(width-1,x*DS)];
            mask[y*w+x]=vivid(color,minimum)&&!overlay(color);
        }
        // Boxes as {top,bottom,left,right,area}; the PC tool merges before judging shape, because a pale silhouette
        // in the middle of an icon can split it into two tall halves that would both be rejected.
        List<int[]> boxes=new ArrayList<>();int[] queue=new int[w*h];
        double least=Math.pow(height*.05,2)*areaScale/(DS*(double)DS);
        for(int start=0;start<w*h;start++){
            if(!mask[start])continue;
            int read=0,end=1,minX=start%w,maxX=minX,top=start/w,bottom=top;queue[0]=start;mask[start]=false;
            while(read<end){int p=queue[read++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
                if(x>0&&mask[p-1]){mask[p-1]=false;queue[end++]=p-1;}if(x+1<w&&mask[p+1]){mask[p+1]=false;queue[end++]=p+1;}
                if(y>0&&mask[p-w]){mask[p-w]=false;queue[end++]=p-w;}if(y+1<h&&mask[p+w]){mask[p+w]=false;queue[end++]=p+w;}}
            if(end>=least*.25)boxes.add(new int[]{top,bottom+1,minX,maxX+1,end});
        }
        merge(boxes);
        List<Icon> found=new ArrayList<>();
        for(int[] b:boxes){
            int boxHeight=b[1]-b[0],boxWidth=Math.max(1,b[3]-b[2]);
            double aspect=boxHeight/(double)boxWidth;
            // Icons are nearly square; a clipped row, or one merged with its neighbour, is not.
            if(b[4]<least||aspect<=.78||aspect>=1.3)continue;
            if(boxHeight<=minHeight||boxHeight>=maxHeight)continue;
            if(b[2]*DS>width*.45)continue;
            int cy=(b[0]+b[1])/2*DS;
            if(cy<minY)continue;
            found.add(new Icon(cy,(b[2]+b[3])/2*DS,b[3]*DS,b[4]*DS*DS));
        }
        return found;
    }

    /** Joins boxes that overlap vertically and nearly touch, which is one icon split by its own artwork. */
    static void merge(List<int[]> boxes) {
        for(boolean merged=true;merged;){
            merged=false;List<int[]> kept=new ArrayList<>();
            for(int i=boxes.size()-1;i>=0;i--){
                int[] b=boxes.get(i);int[] hit=null;
                for(int[] other:kept){
                    int overlap=Math.min(b[1],other[1])-Math.max(b[0],other[0]);
                    int gap=Math.max(b[2],other[2])-Math.min(b[3],other[3]);
                    if(overlap>.5*Math.min(b[1]-b[0],other[1]-other[0])&&gap<=2){hit=other;break;}
                }
                if(hit==null)kept.add(b);
                else{hit[0]=Math.min(hit[0],b[0]);hit[1]=Math.max(hit[1],b[1]);hit[2]=Math.min(hit[2],b[2]);hit[3]=Math.max(hit[3],b[3]);hit[4]+=b[4];merged=true;}
            }
            boxes.clear();boxes.addAll(kept);
        }
    }

    /**
     * The row pitch, where the name box starts, and the row centres; null when this is not the skill screen.
     *
     * Rows are anchored on two things at once: the skill icon on the left and the + button on the right. Either
     * can go missing - an icon whose artwork splits it, a + that dimmed because the skill is already taken - and
     * the row is still found as long as one of them shows. Anything still missing is filled in from the pitch.
     */
    static Layout detect(int[] pixels,int width,int height) {
        Layout byCards=fromCards(pixels,width,height);
        return byCards!=null?byCards:detectByAnchors(pixels,width,height);
    }

    /** Where a row's centre sits on its card, in units of the card height (measured on the phone: a card 161px tall). */
    static final double CARD_ROW_Y=.524;
    /**
     * The skill list the way the factor list is read: the cards' own edges, then every crop at a fixed place on
     * its card. Only the column at CARD_SCAN_X is looked at, instead of hunting the whole screen for icons and +
     * buttons. The reference points the crops are measured from (the icon's right edge, the row's centre, the row
     * pitch) are set from the cards so that nameBox and ptBox give exactly the crops they gave before. A card
     * counts when its + button is where it should be, or its price box shows 獲得済; that is also what tells this
     * screen from any other with boxes on it. Null when fewer than two such cards are found.
     */
    /**
     * The share of the screen height the skill list lies in, with room to spare. Only these rows are copied out
     * of each frame; when no cards are found in them the whole frame is copied and read the older way.
     */
    static final double COPY_TOP=.30, COPY_BOTTOM=.88;
    static Layout fromCards(int[] pixels,int width,int height){return fromCards(pixels,width,height,0,height);}
    /** The skill rows from the cards in rows from..to only; null when fewer than two cards are there. */
    static Layout fromCards(int[] pixels,int width,int height,int from,int to){
        if(CardList.edges(pixels,width,height,(int)Math.rint(CARD_SCAN_X*width),from,to).size()<2)return null;
        // The row pitch is a share of the screen width (184 of 1080), not re-measured: everything across a row and
        // the frames' labels are sized from it, and measured it moved by a pixel or two from frame to frame.
        int pitch=(int)Math.rint(PITCH_X*width);
        int listTop=height>width?(int)(height*LIST_TOP):0;
        // A skill card is one with its + button where it should be, or 獲得済 in its price box.
        CardList.Page page=CardList.find(pixels,width,height,CARD_SCAN_X,0,from,to,card->{
            int iconRight=(int)Math.rint(ICON_RIGHT_X*width),y=card.y(CARD_ROW_Y);
            if(y<listTop||!FactorVision.inside(nameBox(iconRight,pitch,y),width,height))return false;
            return plusAt(pixels,width,height,(int)Math.rint(iconRight+PLUS_TO_ICON_RIGHT*pitch),y,pitch)
                ||acquired(pixels,width,height,ptBox(iconRight,pitch,y));
        });
        if(page==null)return null;
        // Across, the game's layout is fixed to the screen width: the icon's edge is a fraction of it rather than
        // worked out from this frame's card heights, which moved it by a pixel or two from frame to frame.
        int iconRight=(int)Math.rint(ICON_RIGHT_X*width);
        List<Integer> ys=new ArrayList<>();List<int[]> frames=new ArrayList<>();
        for(CardList.Card card:page.cards()){ys.add(card.y(CARD_ROW_Y));frames.add(card.frame(width));}
        return new Layout(pitch,iconRight,ys,frames);
    }
    /** Whether the green + button is at (x, y): most of a small patch there is its green. */
    static boolean plusAt(int[] pixels,int width,int height,int x,int y,int pitch){
        int half=Math.max(2,(int)Math.rint(pitch*.06)),green=0,total=0;
        for(int yy=y-half;yy<=y+half;yy++)for(int xx=x-half;xx<=x+half;xx++){
            if(xx<0||yy<0||xx>=width||yy>=height)continue;
            total++;if(plusGreen(pixels[yy*width+xx]))green++;
        }
        return total>0&&green*10>=total*3;
    }

    /** The older way, kept for screens where the cards cannot be told from the background: icons and + buttons. */
    static Layout detectByAnchors(int[] pixels,int width,int height) {
        List<Icon> icons=icons(pixels,width,height);
        List<Icon> plus=plusButtons(pixels,width,height);
        Layout byPlus=plus.size()>=2?fromAnchors(plus,pixels,width,height,true):null;
        Layout byIcons=icons.size()>=2?fromAnchors(icons,pixels,width,height,false):null;
        if(byPlus==null)return snap(pixels,width,height,byIcons);
        if(byIcons==null)return snap(pixels,width,height,byPlus);
        // Rows from whichever anchor found more of them, but the name always measured from the + buttons: a
        // gold icon's pale centre ends its coloured blob early, and a name box starting on the icon's artwork
        // reads as another skill entirely (スリップストリーム came out as テンアゲスタイル at .94).
        Layout rows=byPlus.ys().size()>=byIcons.ys().size()?byPlus:byIcons;
        return snap(pixels,width,height,new Layout(rows.pitch(),plusNameFrom(plus,rows.pitch()),rows.ys()));
    }

    /** Where the skill cards' edges are looked for: between the + button and the card's right end, clear of the ヒントLv tag. */
    static final double CARD_SCAN_X=.94;
    /**
     * The rows put back on the cards they belong to. The icons and + buttons give each row's height with a few
     * pixels of play, and rows filled in from the pitch can be further off; the cards' own edges are exact. Where
     * a row sits on its card is taken from this frame's rows themselves (the median share of the card height), so
     * the crops keep their shape and only stop wandering. Rows whose card is not wholly in view - cut by the
     * list's edge, or under the 説明省略 and 昇順 buttons - are dropped until they scroll clear. Without cards
     * to go by (another screen, another device) the rows are left as they were found.
     */
    static Layout snap(int[] pixels,int width,int height,Layout layout){
        if(layout==null)return null;
        List<int[]> cards=CardList.edges(pixels,width,height,(int)Math.rint(CARD_SCAN_X*width));
        if(cards.size()<2)return layout;
        List<Integer> shares=new ArrayList<>();
        for(int y:layout.ys())for(int[] card:cards)
            if(y>card[0]&&y<card[1])shares.add((int)Math.rint(1000.0*(y-card[0])/(card[1]-card[0])));
        if(shares.isEmpty())return layout;
        double share=FactorVision.median(shares)/1000.0;
        int listTop=height>width?(int)(height*LIST_TOP):0;
        List<Integer> ys=new ArrayList<>();List<int[]> frames=new ArrayList<>();
        for(int[] card:cards){
            int y=(int)Math.rint(card[0]+share*(card[1]-card[0]));
            if(y<listTop||!FactorVision.inside(nameBox(layout.iconRight(),layout.pitch(),y),width,height))continue;
            // The card's left edge, across its middle; the list is centred, so the right edge mirrors it.
            int mid=(card[0]+card[1])/2,left=0;
            for(int x=0;x<width/4;x++)if(!CardList.light(pixels[mid*width+x])){left=x;break;}
            ys.add(y);frames.add(new int[]{left,card[0],width-left,card[1]});
        }
        return ys.size()>=2?new Layout(layout.pitch(),layout.iconRight(),ys,frames):layout;
    }

    private static Layout fromAnchors(List<Icon> found,int[] pixels,int width,int height,boolean fromPlus) {
        if(found.size()<2)return null;
        found.sort(Comparator.comparingInt(Icon::cx));
        // Icons of the list share an x; anything else on screen forms its own column and loses below.
        List<List<Icon>> columns=new ArrayList<>();
        for(Icon icon:found){
            List<Icon> last=columns.isEmpty()?null:columns.get(columns.size()-1);
            if(last==null||icon.cx()-last.get(last.size()-1).cx()>width*.03)columns.add(new ArrayList<>());
            columns.get(columns.size()-1).add(icon);
        }
        Layout best=null;
        for(List<Icon> column:columns){
            if(column.size()<2)continue;
            column.sort(Comparator.comparingInt(Icon::cy));
            List<Integer> centres=new ArrayList<>(),rights=new ArrayList<>();
            for(Icon icon:column){centres.add(icon.cy());rights.add(fromPlus?icon.cx():icon.right());}
            List<Integer> gaps=new ArrayList<>();for(int i=1;i<centres.size();i++)gaps.add(centres.get(i)-centres.get(i-1));
            // Anchors go missing - a pale icon, a + that dimmed when the skill was taken - so a gap is often
            // two or three rows wide. The median of the gaps is then a pitch that fits nothing (186 and 366
            // average to 276), so every gap is instead tried as one, two, three or four rows, and the pitch
            // that explains the most gaps wins.
            int pitch=0,support=0;
            for(int gap:gaps)for(int steps=1;steps<=4;steps++){
                int candidate=gap/steps;
                if(candidate<height*.02)continue;
                int fits=0;
                for(int other:gaps){
                    int times=Math.max(1,(int)Math.round(other/(double)candidate));
                    if(times<=6&&Math.abs(other-times*candidate)<candidate*.15)fits++;
                }
                if(fits>support||(fits==support&&candidate>pitch)){support=fits;pitch=candidate;}
            }
            if(pitch<=0||support==0)continue;
            // Every row from the first icon to the last, including the ones whose icon was missed, then a
            // couple beyond each end for rows sitting at the edge of the screen with no icon of their own.
            List<Integer> ys=new ArrayList<>();
            int first=centres.get(0),last=centres.get(centres.size()-1);
            int listTop=height>width?(int)(height*LIST_TOP):0;
            int listBottom=height>width?(int)(height*LIST_BOTTOM):height;
            // The icon's right edge, also when the rows were found by their + buttons (the name box is measured from it).
            int from=nameFrom(rights,pitch,fromPlus);
            for(int y=first-pitch*2;y<=last+pitch*2;y+=pitch){
                if(y<listTop||y>listBottom)continue;
                int[] box=nameBox(from,pitch,y);
                if(!FactorVision.inside(box,width,height))continue;
                ys.add(y);
            }
            if(ys.size()<2)continue;
            if(best==null||ys.size()>best.ys().size())best=new Layout(pitch,from,ys);
        }
        return best;
    }

    /**
     * Where the name box is measured from: the icon's right edge, or the same point worked out from the +
     * button's centre, which sits a fixed 4.33 row pitches to its right.
     */
    /** The name box's reference x, taken from the + buttons' own column. */
    private static int plusNameFrom(List<Icon> plus,int pitch) {
        List<Integer> xs=new ArrayList<>();for(Icon icon:plus)xs.add(icon.cx());
        return (int)Math.rint(FactorVision.median(xs)-PLUS_TO_ICON_RIGHT*pitch);
    }
    private static int nameFrom(List<Integer> refs,int pitch,boolean fromPlus) {
        int x=FactorVision.median(refs);
        return fromPlus?(int)Math.rint(x-PLUS_TO_ICON_RIGHT*pitch):x;
    }

    /**
     * The right edge of the skill icon, as a share of the screen width (the game's layout does not move sideways;
     * measured on a 1080-wide capture). What is read on a row is placed from it.
     */
    static final double ICON_RIGHT_X=180/1080.0,PITCH_X=184/1080.0;
    /** The name box of one row: to the right of the icon, slightly above its centre (PC R_NAME_*). */
    static int[] nameBox(int iconRight,int pitch,int y) {
        int centre=(int)Math.rint(y+NAME_CY*pitch),half=(int)Math.rint(NAME_H*pitch/2);
        return new int[]{(int)Math.rint(iconRight+NAME_L*pitch),centre-half,(int)Math.rint(iconRight+NAME_R*pitch),centre+half};
    }
    /** The white box between the − and + buttons: the price after the hint discount, or the 獲得済 badge. */
    static final double PT_L=3.35, PT_R=4.12, PT_T=-.20, PT_B=.18;
    static int[] ptBox(int iconRight,int pitch,int y) {
        return new int[]{(int)Math.rint(iconRight+PT_L*pitch),(int)Math.rint(y+PT_T*pitch),
                         (int)Math.rint(iconRight+PT_R*pitch),(int)Math.rint(y+PT_B*pitch)};
    }
    /**
     * The 獲得済 badge fills that box with pink, where a price is brown digits on white. Measured on the phone:
     * 11–16% of the box is pink when acquired, 0% when it shows a price.
     */
    static boolean pink(int color) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        return r>190&&g<120&&b>110&&b<200;
    }
    static boolean acquired(int[] pixels,int width,int height,int[] box) {
        if(!FactorVision.inside(box,width,height))return false;
        int pink=0,total=0;
        for(int y=box[1];y<box[3];y++)for(int x=box[0];x<box[2];x++){total++;if(pink(pixels[y*width+x]))pink++;}
        return total>0&&pink*100/total>=4;
    }
    /**
     * 現在のスキルPt, in the artwork above the list. The PC ratios are for the landscape layout, so this is
     * a share of the portrait frame instead; the calibration screen can correct it if a device differs.
     */
    static int[] ownedBox(int width,int height) {
        return new int[]{(int)(width*.722),(int)(height*.295),(int)(width*.935),(int)(height*.333)};
    }

    /**
     * The vertical nudges a row is re-read at when its first crop falls short (shares of the row pitch). Rows
     * found from the cards sit where they should, so one nudge each way is enough; rows found from the icons may
     * be further off and get the wider set.
     */
    static final double[] RETRIES={.02,-.02,.05,-.05,.08,-.08}, CARD_RETRIES={.02,-.02};

    List<Reading> read(int[] pixels,int width,int height,Layout layout) throws Exception {return read(pixels,width,height,layout,true);}
    /**
     * retry false skips the nudged re-reads: while the list scrolls only certain names are taken anyway, and a
     * blurred row would otherwise cost six more inferences a frame for nothing.
     */
    List<Reading> read(int[] pixels,int width,int height,Layout layout,boolean retry) throws Exception {
        List<Reading> result=new ArrayList<>();
        if(layout==null)return result;
        List<Integer> centres=new ArrayList<>();List<byte[]> crops=new ArrayList<>();
        for(int y:layout.ys()){
            byte[] crop=nameCrop(pixels,width,height,layout,y,0);
            if(crop==null)continue;
            centres.add(y);crops.add(crop);
        }
        if(centres.isEmpty())return result;
        rows+=centres.size();
        runner.begin();
        try{
        var first=runner.infer(0,crops);
        long[] indices=first.indices();float[] confidence=first.confidence();
        // Rows are laid out from the pitch, so a row whose icon was missed can sit a few pixels off and read as
        // another skill. Re-reading just those rows, nudged up and down, costs little and settles them.
        for(var nudge:!retry?new double[0]:layout.frames()!=null?CARD_RETRIES:RETRIES){
            List<byte[]> again=new ArrayList<>();List<Integer> at=new ArrayList<>();
            for(int i=0;i<centres.size();i++){
                if(confidence[i]>=NAME_MIN)continue;
                byte[] crop=nameCrop(pixels,width,height,layout,centres.get(i),nudge);
                if(crop==null)continue;
                again.add(crop);at.add(i);
            }
            if(again.isEmpty())break;
            var again2=runner.infer(0,again);
            for(int j=0;j<again.size();j++){
                int i=at.get(j);
                if(again2.confidence()[j]>confidence[i]){confidence[i]=again2.confidence()[j];indices[i]=again2.indices()[j];}
            }
        }
        for(int i=0;i<centres.size();i++){
            String name=labels.getString((int)indices[i]);
            result.add(new Reading(ringChecked(pixels,width,height,layout,centres.get(i),name),confidence[i],centres.get(i)));
        }
        return result;
        }finally{runner.end();}
    }

    /**
     * ○ and ◎ skills differ only by the small inner ring, which the model's 200x16 input squeezes away: it read
     * 川崎レース場○ as 川崎レース場◎ with full confidence. A name ending in either is therefore settled by the
     * symbol itself, at full resolution; when it says the other one, and that skill exists, the name is changed.
     */
    String ringChecked(int[] pixels,int width,int height,Layout layout,int y,String name){return ringChecked(pixels,width,height,nameBox(layout.iconRight(),layout.pitch(),y),name);}
    String ringChecked(int[] pixels,int width,int height,int[] box,String name){
        if(!name.endsWith("○")&&!name.endsWith("◎"))return name;
        char seen=ringAtEnd(pixels,width,height,box);
        if(seen==0||name.charAt(name.length()-1)==seen)return name;
        String other=byText.get(Store.normalize(name.substring(0,name.length()-1)+seen));
        return other!=null?other:name;
    }
    /**
     * The ring symbol at the end of the name in the box: ○, ◎, or 0 when it cannot be told. The name's own
     * height gives the symbol's width (it is square); across its middle rows a ○ is crossed twice, by its left
     * and right stroke, and a ◎ four times, its inner ring as well.
     */
    static char ringAtEnd(int[] pixels,int width,int height,int[] box){
        if(!FactorVision.inside(box,width,height))return 0;
        int bw=box[2]-box[0],bh=box[3]-box[1];
        boolean[] ink=new boolean[bw*bh];int top=bh,bottom=-1,right=-1;
        for(int y=0;y<bh;y++)for(int x=0;x<bw;x++){
            int c=pixels[(box[1]+y)*width+box[0]+x],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            // The names' brown: dark, and redder than it is blue.
            boolean on=r*3+g*6+b<1500&&r>b+30;
            ink[y*bw+x]=on;
            if(on){top=Math.min(top,y);bottom=Math.max(bottom,y);right=Math.max(right,x);}
        }
        if(bottom<0)return 0;
        // One pixel wider than the symbol, so its left stroke is surely in; the character before it ends further left.
        int textHeight=bottom-top+1,left=Math.max(0,right-textHeight-1),mid=(top+bottom)/2;
        List<Integer> crossings=new ArrayList<>();
        for(int y=mid-1;y<=mid+1;y++){
            if(y<0||y>=bh)continue;   // ink on the box's top or bottom row only: the row beyond it is not there
            int runs=0;boolean was=false;
            for(int x=left;x<=right;x++){boolean on=ink[y*bw+x];if(on&&!was)runs++;was=on;}
            crossings.add(runs);
        }
        int runs=FactorVision.median(crossings);
        return runs==2?'○':runs==4?'◎':0;
    }
    private byte[] nameCrop(int[] pixels,int width,int height,Layout layout,int y,double nudge) {
        int[] box=nameBox(layout.iconRight(),layout.pitch(),y+(int)Math.rint(nudge*layout.pitch()));
        if(!FactorVision.inside(box,width,height))return null;
        return FactorVision.crop(pixels,width,height,box[0],box[1],box[2],box[3],NAME_WIDTH,false);
    }
    @Override public void close(){runner.close();}
}
