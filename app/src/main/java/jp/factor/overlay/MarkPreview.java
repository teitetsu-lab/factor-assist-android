package jp.factor.overlay;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * A sketch of one game row with what is drawn on it, for choosing between the frame and the letter or line in the
 * settings: a factor card (ball, name, stars) or a skill card (icon, name, the grey line under it, the - button).
 */
final class MarkPreview extends View {
    private final boolean skill,alternative;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    MarkPreview(Context c,boolean skill,boolean alternative){super(c);this.skill=skill;this.alternative=alternative;}

    @Override protected void onDraw(Canvas canvas){
        float w=getWidth(),h=getHeight(),pad=w*.05f;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xff9ab3d8);canvas.drawRoundRect(0,0,w,h,w*.04f,w*.04f,paint);
        RectF card=new RectF(pad,h*.2f,w-pad,h*.8f);float ch=card.height();
        paint.setColor(0xffeceef0);canvas.drawRoundRect(card,ch*.18f,ch*.18f,paint);
        paint.setTextSize(ch*.26f);paint.setFakeBoldText(true);paint.setColor(0xff7a4a1e);
        int red=FactorFrames.RAINBOW[0];
        if(!skill){
            paint.setColor(0xffb8c0f0);canvas.drawCircle(card.left+ch*.35f,card.centerY(),ch*.13f,paint);
            paint.setColor(0xff7a4a1e);canvas.drawText("コーナー巧者○",card.left+ch*.6f,card.centerY()+ch*.09f,paint);
            paint.setColor(0xfff5c518);for(int i=0;i<3;i++)canvas.drawCircle(card.right-ch*(1.05f-i*.32f),card.centerY(),ch*.1f,paint);
            if(alternative){
                paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(ch*.5f);
                float x=card.right-ch*1.55f,base=card.centerY()+ch*.17f;
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ch*.07f);paint.setColor(Color.WHITE);canvas.drawText("必",x,base,paint);
                paint.setStyle(Paint.Style.FILL);paint.setColor(red);canvas.drawText("必",x,base,paint);paint.setTextAlign(Paint.Align.LEFT);
            }
        }else{
            paint.setColor(0xffff9d3c);canvas.drawRoundRect(card.left+ch*.12f,card.top+ch*.14f,card.left+ch*.72f,card.bottom-ch*.14f,ch*.1f,ch*.1f,paint);
            paint.setColor(0xff7a4a1e);canvas.drawText("コーナー巧者○",card.left+ch*.9f,card.top+ch*.36f,paint);
            float lineY=card.top+ch*.48f,lineL=card.left+ch*.9f,lineR=card.right-ch*1.25f;
            paint.setColor(0xffc8ccd2);canvas.drawRect(lineL,lineY-ch*.012f,lineR,lineY+ch*.012f,paint);
            paint.setColor(0xff8a8f99);canvas.drawRoundRect(card.right-ch*1.1f,card.centerY()-ch*.2f,card.right-ch*.7f,card.centerY()+ch*.2f,ch*.08f,ch*.08f,paint);
            paint.setColor(0xff4caf50);canvas.drawRoundRect(card.right-ch*.6f,card.centerY()-ch*.2f,card.right-ch*.2f,card.centerY()+ch*.2f,ch*.08f,ch*.08f,paint);
            if(alternative){
                float b=ch*.2f;paint.setColor(FactorFrames.RAINBOW[4]);canvas.drawRoundRect(lineL+b*1.2f,lineY-ch*.035f,lineR,lineY+ch*.035f,ch*.03f,ch*.03f,paint);
                paint.setColor(red);canvas.drawRoundRect(lineL,lineY-b/2,lineL+b,lineY+b/2,b*.3f,b*.3f,paint);
                paint.setColor(Color.WHITE);paint.setTextSize(b*.72f);paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("必",lineL+b/2,lineY-(paint.descent()+paint.ascent())/2,paint);paint.setTextAlign(Paint.Align.LEFT);
            }
        }
        if(!alternative){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ch*.06f);paint.setColor(red);
            canvas.drawRoundRect(card,ch*.18f,ch*.18f,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
}
