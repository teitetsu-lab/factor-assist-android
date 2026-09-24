package jp.factor.overlay;

import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;

/**
 * Runs the models of one screen - a factor's name and stars - the way that turned out fastest on
 * the phone, shared by both lists:
 *   - identical crops reuse the stored output (InferenceCache), so a still screen costs almost no inference;
 *   - one session per thread count, 1 up to the core count, created on first use, and ThreadTuner keeps using the
 *     fastest (it moves with what the game is doing and with heat); or XNNPACK, when asked for and available;
 *   - each read is timed, split into the model itself and the cropping and resizing around it.
 *
 * A read is begin(), any number of infer() calls, then end(). Recognition thread only, except the figures read
 * for the menu display, where a stale value is harmless.
 */
final class ModelRunner implements AutoCloseable {
    record Predictions(long[] indices,float[] confidence) {}

    final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final byte[][] models;
    /** The crop width each model takes; every model takes 16 rows of 3 colours. */
    private final int[] widths;
    private final InferenceCache[] caches;
    /** sessions[option][model]; an option's sessions are made the first time it is picked. */
    private final OrtSession[][] sessions;
    /** Null with XNNPACK, which manages its own threads. */
    final ThreadTuner tuner;
    /** requestedXnnpack is what the caller asked for; xnnpack is whether the XNNPACK provider actually loaded. */
    final boolean requestedXnnpack, xnnpack;
    volatile long inferred, reused;
    volatile double modelMsAvg, prepMsAvg;
    private int option;private long readStarted,modelNanos;private int modelCrops;

    static final int MAX_THREADS=12;
    /** 1, 2, … up to the core count (at most MAX_THREADS): more threads than cores only adds contention. */
    static int[] threadOptions(int cores){int n=Math.max(1,Math.min(MAX_THREADS,cores));int[] options=new int[n];for(int i=0;i<n;i++)options[i]=i+1;return options;}

    /** widths[i] is the crop width models[i] takes. */
    ModelRunner(boolean useXnnpack,int[] widths,byte[]... models) throws OrtException {
        this.models=models;this.widths=widths.clone();
        caches=new InferenceCache[models.length];for(int i=0;i<models.length;i++)caches[i]=new InferenceCache(512);
        OrtSession[] withXnnpack=null;
        if(useXnnpack)try{withXnnpack=sessions(true,1);}catch(Exception|LinkageError e){withXnnpack=null;}
        xnnpack=withXnnpack!=null;requestedXnnpack=useXnnpack;
        if(xnnpack){sessions=new OrtSession[][]{withXnnpack};tuner=null;return;}
        int[] options=threadOptions(Runtime.getRuntime().availableProcessors());
        sessions=new OrtSession[options.length][];
        // The first option is built now, so a broken model still fails here rather than mid recognition.
        sessions[0]=sessions(false,options[0]);
        tuner=new ThreadTuner(options);
    }
    private OrtSession[] sessions(boolean useXnnpack,int threads) throws OrtException {
        try(var options=new OrtSession.SessionOptions()){
            // With XNNPACK the provider owns the worker threads; ORT's own pool stays at one and does not spin.
            if(useXnnpack){options.addXnnpack(Map.of("intra_op_num_threads","4"));options.setIntraOpNumThreads(1);options.addConfigEntry("session.intra_op.allow_spinning","0");}
            else options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            OrtSession[] made=new OrtSession[models.length];
            try{for(int i=0;i<models.length;i++)made[i]=env.createSession(models[i],options);}
            catch(OrtException e){for(OrtSession s:made)if(s!=null)s.close();throw e;}
            return made;
        }
    }

    /** Starts one read: picks the thread count to use for it. */
    void begin() throws OrtException {
        readStarted=System.nanoTime();modelNanos=0;modelCrops=0;
        option=tuner==null?0:tuner.pick();
        if(sessions[option]==null)sessions[option]=sessions(false,tuner.threads[option]);
    }
    /** The outputs of model for these crops; crops seen before reuse the stored output. */
    Predictions infer(int model,List<byte[]> crops) throws Exception {
        long[] indices=new long[crops.size()];float[] confidence=new float[crops.size()];
        List<byte[]> missing=new ArrayList<>();List<Integer> at=new ArrayList<>();
        for(int i=0;i<crops.size();i++){
            var hit=caches[model].get(crops.get(i));
            if(hit!=null){indices[i]=hit.index();confidence[i]=hit.confidence();}else{missing.add(crops.get(i));at.add(i);}
        }
        reused+=crops.size()-missing.size();
        if(!missing.isEmpty()){
            long started=System.nanoTime();Predictions p=predict(sessions[option][model],widths[model],missing);
            modelNanos+=System.nanoTime()-started;modelCrops+=missing.size();inferred+=missing.size();
            for(int j=0;j<missing.size();j++){
                indices[at.get(j)]=p.indices()[j];confidence[at.get(j)]=p.confidence()[j];
                caches[model].put(missing.get(j),p.indices()[j],p.confidence()[j]);
            }
        }
        return new Predictions(indices,confidence);
    }
    /** Ends the read: the tuner learns this thread count's speed, and the model/preparation split is updated. */
    void end(){
        if(tuner!=null&&modelCrops>0)tuner.record(option,modelNanos/1e6/modelCrops);
        double modelMs=modelNanos/1e6,prepMs=(System.nanoTime()-readStarted)/1e6-modelMs;
        modelMsAvg=modelMsAvg==0?modelMs:modelMsAvg*.9+modelMs*.1;prepMsAvg=prepMsAvg==0?prepMs:prepMsAvg*.9+prepMs*.1;
    }
    private Predictions predict(OrtSession session,int width,List<byte[]> crops) throws Exception {
        ByteBuffer buffer=ByteBuffer.allocateDirect(crops.size()*16*width*3).order(ByteOrder.nativeOrder());
        for(byte[] b:crops)buffer.put(b);
        buffer.flip();
        try(var input=OnnxTensor.createTensor(env,buffer,new long[]{crops.size(),16,width,3},OnnxJavaType.UINT8);
            var result=session.run(Collections.singletonMap("input",input))){
            return new Predictions((long[])result.get("index").orElseThrow().getValue(),(float[])result.get("confidence").orElseThrow().getValue());
        }
    }

    void resetCounters(){inferred=0;reused=0;}
    String splitText(String what){return String.format(Locale.JAPAN,"%sの内訳：前処理 %.1fms / モデル %.1fms",what,prepMsAvg,modelMsAvg);}
    String threadText(){return xnnpack?"推論スレッド：XNNPACK が管理":tuner.text();}
    String modeText(){return xnnpack?"XNNPACK":requestedXnnpack?"標準（XNNPACKは使用不可）":"標準";}
    @Override public void close(){for(OrtSession[] group:sessions)if(group!=null)for(OrtSession s:group)if(s!=null)try{s.close();}catch(Exception ignored){}}
}
