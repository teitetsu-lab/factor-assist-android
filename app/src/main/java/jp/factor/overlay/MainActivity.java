package jp.factor.overlay;

import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import org.json.*;
import java.util.*;

public class MainActivity extends Activity {
    final Map<String,String> parts=new HashMap<>(); TextView status; boolean resumed;
    @Override protected void onResume(){super.onResume();resumed=true;}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putSerializable("qrParts",new HashMap<>(parts));}
    /** 開始 from the floating buttons while this screen is open already: it comes here, not to onCreate. */
    @Override protected void onNewIntent(android.content.Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(intent.getBooleanExtra("capture",false)){intent.removeExtra("capture");new android.os.Handler().post(this::capture);}
    }
    /** Light or dark as set in the app's settings (see Ui.themed). */
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(Ui.themed(base,false));themeMade=Ui.theme(base);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Split QR parts read so far: kept when the screen is made again (rotation, theme).
        if(state!=null){var kept=state.getSerializable("qrParts",HashMap.class);if(kept!=null)for(Object k:kept.keySet())parts.put(String.valueOf(k),String.valueOf(kept.get(k)));}
        render();
        // Only on the start itself: made again (the theme, a rotation, after the process died) or opened from recents,
        // the same intent would start capturing again unasked.
        boolean fromHistory=(getIntent().getFlags()&android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)!=0;
        if(state==null&&!fromHistory&&getIntent().getBooleanExtra("capture",false))new android.os.Handler().post(this::capture);
        getIntent().removeExtra("capture");
        // The skills' effects and the courses (StaminaCalc: the stamina and the 試走), taken again when old.
        if(checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},9);
    }
    /**
     * The top screen: the preset in use and 認識開始 in one card, then the six things done here as tiles, the app's
     * settings (theme, the floating buttons' look) among them. 認識開始 brings up the floating buttons too.
     */
    void render() {
        Ui.palette(this);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.PAGE);scroll.addView(content);setContentView(scroll);
        content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(Ui.dp(this,16),i.top+Ui.dp(this,16),Ui.dp(this,16),i.bottom+Ui.dp(this,16));return insets;});
        // The name and version, and what the recognition is doing.
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);content.addView(header);
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);header.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=new TextView(this);title.setText("因子アシスト");title.setTextSize(26);title.setTextColor(Ui.INK);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));names.addView(title);
        TextView version=new TextView(this);version.setText("Android  "+BuildConfig.VERSION_NAME);version.setTextSize(12);version.setTextColor(Ui.MUTED);names.addView(version);
        boolean running=CaptureService.running,paused=!running&&CaptureService.connected;
        TextView badge=new TextView(this);badge.setTextSize(13);badge.setTextColor(running?FloatingBar.GO:Ui.MUTED);
        badge.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));
        badge.setText("●  "+(running?"認識中":paused?"一時停止中":"停止中"));badge.setGravity(android.view.Gravity.CENTER);
        badge.setBackground(Ui.rounded(this,Ui.SURFACE,18));badge.setPadding(Ui.dp(this,14),0,Ui.dp(this,14),0);
        header.addView(badge,new LinearLayout.LayoutParams(-2,Ui.dp(this,36)));
        // Messages from importing and the like.
        status=new TextView(this);status.setTextColor(Ui.ACCENT);status.setTextSize(13);status.setPadding(Ui.dp(this,4),Ui.dp(this,10),0,0);content.addView(status);
        // The preset in use and 認識開始.
        JSONArray presets=new JSONArray(),stored=Store.all(this);
        for(int i=0;i<stored.length();i++)if(Store.FACTOR.equals(Store.kindOf(stored.optJSONObject(i))))presets.put(stored.optJSONObject(i));
        JSONObject active=Store.selected(this);
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setBackground(Ui.rounded(this,Ui.ACCENT,24));
        hero.setPadding(Ui.dp(this,20),Ui.dp(this,18),Ui.dp(this,20),Ui.dp(this,20));
        var heroLp=new LinearLayout.LayoutParams(-1,-2);heroLp.setMargins(0,Ui.dp(this,12),0,Ui.dp(this,16));content.addView(hero,heroLp);
        TextView using=new TextView(this);using.setText("使用中のプリセット");using.setTextColor(Ui.SOFT_ON_ACCENT);using.setTextSize(13);hero.addView(using);
        TextView preset=new TextView(this);preset.setText((active==null?"プリセットがありません":active.optString("name"))+"  ▾");preset.setTextColor(Ui.ON_ACCENT);preset.setTextSize(22);
        preset.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));preset.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,4));
        preset.setOnClickListener(v->choosePreset(presets));hero.addView(preset);
        TextView counts=new TextView(this);counts.setText(counts());counts.setTextColor(Ui.SOFT_ON_ACCENT);counts.setTextSize(13);hero.addView(counts);
        // 停止 only pauses, as on the floating button: the next 開始 needs no consent again.
        Button start=Ui.pill(this,running?"■  認識を一時停止":"▶  認識開始",Ui.CARD,Ui.ACCENT,()->{if(CaptureService.running){CaptureService.pause(this);new android.os.Handler().postDelayed(this::render,300);}else capture();});
        start.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));
        var startLp=new LinearLayout.LayoutParams(-1,-2);startLp.topMargin=Ui.dp(this,18);hero.addView(start,startLp);
        // The six things done here.
        GridLayout tiles=new GridLayout(this);tiles.setColumnCount(2);content.addView(tiles,new LinearLayout.LayoutParams(-1,-2));
        List<android.view.View> all=new ArrayList<>();
        all.addAll(List.of(
            Ui.tile(this,android.R.drawable.ic_menu_edit,"プリセットを編集","登録する因子と優先度",()->Ui.edit(this,false,this::render)),
            Ui.tile(this,android.R.drawable.ic_input_add,"新規プリセット","空のプリセットを作る",()->Ui.newPreset(this,false,this::render)),
            Ui.tile(this,android.R.drawable.stat_sys_download_done,"プリセットを取り込む","共有コード・QR・ファイルから",this::importChooser),
            Ui.tile(this,android.R.drawable.stat_sys_upload_done,"プリセットを書き出す","共有コード・QR・ファイルへ",this::exportChooser),
            // Other phones and tablets: fit where the page title and the chips are from a screenshot.
            Ui.tile(this,android.R.drawable.ic_menu_crop,"読み取り位置の調整","うまく読めないときに",()->startActivity(new Intent(this,CalibrationActivity.class))),
            Ui.tile(this,android.R.drawable.ic_menu_preferences,"アプリ設定","テーマ・ボタンの見た目",()->startActivity(new Intent(this,SettingsActivity.class)))));
        for(int i=0;i<all.size();i++){
            var lp=new GridLayout.LayoutParams(GridLayout.spec(i/2),GridLayout.spec(i%2,1f));lp.width=0;
            lp.setMargins(i%2==0?0:Ui.dp(this,6),0,i%2==0?Ui.dp(this,6):0,Ui.dp(this,12));tiles.addView(all.get(i),lp);
        }
        Button quit=Ui.textButton(this,"終了する",()->{stopService(new Intent(this,CaptureService.class));stopService(new Intent(this,FloatingService.class));finishAndRemoveTask();});
        content.addView(quit,new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));
    }
    /** The theme this screen was made in: when it was changed in the settings, coming back rebuilds the screen in the new one. */
    int themeMade=-1;
    @Override protected void onRestart(){super.onRestart();if(Ui.theme(this)!=themeMade){recreate();return;}render();}
    /** How many factors the preset in use has at each level, e.g. 必須 1件・重要 16件. */
    String counts(){
        var rules=Store.rules(this);int[] n=new int[Store.TIERS.length];
        for(var rule:rules.values()){
            int best=Integer.MAX_VALUE;for(var r:rule)if(r.priority()>=0&&r.priority()<=Store.PRIORITY_MAX)best=Math.min(best,r.priority());
            if(best!=Integer.MAX_VALUE)n[Store.tierOf(best)]++;
        }
        StringBuilder s=new StringBuilder();
        for(int i=0;i<n.length;i++)if(n[i]>0)s.append(s.length()>0?"・":"").append(i==Store.TIERS.length-1?"本育成":Store.TIERS[i]).append(' ').append(n[i]).append("件");
        return s.length()>0?s.toString():"まだ因子が登録されていません";
    }
    void choosePreset(JSONArray presets){
        if(presets.length()==0){Ui.newPreset(this,false,this::render);return;}
        String[] labels=new String[presets.length()];JSONObject active=Store.selected(this);int current=-1;
        for(int i=0;i<labels.length;i++){labels[i]=presets.optJSONObject(i).optString("name");if(active!=null&&active.optString("id").equals(presets.optJSONObject(i).optString("id")))current=i;}
        new AlertDialog.Builder(this).setTitle("使用するプリセット").setSingleChoiceItems(labels,current,(d,n)->{Store.select(this,presets.optJSONObject(n).optString("id"));d.dismiss();render();}).setNegativeButton("キャンセル",null).show();
    }
    void importChooser(){
        new AlertDialog.Builder(this).setTitle("プリセットを取り込む").setItems(new String[]{"共有コードを貼り付け","QR画像を読み込む","共有コードのファイルを読み込む"},(d,n)->{
            switch(n){
                case 0 -> {EditText code=new EditText(this);code.setMinLines(3);Ui.show(new AlertDialog.Builder(this).setTitle("共有コード").setView(code).setPositiveButton("取り込む",(x,w)->importCode(code.getText().toString())).setNegativeButton("キャンセル",null).create(),false);}
                case 1 -> {Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(pick,2);}
                default -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE),3);
            }}).show();
    }
    /** Writing out the preset in use. The saved file holds just the share code, so it reads back the same way. */
    void exportChooser(){
        JSONObject p=Store.selected(this);if(p==null){Ui.toast(this,"プリセットがありません");return;}
        new AlertDialog.Builder(this).setTitle("「"+p.optString("name")+"」を書き出す").setItems(new String[]{"共有コードをコピー・共有","QRで書き出す","共有コードをファイルに保存"},(d,n)->{
            switch(n){
                case 0 -> Ui.exportPreset(this,p);
                case 1 -> Ui.exportQr(this,false,p);
                default -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,p.optString("name").replaceAll("[\\\\/:*?\"<>|]","_")+".txt"),4);
            }}).show();
    }
    boolean permission(){if(!Settings.canDrawOverlays(this)){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return false;}return true;}
    // Skill support reads the screen without a preset; only factor mode needs one to sort what it reads.
    void capture(){if(!CaptureService.skillMode(this)&&Store.selected(this)==null){Ui.toast(this,"先にプリセットを選択してください");return;}if(!permission())return;if(CaptureService.running)return;
        // A paused share is still connected: resume it without asking for consent again.
        if(CaptureService.connected){startForegroundService(new Intent(this,FloatingService.class));if(CaptureService.startsReading(this))CaptureService.resume(this);if(!Ui.launchGame(this))moveTaskToBack(true);return;}
        // The user picks the game as a single app: only the game is captured, never this app's own drawing.
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),1);}
    void importCode(String raw){try{String code=ShareCode.accept(raw.trim(),parts);if(code==null){status.setText("分割QRを保持しました。残りの画像を読み込んでください");return;}JSONObject value=ShareCode.decode(code);
        boolean exists=false;JSONArray all=Store.all(this);for(int i=0;i<all.length();i++)if(all.optJSONObject(i).optString("id").equals(value.optString("id")))exists=true;
        Runnable apply=()->{Store.save(this,value);render();status.setText("取り込み済み："+value.optString("name"));};
        if(exists)new AlertDialog.Builder(this).setTitle(value.optString("name")).setMessage("端末内の同じプリセットを更新しますか？").setPositiveButton("更新",(d,w)->apply.run()).setNeutralButton("別コピー",(d,w)->{try{value.put("id",UUID.randomUUID().toString());apply.run();}catch(Exception ignored){}}).setNegativeButton("キャンセル",null).show();else apply.run();
    }catch(Exception e){Ui.toast(this,"読み込み失敗："+e.getMessage());}}
    void image(Uri uri){try{var scanner=BarcodeScanning.getClient();scanner.process(InputImage.fromFilePath(this,uri)).addOnSuccessListener(codes->{if(codes.isEmpty())Ui.toast(this,"QRコードが見つかりません");for(var code:codes)if(code.getRawValue()!=null)importCode(code.getRawValue());}).addOnFailureListener(e->Ui.toast(this,"画像を読めませんでした")).addOnCompleteListener(t->scanner.close());}catch(Exception e){Ui.toast(this,"画像を開けませんでした");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==1){startForegroundService(new Intent(this,FloatingService.class));startForegroundService(new Intent(this,CaptureService.class).putExtra("grant",data).putExtra("result",result));
            // Sharing a single app already brought the game forward; opening it again would pause and resume
            // the Unity activity back to back, which can freeze it. Only when this screen is still in front (the
            // whole screen picked in the consent dialog after all) is the game opened here.
            new android.os.Handler().postDelayed(()->{if(resumed&&!Ui.launchGame(this))moveTaskToBack(true);},400);}
        if(request==2){if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)image(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)image(data.getData());}
        if(request==4)try(var out=getContentResolver().openOutputStream(data.getData(),"wt")){JSONObject p=Store.selected(this);out.write(ShareCode.encode(p).getBytes(java.nio.charset.StandardCharsets.US_ASCII));Ui.toast(this,"保存しました："+p.optString("name"));}catch(Exception e){Ui.toast(this,"保存できませんでした");}
        if(request==3)try(var in=getContentResolver().openInputStream(data.getData())){byte[] bytes=in.readNBytes(100001);importCode(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){Ui.toast(this,"コードを開けませんでした");}
    }
}
