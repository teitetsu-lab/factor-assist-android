package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;

/** Rows are counted only while the page is standing still, never mid swipe. */
public class PageSettleTest {
    static byte[] mask(int fill){byte[] m=new byte[TitleMask.COLUMNS*TitleMask.ROWS];java.util.Arrays.fill(m,(byte)fill);return m;}

    @Test public void twoIdenticalFramesAreSettled(){
        PageSettle settle=new PageSettle();
        assertFalse("the first frame has nothing to compare with",settle.settled(mask(10),828,120));
        assertTrue(settle.settled(mask(10),828,120));
        assertTrue(settle.settled(mask(10),830,120));   // a couple of pixels of detection jitter is fine
    }
    @Test public void aMovingTitleOrListIsNotSettled(){
        PageSettle settle=new PageSettle();
        settle.settled(mask(10),828,120);
        assertFalse("the title is sliding in",settle.settled(mask(120),828,120));
        assertTrue(settle.settled(mask(120),828,120));
        assertFalse("the list is sliding sideways",settle.settled(mask(120),700,120));
        assertFalse("and again while it keeps moving",settle.settled(mask(120),760,120));
        assertTrue(settle.settled(mask(120),760,120));
    }
    @Test public void withoutATitleNothingIsSettled(){
        PageSettle settle=new PageSettle();settle.settled(mask(10),828,120);
        assertFalse(settle.settled(null,828,120));
        assertFalse(settle.settled(mask(10),828,120));  // the previous frame had no title to compare with
        assertTrue(settle.settled(mask(10),828,120));
    }
    @Test public void forgettingRequiresTwoFreshFrames(){
        PageSettle settle=new PageSettle();settle.settled(mask(10),828,120);settle.settled(mask(10),828,120);
        settle.forget();
        assertFalse(settle.settled(mask(10),828,120));
        assertTrue(settle.settled(mask(10),828,120));
    }
}
