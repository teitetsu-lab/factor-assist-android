package jp.factor.overlay;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.*;

final class Ui {
    /**
     * The colours of the app's own views, for the theme in use: set by palette(context) before a screen or a floating
     * window is built. INK text, MUTED secondary text, ACCENT the app's teal, SURFACE a plain button, PAGE the
     * background of a screen, CARD a card or dialog, LINE a hairline, ICON_BG the circle behind an icon, ON_ACCENT text
     * on the accent colour.
     */
    static int INK=0xff202b2c, MUTED=0xff687574, ACCENT=0xff087f78, SURFACE=0xfff1f5f4,
        PAGE=0xfff2f4f3, CARD=Color.WHITE, LINE=0xffe4e9e8, ICON_BG=0xffe2f2f0, ON_ACCENT=Color.WHITE, SOFT_ON_ACCENT=0xffc8ebe7;
    static boolean dark(Context c){
        return (c.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    /** Picks the colours above for the theme c is in. */
    static void palette(Context c){
        boolean d=dark(c);
        INK=d?0xffeceeee:0xff202b2c;MUTED=d?0xff96a09f:0xff687574;ACCENT=d?0xff3abeb0:0xff087f78;SURFACE=d?0xff2a2e32:0xfff1f5f4;
        PAGE=d?0xff121416:0xfff2f4f3;CARD=d?0xff202326:Color.WHITE;LINE=d?0xff303438:0xffe4e9e8;ICON_BG=d?0xff1e3c3a:0xffe2f2f0;
        ON_ACCENT=d?0xff0a1e1c:Color.WHITE;SOFT_ON_ACCENT=d?0xff0a3c37:0xffc8ebe7;
    }
    /** 0 light, 1 dark, 2 as the phone is (the default). */
    static int theme(Context c){return Store.prefs(c).getInt("theme",2);}
    /**
     * c with its day/night set by the app's setting: the phone's own when that is 端末に従う. overlay is for what is
     * drawn over the game, which follows the same setting unless 「メニューや詳細もテーマに合わせる」 is off - then it
     * stays light.
     */
    static Context themed(Context c,boolean overlay){
        int mode=theme(c);
        if(overlay&&!Store.prefs(c).getBoolean("overlayTheme",true))mode=0;
        if(mode==2)return c;
        // Only the night bits overridden: a copy of the whole configuration would fix the screen's bounds and
        // orientation as they are now, and windows placed with this context would not follow a rotation.
        var config=new android.content.res.Configuration();
        config.uiMode=mode==1?android.content.res.Configuration.UI_MODE_NIGHT_YES:android.content.res.Configuration.UI_MODE_NIGHT_NO;
        return c.createConfigurationContext(config);
    }
    static android.graphics.drawable.GradientDrawable rounded(Context c,int color,int radius){
        var shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(c,radius));return shape;
    }
    static android.graphics.drawable.Drawable ripple(Context c,android.graphics.drawable.Drawable content,int radius){
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22087f78),content,rounded(c,Color.WHITE,radius));
    }
    /** A card added to parent; what goes in it is stacked inside. */
    static LinearLayout card(LinearLayout parent){
        Context c=parent.getContext();LinearLayout card=new LinearLayout(c);card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(c,CARD,18));card.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        var lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(c,16));parent.addView(card,lp);return card;
    }
    /** The small grey heading above a card. */
    static void cardLabel(LinearLayout parent,String text){
        Context c=parent.getContext();TextView label=new TextView(c);label.setText(text);label.setTextColor(MUTED);label.setTextSize(13);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        label.setPadding(dp(c,6),dp(c,4),0,dp(c,8));parent.addView(label);
    }
    /** The circle an icon sits in. */
    static ImageView iconCircle(Context c,int icon,int size){
        ImageView image=new ImageView(c);var drawable=c.getDrawable(icon).mutate();drawable.setTint(ACCENT);image.setImageDrawable(drawable);
        int pad=size*9/40;image.setPadding(dp(c,pad),dp(c,pad),dp(c,pad),dp(c,pad));image.setBackground(rounded(c,ICON_BG,size/2));return image;
    }
    /** A square-ish tile: an icon, its name, and what it does. */
    static View tile(Context c,int icon,String title,String sub,Runnable action){
        LinearLayout tile=new LinearLayout(c);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(c,16),dp(c,14),dp(c,12),dp(c,14));
        tile.setBackground(ripple(c,rounded(c,CARD,18),18));tile.setOnClickListener(v->action.run());
        tile.addView(iconCircle(c,icon,38),new LinearLayout.LayoutParams(dp(c,38),dp(c,38)));
        TextView name=new TextView(c);name.setText(title);name.setTextColor(INK);name.setTextSize(15);name.setPadding(0,dp(c,12),0,0);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));tile.addView(name);
        TextView under=new TextView(c);under.setText(sub);under.setTextColor(MUTED);under.setTextSize(11);under.setPadding(0,dp(c,2),0,0);tile.addView(under);
        return tile;
    }
    /** The one action of a screen: a wide, fully rounded button. */
    static Button pill(Context c,String text,int background,int ink,Runnable action){
        Button b=button(c,text,action);b.setTextSize(17);b.setTextColor(ink);b.setBackground(ripple(c,rounded(c,background,28),28));
        b.setMinHeight(dp(c,56));b.setMinimumHeight(dp(c,56));return b;
    }
    /** A quiet action: the accent colour on no background. */
    static Button textButton(Context c,String text,Runnable action){
        Button b=button(c,text,action);b.setTextColor(ACCENT);b.setBackground(ripple(c,null,18));return b;
    }
    static int dp(Context c,int n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8)); v.setBackgroundColor(CARD);return v; }
    static android.graphics.drawable.Drawable background(Context c,int color){
        var shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(c,8));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22087f78),shape,null);
    }
    static Button button(Context c,String text,Runnable action) {
        Button b=new Button(c);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setLetterSpacing(0);b.setTextColor(INK);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));
        b.setBackground(background(c,SURFACE));b.setStateListAnimator(null);
        var lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(c,4),0,dp(c,4));b.setLayoutParams(lp);b.setOnClickListener(v->action.run());return b;
    }
    static Button iconButton(Context c,String text,int icon,Runnable action){
        Button b=button(c,text,action);var drawable=c.getDrawable(icon).mutate();drawable.setTint(ACCENT);drawable.setBounds(0,0,dp(c,20),dp(c,20));b.setCompoundDrawablesRelative(drawable,null,null,null);b.setCompoundDrawablePadding(dp(c,12));b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);return b;
    }
    static Button primary(Context c,String text,Runnable action){Button b=button(c,text,action);b.setTextColor(ON_ACCENT);b.setBackground(background(c,ACCENT));return b;}
    static void section(LinearLayout parent,String title){
        Context c=parent.getContext();TextView label=new TextView(c);label.setText(title);label.setTextColor(INK);label.setTextSize(17);label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));label.setPadding(0,dp(c,24),0,dp(c,10));parent.addView(label);
    }
    static Spinner spinner(Context c,String[] values) {
        Spinner s=new Spinner(c,Spinner.MODE_DROPDOWN);
        // Rows made here, in the theme's colours taken as the spinner is built. The platform's own row layout drew
        // nothing at all in the dark windows over the game (the palette is shared, and its item style is not ours).
        int ink=INK,card=CARD;
        ArrayAdapter<String> a=new ArrayAdapter<>(c,0,values){
            private TextView row(int i,View old,boolean drop){
                TextView t=old instanceof TextView v?v:new TextView(c);
                t.setText(getItem(i));t.setTextColor(ink);t.setTextSize(15);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine();
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setMinHeight(dp(c,48));t.setPadding(dp(c,12),0,dp(c,12),0);
                if(drop)t.setBackgroundColor(card);
                return t;
            }
            @Override public View getView(int i,View v,android.view.ViewGroup parent){return row(i,v,false);}
            @Override public View getDropDownView(int i,View v,android.view.ViewGroup parent){return row(i,v,true);}
        };
        s.setAdapter(a);s.setPopupBackgroundDrawable(rounded(c,card,12));
        s.setMinimumHeight(dp(c,52));s.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));return s;
    }
    static void toast(Context c,String text) { Toast.makeText(c,text,Toast.LENGTH_LONG).show(); }
    static final String GAME="jp.co.cygames.umamusume";
    /** Brings the game to the front, launching it if needed. Allowed from the overlay because this app holds SYSTEM_ALERT_WINDOW. */
    static boolean launchGame(Context c) {
        Intent launch=c.getPackageManager().getLaunchIntentForPackage(GAME);
        if(launch==null){toast(c,"ウマ娘が見つかりません");return false;}
        c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;
    }
    static void show(AlertDialog d,boolean floating) { if(floating)d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); aboveKeyboard(d); d.show(); }
    /**
     * Keeps what is being typed in visible over the keyboard: while the keyboard covers the dialog, the dialog moves
     * up, gets shorter if moving up is not enough, and scrolls the field into view; it goes back when the keyboard
     * goes. Needed over the game above all, where the system does not move a window for the keyboard.
     */
    static void aboveKeyboard(android.app.Dialog d){
        var w=d.getWindow();if(w==null)return;
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View decor=w.getDecorView();int[][] saved={null};
        decor.setOnApplyWindowInsetsListener((v,insets)->{
            var p=w.getAttributes();
            if(!insets.isVisible(android.view.WindowInsets.Type.ime())){
                if(saved[0]!=null){p.y=saved[0][0];p.height=saved[0][1];saved[0]=null;w.setAttributes(p);}
                return v.onApplyWindowInsets(insets);
            }
            int covered=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            if(covered>0){
                if(saved[0]==null)saved[0]=new int[]{p.y,p.height};
                int[] at=new int[2];decor.getLocationOnScreen(at);
                int move=Math.min(covered+dp(d.getContext(),8),Math.max(0,at[1]-dp(d.getContext(),32)));
                int shrink=Math.max(0,covered+dp(d.getContext(),8)-move);
                boolean top=(p.gravity&Gravity.VERTICAL_GRAVITY_MASK)==Gravity.TOP;
                // A centred window grows and shrinks around its middle, so shortening it moves its top down by half.
                p.y-=move+(top?0:shrink/2);
                if(shrink>0)p.height=Math.max(dp(d.getContext(),160),decor.getHeight()-shrink);
                w.setAttributes(p);
            }
            View focused=decor.findFocus();
            if(focused!=null)decor.postDelayed(()->focused.requestRectangleOnScreen(new android.graphics.Rect(0,0,focused.getWidth(),focused.getHeight()),false),150);
            return v.onApplyWindowInsets(insets);
        });
    }
    /** Asks for a name, creates the preset and selects it, then runs created (from the app or the floating menu). */
    static void newPreset(Context c,boolean floating,Runnable created) {
        LinearLayout content=column(c);
        EditText name=new EditText(c);name.setSingleLine();name.setHint("プリセット名");content.addView(name);
        Spinner kind=spinner(c,new String[]{"簡易プリセット（必須・重要・必要・本育成）","詳細プリセット（優先度を8段階で設定）"});content.addView(kind);
        AlertDialog d=new AlertDialog.Builder(c).setTitle("新規プリセット").setView(content).setPositiveButton("作成",null).setNegativeButton("キャンセル",null).create();
        show(d,floating);
        d.getButton(-1).setOnClickListener(v->{try{
            if(name.getText().toString().isBlank()){name.setError("名前を入力してください");return;}
            Store.save(c,Store.fresh(name.getText().toString().trim()).put("detailed",kind.getSelectedItemPosition()==1));d.dismiss();created.run();
        }catch(Exception e){toast(c,"作成できませんでした");}});
    }
    static void edit(Context c,boolean floating,Runnable changed) {
        JSONObject selected=Store.selected(c); if(selected==null){toast(c,"プリセットを作成・取り込みしてください");return;}
        LinearLayout content=column(c); ScrollView scroll=new ScrollView(c);scroll.addView(content);
        EditText name=new EditText(c);name.setSingleLine();name.setText(selected.optString("name"));content.addView(name);
        Button rename=button(c,"名前を保存",()->{try{ if(name.getText().toString().isBlank())return;selected.put("name",name.getText().toString());Store.save(c,selected);changed.run();}catch(Exception e){toast(c,e.getMessage());}});content.addView(rename);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle("プリセット編集").setView(scroll).setNegativeButton("閉じる",(d,w)->changed.run()).create();
        Runnable reopen=()->{dialog.dismiss();changed.run();edit(c,floating,changed);};
        // Which kind of preset this is: the plain four tiers, or the eight detailed levels. A preset shared
        // with the PC version stays readable either way, because every entry keeps its tier name.
        Switch detailed=new Switch(c);detailed.setText("詳細プリセット（優先度を8段階で設定）");detailed.setChecked(Store.detailed(selected));content.addView(detailed);
        detailed.setOnCheckedChangeListener((v,on)->{try{selected.put("detailed",on);Store.save(c,selected);reopen.run();}catch(Exception e){toast(c,"保存できませんでした");}});
        content.addView(primary(c,"一覧から因子を追加",()->pick(c,floating,selected,reopen)));
        content.addView(button(c,"名前を入力して追加（OR候補など）",()->entry(c,floating,selected,-1,reopen)));
        content.addView(button(c,"このプリセットを書き出す（コピー・共有）",()->exportPreset(c,selected)));
        content.addView(button(c,"このプリセットをQRで書き出す",()->exportQr(c,floating,selected)));
        content.addView(button(c,"このプリセットを削除",()->{
            AlertDialog confirm=new AlertDialog.Builder(c).setTitle("プリセットを削除")
                .setMessage("「"+selected.optString("name")+"」を削除しますか？\n元に戻せません。")
                .setPositiveButton("削除",(d,w)->{Store.delete(c,selected.optString("id"));dialog.dismiss();changed.run();toast(c,"「"+selected.optString("name")+"」を削除しました");})
                .setNegativeButton("キャンセル",null).create();
            show(confirm,floating);
        }));
        JSONArray entries=selected.optJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i); final int index=i;
            String label=e.optString("factor"); JSONArray group=e.optJSONArray("any_of");
            if(group!=null){java.util.List<String> labels=new java.util.ArrayList<>();for(int n=0;n<group.length();n++)labels.add(group.optString(n));label=String.join(" / ",labels);}
            // Names the model can never output would stay 未設定 forever; flag them.
            boolean unknown=false;var known=knownFactors(c);
            if(group!=null){for(int n=0;n<group.length();n++)unknown|=!known.contains(Store.normalize(group.optString(n)));}else unknown=!known.contains(Store.normalize(e.optString("factor")));
            if(unknown&&!known.isEmpty())label="⚠ "+label+"（認識できない名前）";
            content.addView(button(c,label+"\n"+entryText(selected,e)+"  ★"+e.optInt("min_star",1),()->entry(c,floating,selected,index,()->{dialog.dismiss();changed.run();edit(c,floating,changed);})));
        }
        show(dialog,floating);dialog.getWindow().setLayout(dp(c,340),Math.min(dp(c,560),c.getResources().getDisplayMetrics().heightPixels-dp(c,100)));
    }
    /** Copies the preset's share code (the PC UFP1 format) to the clipboard and opens the share sheet for it. */
    static void exportPreset(Context c,JSONObject preset) {
        try {
            String code=ShareCode.encode(preset);
            c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("因子アシスト 共有コード",code));
            Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,code).putExtra(Intent.EXTRA_SUBJECT,preset.optString("name"));
            c.startActivity(Intent.createChooser(send,"「"+preset.optString("name")+"」の共有コード").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast(c,"共有コードをコピーしました");
        } catch(Exception e){toast(c,"書き出せませんでした");}
    }
    /** Shows the preset as QR codes (split like the PC version when long), with paging and saving every page to the gallery. */
    static void exportQr(Context c,boolean floating,JSONObject preset) {
        java.util.List<String> parts;
        try{parts=QrCodes.parts(preset);}catch(Exception e){toast(c,"QRを作れませんでした");return;}
        String name=preset.optString("name");int shown=dp(c,300);int[] page={0};
        LinearLayout content=column(c);
        TextView label=new TextView(c);label.setTextColor(MUTED);label.setGravity(Gravity.CENTER);content.addView(label);
        ImageView image=new ImageView(c);image.setAdjustViewBounds(true);content.addView(image,new LinearLayout.LayoutParams(-1,shown));
        LinearLayout pager=new LinearLayout(c);
        Button prev=button(c,"前へ",()->{});Button next=button(c,"次へ",()->{});
        pager.addView(prev,new LinearLayout.LayoutParams(0,-2,1));pager.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        if(parts.size()>1)content.addView(pager);
        TextView hint=new TextView(c);hint.setTextColor(MUTED);hint.setText(parts.size()>1?"分割QRです。取り込むときは全部の画像を読み込んでください。":"アプリの「QR画像を読み込む」で取り込めます。");content.addView(hint);
        Runnable render=()->{
            try{image.setImageBitmap(QrCodes.bitmap(parts.get(page[0]),shown));}catch(Exception e){toast(c,"QRを作れませんでした");}
            label.setText("「"+name+"」 "+(page[0]+1)+" / "+parts.size());prev.setEnabled(page[0]>0);next.setEnabled(page[0]<parts.size()-1);
        };
        prev.setOnClickListener(v->{if(page[0]>0){page[0]--;render.run();}});next.setOnClickListener(v->{if(page[0]<parts.size()-1){page[0]++;render.run();}});
        render.run();
        AlertDialog d=new AlertDialog.Builder(c).setTitle("QRで書き出す").setView(content).setPositiveButton("画像を保存",null).setNegativeButton("閉じる",null).create();
        show(d,floating);d.getWindow().setLayout(dp(c,340),-2);
        d.getButton(-1).setOnClickListener(v->{try{saveQr(c,name,parts);toast(c,parts.size()+"枚をギャラリーの「Pictures/因子アシスト」に保存しました");}catch(Exception e){toast(c,"保存できませんでした");}});
    }
    /** An image as the app keeps and saves it: WebP, lossless (75: as small as is quick to make; 100 takes long on a tall image). */
    static byte[] webp(android.graphics.Bitmap b){java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS,75,o);return o.toByteArray();}
    /** One image (WebP) into Pictures/因子アシスト through MediaStore (no storage permission needed for the app's own files). */
    static android.net.Uri saveImage(Context c,String fileName,byte[] image)throws Exception{return saveImage(c,"",fileName,image);}
    /** The same into a folder under Pictures/因子アシスト (sub: "殿堂入り/…"; empty: the folder itself). */
    static android.net.Uri saveImage(Context c,String sub,String fileName,byte[] png)throws Exception{
        var resolver=c.getContentResolver();ContentValues values=new ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,fileName);values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/webp");
        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,android.os.Environment.DIRECTORY_PICTURES+"/因子アシスト"+(sub.isEmpty()?"":"/"+sub));values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
        android.net.Uri uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new java.io.IOException("保存先を作れません");
        try(var out=resolver.openOutputStream(uri)){out.write(png);}catch(Exception e){resolver.delete(uri,null,null);throw e;}
        values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);resolver.update(uri,values,null,null);
        return uri;
    }
    /** QR codes (WebP, lossless) through MediaStore into Pictures/因子アシスト; the app's own inserts need no storage permission. */
    static void saveQr(Context c,String name,java.util.List<String> parts)throws Exception{
        String base=name.replaceAll("[\\\\/:*?\"<>|]","_");var resolver=c.getContentResolver();
        for(int i=0;i<parts.size();i++){
            ContentValues values=new ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,base+(parts.size()>1?"_QR"+(i+1)+"of"+parts.size():"_QR")+".webp");
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/webp");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,android.os.Environment.DIRECTORY_PICTURES+"/因子アシスト");
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
            android.net.Uri uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new java.io.IOException("保存先を作れません");
            // Saved at 8px per module like the PC qr_png, big enough to scan from a screenshot of the saved image.
            try(var out=resolver.openOutputStream(uri)){QrCodes.bitmap(parts.get(i),QrCodes.modules(parts.get(i)).length*8).compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS,75,out);}
            catch(Exception e){resolver.delete(uri,null,null);throw e;}
            values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);resolver.update(uri,values,null,null);
        }
    }
    // The factor list for the picker, built once from the bundled umacapture module files (see FactorCatalog).
    private static FactorCatalog catalog;private static int catalogFor=-1;
    /** Made again once new modules are installed (Modules.installed); a list that could not be read is not kept. */
    static synchronized FactorCatalog catalog(Context c) {
        if(catalog==null||catalogFor!=Modules.installed){
            int now=Modules.installed;
            try{catalog=FactorCatalog.parse(assetText(c,"labels.json"),optionalAsset(c,"factor_info.json"),optionalAsset(c,"factor_tag.json"));catalogFor=now;}
            catch(Exception e){try{return FactorCatalog.parse("{\"factor.name\":[]}",null,null);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
        }
        return catalog;
    }
    static java.util.Set<String> knownFactors(Context c){return catalog(c).known;}
    static String assetText(Context c,String name)throws java.io.IOException{return Modules.text(c,name);}
    static String optionalAsset(Context c,String name){try{return assetText(c,name);}catch(java.io.IOException e){return null;}}

    /** Add factors by picking them from the model's list, narrowed by category and a search box; several at once share one tier and star. */
    static void pick(Context c,boolean floating,JSONObject preset,Runnable changed) {
        FactorCatalog all=catalog(c);
        if(all.factors.isEmpty()){toast(c,"因子名の一覧を読み込めませんでした");return;}
        java.util.Set<String> registered=new java.util.HashSet<>();JSONArray entries=preset.optJSONArray("entries");
        for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);JSONArray g=e.optJSONArray("any_of");if(g==null)g=new JSONArray().put(e.optString("factor"));for(int n=0;n<g.length();n++)registered.add(Store.normalize(g.optString(n)));}
        LinearLayout content=column(c);
        String[] categories=new String[all.categories.size()+1];categories[0]="すべての種別";for(int i=0;i<all.categories.size();i++)categories[i+1]=all.categories.get(i);
        Spinner category=spinner(c,categories);content.addView(category);
        EditText search=new EditText(c);search.setSingleLine();search.setHint("名前・読みで絞り込み（ひらがな・ローマ字可）");content.addView(search);
        TextView count=new TextView(c);count.setTextColor(MUTED);content.addView(count);
        java.util.List<String> shown=new java.util.ArrayList<>();java.util.Set<String> chosen=new java.util.LinkedHashSet<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<>(c,android.R.layout.simple_list_item_multiple_choice,new java.util.ArrayList<>());
        ListView list=new ListView(c);list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);list.setAdapter(adapter);
        content.addView(list,new LinearLayout.LayoutParams(-1,(int)(c.getResources().getDisplayMetrics().heightPixels*.4)));
        Runnable counter=()->count.setText(shown.size()+" 件表示 ／ "+chosen.size()+" 件選択中"+(chosen.isEmpty()?"":"："+String.join("、",chosen)));
        Runnable filter=()->{
            String only=category.getSelectedItemPosition()==0?null:category.getSelectedItem().toString();
            shown.clear();adapter.setNotifyOnChange(false);adapter.clear();
            for(var f:all.filter(only,search.getText().toString())){shown.add(f.name());adapter.add(registered.contains(Store.normalize(f.name()))?f.name()+"（登録済み）":f.name());}
            adapter.notifyDataSetChanged();for(int i=0;i<shown.size();i++)list.setItemChecked(i,chosen.contains(shown.get(i)));counter.run();
        };
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int n){}public void onTextChanged(CharSequence s,int a,int b,int n){}
            public void afterTextChanged(android.text.Editable s){filter.run();}});
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){filter.run();}});
        list.setOnItemClickListener((p,v,pos,id)->{String n=shown.get(pos);if(!chosen.remove(n))chosen.add(n);list.setItemChecked(pos,chosen.contains(n));counter.run();});
        LinearLayout options=new LinearLayout(c);content.addView(options);
        Spinner tier=priority(c,preset,0);options.addView(tier,new LinearLayout.LayoutParams(0,-2,1));
        Spinner star=spinner(c,new String[]{"最低 ★1","最低 ★2","最低 ★3"});options.addView(star,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox group=new CheckBox(c);group.setText("選んだ因子を1つのOR候補にまとめる");content.addView(group);
        filter.run();
        AlertDialog d=new AlertDialog.Builder(c).setTitle("因子を追加").setView(content).setPositiveButton("追加",null).setNegativeButton("キャンセル",null).create();
        show(d,floating);d.getWindow().setLayout(dp(c,340),-2);
        d.getButton(-1).setOnClickListener(v->{try{
            if(chosen.isEmpty()){toast(c,"因子を選んでください");return;}
            int minimum=star.getSelectedItemPosition()+1;
            if(group.isChecked()&&chosen.size()>1){JSONArray names=new JSONArray();for(String n:chosen)names.put(n);entries.put(ranked(preset,new JSONObject().put("min_star",minimum).put("any_of",names),tier));}
            else for(String n:chosen)entries.put(ranked(preset,new JSONObject().put("min_star",minimum).put("factor",n),tier));
            Store.save(c,preset);d.dismiss();changed.run();
        }catch(Exception e){toast(c,"追加できませんでした");}});
    }
    static void entry(Context c,boolean floating,JSONObject preset,int index,Runnable changed) {
        JSONArray entries=preset.optJSONArray("entries"); JSONObject old=index<0?new JSONObject():entries.optJSONObject(index);
        LinearLayout content=column(c); EditText names=new EditText(c); names.setHint("因子名（OR候補は改行）");
        JSONArray alternatives=old.optJSONArray("any_of"); String value=old.optString("factor");
        if(alternatives!=null){StringBuilder b=new StringBuilder();for(int i=0;i<alternatives.length();i++){if(i>0)b.append('\n');b.append(alternatives.optString(i));}value=b.toString();}
        names.setText(value);content.addView(names);Spinner tier=priority(c,preset,Store.entryPriority(old));content.addView(tier);
        Spinner star=spinner(c,new String[]{"最低 ★1","最低 ★2","最低 ★3"});star.setSelection(old.optInt("min_star",1)-1);content.addView(star);
        AlertDialog d=new AlertDialog.Builder(c).setTitle(index<0?"因子追加":"因子編集").setView(content).setPositiveButton("保存",null).setNegativeButton("キャンセル",null)
            .setNeutralButton(index<0?null:"削除",(x,w)->{entries.remove(index);Store.save(c,preset);changed.run();}).create();
        show(d,floating);d.getButton(-1).setOnClickListener(v->{try{
            JSONArray list=new JSONArray();for(String s:names.getText().toString().split("\\R"))if(!s.isBlank())list.put(s.trim());
            if(list.length()==0){names.setError("因子名を入力してください");return;}
            JSONObject e=ranked(preset,new JSONObject().put("min_star",star.getSelectedItemPosition()+1),tier);
            if(list.length()==1)e.put("factor",list.getString(0));else e.put("any_of",list);
            if(index<0)entries.put(e);else entries.put(index,e);Store.save(c,preset);d.dismiss();changed.run();
        }catch(Exception e){toast(c,"保存できませんでした");}});
    }
    /** The priority chooser: the four tiers, or the eight detailed levels when the detailed mode is on. */
    static Spinner priority(Context c,JSONObject preset,int current){
        boolean detailed=Store.detailed(preset);int value=current<0||current>Store.PRIORITY_MAX?0:current;
        String[] levels=detailed?Store.LEVELS:Store.TIERS;
        String[] choices=java.util.Arrays.copyOf(levels,levels.length+1);choices[levels.length]=Store.EXCLUDED;
        Spinner s=spinner(c,choices);
        s.setSelection(current==Store.BANNED?levels.length:detailed?value:Store.tierOf(value));
        return s;
    }
    /** Writes both the number and the tier it falls in, so the PC version still reads the preset. */
    static JSONObject ranked(JSONObject preset,JSONObject entry,Spinner chooser) throws JSONException {
        int levels=Store.detailed(preset)?Store.LEVELS.length:Store.TIERS.length;
        int chosen=chooser.getSelectedItemPosition();
        // The last choice is the ban. It still gets the lowest tier, so the PC version reads the preset as before.
        if(chosen>=levels)return entry.put("tier",Store.TIERS[Store.TIERS.length-1]).put("priority",Store.PRIORITY_MAX).put("ban",true);
        int value=Store.detailed(preset)?chosen:chosen*Store.PER_TIER;
        return entry.put("tier",Store.TIERS[Store.tierOf(value)]).put("priority",value);
    }
    /** How an entry reads in the preset list: its level, its tier, or that it is excluded. */
    static String entryText(JSONObject preset,JSONObject entry){
        int priority=Store.entryPriority(entry);
        if(priority==Store.BANNED)return Store.EXCLUDED;
        return Store.detailed(preset)?Store.LEVELS[priority]:entry.optString("tier");
    }
    static Notification notification(Context c,String text,Class<?> service,int id) {
        NotificationManager nm=c.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("factor","因子アシスト",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(c,id,new Intent(c,service).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c,"factor").setContentTitle("因子アシスト").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).addAction(new Notification.Action.Builder(null,"終了",stop).build()).build();
    }
}
