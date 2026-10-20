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
     * label is drawn in the empty strip under the skill name - what the app read, and what it is worth - so the
     * reading can be checked against the screen without opening anything.
     * side, when not WHOLE, is a second colour the frame turns into towards its right edge (the left the frame's own,
     * or nothing when it has none).
     */
    record Mark(int y,int priority,boolean met,String label,int[] box,int top,String tag,String rank,int price,int side) {
        Mark(int y,int priority,boolean met,String label,int[] box,int top,String tag,String rank,int price){this(y,priority,met,label,box,top,tag,rank,price,WHOLE);}
        Mark(int y,int priority,boolean met,String label,int[] box,int top,String tag,String rank){this(y,priority,met,label,box,top,tag,rank,PRICE_NONE);}
        Mark withPrice(int state){return new Mark(y,priority,met,label,box,top,tag,rank,state,side);}
        Mark withSide(int s){return new Mark(y,priority,met,label,box,top,tag,rank,price,s);}
        Mark(int y,int priority,boolean met){this(y,priority,met,null,null,WHOLE,null,null);}
        Mark(int y,int priority,boolean met,String label){this(y,priority,met,label,null,WHOLE,null,null);}
        Mark(int y,int priority,boolean met,String label,int[] box){this(y,priority,met,label,box,WHOLE,null,null);}
    }
    /*
     * tag and rank are small labels on the frame's corners, in case the colours alone do not say enough: tag at the
     * top left in the colour of the level it names (top when split, else priority; grey for none), rank at the bottom
     * left in the red of the frames to take. Either may be null.
     */
    /**
     * top of a Mark when the whole frame is one colour. Otherwise top is the priority whose colour the frame
     * starts with at its top edge, fading into priority's colour at the bottom edge; a level with no colour
     * (NONE, 7) fades out instead. The skill screen puts the skill preset's level on top and its own green or red below.
     */
    static final int WHOLE=Integer.MIN_VALUE;
    /**
     * price of a skill Mark: the price read for the row, shown by its price box as ✓140pt in green, or ? 未読 in
     * grey while none is read (a skill without a price is left out of the plan). NONE draws nothing.
     */
    static final int PRICE_NONE=Integer.MIN_VALUE,PRICE_UNKNOWN=-1;
    /**
     * Shown as 「★の左に文字」, a factor row gets no frame - its lines would cross the column the list is found
     * by (.057) - but its level as a large letter left of the stars: clear of the name crop (to 5.1 card units)
     * and of the stars (from 8.1), so nothing read is drawn on. Its place across is a share of the screen width
     * (DrawPlaces.letterX): worked out from each card's height, or even the page's, it wandered by a pixel or two.
     */
    /** Where the level letters go across the screen; a factor snapshot keeps it as its labelX. */
    static int letterX(FactorVision.Layout layout,int width){return (int)Math.rint(width*DrawPlaces.current.letterX);}
    /** A level as the result window writes it: 必/重/要/本 for a plain preset, 0-7 for a detailed one; null for none. */
    static String levelTag(int level,boolean detailed){
        if(level<0||level>Store.PRIORITY_MAX)return null;
        return detailed?String.valueOf(level):new String[]{"必","重","要","本"}[Store.tierOf(level)];
    }
    /**
     * One recognised frame: row centres in capture pixels, and the frame size they are measured in. rowHalf is
     * how tall a row is on the skill screen, where the cards are taller than a factor chip and the box comes
     * from the row pitch instead of the calibration; 0 means the factor screen's calibrated chip.
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
                moved.add(new Mark(y,m.priority(),m.met(),m.label(),boxes==null?null:boxes.get(i),m.top(),m.tag(),m.rank(),m.price(),m.side()));break;
            }
        }
        return new Snapshot(width,height,moved,rowHalf,labelX);
    }
    /** The skill row card, as shares of the frame width; its height comes from the pitch. */
    static final double ROW_LEFT=.043, ROW_RIGHT=.957;

    // Chips never move sideways, so x and height come from the device calibration (shares of the frame width);
    // deriving them from the detected star column and row pitch, which jitter by a pixel or two, made the frames
    // shake sideways. Only y follows the recognised row, as the list scrolls.
    static RectF chip(Snapshot s,Mark m){
        // The card the row is on, when it was found: the frame's line then runs along the card's own border,
        // centred on it, whatever the row's height or the device.
        if(m.box()!=null)return new RectF(m.box()[0],m.box()[1],m.box()[2],m.box()[3]);
        float w=s.frameWidth();
        if(s.rowHalf()>0)return new RectF((float)(ROW_LEFT*w),m.y()-s.rowHalf(),(float)(ROW_RIGHT*w),m.y()+s.rowHalf());
        Calibration c=Calibration.current;float half=(float)(c.chipHalfHeight*w);
        return new RectF((float)(c.chipLeft*w),m.y()-half,(float)(c.chipRight*w),m.y()+half);
    }
    /**
     * Frame colour for a priority: the seven colours of a rainbow from 0 (most important) to 6, and no
     * frame for 7. Grey marks a registered factor that has too few stars, and 0 means nothing is drawn.
     */
    static final int[] RAINBOW={0xffff3b30,0xffff9500,0xffffcc00,0xff34c759,0xff007aff,0xff3634a3,0xffaf52de,0};
    static final int GREY=0xff8e8e93;
    /** A plain black frame: on the skill screen, a row read but not (yet) coloured by a plan. */
    static final int PLAIN=-100,BLACK=0xff202124;
    static int color(int priority){
        if(priority==PLAIN)return BLACK;
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
            // Every frame of the screen while frames are drawn, so they keep up with the list; with nothing to draw
            // (paused, off the lists) a tenth of a second is soon enough, and the phone is not woken 60 times a second.
            postDelayed(this,now!=null&&!now.marks().isEmpty()?16:100);
        }};
        private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds=new Rect();
        /** The game's name font is wider than the system one: at the same height its glyphs are ~15% wider. */
        static final float TEXT_SCALE_X=1.15f;
        // The label's look is settable. The window is kept, not rebuilt, when the settings change: a window added
        // again lands on top of the floating menu and the dialogs, so it is added once and only told to reload.
        private int labelColor,labelOutline;private float labelSize,labelWidth,labelDrop,labelShift;
        /**
         * The unit of the sideways nudge set in 表示設定 (nameTextShift, in hundredths): a share of the screen width, the
         * size a row half has on the S25 (72 of 1080). Taken from the row height it moved with it, a pixel or two a frame.
         */
        static final float SHIFT_UNIT=72/1080f;
        Overlay(Context c){super(c);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(c,5));
            text.setStyle(Paint.Style.FILL);text.setFakeBoldText(true);
            reload();
        }
        /** Picks up changed display settings; the window itself is kept, so it stays under the menu and dialogs. */
        /** The settings' choice of the letter (factors) and the line (skills) over the frame. */
        private boolean factorLetters,skillLines;
        void reload(){
            var choices=Store.prefs(getContext());
            factorLetters="letter".equals(choices.getString("factorMarks","frame"));
            skillLines="line".equals(choices.getString("skillMarks","frame"));
            var prefs=Store.prefs(getContext());
            labelColor=prefs.getInt("rowTextColor",0xff202b2c);
            labelOutline=prefs.getInt("rowOutlineColor",Color.WHITE);
            // New keys: the old size and shift were set by eye against a line that started elsewhere. The defaults
            // match the game's own name as measured on the phone: 27px tall, with TEXT_SCALE_X for its width.
            labelSize=prefs.getInt("nameTextSize",38)/100f;
            labelWidth=prefs.getInt("rowOutlineWidth",14)/100f;
            labelDrop=prefs.getInt("rowTextDrop",58)/100f;
            labelShift=prefs.getInt("nameTextShift",0)/100f;
            invalidate();
        }
        static boolean visible(){return CaptureService.running&&CaptureService.contentVisible;}
        private final Paint badge=new Paint(Paint.ANTI_ALIAS_FLAG),badgeText=new Paint(Paint.ANTI_ALIAS_FLAG);
        /**
         * The price read for the row (see PRICE_*), as a small pill on the top right corner of the game's price box:
         * placed as the reading places that box, from the icon's edge and the row pitch (shares of the width).
         */
        void priceBox(Canvas canvas,Snapshot s,Mark m){
            if(m.price()==PRICE_NONE)return;
            float w=s.frameWidth(),pitch=(float)(SkillVision.PITCH_X*w),icon=(float)(SkillVision.ICON_RIGHT_X*w);
            pricePill(canvas,m,icon+(float)SkillVision.PT_R*pitch+pitch*.16f,m.y()+(float)SkillVision.PT_T*pitch,pitch*.22f);
        }
        /** The pill itself, its right edge at right and centred on y, h tall; its width. */
        float pricePill(Canvas canvas,Mark m,float right,float y,float h){
            boolean read=m.price()!=PRICE_UNKNOWN;String text=read?"✓"+m.price()+"pt":"? 未読";
            badgeText.setTextSize(h*.66f);badgeText.setFakeBoldText(true);badgeText.setTextAlign(Paint.Align.CENTER);
            float width=badgeText.measureText(text)+h*.7f;RectF r=new RectF(right-width,y-h/2,right,y+h/2);
            badge.setColor(0xffffffff);canvas.drawRoundRect(new RectF(r.left-h*.08f,r.top-h*.08f,r.right+h*.08f,r.bottom+h*.08f),h,h,badge);
            badge.setColor(read?0xff2ebe6e:0xff969696);canvas.drawRoundRect(r,h,h,badge);
            badgeText.setColor(0xffffffff);canvas.drawText(text,r.centerX(),y-(badgeText.descent()+badgeText.ascent())/2,badgeText);
            return width;
        }
        float pricePillWidth(Mark m,float h){
            if(m.price()==PRICE_NONE)return 0;
            badgeText.setTextSize(h*.66f);badgeText.setFakeBoldText(true);
            return badgeText.measureText(m.price()!=PRICE_UNKNOWN?"✓"+m.price()+"pt":"? 未読")+h*.7f;
        }
        /** The tag and the rank of a mark, as small filled labels sitting on the frame's left corners. */
        void corners(Canvas canvas,Mark m,RectF box){
            float h=box.height()*.3f;
            if(m.tag()!=null){int c=color(m.top()!=WHOLE?m.top():m.priority());label(canvas,m.tag(),box.left-h*.1f,box.top-h*.1f,h,c!=0?c:GREY);}
            if(m.rank()!=null)label(canvas,m.rank(),box.left-h*.1f,box.bottom-h*.9f,h,RAINBOW[0]);
        }
        private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
        /**
         * The skill screen shown as 「名前の下に線」: in place of the frame, the grey line under the name is drawn
         * over in the frame's colours, left (top when split) to right (bottom), with the tag at its left end and the
         * rank at its right, both on the line and short of the pentagonal - button. The line keeps clear of the name,
         * of the line under it and of the column the list is found by.
         */
        void underline(Canvas canvas,Snapshot s,Mark m,RectF box,int color){
            // Placed as set on the 読み取り位置の調整 screen, from the page's row height and the screen width (DrawPlaces).
            DrawPlaces at=DrawPlaces.current;float half=s.rowHalf();
            float y=box.top+half*(float)at.lineY,thick=half*.12f,h=half*(float)at.badge;
            float left=(float)(at.lineStart*s.frameWidth()),right=(float)(at.lineEnd*s.frameWidth());
            // Right to left at the line's end: the price read (the price box itself is read, so it goes here), then the number.
            float priceWidth=pricePillWidth(m,h),priceGap=priceWidth>0?priceWidth+h*.2f:0;
            float tagWidth=m.tag()!=null?labelWidth(m.tag(),h):0,rankWidth=m.rank()!=null?labelWidth(m.rank(),h):0;
            float from=left+(tagWidth>0?tagWidth+h*.2f:0),to=right-priceGap-(rankWidth>0?rankWidth+h*.2f:0);
            int start,end;
            if(m.top()!=WHOLE){int top=color(m.top());start=top!=0?top:color&0xffffff;end=color!=0?color:top&0xffffff;}
            else{start=color;end=color;}
            if(((start|end)>>>24)!=0&&to>from){
                line.setStyle(Paint.Style.FILL);line.setColor(0xff000000);
                line.setShader(new LinearGradient(from,y,to,y,start,end,Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new RectF(from,y-thick/2,to,y+thick/2),thick/2,thick/2,line);line.setShader(null);
            }
            if(m.tag()!=null){int c=color(m.top()!=WHOLE?m.top():m.priority());label(canvas,m.tag(),left,y-h/2,h,c!=0?c:GREY);}
            if(m.rank()!=null)label(canvas,m.rank(),right-priceGap-rankWidth,y-h/2,h,RAINBOW[0]);
            if(priceWidth>0)pricePill(canvas,m,right,y,h);
        }
        private final Paint letter=new Paint(Paint.ANTI_ALIAS_FLAG);
        /** A factor's level written large left of its stars, in its frame's colour (grey when short of stars). */
        void dotLetter(Canvas canvas,Snapshot s,Mark m){
            if(m.tag()==null)return;
            int c=m.met()?color(m.priority()):GREY;if(c==0)return;
            float size=s.frameWidth()*(float)DrawPlaces.current.letterSize,x=s.labelX()>0?s.labelX():s.frameWidth()*(float)DrawPlaces.current.letterX,y=m.y();
            letter.setTextSize(size);letter.setTextScaleX(1f);letter.setFakeBoldText(true);letter.setTextAlign(Paint.Align.CENTER);
            float base=y-(letter.descent()+letter.ascent())/2;
            letter.setStyle(Paint.Style.STROKE);letter.setStrokeWidth(size*.16f);letter.setColor(Color.WHITE);canvas.drawText(m.tag(),x,base,letter);
            letter.setStyle(Paint.Style.FILL);letter.setColor(c);canvas.drawText(m.tag(),x,base,letter);
        }
        /** How wide label draws text at height h. */
        float labelWidth(String text,float h){badgeText.setTextSize(h*.72f);badgeText.setFakeBoldText(true);return Math.max(h,badgeText.measureText(text)+h*.5f);}
        void label(Canvas canvas,String text,float x,float y,float h,int color){
            badgeText.setTextSize(h*.72f);badgeText.setFakeBoldText(true);badgeText.setTextAlign(Paint.Align.CENTER);
            float w=labelWidth(text,h);
            badge.setStyle(Paint.Style.FILL);badge.setColor(color);
            canvas.drawRoundRect(new RectF(x,y,x+w,y+h),h*.3f,h*.3f,badge);
            // Dark text on the light yellow, white on the others.
            badgeText.setColor(color==RAINBOW[2]?0xff202124:Color.WHITE);
            canvas.drawText(text,x+w/2,y+h/2-(badgeText.descent()+badgeText.ascent())/2,badgeText);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(poll);}
        @Override protected void onDetachedFromWindow(){removeCallbacks(poll);super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas canvas){
            Snapshot s=drawn;if(hidden||s==null||s.frameWidth()<=0)return;
            // The capture covers the whole display; this window may start below the status bar, so shift by its position.
            getLocationOnScreen(location);Rect screen=getContext().getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            float scale=screen.width()/(float)s.frameWidth();
            canvas.save();canvas.translate(-location[0],-location[1]);canvas.scale(scale,scale);
            for(Mark m:s.marks()){
                // The strip under the name is empty (the game hides the description), so the reading goes there.
                if(m.label()!=null&&s.rowHalf()>0&&labelSize>0){
                    text.setTextSize(s.rowHalf()*labelSize);text.setTextScaleX(TEXT_SCALE_X);
                    // Left-aligned with the skill's own name: the first glyph's ink, not the pen, starts where the
                    // game's name does (labelX).
                    text.getTextBounds(m.label(),0,1,bounds);
                    float x=s.labelX()-bounds.left+s.frameWidth()*SHIFT_UNIT*labelShift,y=m.y()+s.rowHalf()*labelDrop;
                    if(labelWidth>0){
                        text.setColor(labelOutline);text.setStyle(Paint.Style.STROKE);text.setStrokeWidth(s.rowHalf()*labelWidth);
                        canvas.drawText(m.label(),x,y,text);
                    }
                    text.setStyle(Paint.Style.FILL);text.setColor(labelColor);
                    canvas.drawText(m.label(),x,y,text);
                }
                int color=color(m.priority());
                RectF box=chip(s,m);
                // A letter or a line instead of the frame, as chosen in the settings.
                boolean skillRow=s.rowHalf()>0;
                if(skillRow?skillLines:factorLetters){
                    if(skillRow){underline(canvas,s,m,box,color);continue;}
                    dotLetter(canvas,s,m);continue;
                }
                // The corner labels are the skill screen's; a factor row's level is only written on its dot (above).
                if(s.rowHalf()>0){corners(canvas,m,box);priceBox(canvas,s,m);}
                if(m.side()!=WHOLE){
                    // Two colours, left to right: the frame's own into the second; with none of its own, the second fades in.
                    int right=color(m.side());if(right==0&&color==0)continue;
                    int from=color!=0?color:right&0xffffff,to=right!=0?right:color&0xffffff;
                    paint.setColor(0xff000000);paint.setShader(new LinearGradient(box.left,box.top,box.right,box.top,from,to,Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(box,12,12,paint);continue;
                }
                if(m.top()!=WHOLE){
                    // Two colours, top to bottom, blended all the way down; a missing one is its partner faded out.
                    int top=color(m.top());if(top==0&&color==0)continue;
                    int from=top!=0?top:color&0xffffff,to=color!=0?color:top&0xffffff;
                    paint.setColor(0xff000000);paint.setShader(new LinearGradient(box.left,box.top,box.left,box.bottom,from,to,Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(box,12,12,paint);continue;
                }
                if(color==0)continue;
                paint.setColor(color);
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
