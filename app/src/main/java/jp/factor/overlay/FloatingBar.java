package jp.factor.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating ☰ and 開始/停止 over the game, in the shape chosen in the app's settings. The same views are built
 * for the floating window and for the previews in the settings, so a preview is exactly what will float.
 */
final class FloatingBar {
    static final String[] STYLES={"capsule","circle","square","mini","ball","edge"};
    static final String[] NAMES={"カプセル","丸ボタン","四角","ミニ","ボール","エッジ"};
    static final String[] NOTES={"☰と開始/停止を1つの枠に","記号だけの丸2つ","大きく押しやすい","小さく、文字なし","丸1つ。タップで開く","画面の端の取っ手。引き出して開く"};
    /** How the ball and the edge handle open (開き方 in the settings); the first is the default. */
    static final String[] BALL_OPENINGS={"横にカプセル","扇状に飛び出す","下にカード"};
    static final String[] EDGE_OPENINGS={"横にカプセル","縦の引き出し","横長の引き出し"};
    /** Colours a user can pick from; anything else is typed in as #RRGGBB. */
    static final String[] COLOR_NAMES={"黒","白","青緑","紺","赤","緑","紫"};
    static final int[] COLORS={0x181a1e,0xffffff,0x087f78,0x1e2a4a,0xc62828,0x2e7d32,0x6a1b9a};
    static final int GO=0xff2ebe6e, STOP=0xffe5484d;

    /** The bar's size, in percent of the default (ボタンの大きさ in the settings). Set by build for what it makes. */
    static int scale=100;
    static int size(Context c){return Math.max(70,Math.min(150,Store.prefs(c).getInt("barScale",100)));}
    static int px(Context c,int dp){return Math.round(dp*scale/100f*c.getResources().getDisplayMetrics().density);}
    static float sp(float size){return size*scale/100f;}
    static String style(Context c){
        String s=Store.prefs(c).getString("barStyle","capsule");
        for(String known:STYLES)if(known.equals(s))return s;
        return "capsule";
    }
    /** 0, 1 or 2 from BALL_OPENINGS or EDGE_OPENINGS; the other shapes have only the one way. */
    static int opening(Context c,String style){return Math.max(0,Math.min(2,Store.prefs(c).getInt(style+"Open",0)));}
    static int color(Context c){return Store.prefs(c).getInt("capsuleColor",COLORS[0])&0xffffff;}
    static int opacity(Context c){return Store.prefs(c).getInt("capsuleOpacity",205);}
    /** Dark ink on a light background, white on a dark one. */
    static int ink(int rgb){
        double light=(.299*((rgb>>16)&255)+.587*((rgb>>8)&255)+.114*(rgb&255))/255;
        return light>.6?0xff202124:Color.WHITE;
    }

    /**
     * What was built: the whole bar, the view dragging and the open/close tap go through, and the 開始/停止 view (or
     * null). anchor is the point (in px, inside the bar) that stays put on screen as the bar opens and closes - the
     * middle of the ball or the handle - and rest is where that point is when the bar is closed.
     */
    record Built(View view,View grip,View toggle,int anchorX,int anchorY,int restX,int restY) {}

    /**
     * Builds the bar. open is for the ball and the edge handle: whether their ☰ and 開始/停止 are out. onRight is for
     * them too: the ball on the right half of the screen or the handle on the right edge opens to the left. status is
     * the line at the top of the edge's wide drawer. The runnables may be null (the previews).
     */
    static Built build(Context c,String style,int opening,int rgb,int alpha,int percent,boolean running,boolean open,boolean onRight,
                       CharSequence status,Runnable onMenu,Runnable onToggle,Runnable onOpen){
        scale=percent;
        int background=(alpha<<24)|rgb,ink=ink(rgb);
        LinearLayout bar=new LinearLayout(c);bar.setGravity(Gravity.CENTER_VERTICAL);
        switch(style){
            case "circle" -> {
                TextView menu=round(c,"☰",background,ink,48,18);click(menu,onMenu);bar.addView(menu,size(c,48,48,0));
                TextView toggle=round(c,running?"■":"▶",running?STOP:GO,Color.WHITE,48,18);click(toggle,onToggle);bar.addView(toggle,size(c,48,48,8));
                return new Built(bar,menu,toggle,0,0,0,0);
            }
            case "square" -> {
                TextView menu=box(c,"☰",background,ink,52,48,18);click(menu,onMenu);bar.addView(menu,size(c,52,48,0));
                TextView toggle=box(c,running?"停止":"開始",running?STOP:GO,Color.WHITE,76,48,15);click(toggle,onToggle);bar.addView(toggle,size(c,76,48,6));
                return new Built(bar,menu,toggle,0,0,0,0);
            }
            case "mini" -> {
                LinearLayout pill=capsule(c,background,ink,running,false,34,onMenu,onToggle);bar.addView(pill);
                return new Built(bar,pill.getChildAt(0),pill.getChildAt(2),0,0,0,0);
            }
            case "ball" -> {return ball(c,bar,opening,background,ink,running,open,onRight,onMenu,onToggle,onOpen);}
            case "edge" -> {return edge(c,bar,opening,background,ink,running,open,onRight,status,onMenu,onToggle,onOpen);}
            default -> {
                LinearLayout pill=capsule(c,background,ink,running,true,40,onMenu,onToggle);bar.addView(pill);
                return new Built(bar,pill.getChildAt(0),pill.getChildAt(2),0,0,0,0);
            }
        }
    }

    /** The ball: a ring, green to start and red while reading, that opens into one of BALL_OPENINGS. */
    static Built ball(Context c,LinearLayout bar,int opening,int background,int ink,boolean running,boolean open,boolean onRight,
                      Runnable onMenu,Runnable onToggle,Runnable onOpen){
        int ring=running?STOP:GO,half=px(c,23);
        boolean cross=open&&opening==1;
        TextView ball=round(c,cross?"×":"●",background,cross?ink:ring,46,cross?22:16);
        ((GradientDrawable)ball.getBackground()).setStroke(px(c,3),ring);click(ball,onOpen);
        if(!open){bar.addView(ball,size(c,46,46,0));return new Built(bar,ball,null,half,half,half,half);}
        if(opening==1){
            // Two circles on an arc around the ball, each with its name beside it.
            int width=190;FrameLayout fan=new FrameLayout(c);
            place(c,fan,ball,0,50,46,46,onRight);
            TextView menu=round(c,"☰",background,ink,44,17);click(menu,onMenu);place(c,fan,menu,50,2,44,44,onRight);
            TextView toggle=round(c,running?"■":"▶",ring,Color.WHITE,44,17);click(toggle,onToggle);place(c,fan,toggle,50,100,44,44,onRight);
            place(c,fan,label(c,"メニュー",background,ink,onMenu),100,8,-2,32,onRight);
            place(c,fan,label(c,running?"停止":"開始",background,ink,onToggle),100,106,-2,32,onRight);
            bar.addView(fan,new LinearLayout.LayoutParams(px(c,width),px(c,146)));
            return new Built(bar,ball,toggle,onRight?px(c,width)-half:half,px(c,73),half,half);
        }
        if(opening==2){
            // A small card under the ball: a big 認識開始, and the menu under it.
            int width=170;LinearLayout column=new LinearLayout(c);column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(onRight?Gravity.END:Gravity.START);
            column.addView(ball,size(c,46,46,0));
            LinearLayout card=new LinearLayout(c);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Ui.rounded(c,background,20*scale/100));
            card.setPadding(px(c,8),px(c,8),px(c,8),px(c,4));
            TextView toggle=box(c,running?"■  停止":"▶  認識開始",ring,Color.WHITE,0,0,15);click(toggle,onToggle);
            card.addView(toggle,new LinearLayout.LayoutParams(-1,px(c,46)));
            TextView menu=box(c,"☰   メニュー",Color.TRANSPARENT,ink,0,0,15);click(menu,onMenu);
            card.addView(menu,new LinearLayout.LayoutParams(-1,px(c,42)));
            var lp=new LinearLayout.LayoutParams(px(c,width),-2);lp.topMargin=px(c,6);column.addView(card,lp);
            bar.addView(column,new LinearLayout.LayoutParams(px(c,width),-2));
            return new Built(bar,ball,toggle,onRight?px(c,width)-half:half,half,half,half);
        }
        LinearLayout pill=capsule(c,background,ink,running,true,40,onMenu,onToggle);
        int pillWidth=px(c,44)+px(c,1)+px(c,76);
        if(onRight){bar.addView(pill,size(c,-2,40,0));bar.addView(ball,size(c,46,46,8));}
        else{bar.addView(ball,size(c,46,46,0));bar.addView(pill,size(c,-2,40,8));}
        return new Built(bar,ball,pill.getChildAt(2),onRight?pillWidth+px(c,8)+half:half,half,half,half);
    }

    /** The edge handle: a thin bar on the screen's edge that opens into one of EDGE_OPENINGS. */
    static Built edge(Context c,LinearLayout bar,int opening,int background,int ink,boolean running,boolean open,boolean onRight,
                      CharSequence status,Runnable onMenu,Runnable onToggle,Runnable onOpen){
        int ring=running?STOP:GO,restY=px(c,36);
        if(open&&opening==1){
            // A tall drawer against the edge: ▶ over ☰, each with its name, and a nub on the inner side to close it.
            LinearLayout drawer=drawer(c,background,onRight,20);drawer.setGravity(Gravity.CENTER_VERTICAL);click(drawer,onOpen);
            LinearLayout column=new LinearLayout(c);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView toggle=round(c,running?"■":"▶",ring,Color.WHITE,48,18);click(toggle,onToggle);column.addView(toggle,size(c,48,48,0));
            column.addView(caption(c,running?"停止":"開始",ink,12));
            LinearLayout menu=new LinearLayout(c);menu.setOrientation(LinearLayout.VERTICAL);menu.setGravity(Gravity.CENTER_HORIZONTAL);click(menu,onMenu);
            TextView burger=caption(c,"☰",ink,20);menu.addView(burger);menu.addView(caption(c,"メニュー",(ink&0xffffff)|0xb3000000,11));
            var menuLp=new LinearLayout.LayoutParams(-2,-2);menuLp.topMargin=px(c,12);column.addView(menu,menuLp);
            View nub=new View(c);nub.setBackground(Ui.rounded(c,(ink&0xffffff)|0x55000000,2));
            var nubLp=new LinearLayout.LayoutParams(px(c,4),px(c,36));
            if(onRight){nubLp.setMarginStart(px(c,6));drawer.addView(nub,nubLp);}
            drawer.addView(column,new LinearLayout.LayoutParams(px(c,72),-2));
            if(!onRight){nubLp.setMarginEnd(px(c,6));drawer.addView(nub,nubLp);}
            bar.addView(drawer,new LinearLayout.LayoutParams(-2,px(c,176)));
            return new Built(bar,drawer,toggle,0,px(c,88),0,restY);
        }
        if(open&&opening==2){
            // A wide drawer: what is going on at the top, a big 認識開始 and the ☰ under it.
            LinearLayout drawer=drawer(c,background,onRight,22);drawer.setOrientation(LinearLayout.VERTICAL);click(drawer,onOpen);
            drawer.setPadding(px(c,16),px(c,12),px(c,16),px(c,12));
            TextView line=caption(c,"",(ink&0xffffff)|0xcc000000,13);line.setText(status==null?"":status);line.setGravity(Gravity.START);
            line.setSingleLine();line.setEllipsize(android.text.TextUtils.TruncateAt.END);drawer.addView(line,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout buttons=new LinearLayout(c);
            TextView toggle=box(c,running?"■  停止":"▶  認識開始",ring,Color.WHITE,0,0,15);click(toggle,onToggle);
            buttons.addView(toggle,new LinearLayout.LayoutParams(0,px(c,48),1));
            TextView menu=box(c,"☰",(ink&0xffffff)|0x26000000,ink,0,0,18);click(menu,onMenu);buttons.addView(menu,size(c,48,48,8));
            var lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(c,10);drawer.addView(buttons,lp);
            bar.addView(drawer,new LinearLayout.LayoutParams(px(c,250),-2));
            bar.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
            return new Built(bar,drawer,toggle,0,bar.getMeasuredHeight()/2,0,restY);
        }
        View handle=new View(c);var shape=Ui.rounded(c,background,6*scale/100);shape.setStroke(px(c,2),ring);handle.setBackground(shape);
        // A wider invisible strip around the thin handle, so it is easy to catch with a thumb.
        LinearLayout grip=new LinearLayout(c);grip.setGravity(Gravity.CENTER);grip.setPadding(px(c,6),0,px(c,6),0);
        grip.addView(handle,new LinearLayout.LayoutParams(px(c,8),px(c,64)));click(grip,onOpen);
        View toggle=null;LinearLayout pill=null;
        if(open){pill=capsule(c,background,ink,running,true,40,onMenu,onToggle);toggle=pill.getChildAt(2);}
        if(onRight&&pill!=null)bar.addView(pill,size(c,-2,40,0));
        bar.addView(grip,new LinearLayout.LayoutParams(-2,px(c,72)));
        if(!onRight&&pill!=null)bar.addView(pill,size(c,-2,40,2));
        return new Built(bar,grip,toggle,0,restY,0,restY);
    }

    /** ☰ | ▶ 開始 in one rounded capsule; withText false leaves only the ▶ / ■. Children: ☰, divider, toggle. */
    static LinearLayout capsule(Context c,int background,int ink,boolean running,boolean withText,int height,Runnable onMenu,Runnable onToggle){
        LinearLayout pill=new LinearLayout(c);pill.setGravity(Gravity.CENTER_VERTICAL);pill.setBackground(Ui.rounded(c,background,height*scale/200));
        TextView menu=new TextView(c);menu.setText("☰");menu.setTextSize(sp(height>=40?18:15));menu.setTextColor(ink);menu.setGravity(Gravity.CENTER);
        click(menu,onMenu);pill.addView(menu,new LinearLayout.LayoutParams(px(c,height+4),px(c,height)));
        View divider=new View(c);divider.setBackgroundColor((ink&0xffffff)|0x4d000000);pill.addView(divider,new LinearLayout.LayoutParams(px(c,1),px(c,height/2)));
        TextView toggle=new TextView(c);toggle.setGravity(Gravity.CENTER);toggle.setTextSize(sp(height>=40?15:14));toggle.setTextColor(ink);
        toggle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        var text=new android.text.SpannableString(withText?(running?"■ 停止":"▶ 開始"):(running?"■":"▶"));
        text.setSpan(new android.text.style.ForegroundColorSpan(running?STOP:GO),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        toggle.setText(text);click(toggle,onToggle);
        pill.addView(toggle,new LinearLayout.LayoutParams(px(c,withText?76:height+4),px(c,height)));
        return pill;
    }
    /** A drawer against the screen's edge: rounded only on the side facing into the screen. */
    static LinearLayout drawer(Context c,int background,boolean onRight,int radius){
        LinearLayout drawer=new LinearLayout(c);var shape=new GradientDrawable();shape.setColor(background);
        float r=px(c,radius);
        shape.setCornerRadii(onRight?new float[]{r,r,0,0,0,0,r,r}:new float[]{0,0,r,r,r,r,0,0});
        drawer.setBackground(shape);return drawer;
    }
    static TextView round(Context c,String text,int background,int ink,int size,int textSize){
        TextView v=new TextView(c);v.setText(text);v.setTextColor(ink);v.setTextSize(sp(textSize));v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.rounded(c,background,size*scale/200));return v;
    }
    static TextView box(Context c,String text,int background,int ink,int width,int height,int textSize){
        TextView v=new TextView(c);v.setText(text);v.setTextColor(ink);v.setTextSize(sp(textSize));v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));v.setBackground(Ui.rounded(c,background,12*scale/100));return v;
    }
    static TextView caption(Context c,String text,int ink,int textSize){
        TextView v=new TextView(c);v.setText(text);v.setTextColor(ink);v.setTextSize(sp(textSize));v.setGravity(Gravity.CENTER);return v;
    }
    /** The name beside a circle of the fan: a small pill of the bar's colour. */
    static TextView label(Context c,String text,int background,int ink,Runnable action){
        TextView v=caption(c,text,ink,13);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        v.setPadding(px(c,12),0,px(c,12),0);v.setBackground(Ui.rounded(c,background,16*scale/100));click(v,action);return v;
    }
    /** Puts child at (x,y) dp in the frame, measured from the right instead when mirrored. */
    static void place(Context c,FrameLayout frame,View child,int x,int y,int width,int height,boolean mirrored){
        var lp=new FrameLayout.LayoutParams(width<0?width:px(c,width),px(c,height),Gravity.TOP|(mirrored?Gravity.END:Gravity.START));
        lp.topMargin=px(c,y);if(mirrored)lp.setMarginEnd(px(c,x));else lp.setMarginStart(px(c,x));
        frame.addView(child,lp);
    }
    static void click(View v,Runnable action){if(action!=null)v.setOnClickListener(x->action.run());}
    static LinearLayout.LayoutParams size(Context c,int width,int height,int marginStart){
        var lp=new LinearLayout.LayoutParams(width<0?width:px(c,width),px(c,height));lp.setMarginStart(px(c,marginStart));return lp;
    }
}
