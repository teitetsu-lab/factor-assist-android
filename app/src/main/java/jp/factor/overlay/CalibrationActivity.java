package jp.factor.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

/**
 * 読み取り位置の調整, in tabs. 因子画面: the user picks a screenshot of the factor selection screen, the title box and
 * chip frame are placed automatically (OCR finds the title line on the still image), the user drags them to fit,
 * along with where the level letter goes when the whole screen is shared. スキル画面: where the line under a skill's
 * name goes then. What recognition reads is shown on the screenshot, and a sample placed on it turns red. Every
 * change is checked right away: the title box is OCR-read and the factor rows are counted.
 */
public class CalibrationActivity extends Activity {
    static final int PICK=1;
    CalibrationView view;TextView result,help;LinearLayout tabs;TextView[] tabViews;
    TextRecognizer ocr;final Handler handler=new Handler();
    int rows;int firstRowY;
    /** The factor screenshot's first card, the letter's frame of reference; null when none was found. */
    CardList.Card card;
    /** The skill screenshot's first card top and its row half height, the line's frame of reference. */
    int skillTop=-1,skillHalf;
    final Runnable check=this::check;

    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Ui.themed(base,false));}
    @Override protected void onCreate(Bundle state){Ui.palette(this);
        super.onCreate(state);ocr=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        LinearLayout content=Ui.column(this);setContentView(content);
        content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(Ui.dp(this,12),i.top+Ui.dp(this,8),Ui.dp(this,12),i.bottom+Ui.dp(this,8));return insets;});
        // The same header as the settings: back, and what this screen is.
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);content.addView(header);
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(30);back.setTextColor(Ui.INK);back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.ripple(this,null,24));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,48)));
        TextView title=new TextView(this);title.setText("読み取り位置の調整");title.setTextSize(22);title.setTextColor(Ui.INK);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));header.addView(title);
        List<String> names=new ArrayList<>(List.of("因子画面"));
        names.add("スキル画面");
        tabs=new LinearLayout(this);tabs.setBackground(Ui.rounded(this,Ui.SURFACE,22));tabs.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));
        tabViews=new TextView[names.size()];
        for(int i=0;i<names.size();i++){int n=i;
            TextView t=new TextView(this);t.setText(names.get(i));t.setGravity(Gravity.CENTER);t.setTextSize(14);
            t.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));t.setOnClickListener(v->select(n==1));
            tabs.addView(t,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));tabViews[i]=t;
        }
        if(names.size()>1)content.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
        help=new TextView(this);help.setTextColor(Ui.INK);help.setPadding(0,Ui.dp(this,8),0,0);content.addView(help);
        LinearLayout row=new LinearLayout(this);content.addView(row);
        row.addView(Ui.primary(this,"スクショを選ぶ",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),PICK)),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(Ui.button(this,"自動で置き直す",this::autoPlace),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout row2=new LinearLayout(this);content.addView(row2);
        row2.addView(Ui.primary(this,"保存",this::save),new LinearLayout.LayoutParams(0,-2,1));
        row2.addView(Ui.button(this,"初期値（S25）に戻す",this::reset),new LinearLayout.LayoutParams(0,-2,1));
        result=new TextView(this);result.setTextColor(Ui.ACCENT);content.addView(result);
        view=new CalibrationView(this);view.setListener(()->{handler.removeCallbacks(check);handler.postDelayed(check,300);});
        content.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        select(false);
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);ocr.close();super.onDestroy();}

    /** Switches between the factor and the skill screen tab; each takes its own screenshot. */
    void select(boolean skill){
        for(int i=0;i<tabViews.length;i++){boolean on=(i==1)==skill;
            tabViews[i].setTextColor(on?Ui.ACCENT:Ui.MUTED);tabViews[i].setBackground(on?Ui.rounded(this,Ui.CARD,19):null);}
        help.setText(skill
            ?"スキル取得画面のスクショを選ぶと、画面全体で共有したときに引く線（名前の下の灰色の線に重ねる）の見本を置きます。辺をドラッグで長さと高さ、内側をドラッグで移動できます。"
                +"暗い範囲とピンクの縦線は読み取りに使う場所です。見本が重なると赤くなります。"
            :"ウマ娘の因子選択画面のスクショを選ぶと、青い枠（ページタイトルを読む範囲）と赤い枠（因子の色枠の大きさ）を自動で置きます。"
                +"辺をドラッグで調整、枠の内側をドラッグで移動できます。青はタイトルの文字全体が入り、左右の矢印と下の点が入らないように。赤は因子のチップ1つにぴったり合わせてください。"
                +"「必」の見本は、画面全体で共有したときに★の左に出す文字の位置と大きさです。暗い範囲とピンクの縦線は読み取りに使う場所で、重なると赤くなります。");
        view.clear(skill);result.setText("");
    }

    @Override protected void onActivityResult(int request,int code,Intent data){
        super.onActivityResult(request,code,data);if(request!=PICK||code!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),data.getData()),(d,info,src)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            Bitmap image=decoded.copy(Bitmap.Config.ARGB_8888,false);
            int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
            if(view.skill()){showSkill(image,pixels);return;}
            FactorVision.Layout layout=FactorVision.detectByStars(pixels,w,h);
            rows=layout==null?0:layout.ys().size();firstRowY=layout==null?h*3/10:layout.ys().get(0);
            var cards=FactorVision.cards(pixels,w,h);card=cards.isEmpty()?null:cards.get(0);
            if(card!=null)firstRowY=card.centre();
            view.showFactor(image,Calibration.current,firstRowY,letterBox(DrawPlaces.current,w),factorZones(w),(float)(FactorVision.CARD_SCAN_X*w));
            autoPlace();
        }catch(Exception e){Ui.toast(this,"画像を開けませんでした");}
    }
    /** The letter sample from the saved places, on the first card's row. */
    RectF letterBox(DrawPlaces at,int w){
        float cx=card!=null?card.x(at.letterUnits):(float)(FactorFrames.LETTER_X*w),size=(float)(at.letterSize*w);
        return new RectF(cx-size/2,firstRowY-size/2,cx+size/2,firstRowY+size/2);
    }
    /** What recognition reads on the factor screenshot's first card: the name, and the stars. */
    List<RectF> factorZones(int w){
        List<RectF> zones=new ArrayList<>();if(card==null)return zones;
        int[] name=FactorVision.nameOf(card);zones.add(new RectF(name[0],name[1],name[2],name[3]));
        zones.add(new RectF(card.x(FactorVision.STAR_COLUMN),card.top(),card.x(FactorVision.STARS[2]+FactorVision.STAR_HALF*3),card.bottom()));
        return zones;
    }

    SkillVision.Layout skillLayout;
    void showSkill(Bitmap image,int[] pixels){
        int w=image.getWidth(),h=image.getHeight();
        SkillVision.Layout layout=SkillVision.fromCards(pixels,w,h);if(layout==null)layout=SkillVision.detect(pixels,w,h);
        skillLayout=layout;
        if(layout==null||layout.ys().isEmpty()){skillTop=-1;result.setText("スキルの行が見つかりません（スキル取得画面のスクショですか？）");view.showSkill(image,new RectF(),List.of(),-1);return;}
        int y=layout.ys().get(0);int[] frame=layout.frameOf(y);
        skillTop=frame!=null?frame[1]:y-(int)Math.rint(layout.pitch()*.42);skillHalf=(int)Math.rint(layout.pitch()*.39);
        List<RectF> zones=new ArrayList<>();
        int[] name=SkillVision.nameBox(layout.iconRight(),layout.pitch(),y);zones.add(new RectF(name[0],name[1],name[2],name[3]));
        int[] pt=SkillVision.ptBox(layout.iconRight(),layout.pitch(),y);zones.add(new RectF(pt[0],pt[1],pt[2],pt[3]));
        view.showSkill(image,lineBox(DrawPlaces.current,w),zones,(float)(SkillVision.CARD_SCAN_X*w));
        result.setText("スキルの行："+layout.ys().size()+" 行見つかりました");
    }
    RectF lineBox(DrawPlaces at,int w){
        float y=skillTop+skillHalf*(float)at.lineY,h=skillHalf*(float)at.badge;
        return new RectF((float)(at.lineStart*w),y-h/2,(float)(at.lineEnd*w),y+h/2);
    }

    /** Title box from the OCR'd title line when found, else the brown-text band; chip frame from the detected stars. */
    void autoPlace(){
        if(!view.hasImage()){Ui.toast(this,"先にスクショを選んでください");return;}
        Bitmap image=view.image();int w=image.getWidth(),h=image.getHeight();
        if(view.skill()){view.placeLine(lineBox(DrawPlaces.DEFAULT,w));return;}
        view.placeLetter(letterBox(DrawPlaces.DEFAULT,w));
        int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
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
        if(!view.hasImage())return;
        String overlap=view.sampleOverlaps()?"\n見本が読み取り範囲に重なっています。重ならない位置に動かしてください":"";
        if(view.skill()){if(skillTop>=0)result.setText(result.getText().toString().split("\n")[0]+overlap);return;}
        Bitmap image=view.image();Calibration c=view.calibration();
        String rowText=rows>0?"因子の行："+rows+" 行見つかりました":"因子の行が見つかりません（因子選択画面のスクショですか？）";
        Bitmap input=CaptureService.ocrInput(image,c.titleBox(image.getWidth(),image.getHeight()));
        ocr.process(InputImage.fromBitmap(input,0)).addOnSuccessListener(text->{
            String label=CaptureService.headerLabel(text),raw=text.getText().replace('\n',' ').trim();
            result.setText((label.isEmpty()?"タイトル：読めません（OCR「"+raw+"」）。青い枠を文字に合わせてください":"タイトル："+label+" と読めました（OCR「"+raw+"」）")+"\n"+rowText+overlap);
        }).addOnFailureListener(e->result.setText("タイトル：読み取りに失敗しました\n"+rowText+overlap));
    }

    void save(){
        if(!view.hasImage()){Ui.toast(this,"先にスクショを選んで調整してください");return;}
        int w=view.image().getWidth();DrawPlaces at=DrawPlaces.current;
        if(view.skill()){
            if(skillTop<0){Ui.toast(this,"スキルの行が見つからないので保存できません");return;}
            RectF r=view.lineBox();
            new DrawPlaces(at.letterUnits,at.letterSize,(r.centerY()-skillTop)/skillHalf,r.left/w,r.right/w,r.height()/skillHalf).save(this);
            Ui.toast(this,"線の位置を保存しました");return;
        }
        view.calibration().save(this);
        RectF l=view.letterBox();
        double units=card!=null?(l.centerX()-card.left())/card.unit():at.letterUnits;
        new DrawPlaces(units,l.height()/w,at.lineY,at.lineStart,at.lineEnd,at.badge).save(this);
        Ui.toast(this,"読み取り位置を保存しました。次のサイクルから使います");
    }
    void reset(){
        int w=view.hasImage()?view.image().getWidth():0;DrawPlaces at=DrawPlaces.current,d=DrawPlaces.DEFAULT;
        if(view.skill()){new DrawPlaces(at.letterUnits,at.letterSize,d.lineY,d.lineStart,d.lineEnd,d.badge).save(this);if(view.hasImage()&&skillTop>=0)view.placeLine(lineBox(d,w));Ui.toast(this,"線の位置を初期値に戻しました");return;}
        Calibration.reset(this);new DrawPlaces(d.letterUnits,d.letterSize,at.lineY,at.lineStart,at.lineEnd,at.badge).save(this);
        if(view.hasImage()){view.place(Calibration.DEFAULT,firstRowY);view.placeLetter(letterBox(d,w));}
        Ui.toast(this,"初期値に戻しました");
    }
}
