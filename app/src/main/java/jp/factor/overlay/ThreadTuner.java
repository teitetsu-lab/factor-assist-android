package jp.factor.overlay;

import java.util.Locale;

/**
 * Picks the inference thread count that is currently fastest. ONNX Runtime fixes the thread count when a
 * session is created, so FactorVision keeps one session per option and this chooses between them.
 *
 * The best option depends on what the game is doing and on heat (on the S25 the same inference took 46ms
 * early and ~140ms later while scrolling), so it keeps measuring: every option is tried once, then the
 * fastest is used and every EXPLORE_EVERY-th call goes to one of the others to refresh its time.
 */
final class ThreadTuner {
    static final int EXPLORE_EVERY=20;
    static final double SMOOTHING=.2;
    final int[] threads;
    private final double[] msPerCrop;
    private int calls;
    /** Index forced by the user in 表示設定, or -1 for automatic. */
    private int fixed=-1;
    /** threadCount 0 = automatic; otherwise the option with that many threads, if offered. */
    synchronized void setThreads(int threadCount){fixed=-1;for(int i=0;i<threads.length;i++)if(threads[i]==threadCount)fixed=i;}

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
    synchronized int best(){int best=0;for(int i=1;i<msPerCrop.length;i++)if(msPerCrop[i]>0&&(msPerCrop[best]==0||msPerCrop[i]<msPerCrop[best]))best=i;return best;}
    synchronized String text() {
        StringBuilder s=new StringBuilder("推論スレッド：").append(fixed>=0?"固定 "+threads[fixed]:"自動 → "+threads[best()]+" を使用").append("\n");
        for(int i=0;i<threads.length;i++)s.append(i==0?"":i%4==0?"\n":" / ").append(threads[i]).append(msPerCrop[i]==0?": 未計測":String.format(Locale.JAPAN,": %.2fms/枚",msPerCrop[i]));
        return s.toString();
    }
}
