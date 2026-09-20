package jp.factor.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class Store {
    static final String[] TIERS = {"必須", "重要", "必要", "本育成で取得可能"};
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("presets", 0); }
    static JSONArray all(Context c) {
        try { return new JSONArray(prefs(c).getString("data", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    static JSONObject selected(Context c) {
        JSONArray all = all(c);
        String id = prefs(c).getString("selected", "");
        for (int i=0; i<all.length(); i++) if (all.optJSONObject(i).optString("id").equals(id)) return all.optJSONObject(i);
        return all.optJSONObject(0);
    }
    static void select(Context c, String id) { prefs(c).edit().putString("selected", id).apply(); }
    static void save(Context c, JSONObject value) {
        try {
            JSONArray all = all(c); boolean found = false;
            for (int i=0; i<all.length(); i++) if (all.getJSONObject(i).getString("id").equals(value.getString("id"))) { all.put(i,value); found=true; break; }
            if (!found) all.put(value);
            prefs(c).edit().putString("data", all.toString()).putString("selected",value.getString("id")).apply();
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    static JSONObject fresh(String name) {
        try { return new JSONObject().put("version",1).put("id",UUID.randomUUID().toString()).put("name",name).put("entries",new JSONArray()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).replaceAll("[\\s・･]", "").replace('〇','○'); }
    /** Looser key for the factor search box only: hiragana folds to katakana and latin to lower case. */
    static String searchKey(String s) {
        String n=normalize(s);StringBuilder b=new StringBuilder(n.length());
        for(char ch:n.toCharArray())b.append(ch>='ぁ'&&ch<='ゖ'?(char)(ch+0x60):ch);
        return b.toString().toLowerCase(Locale.ROOT);
    }
    record Rule(int rank,int minimum) {}
    static Map<String,List<Rule>> rules(Context c) {
        Map<String,List<Rule>> rules=new HashMap<>();JSONObject preset=selected(c);if(preset==null)return rules;
        JSONArray entries=preset.optJSONArray("entries");
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);int rank=Arrays.asList(TIERS).indexOf(entry.optString("tier"));if(rank<0)continue;
            JSONArray names=entry.optJSONArray("any_of");if(names==null)names=new JSONArray().put(entry.optString("factor"));
            for(int n=0;n<names.length();n++)rules.computeIfAbsent(normalize(names.optString(n)),key->new ArrayList<>()).add(new Rule(rank,entry.optInt("min_star",1)));
        }
        return rules;
    }
    static String tier(Map<String,List<Rule>> rules,String name,int stars) {
        List<Rule> matching=rules.get(normalize(name));if(matching==null)return "未設定";int best=TIERS.length;
        for(Rule rule:matching)if(stars>=rule.minimum)best=Math.min(best,rule.rank);
        return best<TIERS.length?TIERS[best]:"条件未達";
    }
    static Map<String,String> targets(Context c) {
        Map<String,String> result = new HashMap<>(); JSONObject value=selected(c);
        JSONArray entries=value==null?new JSONArray():value.optJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i); JSONArray names=e.optJSONArray("any_of");
            if(names==null) names=new JSONArray().put(e.optString("factor"));
            for(int j=0;j<names.length();j++) result.put(normalize(names.optString(j)),e.optString("tier"));
        }
        return result;
    }
}
