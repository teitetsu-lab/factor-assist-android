package jp.factor.overlay;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class CaptureResultsTest {
    @Test public void countsIncludeUnconfiguredFactors(){
        var rules=Map.of("末脚",java.util.List.of(new Store.Rule(Store.priorityOf("必須"),1)),"地固め",java.util.List.of(new Store.Rule(Store.priorityOf("重要"),1)),"集中力",java.util.List.of(new Store.Rule(Store.priorityOf("必要"),1)));
        var factors=Map.of("末脚",2,"地固め",1,"集中力",3,"右回り",1,"左回り",2);
        // The letters carry marks for their frame colours; without them the line reads 必1 重1 要1 本0 未2.
        assertEquals("必1 重1 要1 本0 未2",CaptureService.countSummary(factors,rules).replaceAll(CaptureService.MARK+".",""));
    }
    @Test public void aDetailedPresetCountsEachLevelByItsNumber(){
        var rules=Map.of("末脚",java.util.List.of(new Store.Rule(0,1)),"地固め",java.util.List.of(new Store.Rule(0,1)),"集中力",java.util.List.of(new Store.Rule(5,1)));
        var factors=Map.of("末脚",2,"地固め",1,"集中力",3,"右回り",1);
        String line=CaptureService.countSummary(factors,rules,true);
        assertEquals("0:2 5:1 未1",line.replaceAll(CaptureService.MARK+".",""));
        assertTrue("番号に枠の色の印",line.startsWith(CaptureService.MARK+"00:"));
    }
    @Test public void resetLeavesNoPlaceholderPage(){
        synchronized(CaptureService.class){
            CaptureService.results.put("元因子",new LinkedHashMap<>(Map.of("末脚",2)));
            long generation=CaptureService.resultGeneration;
            CaptureService.resetResults();
            assertTrue(CaptureService.snapshot().isEmpty());
            assertEquals(generation+1,CaptureService.resultGeneration);
        }
    }
    @Test public void reacquiredPageDoesNotCreateOriginalPage(){
        synchronized(CaptureService.class){
            CaptureService.resetResults();
            try{
                CaptureService.results.put("再獲得因子2",new LinkedHashMap<>(Map.of("末脚",2)));
                assertEquals(1,CaptureService.snapshot().size());
                assertFalse(CaptureService.snapshot().containsKey("元因子"));
            }finally{CaptureService.resetResults();}
        }
    }
    @Test public void titleSurvivesOcrMisreadsOfIn(){
        // Readings logged from ML Kit on the S25.
        assertEquals("再獲得因子4",CaptureService.titleLabel("再獲得図子4"));
        assertEquals("再獲得因子3",CaptureService.titleLabel("再獲得圏子3"));
        assertEquals("再獲得因子2",CaptureService.titleLabel("再獲得図子2"));
        assertEquals("再獲得因子1",CaptureService.titleLabel("再獲得因子1"));
        assertEquals("再獲得因子3",CaptureService.titleLabel("再獲得因子 ３"));
        assertEquals("元因子",CaptureService.titleLabel("元困子"));
        assertEquals("元因子",CaptureService.titleLabel("元因子"));
        assertEquals("",CaptureService.titleLabel(""));
        assertEquals("",CaptureService.titleLabel("因子選択"));
    }
}
