package jp.factor.overlay;
import java.util.*;

final class CycleStats {
    record Sample(long end,long duration) {}
    private final Deque<Sample> samples=new ArrayDeque<>();
    private long started;
    synchronized void reset(long now){samples.clear();started=now;}
    synchronized void add(long now,long duration){samples.addLast(new Sample(now,duration));trim(now);}
    private void trim(long now){while(!samples.isEmpty()&&samples.peekFirst().end<now-60_000_000_000L)samples.removeFirst();}
    synchronized double rate(long now,int seconds){trim(now);long start=Math.max(started,now-seconds*1_000_000_000L);long count=samples.stream().filter(s->s.end>=start).count();return count/Math.max(1.0,(now-start)/1e9);}
    synchronized String text(long now){
        trim(now);double ms=samples.stream().mapToLong(Sample::duration).average().orElse(0)/1e6;
        StringBuilder out=new StringBuilder(String.format(Locale.JAPAN,"実測（直近5秒）：%.1f 回/秒\n平均（直近60秒）：%.1f 回/秒\n平均処理時間：%.0f ms",rate(now,5),rate(now,60),ms));
        for(int age=10;age<=60;age+=10){long to=now-(age-10)*1_000_000_000L,from=Math.max(started,now-age*1_000_000_000L);if(to<=started)break;long count=samples.stream().filter(s->s.end>=from&&s.end<to).count();out.append(String.format(Locale.JAPAN,"\n%d〜%d秒前：%.1f 回/秒",age-10,age,count/Math.max(1.0,(to-from)/1e9)));}
        return out.toString();
    }
}
