package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;

/**
 * Switching between factor and skill reading by itself goes by the card lists: each screen's card detection finds
 * its own screen and not the other. (The older whole-frame factor detection also fires on the skill screen, which
 * is why it is not used for this.)
 */
public class ScreenKindTest {
    static int[] load(String name,int[] size)throws Exception{
        try(var input=new DataInputStream(new GZIPInputStream(ScreenKindTest.class.getResourceAsStream(name)))){
            size[0]=input.readInt();size[1]=input.readInt();int[] pixels=new int[size[0]*size[1]];
            for(int i=0;i<pixels.length;i++)pixels[i]=input.readInt();return pixels;
        }
    }
    static boolean factorCards(int[] p,int w,int h){return FactorVision.detectCards(p,w,h,(int)(h*FactorVision.LIST_TOP),(int)(h*FactorVision.LIST_BOTTOM))!=null;}
    static boolean skillCards(int[] p,int w,int h){return SkillVision.fromCards(p,w,h,(int)(h*SkillVision.COPY_TOP),(int)(h*SkillVision.COPY_BOTTOM))!=null;}
    @Test public void eachCardListIsFoundOnItsOwnScreenOnly()throws Exception{
        int[] fs=new int[2],ss=new int[2];int[] factor=load("/frame00.argb.gz",fs),skill=load("/skill00.argb.gz",ss);
        assertTrue("因子画面で因子のカードが見つからない",factorCards(factor,fs[0],fs[1]));
        assertFalse("因子画面をスキル画面と見ている",skillCards(factor,fs[0],fs[1]));
        assertTrue("スキル画面でスキルのカードが見つからない",skillCards(skill,ss[0],ss[1]));
        assertFalse("スキル画面を因子画面と見ている",factorCards(skill,ss[0],ss[1]));
    }
}
