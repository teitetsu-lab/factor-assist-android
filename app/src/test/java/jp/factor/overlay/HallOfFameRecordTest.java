package jp.factor.overlay;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;

public class HallOfFameRecordTest {
    @Test public void aRecordBrokenHalfWayIsReadFromTheOneBefore()throws Exception{
        File dir=Files.createTempDirectory("hof").toFile();
        HallOfFame.writeMeta(dir,new JSONObject().put("key","a").put("saved",true));
        HallOfFame.writeMeta(dir,new JSONObject().put("key","a").put("saved",true).put("name","スズカ"));
        assertEquals("スズカ",HallOfFame.readMeta(dir).optString("name"));
        // Cut off as it was written: the one before is read instead.
        Files.write(new File(dir,"meta.json").toPath(),"{\"key\":\"a\",\"sav".getBytes("UTF-8"));
        JSONObject m=HallOfFame.readMeta(dir);
        assertNotNull(m);assertTrue(m.optBoolean("saved"));assertEquals("スズカ",m.optString("name"));
        // No half-written file is left beside it, and the record is mended: it reads again by itself.
        assertFalse(new File(dir,"meta.json.tmp").exists());
        assertEquals("スズカ",new JSONObject(new String(Files.readAllBytes(new File(dir,"meta.json").toPath()),"UTF-8")).optString("name"));
    }
    @Test public void theCopyNeverSaysNotSavedOverSaved()throws Exception{
        File dir=Files.createTempDirectory("hof").toFile();
        HallOfFame.writeMeta(dir,new JSONObject().put("key","a").put("saved",true).put("name","スズカ"));
        // Written as not saved for the same horse (a stale session, say): the copy keeps saying saved.
        HallOfFame.writeMeta(dir,new JSONObject().put("key","a").put("saved",false).put("name","スズカ"));
        Files.write(new File(dir,"meta.json").toPath(),"{broken".getBytes("UTF-8"));
        assertTrue(HallOfFame.readMeta(dir).optBoolean("saved"));
        // Another horse's record (the folder reused) does replace it.
        HallOfFame.writeMeta(dir,new JSONObject().put("key","b").put("saved",false));
        Files.write(new File(dir,"meta.json").toPath(),"{broken".getBytes("UTF-8"));
        assertEquals("b",HallOfFame.readMeta(dir).optString("key"));
    }
    @Test public void nothingThereReadsAsNull()throws Exception{
        assertNull(HallOfFame.readMeta(Files.createTempDirectory("hof").toFile()));
    }
}
