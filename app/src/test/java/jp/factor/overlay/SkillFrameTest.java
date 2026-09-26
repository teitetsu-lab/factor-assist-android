package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** A real skill acquisition screen from the phone, so the crop ratios are checked against the device. */
public class SkillFrameTest {
    static int[] pixels;static int w,h;

    static void load()throws Exception{
        if(pixels!=null)return;
        try(var input=new DataInputStream(new GZIPInputStream(SkillFrameTest.class.getResourceAsStream("/skill00.argb.gz")))){
            w=input.readInt();h=input.readInt();pixels=new int[w*h];for(int i=0;i<pixels.length;i++)pixels[i]=input.readInt();
        }
    }

    @Test public void theListIsFoundOnTheRecordedPhoneScreen()throws Exception{
        load();
        var layout=SkillVision.detect(pixels,w,h);
        assertNotNull("スキル画面を検出できない",layout);
        // The recording is 1080x2340; the rows sit about 184px apart, and the icons end around x=180.
        assertEquals(184,layout.pitch(),8);
        assertEquals(180,layout.iconRight(),20);
        assertTrue("行が少なすぎる："+layout.ys().size(),layout.ys().size()>=4);
        for(int y:layout.ys())assertTrue("キャラ絵の領域を行にしている",y>h*.34);
    }

    /** The rows sit on the skill cards: each row inside one whole card, and no two rows on the same card. */
    @Test public void theRowsAreOnWholeCards()throws Exception{
        load();
        var layout=SkillVision.detect(pixels,w,h);assertNotNull(layout);
        var cards=CardList.edges(pixels,w,h,(int)Math.rint(SkillVision.CARD_SCAN_X*w));
        assertTrue("カードが見つからない："+cards.size(),cards.size()>=4);
        Set<Integer> used=new HashSet<>();
        for(int y:layout.ys()){
            int on=-1;
            for(int i=0;i<cards.size();i++)if(y>cards.get(i)[0]&&y<cards.get(i)[1])on=i;
            assertTrue("行 y"+y+" がどのカードにも乗っていない",on>=0);
            assertTrue("同じカードに2行",used.add(on));
        }
    }

    @Test public void theNamesAreReadWithTheSkillModel()throws Exception{
        load();
        var layout=SkillVision.detect(pixels,w,h);assertNotNull(layout);
        Path assets=Path.of(System.getProperty("factor.assets"));
        try(var vision=new SkillVision(Files.readAllBytes(assets.resolve("skill.onnx")),
                                       new String(Files.readAllBytes(assets.resolve("labels.json")),StandardCharsets.UTF_8))){
            List<String> names=new ArrayList<>();
            for(var row:vision.read(pixels,w,h,layout))if(row.confidence()>=SkillVision.NAME_MIN)names.add(row.name());
            // What is on screen in that frame, top to bottom.
            assertTrue("読めたスキルが少なすぎる："+names,names.size()>=4);
            for(String expected:new String[]{"曇りの日○","コーナー巧者○","直線巧者","集中力","ペースアップ"})
                assertTrue(expected+" が読めていない："+names,names.contains(expected));
        }
    }
}
