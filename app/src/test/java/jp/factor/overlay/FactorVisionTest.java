package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class FactorVisionTest {
    @Test public void recordedGalaxyFrameMatchesPcNamesAndStars()throws Exception{
        int w,h;int[] pixels;
        try(var input=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/frame00.argb.gz")))){
            w=input.readInt();h=input.readInt();pixels=new int[w*h];for(int i=0;i<pixels.length;i++)pixels[i]=input.readInt();
        }
        var layout=FactorVision.detect(pixels,w,h);assertNotNull(layout);assertEquals(120,layout.pitch(),2);assertEquals(828,layout.starX(),3);
        Path assets=Path.of(System.getProperty("factor.assets"));
        try(var vision=new FactorVision(Files.readAllBytes(assets.resolve("factor.onnx")),Files.readAllBytes(assets.resolve("factor_rank.onnx")),new String(Files.readAllBytes(assets.resolve("labels.json")),StandardCharsets.UTF_8))){
            Map<String,Integer> actual=new LinkedHashMap<>();for(var row:vision.read(pixels,w,h,layout))if(row.nameConfidence()>=.97&&row.starConfidence()>=.94)actual.put(row.name(),row.stars());
            Map<String,Integer> expected=Map.of("賢さ",2,"マイル",2,"夏空ハレーション",2,"天皇賞（春）",2,"マイルCS",1,"チャンピオンズC",2,"朝日杯FS",2,"東京大賞典",3);
            assertEquals(expected,actual);
        }
    }
    @Test public void emptyScreenIsNotFactorList(){assertNull(FactorVision.detect(new int[1080*2340],1080,2340));}
    @Test public void cropProducesBgr(){int[] pixels=new int[16*168];Arrays.fill(pixels,0xff102030);byte[] output=FactorVision.crop(pixels,168,16,0,0,168,16,168,false);assertEquals(0x30,output[0]&255);assertEquals(0x20,output[1]&255);assertEquals(0x10,output[2]&255);}
    @Test public void rateHasNoArtificialCeiling(){assertEquals(200_000_000L,CycleLimit.intervalNanos("5"));assertEquals(1_000_000_000L,CycleLimit.intervalNanos("1"));assertEquals(1,CycleLimit.intervalNanos("999999999999999999999999999999999999"));assertEquals("1000",CycleLimit.validate("1000"));}
    @Test(expected=IllegalArgumentException.class)public void rejectsZero(){CycleLimit.validate("0");}
    @Test public void statisticsMeasureCompletedCyclesNotConfiguredRate(){CycleStats stats=new CycleStats();stats.reset(0);for(int i=1;i<=50;i++)stats.add(i*100_000_000L,20_000_000L);assertEquals(10,stats.rate(5_000_000_000L,5),.001);assertEquals(0,stats.rate(66_000_000_000L,5),.001);}
}
