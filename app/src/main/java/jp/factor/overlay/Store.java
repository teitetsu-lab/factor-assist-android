package jp.factor.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class Store {
    static final String[] TIERS = {"必須", "重要", "必要", "本育成で取得可能"};
    /** Shown wherever a tier or level is shown, for entries that are excluded. */
    static final String EXCLUDED = "除外（候補から外す）";
    /**
     * The detailed mode ranks factors 0 (most important) to 7, drawn as the seven colours of a rainbow
     * plus "no frame" for the last one. The four tiers are pairs of those levels, which keeps presets
     * readable by the PC version: every entry still carries its tier name, and `priority` is an extra
     * field the PC tool ignores.
     */
    static final String[] LEVELS = {"0 赤（最優先）", "1 橙", "2 黄", "3 緑", "4 青", "5 藍", "6 紫", "7 枠なし"};
    static final int PRIORITY_MAX = LEVELS.length - 1, PER_TIER = LEVELS.length / 4;
    /** Registered, but the factor has fewer stars than the entry requires. */
    static final int UNMET = PRIORITY_MAX + 1;
    /**
     * Deliberately excluded: counted apart and never framed.
     * the screen. A banned entry still carries a tier, so the PC version simply reads it as a low one.
     */
    static final int BANNED = UNMET + 1;
    /** Not in the preset at all. */
    static final int NONE = -1;
    static int tierOf(int priority) { return Math.min(TIERS.length - 1, Math.max(0, priority) / PER_TIER); }
    static int priorityOf(String tier) { int i = Arrays.asList(TIERS).indexOf(tier); return i < 0 ? NONE : i * PER_TIER; }
    /** A detailed preset ranks its factors 0-7 instead of the four tiers. A property of the preset itself. */
    static boolean detailed(JSONObject preset) { return preset != null && preset.optBoolean("detailed", false); }
    static boolean detailed(Context c) { return detailed(selected(c)); }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("presets", 0); }
    static JSONArray all(Context c) {
        try { return new JSONArray(prefs(c).getString("data", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    /** What a preset lists: factors. */
    static final String FACTOR = "factor", SKILL = "skill";
    static String kindOf(JSONObject preset) { return FACTOR; }
    static String key(String kind) { return "selected"; }
    static JSONObject selected(Context c) { return selected(c, FACTOR); }
    static JSONObject selected(Context c, String kind) {
        JSONArray all = all(c);
        String id = prefs(c).getString(key(kind), "");
        JSONObject first = null;
        for (int i=0; i<all.length(); i++) {
            JSONObject preset = all.optJSONObject(i);
            if (!kindOf(preset).equals(kind)) continue;
            if (preset.optString("id").equals(id)) return preset;
            if (first == null) first = preset;
        }
        return first;
    }
    static void select(Context c, String id) {
        JSONArray all = all(c);
        for (int i=0; i<all.length(); i++) {
            JSONObject preset = all.optJSONObject(i);
            if (preset.optString("id").equals(id)) { prefs(c).edit().putString(key(kindOf(preset)), id).apply(); return; }
        }
    }
    static void save(Context c, JSONObject value) {
        try {
            JSONArray all = all(c); boolean found = false;
            for (int i=0; i<all.length(); i++) if (all.getJSONObject(i).getString("id").equals(value.getString("id"))) { all.put(i,value); found=true; break; }
            if (!found) all.put(value);
            prefs(c).edit().putString("data", all.toString()).putString(key(kindOf(value)),value.getString("id")).apply();
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    /** Removes a preset. If it was the chosen one, the first remaining preset of its kind is used instead. */
    static void delete(Context c, String id) {
        JSONArray all = all(c), kept = new JSONArray(); String kind = null;
        for (int i=0; i<all.length(); i++) {
            JSONObject preset = all.optJSONObject(i);
            if (preset.optString("id").equals(id)) kind = kindOf(preset); else kept.put(preset);
        }
        var edit = prefs(c).edit().putString("data", kept.toString());
        if (kind != null && id.equals(prefs(c).getString(key(kind), ""))) edit.remove(key(kind));
        edit.apply();
    }
    static JSONObject fresh(String name) { return fresh(name, FACTOR); }
    static JSONObject fresh(String name,String kind) {
        try { return new JSONObject().put("version",1).put("id",UUID.randomUUID().toString()).put("name",name)
                                     .put("kind",kind).put("entries",new JSONArray()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).replaceAll("[\\s・･]", "").replace('〇','○'); }
    /** Letters only, to match a horse's name read on screen against another reading (brackets, dots and stars go). */
    static String letters(String s){return s==null?"":s.replaceAll("[\\s\\[\\]［］【】「」・･☆★♪♡!！?？.,、。〜~ー-]","");}
    /** Looser key for the factor search box only: hiragana folds to katakana and latin to lower case. */
    static String searchKey(String s) {
        String n=normalize(s);StringBuilder b=new StringBuilder(n.length());
        for(char ch:n.toCharArray())b.append(ch>='ぁ'&&ch<='ゖ'?(char)(ch+0x60):ch);
        return b.toString().toLowerCase(Locale.ROOT);
    }
    record Rule(int priority,int minimum) {}
    /** An entry's priority: BANNED when excluded, the number when it has one, otherwise its tier's first level. */
    static int entryPriority(JSONObject entry) {
        if(entry.optBoolean("ban",false))return BANNED;
        int tier=priorityOf(entry.optString("tier"));if(tier<0)return NONE;
        int priority=entry.optInt("priority",tier);
        return priority<0||priority>PRIORITY_MAX?tier:priority;
    }
    static Map<String,List<Rule>> rules(Context c) { return rules(c, FACTOR); }
    static Map<String,List<Rule>> rules(Context c,String kind) {
        Map<String,List<Rule>> rules=new HashMap<>();JSONObject preset=selected(c,kind);if(preset==null)return rules;
        JSONArray entries=preset.optJSONArray("entries");
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);int priority=entryPriority(entry);if(priority<0)continue;
            JSONArray names=entry.optJSONArray("any_of");if(names==null)names=new JSONArray().put(entry.optString("factor"));
            for(int n=0;n<names.length();n++)rules.computeIfAbsent(normalize(names.optString(n)),key->new ArrayList<>()).add(new Rule(priority,entry.optInt("min_star",1)));
        }
        return rules;
    }
    /** The best priority this factor is registered at, ignoring the stars it needs; NONE when unregistered. */
    static int registered(Map<String,List<Rule>> rules,String name) {
        List<Rule> matching=rules.get(normalize(name));if(matching==null)return NONE;int best=BANNED;
        for(Rule rule:matching)best=Math.min(best,rule.priority());
        return best;
    }
    /** The best priority this factor earns with these stars: 0..PRIORITY_MAX, or UNMET / NONE. */
    static int priority(Map<String,List<Rule>> rules,String name,int stars) {
        List<Rule> matching=rules.get(normalize(name));if(matching==null)return NONE;int best=UNMET;
        // A ban wins over every other entry for that name, whatever the stars.
        for(Rule rule:matching)if(rule.priority()==BANNED)return BANNED;
        for(Rule rule:matching)if(stars>=rule.minimum)best=Math.min(best,rule.priority());
        return best;
    }
    static String tier(Map<String,List<Rule>> rules,String name,int stars) {
        int priority=priority(rules,name,stars);
        return priority==NONE?"未設定":priority==BANNED?"除外":priority==UNMET?"条件未達":TIERS[tierOf(priority)];
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
