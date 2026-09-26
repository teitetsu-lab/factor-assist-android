package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Skill rows are found from the coloured icon at the left of each row, without needing the model. */
public class SkillVisionTest {
    static final int W=1080,H=2340,ICON=72,LEFT=60,PITCH=120,FIRST=900;

    /** A grey screen with square icons down the left, like the skill acquisition list. */
    static int[] screen(int rows,int left,int pitch,int first) {
        int[] pixels=new int[W*H];
        Arrays.fill(pixels,0xffe8e8e8);
        for(int r=0;r<rows;r++)icon(pixels,left,first+r*pitch);
        return pixels;
    }
    static void icon(int[] pixels,int left,int cy) {
        for(int y=cy-ICON/2;y<cy+ICON/2;y++)for(int x=left;x<left+ICON;x++)pixels[y*W+x]=0xff2f7fd0;
    }
    /** Rows are laid out from the pitch, so the list may reach past the icons; every icon must be on one. */
    static void assertRowsAt(SkillVision.Layout layout,int first,int pitch,int rows) {
        assertNotNull(layout);
        assertEquals(pitch,layout.pitch(),4);
        for(int r=0;r<rows;r++){
            int wanted=first+r*pitch;boolean found=false;
            for(int y:layout.ys())if(Math.abs(y-wanted)<=pitch*.2)found=true;
            assertTrue("行 "+wanted+" が無い："+layout.ys(),found);
        }
    }

    @Test public void everyRowIsFoundWithItsPitch(){
        var layout=SkillVision.detect(screen(6,LEFT,PITCH,FIRST),W,H);
        assertRowsAt(layout,FIRST,PITCH,6);
        // The right edge of the icons, which every name crop is measured from.
        assertEquals(LEFT+ICON,layout.iconRight(),SkillVision.DS);
    }
    @Test public void aMissedIconDoesNotCutTheListInTwo(){
        // A pale or oddly drawn icon can be missed; the gap is then twice the pitch. The rows beyond it must
        // still be read, so the missing row is filled in rather than ending the list there.
        int[] pixels=screen(5,LEFT,PITCH,FIRST);
        int missing=FIRST+2*PITCH;
        for(int y=missing-ICON/2;y<missing+ICON/2;y++)for(int x=LEFT;x<LEFT+ICON;x++)pixels[y*W+x]=0xffe8e8e8;
        var layout=SkillVision.detect(pixels,W,H);
        assertRowsAt(layout,FIRST,PITCH,5);
        boolean filled=false;for(int y:layout.ys())if(Math.abs(y-missing)<=PITCH*.2)filled=true;
        assertTrue("欠けた行が埋まっていない："+layout.ys(),filled);
    }
    @Test public void twoGapsOfDifferentWidthsStillGiveThePitch(){
        // Seen on the phone: icons 186 and 366 apart. Their median, 276, fits neither, so the pitch has to be
        // the one that explains both - 186, with the second gap being two rows.
        int[] pixels=screen(2,LEFT,PITCH,FIRST);
        icon(pixels,LEFT,FIRST+3*PITCH);
        var layout=SkillVision.detect(pixels,W,H);
        assertNotNull("行を見失っている",layout);
        assertEquals(PITCH,layout.pitch(),4);
        assertRowsAt(layout,FIRST,PITCH,4);
    }
    @Test public void theHeaderAboveTheListIsIgnored(){
        // Anything in the top third is the screen's own decoration, never a row.
        int[] pixels=screen(4,LEFT,PITCH,FIRST);
        for(int y=200;y<200+ICON;y++)for(int x=LEFT;x<LEFT+ICON;x++)pixels[y*W+x]=0xffd02f7f;
        var layout=SkillVision.detect(pixels,W,H);
        assertRowsAt(layout,FIRST,PITCH,4);
        for(int y:layout.ys())assertTrue("見出しの飾りを行にしている："+y,y>H*.34);
    }
    @Test public void rowsUnderTheBottomButtonsAreLeftAlone(){
        // The last row sits behind 説明省略 / 昇順; half of its name is covered and it reads as another skill.
        var layout=SkillVision.detect(screen(8,LEFT,PITCH,FIRST),W,H);
        assertNotNull(layout);
        for(int y:layout.ys())assertTrue("下のボタンに隠れた行を読もうとしている："+y,y<=H*SkillVision.LIST_BOTTOM);
    }
    @Test public void aScreenWithoutIconsIsNotTheSkillScreen(){
        int[] plain=new int[W*H];Arrays.fill(plain,0xffe8e8e8);
        assertNull(SkillVision.detect(plain,W,H));
    }
    @Test public void anIconSplitByItsArtworkIsStillOneRow(){
        // A pale silhouette can cut an icon into two tall halves; both would fail the square test alone.
        int[] pixels=screen(3,LEFT,PITCH,FIRST);
        for(int r=0;r<3;r++){int cy=FIRST+r*PITCH;
            for(int y=cy-ICON/2;y<cy+ICON/2;y++)for(int x=LEFT+ICON/2-3;x<LEFT+ICON/2+3;x++)pixels[y*W+x]=0xfff2f2f2;}
        assertRowsAt(SkillVision.detect(pixels,W,H),FIRST,PITCH,3);
    }
    @Test public void theNameSitsToTheRightOfTheIconAndSlightlyAbove(){
        int[] box=SkillVision.nameBox(LEFT+ICON,PITCH,FIRST);
        assertEquals(LEFT+ICON+(int)Math.rint(SkillVision.NAME_L*PITCH),box[0]);
        assertEquals(LEFT+ICON+(int)Math.rint(SkillVision.NAME_R*PITCH),box[2]);
        int centre=(box[1]+box[3])/2;
        assertTrue("名前はアイコン中心より上",centre<FIRST);
        assertEquals((int)Math.rint(SkillVision.NAME_H*PITCH),box[3]-box[1],1);
    }
    @Test public void listsAtOtherPositionsAndScalesStillWork(){
        var layout=SkillVision.detect(screen(5,140,150,1100),W,H);
        assertRowsAt(layout,1100,150,5);
        assertEquals(140+ICON,layout.iconRight(),SkillVision.DS);
    }
    @Test public void theAppsOwnFrameColoursAreIgnored(){
        // The frames are captured with the game when the whole screen is shared; left in, their strokes cut
        // the icons in two and the rows disappear.
        assertTrue(SkillVision.overlay(FactorFrames.RAINBOW[0]));
        assertTrue(SkillVision.overlay(FactorFrames.GREY));
        assertFalse("ゲーム側の色まで捨てない",SkillVision.overlay(0xff2f7fd0));
    }
}
