package jp.factor.overlay;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * The recognition modules - umacapture's models and name lists. The newest set is umacapture's own
 * modules.zip, kept as it was downloaded in the app's modules folder next to the version_info.json that
 * describes it (Android/data/jp.factor.overlay/files/modules); each file is read straight out of the zip.
 * Without a usable zip there, the set bundled in the APK is used. Every file goes through here, so a model and
 * the labels.json that numbers its outputs always come from the same set.
 */
final class Modules {
    static final String ZIP="modules.zip", VERSION="version_info.json";
    /** Asset name → the file's path inside umacapture's modules.zip. */
    static final Map<String,String> FILES;
    static {
        Map<String,String> files=new LinkedHashMap<>();
        files.put("factor.onnx","modules/factor/prediction.onnx");
        files.put("factor_rank.onnx","modules/factor_rank/prediction.onnx");
        files.put("labels.json","modules/labels.json");
        files.put("factor_info.json","modules/factor_info.json");
        files.put("factor_tag.json","modules/factor_tag.json");
        files.put("umacapture-modules-LICENSE.md","modules/license.md");
        FILES=Collections.unmodifiableMap(files);
    }
    /** A zip without these is not used at all. */
    static final List<String> REQUIRED=List.of("factor.onnx","factor_rank.onnx","labels.json");

    static File folder(Context c){return new File(c.getExternalFilesDir(null),"modules");}
    /** The set in use before the last update, kept in case the new one turns out unusable. */
    static File backup(Context c){return new File(folder(c),"backup");}

    static byte[] read(Context c,String asset) throws IOException {
        File dir=usable(c);
        if(dir!=null){
            if(asset.equals("umacapture-modules-version.json"))return java.nio.file.Files.readAllBytes(new File(dir,VERSION).toPath());
            byte[] bytes=fromZip(new File(dir,ZIP),asset);
            if(bytes!=null)return bytes;
        }
        try(var in=c.getAssets().open(asset)){return in.readAllBytes();}
    }
    /**
     * Several files that must come from the same set - a model and the labels.json that numbers its outputs. Read
     * one by one, an update finishing between two reads paired the old model with the new labels; here the set
     * is chosen once and its zip opened once for all of them.
     */
    static Map<String,byte[]> readSet(Context c,String... assets) throws IOException {
        Map<String,byte[]> out=new HashMap<>();
        File dir=usable(c);
        if(dir!=null)try(ZipFile file=new ZipFile(new File(dir,ZIP))){
            for(String asset:assets){
                String path=FILES.get(asset);var entry=path==null?null:file.getEntry(path);
                if(entry!=null)try(var in=file.getInputStream(entry)){out.put(asset,in.readAllBytes());}
            }
        }catch(IOException e){out.clear();}  // gone or replaced under us: the bundled set, whole
        for(String asset:assets)if(!out.containsKey(asset))
            try(var in=c.getAssets().open(asset)){out.put(asset,in.readAllBytes());}
        return out;
    }
    /**
     * The folder whose modules.zip is used: the downloaded one, else the backup of the one before it, else null
     * for the set bundled in the APK. A zip counts only with its version_info.json beside it and every model and
     * the labels inside, and each set is used whole, so a model never pairs with another set's labels.
     */
    static File usable(Context c){
        for(File dir:new File[]{folder(c),backup(c)})if(complete(dir))return dir;
        return null;
    }
    /** Where the models in use come from, for the app screen. */
    static String source(Context c){
        File dir=usable(c);
        return dir==null?"アプリ同梱":dir.equals(folder(c))?"ダウンロード版":"バックアップ（1つ前の版）";
    }
    /**
     * Files are in the modules folder but do not make a whole set - damaged, or left half-written. The backup
     * or the bundled set is used meanwhile; this is what the re-download button is offered for.
     */
    static boolean broken(Context c){
        File folder=folder(c);
        boolean any=new File(folder,ZIP).exists()||new File(folder,VERSION).exists();
        return any&&!complete(folder);
    }
    static boolean complete(File dir){
        File zip=new File(dir,ZIP);
        if(!zip.isFile()||!new File(dir,VERSION).isFile())return false;
        try(ZipFile file=new ZipFile(zip)){
            for(String asset:REQUIRED)if(file.getEntry(FILES.get(asset))==null)return false;
            return true;
        }catch(IOException e){return false;}
    }
    static String text(Context c,String asset) throws IOException {return new String(read(c,asset),StandardCharsets.UTF_8);}

    /** One file out of a modules.zip, or null when the zip does not hold it. */
    static byte[] fromZip(File zip,String asset) throws IOException {
        String path=FILES.get(asset);
        if(path==null)return null;
        try(ZipFile file=new ZipFile(zip)){
            var entry=file.getEntry(path);
            if(entry==null)return null;
            try(var in=file.getInputStream(entry)){return in.readAllBytes();}
        }
    }

    /** When the models in use were made (umacapture's recognizer_version), or "" when unknown. */
    static String version(Context c){
        try{return new JSONObject(text(c,"umacapture-modules-version.json")).optString("recognizer_version","");}
        catch(Exception e){return "";}
    }

    static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx");
    /** Whether the recognizer_version offered is later than the one in use. An unreadable one never is. */
    static boolean newer(String offered,String current){
        try{
            OffsetDateTime next=OffsetDateTime.parse(offered,STAMP);
            if(current==null||current.isEmpty())return true;
            return next.isAfter(OffsetDateTime.parse(current,STAMP));
        }catch(Exception e){return false;}
    }
}
