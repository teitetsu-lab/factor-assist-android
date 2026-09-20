package jp.factor.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

/**
 * 読み取り位置の調整: the user picks a screenshot of the factor selection screen, the title box and chip frame are
 * placed automatically (OCR finds the title line on the still image), the user drags them to fit, and saves.
 * Every change is checked right away: the title box is OCR-read and the factor rows are counted.
 */
public class CalibrationActivity extends Activity {
    static final int PICK=1;
    CalibrationView view;TextView result;TextRecognizer ocr;final Handler handler=new Handler();
    int rows;int firstRowY;
    final Runnable check=this::check;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ocr=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        LinearLayout content=Ui.column(this);setContentView(content);
        content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(Ui.dp(this,12),i.top+Ui.dp(this,8),Ui.dp(this,12),i.bottom+Ui.dp(this,8));return insets;});
        TextView help=new TextView(this);help.setTextColor(Ui.INK);
        help.setText("ウマ娘の因子選択画面のスクショを選ぶと、青い枠（ページタイトルを読む範囲）と赤い枠（因子の色枠の大きさ）を自動で置きます。"
            +"辺をドラッグで調整、枠の内側をドラッグで移動できます。青はタイトルの文字全体が入り、左右の矢印と下の点が入らないように。赤は因子のチップ1つにぴったり合わせてください。");
        content.addView(help);
        LinearLayout row=new LinearLayout(this);content.addView(row);
        row.addView(Ui.primary(this,"スクショを選ぶ",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),PICK)),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(Ui.button(this,"自動で置き直す",this::autoPlace),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout row2=new LinearLayout(this);content.addView(row2);
        row2.addView(Ui.primary(this,"保存",this::save),new LinearLayout.LayoutParams(0,-2,1));
        row2.addView(Ui.button(this,"初期値（S25）に戻す",()->{Calibration.reset(this);if(view.hasImage())view.place(Calibration.DEFAULT,firstRowY);Ui.toast(this,"初期値に戻しました");}),new LinearLayout.LayoutParams(0,-2,1));
        result=new TextView(this);result.setTextColor(Ui.ACCENT);content.addView(result);
        view=new CalibrationView(this);view.setListener(()->{handler.removeCallbacks(check);handler.postDelayed(check,300);});
        content.addView(view,new LinearLayout.LayoutParams(-1,0,1));
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);ocr.close();super.onDestroy();}

    @Override protected void onActivityResult(int request,int code,Intent data){
        super.onActivityResult(request,code,data);if(request!=PICK||code!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),data.getData()),(d,info,src)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            Bitmap image=decoded.copy(Bitmap.Config.ARGB_8888,false);
            int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
            FactorVision.Layout layout=FactorVision.detect(pixels,w,h);
            rows=layout==null?0:layout.ys().size();firstRowY=layout==null?h*3/10:layout.ys().get(0);
            view.show(image,Calibration.current,firstRowY);autoPlace();
        }catch(Exception e){Ui.toast(this,"画像を開けませんでした");}
    }

    /** Title box from the OCR'd title line when found, else the brown-text band; chip frame from the detected stars. */
    void autoPlace(){
        if(!view.hasImage()){Ui.toast(this,"先にスクショを選んでください");return;}
        Bitmap image=view.image();int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        Calibration guess=Calibration.guess(pixels,w,h);
        result.setText("タイトルを探しています…");
        ocr.process(InputImage.fromBitmap(image,0)).addOnSuccessListener(text->{
            Calibration placed=guess;
            for(var block:text.getTextBlocks())for(var line:block.getLines()){
                Rect b=line.getBoundingBox();if(b==null||CaptureService.titleLabel(line.getText()).isEmpty())continue;
                // Wide enough for the longest title (再獲得因子N is ~6.7 glyph heights wide) whichever page the screenshot shows.
                int height=b.height(),cx=b.centerX(),half=height*42/10,pad=height*3/10;
                placed=new Calibration(Math.max(0,cx-half)/(double)w,Math.max(0,b.top-pad)/(double)h,Math.min(w,cx+half)/(double)w,Math.min(h,b.bottom+pad)/(double)h,
                    guess.chipLeft,guess.chipRight,guess.chipHalfHeight);
            }
            view.place(placed,firstRowY);
        }).addOnFailureListener(e->view.place(guess,firstRowY));
    }

    /** Reads the current title box like the live recognition does, and reports it with the row count. */
    void check(){
        if(!view.hasImage())return;Bitmap image=view.image();Calibration c=view.calibration();
        String rowText=rows>0?"因子の行："+rows+" 行見つかりました":"因子の行が見つかりません（因子選択画面のスクショですか？）";
        Bitmap input=CaptureService.ocrInput(image,c.titleBox(image.getWidth(),image.getHeight()));
        ocr.process(InputImage.fromBitmap(input,0)).addOnSuccessListener(text->{
            String label=CaptureService.headerLabel(text),raw=text.getText().replace('\n',' ').trim();
            result.setText((label.isEmpty()?"タイトル：読めません（OCR「"+raw+"」）。青い枠を文字に合わせてください":"タイトル："+label+" と読めました（OCR「"+raw+"」）")+"\n"+rowText);
        }).addOnFailureListener(e->result.setText("タイトル：読み取りに失敗しました\n"+rowText));
    }

    void save(){
        if(!view.hasImage()){Ui.toast(this,"先にスクショを選んで調整してください");return;}
        view.calibration().save(this);Ui.toast(this,"読み取り位置を保存しました。次のサイクルから使います");
    }
}
