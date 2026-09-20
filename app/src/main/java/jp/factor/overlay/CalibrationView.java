package jp.factor.overlay;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/**
 * A screenshot with the two boxes the user fits: the page title box (blue) and one factor chip (red).
 * Drag an edge to move that edge, drag inside a box to move the whole box. Boxes are kept in image pixels.
 */
final class CalibrationView extends View {
    interface Listener{void changed();}
    private Bitmap image;private final RectF title=new RectF(),chip=new RectF();
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(),label=new Paint(Paint.ANTI_ALIAS_FLAG),back=new Paint();
    private float scale=1,offsetX,offsetY;private Listener listener;
    // Drag state: the box being dragged and which of its edges follow the finger (all four = move the box).
    private RectF dragging;private boolean dl,dt,dr,db;private float lastX,lastY;

    CalibrationView(Context c){
        super(c);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(Ui.dp(c,2));
        label.setTextSize(Ui.dp(c,13));label.setColor(Color.WHITE);label.setFakeBoldText(true);back.setColor(0xff202b2c);
    }
    void setListener(Listener l){listener=l;}
    boolean hasImage(){return image!=null;}
    Bitmap image(){return image;}

    /** Shows image with the boxes placed from calibration c; the chip preview sits on the row at chipY. */
    void show(Bitmap bitmap,Calibration c,int chipY){image=bitmap;place(c,chipY);}
    void place(Calibration c,int chipY){
        if(image==null)return;int w=image.getWidth(),h=image.getHeight();int[] b=c.titleBox(w,h);
        title.set(b[0],b[1],b[2],b[3]);float half=(float)(c.chipHalfHeight*w);
        chip.set((float)(c.chipLeft*w),chipY-half,(float)(c.chipRight*w),chipY+half);
        invalidate();if(listener!=null)listener.changed();
    }
    /** The fitted boxes as a calibration (shares of the screenshot size). */
    Calibration calibration(){
        int w=image.getWidth(),h=image.getHeight();
        return new Calibration(title.left/w,title.top/h,title.right/w,title.bottom/h,chip.left/w,chip.right/w,chip.height()/2/w);
    }

    @Override protected void onDraw(Canvas canvas){
        canvas.drawRect(0,0,getWidth(),getHeight(),back);
        if(image==null){label.setTextAlign(Paint.Align.CENTER);canvas.drawText("「スクショを選ぶ」から因子選択画面の画像を選んでください",getWidth()/2f,getHeight()/2f,label);return;}
        scale=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
        offsetX=(getWidth()-image.getWidth()*scale)/2;offsetY=(getHeight()-image.getHeight()*scale)/2;
        canvas.save();canvas.translate(offsetX,offsetY);canvas.scale(scale,scale);
        canvas.drawBitmap(image,0,0,null);
        box(canvas,title,0xff277ddd,"タイトル");box(canvas,chip,0xffff3b30,"因子の枠");
        canvas.restore();
    }
    private void box(Canvas canvas,RectF r,int color,String name){
        fill.setColor((color&0x00ffffff)|0x33000000);canvas.drawRect(r,fill);
        stroke.setColor(color);stroke.setStrokeWidth(Ui.dp(getContext(),2)/scale);canvas.drawRect(r,stroke);
        label.setTextAlign(Paint.Align.LEFT);label.setTextSize(Ui.dp(getContext(),13)/scale);label.setColor(color);canvas.drawText(name,r.left,r.top-4/scale,label);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(image==null)return false;
        float x=(e.getX()-offsetX)/scale,y=(e.getY()-offsetY)/scale;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN->{
                getParent().requestDisallowInterceptTouchEvent(true);
                float tol=Ui.dp(getContext(),20)/scale;dragging=null;
                for(RectF r:new RectF[]{title,chip}){
                    boolean nearX=x>r.left-tol&&x<r.right+tol,nearY=y>r.top-tol&&y<r.bottom+tol;
                    boolean l=nearY&&Math.abs(x-r.left)<tol,ri=nearY&&Math.abs(x-r.right)<tol,t=nearX&&Math.abs(y-r.top)<tol,b=nearX&&Math.abs(y-r.bottom)<tol;
                    if(l||ri||t||b){dragging=r;dl=l;dr=ri&&!l;dt=t;db=b&&!t;break;}
                    if(r.contains(x,y)){dragging=r;dl=dr=dt=db=true;break;}
                }
                lastX=x;lastY=y;return dragging!=null;
            }
            case MotionEvent.ACTION_MOVE->{
                if(dragging==null)return false;float dx=x-lastX,dy=y-lastY;lastX=x;lastY=y;
                float min=8/scale;RectF r=dragging;
                if(dl)r.left=Math.min(r.left+dx,dr?r.left+dx:r.right-min);if(dr)r.right=Math.max(r.right+dx,dl?r.right+dx:r.left+min);
                if(dt)r.top=Math.min(r.top+dy,db?r.top+dy:r.bottom-min);if(db)r.bottom=Math.max(r.bottom+dy,dt?r.bottom+dy:r.top+min);
                clamp(r);invalidate();return true;
            }
            case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(dragging!=null&&listener!=null)listener.changed();dragging=null;return true;}
        }
        return false;
    }
    private void clamp(RectF r){
        float w=image.getWidth(),h=image.getHeight();
        if(r.left<0)r.offset(-r.left,0);if(r.top<0)r.offset(0,-r.top);if(r.right>w)r.offset(w-r.right,0);if(r.bottom>h)r.offset(0,h-r.bottom);
        r.left=Math.max(0,r.left);r.top=Math.max(0,r.top);r.right=Math.min(w,r.right);r.bottom=Math.min(h,r.bottom);
    }
}
