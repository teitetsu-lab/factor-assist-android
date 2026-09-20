package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.*;
import org.json.*;

/** Every exported QR page must scan back and reassemble through the same import path as a picked QR image. */
public class QrCodesTest {
    static String scan(boolean[][] modules)throws Exception{
        int scale=4,n=modules.length*scale;int[] pixels=new int[n*n];
        for(int y=0;y<n;y++)for(int x=0;x<n;x++)pixels[y*n+x]=modules[y/scale][x/scale]?0xff000000:0xffffffff;
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.PURE_BARCODE,true);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(n,n,pixels))),hints).getText();
    }
    static JSONObject preset(int entries)throws Exception{
        JSONObject p=Store.fresh("QRテスト");
        for(int i=0;i<entries;i++)p.getJSONArray("entries").put(new JSONObject().put("tier",Store.TIERS[i%3]).put("min_star",1+i%3).put("factor","因子"+i+"-"+UUID.randomUUID()));
        return p;
    }
    static void roundTrip(JSONObject preset,int expectedPages)throws Exception{
        var parts=QrCodes.parts(preset);assertEquals(expectedPages>1,parts.size()>1);if(expectedPages==1)assertEquals(1,parts.size());
        Map<String,String> collected=new HashMap<>();String code=null;
        for(String part:parts){String text=scan(QrCodes.modules(part));assertEquals(part,text);code=ShareCode.accept(text,collected);}
        assertNotNull(code);assertEquals(preset.toString(),ShareCode.decode(code).toString());
    }
    @Test public void smallPresetIsOneQr()throws Exception{roundTrip(preset(10),1);}
    @Test public void largePresetSplitsAndReassembles()throws Exception{roundTrip(preset(300),2);}
    @Test public void quietZoneIsFourModules()throws Exception{
        boolean[][] m=QrCodes.modules(ShareCode.encode(preset(1)));
        for(int i=0;i<4;i++)for(int j=0;j<m.length;j++){assertFalse(m[i][j]);assertFalse(m[j][i]);}
        assertTrue(m[4][4]); // the finder pattern starts right after the margin
    }
}
