package jp.factor.overlay;

/**
 * Tells a settled page from one that is still sliding. Swiping from 再獲得因子4 to 3 shows the new title while
 * the old page's rows are still on screen, and those rows would otherwise be counted under the new page.
 *
 * A frame counts as settled only when the title pixels and the star column are both where they were in the
 * previous frame; anything moving means the page is still changing.
 */
final class PageSettle {
    private byte[] mask;private int starX=-1;

    /** True when this frame matches the previous one, so its rows belong to the title that is showing. */
    boolean settled(byte[] titleMask,int starXNow,int pitch){
        boolean same=titleMask!=null&&mask!=null&&TitleMask.distance(titleMask,mask)==0
            &&starX>=0&&Math.abs(starXNow-starX)<=Math.max(2,pitch/20);
        mask=titleMask;starX=starXNow;
        return same;
    }
    void forget(){mask=null;starX=-1;}
}
