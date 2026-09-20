package jp.factor.overlay;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** TextView that draws a stroke behind the glyphs, so the floating result stays readable over the game on a transparent background. */
// The app uses platform widgets throughout and has no AppCompat dependency.
@android.annotation.SuppressLint("AppCompatCustomView")
final class OutlinedTextView extends TextView {
    private int outlineColor=Color.BLACK;private float outlineWidth;private boolean drawingOutline;

    OutlinedTextView(Context c){super(c);}

    /** width in px; 0 turns the outline off. */
    void setOutline(int color,float width){
        outlineColor=color;outlineWidth=width;
        // TextView clips drawing to the text area, cutting off the stroke on the outer glyphs. It widens that clip
        // only by the shadow radius, so a transparent shadow as wide as the stroke makes room for it (inside the padding).
        setShadowLayer(width>0?width:0,0,0,Color.TRANSPARENT);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if(outlineWidth<=0){super.onDraw(canvas);return;}
        Paint paint=getPaint();int fill=getCurrentTextColor();
        // First pass: the stroke in the outline colour. setTextColor would re-layout and recurse into onDraw, hence the guard.
        drawingOutline=true;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(outlineWidth);setTextColor(outlineColor);
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);setTextColor(fill);
        drawingOutline=false;
        super.onDraw(canvas);
    }
    @Override public void invalidate(){if(!drawingOutline)super.invalidate();}
    @Override public void requestLayout(){if(!drawingOutline)super.requestLayout();}
}
