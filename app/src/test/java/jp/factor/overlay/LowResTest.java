package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * The screen is taken at about 720 across whatever the phone (CaptureService.captureSize): the recorded 1080-wide
 * screens, shrunk the way the phone shrinks its mirror of the screen, must read just as they do at full size.
 */
public class LowResTest {
    record Frame(int[] pixels,int w,int h) {}
    static Frame load(String name)throws Exception{
        try(var input=new DataInputStream(new GZIPInputStream(LowResTest.class.getResourceAsStream(name)))){
            int w=input.readInt(),h=input.readInt();int[] p=new int[w*h];for(int i=0;i<p.length;i++)p[i]=input.readInt();
            return new Frame(p,w,h);
        }
    }
    /** Bilinear, as the phone's compositor scales the mirrored screen into a smaller display. */
    static Frame shrink(Frame f,int across){
        int[] size=CaptureService.captureSize(f.w(),f.h(),across);int nw=size[0],nh=size[1];int[] out=new int[nw*nh];
        double sx=f.w()/(double)nw,sy=f.h()/(double)nh;
        for(int y=0;y<nh;y++){double fy=Math.max(0,(y+.5)*sy-.5);int y0=(int)fy,y1=Math.min(f.h()-1,y0+1);double dy=fy-y0;
            for(int x=0;x<nw;x++){double fx=Math.max(0,(x+.5)*sx-.5);int x0=(int)fx,x1=Math.min(f.w()-1,x0+1);double dx=fx-x0;
                int a=f.pixels()[y0*f.w()+x0],b=f.pixels()[y0*f.w()+x1],c=f.pixels()[y1*f.w()+x0],d=f.pixels()[y1*f.w()+x1];int v=0xff000000;
                for(int shift=0;shift<24;shift+=8){
                    double top=((a>>shift)&255)*(1-dx)+((b>>shift)&255)*dx,bottom=((c>>shift)&255)*(1-dx)+((d>>shift)&255)*dx;
                    v|=((int)Math.round(top*(1-dy)+bottom*dy)&255)<<shift;}
                out[y*nw+x]=v;}}
        return new Frame(out,nw,nh);
    }
    static Map<String,Integer> factors(Frame f)throws Exception{
        var layout=FactorVision.detect(f.pixels(),f.w(),f.h());assertNotNull("因子の行が見つからない（"+f.w()+"x"+f.h()+"）",layout);
        Path assets=Path.of(System.getProperty("factor.assets"));
        try(var vision=new FactorVision(Files.readAllBytes(assets.resolve("factor.onnx")),Files.readAllBytes(assets.resolve("factor_rank.onnx")),new String(Files.readAllBytes(assets.resolve("labels.json")),StandardCharsets.UTF_8))){
            Map<String,Integer> read=new LinkedHashMap<>();
            for(var row:vision.read(f.pixels(),f.w(),f.h(),layout))if(row.nameConfidence()>=FactorVision.NAME_MIN&&row.starConfidence()>=FactorVision.STAR_MIN)read.put(row.name(),row.stars());
            return read;
        }
    }
    static List<String> skills(Frame f)throws Exception{
        var layout=SkillVision.detect(f.pixels(),f.w(),f.h());assertNotNull("スキルの行が見つからない（"+f.w()+"x"+f.h()+"）",layout);
        Path assets=Path.of(System.getProperty("factor.assets"));
        try(var vision=new SkillVision(Files.readAllBytes(assets.resolve("skill.onnx")),new String(Files.readAllBytes(assets.resolve("labels.json")),StandardCharsets.UTF_8))){
            List<String> names=new ArrayList<>();
            for(var row:vision.read(f.pixels(),f.w(),f.h(),layout))if(row.confidence()>=SkillVision.NAME_MIN)names.add(row.name());
            return names;
        }
    }

    @Test public void theCaptureIsAbout720AcrossWhateverThePhone(){
        assertArrayEquals(new int[]{720,1560},CaptureService.captureSize(1080,2340,720));   // FHD+ (S25)
        assertArrayEquals(new int[]{720,1560},CaptureService.captureSize(1440,3120,720));   // WQHD+
        assertArrayEquals(new int[]{720,1280},CaptureService.captureSize(2160,3840,720));   // 4K
        assertArrayEquals(new int[]{1560,720},CaptureService.captureSize(3120,1440,720));   // turned sideways: the short side
        assertArrayEquals("a screen no bigger is taken as it is",new int[]{720,1280},CaptureService.captureSize(720,1280,720));
        assertArrayEquals(new int[]{600,1000},CaptureService.captureSize(600,1000,720));
    }
    @Test public void factorsReadTheSameAt720()throws Exception{
        Frame full=load("/frame00.argb.gz");
        assertEquals(factors(full),factors(shrink(full,720)));
    }
    @Test public void skillsReadTheSameAt720()throws Exception{
        Frame full=load("/skill00.argb.gz");
        assertEquals(skills(full),skills(shrink(full,720)));
    }
}
