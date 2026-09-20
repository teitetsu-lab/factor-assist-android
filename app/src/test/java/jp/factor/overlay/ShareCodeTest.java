package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class ShareCodeTest {
    static final String CODE="UFP1.eNqrVkrNKynKTC1WsoquVkpLTC7JL1KyUno2Z_WLlllKOkq5mXnxxSWJQDEjHaWSzFSQ5Mv23hfLGpVqY3WUMlOA_JLU4hJdIEtHKS8xNxWke2Pf8ykrgPzSgpTEktSU-MQSoKiRgZGZroGlrqFliIGhlYEBEEUB1ZSlFhVn5ucpWRnWAgBuzDDs.f6a7b340e02b89b7";
    @Test public void readsPythonCode() throws Exception {var p=ShareCode.decode(CODE);assertEquals("汎用",p.getString("name"));assertEquals("末脚",p.getJSONArray("entries").getJSONObject(0).getString("factor"));assertEquals(2,p.getJSONArray("entries").getJSONObject(0).getInt("min_star"));}
    @Test public void combinesSavedQrImagesInEitherOrder() throws Exception {String hash=ShareCode.hash(CODE.getBytes(StandardCharsets.US_ASCII));Map<String,String> parts=new HashMap<>();assertNull(ShareCode.accept("UFPQ1:"+hash+":2:2:"+CODE.substring(100),parts));assertEquals(CODE,ShareCode.accept("UFPQ1:"+hash+":1:2:"+CODE.substring(0,100),parts));}
    @Test(expected=Exception.class) public void rejectsDamage() throws Exception {ShareCode.decode(CODE.substring(0,CODE.length()-1)+"0");}
    @Test public void normalizesGameNames(){assertEquals(Store.normalize("レースの真髄・速"),Store.normalize("レースの真髄 速"));assertEquals(Store.normalize("右回り〇"),Store.normalize("右回り○"));}
}
