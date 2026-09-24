package jp.factor.overlay;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.List;

/**
 * Coloured frames around the factor chips, by preset tier, drawn in a full-screen overlay that lets touches
 * through. The positions come from the last recognised capture, so while scrolling they trail the list by
 * one cycle (~40–70 ms).
 */
final class FactorFrames {
    /**
     * met is false when the factor is registered but has too few stars; its colour then starts half grey.
     */
    record Mark(int y,int priority,boolean met,String label,int[] box) {
        Mark(int y,int priority,boolean met){this(y,priority,met,null,null);}
        Mark(int y,int priority,boolean met,String label){this(y,priority,met,label,null);}
    }
    /**
     * One recognised frame: row centres in capture pixels, and the frame size they are measured in.
     */
    record Snapshot(int frameWidth,int frameHeight,List<Mark> marks,int rowHalf,int labelX) {
        Snapshot(int frameWidth,int frameHeight,List<Mark> marks){this(frameWidth,frameHeight,marks,0,0);}
    }
    /**
     * The last frames moved onto the rows just found, before those rows are read. The rows' positions are known
     * a few milliseconds after a capture arrives, the names and what goes with them only after the models have
     * run; drawn from here, the frames follow a scrolling list as closely as the cards themselves. How far the
     * list moved is the median distance from each row to the nearest drawn one (within half a pitch); each row
     * then takes the look of the frame that sat that far above it. A row that just came into view has nothing to
     * take and waits for its reading. ys and boxes (null entries, or null, where no card box is known) run
     * together. Null when there is nothing to move.
     */
    static Snapshot follow(Snapshot last,List<Integer> ys,List<int[]> boxes,int pitch,int width,int height,int rowHalf,int labelX){
        if(last==null||last.marks().isEmpty()||ys.isEmpty())return null;
        java.util.List<Integer> moves=new java.util.ArrayList<>();
        for(int y:ys){
            Mark nearest=null;
            for(Mark m:last.marks())if(nearest==null||Math.abs(y-m.y())<Math.abs(y-nearest.y()))nearest=m;
            if(nearest!=null&&Math.abs(y-nearest.y())<pitch/2)moves.add(y-nearest.y());
        }
        if(moves.isEmpty())return null;
        int move=FactorVision.median(moves);
        java.util.List<Mark> moved=new java.util.ArrayList<>();
        for(int i=0;i<ys.size();i++){
            int y=ys.get(i);
            for(Mark m:last.marks())if(Math.abs(y-move-m.y())<pitch/4){
                moved.add(new Mark(y,m.priority(),m.met(),m.label(),boxes==null?null:boxes.get(i)));break;
            }
        }
        return new Snapshot(width,height,moved,rowHalf,labelX);
    }

    // Chips never move sideways, so x and height come from the device calibration (shares of the frame width);
    // deriving them from the detected star column and row pitch, which jitter by a pixel or two, made the frames
    // shake sideways. Only y follows the recognised row, as the list scrolls.
    static RectF chip(Snapshot s,Mark m){
        // The card the row is on, when it was found: the frame's line then runs along the card's own border,
        // centred on it, whatever the row's height or the device.
        if(m.box()!=null)return new RectF(m.box()[0],m.box()[1],m.box()[2],m.box()[3]);
        float w=s.frameWidth();
        Calibration c=Calibration.current;float half=(float)(c.chipHalfHeight*w);
        return new RectF((float)(c.chipLeft*w),m.y()-half,(float)(c.chipRight*w),m.y()+half);
    }
    /**
     * Frame colour for a priority: the seven colours of a rainbow from 0 (most important) to 6, and no
     * frame for 7. Grey marks a registered factor that has too few stars, and 0 means nothing is drawn.
     */
    static final int[] RAINBOW={0xffff3b30,0xffff9500,0xffffcc00,0xff34c759,0xff007aff,0xff3634a3,0xffaf52de,0};
    static final int GREY=0xff8e8e93;
    static int color(int priority){
        if(priority==Store.NONE||priority==Store.BANNED)return 0;   // 除外は枠を出さない
        if(priority==Store.UNMET)return GREY;
        return RAINBOW[Math.min(RAINBOW.length-1,Math.max(0,priority))];
    }
    /** Plain colour when the stars are enough; the gradient into grey is what marks a factor that falls short. */
    static boolean graded(boolean met){return !met;}
    /** Plain arithmetic rather than android.graphics.Color, so the unit tests can check the colours. */
    static int blend(int from,int to,float amount){
        int argb=0xff000000;
        for(int shift=16;shift>=0;shift-=8){
            int a=(from>>shift)&0xff,b=(to>>shift)&0xff;
            argb|=Math.round(a+(b-a)*amount)<<shift;
        }
        return argb;
    }

    /** The overlay view. Polls the latest snapshot and redraws only when it changed. */
    static final class Overlay extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int[] location=new int[2];
        private Snapshot drawn;private boolean hidden=true;
        private final Runnable poll=new Runnable(){public void run(){
            Snapshot now=visible()?CaptureService.frames:null;boolean hide=now==null;
            if(now!=drawn||hide!=hidden){drawn=now;hidden=hide;invalidate();}
            postDelayed(this,16);
        }};
        Overlay(Context c){super(c);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(c,5));
            reload();
        }
        /** Picks up changed display settings; the window itself is kept, so it stays under the menu and dialogs. */
        void reload(){
            invalidate();
        }
        static boolean visible(){return CaptureService.running&&CaptureService.contentVisible;}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(poll);}
        @Override protected void onDetachedFromWindow(){removeCallbacks(poll);super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas canvas){
            Snapshot s=drawn;if(hidden||s==null||s.frameWidth()<=0)return;
            // The capture covers the whole display; this window may start below the status bar, so shift by its position.
            getLocationOnScreen(location);Rect screen=getContext().getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            float scale=screen.width()/(float)s.frameWidth();
            canvas.save();canvas.translate(-location[0],-location[1]);canvas.scale(scale,scale);
            for(Mark m:s.marks()){
                int color=color(m.priority());if(color==0)continue;
                RectF box=chip(s,m);paint.setColor(color);
                // A factor with enough stars gets its plain colour. One that falls short starts grey on the left
                // and grows into its priority's colour, so the miss reads first and the priority still shows.
                paint.setShader(graded(m.met())?new LinearGradient(box.left,box.top,box.right,box.top,GREY,color,Shader.TileMode.CLAMP):null);
                canvas.drawRoundRect(box,12,12,paint);
            }
            paint.setShader(null);
            canvas.restore();
        }
    }
}
