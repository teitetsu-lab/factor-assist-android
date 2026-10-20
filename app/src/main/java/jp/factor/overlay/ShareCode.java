package jp.factor.overlay;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

final class ShareCode {
    static String hash(byte[] bytes) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s=new StringBuilder();
        for(byte b:hash) s.append(String.format(Locale.ROOT,"%02x",b & 255)); return s.substring(0,16);
    }
    /** Same format as the PC preset_share.encode_preset: zlib level 9, unpadded URL-safe base64, 16-hex SHA-256 of the packed bytes. */
    static String encode(JSONObject preset) throws Exception {
        byte[] raw=preset.toString().getBytes(StandardCharsets.UTF_8);
        Deflater deflater=new Deflater(9); ByteArrayOutputStream out=new ByteArrayOutputStream();
        try {
            deflater.setInput(raw); deflater.finish(); byte[] buffer=new byte[4096];
            while(!deflater.finished()) out.write(buffer,0,deflater.deflate(buffer));
        } finally { deflater.end(); }
        byte[] packed=out.toByteArray();
        return "UFP1."+Base64.getUrlEncoder().withoutPadding().encodeToString(packed)+"."+hash(packed);
    }
    /** QR payloads matching the PC qr_parts: the code itself when short, otherwise 1400-character UFPQ1 chunks. */
    static List<String> split(String code) throws Exception {
        if(code.length()<=1400) return List.of(code);
        String identity=hash(code.getBytes(StandardCharsets.US_ASCII)); List<String> parts=new ArrayList<>(); int total=(code.length()+1399)/1400;
        for(int i=0;i<total;i++) parts.add("UFPQ1:"+identity+":"+(i+1)+":"+total+":"+code.substring(i*1400,Math.min(code.length(),(i+1)*1400)));
        return parts;
    }
    static JSONObject decode(String text) throws Exception {
        String[] p=text.trim().split("\\.");
        if(text.length()>100000 || p.length!=3 || !p[0].equals("UFP1")) throw new IOException("共有コードの形式が違います");
        byte[] packed=Base64.getUrlDecoder().decode(p[1]);
        if(!hash(packed).equals(p[2])) throw new IOException("共有コードが壊れています");
        Inflater inflater=new Inflater(); ByteArrayOutputStream out=new ByteArrayOutputStream();
        try {
            inflater.setInput(packed); byte[] buffer=new byte[4096];
            while(!inflater.finished()) {
                int n=inflater.inflate(buffer);
                if(n==0 || out.size()+n>1000000) throw new IOException("コードが不完全か大きすぎます");
                out.write(buffer,0,n);
            }
        } finally { inflater.end(); }
        JSONObject value=new JSONObject(out.toString(StandardCharsets.UTF_8));
        if(value.getInt("version")!=1 || value.getString("id").isEmpty() || value.getString("name").isBlank()) throw new IOException("プリセットが不正です");
        JSONArray entries=value.getJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.getJSONObject(i);
            if(!Arrays.asList(Store.TIERS).contains(e.getString("tier")) || e.optInt("min_star",1)<1 || e.optInt("min_star",1)>3) throw new IOException("優先度または星数が不正です");
            // The number is this app's own field; a preset from the PC version simply has none.
            if(e.has("priority") && (e.optInt("priority",-1)<0 || e.optInt("priority",-1)>Store.PRIORITY_MAX)) throw new IOException("優先度の数値が不正です");
            if(!e.has("factor") && !e.has("any_of")) throw new IOException("因子名がありません");
        }
        return value;
    }
    static String accept(String raw, Map<String,String> collected) throws Exception {
        if(raw.startsWith("UFP1.")) return raw;
        String[] p=raw.split(":",5);
        if(p.length!=5 || !p[0].equals("UFPQ1")) throw new IOException("プリセットのQRではありません");
        int part=Integer.parseInt(p[2]), total=Integer.parseInt(p[3]);
        if(total<1 || total>100 || part<1 || part>total) throw new IOException("QR番号が不正です");
        String group=p[1]+":"+total+":";
        collected.put(group+part,p[4]); StringBuilder s=new StringBuilder();
        for(int i=1;i<=total;i++) { String chunk=collected.get(group+i); if(chunk==null)return null; s.append(chunk); }
        if(!hash(s.toString().getBytes(StandardCharsets.US_ASCII)).equals(p[1]))throw new IOException("QRの内容が一致しません");
        // Whole: this group's parts are done with; any other group being collected stays.
        collected.keySet().removeIf(k->k.startsWith(group));
        return s.toString();
    }
}
