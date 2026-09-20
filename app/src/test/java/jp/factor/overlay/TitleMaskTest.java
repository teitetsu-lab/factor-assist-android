package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Title shapes from the recorded S25 frame, whose page title is 再獲得因子4. */
public class TitleMaskTest {
    int w,h;int[] pixels;
    public TitleMaskTest()throws Exception{
        try(var input=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/frame00.argb.gz")))){
            w=input.readInt();h=input.readInt();pixels=new int[w*h];for(int i=0;i<pixels.length;i++)pixels[i]=input.readInt();
        }
    }
    static int clamp(int v){return Math.max(0,Math.min(255,v));}

    @Test public void findsTheTitleAndNothingOnABlankScreen(){
        byte[] mask=TitleMask.of(pixels,w,h);assertNotNull(mask);
        int cells=0;for(byte share:mask)if((share&255)*100>=255*15)cells++;assertTrue("title cells "+cells,cells>=TitleMask.MIN_TEXT_CELLS);
        int[] blank=new int[w*h];Arrays.fill(blank,0xffffffff);assertNull(TitleMask.of(blank,w,h));
    }
    @Test public void ignoresPixelNoiseAndTheAnimatedArrows(){
        byte[] base=TitleMask.of(pixels,w,h);
        int[] noisy=pixels.clone();Random random=new Random(5);
        for(int i=0;i<noisy.length;i++){int d=random.nextInt(11)-5,c=noisy[i];noisy[i]=0xff000000|clamp(((c>>16)&255)+d)<<16|clamp(((c>>8)&255)+d)<<8|clamp((c&255)+d);}
        assertEquals(0,TitleMask.distance(base,TitleMask.of(noisy,w,h)));
        // Arrows (x < 216 and x > 864) and the page dots (y ≥ 526) are outside the box, so repainting them changes nothing.
        int[] moved=pixels.clone();
        for(int y=420;y<560;y++)for(int x=0;x<w;x++)if(x<200||x>880||y>=526)moved[y*w+x]=0xff7a4020;
        assertEquals(0,TitleMask.distance(base,TitleMask.of(moved,w,h)));
    }
    @Test public void aChangedDigitIsADifferentPage(){
        byte[] base=TitleMask.of(pixels,w,h);
        // Blank the trailing "4" (x 660–704): what a different page number looks like to the mask.
        int[] other=pixels.clone();for(int y=455;y<519;y++)for(int x=660;x<710;x++)other[y*w+x]=0xffffffff;
        assertTrue(TitleMask.distance(base,TitleMask.of(other,w,h))>0);
        // Even half a digit changed (as between similar digits) must count as a different title.
        int[] half=pixels.clone();for(int y=455;y<519;y++)for(int x=660;x<682;x++)half[y*w+x]=0xffffffff;
        assertTrue(TitleMask.distance(base,TitleMask.of(half,w,h))>0);
    }
    @Test public void learnsOnlyFromTwoAgreeingReads(){
        byte[] mask=TitleMask.of(pixels,w,h);TitleMask titles=new TitleMask();
        titles.learn("再獲得因子1",mask);                 // a single misread
        assertNull(titles.match(mask));
        titles.learn("再獲得因子4",mask);titles.learn("再獲得因子4",mask);
        assertEquals("再獲得因子4",titles.match(mask));
        titles.learn("",mask);assertEquals("再獲得因子4",titles.match(mask)); // failed OCR reads are ignored
        assertNull(titles.match(null));
    }
    @Test public void neverNamesAnotherPageByShape(){
        byte[] page4=TitleMask.of(pixels,w,h);TitleMask titles=new TitleMask();
        titles.learn("再獲得因子4",page4);titles.learn("再獲得因子4",page4);
        // A title that differs only in its digit is not the current page: it must go to OCR.
        int[] other=pixels.clone();for(int y=455;y<519;y++)for(int x=660;x<710;x++)other[y*w+x]=0xffffffff;
        assertNull(titles.match(TitleMask.of(other,w,h)));
        // Once OCR confirms the new page twice, only that page is remembered.
        byte[] page3=TitleMask.of(other,w,h);titles.learn("再獲得因子3",page3);titles.learn("再獲得因子3",page3);
        assertEquals("再獲得因子3",titles.match(page3));assertNull(titles.match(page4));
    }
}
