package jp.factor.overlay;

import java.util.*;
import org.json.*;

/**
 * Every factor the recognition model can output, for the preset picker.
 *
 * Built straight from umacapture's module files so a model update needs no code change:
 *   labels.json      factor.name — the model's classes; the only list that decides what can be recognised
 *   factor_info.json per-class reading, romaji, tag and in-game sort order (optional)
 *   factor_tag.json  display names for the tags (optional)
 * factor_info entries are matched to labels by sid (= class index) and must carry the same name.
 * Classes without an info entry — factors newer than the info file — still appear, under UNCATEGORISED.
 */
final class FactorCatalog {
    static final String UNCATEGORISED="未分類（新しい因子）";
    record Factor(String name,String category,String searchText,int order) {}

    final List<Factor> factors;        // in-game order, then new factors in model order
    final List<String> categories;     // factor_tag.json order, only those in use, UNCATEGORISED last
    final Set<String> known;           // Store.normalize(name) of every factor
    final int uncategorised;

    private FactorCatalog(List<Factor> factors,List<String> categories,Set<String> known,int uncategorised) {
        this.factors=factors;this.categories=categories;this.known=known;this.uncategorised=uncategorised;
    }

    static FactorCatalog parse(String labelsJson,String infoJson,String tagJson) throws JSONException {
        JSONArray labels=new JSONObject(labelsJson).getJSONArray("factor.name");
        Map<String,String> tagNames=new LinkedHashMap<>();
        if(tagJson!=null){JSONArray tags=new JSONArray(tagJson);for(int i=0;i<tags.length();i++){JSONObject t=tags.getJSONObject(i);tagNames.put(t.getString("id"),t.optString("name",t.getString("id")));}}
        Map<Integer,JSONObject> info=new HashMap<>();
        if(infoJson!=null){JSONArray all=new JSONArray(infoJson);for(int i=0;i<all.length();i++){JSONObject f=all.getJSONObject(i);info.put(f.getInt("sid"),f);}}

        List<Factor> factors=new ArrayList<>();Set<String> known=new HashSet<>();Set<String> used=new HashSet<>();int missing=0;
        for(int sid=0;sid<labels.length();sid++){
            String name=labels.getString(sid);if(!known.add(Store.normalize(name)))continue;
            JSONObject f=info.get(sid);JSONArray names=f==null?null:f.optJSONArray("names");
            // A sid whose info names a different factor means the files come from different module versions.
            // Some info names carry a suffix after ". " (e.g. "L'Arc・栄養管理. 栄養管理"); only the part before it is the label.
            if(names!=null){String infoName=names.optString(0);int suffix=infoName.indexOf(". ");if(suffix>0)infoName=infoName.substring(0,suffix);
                if(!Store.normalize(infoName).equals(Store.normalize(name))){f=null;names=null;}}
            String category;StringBuilder search=new StringBuilder(Store.searchKey(name));int order;
            if(f==null){category=UNCATEGORISED;order=1_000_000+sid;missing++;}
            else{
                JSONArray tags=f.optJSONArray("tags");String tag=tags!=null&&tags.length()>0?tags.optString(0):"other";
                category=tagNames.getOrDefault(tag,tag);order=f.optInt("sort_key",1_000_000+sid);
                for(int n=1;n<names.length();n++)search.append('\n').append(Store.searchKey(names.optString(n)));
            }
            used.add(category);factors.add(new Factor(name,category,search.toString(),order));
        }
        factors.sort(Comparator.comparingInt(Factor::order));
        List<String> categories=new ArrayList<>();
        for(String c:tagNames.values())if(used.contains(c)&&!categories.contains(c))categories.add(c);
        for(Factor f:factors)if(!categories.contains(f.category())&&!f.category().equals(UNCATEGORISED))categories.add(f.category());
        if(used.contains(UNCATEGORISED))categories.add(UNCATEGORISED);
        return new FactorCatalog(List.copyOf(factors),List.copyOf(categories),Set.copyOf(known),missing);
    }

    /** category null means all; query is matched against the name, reading and romaji. */
    List<Factor> filter(String category,String query) {
        String q=Store.searchKey(query);List<Factor> out=new ArrayList<>();
        for(Factor f:factors)if((category==null||category.equals(f.category()))&&(q.isEmpty()||f.searchText().contains(q)))out.add(f);
        return out;
    }
}
