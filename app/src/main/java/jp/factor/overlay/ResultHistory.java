package jp.factor.overlay;

import android.content.Context;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * What was read, kept with the time it was read up to, so that nothing is lost to a reset or a mode switch nobody
 * meant: the results are saved just before they are cleared, and any saved result can be put back and read on
 * from. Factors and skills are kept apart, each in its own mode. Only the text is kept, not the chip images.
 */
final class ResultHistory {
    static final int KEEP=50;
    record Entry(long time,String mode,JSONObject data) {
        String when(){return new SimpleDateFormat("M/d HH:mm",Locale.JAPAN).format(new Date(time));}
        /** 元因子 5件・再獲得因子1 3件…, or スキル 24件（取得済 3）. */
        String summary(){
            if(Store.SKILL.equals(mode)){
                JSONArray skills=data.optJSONArray("skills");int n=skills==null?0:skills.length(),owned=0;
                for(int i=0;i<n;i++)if(skills.optJSONObject(i).optBoolean("acquired"))owned++;
                return "スキル "+n+"件"+(owned>0?"（取得済 "+owned+"）":"");
            }
            JSONObject pages=data.optJSONObject("pages");StringJoiner s=new StringJoiner("・");
            if(pages!=null)for(Iterator<String> i=pages.keys();i.hasNext();){String page=i.next();s.add(page+" "+pages.optJSONObject(page).length()+"件");}
            return s.length()>0?s.toString():"因子なし";
        }
    }

    private static File file(Context c){return new File(c.getFilesDir(),"result_history.json");}
    static synchronized List<Entry> all(Context c){
        List<Entry> out=new ArrayList<>();
        try{
            File f=file(c);if(!f.exists())return out;
            JSONArray stored=new JSONArray(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            for(int i=0;i<stored.length();i++){JSONObject e=stored.getJSONObject(i);out.add(new Entry(e.getLong("time"),e.getString("mode"),e.getJSONObject("data")));}
        }catch(Exception ignored){}
        return out;
    }
    /** The saved results of one mode, newest first. */
    static List<Entry> of(Context c,String mode){
        List<Entry> out=new ArrayList<>();for(Entry e:all(c))if(e.mode().equals(mode))out.add(e);
        out.sort((a,b)->Long.compare(b.time(),a.time()));return out;
    }
    private static synchronized void add(Context c,Entry entry){
        List<Entry> kept=all(c);kept.add(entry);
        // The oldest of each mode go first once there are more than KEEP of it.
        for(String mode:new String[]{Store.FACTOR,Store.SKILL}){
            List<Entry> same=new ArrayList<>();for(Entry e:kept)if(e.mode().equals(mode))same.add(e);
            same.sort(Comparator.comparingLong(Entry::time));
            for(int i=0;i<same.size()-KEEP;i++)kept.remove(same.get(i));
        }
        try{
            JSONArray out=new JSONArray();
            for(Entry e:kept)out.put(new JSONObject().put("time",e.time()).put("mode",e.mode()).put("data",e.data()));
            Files.write(file(c).toPath(),out.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}
    }

    /** Saves what the mode has read now, unless it has read nothing. */
    static void save(Context c,String mode){
        try{
            JSONObject data=new JSONObject();
            if(Store.SKILL.equals(mode)){
                var skills=CaptureService.skillsInOrder();if(skills.isEmpty())return;
                JSONArray list=new JSONArray();
                for(var s:skills)list.put(new JSONObject().put("name",s.name()).put("pt",s.pt()).put("acquired",s.acquired()));
                data.put("skills",list).put("ownedPt",CaptureService.ownedPt);
            }else{
                var pages=CaptureService.snapshot();if(pages.isEmpty())return;
                JSONObject out=new JSONObject();
                for(var page:pages.entrySet())out.put(page.getKey(),new JSONObject(page.getValue()));
                data.put("pages",out);
            }
            add(c,new Entry(System.currentTimeMillis(),mode,data));
        }catch(Exception ignored){}
    }

    /**
     * Puts a saved result back as the mode's results, to read on from. What the mode holds now is saved first,
     * so putting one back never loses anything either.
     */
    static void restore(Context c,Entry entry){
        save(c,entry.mode());
        CaptureService.restore(entry.mode(),entry.data());
    }
    /** The newest saved result of the mode that is not what the mode holds now; null when there is none. */
    static Entry previous(Context c,String mode){
        String now=current(mode);
        for(Entry e:of(c,mode))if(!e.data().toString().equals(now))return e;
        return null;
    }
    private static String current(String mode){
        try{
            JSONObject data=new JSONObject();
            if(Store.SKILL.equals(mode)){
                JSONArray list=new JSONArray();
                for(var s:CaptureService.skillsInOrder())list.put(new JSONObject().put("name",s.name()).put("pt",s.pt()).put("acquired",s.acquired()));
                data.put("skills",list).put("ownedPt",CaptureService.ownedPt);
            }else{
                JSONObject out=new JSONObject();
                for(var page:CaptureService.snapshot().entrySet())out.put(page.getKey(),new JSONObject(page.getValue()));
                data.put("pages",out);
            }
            return data.toString();
        }catch(Exception e){return "";}
    }

    /** The saved result as text, for looking at before putting it back. */
    static String text(Entry e){
        StringBuilder s=new StringBuilder();
        if(Store.SKILL.equals(e.mode())){
            int owned=e.data().optInt("ownedPt",-1);if(owned>0)s.append("所持 ").append(owned).append("pt\n");
            JSONArray skills=e.data().optJSONArray("skills");
            for(int i=0;skills!=null&&i<skills.length();i++){JSONObject k=skills.optJSONObject(i);
                s.append(k.optString("name")).append("  ").append(k.optBoolean("acquired")?"取得済":k.optInt("pt")>0?k.optInt("pt")+"pt":"pt不明").append('\n');}
        }else{
            JSONObject pages=e.data().optJSONObject("pages");
            if(pages!=null)for(Iterator<String> i=pages.keys();i.hasNext();){String page=i.next();JSONObject f=pages.optJSONObject(page);
                s.append("【").append(page).append("】\n");
                for(Iterator<String> n=f.keys();n.hasNext();){String name=n.next();s.append(name).append("  ☆").append(f.optInt(name)).append('\n');}}
        }
        return s.toString().trim();
    }
}
