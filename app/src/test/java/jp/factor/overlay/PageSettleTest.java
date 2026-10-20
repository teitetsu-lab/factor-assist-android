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
    /**
     * Read by the cards, the crops are placed from a fixed left edge; the list sliding sideways into place under a
     * title that already stands still shows only in the star column as measured.
     */
    @Test public void aListSlidingSidewaysIsSeenWithCards()throws Exception{
        int w,h;int[] still;
        try(var input=new java.io.DataInputStream(new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/frame00.argb.gz")))){
            w=input.readInt();h=input.readInt();still=new int[w*h];for(int i=0;i<still.length;i++)still[i]=input.readInt();
        }
        int shift=20;int[] slid=new int[still.length];   // 20px to the left: the cards are still found there
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)slid[y*w+x]=still[y*w+Math.min(w-1,x+shift)];
        var a=FactorVision.detectCards(still,w,h,0,h);var b=FactorVision.detectCards(slid,w,h,0,h);
        assertNotNull(a);assertNotNull(b);
        assertEquals("the crops stay where the width puts them",a.starX(),b.starX());
        assertEquals(-shift,b.measuredStarX()-a.measuredStarX(),3);
        PageSettle settle=new PageSettle();
        settle.settled(mask(10),a.measuredStarX(),a.pitch());
        assertTrue(settle.settled(mask(10),a.measuredStarX(),a.pitch()));
        assertFalse("the title stood still, the list did not",settle.settled(mask(10),b.measuredStarX(),b.pitch()));
    }
    @Test public void forgettingRequiresTwoFreshFrames(){
        PageSettle settle=new PageSettle();settle.settled(mask(10),828,120);settle.settled(mask(10),828,120);
        settle.forget();
        assertFalse(settle.settled(mask(10),828,120));
        assertTrue(settle.settled(mask(10),828,120));
    }
}
