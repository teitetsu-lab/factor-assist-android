package jp.factor.overlay;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Model output per exact crop. The game background keeps animating, so frames keep arriving while the
 * list stands still; the row crops are then byte-identical and need no new inference. Keys compare the
 * full crop contents (ByteBuffer.equals), so a hit is never a different image.
 */
final class InferenceCache {
    record Hit(long index, float confidence) {}
    private final LinkedHashMap<ByteBuffer,Hit> entries;

    InferenceCache(int capacity) {
        entries=new LinkedHashMap<>(capacity*2,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<ByteBuffer,Hit> eldest){return size()>capacity;}
        };
    }
    Hit get(byte[] crop){return entries.get(ByteBuffer.wrap(crop));}
    // Crops are freshly allocated per frame and never written after this, so wrapping without a copy is safe.
    void put(byte[] crop,long index,float confidence){entries.put(ByteBuffer.wrap(crop),new Hit(index,confidence));}
    int size(){return entries.size();}
}
