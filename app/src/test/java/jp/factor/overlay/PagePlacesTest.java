package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Factors end up in the order they appear in the game, whichever way the list was scrolled. */
public class PagePlacesTest {
    static List<PagePlaces.Row> rows(Object... nameY){
        List<PagePlaces.Row> list=new ArrayList<>();
        for(int i=0;i<nameY.length;i+=2)list.add(new PagePlaces.Row((String)nameY[i],(Integer)nameY[i+1]));
        return list;
    }
    static Set<String> names(String... n){return new LinkedHashSet<>(Arrays.asList(n));}

    @Test public void scrollingUpPutsTheNewRowsOnTop(){
        // The page holds A..G. First DEFG is on screen, then the list is scrolled up to BCDE.
        PagePlaces places=new PagePlaces();
        places.record("元因子",rows("D",100,"E",220,"F",340,"G",460));
        places.record("元因子",rows("B",100,"C",220,"D",340,"E",460));
        assertEquals(List.of("B","C","D","E","F","G"),places.ordered("元因子",names("G","F","E","D","C","B")));
    }
    @Test public void scrollingDownKeepsAppendingBelow(){
        PagePlaces places=new PagePlaces();
        places.record("元因子",rows("A",100,"B",220,"C",340));
        places.record("元因子",rows("B",100,"C",220,"D",340));
        places.record("元因子",rows("C",100,"D",220,"E",340));
        assertEquals(List.of("A","B","C","D","E"),places.ordered("元因子",names("E","D","C","B","A")));
    }
    @Test public void aJumpWithNoSharedRowKeepsTheLastScrollDistance(){
        PagePlaces places=new PagePlaces();
        places.record("元因子",rows("A",100,"B",220));
        places.record("元因子",rows("B",100,"C",220));   // scrolled by 120
        places.record("元因子",rows("X",100,"Y",220));   // nothing in common: placed with the same distance
        List<String> order=places.ordered("元因子",names("Y","X","C","B","A"));
        assertEquals("A",order.get(0));assertTrue(order.indexOf("X")<order.indexOf("Y"));
    }
    @Test public void pagesAreIndependentAndNamesCanBeRemoved(){
        PagePlaces places=new PagePlaces();
        places.record("元因子",rows("A",100,"B",220));
        places.record("再獲得因子1",rows("B",100,"A",220));
        assertEquals(List.of("A","B"),places.ordered("元因子",names("B","A")));
        assertEquals(List.of("B","A"),places.ordered("再獲得因子1",names("A","B")));
        assertEquals(Map.of("A",100,"B",220),places.of("元因子"));
        places.remove("元因子","A");
        assertEquals(Map.of("B",220),places.of("元因子"));
        places.clear();
        assertTrue(places.of("再獲得因子1").isEmpty());
    }
    @Test public void unplacedNamesGoLast(){
        PagePlaces places=new PagePlaces();places.record("元因子",rows("A",100));
        assertEquals(List.of("A","Z"),places.ordered("元因子",names("Z","A")));
    }
}
