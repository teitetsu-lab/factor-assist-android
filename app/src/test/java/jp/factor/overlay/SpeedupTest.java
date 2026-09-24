package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** The speed changes must not change what is read: every pre-inference step is compared bit for bit with the old code. */
public class SpeedupTest {
    int w,h;int[] pixels;
    public SpeedupTest()throws Exception{
        try(var input=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/frame00.argb.gz")))){
            w=input.readInt();h=input.readInt();pixels=new int[w*h];for(int i=0;i<pixels.length;i++)pixels[i]=input.readInt();
        }
    }

    @Test public void detectMatchesLegacyAcrossReusedScratch(){
        var expected=LegacyVision.detect(pixels,w,h);assertNotNull(expected);
        assertEquals(expected,FactorVision.detectByStars(pixels,w,h));
        assertNull(FactorVision.detectByStars(new int[w*h],w,h));
        // A smaller frame after a larger one reuses the oversized scratch arrays.
        int sw=w/2,sh=h/2;int[] small=new int[sw*sh];for(int y=0;y<sh;y++)System.arraycopy(pixels,y*w,small,y*sw,sw);
        assertEquals(LegacyVision.detect(small,sw,sh),FactorVision.detectByStars(small,sw,sh));
        assertEquals(expected,FactorVision.detectByStars(pixels,w,h));
    }

    @Test public void cropsMatchLegacyForEveryRowAndOffset(){
        var layout=FactorVision.detectByStars(pixels,w,h);int p=layout.pitch(),s=layout.starX();
        for(int y:layout.ys())for(int k=-1;k<=1;k++){int offset=(int)Math.rint(k*p/68.0);
            // Coordinates written out exactly as the old read() computed them.
            byte[] oldName=LegacyVision.crop(pixels,w,h,Math.max(0,(int)(s-6.18*p)),(int)(y+offset-.279*p),(int)(s-2.15*p),(int)(y+offset+.191*p),168,true);
            byte[] oldRank=LegacyVision.crop(pixels,w,h,(int)(s-.12*p),(int)(y+offset-.25*p),(int)(s+1.24*p),(int)(y+offset+.22*p),52,false);
            int[] nb=FactorVision.nameBox(s,p,y,k),rb=FactorVision.rankBox(s,p,y,k);
            assertEquals(oldName!=null,FactorVision.inside(nb,w,h));assertEquals(oldRank!=null,FactorVision.inside(rb,w,h));
            if(oldName!=null)assertArrayEquals(oldName,FactorVision.crop(pixels,w,h,nb[0],nb[1],nb[2],nb[3],168,true));
            if(oldRank!=null)assertArrayEquals(oldRank,FactorVision.crop(pixels,w,h,rb[0],rb[1],rb[2],rb[3],52,false));
        }
    }

    @Test public void cropMatchesLegacyOnNoise(){
        Random random=new Random(7);int[] noise=new int[300*90];for(int i=0;i<noise.length;i++)noise[i]=0xff000000|random.nextInt(0x1000000);
        assertArrayEquals(LegacyVision.crop(noise,300,90,3,5,297,80,168,true),FactorVision.crop(noise,300,90,3,5,297,80,168,true));
        assertArrayEquals(LegacyVision.crop(noise,300,90,10,2,70,40,52,false),FactorVision.crop(noise,300,90,10,2,70,40,52,false));
    }

    static int clamp(int v){return Math.max(0,Math.min(255,v));}
}
