package jp.factor.overlay;

import java.util.*;

/**
 * Where each factor sits in its page, so results are listed top to bottom as in the game however the list was
 * scrolled. Rows shared with earlier frames give the scroll distance: seeing D and E again places the B and C
 * above them correctly, even though they were read later.
 *
 * Positions are in capture pixels, relative to wherever the page happened to be when it was first seen.
 */
final class PagePlaces {
    record Row(String name,int y) {}
    private final Map<String,Map<String,Integer>> places=new LinkedHashMap<>();
    private final Map<String,Integer> scrolled=new HashMap<>();

    /** Places every row of this frame, using the ones already known to measure how far the page has scrolled. */
    synchronized void record(String label,List<Row> rows){
        if(rows.isEmpty())return;
        Map<String,Integer> place=places.computeIfAbsent(label,k->new HashMap<>());
        List<Integer> shifts=new ArrayList<>();
        for(Row row:rows){Integer known=place.get(row.name());if(known!=null)shifts.add(known-row.y());}
        int offset;
        if(!shifts.isEmpty()){Collections.sort(shifts);offset=shifts.get(shifts.size()/2);}
        else offset=scrolled.getOrDefault(label,0);   // nothing in common: keep the last known scroll distance
        scrolled.put(label,offset);
        for(Row row:rows)place.putIfAbsent(row.name(),row.y()+offset);
    }
    /** The given names of one page, top to bottom; anything never placed goes last. */
    synchronized List<String> ordered(String label,Collection<String> names){
        Map<String,Integer> place=places.getOrDefault(label,Map.of());
        List<String> sorted=new ArrayList<>(names);
        sorted.sort(Comparator.comparingInt(n->place.getOrDefault(n,Integer.MAX_VALUE)));
        return sorted;
    }
    synchronized Map<String,Integer> of(String label){return Map.copyOf(places.getOrDefault(label,Map.of()));}
    synchronized Map<String,Map<String,Integer>> copy(){
        Map<String,Map<String,Integer>> out=new LinkedHashMap<>();
        for(var page:places.entrySet())out.put(page.getKey(),new HashMap<>(page.getValue()));
        return out;
    }
    synchronized void clear(){places.clear();scrolled.clear();}
    synchronized void remove(String label,String name){
        Map<String,Integer> place=places.get(label);
        if(place!=null){place.remove(name);if(place.isEmpty()){places.remove(label);scrolled.remove(label);}}
    }
}
