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
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);render();
        if(getIntent().getBooleanExtra("capture",false))new android.os.Handler().post(this::capture);
        if(checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},9);
    }
    void render() {
        LinearLayout content=Ui.column(this);ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);
        content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(Ui.dp(this,16),i.top+Ui.dp(this,16),Ui.dp(this,16),i.bottom+Ui.dp(this,16));return insets;});
        TextView title=new TextView(this);title.setText("因子アシスト");title.setTextSize(26);title.setTextColor(Ui.INK);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));content.addView(title);
        TextView version=new TextView(this);version.setText("ANDROID  /  0.2.2");version.setTextSize(12);version.setTextColor(Ui.MUTED);version.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,12));content.addView(version);
        status=new TextView(this);status.setText(CaptureService.message);status.setTextColor(Ui.ACCENT);status.setTextSize(14);content.addView(status);
        Ui.section(content,"認識");
        LinearLayout actions=new LinearLayout(this);content.addView(actions);
        Button start=Ui.primary(this,"認識開始",this::capture);Button stop=Ui.button(this,"終了",()->{stopService(new Intent(this,CaptureService.class));status.setText("停止中");});
        var startLayout=new LinearLayout.LayoutParams(0,-2,2);startLayout.setMarginEnd(Ui.dp(this,8));actions.addView(start,startLayout);actions.addView(stop,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(Ui.iconButton(this,"フローティング操作を表示",android.R.drawable.ic_menu_view,()->{if(permission())startForegroundService(new Intent(this,FloatingService.class));}));
        // Other phones and tablets: fit where the page title and the chips are from a screenshot.
        content.addView(Ui.iconButton(this,"読み取り位置の調整（S25以外の機種向け）",android.R.drawable.ic_menu_crop,()->startActivity(new Intent(this,CalibrationActivity.class))));
        Ui.section(content,"使用プリセット");
        JSONArray presets=Store.all(this);String[] labels=new String[presets.length()];JSONObject active=Store.selected(this);int current=0;
        for(int i=0;i<labels.length;i++){labels[i]=presets.optJSONObject(i).optString("name");if(active!=null&&active.optString("id").equals(presets.optJSONObject(i).optString("id")))current=i;}
        Spinner choice=Ui.spinner(this,labels);choice.setSelection(current);content.addView(choice);
        choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int n,long id){Store.select(MainActivity.this,presets.optJSONObject(n).optString("id"));}});
        content.addView(Ui.iconButton(this,"プリセットを編集",android.R.drawable.ic_menu_edit,()->Ui.edit(this,false,this::render)));
        content.addView(Ui.iconButton(this,"新規プリセット",android.R.drawable.ic_input_add,()->Ui.newPreset(this,false,this::render)));
        Ui.section(content,"取り込み");
        content.addView(Ui.button(this,"共有コードを貼り付け",()->{EditText code=new EditText(this);code.setMinLines(3);new AlertDialog.Builder(this).setTitle("共有コード").setView(code).setPositiveButton("取り込む",(d,w)->importCode(code.getText().toString())).setNegativeButton("キャンセル",null).show();}));
        content.addView(Ui.button(this,"QR画像を読み込む",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(pick,2);}));
        content.addView(Ui.button(this,"共有コードのファイルを読み込む",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE),3)));
        Ui.section(content,"書き出し（選択中のプリセット）");
        content.addView(Ui.button(this,"共有コードをコピー・共有",()->{JSONObject p=Store.selected(this);if(p==null)Ui.toast(this,"プリセットがありません");else Ui.exportPreset(this,p);}));
        content.addView(Ui.button(this,"QRで書き出す",()->{JSONObject p=Store.selected(this);if(p==null)Ui.toast(this,"プリセットがありません");else Ui.exportQr(this,false,p);}));
        // The saved file holds just the share code, so 共有コードのファイルを読み込む and the PC version read it back.
        content.addView(Ui.button(this,"共有コードをファイルに保存",()->{JSONObject p=Store.selected(this);if(p==null){Ui.toast(this,"プリセットがありません");return;}
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,p.optString("name").replaceAll("[\\\\/:*?\"<>|]","_")+".txt"),4);}));
    }
    boolean permission(){if(!Settings.canDrawOverlays(this)){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return false;}return true;}
    void capture(){if(Store.selected(this)==null){Ui.toast(this,"先にプリセットを選択してください");return;}if(!permission())return;if(CaptureService.running)return;
        // A paused share is still connected: resume it without asking for consent again.
        if(CaptureService.connected){startForegroundService(new Intent(this,FloatingService.class));CaptureService.resume(this);if(!Ui.launchGame(this))moveTaskToBack(true);return;}
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),1);}
    void importCode(String raw){try{String code=ShareCode.accept(raw.trim(),parts);if(code==null){status.setText("分割QRを保持しました。残りの画像を読み込んでください");return;}JSONObject value=ShareCode.decode(code);
        boolean exists=false;JSONArray all=Store.all(this);for(int i=0;i<all.length();i++)if(all.optJSONObject(i).optString("id").equals(value.optString("id")))exists=true;
        Runnable apply=()->{Store.save(this,value);parts.clear();render();status.setText("取り込み済み："+value.optString("name"));};
        if(exists)new AlertDialog.Builder(this).setTitle(value.optString("name")).setMessage("端末内の同じプリセットを更新しますか？").setPositiveButton("更新",(d,w)->apply.run()).setNeutralButton("別コピー",(d,w)->{try{value.put("id",UUID.randomUUID().toString());apply.run();}catch(Exception ignored){}}).setNegativeButton("キャンセル",null).show();else apply.run();
    }catch(Exception e){Ui.toast(this,"読み込み失敗："+e.getMessage());}}
    void image(Uri uri){try{var scanner=BarcodeScanning.getClient();scanner.process(InputImage.fromFilePath(this,uri)).addOnSuccessListener(codes->{if(codes.isEmpty())Ui.toast(this,"QRコードが見つかりません");for(var code:codes)if(code.getRawValue()!=null)importCode(code.getRawValue());}).addOnFailureListener(e->Ui.toast(this,"画像を読めませんでした")).addOnCompleteListener(t->scanner.close());}catch(Exception e){Ui.toast(this,"画像を開けませんでした");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==1){startForegroundService(new Intent(this,FloatingService.class));startForegroundService(new Intent(this,CaptureService.class).putExtra("grant",data).putExtra("result",result));
            // Sharing a single app already brought the game forward; opening it again would pause and resume
            // the Unity activity back to back, which can freeze it. Only when this screen is still in front
            // (whole-screen sharing) is the game opened here.
            new android.os.Handler().postDelayed(()->{if(resumed&&!Ui.launchGame(this))moveTaskToBack(true);},400);}
        if(request==2){if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)image(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)image(data.getData());}
        if(request==4)try(var out=getContentResolver().openOutputStream(data.getData(),"wt")){JSONObject p=Store.selected(this);out.write(ShareCode.encode(p).getBytes(java.nio.charset.StandardCharsets.US_ASCII));Ui.toast(this,"保存しました："+p.optString("name"));}catch(Exception e){Ui.toast(this,"保存できませんでした");}
        if(request==3)try(var in=getContentResolver().openInputStream(data.getData())){byte[] bytes=in.readNBytes(100001);importCode(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){Ui.toast(this,"コードを開けませんでした");}
    }
}
