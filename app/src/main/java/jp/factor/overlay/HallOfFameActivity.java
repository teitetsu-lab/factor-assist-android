package jp.factor.overlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

/**
 * The 殿堂入りウマ娘 kept so far (see HallOfFame), to look through and to search by their factors: each factor asked
 * for with the fewest stars it must have. A horse comes up for every factor it or a parent has, and the list is in
 * order of how many the horse itself has, then of how many more its parents add - 子5, 子4・親1, 子4, 子3・親2 … -
 * each factor shown as 名前(子/親1/親2) stars, each group folding away under its heading. Tapping one shows its three
 * images; sliding one sideways uncovers a button that deletes it.
 */
public class HallOfFameActivity extends Activity {
    /** A horse as kept: what its folder's meta.json says, and a small picture of its stats. */
    record Entry(File dir,String name,String epithet,String evaluation,long savedAt,List<HallOfFame.Factor> factors,List<String> uris,int[] stats,int skills) {
        int score(){try{return Integer.parseInt(evaluation);}catch(Exception e){return 0;}}
        /** The horse's own factors' stars, summed; how many factors it has, and with its parents'. */
        int ownStars(){int n=0;for(HallOfFame.Factor f:factors)if(f.who()==0)n+=f.stars();return n;}
        int ownCount(){int n=0;for(HallOfFame.Factor f:factors)if(f.who()==0)n++;return n;}
    }
    /**
     * The orders the list can be in: what each is by, as the card's third line shows it. The arrow beside the choice
     * turns it round (high or low first; for the name ア to ン or back).
     */
    enum Order {
        EVALUATION("評価点"),SAVED("保存日"),NAME("名前"),SPEED("スピード"),STAMINA("スタミナ"),POWER("パワー"),GUTS("根性"),WIT("賢さ"),
        OWN_STARS("子の★合計"),OWN_COUNT("子の因子の数"),ALL_COUNT("因子の数（親も含む）"),SKILLS("スキルの数");
        final String label;Order(String label){this.label=label;}
        Comparator<Entry> by(){return switch(this){
            case EVALUATION->Comparator.comparingInt(Entry::score);
            case SAVED->Comparator.comparingLong(Entry::savedAt);
            case NAME->Comparator.comparing((Entry e)->Store.searchKey(e.name())).thenComparing(e->Store.searchKey(e.epithet()));
            case SPEED,STAMINA,POWER,GUTS,WIT->Comparator.comparingInt(e->e.stats()[ordinal()-SPEED.ordinal()]);
            case OWN_STARS->Comparator.comparingInt(Entry::ownStars);
            case OWN_COUNT->Comparator.comparingInt(Entry::ownCount);
            case ALL_COUNT->Comparator.comparingInt(e->e.factors().size());
            // Not known (saved before the lists were kept): last whichever way round.
            case SKILLS->Comparator.comparingInt(Entry::skills);};}
        /** The card's third line: the value it is ordered by (the evaluation when by name, which the card shows anyway). */
        String shown(Entry e){return switch(this){
            case EVALUATION,NAME->"評価点 "+String.format(Locale.JAPAN,"%,d",e.score());
            case SAVED->new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.JAPAN).format(new Date(e.savedAt()));
            case SPEED,STAMINA,POWER,GUTS,WIT->label+" "+e.stats()[ordinal()-SPEED.ordinal()];
            case OWN_STARS->"子の★ "+e.ownStars();
            case OWN_COUNT->"子の因子 "+e.ownCount()+"個";
            case ALL_COUNT->"因子 "+e.factors().size()+"個";
            case SKILLS->e.skills()<0?"スキル ?":"スキル "+e.skills()+"個";};}
    }
    /** How many skills the horse has: the skill cards in the lists kept when it was saved (layout.json); -1 without them. */
    static int skills(File dir){
        try{File f=new File(dir,"layout.json");if(!f.exists())return -1;
            var rows=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("tabs").getJSONObject(0).getJSONArray("rows");
            int n=0;for(int i=0;i<rows.length();i++){var cards=rows.getJSONObject(i).getJSONArray("cards");for(int k=0;k<cards.length();k++)if(!cards.isNull(k))n++;}
            return n;
        }catch(Exception e){return -1;}
    }
    /** A factor searched for, and the fewest stars it must have. */
    record Want(String name,int stars) {}

    final List<Want> wants=new ArrayList<>();
    List<Entry> entries=List.of();
    /** The list while it is on screen: a horse saved meanwhile is added to it at once (see saved). */
    private static volatile HallOfFameActivity open;
    /** A horse has just been saved: the list, if it is on screen, is read again. */
    static void saved(){HallOfFameActivity a=open;if(a!=null)a.runOnUiThread(()->{if(a.isFinishing())return;a.entries=load(a);a.refresh();});}
    /** Back on screen (from the game, after taking a horse): the list read again, so a horse saved meanwhile is in it. */
    @Override protected void onStart(){super.onStart();open=this;entries=load(this);refresh();}
    @Override protected void onStop(){if(open==this)open=null;super.onStop();}
    /** How the list is ordered (high first unless turned round), and whether it is a grid of two columns; kept in the settings. */
    Order order=Order.EVALUATION;boolean ascending,grid;
    void keepView(){Store.prefs(this).edit().putString("hofOrder",order.name()).putBoolean("hofAscending",ascending).putBoolean("hofGrid",grid).apply();}
    LinearLayout results;FlowLayout chips;
    /** The groups folded away, by their heading. */
    final Set<String> folded=new HashSet<>();

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(Ui.themed(base,false));themeMade=Ui.theme(base);}
    /** The theme this screen was made in: changed meanwhile, coming back makes it again in the new one (as the top screen does). */
    int themeMade=-1;
    @Override protected void onRestart(){super.onRestart();if(Ui.theme(this)!=themeMade)recreate();}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        var prefs=Store.prefs(this);try{order=Order.valueOf(prefs.getString("hofOrder","EVALUATION"));}catch(Exception ignored){}
        ascending=prefs.getBoolean("hofAscending",false);grid=prefs.getBoolean("hofGrid",false);
        entries=load(this);build();
    }

    static List<Entry> load(Context c){
        List<Entry> out=new ArrayList<>();File[] dirs=new File(c.getFilesDir(),"halloffame").listFiles();if(dirs==null)return out;
        for(File d:dirs)try{
            JSONObject m=HallOfFame.readMeta(d);if(m==null||!m.optBoolean("saved"))continue;
            String[] key=m.optString("key").split("[|]");
            int[] stats=new int[5];if(key.length>2){String[] v=key[2].split(",");for(int i=0;i<5&&i<v.length;i++)try{stats[i]=Integer.parseInt(v[i]);}catch(Exception ignored){}}
            out.add(new Entry(d,m.optString("name"),m.optString("epithet"),key.length>1?key[1]:"",m.optLong("savedAt"),
                HallOfFame.factorsOf(m.optJSONArray("factors")),HallOfFame.strings(m.optJSONArray("uris")),stats,skills(d)));
        }catch(Exception ignored){}
        return out;
    }
    /**
     * The factor suggestions: every factor whose name - or its reading, in hiragana or katakana - has what is typed
     * anywhere in it (回復 or かいふく finds コーナー回復○), in the game's order.
     */
    static final class FactorSuggest extends ArrayAdapter<String> {
        private final List<FactorCatalog.Factor> all;private List<String> shown=new ArrayList<>();
        FactorSuggest(Context c){super(c,android.R.layout.simple_dropdown_item_1line);all=Ui.catalog(c).factors;}
        @Override public int getCount(){return shown.size();}
        @Override public String getItem(int i){return shown.get(i);}
        @Override public Filter getFilter(){return new Filter(){
            @Override protected FilterResults performFiltering(CharSequence typed){
                List<String> out=new ArrayList<>();String q=typed==null?"":Store.searchKey(typed.toString());
                if(!q.isEmpty())for(FactorCatalog.Factor f:all)if(f.searchText().contains(q))out.add(f.name());
                FilterResults r=new FilterResults();r.values=out;r.count=out.size();return r;}
            @SuppressWarnings("unchecked") @Override protected void publishResults(CharSequence typed,FilterResults r){
                shown=r.values==null?new ArrayList<>():(List<String>)r.values;notifyDataSetChanged();}
            @Override public CharSequence convertResultToString(Object v){return (String)v;}
        };}
    }
    /** The character name asked for (as Store.searchKey has it): only horses whose name or epithet has it are listed. */
    String nameQuery="";

    void build(){
        Ui.palette(this);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.PAGE);scroll.addView(content);setContentView(scroll);
        content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(Ui.dp(this,16),i.top+Ui.dp(this,16),Ui.dp(this,16),i.bottom+Ui.dp(this,16));return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);content.addView(header);
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(30);back.setTextColor(Ui.INK);back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.ripple(this,null,24));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,48)));
        TextView title=new TextView(this);title.setText("殿堂入りウマ娘");title.setTextSize(24);title.setTextColor(Ui.INK);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));header.addView(title);
        TextView note=new TextView(this);note.setText(entries.size()+"頭を保存済み。因子を足すと、子が持っている数の多い順に並べます（親の分は子のあと）。カードを左右にスライドすると消せます");note.setTextColor(Ui.MUTED);note.setTextSize(12);content.addView(note);

        // The factors asked for, as chips; tapped, a chip's least stars change or it goes.
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackground(Ui.rounded(this,Ui.CARD,18));
        box.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10));
        var boxLp=new LinearLayout.LayoutParams(-1,-2);boxLp.topMargin=Ui.dp(this,12);content.addView(box,boxLp);
        chips=new FlowLayout(this,Ui.dp(this,6));box.addView(chips);
        AutoCompleteTextView add=new AutoCompleteTextView(this);add.setHint("＋ 因子を追加（例：コーナー）");add.setSingleLine();add.setThreshold(1);add.setTextColor(Ui.INK);add.setHintTextColor(Ui.MUTED);
        add.setAdapter(new FactorSuggest(this));
        add.setOnItemClickListener((p,v,n,id)->{String name=(String)p.getItemAtPosition(n);add.setText("");
            boolean had=false;for(Want w:wants)if(w.name().equals(name))had=true;if(!had)wants.add(new Want(name,1));refresh();});
        box.addView(add,new LinearLayout.LayoutParams(-1,-2));
        // By character too: what is typed, anywhere in the name or the epithet, hiragana or katakana.
        EditText byName=new EditText(this);byName.setHint("キャラ名で探す（例：きたさん）");byName.setSingleLine();byName.setTextColor(Ui.INK);byName.setHintTextColor(Ui.MUTED);
        byName.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence t,int a,int b,int c){}public void onTextChanged(CharSequence t,int a,int b,int c){}
            public void afterTextChanged(android.text.Editable t){nameQuery=Store.searchKey(t.toString());refresh();}});
        box.addView(byName,0,new LinearLayout.LayoutParams(-1,-2));   // the name first, the factors under it

        // The order (chosen from a list), the arrow that turns it round, and list or grid.
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);bar.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,4));
        TextView sort=new TextView(this);sort.setTextColor(Ui.MUTED);sort.setTextSize(13);sort.setPadding(0,0,Ui.dp(this,8),0);
        TextView arrow=new TextView(this);arrow.setTextColor(Ui.INK);arrow.setTextSize(13);arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(Ui.dp(this,10),Ui.dp(this,4),Ui.dp(this,10),Ui.dp(this,4));arrow.setBackground(Ui.rounded(this,Ui.CARD,8));
        TextView asList=new TextView(this),asGrid=new TextView(this);
        Runnable label=()->{sort.setText("並び順："+order.label+" ▾");
            arrow.setText(order==Order.NAME?(ascending?"↓ ア→ン":"↑ ン→ア"):ascending?"↑ 低い順":"↓ 高い順");
            for(TextView t:new TextView[]{asList,asGrid}){boolean on=(t==asGrid)==grid;t.setTextColor(on?0xffffffff:Ui.MUTED);t.setBackground(Ui.rounded(this,on?Ui.ACCENT:Ui.CARD,8));}};
        sort.setOnClickListener(v->{String[] names=new String[Order.values().length];for(Order o:Order.values())names[o.ordinal()]=o.label;
            new AlertDialog.Builder(this).setTitle("並び順").setSingleChoiceItems(names,order.ordinal(),(d,n)->{order=Order.values()[n];
                ascending=order==Order.NAME;keepView();label.run();refresh();d.dismiss();}).show();});
        arrow.setOnClickListener(v->{ascending=!ascending;keepView();label.run();refresh();});
        asList.setText("☰");asGrid.setText("▦");
        for(TextView t:new TextView[]{asList,asGrid}){t.setTextSize(15);t.setGravity(Gravity.CENTER);t.setPadding(Ui.dp(this,10),Ui.dp(this,3),Ui.dp(this,10),Ui.dp(this,3));}
        asList.setOnClickListener(v->{grid=false;keepView();label.run();refresh();});asGrid.setOnClickListener(v->{grid=true;keepView();label.run();refresh();});
        bar.addView(sort);bar.addView(arrow);
        var gap=new LinearLayout.LayoutParams(-2,-2);gap.leftMargin=Ui.dp(this,12);bar.addView(asList,gap);
        var gap2=new LinearLayout.LayoutParams(-2,-2);gap2.leftMargin=Ui.dp(this,4);bar.addView(asGrid,gap2);
        label.run();content.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);content.addView(results);
        refresh();
    }

    /** How one horse stands for the factors asked: {the horse's own, its parents' that it lacks}. */
    int[] hits(Entry e){
        int own=0,parent=0;
        for(Want w:wants){boolean o=false,p=false;
            for(HallOfFame.Factor f:e.factors())if(f.name().equals(w.name())&&f.stars()>=w.stars()){if(f.who()==0)o=true;else p=true;}
            if(o)own++;else if(p)parent++;}
        return new int[]{own,parent};
    }
    /** A factor's stars as 子/親1/親2. */
    static String stars(Entry e,String name){
        int[] s=new int[3];for(HallOfFame.Factor f:e.factors())if(f.name().equals(name))s[f.who()]=Math.max(s[f.who()],f.stars());
        return s[0]+"/"+s[1]+"/"+s[2];
    }

    void refresh(){
        Ui.palette(this);   // shared colours: the floating menu may have set them for its own theme meanwhile
        chips.removeAllViews();
        for(Want w:wants){TextView chip=chip(w.name()+(w.stars()>1?" ★"+w.stars()+"以上":"")+"  ×",true,false);chip.setOnClickListener(v->editWant(w));chips.addView(chip);}
        results.removeAllViews();
        List<Entry> list=new ArrayList<>();Map<Entry,int[]> scores=new HashMap<>();
        for(Entry e:entries){if(!nameQuery.isEmpty()&&!Store.searchKey(e.name()+"\n"+e.epithet()).contains(nameQuery))continue;
            int[] h=hits(e);if(!wants.isEmpty()&&h[0]+h[1]==0)continue;scores.put(e,h);list.add(e);}
        // The factors searched for come first (the horse's own hits, then its parents'); the order chosen within them.
        Comparator<Entry> second=ascending?order.by():order.by().reversed();
        if(order==Order.SKILLS)second=Comparator.<Entry>comparingInt(e->e.skills()<0?1:0).thenComparing(second);
        list.sort(Comparator.<Entry>comparingInt(e->-scores.get(e)[0]).thenComparingInt(e->-scores.get(e)[1]).thenComparing(second));
        if(list.isEmpty()){TextView none=new TextView(this);none.setText(entries.isEmpty()?"まだ保存した殿堂入りウマ娘がありません。殿堂入りウマ娘の詳細を開いて、スキルと継承のタブを最後までスクロールすると保存されます":"当てはまるウマ娘がいません");
            none.setTextColor(Ui.MUTED);none.setPadding(0,Ui.dp(this,16),0,0);results.addView(none);return;}
        // Each group (子4・親1 …) under a heading that folds it away and back (▽ open, △ folded); what is folded stays so.
        String group=null;LinearLayout into=results;
        for(Entry e:list){
            int[] h=scores.get(e);
            if(!wants.isEmpty()){String g="子"+h[0]+(h[1]>0?"・親"+h[1]:"")+"（計"+(h[0]+h[1])+"）";
                if(!g.equals(group)){group=g;String key=g;
                    LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
                    TextView head=new TextView(this);head.setTextColor(Ui.INK);head.setTextSize(15);head.setTypeface(null,Typeface.BOLD);
                    head.setPadding(Ui.dp(this,4),Ui.dp(this,14),0,Ui.dp(this,6));
                    Runnable show=()->{boolean shut=folded.contains(key);head.setText((shut?"△  ":"▽  ")+key);body.setVisibility(shut?View.GONE:View.VISIBLE);};
                    head.setOnClickListener(v->{if(!folded.remove(key))folded.add(key);show.run();});
                    results.addView(head);results.addView(body);into=body;show.run();}}
            if(!grid){into.addView(card(e));continue;}
            // A grid: two to a row; a row's second place waits for the next card of the same group.
            LinearLayout row=into.getChildCount()>0&&into.getChildAt(into.getChildCount()-1) instanceof LinearLayout r&&"half".equals(r.getTag())?(LinearLayout)into.getChildAt(into.getChildCount()-1):null;
            if(row==null){row=new LinearLayout(this);row.setTag("half");into.addView(row,new LinearLayout.LayoutParams(-1,-2));
                var lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=Ui.dp(this,4);row.addView(gridCard(e),lp);
                var empty=new LinearLayout.LayoutParams(0,-2,1);empty.leftMargin=Ui.dp(this,4);row.addView(new View(this),empty);}
            else{row.setTag("full");var lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=Ui.dp(this,4);row.removeViewAt(1);row.addView(gridCard(e),lp);}
        }
    }
    /** A card for the grid: the picture of its stats over its name, epithet and the value it is ordered by. */
    View gridCard(Entry e){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Ui.ripple(this,Ui.rounded(this,Ui.CARD,18),18));
        card.setPadding(Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,12));
        ImageView thumb=new ImageView(this);File t=HallOfFame.thumb(e.dir());if(t!=null)thumb.setImageBitmap(BitmapFactory.decodeFile(t.getPath()));
        thumb.setAdjustViewBounds(true);card.addView(thumb,new LinearLayout.LayoutParams(-1,-2));
        TextView name=new TextView(this);name.setText(e.name().isEmpty()?"（名前不明）":e.name());name.setTextColor(Ui.INK);name.setTextSize(15);name.setTypeface(null,Typeface.BOLD);
        name.setPadding(0,Ui.dp(this,8),0,0);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
        TextView sub=new TextView(this);sub.setText(e.epithet()+"\n"+order.shown(e));sub.setTextColor(Ui.MUTED);sub.setTextSize(12);card.addView(sub);
        if(!wants.isEmpty()){FlowLayout found=new FlowLayout(this,Ui.dp(this,4));found.setPadding(0,Ui.dp(this,8),0,0);
            for(Want w:wants){boolean own=false,any=false;for(HallOfFame.Factor f:e.factors())if(f.name().equals(w.name())&&f.stars()>=w.stars()){any=true;if(f.who()==0)own=true;}
                found.addView(chip(w.name()+"("+stars(e,w.name())+")",any,own));}
            card.addView(found);}
        card.setOnClickListener(v->show(e));
        return swipeToDelete(card,()->remove(e));
    }
    void editWant(Want w){
        String[] items={"★1以上","★2以上","★3","外す"};
        new AlertDialog.Builder(this).setTitle(w.name()).setItems(items,(d,n)->{int i=wants.indexOf(w);if(i<0)return;
            if(n==3)wants.remove(i);else wants.set(i,new Want(w.name(),n+1));refresh();}).show();
    }

    View card(Entry e){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Ui.ripple(this,Ui.rounded(this,Ui.CARD,18),18));
        card.setPadding(Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);card.addView(top);
        ImageView thumb=new ImageView(this);File t=HallOfFame.thumb(e.dir());if(t!=null)thumb.setImageBitmap(BitmapFactory.decodeFile(t.getPath()));
        thumb.setAdjustViewBounds(true);top.addView(thumb,new LinearLayout.LayoutParams(Ui.dp(this,120),-2));
        LinearLayout text=new LinearLayout(this);text.setOrientation(LinearLayout.VERTICAL);text.setPadding(Ui.dp(this,12),0,0,0);top.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        TextView name=new TextView(this);name.setText(e.name().isEmpty()?"（名前不明）":e.name());name.setTextColor(Ui.INK);name.setTextSize(17);name.setTypeface(null,Typeface.BOLD);text.addView(name);
        TextView sub=new TextView(this);sub.setText(e.epithet()+"\n"+order.shown(e));
        sub.setTextColor(Ui.MUTED);sub.setTextSize(12);text.addView(sub);
        if(!wants.isEmpty()){
            FlowLayout found=new FlowLayout(this,Ui.dp(this,6));found.setPadding(0,Ui.dp(this,10),0,0);
            for(Want w:wants){
                boolean own=false,any=false;for(HallOfFame.Factor f:e.factors())if(f.name().equals(w.name())&&f.stars()>=w.stars()){any=true;if(f.who()==0)own=true;}
                found.addView(chip(w.name()+"("+stars(e,w.name())+")",any,own));
            }
            card.addView(found);
        }
        card.setOnClickListener(v->show(e));
        return swipeToDelete(card,()->remove(e));
    }
    /**
     * The card over a red strip with a trash button at each end: slid sideways it uncovers the button on the side it
     * left, which deletes. Let go short of halfway, it slides back; a tap on an uncovered card covers it again.
     */
    View swipeToDelete(View card,Runnable delete){
        FrameLayout holder=new FrameLayout(this);
        int reveal=Ui.dp(this,88),slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        LinearLayout under=new LinearLayout(this);under.setBackground(Ui.rounded(this,0xffe5484d,18));
        for(int side=0;side<2;side++){
            TextView trash=new TextView(this);trash.setText("🗑\n削除");trash.setGravity(Gravity.CENTER);trash.setTextColor(0xffffffff);trash.setTextSize(13);
            trash.setOnClickListener(v->{delete.run();card.animate().translationX(0).setDuration(150).start();});
            under.addView(trash,new LinearLayout.LayoutParams(reveal,-1));
            if(side==0){View gap=new View(this);under.addView(gap,new LinearLayout.LayoutParams(0,-1,1));}
        }
        holder.addView(under,new FrameLayout.LayoutParams(-1,-1));
        holder.addView(card,new FrameLayout.LayoutParams(-1,-2));
        float[] start=new float[2];boolean[] sliding=new boolean[1];
        card.setOnTouchListener((v,ev)->{
            switch(ev.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN -> {start[0]=ev.getRawX()-v.getTranslationX();start[1]=ev.getRawX();sliding[0]=false;}
                case android.view.MotionEvent.ACTION_MOVE -> {
                    float dx=ev.getRawX()-start[1];
                    if(!sliding[0]&&Math.abs(dx)>slop){sliding[0]=true;v.getParent().requestDisallowInterceptTouchEvent(true);v.setPressed(false);}
                    if(sliding[0]){v.setTranslationX(Math.max(-reveal*1.2f,Math.min(reveal*1.2f,ev.getRawX()-start[0])));return true;}
                }
                case android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL -> {
                    if(sliding[0]){float x=v.getTranslationX();v.animate().translationX(Math.abs(x)>reveal/2f?Math.signum(x)*reveal:0).setDuration(150).start();return true;}
                    // A tap on a card slid open covers the button again instead of opening it.
                    if(v.getTranslationX()!=0&&ev.getActionMasked()==android.view.MotionEvent.ACTION_UP){v.animate().translationX(0).setDuration(150).start();return true;}
                }
                default -> {}
            }
            return false;
        });
        var lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=Ui.dp(this,10);holder.setLayoutParams(lp);
        return holder;
    }
    /** A chip: yellow for a factor the horse has, softer for one only a parent has, grey and struck out for one missing. */
    TextView chip(String text,boolean on,boolean own){
        TextView c=new TextView(this);c.setTextSize(13);c.setPadding(Ui.dp(this,10),Ui.dp(this,4),Ui.dp(this,10),Ui.dp(this,4));
        var s=new SpannableStringBuilder(text);
        if(!on)s.setSpan(new StrikethroughSpan(),0,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        c.setText(s);c.setTextColor(on?(own?0xff7a5600:0xff8a6d2a):Ui.MUTED);
        c.setBackground(Ui.rounded(this,on?(own?0xffffe08a:0xfff6e7bd):Ui.SURFACE,14));
        return c;
    }

    /**
     * Lets a horse go: its record only (its images stay in the gallery), or the images too. Either way it is taken
     * afresh if its details are opened again.
     */
    void remove(Entry e){
        String who=(e.name().isEmpty()?"このウマ娘":e.name())+"（評価点 "+e.evaluation()+"）";
        new AlertDialog.Builder(this).setTitle(who+"を消す").setItems(new String[]{"記録だけ消す（ギャラリーの画像は残す）","記録とギャラリーの画像も消す"},(d,n)->{
            if(n==1)for(String u:e.uris())if(!u.isEmpty())try{getContentResolver().delete(Uri.parse(u),null,null);}catch(Exception ignored){}
            HallOfFame.log("台帳から消した："+e.dir().getName()+" "+e.name()+"（"+(n==1?"画像も":"記録だけ")+"）");
            for(String part:HallOfFame.ownParts(e.dir()))CardBook.discard(this,part);   // its own parts, which nothing else uses
            CaptureService.hof.forget(e.dir());
            entries=load(this);refresh();Ui.toast(this,n==1?"記録と画像を消しました":"記録を消しました");
        }).setNegativeButton("キャンセル",null).show();
    }
    /** The horse's images (スキル and 継承; 育成情報 too for horses saved before it was dropped), a tab each, with sending and sharing. */
    void show(Entry e){
        Ui.palette(this);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs=new LinearLayout(this);content.addView(tabs);
        ScrollView scroll=new ScrollView(this);ImageView image=new ImageView(this);image.setAdjustViewBounds(true);scroll.addView(image);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(this,480)));
        // Made again from what was kept when it was saved and the card book as it is now: no taking again.
        Runnable[] shownTab=new Runnable[1];
        content.addView(Ui.button(this,"画像を作り直す",()->{Ui.toast(this,"作り直しています…");
            new Thread(()->{String said=CaptureService.remakeHallOfFame(this,e.dir());
                runOnUiThread(()->{Ui.toast(this,said);if(shownTab[0]!=null)shownTab[0].run();});}).start();}));
        content.addView(Ui.button(this,"カードを確認",()->cards(e)));
        int count=Math.max(HallOfFame.TAKEN,Math.min(3,e.uris().size()));Runnable[] pick=new Runnable[count+1];int[] current={0};
        shownTab[0]=()->pick[current[0]].run();   // the tab on show, read again (after 作り直す)
        for(int i=0;i<count;i++){int n=i;Button b=Ui.button(this,HallOfFame.TABS[i],()->pick[n].run());tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));
            pick[i]=()->{current[0]=n;if(image.getParent()==null){scroll.removeAllViews();scroll.addView(image);}
                Bitmap old=image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable bd?bd.getBitmap():null;image.setImageBitmap(read(e,n));if(old!=null)old.recycle();
                for(int k=0;k<tabs.getChildCount();k++)((Button)tabs.getChildAt(k)).setTextColor(k==n?Ui.ACCENT:Ui.MUTED);scroll.scrollTo(0,0);};}
        pick[0].run();
        AlertDialog d=new AlertDialog.Builder(this).setTitle(e.name()+"　"+e.epithet()).setView(content)
            .setPositiveButton("閉じる",null)
            .setNeutralButton("共有",(x,w)->share(e))
            .create();
        d.show();
    }
    /**
     * Every part the horse's images were made of (its layout.json: each name's letters, each skill's icon, the common
     * cards, the stars, the banner and the headings), as what it is over the image of it that was used - the names
     * first, in the images' order. One slid sideways uncovers a trash button: that part is thrown away, every take of
     * it (CardBook.discard), so nothing of it is clean and it is taken again the next time it is on screen.
     */
    void cards(Entry e){
        Ui.palette(this);
        List<String[]> parts=new ArrayList<>();
        try{JSONObject layout=new JSONObject(new String(Files.readAllBytes(new File(e.dir(),"layout.json").toPath()),StandardCharsets.UTF_8));
            org.json.JSONArray tabs=layout.getJSONArray("tabs");Set<String> seen=new HashSet<>();List<String[]> named=new ArrayList<>(),rest=new ArrayList<>();
            for(int t=0;t<tabs.length();t++){org.json.JSONArray rows=tabs.getJSONObject(t).getJSONArray("rows");
                for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);List<Object> recs=new ArrayList<>();
                    for(String k:new String[]{"banner","heading","icon"})recs.add(row.opt(k));
                    org.json.JSONArray cards=row.getJSONArray("cards");for(int k=0;k<cards.length();k++)recs.add(cards.opt(k));
                    for(Object o:recs){if(!(o instanceof JSONObject r)||r.optJSONObject("shown")==null)continue;
                        org.json.JSONArray ps=r.getJSONObject("shown").getJSONArray("parts");
                        for(int k=0;k<ps.length();k++){JSONObject part=ps.getJSONObject(k);String key=part.getString("key");if(!seen.add(key))continue;
                            org.json.JSONArray b=part.getJSONArray("box");
                            String[] p={key,part.optString("file",""),String.valueOf(b.getInt(2)-b.getInt(0)),String.valueOf(b.getInt(3)-b.getInt(1))};
                            (named(key)?named:rest).add(p);}}}}
            parts.addAll(named);parts.addAll(rest);
        }catch(Exception ex){Ui.toast(this,"この子は並びが残っていないので、確認できません（撮り直してください）");return;}
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);int pad=Ui.dp(this,12);list.setPadding(pad,pad,pad,pad);
        TextView note=new TextView(this);note.setText("汚いものは左右にスライドして🗑を押すと捨てられます（確認が出ます）。捨てたものは、次に画面に出たときに撮り直します（そろったら「画像を作り直す」）");
        note.setTextColor(Ui.MUTED);note.setTextSize(12);note.setPadding(0,0,0,Ui.dp(this,10));list.addView(note);
        for(String[] p:parts){
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Ui.rounded(this,Ui.CARD,14));card.setPadding(pad,pad/2,pad,pad/2);
            // A tap stays on the card - it must never reach the trash buttons under it - and shows the image large.
            TextView label=new TextView(this);label.setText(label(p[0]));label.setTextColor(Ui.INK);label.setTextSize(13);card.addView(label);
            ImageView image=new ImageView(this);Bitmap b=used(p);
            if(b!=null){image.setImageBitmap(b);card.addView(image,new LinearLayout.LayoutParams(-2,-2));}
            else{TextView none=new TextView(this);none.setText("（綺麗なものがまだ無い）");none.setTextColor(Ui.MUTED);none.setTextSize(12);card.addView(none);}
            card.setOnClickListener(v->{if(b!=null)zoom(label(p[0]),b);});
            // As on the list of horses: slid sideways, the trash button; it asks before anything is thrown away.
            list.addView(swipeToDelete(card,()->new AlertDialog.Builder(this).setTitle("「"+label(p[0])+"」を捨てますか？")
                .setMessage("この画像を捨てて、次に画面に出たときに撮り直します（そろったら「画像を作り直す」）")
                .setPositiveButton("捨てる",(x,y)->{CardBook.discard(this,p[0]);CaptureService.compose.forgetPart(p[0]);label.setText(label(p[0])+"　→ 捨てました");image.setAlpha(.25f);})
                .setNegativeButton("やめる",null).show()));
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(list);
        new AlertDialog.Builder(this).setTitle("使ったカード（"+parts.size()+"）").setView(scroll).setPositiveButton("閉じる",null).show();
    }
    /**
     * A part's image as wide as the screen allows, each pixel a square block (not smoothed): what is over it is to be
     * seen, to tell whether it is clean.
     */
    void zoom(String title,Bitmap b){
        int room=getResources().getDisplayMetrics().widthPixels-Ui.dp(this,64);
        int scale=Math.max(1,room/Math.max(1,b.getWidth()));
        Bitmap big=Bitmap.createScaledBitmap(b,b.getWidth()*scale,b.getHeight()*scale,false);
        ImageView view=new ImageView(this);view.setImageBitmap(big);view.setAdjustViewBounds(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(view);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("閉じる",null).show();
    }
    /** A name's own part (its letters, a skill's icon, a horse's rainbow card): listed first. */
    static boolean named(String key){
        return CardBook.free(key)||key.startsWith("si|")||key.startsWith("sr|")||key.startsWith("sn|")||key.startsWith("fn|")||key.startsWith("Fn|");
    }
    /** What a part is, by its key, for カードを確認. */
    static String label(String key){
        String[] k=key.split("\\|",-1);String kind=k.length>1?colour(k[1]):"";
        return switch(k[0]){
            case "st","sn" -> k.length>2?k[2]:key;
            case "Ft","ft","Fn","fn" -> k.length>1?k[1]:key;
            case "si" -> (k.length>1?k[1]:key)+"（アイコン）";
            case "sr" -> (k.length>1?k[1]:key)+"（虹のカード）";
            case "sc","fc","Fc" -> "共通のカード（"+kind+"）";
            case "sb","fb","Fb" -> "共通のカード（"+kind+"・前の作り）";
            case "fs","Fs" -> "★"+(k.length>2?k[2]:"")+"（"+kind+"）";
            case "hb" -> "「因子」の帯";
            case "hd" -> "「継承元」の見出し";
            case "hi" -> "キャラのアイコン";
            default -> key;
        };
    }
    static String colour(String kind){
        return switch(kind){case "white" -> "白";case "pink" -> "ピンク";case "gold" -> "金";case "blue" -> "青";case "green" -> "緑";case "rainbow" -> "虹";default -> kind;};
    }
    /** The image of a part the horse's images were made of: the take recorded, else the one the card book uses now. */
    Bitmap used(String[] p){
        try{File d=CardBook.dir(this,p[0]);
            if(!p[1].isEmpty()){File f=new File(d,p[1]);if(f.exists())return BitmapFactory.decodeFile(f.getPath());}
            File f=CardBook.cleanFile(this,p[0],CardBook.free(p[0])?-1:Integer.parseInt(p[2]),Integer.parseInt(p[3]));
            return f==null?null:BitmapFactory.decodeFile(f.getPath());
        }catch(Exception ex){return null;}
    }
    Bitmap read(Entry e,int i){
        try{byte[] b=bytes(e,i);if(b==null)return null;var o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;return BitmapFactory.decodeByteArray(b,0,b.length,o);}catch(Exception ex){return null;}
    }
    /** An image as saved: from the gallery, or the copy kept with the horse's record. */
    byte[] bytes(Entry e,int i){
        try{if(i<e.uris().size()&&!e.uris().get(i).isEmpty())try(var in=getContentResolver().openInputStream(Uri.parse(e.uris().get(i)))){if(in!=null)return in.readAllBytes();}}catch(Exception ignored){}
        try{File f=HallOfFame.saved(e.dir(),i);if(f!=null)return Files.readAllBytes(f.toPath());}catch(Exception ignored){}
        return null;
    }
    void share(Entry e){
        ArrayList<Uri> uris=new ArrayList<>();for(String u:e.uris())if(!u.isEmpty())uris.add(Uri.parse(u));
        if(uris.isEmpty()){Ui.toast(this,"ギャラリーの画像が見つかりません");return;}
        Intent send=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send,"殿堂入りウマ娘を共有"));
    }
}
