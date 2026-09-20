package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;

public class InferenceCacheTest {
    @Test public void hitsOnlyOnIdenticalContents(){
        InferenceCache cache=new InferenceCache(4);
        byte[] crop={1,2,3,4};cache.put(crop,42,.99f);
        var hit=cache.get(new byte[]{1,2,3,4});assertNotNull(hit);assertEquals(42,hit.index());assertEquals(.99f,hit.confidence(),0);
        assertNull(cache.get(new byte[]{1,2,3,5}));
        assertNull(cache.get(new byte[]{1,2,3}));
    }
    @Test public void evictsLeastRecentlyUsed(){
        InferenceCache cache=new InferenceCache(2);
        cache.put(new byte[]{1},1,1);cache.put(new byte[]{2},2,1);
        assertNotNull(cache.get(new byte[]{1}));   // 1 is now the most recent
        cache.put(new byte[]{3},3,1);
        assertEquals(2,cache.size());assertNull(cache.get(new byte[]{2}));assertNotNull(cache.get(new byte[]{1}));assertNotNull(cache.get(new byte[]{3}));
    }
}
