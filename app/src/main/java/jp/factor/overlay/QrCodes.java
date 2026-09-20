package jp.factor.overlay;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

/** Preset QR codes with the PC qr_png settings (error correction M, 4-module quiet zone), one per ShareCode.split part. */
final class QrCodes {
    static List<String> parts(org.json.JSONObject preset) throws Exception { return ShareCode.split(ShareCode.encode(preset)); }

    /** One boolean per module including the quiet zone, true = dark. Kept free of Android types for unit tests. */
    static boolean[][] modules(String text) throws WriterException {
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.MARGIN,4);
        // Size 1 lets ZXing pick the natural module count instead of scaling.
        BitMatrix m=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,1,1,hints);
        boolean[][] out=new boolean[m.getHeight()][m.getWidth()];
        for(int y=0;y<m.getHeight();y++)for(int x=0;x<m.getWidth();x++)out[y][x]=m.get(x,y);
        return out;
    }

    /** Scaled to whole pixels per module, at least minSize wide, so scanners never see blurred edges. */
    static android.graphics.Bitmap bitmap(String text,int minSize) throws WriterException {
        boolean[][] m=modules(text);int n=m.length,scale=Math.max(1,(minSize+n-1)/n),size=n*scale;
        int[] pixels=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[y*size+x]=m[y/scale][x/scale]?0xff000000:0xffffffff;
        return android.graphics.Bitmap.createBitmap(pixels,size,size,android.graphics.Bitmap.Config.ARGB_8888);
    }
}
