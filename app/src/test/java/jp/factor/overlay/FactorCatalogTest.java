package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Guards the bundled module files: rerun after update-modules.ps1 to catch files from mismatched versions. */
public class FactorCatalogTest {
    static final Path ASSETS=Path.of(System.getProperty("factor.assets"));
    static String asset(String name)throws Exception{return new String(Files.readAllBytes(ASSETS.resolve(name)),StandardCharsets.UTF_8);}
    static FactorCatalog bundled()throws Exception{return FactorCatalog.parse(asset("labels.json"),asset("factor_info.json"),asset("factor_tag.json"));}

    @Test public void everyModelClassIsListedOnceAndDescribed()throws Exception{
        var catalog=bundled();int labels=new JSONObject(asset("labels.json")).getJSONArray("factor.name").length();
        assertEquals(labels,catalog.factors.size());assertEquals(labels,catalog.known.size());
        // The bundled factor_info.json comes from the same module version as labels.json, so nothing is left uncategorised.
        assertEquals(0,catalog.uncategorised);
        assertFalse(catalog.categories.contains(FactorCatalog.UNCATEGORISED));
    }
    @Test public void searchMatchesNameReadingAndRomaji()throws Exception{
        var catalog=bundled();
        assertTrue(names(catalog.filter(null,"コーナー巧者")).contains("コーナー巧者○"));
        assertTrue(names(catalog.filter(null,"こーなーこうしゃ")).contains("コーナー巧者○"));
        assertTrue(names(catalog.filter(null,"koonaakousha")).contains("コーナー巧者○"));
        assertTrue(names(catalog.filter(null,"L'Arc・栄養管理")).contains("L'Arc・栄養管理"));
    }
    @Test public void categoriesFilterAndKeepTagOrder()throws Exception{
        var catalog=bundled();
        assertEquals("青因子",catalog.categories.get(0));
        var blue=names(catalog.filter("青因子",""));assertTrue(blue.containsAll(List.of("スピード","スタミナ","パワー","根性","賢さ")));assertEquals(5,blue.size());
        assertTrue(names(catalog.filter("赤因子","")).contains("芝"));
    }
    @Test public void factorsNewerThanTheInfoFileStillAppear()throws Exception{
        String labels="{\"factor.name\":[\"スピード\",\"新しい因子\"]}";
        String info="[{\"sid\":0,\"sort_key\":5,\"names\":[\"スピード\",\"すぴーど\",\"supiido\"],\"tags\":[\"factor_status\"]}]";
        var catalog=FactorCatalog.parse(labels,info,"[{\"id\":\"factor_status\",\"name\":\"青因子\"}]");
        assertEquals(List.of("スピード","新しい因子"),names(catalog.factors));
        assertEquals(FactorCatalog.UNCATEGORISED,catalog.factors.get(1).category());assertEquals(1,catalog.uncategorised);
        assertEquals(List.of("青因子",FactorCatalog.UNCATEGORISED),catalog.categories);
        // Without any info file the list still works, all uncategorised and searchable by name.
        var bare=FactorCatalog.parse(labels,null,null);assertEquals(2,bare.factors.size());assertEquals(List.of("新しい因子"),names(bare.filter(null,"新しい")));
    }
    @Test public void infoFromAnotherVersionIsNotTrusted()throws Exception{
        String labels="{\"factor.name\":[\"スピード\"]}";
        String info="[{\"sid\":0,\"sort_key\":5,\"names\":[\"スタミナ\",\"すたみな\"],\"tags\":[\"factor_status\"]}]";
        var catalog=FactorCatalog.parse(labels,info,null);
        assertEquals(FactorCatalog.UNCATEGORISED,catalog.factors.get(0).category());
        assertTrue(catalog.filter(null,"すたみな").isEmpty());
    }
    @Test public void hiraganaSearchKeyFoldsToKatakana(){assertEquals(Store.searchKey("スピード"),Store.searchKey("すぴーど"));assertEquals("abc",Store.searchKey("ＡＢＣ"));}

    @Test public void exportedCodeReadsBack()throws Exception{
        JSONObject preset=Store.fresh("書き出しテスト");preset.getJSONArray("entries")
            .put(new JSONObject().put("tier","必須").put("min_star",3).put("factor","スピード"))
            .put(new JSONObject().put("tier","重要").put("min_star",1).put("any_of",new JSONArray().put("芝").put("ダート")));
        String code=ShareCode.encode(preset);
        assertTrue(code.startsWith("UFP1."));assertFalse(code.contains("="));
        assertEquals(preset.toString(),ShareCode.decode(code).toString());
        // Written for the PC check in CLAUDE_CHANGES.md (python preset_share.decode_preset).
        Files.write(Path.of(System.getProperty("java.io.tmpdir"),"factor-export-sample.txt"),code.getBytes(StandardCharsets.US_ASCII));
    }
    @Test public void qrSplitMatchesImport()throws Exception{
        JSONObject preset=Store.fresh("QR");for(int i=0;i<400;i++)preset.getJSONArray("entries").put(new JSONObject().put("tier","必要").put("min_star",1).put("factor","因子"+i+"-"+UUID.randomUUID()));
        String code=ShareCode.encode(preset);var parts=ShareCode.split(code);assertTrue(parts.size()>1);
        Map<String,String> collected=new HashMap<>();String joined=null;for(int i=parts.size()-1;i>=0;i--)joined=ShareCode.accept(parts.get(i),collected);
        assertEquals(code,joined);
    }
    static List<String> names(List<FactorCatalog.Factor> factors){List<String> out=new ArrayList<>();for(var f:factors)out.add(f.name());return out;}
}
