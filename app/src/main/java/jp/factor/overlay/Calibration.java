package jp.factor.overlay;

import android.content.Context;

/**
 * Where to read on this device's screen, as shares of the capture size. The defaults were measured on the
 * Galaxy S25 (1080×2340); other screens are fitted by the user in CalibrationActivity from a screenshot of
 * the factor selection screen, and saved in the preferences.
 *
 * Only the page title box and the chip frame need this: the factor rows themselves are found from the gold
 * stars and read relative to their pitch, which already works at any resolution.
 */
final class Calibration {
    // Title text box: title spans x 376–704, y 463–512 on the S25; the animated arrows sit outside x 216–864
    // and the page dots start at y 526.
    final double titleLeft, titleTop, titleRight, titleBottom;
    // Chip frame: chips span x 48–1033 and are 96 px tall on the S25 (shares of the width, as the UI scales with it).
    final double chipLeft, chipRight, chipHalfHeight;

    Calibration(double titleLeft,double titleTop,double titleRight,double titleBottom,double chipLeft,double chipRight,double chipHalfHeight){
        this.titleLeft=titleLeft;this.titleTop=titleTop;this.titleRight=titleRight;this.titleBottom=titleBottom;
        this.chipLeft=chipLeft;this.chipRight=chipRight;this.chipHalfHeight=chipHalfHeight;
    }
    static final Calibration DEFAULT=new Calibration(.2,.19,.8,.222,48/1080.0,1033/1080.0,48/1080.0);

    /** What recognition uses right now; replaced when the service starts and when a calibration is saved. */
    static volatile Calibration current=DEFAULT;

    int[] titleBox(int width,int height){return new int[]{(int)(width*titleLeft),(int)(height*titleTop),(int)(width*titleRight),(int)(height*titleBottom)};}

    static Calibration load(Context c){
        var p=Store.prefs(c);if(!p.contains("calTitleLeft"))return DEFAULT;
        return new Calibration(p.getFloat("calTitleLeft",0),p.getFloat("calTitleTop",0),p.getFloat("calTitleRight",0),p.getFloat("calTitleBottom",0),
            p.getFloat("calChipLeft",0),p.getFloat("calChipRight",0),p.getFloat("calChipHalfHeight",0));
    }
    void save(Context c){
        Store.prefs(c).edit().putFloat("calTitleLeft",(float)titleLeft).putFloat("calTitleTop",(float)titleTop).putFloat("calTitleRight",(float)titleRight).putFloat("calTitleBottom",(float)titleBottom)
            .putFloat("calChipLeft",(float)chipLeft).putFloat("calChipRight",(float)chipRight).putFloat("calChipHalfHeight",(float)chipHalfHeight).apply();
        current=this;
    }
    static void reset(Context c){
        Store.prefs(c).edit().remove("calTitleLeft").remove("calTitleTop").remove("calTitleRight").remove("calTitleBottom")
            .remove("calChipLeft").remove("calChipRight").remove("calChipHalfHeight").apply();
        current=DEFAULT;
    }

    /**
     * Starting point for the user's fitting: the title box found as the band of brown title text above the list,
     * and the chip frame around the first detected row. Falls back to the defaults for whatever is not found.
     * Pixels are ARGB, width × height.
     */
    static Calibration guess(int[] pixels,int width,int height){
        // The star rows: the proportions below were measured against them, not against the cards.
        FactorVision.Layout layout=FactorVision.detectByStars(pixels,width,height);
        double tl=DEFAULT.titleLeft,tt=DEFAULT.titleTop,tr=DEFAULT.titleRight,tb=DEFAULT.titleBottom;
        double cl=DEFAULT.chipLeft,cr=DEFAULT.chipRight,ch=DEFAULT.chipHalfHeight;
        if(layout!=null){
            int p=layout.pitch(),s=layout.starX();
            // Chip edges from the S25 proportions to the row pitch (left 6.5, right 1.72, half height 0.4 pitches).
            cl=Math.max(0,s-6.5*p)/width;cr=Math.min(width,s+1.72*p)/(double)width;ch=.4*p/width;
            int[] band=titleBand(pixels,width,height,layout.ys().get(0)-p);
            if(band!=null){
                int pad=Math.max(4,(band[3]-band[1])*3/10);
                tl=Math.max(0,band[0]-pad*3)/(double)width;tr=Math.min(width,band[2]+pad*3)/(double)width;
                tt=Math.max(0,band[1]-pad)/(double)height;tb=Math.min(height,band[3]+pad)/(double)height;
            }
        }
        return new Calibration(tl,tt,tr,tb,cl,cr,ch);
    }
    /** Bounding box {left, top, right, bottom} of the tallest run of rows holding brown title text above limitY, or null. */
    static int[] titleBand(int[] pixels,int width,int height,int limitY){
        int x0=width/10,x1=width-width/10,bottom=Math.min(height,Math.max(0,limitY));int minCount=Math.max(3,width/200);
        int bestTop=-1,bestBottom=-1,runTop=-1;
        for(int y=0;y<=bottom;y++){
            int count=0;if(y<bottom)for(int x=x0;x<x1;x++)if(TitleMask.text(pixels[y*width+x]))count++;
            if(count>=minCount){if(runTop<0)runTop=y;}
            else if(runTop>=0){if(y-runTop>bestBottom-bestTop){bestTop=runTop;bestBottom=y;}runTop=-1;}
        }
        if(bestTop<0||bestBottom-bestTop<height/200)return null;
        int left=x1,right=x0;
        for(int y=bestTop;y<bestBottom;y++)for(int x=x0;x<x1;x++)if(TitleMask.text(pixels[y*width+x])){left=Math.min(left,x);right=Math.max(right,x+1);}
        return new int[]{left,bestTop,right,bestBottom};
    }
}
