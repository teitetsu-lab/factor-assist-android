package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Frames moved onto the rows of a new frame before those rows are read. */
public class FollowFramesTest {
    @Test public void eachRowTakesTheFrameThatWasAMoveAboveIt(){
        var last=new FactorFrames.Snapshot(1080,2340,List.of(
            new FactorFrames.Mark(1000,0,true,"a",null),
            new FactorFrames.Mark(1184,3,true,"b",null)),72,200);
        // The list moved 30px down: the two rows now sit at 1030 and 1214, and a new one came in at 846.
        var moved=FactorFrames.follow(last,List.of(846,1030,1214),null,184,1080,2340,72,200);
        assertNotNull(moved);
        assertEquals(2,moved.marks().size());
        assertEquals(1030,moved.marks().get(0).y());assertEquals("a",moved.marks().get(0).label());assertEquals(0,moved.marks().get(0).priority());
        assertEquals(1214,moved.marks().get(1).y());assertEquals("b",moved.marks().get(1).label());
    }
    @Test public void nothingDrawnNothingMoved(){
        assertNull(FactorFrames.follow(null,List.of(1000),null,184,1080,2340,0,0));
        assertNull(FactorFrames.follow(new FactorFrames.Snapshot(1080,2340,List.of()),List.of(1000),null,184,1080,2340,0,0));
    }
}
