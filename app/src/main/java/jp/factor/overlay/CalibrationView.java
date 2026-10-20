package jp.factor.overlay;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * A screenshot with what the user fits on it. On a factor screen: the page title box (blue), one factor chip (red)
 * and where the level letter goes when shown as a letter (a sample 必). On a skill screen: where the line
 * over the grey line under the name goes, with its labels. What recognition reads is drawn faintly over the
 * screenshot - the crops and the column the list is found by - and a sample placed over any of it turns red.
 * Drag an edge to move that edge, drag inside a box to move the whole box. Boxes are kept in image pixels.
 */
final class CalibrationView extends View {
    interface Listener{void changed();}
    private Bitmap image;private boolean skill;
    private final RectF title=new RectF(),chip=new RectF(),letter=new RectF(),line=new RectF(),name=new RectF(),frame=new RectF();
    private final List<RectF> zones=new ArrayList<>();private float scanX=-1;
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(),label=new Paint(Paint.ANTI_ALIAS_FLAG),back=new Paint(),
        sample=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float scale=1,offsetX,offsetY;private Listener listener;
    // Drag state: the box being dragged and which of its edges follow the finger (all four = move the box).
    private RectF dragging;private boolean dl,dt,dr,db,framingLeft;private float lastX,lastY;

    CalibrationView(Context c){
        super(c);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(Ui.dp(c,2));
        label.setTextSize(Ui.dp(c,13));label.setColor(Color.WHITE);label.setFakeBoldText(true);back.setColor(0xff202b2c);
        sample.setFakeBoldText(true);sample.setTextAlign(Paint.Align.CENTER);
    }
    void setListener(Listener l){listener=l;}
    boolean hasImage(){return image!=null;}
    Bitmap image(){return image;}
    boolean skill(){return skill;}
    void clear(boolean skillScreen){image=null;skill=skillScreen;zones.clear();scanX=-1;invalidate();}

    /** A factor screen, with the boxes placed from calibration c; the chip and the letter sit on the row at chipY. */
    void showFactor(Bitmap bitmap,Calibration c,int chipY,RectF letterBox,List<RectF> readZones,float readColumn){
        image=bitmap;skill=false;letter.set(letterBox);setZones(readZones,readColumn);place(c,chipY);
    }
    /** A skill screen, with the line placed at lineBox and the text under a name at nameBox. */
    void showSkill(Bitmap bitmap,RectF lineBox,RectF nameBox,List<RectF> readZones,float readColumn){
        image=bitmap;skill=true;line.set(lineBox);name.set(nameBox);setZones(readZones,readColumn);invalidate();if(listener!=null)listener.changed();
    }
    private void setZones(List<RectF> readZones,float readColumn){zones.clear();zones.addAll(readZones);scanX=readColumn;}
    void place(Calibration c,int chipY){
        if(image==null)return;int w=image.getWidth(),h=image.getHeight();int[] b=c.titleBox(w,h);
        title.set(b[0],b[1],b[2],b[3]);float half=(float)(c.chipHalfHeight*w);
        chip.set((float)(c.chipLeft*w),chipY-half,(float)(c.chipRight*w),chipY+half);
        invalidate();if(listener!=null)listener.changed();
    }
    void placeLetter(RectF box){letter.set(box);invalidate();if(listener!=null)listener.changed();}
    void placeLine(RectF box){line.set(box);invalidate();if(listener!=null)listener.changed();}
    void placeName(RectF box){name.set(box);invalidate();if(listener!=null)listener.changed();}
    /** The frame sample over the first row's card; its right edge always mirrors its left, as the lists are centred. */
    void placeFrame(RectF box){frame.set(box);invalidate();if(listener!=null)listener.changed();}
    RectF frameBox(){return new RectF(frame);}
    RectF nameBox(){return new RectF(name);}
    RectF letterBox(){return new RectF(letter);}
    RectF lineBox(){return new RectF(line);}
    /** The fitted boxes as a calibration (shares of the screenshot size). */
    Calibration calibration(){
        int w=image.getWidth(),h=image.getHeight();
        return new Calibration(title.left/w,title.top/h,title.right/w,title.bottom/h,chip.left/w,chip.right/w,chip.height()/2/w);
    }
    /** Whether a sample sits on something recognition reads. */
    boolean overlaps(RectF r){
        if(scanX>=0&&scanX>=r.left&&scanX<=r.right)return true;
        for(RectF z:zones)if(RectF.intersects(z,r))return true;
        return false;
    }
    boolean sampleOverlaps(){return image!=null&&(skill?overlaps(line)||overlaps(name):overlaps(letter));}

    @Override protected void onDraw(Canvas canvas){
        canvas.drawRect(0,0,getWidth(),getHeight(),back);
        if(image==null){label.setTextAlign(Paint.Align.CENTER);label.setColor(Color.WHITE);label.setTextSize(Ui.dp(getContext(),13));
            canvas.drawText(skill?"「スクショを選ぶ」からスキル取得画面の画像を選んでください":"「スクショを選ぶ」から因子選択画面の画像を選んでください",getWidth()/2f,getHeight()/2f,label);return;}
        scale=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
        offsetX=(getWidth()-image.getWidth()*scale)/2;offsetY=(getHeight()-image.getHeight()*scale)/2;
        canvas.save();canvas.translate(offsetX,offsetY);canvas.scale(scale,scale);
        canvas.drawBitmap(image,0,0,null);
        // What recognition reads, faintly: the crops, and the column the list is found by.
        fill.setColor(0x55000000);for(RectF z:zones)canvas.drawRect(z,fill);
        if(scanX>=0){stroke.setColor(0xffff00ff);stroke.setStrokeWidth(Ui.dp(getContext(),2)/scale);canvas.drawLine(scanX,0,scanX,image.getHeight(),stroke);}
        frameSample(canvas);
        if(skill){lineSample(canvas);nameSample(canvas);}
        else{box(canvas,title,0xff277ddd,"タイトル");box(canvas,chip,0xffff3b30,"因子の枠");letterSample(canvas);}
        canvas.restore();
    }
    private void box(Canvas canvas,RectF r,int color,String name){
        fill.setColor((color&0x00ffffff)|0x33000000);canvas.drawRect(r,fill);
        stroke.setColor(color);stroke.setStrokeWidth(Ui.dp(getContext(),2)/scale);canvas.drawRect(r,stroke);
        label.setTextAlign(Paint.Align.LEFT);label.setTextSize(Ui.dp(getContext(),13)/scale);label.setColor(color);canvas.drawText(name,r.left,r.top-4/scale,label);
    }
    /** The sample's own box: dashed, green when clear of what is read, red when on it. */
    private void outline(Canvas canvas,RectF r,String name){
        int color=overlaps(r)?0xffff3b30:0xff2ebe6e;
        stroke.setColor(color);stroke.setStrokeWidth(Ui.dp(getContext(),2)/scale);
        stroke.setPathEffect(new DashPathEffect(new float[]{8/scale,6/scale},0));canvas.drawRect(r,stroke);stroke.setPathEffect(null);
        label.setTextAlign(Paint.Align.LEFT);label.setTextSize(Ui.dp(getContext(),13)/scale);label.setColor(color);
        canvas.drawText(overlaps(r)?name+"（読み取り範囲に重なっています）":name,r.left,r.bottom+Ui.dp(getContext(),14)/scale,label);
    }
    private void letterSample(Canvas canvas){
        float size=letter.height();sample.setTextSize(size);
        float base=letter.centerY()-(sample.descent()+sample.ascent())/2;
        sample.setStyle(Paint.Style.STROKE);sample.setStrokeWidth(size*.16f);sample.setColor(Color.WHITE);canvas.drawText("必",letter.centerX(),base,sample);
        sample.setStyle(Paint.Style.FILL);sample.setColor(FactorFrames.RAINBOW[0]);canvas.drawText("必",letter.centerX(),base,sample);
        outline(canvas,letter,"文字");
    }
    private void lineSample(Canvas canvas){
        float h=line.height(),y=line.centerY(),thick=h*.25f;
        fill.setColor(FactorFrames.RAINBOW[4]);canvas.drawRoundRect(new RectF(line.left+h,y-thick/2,line.right-h,y+thick/2),thick/2,thick/2,fill);
        badge(canvas,"必",line.left,y,h,FactorFrames.RAINBOW[0]);badge(canvas,"1",line.right-h,y,h,FactorFrames.RAINBOW[0]);
        outline(canvas,line,"線");
    }
    /** The frame as it is drawn around a card: its left and right edges are what is set. */
    private void frameSample(Canvas canvas){
        if(frame.isEmpty())return;
        stroke.setColor(FactorFrames.RAINBOW[0]);stroke.setStrokeWidth(Ui.dp(getContext(),3)/scale);
        canvas.drawRoundRect(frame,frame.height()*.12f,frame.height()*.12f,stroke);
        label.setTextAlign(Paint.Align.LEFT);label.setTextSize(Ui.dp(getContext(),13)/scale);label.setColor(FactorFrames.RAINBOW[0]);
        canvas.drawText("枠（左右の端をドラッグ）",frame.left,frame.top-4/scale,label);
    }
    /** What is written under a skill's name, from its left edge; only its place across is kept. */
    private void nameSample(Canvas canvas){
        float h=name.height();sample.setTextSize(h*.8f);sample.setTextAlign(Paint.Align.LEFT);
        float base=name.centerY()-(sample.descent()+sample.ascent())/2;
        sample.setStyle(Paint.Style.STROKE);sample.setStrokeWidth(h*.12f);sample.setColor(Color.BLACK);canvas.drawText("評価点：95/100pt",name.left,base,sample);
        sample.setStyle(Paint.Style.FILL);sample.setColor(Color.WHITE);canvas.drawText("評価点：95/100pt",name.left,base,sample);
        sample.setTextAlign(Paint.Align.CENTER);
        outline(canvas,name,"文字");
    }
    private void badge(Canvas canvas,String text,float x,float y,float h,int color){
        fill.setColor(color);canvas.drawRoundRect(new RectF(x,y-h/2,x+h,y+h/2),h*.3f,h*.3f,fill);
        sample.setStyle(Paint.Style.FILL);sample.setColor(Color.WHITE);sample.setTextSize(h*.72f);
        canvas.drawText(text,x+h/2,y-(sample.descent()+sample.ascent())/2,sample);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(image==null)return false;
        float x=(e.getX()-offsetX)/scale,y=(e.getY()-offsetY)/scale;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN->{
                getParent().requestDisallowInterceptTouchEvent(true);
                float tol=Ui.dp(getContext(),20)/scale;dragging=null;
                // The frame is only taken by its left or right edge: its inside is the card, where the other samples are.
                if(!frame.isEmpty()&&y>frame.top-tol&&y<frame.bottom+tol&&(Math.abs(x-frame.left)<tol||Math.abs(x-frame.right)<tol)){
                    dragging=frame;framingLeft=Math.abs(x-frame.left)<Math.abs(x-frame.right);lastX=x;lastY=y;return true;
                }
                for(RectF r:skill?new RectF[]{name,line}:new RectF[]{letter,title,chip}){
                    boolean nearX=x>r.left-tol&&x<r.right+tol,nearY=y>r.top-tol&&y<r.bottom+tol;
                    boolean l=nearY&&Math.abs(x-r.left)<tol,ri=nearY&&Math.abs(x-r.right)<tol,t=nearX&&Math.abs(y-r.top)<tol,b=nearX&&Math.abs(y-r.bottom)<tol;
                    if(r.contains(x,y)&&r==letter&&!(t||b)){dragging=r;dl=dr=dt=db=true;break;}
                    // The text under a name only moves sideways: its height follows the row.
                    if(r==name){if(r.contains(x,y)||l||ri){dragging=r;dl=dr=true;dt=db=false;break;}continue;}
                    if(l||ri||t||b){dragging=r;dl=l;dr=ri&&!l;dt=t;db=b&&!t;break;}
                    if(r.contains(x,y)){dragging=r;dl=dr=dt=db=true;break;}
                }
                lastX=x;lastY=y;return dragging!=null;
            }
            case MotionEvent.ACTION_MOVE->{
                if(dragging==null)return false;float dx=x-lastX,dy=y-lastY;lastX=x;lastY=y;
                if(dragging==frame){
                    float w=image.getWidth(),left=Math.max(0,Math.min(w/2-8/scale,frame.left+(framingLeft?dx:-dx)));
                    frame.left=left;frame.right=w-left;invalidate();return true;
                }
                float min=8/scale;RectF r=dragging;
                if(dl)r.left=Math.min(r.left+dx,dr?r.left+dx:r.right-min);if(dr)r.right=Math.max(r.right+dx,dl?r.right+dx:r.left+min);
                if(dt)r.top=Math.min(r.top+dy,db?r.top+dy:r.bottom-min);if(db)r.bottom=Math.max(r.bottom+dy,dt?r.bottom+dy:r.top+min);
                // The letter stays square: its height is its size, and its width follows.
                if(r==letter){float c=r.centerX(),half=r.height()/2;r.left=c-half;r.right=c+half;}
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
