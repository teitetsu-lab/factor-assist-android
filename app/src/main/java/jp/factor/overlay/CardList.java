package jp.factor.overlay;

import java.util.*;
import java.util.function.Predicate;

/**
 * A list drawn as cards on a light background, like the factor list, found the way
 * umacapture finds its rows: going down one column, a card's top is the first card row after a run of light
 * background, and its bottom the last card row before the next run. Everything in a row sits at a fixed place on
 * its card, so each screen gives its crops in units of the card height, and the same numbers hold at any
 * resolution. What a screen adds is only where to look and how to tell its own cards from other boxes.
 *
 * Independent of Android, so recorded frames can be tested.
 */
final class CardList {
    /** Rows of the list's light background that must lie between two cards. */
    static final int GAP=5;

    /**
     * One card: its top and bottom edge, the list's left edge, and the page's usual card height (unit). Positions
     * are in units of that usual height, not of this card's own: some cards come out a few pixels taller (the
     * coloured factor cards, by their shadow), and measuring from those put the factor stars 25px off.
     */
    record Card(int top,int bottom,int left,int unit) {
        int height(){return bottom-top;}
        int centre(){return top+unit/2;}
        int x(double units){return (int)Math.rint(left+units*unit);}
        int y(double units){return (int)Math.rint(top+units*unit);}
        /** A box on the card, {left, top, right, bottom} in frame pixels, from its edges in card units. */
        int[] box(double left,double top,double right,double bottom){return new int[]{x(left),y(top),x(right),y(bottom)};}
        /** The card itself as {left, top, right, bottom}, for drawing a frame along its border; the list is centred. */
        int[] frame(int width){return new int[]{left,top,width-left,bottom};}
    }
    /** The cards wholly on screen, and what they share: the usual card height, the list's left edge, the row pitch. */
    record Page(List<Card> cards,int unit,int left,int pitch) {}

    static boolean light(int color){return ((color>>16)&255)>=235&&((color>>8)&255)>=235&&(color&255)>=235;}
    private static boolean gap(int[] column,int from,int to){
        for(int y=from;y<to;y++)if(!light(column[y]))return false;
        return true;
    }

    /**
     * The cards met going down the column at x, as {top, bottom}: each a run of non-background with at least GAP
     * rows of light background above and below it, and all of the usual card height - a card cut off by the
     * list's edge or covered by a button comes out shorter or longer and is left out until it scrolls clear.
     */
    static List<int[]> edges(int[] pixels,int width,int height,int x){return edges(pixels,width,height,x,0,height);}
    /** Only rows from..to are looked at: the only rows of this frame that were copied out (see CaptureService). */
    static List<int[]> edges(int[] pixels,int width,int height,int x,int from,int to){
        from=Math.max(0,from);to=Math.min(height,to);
        int[] column=new int[height];
        for(int y=from;y<to;y++)column[y]=pixels[y*width+x];
        return edgesOfColumn(column,width,from,to);
    }
    /**
     * The same, from one column of colours already taken out (column[y] is the pixel at row y): what the capture
     * thread uses to move the frames with the list between two full reads, reading nothing but that column.
     */
    static List<int[]> edgesOfColumn(int[] column,int width,int from,int to){
        from=Math.max(0,from);to=Math.min(column.length,to);
        List<int[]> runs=new ArrayList<>();
        for(int y=from+GAP;y<to-GAP;y++){
            if(light(column[y])||!gap(column,y-GAP,y))continue;
            int bottom=-1;
            for(int yy=y+1;yy<to-GAP;yy++)if(!light(column[yy-1])&&gap(column,yy,yy+GAP)){bottom=yy;break;}
            if(bottom<0)break;
            runs.add(new int[]{y,bottom});y=bottom;
        }
        List<Integer> heights=new ArrayList<>();
        for(int[] run:runs)if(run[1]-run[0]>=width*.05)heights.add(run[1]-run[0]);
        if(heights.isEmpty())return List.of();
        int usual=FactorVision.median(heights);
        runs.removeIf(run->Math.abs((run[1]-run[0])-usual)>Math.max(4,usual*.04));
        return runs;
    }

    /**
     * The page of cards, or null when fewer than two are found. scanX is where to go down the screen (a share of
     * its width): somewhere inside every card and clear of anything drawn on it. The left edge is the median of
     * the cards' own, so one card with a button over its end does not move the rest; a list whose left edge sits
     * at the screen's edge is a page mid swipe and is not read. Cards above minTop, or that the screen does not
     * accept as its own (accepts), are left out.
     */
    static Page find(int[] pixels,int width,int height,double scanX,int minTop,Predicate<Card> accepts){
        return find(pixels,width,height,scanX,minTop,0,height,accepts);
    }
    /** from..to: the rows copied out this frame; nothing outside them is looked at. */
    static Page find(int[] pixels,int width,int height,double scanX,int minTop,int from,int to,Predicate<Card> accepts){
        List<int[]> runs=edges(pixels,width,height,(int)Math.rint(scanX*width),from,to);
        if(runs.size()<2)return null;
        List<Integer> heights=new ArrayList<>(),lefts=new ArrayList<>();
        for(int[] run:runs){
            heights.add(run[1]-run[0]);
            int mid=(run[0]+run[1])/2;
            for(int x=0;x<width/4;x++)if(!light(pixels[mid*width+x])){lefts.add(x);break;}
        }
        if(lefts.isEmpty())return null;
        int unit=FactorVision.median(heights),left=FactorVision.median(lefts);
        if(left<width*.02)return null;
        List<Card> cards=new ArrayList<>();
        for(int[] run:runs){
            if(run[0]<minTop)continue;
            Card card=new Card(run[0],run[1],left,unit);
            if(accepts.test(card))cards.add(card);
        }
        if(cards.size()<2)return null;
        List<Integer> steps=new ArrayList<>();
        for(int i=1;i<cards.size();i++)steps.add(cards.get(i).top()-cards.get(i-1).top());
        return new Page(List.copyOf(cards),unit,left,FactorVision.median(steps));
    }
}
