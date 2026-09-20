package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;

/** Automatic placement on the recorded S25 frame (title 再獲得因子4 at x 376–704, y 463–512; chips x 48–1033). */
public class CalibrationTest {
    int w,h;int[] pixels;
    public CalibrationTest()throws Exception{
        try(var input=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/frame00.argb.gz")))){
            w=input.readInt();h=input.readInt();pixels=new int[w*h];for(int i=0;i<pixels.length;i++)pixels[i]=input.readInt();
        }
    }
    @Test public void findsTheTitleBandAboveTheList(){
        int[] band=Calibration.titleBand(pixels,w,h,FactorVision.detect(pixels,w,h).ys().get(0)-120);
        assertNotNull(band);
        assertEquals(376,band[0],6);assertEquals(463,band[1],6);assertEquals(704,band[2],6);assertEquals(512,band[3],6);
    }
    @Test public void guessCoversTheTitleButNotTheArrowsOrDots(){
        Calibration c=Calibration.guess(pixels,w,h);int[] b=c.titleBox(w,h);
        assertTrue("left "+b[0],b[0]<=376&&b[0]>=200);assertTrue("right "+b[2],b[2]>=704&&b[2]<=880);
        assertTrue("top "+b[1],b[1]<=463);assertTrue("bottom "+b[3],b[3]>=512&&b[3]<526);
        assertEquals(48,c.chipLeft*w,8);assertEquals(1033,c.chipRight*w,8);assertEquals(48,c.chipHalfHeight*w,4);
    }
    @Test public void fallsBackToTheDefaultsWithoutAFactorScreen(){
        Calibration c=Calibration.guess(new int[w*h],w,h);
        assertEquals(Calibration.DEFAULT.titleTop,c.titleTop,0);assertEquals(Calibration.DEFAULT.chipLeft,c.chipLeft,0);
    }
    @Test public void defaultsMatchTheS25Measurements(){
        int[] b=Calibration.DEFAULT.titleBox(1080,2340);assertArrayEquals(new int[]{216,444,864,519},b);
    }
}
