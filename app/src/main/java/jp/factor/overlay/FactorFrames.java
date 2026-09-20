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
    record Mark(int y,String tier) {}
    /** One recognised frame: row centres in capture pixels, and the frame size they are measured in. */
    record Snapshot(int frameWidth,int frameHeight,List<Mark> marks) {}

    // Chips never move sideways, so x and height come from the device calibration (shares of the frame width);
    // deriving them from the detected star column and row pitch, which jitter by a pixel or two, made the frames
    // shake sideways. Only y follows the recognised row, as the list scrolls.
    static RectF chip(Snapshot s,Mark m){
        Calibration c=Calibration.current;float w=s.frameWidth(),half=(float)(c.chipHalfHeight*w);
        return new RectF((float)(c.chipLeft*w),m.y()-half,(float)(c.chipRight*w),m.y()+half);
    }
    /** Frame colour per tier; 0 = no frame (未設定). */
    static int color(String tier){
        return switch(tier){
            case "必須"->0xffff3b30;
            case "重要"->0xffff9500;
            case "必要"->0xff007aff;
            case "本育成で取得可能"->0xff34c759;
            case "条件未達"->0xff8e8e93;
            default->0;
        };
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
        Overlay(Context c){super(c);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(c,3));}
        static boolean visible(){return CaptureService.running&&CaptureService.contentVisible;}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(poll);}
        @Override protected void onDetachedFromWindow(){removeCallbacks(poll);super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas canvas){
            Snapshot s=drawn;if(hidden||s==null||s.frameWidth()<=0)return;
            // The capture covers the whole display; this window may start below the status bar, so shift by its position.
            getLocationOnScreen(location);Rect screen=getContext().getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            float scale=screen.width()/(float)s.frameWidth();
            canvas.save();canvas.translate(-location[0],-location[1]);canvas.scale(scale,scale);
            for(Mark m:s.marks()){int color=color(m.tier());if(color==0)continue;paint.setColor(color);canvas.drawRoundRect(chip(s,m),12,12,paint);}
            canvas.restore();
        }
    }
}
