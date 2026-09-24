package jp.factor.overlay;

import java.util.*;

/**
 * A text reading of a factor name, matched to the name it is. The recogniser is close but not exact,
 * in the same few ways every time (seen in the saved crops):
 *   - a stray character in front, from the edge of the ball left of the name: ")安田記念", "ト菊花賞"
 *   - the 指定 badge after the name read along with it: "中距離指定"
 *   - ○ read as a Latin O or zero: "秋ウマ娘O"
 *   - one character wrong: "超新量爆発"
 * Those are undone in turn, and only a single, unambiguous name is ever returned - two names one character
 * apart (秋ウマ娘○ and 秋ウマ娘◎) are never guessed between.
 */
final class NameMatch {
    /** names: every known name, normalised (Store.normalize), to itself. */
    static String match(String raw,Map<String,String> names){
        if(raw==null||names==null)return null;
        String text=Store.normalize(raw.replace("\n",""));
        if(text.isEmpty())return null;
        for(String candidate:candidates(text)){String name=names.get(candidate);if(name!=null)return name;}
        // One character off, and only when no other name is as close.
        String best=null;int ties=0;
        for(String candidate:candidates(text)){
            if(candidate.length()<3)continue;
            for(var entry:names.entrySet()){
                String key=entry.getKey();
                if(Math.abs(key.length()-candidate.length())>1||!withinOne(candidate,key))continue;
                if(best==null||!best.equals(entry.getValue())){ties+=best==null?0:1;best=entry.getValue();}
            }
            if(best!=null)return ties==0?best:null;
        }
        return null;
    }

    /** The reading as is, then with each of the known slips undone, most likely first. */
    static List<String> candidates(String text){
        Set<String> out=new LinkedHashSet<>();
        String plain=text.endsWith("指定")?text.substring(0,text.length()-2):text;
        for(String base:new String[]{text,plain}){
            String ring=circle(base);
            out.add(base);out.add(ring);
            if(base.length()>2){out.add(base.substring(1));out.add(circle(base.substring(1)));}
        }
        out.removeIf(String::isEmpty);
        return new ArrayList<>(out);
    }
    /** A trailing O, o or 0 is the ○ that ends so many names. */
    static String circle(String text){
        if(text.isEmpty())return text;
        char last=text.charAt(text.length()-1);
        return last=='O'||last=='o'||last=='0'?text.substring(0,text.length()-1)+'○':text;
    }

    /** Whether b is a (one substitution, insertion or deletion away, or equal). */
    static boolean withinOne(String a,String b){
        if(a.equals(b))return true;
        int la=a.length(),lb=b.length();
        if(Math.abs(la-lb)>1)return false;
        int i=0,j=0,edits=0;
        while(i<la&&j<lb){
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(++edits>1)return false;
            if(la>lb)i++;else if(lb>la)j++;else{i++;j++;}
        }
        return edits+(la-i)+(lb-j)<=1;
    }
}
