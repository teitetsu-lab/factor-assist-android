package jp.factor.overlay;

import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** PC factor model preprocessing, independent of Android so recorded frames can be tested. */
final class FactorVision implements AutoCloseable {
    record Blob(double y, int x, int area, int width, int height) {}
    record Layout(int pitch, int starX, List<Integer> ys) {}
    record Reading(String name, int stars, float nameConfidence, float starConfidence, int y) {}
    final OrtEnvironment env=OrtEnvironment.getEnvironment();
    /** One name/star session pair per thread-count option; tuner picks the pair (null with a single option). */
    final OrtSession[] nameSessions, rankSessions;
    final ThreadTuner tuner;
    final JSONArray labels, stars;
    /** requestedXnnpack is what the caller asked for; xnnpack is whether the XNNPACK provider actually loaded. */
    final boolean requestedXnnpack, xnnpack;
    final InferenceCache nameCache=new InferenceCache(512), rankCache=new InferenceCache(512);
    // Written only by the recognition thread; read for the menu display, where a stale value is harmless.
    volatile long inferred, reused, rows, retriedRows;
    void resetCounters(){inferred=0;reused=0;rows=0;retriedRows=0;}
    // Model time of the current read(), for the thread tuner. Recognition thread only.
    private long modelNanos;private int modelCrops;
    volatile double modelMsAvg,prepMsAvg;

    FactorVision(byte[] nameModel, byte[] starModel, String labelJson) throws Exception {this(nameModel,starModel,labelJson,false);}
    FactorVision(byte[] nameModel, byte[] starModel, String labelJson, boolean useXnnpack) throws Exception {
        JSONObject data=new JSONObject(labelJson); labels=data.getJSONArray("factor.name");stars=data.getJSONArray("factor_rank.name");
        OrtSession[] pair=null;
        if(useXnnpack)try{pair=sessions(nameModel,starModel,true,1);}catch(Exception|LinkageError e){pair=null;}
        xnnpack=pair!=null;requestedXnnpack=useXnnpack;
        if(pair!=null){nameSessions=new OrtSession[]{pair[0]};rankSessions=new OrtSession[]{pair[1]};tuner=null;return;}
        // The standard CPU path gets one session pair per thread count, 1 up to the CPU core count, created on first
        // use, and picks the fastest as it runs. The first pair is built now so a broken model still fails here.
        int[] options=threadOptions(Runtime.getRuntime().availableProcessors());
        nameSessions=new OrtSession[options.length];rankSessions=new OrtSession[options.length];
        this.nameModel=nameModel;this.starModel=starModel;
        pair=sessions(nameModel,starModel,false,options[0]);nameSessions[0]=pair[0];rankSessions[0]=pair[1];
        tuner=new ThreadTuner(options);
    }
    static final int MAX_THREADS=12;
    /** 1, 2, … up to the core count (at most MAX_THREADS): more threads than cores only adds contention. */
    static int[] threadOptions(int cores){int n=Math.max(1,Math.min(MAX_THREADS,cores));int[] options=new int[n];for(int i=0;i<n;i++)options[i]=i+1;return options;}
    private byte[] nameModel,starModel;
    /** Sessions for option i, created the first time the tuner tries it. Recognition thread only. */
    private void ensureSessions(int i) throws OrtException {
        if(nameSessions[i]!=null)return;
        OrtSession[] pair=sessions(nameModel,starModel,false,tuner.threads[i]);nameSessions[i]=pair[0];rankSessions[i]=pair[1];
    }
    OrtSession[] sessions(byte[] nameModel,byte[] starModel,boolean useXnnpack,int threads) throws OrtException {
        try(var options=new OrtSession.SessionOptions()) {
            // With XNNPACK the provider owns the worker threads; ORT's own pool stays at one and does not spin.
            if(useXnnpack){options.addXnnpack(Map.of("intra_op_num_threads","4"));options.setIntraOpNumThreads(1);options.addConfigEntry("session.intra_op.allow_spinning","0");}
            else options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            OrtSession first=env.createSession(nameModel,options);
            try { return new OrtSession[]{first,env.createSession(starModel,options)}; }
            catch(OrtException e){first.close();throw e;}
        }
    }
    static boolean gold(int color) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        if(!(r>190&&g>150&&b<150&&r>b+60&&g>b+40&&Math.abs(r-g)<90))return false;
        return !(Math.abs(r-255)<40&&Math.abs(g-150)<40&&b<40);
    }
    static int median(List<Integer> numbers) {var sorted=new ArrayList<>(numbers);Collections.sort(sorted);int n=sorted.size();return n%2==1?sorted.get(n/2):(sorted.get(n/2-1)+sorted.get(n/2))/2;}

    // Reused per thread: detect runs every cycle, and a fresh half-frame mask and queue per call only feed the GC.
    private static final ThreadLocal<boolean[]> maskScratch=new ThreadLocal<>();
    private static final ThreadLocal<int[]> queueScratch=new ThreadLocal<>();
    static Layout detect(int[] pixels,int width,int height) {
        int w=(width+1)/2,h=(height+1)/2;
        boolean[] mask=maskScratch.get();int[] queue=queueScratch.get();
        if(mask==null||mask.length<w*h){mask=new boolean[w*h];queue=new int[w*h];maskScratch.set(mask);queueScratch.set(queue);}
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)mask[y*w+x]=gold(pixels[(y*2)*width+x*2]);
        List<Blob> blobs=new ArrayList<>();List<Integer> areas=new ArrayList<>();
        for(int start=0;start<w*h;start++) {
            if(!mask[start])continue;
            int read=0,end=1,minX=start%w,maxX=minX,minY=start/w,maxY=minY;queue[0]=start;mask[start]=false;
            while(read<end){int p=queue[read++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                if(x>0&&mask[p-1]){mask[p-1]=false;queue[end++]=p-1;}if(x+1<w&&mask[p+1]){mask[p+1]=false;queue[end++]=p+1;}
                if(y>0&&mask[p-w]){mask[p-w]=false;queue[end++]=p-w;}if(y+1<h&&mask[p+w]){mask[p+w]=false;queue[end++]=p+w;}
            }
            blobs.add(new Blob((minY+maxY+1)/2.0,minX,end,maxX-minX+1,maxY-minY+1));if(end>2.5)areas.add(end);
        }
        if(areas.isEmpty())return null;double area=median(areas);
        blobs.removeIf(b->b.area<area*.3||b.area>area*4||b.height/(double)b.width<=.5||b.height/(double)b.width>=2.2);
        blobs.sort(Comparator.comparingDouble(Blob::y));if(blobs.size()<3)return null;
        List<List<Blob>> groups=new ArrayList<>();
        for(Blob b:blobs){if(groups.isEmpty()||b.y-groups.get(groups.size()-1).get(groups.get(groups.size()-1).size()-1).y>h*.012)groups.add(new ArrayList<>());groups.get(groups.size()-1).add(b);}
        List<Integer> ys=new ArrayList<>(),xs=new ArrayList<>(),diff=new ArrayList<>();
        for(var group:groups){List<Integer> centers=new ArrayList<>();int left=Integer.MAX_VALUE;for(Blob b:group){centers.add((int)(b.y*2));left=Math.min(left,b.x*2);}ys.add(median(centers));xs.add(left);}
        if(ys.size()<3)return null;for(int i=1;i<ys.size();i++)diff.add(ys.get(i)-ys.get(i-1));int pitch=median(diff);if(pitch<12)return null;
        int bestStart=0,bestSize=0;
        for(int i=0;i<ys.size();){int j=i;while(j+1<ys.size()&&Math.abs(ys.get(j+1)-ys.get(j)-pitch)<pitch*.35)j++;if(j-i+1>bestSize){bestStart=i;bestSize=j-i+1;}i=j+1;}
        if(bestSize<3)return null;
        return new Layout(pitch,median(xs.subList(bestStart,bestStart+bestSize)),new ArrayList<>(ys.subList(bestStart,bestStart+bestSize)));
    }

    // Pillow-compatible bilinear downsampling uses a widened triangle filter.
    static byte[] crop(int[] pixels,int width,int height,int left,int top,int right,int bottom,int outWidth,boolean debadge) {
        if(left<0||top<0||right>width||bottom>height||right<=left||bottom<=top)return null;
        int cw=right-left,ch=bottom-top;int[] local=new int[cw*ch];
        for(int y=0;y<ch;y++)System.arraycopy(pixels,(top+y)*width+left,local,y*cw,cw);
        if(debadge){int first=cw,last=-1;for(int x=0;x<cw;x++){int red=0;for(int y=0;y<ch;y++){int c=local[y*cw+x];if(((c>>16)&255)>235&&((c>>8)&255)<100&&(c&255)<150)red++;}if(red>2){first=Math.min(first,x);last=x;}}
            if(last-first>=2){int[] components=new int[3];for(int c=0;c<3;c++){List<Integer> values=new ArrayList<>();for(int y=0;y<ch;y++)for(int x=Math.max(0,cw-12);x<cw;x++)values.add((local[y*cw+x]>>(c*8))&255);components[c]=median(values);}int fill=components[0]|components[1]<<8|components[2]<<16;for(int y=0;y<ch;y++)for(int x=Math.max(0,first-3);x<Math.min(cw,last+4);x++)local[y*cw+x]=fill;}}
        byte[] output=new byte[outWidth*16*3];int[] horizontal=new int[ch*outWidth*3];double sx=cw/(double)outWidth,sy=ch/16.0,fx=Math.max(1,sx),fy=Math.max(1,sy);
        // Per-channel sums in locals, same arithmetic order as before, without an array per output pixel.
        for(int y=0;y<ch;y++)for(int x=0;x<outWidth;x++){double center=(x+.5)*sx;int a=Math.max(0,(int)(center-fx+.5)),b=Math.min(cw,(int)(center+fx+.5));double sum=0,c0=0,c1=0,c2=0;for(int k=a;k<b;k++){double weight=Math.max(0,1-Math.abs((k+.5-center)/fx));sum+=weight;int p=local[y*cw+k];c0+=weight*(p&255);c1+=weight*((p>>8)&255);c2+=weight*((p>>16)&255);}int o=(y*outWidth+x)*3;horizontal[o]=(int)Math.round(c0/sum);horizontal[o+1]=(int)Math.round(c1/sum);horizontal[o+2]=(int)Math.round(c2/sum);}
        for(int y=0;y<16;y++)for(int x=0;x<outWidth;x++){double center=(y+.5)*sy;int a=Math.max(0,(int)(center-fy+.5)),b=Math.min(ch,(int)(center+fy+.5));double sum=0,c0=0,c1=0,c2=0;for(int k=a;k<b;k++){double weight=Math.max(0,1-Math.abs((k+.5-center)/fy));sum+=weight;int i=(k*outWidth+x)*3;c0+=weight*horizontal[i];c1+=weight*horizontal[i+1];c2+=weight*horizontal[i+2];}int o=(y*outWidth+x)*3;output[o]=(byte)Math.round(c0/sum);output[o+1]=(byte)Math.round(c1/sum);output[o+2]=(byte)Math.round(c2/sum);}
        return output;
    }
    record Predictions(long[] indices,float[] confidence) {}
    /** predict() for the crops the cache has not seen; identical crops reuse the stored output. */
    Predictions infer(List<byte[]> crops,int width,OrtSession session,InferenceCache cache) throws Exception {
        long[] indices=new long[crops.size()];float[] confidence=new float[crops.size()];
        List<byte[]> missing=new ArrayList<>();List<Integer> at=new ArrayList<>();
        for(int i=0;i<crops.size();i++){var hit=cache.get(crops.get(i));if(hit!=null){indices[i]=hit.index();confidence[i]=hit.confidence();}else{missing.add(crops.get(i));at.add(i);}}
        reused+=crops.size()-missing.size();
        if(!missing.isEmpty()){long started=System.nanoTime();Predictions p=predict(missing,width,session);modelNanos+=System.nanoTime()-started;modelCrops+=missing.size();inferred+=missing.size();
            for(int j=0;j<missing.size();j++){indices[at.get(j)]=p.indices[j];confidence[at.get(j)]=p.confidence[j];cache.put(missing.get(j),p.indices[j],p.confidence[j]);}}
        return new Predictions(indices,confidence);
    }
    Predictions predict(List<byte[]> crops,int width,OrtSession session) throws Exception {
        ByteBuffer buffer=ByteBuffer.allocateDirect(crops.size()*16*width*3).order(ByteOrder.nativeOrder());for(byte[] b:crops)buffer.put(b);buffer.flip();
        try(var input=OnnxTensor.createTensor(env,buffer,new long[]{crops.size(),16,width,3},OnnxJavaType.UINT8);var result=session.run(Collections.singletonMap("input",input))){return new Predictions((long[])result.get("index").orElseThrow().getValue(),(float[])result.get("confidence").orElseThrow().getValue());}
    }
    /** Confidence a row needs before CaptureService accepts it; rows below get the ±1 offset retries. */
    static final float NAME_MIN=.97f, STAR_MIN=.94f;
    static int[] nameBox(int s,int p,int y,int k){int offset=(int)Math.rint(k*p/68.0);return new int[]{Math.max(0,(int)(s-6.18*p)),(int)(y+offset-.279*p),(int)(s-2.15*p),(int)(y+offset+.191*p)};}
    static int[] rankBox(int s,int p,int y,int k){int offset=(int)Math.rint(k*p/68.0);return new int[]{(int)(s-.12*p),(int)(y+offset-.25*p),(int)(s+1.24*p),(int)(y+offset+.22*p)};}
    static boolean inside(int[] b,int width,int height){return b[0]>=0&&b[1]>=0&&b[2]<=width&&b[3]<=height&&b[2]>b[0]&&b[3]>b[1];}
    byte[] nameCrop(int[] pixels,int width,int height,int[] b){return crop(pixels,width,height,b[0],b[1],b[2],b[3],168,true);}
    byte[] rankCrop(int[] pixels,int width,int height,int[] b){return crop(pixels,width,height,b[0],b[1],b[2],b[3],52,false);}

    /**
     * Same readings as running all three vertical offsets per row, but the ±1 offsets are only inferred
     * for rows whose centre crop falls short of the acceptance thresholds. On a settled list that is
     * roughly a third of the crops and inferences.
     */
    List<Reading> read(int[] pixels,int width,int height,Layout layout) throws Exception {
        long readStarted=System.nanoTime();modelNanos=0;modelCrops=0;
        List<Reading> result=new ArrayList<>();if(layout==null)return result;
        int p=layout.pitch,s=layout.starX;List<Integer> centers=new ArrayList<>();List<byte[]> nc=new ArrayList<>(),sc=new ArrayList<>();
        // A row is kept only if all three offsets fit on screen, exactly as before.
        for(int y:layout.ys){boolean fits=true;for(int k=-1;k<=1&&fits;k++)fits=inside(nameBox(s,p,y,k),width,height)&&inside(rankBox(s,p,y,k),width,height);
            if(!fits)continue;centers.add(y);nc.add(nameCrop(pixels,width,height,nameBox(s,p,y,0)));sc.add(rankCrop(pixels,width,height,rankBox(s,p,y,0)));}
        if(centers.isEmpty())return result;
        rows+=centers.size();
        int option=tuner==null?0:tuner.pick();if(tuner!=null)ensureSessions(option);OrtSession names=nameSessions[option],ranks=rankSessions[option];
        Predictions nr=infer(nc,168,names,nameCache),sr=infer(sc,52,ranks,rankCache);
        // Candidates per row in the original offset order (-1, 0, +1) so ties resolve the same way.
        long[][] nIdx=new long[centers.size()][3],rIdx=new long[centers.size()][3];float[][] nConf=new float[centers.size()][3],rConf=new float[centers.size()][3];
        List<Integer> retry=new ArrayList<>();
        for(int i=0;i<centers.size();i++){
            Arrays.fill(nConf[i],-1);Arrays.fill(rConf[i],-1);
            nIdx[i][1]=nr.indices[i];nConf[i][1]=nr.confidence[i];rIdx[i][1]=sr.indices[i];rConf[i][1]=sr.confidence[i];
            if(nConf[i][1]<NAME_MIN||rConf[i][1]<STAR_MIN)retry.add(i);
        }
        if(!retry.isEmpty()){retriedRows+=retry.size();
            List<byte[]> rn=new ArrayList<>(),rs=new ArrayList<>();
            for(int i:retry)for(int k=-1;k<=1;k+=2){int y=centers.get(i);rn.add(nameCrop(pixels,width,height,nameBox(s,p,y,k)));rs.add(rankCrop(pixels,width,height,rankBox(s,p,y,k)));}
            Predictions rnr=infer(rn,168,names,nameCache),rsr=infer(rs,52,ranks,rankCache);
            for(int j=0;j<retry.size();j++){int i=retry.get(j);
                for(int side=0;side<2;side++){int from=j*2+side,slot=side==0?0:2;
                    nIdx[i][slot]=rnr.indices[from];nConf[i][slot]=rnr.confidence[from];rIdx[i][slot]=rsr.indices[from];rConf[i][slot]=rsr.confidence[from];}}
        }
        if(tuner!=null&&modelCrops>0)tuner.record(option,modelNanos/1e6/modelCrops);
        // Split of the 因子推論 stage: the model itself vs cropping and resizing in Java.
        double modelMs=modelNanos/1e6,prepMs=(System.nanoTime()-readStarted)/1e6-modelMs;
        modelMsAvg=modelMsAvg==0?modelMs:modelMsAvg*.9+modelMs*.1;prepMsAvg=prepMsAvg==0?prepMs:prepMsAvg*.9+prepMs*.1;
        for(int i=0;i<centers.size();i++){int n=-1,r=-1;for(int j=0;j<3;j++){if(nConf[i][j]<0)continue;if(n<0||nConf[i][j]>nConf[i][n])n=j;if(r<0||rConf[i][j]>rConf[i][r])r=j;}
            result.add(new Reading(labels.getString((int)nIdx[i][n]),Integer.parseInt(stars.getString((int)rIdx[i][r])),nConf[i][n],rConf[i][r],centers.get(i)));}
        return result;
    }
    void closeAll(){for(OrtSession[] group:new OrtSession[][]{nameSessions,rankSessions})for(OrtSession session:group)if(session!=null)try{session.close();}catch(Exception ignored){}}
    @Override public void close(){closeAll();}
    String splitText(){return String.format(Locale.JAPAN,"因子推論の内訳：前処理 %.1fms / モデル %.1fms",prepMsAvg,modelMsAvg);}
    String threadText(){return xnnpack?"推論スレッド：XNNPACK が管理":tuner.text();}
}
