package jp.factor.overlay;

import ai.onnxruntime.*;
import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which horse a portrait is: umacapture's character model (modules/character), which takes a trainee's icon at 32×32
 * and names its outfit - "[Fate's Chosen Star]エピファネイア", the epithet in brackets before the name - from the
 * labels' character.card. Read off the portrait at the top of 殿堂入りウマ娘の詳細 (it names it as surely as the icon
 * in the 継承 list, which is what umacapture reads), so no text is read for the name or the epithet.
 */
final class CharaVision implements AutoCloseable {
    /** An outfit as the model names it: the epithet (without its brackets) and the name; how sure. */
    record Outfit(String epithet,String name,float confidence) {}
    /** The portrait's place at the top of the dialog (S25, 1080×2340): its round frame, a square. */
    static final double LEFT=100/1080.0,TOP=382/2340.0,SIDE=232/1080.0;
    /** Below this the reading is not taken. */
    static final float SURE=.9f;
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final JSONArray cards;

    CharaVision(byte[] model,String labelsJson)throws Exception{
        session=env.createSession(model,new OrtSession.SessionOptions());
        cards=new JSONObject(labelsJson).getJSONArray("character.card");
    }
    /** The outfit of the portrait in the frame; null when the model is unsure. */
    Outfit read(Bitmap frame,int w,int h)throws Exception{
        int side=(int)Math.round(SIDE*w);
        Bitmap crop=Bitmap.createBitmap(frame,(int)Math.round(LEFT*w),(int)Math.round(TOP*h),side,side);
        Bitmap small=Bitmap.createScaledBitmap(crop,32,32,true);crop.recycle();
        int[] px=new int[32*32];small.getPixels(px,0,32,0,0,32,32);small.recycle();
        // As the model was made with (OpenCV): blue, green, red.
        ByteBuffer in=ByteBuffer.allocateDirect(32*32*3);
        for(int p:px){in.put((byte)(p&255));in.put((byte)((p>>8)&255));in.put((byte)((p>>16)&255));}
        in.rewind();
        try(OnnxTensor t=OnnxTensor.createTensor(env,in,new long[]{1,32,32,3},OnnxJavaType.UINT8);var out=session.run(Map.of("input",t))){
            long index=((long[])out.get("card_index").get().getValue())[0];float sure=((float[])out.get("card_confidence").get().getValue())[0];
            if(sure<SURE||index<0||index>=cards.length())return null;
            String label=cards.getString((int)index);int end=label.indexOf(']');
            String epithet=label.startsWith("[")&&end>0?label.substring(1,end):"",name=end>0?label.substring(end+1):label;
            return new Outfit(epithet.trim(),name.trim(),sure);
        }
    }
    @Override public void close(){try{session.close();}catch(Exception ignored){}}
}
