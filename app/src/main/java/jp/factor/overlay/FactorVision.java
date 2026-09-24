package jp.factor.overlay;

import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** PC factor model preprocessing, independent of Android so recorded frames can be tested. */
final class FactorVision implements AutoCloseable {
    record Blob(double y, int x, int area, int width, int height) {}
    /**
     * cards is the factor rows as whole cards (see cards()), when they could be found; the crops are then placed
     * from each card. Null means the rows came from the gold stars alone, the older way, kept for screens where
     * the cards cannot be told from the background.
     */
    record Layout(int pitch, int starX, List<Integer> ys, List<CardList.Card> cards) {
        Layout(int pitch,int starX,List<Integer> ys){this(pitch,starX,ys,null);}
    }
    record Reading(String name, int stars, float nameConfidence, float starConfidence, int y) {}

    /**
     * Where each thing sits on a factor card, in units of the card height (see CardList): measured on the phone,
     * a card 96px tall with its left edge at 48. The name runs from just right of the factor's ball to the middle.
     */
    static final double NAME_LEFT=.583, NAME_RIGHT=5.115, NAME_TOP=.302, NAME_BOTTOM=.708;
    /** The three star centres, and where the old star-column x (the first star's left) falls. */
    static final double[] STARS={8.32,8.81,9.28};
    static final double STAR_COLUMN=8.125, STAR_HALF=.09;
    /** Where the card edges are looked for: inside the card's left end, left of the ball, clear of every drawing. */
    static final double CARD_SCAN_X=.057;
    static int[] nameOf(CardList.Card card){return card.box(NAME_LEFT,NAME_TOP,NAME_RIGHT,NAME_BOTTOM);}

    /**
     * Every factor row whose card is wholly on screen (see CardList.find). A factor card is one with at least one
     * lit star where the stars go - which is also what tells this list from any other screen with boxes on it.
     */
    static List<CardList.Card> cards(int[] pixels,int width,int height){
        CardList.Page page=page(pixels,width,height);
        return page==null?List.of():page.cards();
    }
    static CardList.Page page(int[] pixels,int width,int height){return page(pixels,width,height,0,height);}
    static CardList.Page page(int[] pixels,int width,int height,int from,int to){
        return CardList.find(pixels,width,height,CARD_SCAN_X,0,from,to,
            card->card.x(STARS[2]+STAR_HALF)<width&&stars(pixels,width,height,card)[0]>0);
    }
    /**
     * The share of the screen height the factor list lies in, with room to spare. Only these rows (and the
     * title's) are copied out of each frame; when no cards are found in them the whole frame is copied and read
     * the older way, so a device laid out differently still works, only slower.
     */
    static final double LIST_TOP=.15, LIST_BOTTOM=.85;
    /** The factor rows from the cards in rows from..to only; null when fewer than two cards are there. */
    static Layout detectCards(int[] pixels,int width,int height,int from,int to){
        CardList.Page page=page(pixels,width,height,from,to);
        if(page==null)return null;
        List<Integer> ys=new ArrayList<>();
        for(CardList.Card card:page.cards())ys.add(card.centre());
        return new Layout(page.pitch(),page.cards().get(0).x(STAR_COLUMN),ys,page.cards());
    }
    /**
     * The stars lit on a card, by colour at the three star places: {lit, clear} where clear is 0 when a place is
     * neither plainly gold nor plainly not (the row caught mid animation), so the count cannot be trusted.
     */
    static int[] stars(int[] pixels,int width,int height,CardList.Card card){
        int half=Math.max(2,(int)Math.rint(STAR_HALF*card.unit())),cy=card.centre(),lit=0,clear=1;
        for(double place:STARS){
            int cx=card.x(place),gold=0,total=0;
            for(int y=cy-half;y<=cy+half;y++)for(int x=cx-half;x<=cx+half;x++){
                if(x<0||y<0||x>=width||y>=height)continue;
                total++;if(gold(pixels[y*width+x]))gold++;
            }
            double share=total==0?0:gold/(double)total;
            if(share>.3)lit++;else if(share>.1)clear=0;
        }
        return new int[]{lit,clear};
    }
    /** The name model (0) and the star model (1), run together (see ModelRunner). */
    final ModelRunner runner;
    static final int NAME_MODEL=0, RANK_MODEL=1;
    final JSONArray labels, stars;
    // Written only by the recognition thread; read for the menu display, where a stale value is harmless.
    volatile long rows, retriedRows;
    void resetCounters(){runner.resetCounters();rows=0;retriedRows=0;}

    FactorVision(byte[] nameModel, byte[] starModel, String labelJson) throws Exception {this(nameModel,starModel,labelJson,false);}
    FactorVision(byte[] nameModel, byte[] starModel, String labelJson, boolean useXnnpack) throws Exception {
        JSONObject data=new JSONObject(labelJson); labels=data.getJSONArray("factor.name");stars=data.getJSONArray("factor_rank.name");
        runner=new ModelRunner(useXnnpack,new int[]{168,52},nameModel,starModel);
    }
    static int[] threadOptions(int cores){return ModelRunner.threadOptions(cores);}
    static boolean gold(int color) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        if(!(r>190&&g>150&&b<150&&r>b+60&&g>b+40&&Math.abs(r-g)<90))return false;
        return !(Math.abs(r-255)<40&&Math.abs(g-150)<40&&b<40);
    }
    static int median(List<Integer> numbers) {var sorted=new ArrayList<>(numbers);Collections.sort(sorted);int n=sorted.size();return n%2==1?sorted.get(n/2):(sorted.get(n/2-1)+sorted.get(n/2))/2;}

    // Reused per thread: detect runs every cycle, and a fresh half-frame mask and queue per call only feed the GC.
    private static final ThreadLocal<boolean[]> maskScratch=new ThreadLocal<>();
    private static final ThreadLocal<int[]> queueScratch=new ThreadLocal<>();
    /**
     * The factor rows of a frame, or null when this is not the factor list. The cards are tried first; only when
     * fewer than two are found does the older way, from the gold stars alone, have a say.
     */
    static Layout detect(int[] pixels,int width,int height) {
        Layout byCards=detectCards(pixels,width,height,0,height);
        return byCards!=null?byCards:detectByStars(pixels,width,height);
    }
    static Layout detectByStars(int[] pixels,int width,int height) {
        int w=(width+1)/2,h=(height+1)/2;
        boolean[] mask=maskScratch.get();int[] queue=queueScratch.get();
        if(mask==null||mask.length<w*h){mask=new boolean[w*h];queue=new int[w*h];maskScratch.set(mask);queueScratch.set(queue);}
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)mask[y*w+x]=gold(pixels[(y*2)*width+x*2]);
        List<Blob> blobs=new ArrayList<>();List<Integer> areas=new ArrayList<>();
        for(int start=0;start<w*h;start++) {
            if(!mask[start])continue;
            int read=0,end=1,minX=start%w,maxX=minX,minY=start/w,maxY=minY;queue[0]=start;mask[start]=false;
            while(read<end){int p=queue[read++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                if(x>0&&mask[p-1]){mask[p-1]=false;queue[end++]=p-1;}if(x+1<w&&mask[p+1]){mask[p+1]=false;queue[end++]=p+1;}
                if(y>0&&mask[p-w]){mask[p-w]=false;queue[end++]=p-w;}if(y+1<h&&mask[p+w]){mask[p+w]=false;queue[end++]=p+w;}
            }
            blobs.add(new Blob((minY+maxY+1)/2.0,minX,end,maxX-minX+1,maxY-minY+1));if(end>2.5)areas.add(end);
        }
        if(areas.isEmpty())return null;double area=median(areas);
        blobs.removeIf(b->b.area<area*.3||b.area>area*4||b.height/(double)b.width<=.5||b.height/(double)b.width>=2.2);
        blobs.sort(Comparator.comparingDouble(Blob::y));if(blobs.size()<3)return null;
        List<List<Blob>> groups=new ArrayList<>();
        for(Blob b:blobs){if(groups.isEmpty()||b.y-groups.get(groups.size()-1).get(groups.get(groups.size()-1).size()-1).y>h*.012)groups.add(new ArrayList<>());groups.get(groups.size()-1).add(b);}
        List<Integer> ys=new ArrayList<>(),xs=new ArrayList<>(),diff=new ArrayList<>();
        for(var group:groups){List<Integer> centers=new ArrayList<>();int left=Integer.MAX_VALUE;for(Blob b:group){centers.add((int)(b.y*2));left=Math.min(left,b.x*2);}ys.add(median(centers));xs.add(left);}
        if(ys.size()<3)return null;for(int i=1;i<ys.size();i++)diff.add(ys.get(i)-ys.get(i-1));int pitch=median(diff);if(pitch<12)return null;
        int bestStart=0,bestSize=0;
        for(int i=0;i<ys.size();){int j=i;while(j+1<ys.size()&&Math.abs(ys.get(j+1)-ys.get(j)-pitch)<pitch*.35)j++;if(j-i+1>bestSize){bestStart=i;bestSize=j-i+1;}i=j+1;}
        if(bestSize<3)return null;
        return new Layout(pitch,median(xs.subList(bestStart,bestStart+bestSize)),new ArrayList<>(ys.subList(bestStart,bestStart+bestSize)));
    }

    /**
     * Paints over the red 指定 badge after a factor's name with the row's own colour (the median of its right
     * end), in place. The factor-select screen puts it on the chosen factors; the model and the text recogniser
     * would otherwise read it as part of the name.
     */
    static void debadge(int[] local,int cw,int ch){
        int first=cw,last=-1;for(int x=0;x<cw;x++){int red=0;for(int y=0;y<ch;y++){int c=local[y*cw+x];if(((c>>16)&255)>235&&((c>>8)&255)<100&&(c&255)<150)red++;}if(red>2){first=Math.min(first,x);last=x;}}
        if(last-first>=2){int[] components=new int[3];for(int c=0;c<3;c++){List<Integer> values=new ArrayList<>();for(int y=0;y<ch;y++)for(int x=Math.max(0,cw-12);x<cw;x++)values.add((local[y*cw+x]>>(c*8))&255);components[c]=median(values);}int fill=0xff000000|components[0]|components[1]<<8|components[2]<<16;for(int y=0;y<ch;y++)for(int x=Math.max(0,first-3);x<Math.min(cw,last+4);x++)local[y*cw+x]=fill;}
    }
    // Pillow-compatible bilinear downsampling uses a widened triangle filter.
    static byte[] crop(int[] pixels,int width,int height,int left,int top,int right,int bottom,int outWidth,boolean debadge) {
        if(left<0||top<0||right>width||bottom>height||right<=left||bottom<=top)return null;
        int cw=right-left,ch=bottom-top;int[] local=new int[cw*ch];
        for(int y=0;y<ch;y++)System.arraycopy(pixels,(top+y)*width+left,local,y*cw,cw);
        if(debadge)debadge(local,cw,ch);
        byte[] output=new byte[outWidth*16*3];int[] horizontal=new int[ch*outWidth*3];double sx=cw/(double)outWidth,sy=ch/16.0,fx=Math.max(1,sx),fy=Math.max(1,sy);
        // Per-channel sums in locals, same arithmetic order as before, without an array per output pixel.
        for(int y=0;y<ch;y++)for(int x=0;x<outWidth;x++){double center=(x+.5)*sx;int a=Math.max(0,(int)(center-fx+.5)),b=Math.min(cw,(int)(center+fx+.5));double sum=0,c0=0,c1=0,c2=0;for(int k=a;k<b;k++){double weight=Math.max(0,1-Math.abs((k+.5-center)/fx));sum+=weight;int p=local[y*cw+k];c0+=weight*(p&255);c1+=weight*((p>>8)&255);c2+=weight*((p>>16)&255);}int o=(y*outWidth+x)*3;horizontal[o]=(int)Math.round(c0/sum);horizontal[o+1]=(int)Math.round(c1/sum);horizontal[o+2]=(int)Math.round(c2/sum);}
        for(int y=0;y<16;y++)for(int x=0;x<outWidth;x++){double center=(y+.5)*sy;int a=Math.max(0,(int)(center-fy+.5)),b=Math.min(ch,(int)(center+fy+.5));double sum=0,c0=0,c1=0,c2=0;for(int k=a;k<b;k++){double weight=Math.max(0,1-Math.abs((k+.5-center)/fy));sum+=weight;int i=(k*outWidth+x)*3;c0+=weight*horizontal[i];c1+=weight*horizontal[i+1];c2+=weight*horizontal[i+2];}int o=(y*outWidth+x)*3;output[o]=(byte)Math.round(c0/sum);output[o+1]=(byte)Math.round(c1/sum);output[o+2]=(byte)Math.round(c2/sum);}
        return output;
    }
    /** Confidence a row needs before CaptureService accepts it; rows below get the ±1 offset retries. */
    static final float NAME_MIN=.97f, STAR_MIN=.94f;
    static int[] nameBox(int s,int p,int y,int k){int offset=(int)Math.rint(k*p/68.0);return new int[]{Math.max(0,(int)(s-6.18*p)),(int)(y+offset-.279*p),(int)(s-2.15*p),(int)(y+offset+.191*p)};}
    static int[] rankBox(int s,int p,int y,int k){int offset=(int)Math.rint(k*p/68.0);return new int[]{(int)(s-.12*p),(int)(y+offset-.25*p),(int)(s+1.24*p),(int)(y+offset+.22*p)};}
    static boolean inside(int[] b,int width,int height){return b[0]>=0&&b[1]>=0&&b[2]<=width&&b[3]<=height&&b[2]>b[0]&&b[3]>b[1];}
    byte[] nameCrop(int[] pixels,int width,int height,int[] b){return crop(pixels,width,height,b[0],b[1],b[2],b[3],168,true);}
    byte[] rankCrop(int[] pixels,int width,int height,int[] b){return crop(pixels,width,height,b[0],b[1],b[2],b[3],52,false);}

    /**
     * Same readings as running all three vertical offsets per row, but the ±1 offsets are only inferred
     * for rows whose centre crop falls short of the acceptance thresholds. On a settled list that is
     * roughly a third of the crops and inferences.
     */
    /**
     * The card of the row at y as {left, top, right, bottom}, for drawing its frame along the card's own border;
     * null when the rows came from the stars. The cards are centred on the screen, so the right edge mirrors the left.
     */
    static int[] frameOf(Layout layout,int y,int width){
        if(layout.cards()==null)return null;
        for(CardList.Card card:layout.cards())if(card.centre()==y)return card.frame(width);
        return null;
    }
    /** The name box of the row at y, whichever way the rows were found. */
    static int[] nameBox(Layout layout,int y){
        if(layout.cards()!=null)for(CardList.Card card:layout.cards())if(card.centre()==y)return nameOf(card);
        return nameBox(layout.starX(),layout.pitch(),y,0);
    }
    /** The stars as the colour count gives them and as the star model gives them, when the two disagree (for the log). */
    volatile String lastStarDisagreement;

    List<Reading> read(int[] pixels,int width,int height,Layout layout) throws Exception {
        if(layout==null)return new ArrayList<>();
        runner.begin();
        try{return layout.cards()!=null?readCards(pixels,width,height,layout):readByStars(pixels,width,height,layout);}
        finally{runner.end();}
    }
    /**
     * The card way: one name crop per card, always at the same place on it, and the stars counted by colour. The
     * star model still runs on the same rows for now, so any row where the two disagree shows up in the log.
     */
    List<Reading> readCards(int[] pixels,int width,int height,Layout layout) throws Exception {
        List<Reading> result=new ArrayList<>();
        List<CardList.Card> cards=new ArrayList<>();List<byte[]> nc=new ArrayList<>(),sc=new ArrayList<>();
        int p=layout.pitch();
        for(CardList.Card card:layout.cards()){
            int[] name=nameOf(card),rank=rankBox(card.x(STAR_COLUMN),p,card.centre(),0);
            if(!inside(name,width,height)||!inside(rank,width,height))continue;
            cards.add(card);nc.add(nameCrop(pixels,width,height,name));sc.add(rankCrop(pixels,width,height,rank));
        }
        if(cards.isEmpty())return result;
        rows+=cards.size();
        var nr=runner.infer(NAME_MODEL,nc);var sr=runner.infer(RANK_MODEL,sc);
        for(int i=0;i<cards.size();i++){
            CardList.Card card=cards.get(i);int[] counted=stars(pixels,width,height,card);
            String name=labels.getString((int)nr.indices()[i]);
            int byModel=Integer.parseInt(stars.getString((int)sr.indices()[i]));
            if(counted[1]==1&&byModel!=counted[0])lastStarDisagreement=name+" 色で★"+counted[0]+" / モデル★"+byModel+String.format(Locale.JAPAN,"(%.2f)",sr.confidence()[i]);
            // A clear colour count is certain; a blurred one (mid animation) is not taken at all.
            result.add(new Reading(name,counted[0],nr.confidence()[i],counted[1]==1?1f:0f,card.centre()));
        }
        return result;
    }
    List<Reading> readByStars(int[] pixels,int width,int height,Layout layout) throws Exception {
        List<Reading> result=new ArrayList<>();
        int p=layout.pitch,s=layout.starX;List<Integer> centers=new ArrayList<>();List<byte[]> nc=new ArrayList<>(),sc=new ArrayList<>();
        // A row is kept only if all three offsets fit on screen, exactly as before.
        for(int y:layout.ys){boolean fits=true;for(int k=-1;k<=1&&fits;k++)fits=inside(nameBox(s,p,y,k),width,height)&&inside(rankBox(s,p,y,k),width,height);
            if(!fits)continue;centers.add(y);nc.add(nameCrop(pixels,width,height,nameBox(s,p,y,0)));sc.add(rankCrop(pixels,width,height,rankBox(s,p,y,0)));}
        if(centers.isEmpty())return result;
        rows+=centers.size();
        var nr=runner.infer(NAME_MODEL,nc);var sr=runner.infer(RANK_MODEL,sc);
        // Candidates per row in the original offset order (-1, 0, +1) so ties resolve the same way.
        long[][] nIdx=new long[centers.size()][3],rIdx=new long[centers.size()][3];float[][] nConf=new float[centers.size()][3],rConf=new float[centers.size()][3];
        List<Integer> retry=new ArrayList<>();
        for(int i=0;i<centers.size();i++){
            Arrays.fill(nConf[i],-1);Arrays.fill(rConf[i],-1);
            nIdx[i][1]=nr.indices()[i];nConf[i][1]=nr.confidence()[i];rIdx[i][1]=sr.indices()[i];rConf[i][1]=sr.confidence()[i];
            if(nConf[i][1]<NAME_MIN||rConf[i][1]<STAR_MIN)retry.add(i);
        }
        if(!retry.isEmpty()){retriedRows+=retry.size();
            List<byte[]> rn=new ArrayList<>(),rs=new ArrayList<>();
            for(int i:retry)for(int k=-1;k<=1;k+=2){int y=centers.get(i);rn.add(nameCrop(pixels,width,height,nameBox(s,p,y,k)));rs.add(rankCrop(pixels,width,height,rankBox(s,p,y,k)));}
            var rnr=runner.infer(NAME_MODEL,rn);var rsr=runner.infer(RANK_MODEL,rs);
            for(int j=0;j<retry.size();j++){int i=retry.get(j);
                for(int side=0;side<2;side++){int from=j*2+side,slot=side==0?0:2;
                    nIdx[i][slot]=rnr.indices()[from];nConf[i][slot]=rnr.confidence()[from];rIdx[i][slot]=rsr.indices()[from];rConf[i][slot]=rsr.confidence()[from];}}
        }
        for(int i=0;i<centers.size();i++){int n=-1,r=-1;for(int j=0;j<3;j++){if(nConf[i][j]<0)continue;if(n<0||nConf[i][j]>nConf[i][n])n=j;if(r<0||rConf[i][j]>rConf[i][r])r=j;}
            result.add(new Reading(labels.getString((int)nIdx[i][n]),Integer.parseInt(stars.getString((int)rIdx[i][r])),nConf[i][n],rConf[i][r],centers.get(i)));}
        return result;
    }
    @Override public void close(){runner.close();}
}
