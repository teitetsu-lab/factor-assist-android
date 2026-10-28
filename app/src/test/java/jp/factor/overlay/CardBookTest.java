package jp.factor.overlay;

import static org.junit.Assert.*;
import org.junit.Test;

/** The common chip and the letters on the factor mode's test frame (frame00: 1080 wide, chips 48-1034 across, 97 tall). */
public class CardBookTest {
    static final int[] TOPS={583,704,824,944,1064,1184,1304,1424};
    static final int L=48,CW=1034-48,H=97;

    /** Where each chip's letters end, as measured on the frame: 賢さ, マイル (its 指定 badge after it, to 332), 夏空ハレーション, 天皇賞（春）… */
    @Test public void lettersEndWhereTheNameDoes()throws Exception{
        int[] s=new int[2];int[] px=ScreenKindTest.load("/frame00.argb.gz",s);int w=s[0];
        int[] ends={170,206,379,292,256,371,253,276};
        for(int i=0;i<TOPS.length;i++){int t=TOPS[i];int[] at=letters(px,w,98,t+26,810,t+72,t+24);
            assertNotNull("chip "+t,at);assertEquals("chip "+t,ends[i],at[0],2);}
        int[] mile=letters(px,w,98,704+26,810,704+72,704+20);assertTrue("the badge's end "+mile[1],mile[1]>=328&&mile[1]<=336);
    }
    /** A white chip with its letters filled in, and another's letters put on it where they go, is that other chip again. */
    @Test public void aCommonChipAndLettersMakeTheChip()throws Exception{
        int[] s=new int[2];int[] px=ScreenKindTest.load("/frame00.argb.gz",s);int w=s[0];
        int[] common=chip(px,w,944);   // 天皇賞（春）
        int[] at=letters(px,w,98,944+26,810,944+72,944+20);int fillEnd=at[1]+8;
        assertTrue(CardBook.fill(common,CW,H,new int[][]{{98-L,26,fillEnd-L,72}},new CardBook.Blank(24,72)));
        assertNull("no letters left on it",lettersOn(common));
        for(int t:new int[]{1064,1184,1304,1424}){int[] chip=chip(px,w,t);int end=letters(px,w,98,t+26,810,t+72,t+20)[0]+6;
            int[] made=common.clone();
            for(int y=26;y<72;y++)for(int x=98-L;x<end-L;x++)made[y*CW+x]=chip[y*CW+x];
            int worst=0;for(int y=26;y<72;y++)for(int x=98-L;x<690-L;x++)worst=Math.max(worst,off(made[y*CW+x],chip[y*CW+x]));
            assertTrue("chip "+t+" off by "+worst,worst<=12);}
    }
    /** The coloured chips filled in too: the letters, and マイル's red 指定 badge, gone. */
    @Test public void colouredChipsAndTheBadgeFillIn()throws Exception{
        int[] s=new int[2];int[] px=ScreenKindTest.load("/frame00.argb.gz",s);int w=s[0];
        for(int t:new int[]{583,704,824}){int[] chip=chip(px,w,t);int[] at=letters(px,w,98,t+26,810,t+72,t+20);int fillEnd=at[1]+8;
            assertTrue(CardBook.fill(chip,CW,H,new int[][]{{98-L,26,fillEnd-L,72}},new CardBook.Blank(24,72)));
            assertNull("chip "+t,lettersOn(chip));
            int red=0;for(int y=26;y<72;y++)for(int x=98-L;x<690-L;x++){int p=chip[y*CW+x];if(((p>>16)&255)>235&&((p>>8)&255)<100&&(p&255)<150)red++;}
            assertEquals("chip "+t,0,red);}
    }

    static int[] chip(int[] px,int w,int top){int[] out=new int[CW*H];for(int y=0;y<H;y++)System.arraycopy(px,(top+y)*w+L,out,y*CW,CW);return out;}
    static int[] letters(int[] px,int w,int l,int t,int r,int b,int ref){
        int cw=r-l,ch=b-t;int[] reg=new int[cw*ch],row=new int[cw];
        for(int y=0;y<ch;y++)System.arraycopy(px,(t+y)*w+l,reg,y*cw,cw);System.arraycopy(px,ref*w+l,row,0,cw);
        int[] at=CardBook.letters(reg,row,cw,ch);return at==null?null:new int[]{l+at[0],l+at[1]};
    }
    static int[] lettersOn(int[] chip){return letters(chip,CW,98-L,26,810-L,72,24);}
    static int off(int a,int b){int m=0;for(int sh=0;sh<24;sh+=8)m=Math.max(m,Math.abs(((a>>sh)&255)-((b>>sh)&255)));return m;}
}
