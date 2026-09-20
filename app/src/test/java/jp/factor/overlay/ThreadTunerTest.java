package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ThreadTunerTest {
    @Test public void optionsFollowTheCoreCount(){
        assertArrayEquals(new int[]{1,2,3,4,5,6,7,8},FactorVision.threadOptions(8));   // Galaxy S25
        assertArrayEquals(new int[]{1},FactorVision.threadOptions(0));
        assertEquals(FactorVision.MAX_THREADS,FactorVision.threadOptions(64).length);
    }
    @Test public void triesEveryOptionThenSettlesOnTheFastest(){
        ThreadTuner tuner=new ThreadTuner(1,2,3,4);double[] cost={9,4,2,5};
        for(int i=0;i<4;i++){int pick=tuner.pick();assertEquals(i,pick);tuner.record(pick,cost[pick]);}
        Map<Integer,Integer> used=new HashMap<>();
        for(int call=0;call<200;call++){int pick=tuner.pick();used.merge(pick,1,Integer::sum);tuner.record(pick,cost[pick]);}
        assertEquals(2,tuner.best());
        assertTrue("mostly the fastest: "+used,used.get(2)>=180);
        // Every other option is still re-measured now and then.
        for(int i:new int[]{0,1,3})assertTrue("option "+i+" re-measured: "+used,used.getOrDefault(i,0)>0);
    }
    @Test public void followsAChangeInSpeed(){
        ThreadTuner tuner=new ThreadTuner(1,2);double[] cost={3,5};
        for(int call=0;call<100;call++){int pick=tuner.pick();tuner.record(pick,cost[pick]);}
        assertEquals(0,tuner.best());
        cost[0]=10;   // e.g. the game now competes for that core
        for(int call=0;call<400;call++){int pick=tuner.pick();tuner.record(pick,cost[pick]);}
        assertEquals(1,tuner.best());
    }
    @Test public void callsWithNothingInferredDoNotCount(){
        ThreadTuner tuner=new ThreadTuner(1,2);tuner.record(0,0);assertEquals(0,tuner.pick());
    }
    @Test public void aChosenThreadCountIsKeptUntilSetBackToAuto(){
        ThreadTuner tuner=new ThreadTuner(1,2,3,4);double[] cost={9,4,2,5};
        tuner.setThreads(4);for(int call=0;call<50;call++){int pick=tuner.pick();assertEquals(3,pick);tuner.record(pick,cost[pick]);}
        tuner.setThreads(0);for(int call=0;call<100;call++){int pick=tuner.pick();tuner.record(pick,cost[pick]);}
        assertEquals(2,tuner.best());
        tuner.setThreads(99);assertNotEquals(-1,tuner.pick());   // an unknown count falls back to automatic
    }
}
