package jp.factor.overlay;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Children left to right, wrapping onto the next line when a line is full (chips). gap is between them both ways. */
final class FlowLayout extends ViewGroup {
    private final int gap;
    FlowLayout(Context c,int gap){super(c);this.gap=gap;}

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),x=0,y=0,line=0;
        for(int i=0;i<getChildCount();i++){
            View v=getChildAt(i);if(v.getVisibility()==GONE)continue;
            v.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            if(x>0&&x+v.getMeasuredWidth()>width){x=0;y+=line+gap;line=0;}
            x+=v.getMeasuredWidth()+gap;line=Math.max(line,v.getMeasuredHeight());
        }
        setMeasuredDimension(width,resolveSize(y+line,heightSpec));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int width=r-l,x=0,y=0,line=0;
        for(int i=0;i<getChildCount();i++){
            View v=getChildAt(i);if(v.getVisibility()==GONE)continue;
            int w=v.getMeasuredWidth(),h=v.getMeasuredHeight();
            if(x>0&&x+w>width){x=0;y+=line+gap;line=0;}
            v.layout(x,y,x+w,y+h);x+=w+gap;line=Math.max(line,h);
        }
    }
}
