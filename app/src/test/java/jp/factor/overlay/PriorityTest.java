package jp.factor.overlay;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
import java.util.*;

/** The eight rainbow levels, and the four tiers they fall into. */
public class PriorityTest {
    static Map<String,List<Store.Rule>> rules(Object... namePriority){
        Map<String,List<Store.Rule>> rules=new HashMap<>();
        for(int i=0;i<namePriority.length;i+=3)
            rules.computeIfAbsent(Store.normalize((String)namePriority[i]),k->new ArrayList<>())
                 .add(new Store.Rule((Integer)namePriority[i+1],(Integer)namePriority[i+2]));
        return rules;
    }

    @Test public void eachTierCoversTwoLevels(){
        assertEquals(8,Store.LEVELS.length);assertEquals(7,Store.PRIORITY_MAX);assertEquals(2,Store.PER_TIER);
        assertEquals("必須",Store.TIERS[Store.tierOf(0)]);assertEquals("必須",Store.TIERS[Store.tierOf(1)]);
        assertEquals("重要",Store.TIERS[Store.tierOf(2)]);assertEquals("必要",Store.TIERS[Store.tierOf(4)]);
        assertEquals("本育成で取得可能",Store.TIERS[Store.tierOf(7)]);
        for(int i=0;i<Store.TIERS.length;i++)assertEquals(Store.TIERS[i],Store.TIERS[Store.tierOf(Store.priorityOf(Store.TIERS[i]))]);
    }
    @Test public void aPresetFromThePcVersionHasNoNumbers(){
        // Only a tier: the entry takes the first level of that tier, so nothing changes for those presets.
        assertEquals(0,Store.entryPriority(new JSONObject(Map.of("tier","必須"))));
        assertEquals(4,Store.entryPriority(new JSONObject(Map.of("tier","必要"))));
        assertEquals(Store.NONE,Store.entryPriority(new JSONObject(Map.of("tier","なにか"))));
        // A number wins; one out of range falls back to the tier rather than colouring the row wrongly.
        assertEquals(5,Store.entryPriority(new JSONObject(Map.of("tier","必要","priority",5))));
        assertEquals(4,Store.entryPriority(new JSONObject(Map.of("tier","必要","priority",99))));
        assertEquals(4,Store.entryPriority(new JSONObject(Map.of("tier","必要","priority",-1))));
    }
    @Test public void theStrongestMatchingEntryWins(){
        var rules=rules("末脚",5,1,"末脚",1,3,"地固め",3,2);
        assertEquals(5,Store.priority(rules,"末脚",1));      // ★1 では ★3 の登録に届かない
        assertEquals(1,Store.priority(rules,"末脚",3));
        assertEquals(Store.UNMET,Store.priority(rules,"地固め",1));
        assertEquals(Store.NONE,Store.priority(rules,"知らない因子",3));
        assertEquals("必須",Store.tier(rules,"末脚",3));
        assertEquals("条件未達",Store.tier(rules,"地固め",1));
        assertEquals("未設定",Store.tier(rules,"知らない因子",3));
    }
    @Test public void theKindOfPresetIsThePresetsOwnProperty() throws Exception {
        // A plain preset keeps the four tiers; only a detailed one is edited in eight levels.
        assertFalse(Store.detailed(Store.fresh("簡易")));
        assertTrue(Store.detailed(Store.fresh("詳細").put("detailed",true)));
        assertFalse("プリセットが無いときは簡易扱い",Store.detailed((org.json.JSONObject)null));
    }
    @Test public void aBannedEntryIsKeptOffTheScreen() throws Exception {
        // An excluded entry is never framed, whatever else is registered under the same name.
        var banned=new org.json.JSONObject().put("tier","必須").put("ban",true);
        assertEquals(Store.BANNED,Store.entryPriority(banned));
        var rules=rules("末脚",Store.BANNED,1,"末脚",0,1);   // 同じ名前に通常の登録があっても
        assertEquals("除外が優先される",Store.BANNED,Store.priority(rules,"末脚",3));
        assertEquals("除外",Store.tier(rules,"末脚",3));
        assertEquals("枠は出ない",0,FactorFrames.color(Store.BANNED));
    }
    @Test public void coloursRunThroughTheRainbowAndStopAtSeven(){
        assertEquals(8,FactorFrames.RAINBOW.length);
        assertEquals(0xffff3b30,FactorFrames.color(0));               // 赤が最優先
        assertEquals(0xffaf52de,FactorFrames.color(6));               // 紫が最後の色
        assertEquals(0,FactorFrames.color(7));                        // 7 は枠なし
        assertEquals(0,FactorFrames.color(Store.NONE));
        Set<Integer> colours=new HashSet<>();
        for(int i=0;i<Store.PRIORITY_MAX;i++)colours.add(FactorFrames.color(i));
        assertEquals("色が重複していない",7,colours.size());
    }
    @Test public void onlyAFactorShortOfStarsGetsTheGradient(){
        // Enough stars: the plain colour. Too few: the same colour fading into grey across the frame.
        assertFalse(FactorFrames.graded(true));
        assertTrue(FactorFrames.graded(false));
        assertEquals("色そのものは星数で変わらない",FactorFrames.color(0),FactorFrames.color(0));
    }
}
