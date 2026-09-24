package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Reading umacapture's modules.zip, and deciding when a published one is newer. */
public class ModulesTest {
    @Test public void aLaterRecognizerVersionIsNewer(){
        assertTrue(Modules.newer("2026-09-25T11:00:00+0900","2026-09-18T11:00:00+0900"));
        assertFalse(Modules.newer("2026-09-18T11:00:00+0900","2026-09-18T11:00:00+0900"));
        assertFalse(Modules.newer("2026-09-01T11:00:00+0900","2026-09-18T11:00:00+0900"));
        // Nothing known in use yet: anything readable is newer. Something unreadable never is.
        assertTrue(Modules.newer("2026-09-18T11:00:00+0900",""));
        assertFalse(Modules.newer("そのうち","2026-09-18T11:00:00+0900"));
    }
    @Test public void filesAreReadFromUnderTheModulesFolderInTheZip() throws Exception {
        File zip=File.createTempFile("modules",".zip");
        try{
            try(var out=new ZipOutputStream(new FileOutputStream(zip))){
                out.putNextEntry(new ZipEntry("modules/labels.json"));out.write("{}".getBytes(StandardCharsets.UTF_8));out.closeEntry();
                out.putNextEntry(new ZipEntry("modules/factor/prediction.onnx"));out.write(new byte[]{1,2,3});out.closeEntry();
            }
            assertEquals("{}",new String(Modules.fromZip(zip,"labels.json"),StandardCharsets.UTF_8));
            assertArrayEquals(new byte[]{1,2,3},Modules.fromZip(zip,"factor.onnx"));
            assertNull(Modules.fromZip(zip,"factor_rank.onnx"));
            assertNull(Modules.fromZip(zip,"../../escape.txt"));
        }finally{zip.delete();}
    }
    @Test public void onlyAWholeSetIsUsed() throws Exception {
        File dir=java.nio.file.Files.createTempDirectory("modules").toFile();
        try{
            File zip=new File(dir,Modules.ZIP);
            // Every model and the labels, but no version_info.json beside it yet: not used.
            try(var out=new ZipOutputStream(new FileOutputStream(zip))){
                for(String asset:Modules.REQUIRED){out.putNextEntry(new ZipEntry(Modules.FILES.get(asset)));out.write(1);out.closeEntry();}
            }
            assertFalse(Modules.complete(dir));
            new FileOutputStream(new File(dir,Modules.VERSION)).close();
            assertTrue(Modules.complete(dir));
            // A zip missing one of the models is not used at all, whatever else it holds.
            try(var out=new ZipOutputStream(new FileOutputStream(zip))){
                for(String asset:Modules.REQUIRED)if(!asset.equals("factor_rank.onnx")){out.putNextEntry(new ZipEntry(Modules.FILES.get(asset)));out.write(1);out.closeEntry();}
            }
            assertFalse(Modules.complete(dir));
            // Nor is a file that is not a zip.
            try(var out=new FileOutputStream(zip)){out.write("broken".getBytes(StandardCharsets.UTF_8));}
            assertFalse(Modules.complete(dir));
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
