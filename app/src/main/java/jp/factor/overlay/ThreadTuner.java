package jp.factor.overlay;

import java.util.Locale;

/**
 * Picks the inference thread count that currently costs the least CPU (see best). ONNX Runtime fixes the thread count when a
 * session is created, so FactorVision keeps one session per option and this chooses between them.
 *
 * The best option depends on what the game is doing and on heat (on the S25 the same inference took 46ms
 * early and ~140ms later while scrolling), so it keeps measuring: every option is tried once, then the
 * cheapest is used and every EXPLORE_EVERY-th call goes to one of the others to refresh its time.
 */
final class ThreadTuner {
    static final int EXPLORE_EVERY=20;
    static final double SMOOTHING=.2;
    final int[] threads;
    private final double[] msPerCrop;
    private int calls;
    /** Index forced by the user in 表示設定, or -1 for automatic. */
    private int fixed=-1;
    /** Automatic by speed alone (最速重視) instead of by CPU used (発熱抑制重視, the default). */
    private boolean fastest;
    /** Setting value for 自動（最速重視）. */
    static final int AUTO_FASTEST=-1;
    /**
     * threadCount 0 = automatic, least CPU; AUTO_FASTEST = automatic, fastest; otherwise the option with that
     * many threads, if offered (an unknown count is automatic, least CPU).
     */
    synchronized void setThreads(int threadCount){fixed=-1;fastest=threadCount==AUTO_FASTEST;for(int i=0;i<threads.length;i++)if(threads[i]==threadCount)fixed=i;}

    ThreadTuner(int... threads){this.threads=threads.clone();msPerCrop=new double[threads.length];}

    /** Index of the option to use for the next inference. */
    synchronized int pick() {
        if(fixed>=0)return fixed;
        for(int i=0;i<msPerCrop.length;i++)if(msPerCrop[i]==0)return i;
        int best=best();calls++;
        if(threads.length>1&&calls%EXPLORE_EVERY==0){int others=threads.length-1;return (best+1+(calls/EXPLORE_EVERY)%others)%threads.length;}
        return best;
    }
    /** Time per crop measured with option i; ignored when nothing was inferred. */
    synchronized void record(int i,double ms){if(ms<=0)return;msPerCrop[i]=msPerCrop[i]==0?ms:msPerCrop[i]*(1-SMOOTHING)+ms*SMOOTHING;}
    /**
     * The option that costs the least CPU for the work: its time per crop times its threads. More threads finish
     * sooner but keep that many cores busy, and at the read rates used (10 a second leaves 100ms a read) the
     * sooner is worth nothing while the cores are heat: on the S25 one thread took 1.8ms a crop and four 0.9ms,
     * half the time for twice the CPU. On a tie the fewer threads win.
     */
    synchronized int best(){
        int best=-1;
        for(int i=0;i<msPerCrop.length;i++){
            if(msPerCrop[i]<=0)continue;
            double cost=fastest?msPerCrop[i]:msPerCrop[i]*threads[i],bestCost=best<0?0:fastest?msPerCrop[best]:msPerCrop[best]*threads[best];
            if(best<0||cost<bestCost)best=i;
        }
        return best<0?0:best;
    }
    synchronized String text() {
        StringBuilder s=new StringBuilder("推論スレッド：").append(fixed>=0?"固定 "+threads[fixed]:(fastest?"自動（最速重視）→ ":"自動（発熱抑制重視）→ ")+threads[best()]+" を使用").append("\n");
        for(int i=0;i<threads.length;i++)s.append(i==0?"":i%4==0?"\n":" / ").append(threads[i]).append(msPerCrop[i]==0?": 未計測":String.format(Locale.JAPAN,": %.2fms/枚",msPerCrop[i]));
        return s.toString();
    }
}
