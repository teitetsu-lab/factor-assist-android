package jp.factor.overlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's own settings, in tabs: 全般 (the theme), フローティング (how the buttons over the game look), and
 * the personal ones. Everything is saved as it is changed; the floating buttons follow at once.
 */
public class SettingsActivity extends Activity {
    static final int GAME_COLOR=0xff96b0d6;
    final List<String> tabs=new ArrayList<>(List.of("全般","フローティング"));
    LinearLayout page;String tab,shownTab;ScrollView scroll;
    /** Rebuilds each preview, after the colour, the opacity or the size changed. */
    final List<Runnable> previews=new ArrayList<>();

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(Ui.themed(base,false));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        tab=state!=null?state.getString("tab","全般"):getIntent().getStringExtra("tab")!=null?getIntent().getStringExtra("tab"):"全般";
        if(!tabs.contains(tab))tab="全般";
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("tab",tab);}

    void render(){
        Ui.palette(this);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        // Picking a shape rebuilds the page; it stays scrolled where it was.
        int keep=scroll!=null&&tab.equals(shownTab)?scroll.getScrollY():0;shownTab=tab;
        scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.PAGE);scroll.addView(content);setContentView(scroll);
        if(keep>0)scroll.post(()->scroll.scrollTo(0,keep));
        // Room for the keyboard too, and the field being typed in is scrolled up above it.
        content.setOnApplyWindowInsetsListener((v,insets)->{var i=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            int keyboard=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            v.setPadding(Ui.dp(this,16),i.top+Ui.dp(this,8),Ui.dp(this,16),Math.max(i.bottom,keyboard)+Ui.dp(this,16));
            if(keyboard>0)scroll.post(this::showFocused);
            return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);content.addView(header);
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(30);back.setTextColor(Ui.INK);back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.ripple(this,null,24));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,48)));
        TextView title=new TextView(this);title.setText("設定");title.setTextSize(24);title.setTextColor(Ui.INK);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));header.addView(title);
        // The tabs as one segmented bar: the chosen one is a white pill in it.
        LinearLayout bar=new LinearLayout(this);bar.setBackground(Ui.rounded(this,Ui.SURFACE,24));bar.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));
        var barLp=new LinearLayout.LayoutParams(-1,-2);barLp.setMargins(0,Ui.dp(this,12),0,Ui.dp(this,16));content.addView(bar,barLp);
        for(String name:tabs){
            TextView t=new TextView(this);t.setText(name);t.setTextSize(13);t.setGravity(Gravity.CENTER);t.setSingleLine();
            t.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            boolean on=name.equals(tab);t.setTextColor(on?Ui.ACCENT:Ui.MUTED);if(on)t.setBackground(Ui.rounded(this,Ui.CARD,20));
            t.setOnClickListener(v->{if(!name.equals(tab)){tab=name;render();}});
            bar.addView(t,new LinearLayout.LayoutParams(0,Ui.dp(this,40),name.length()+2));
        }
        page=content;previews.clear();
        switch(tab){
            case "フローティング" -> floating();
            default -> general();
        }
    }

    /** Scrolls the field being typed in up above the keyboard. */
    void showFocused(){
        View focused=getCurrentFocus();if(focused==null||scroll==null)return;
        var insets=scroll.getRootWindowInsets();if(insets==null)return;
        int keyboard=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        int[] at=new int[2];focused.getLocationInWindow(at);
        int below=at[1]+focused.getHeight()+Ui.dp(this,24)-(scroll.getRootView().getHeight()-keyboard);
        if(below>0)scroll.smoothScrollBy(0,below);
    }

    // ---- 全般
    void general(){
        var prefs=Store.prefs(this);
        Ui.cardLabel(page,"テーマ");
        LinearLayout card=Ui.card(page);
        RadioGroup group=new RadioGroup(this);card.addView(group);
        String[][] themes={{"ライト","明るい色"},{"ダーク","暗い色"},{"端末に従う","端末のダークモードの設定に合わせる"}};
        for(int i=0;i<themes.length;i++){
            RadioButton r=new RadioButton(this);r.setId(View.generateViewId());
            var text=new android.text.SpannableStringBuilder(themes[i][0]+"   "+themes[i][1]);
            text.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED),themes[i][0].length(),text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.RelativeSizeSpan(.85f),themes[i][0].length(),text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            r.setText(text);r.setTextColor(Ui.INK);r.setTextSize(15);r.setMinHeight(Ui.dp(this,48));
            r.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            group.addView(r);if(Ui.theme(this)==i)r.setChecked(true);
            int mode=i;r.setOnCheckedChangeListener((b,on)->{if(on&&Ui.theme(this)!=mode){prefs.edit().putInt("theme",mode).apply();themeChanged();}});
        }
        Ui.cardLabel(page,"認識開始を押したとき");
        LinearLayout start=Ui.card(page);
        RadioGroup starts=new RadioGroup(this);start.addView(starts);
        String[][] choices={{"すぐ読み取りを始める","フローティングは認識中の状態で出る"},{"停止状態で待つ","フローティングの ▶ 開始で読み取りを始める"}};
        boolean reading=CaptureService.startsReading(this);
        for(int i=0;i<choices.length;i++){
            RadioButton r=radio(choices[i][0],choices[i][1]);starts.addView(r);
            if((i==0)==reading)r.setChecked(true);
            boolean on=i==0;r.setOnCheckedChangeListener((b,checked)->{if(checked)prefs.edit().putBoolean("startReading",on).apply();});
        }
        Ui.cardLabel(page,"因子画面の表示");
        markChoices("factorMarks",false,new String[]{"frame","letter"},new String[]{"枠","★の左に文字"});
        Ui.cardLabel(page,"スキル取得画面の表示");
        markChoices("skillMarks",true,new String[]{"frame","line"},new String[]{"枠","名前の下に線"});

        Ui.cardLabel(page,"ゲームの上の表示");
        LinearLayout overlay=Ui.card(page);
        switchRow(overlay,"メニューや詳細もテーマに合わせる","オフならゲームの上は明るい色のまま",prefs.getBoolean("overlayTheme",true),
            on->{prefs.edit().putBoolean("overlayTheme",on).apply();themeChanged();});
    }
    /** The frame, or the letter/line in its place, for one screen: two cards with a sketch of the row. */
    void markChoices(String key,boolean skill,String[] values,String[] names){
        var prefs=Store.prefs(this);String now=prefs.getString(key,values[0]);
        LinearLayout row=new LinearLayout(this);page.addView(row,new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<values.length;i++){
            String value=values[i];boolean on=value.equals(now);
            LinearLayout card=choice(on,()->{prefs.edit().putString(key,value).apply();render();});
            MarkPreview sketch=new MarkPreview(this,skill,i==1);card.addView(sketch,new LinearLayout.LayoutParams(-1,Ui.dp(this,64)));
            LinearLayout nameRow=new LinearLayout(this);nameRow.setGravity(Gravity.CENTER_VERTICAL);nameRow.setPadding(Ui.dp(this,4),Ui.dp(this,8),0,0);
            TextView name=new TextView(this);name.setText(names[i]);name.setTextColor(Ui.INK);name.setTextSize(14);
            name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));nameRow.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            if(on)nameRow.addView(check(),new LinearLayout.LayoutParams(Ui.dp(this,22),Ui.dp(this,22)));
            card.addView(nameRow);
            var lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(i==0?0:Ui.dp(this,6),0,i==0?Ui.dp(this,6):0,Ui.dp(this,8));row.addView(card,lp);
        }
    }
    /** A choice with its note after it, in a smaller muted size. */
    RadioButton radio(String name,String note){
        RadioButton r=new RadioButton(this);r.setId(View.generateViewId());
        var text=new android.text.SpannableStringBuilder(name+"   "+note);
        text.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED),name.length(),text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.RelativeSizeSpan(.85f),name.length(),text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        r.setText(text);r.setTextColor(Ui.INK);r.setTextSize(15);r.setMinHeight(Ui.dp(this,48));
        r.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        return r;
    }
    /** The new theme shows at once: this screen is rebuilt, and the floating window is restarted in it. */
    void themeChanged(){
        if(FloatingService.shown){stopService(new Intent(this,FloatingService.class));startForegroundService(new Intent(this,FloatingService.class));}
        recreate();
    }
    void switchRow(LinearLayout card,String name,String note,boolean value,java.util.function.Consumer<Boolean> changed){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
        LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        TextView n=new TextView(this);n.setText(name);n.setTextColor(Ui.INK);n.setTextSize(15);texts.addView(n);
        TextView s=new TextView(this);s.setText(note);s.setTextColor(Ui.MUTED);s.setTextSize(12);texts.addView(s);
        Switch sw=new Switch(this);sw.setChecked(value);
        sw.setThumbTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{Ui.ACCENT,0xffbdbdbd}));
        sw.setTrackTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{(Ui.ACCENT&0xffffff)|0x80000000,0x40808080}));
        sw.setOnCheckedChangeListener((b,on)->changed.accept(on));row.addView(sw);
        row.setOnClickListener(v->sw.toggle());
    }

    // ---- フローティング
    void floating(){
        var prefs=Store.prefs(this);String style=FloatingBar.style(this);
        Ui.cardLabel(page,"ボタンの形");
        GridLayout grid=new GridLayout(this);grid.setColumnCount(2);page.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<FloatingBar.STYLES.length;i++){
            String s=FloatingBar.STYLES[i];boolean on=s.equals(style);
            LinearLayout card=choice(on,()->{prefs.edit().putString("barStyle",s).apply();render();});
            boolean edge="edge".equals(s);
            card.addView(preview(64,edge?Gravity.START|Gravity.CENTER_VERTICAL:Gravity.CENTER,
                ()->FloatingBar.build(this,s,0,FloatingBar.color(this),FloatingBar.opacity(this),80,false,false,false,null,null,null,null).view()));
            LinearLayout nameRow=new LinearLayout(this);nameRow.setGravity(Gravity.CENTER_VERTICAL);nameRow.setPadding(Ui.dp(this,4),Ui.dp(this,10),0,0);
            TextView name=new TextView(this);name.setText(FloatingBar.NAMES[i]);name.setTextColor(Ui.INK);name.setTextSize(15);
            name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));nameRow.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            if(on)nameRow.addView(check(),new LinearLayout.LayoutParams(Ui.dp(this,22),Ui.dp(this,22)));
            card.addView(nameRow);
            TextView note=new TextView(this);note.setText(FloatingBar.NOTES[i]);note.setTextColor(Ui.MUTED);note.setTextSize(11);
            note.setPadding(Ui.dp(this,4),Ui.dp(this,2),0,0);card.addView(note);
            var lp=new GridLayout.LayoutParams(GridLayout.spec(i/2),GridLayout.spec(i%2,1f));lp.width=0;
            lp.setMargins(i%2==0?0:Ui.dp(this,6),0,i%2==0?Ui.dp(this,6):0,Ui.dp(this,12));grid.addView(card,lp);
        }
        // The ball and the edge handle each open in one of three ways; the other shapes have just the one.
        if("ball".equals(style)||"edge".equals(style)){
            boolean edge="edge".equals(style);String[] names=edge?FloatingBar.EDGE_OPENINGS:FloatingBar.BALL_OPENINGS;
            int opening=FloatingBar.opening(this,style);
            Ui.cardLabel(page,"開き方（"+(edge?"エッジ":"ボール")+"）");
            LinearLayout row=new LinearLayout(this);page.addView(row,new LinearLayout.LayoutParams(-1,-2));
            for(int i=0;i<3;i++){
                int n=i;LinearLayout card=choice(i==opening,()->{prefs.edit().putInt(style+"Open",n).apply();render();});
                card.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,10));
                card.addView(preview(104,edge?Gravity.START|Gravity.CENTER_VERTICAL:Gravity.CENTER,
                    ()->FloatingBar.build(this,style,n,FloatingBar.color(this),FloatingBar.opacity(this),edge?55:48,false,true,false,status(),null,null,null).view()));
                TextView name=new TextView(this);name.setText(names[i]);name.setTextColor(Ui.INK);name.setTextSize(13);name.setGravity(Gravity.CENTER);
                name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));name.setPadding(0,Ui.dp(this,8),0,0);card.addView(name);
                var lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(i==0?0:Ui.dp(this,4),0,i==2?0:Ui.dp(this,4),Ui.dp(this,12));row.addView(card,lp);
            }
        }
        Ui.cardLabel(page,"ボタンの大きさ");
        slider(Ui.card(page),70,150,"barScale",100,"%");
        Ui.cardLabel(page,"色");
        colors(Ui.card(page));
        Ui.cardLabel(page,"不透明度");
        slider(Ui.card(page),40,255,"capsuleOpacity",205,"");
    }
    CharSequence status(){
        String name="";var p=Store.selected(this);if(p!=null)name=p.optString("name");
        var s=new android.text.SpannableStringBuilder("● 停止中"+(name.isEmpty()?"":"　"+name));
        s.setSpan(new android.text.style.ForegroundColorSpan(FloatingBar.GO),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
    /** A card that can be chosen: outlined in the accent colour while it is the one in use. */
    LinearLayout choice(boolean on,Runnable pick){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,12));
        var shape=Ui.rounded(this,Ui.CARD,18);shape.setStroke(Ui.dp(this,on?2:1),on?Ui.ACCENT:Ui.LINE);
        card.setBackground(Ui.ripple(this,shape,18));card.setOnClickListener(v->pick.run());return card;
    }
    TextView check(){
        TextView v=new TextView(this);v.setText("✓");v.setTextColor(Ui.ON_ACCENT);v.setTextSize(12);v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);v.setBackground(Ui.rounded(this,Ui.ACCENT,11));return v;
    }
    /**
     * A strip of "game" with the bar on it, as it will float. The bar is built without actions, and the strip
     * takes the taps so the whole card is what is tapped.
     */
    FrameLayout preview(int height,int gravity,java.util.function.Supplier<View> bar){
        FrameLayout strip=new FrameLayout(this);strip.setBackground(Ui.rounded(this,GAME_COLOR,12));strip.setClipToOutline(true);
        strip.setLayoutParams(new LinearLayout.LayoutParams(-1,Ui.dp(this,height)));
        Runnable fill=()->{strip.removeAllViews();View v=bar.get();v.setClickable(false);
            strip.addView(v,new FrameLayout.LayoutParams(-2,-2,gravity));};
        fill.run();previews.add(fill);
        return strip;
    }
    /** A slider with the number beside it, which can also be typed in. */
    void slider(LinearLayout card,int min,int max,String key,int def,String unit){
        var prefs=Store.prefs(this);int value=Math.max(min,Math.min(max,prefs.getInt(key,def)));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
        SeekBar bar=new SeekBar(this);bar.setMin(min);bar.setMax(max);bar.setProgress(value);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        row.addView(bar,new LinearLayout.LayoutParams(0,-2,1));
        EditText number=new EditText(this);number.setSingleLine();number.setGravity(Gravity.CENTER);number.setTextColor(Ui.INK);
        number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);number.setText(String.valueOf(value));
        number.setBackground(Ui.rounded(this,Ui.SURFACE,12));number.setPadding(Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,6),Ui.dp(this,8));
        row.addView(number,new LinearLayout.LayoutParams(Ui.dp(this,64),-2));
        if(!unit.isEmpty()){TextView u=new TextView(this);u.setText(unit);u.setTextColor(Ui.MUTED);u.setPadding(Ui.dp(this,6),0,0,0);row.addView(u);}
        boolean[] typing={false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){refreshPreviews();}
            public void onProgressChanged(SeekBar s,int n,boolean user){if(!user)return;prefs.edit().putInt(key,n).apply();typing[0]=true;number.setText(String.valueOf(n));typing[0]=false;}});
        number.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence c,int a,int b,int d){}public void onTextChanged(CharSequence c,int a,int b,int d){}
            public void afterTextChanged(android.text.Editable e){
                if(typing[0])return;
                try{int n=Integer.parseInt(e.toString());if(n<min||n>max)return;prefs.edit().putInt(key,n).apply();bar.setProgress(n);refreshPreviews();}catch(NumberFormatException ignored){}
            }});
    }
    void refreshPreviews(){for(Runnable r:previews)r.run();}
    /** The preset colours as circles, and any other typed in as #RRGGBB. */
    void colors(LinearLayout card){
        var prefs=Store.prefs(this);int current=FloatingBar.color(this);
        LinearLayout row=new LinearLayout(this);card.addView(row);
        EditText hex=new EditText(this);
        List<View> circles=new ArrayList<>();
        for(int i=0;i<FloatingBar.COLORS.length;i++){
            int rgb=FloatingBar.COLORS[i];
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);
            View circle=new View(this);circles.add(circle);
            cell.addView(circle,new LinearLayout.LayoutParams(Ui.dp(this,34),Ui.dp(this,34)));
            TextView name=new TextView(this);name.setText(FloatingBar.COLOR_NAMES[i]);name.setTextColor(Ui.INK);name.setTextSize(11);
            name.setPadding(0,Ui.dp(this,4),0,0);cell.addView(name);
            cell.setOnClickListener(v->{hex.setText(String.format("#%06X",rgb));});
            row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
        }
        Runnable ring=()->{int now=FloatingBar.color(this);
            for(int i=0;i<circles.size();i++){var shape=Ui.rounded(this,0xff000000|FloatingBar.COLORS[i],17);
                boolean on=FloatingBar.COLORS[i]==now;shape.setStroke(Ui.dp(this,on?3:1),on?Ui.ACCENT:Ui.LINE);circles.get(i).setBackground(shape);}};
        ring.run();
        LinearLayout free=new LinearLayout(this);free.setGravity(Gravity.CENTER_VERTICAL);free.setPadding(0,Ui.dp(this,14),0,0);card.addView(free);
        TextView label=new TextView(this);label.setText("自由入力");label.setTextColor(Ui.INK);label.setTextSize(14);label.setPadding(0,0,Ui.dp(this,12),0);free.addView(label);
        hex.setSingleLine();hex.setTextColor(Ui.INK);hex.setText(String.format("#%06X",current));hex.setHint("#RRGGBB");
        hex.setBackground(Ui.rounded(this,Ui.SURFACE,12));hex.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,8));
        free.addView(hex,new LinearLayout.LayoutParams(Ui.dp(this,130),-2));
        View sample=new View(this);free.addView(sample,sizeWithStart(28,12));
        Runnable showSample=()->{var shape=Ui.rounded(this,0xff000000|FloatingBar.color(this),14);shape.setStroke(Ui.dp(this,1),Ui.LINE);sample.setBackground(shape);};
        showSample.run();
        hex.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence c,int a,int b,int d){}public void onTextChanged(CharSequence c,int a,int b,int d){}
            public void afterTextChanged(android.text.Editable e){
                String s=e.toString().trim().replace("#","");
                if(!s.matches("[0-9a-fA-F]{6}"))return;
                int rgb=Integer.parseInt(s,16);if(rgb==FloatingBar.color(SettingsActivity.this))return;
                prefs.edit().putInt("capsuleColor",rgb).apply();ring.run();showSample.run();refreshPreviews();
            }});
    }
    LinearLayout.LayoutParams sizeWithStart(int size,int start){var lp=new LinearLayout.LayoutParams(Ui.dp(this,size),Ui.dp(this,size));lp.setMarginStart(Ui.dp(this,start));return lp;}

}
