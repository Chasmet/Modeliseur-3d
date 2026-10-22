package com.chasmet.modeliseur3d.diagnostics;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Small, persistent app-only journal. Never reads Android's global logcat. */
public final class DiagnosticLog {
    private static final int LIMIT=128*1024;
    private static File directory;
    private DiagnosticLog(){}
    public static synchronized void initialize(Context c){
        if(directory!=null)return;
        directory=new File(c.getApplicationContext().getFilesDir(),"diagnostics");
        directory.mkdirs();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            record("ERREUR","Arrêt inattendu",error);
            if(previous!=null)previous.uncaughtException(thread,error);
        });
    }
    public static String sanitize(String value){
        if(value==null)return "";
        String out=value.replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [masqué]")
                .replaceAll("https?://[^\\s]+","[adresse masquée]")
                .replaceAll("(?i)(token|password|secret|authorization|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+","$1=[masqué]")
                .replaceAll("\\b[0-9a-fA-F]{32,}\\b","[identifiant masqué]")
                .replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b","[IP masquée]")
                .replaceAll("/data/[^\\s]+","[chemin privé]");
        return out.length()>1800?out.substring(0,1800)+"…":out;
    }
    public static void record(String level,String message){record(level,message,null);}
    public static synchronized void record(String level,String message,Throwable error){
        if(directory==null)return;
        try{
            File current=new File(directory,"current.log"),old=new File(directory,"previous.log");
            if(current.length()>LIMIT){old.delete();if(!current.renameTo(old))return;}
            String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date())+" "+level+" "+sanitize(message);
            if(error!=null){line+=" · "+error.getClass().getSimpleName()+": "+sanitize(error.getMessage());
                int limit=Math.min(6,error.getStackTrace().length);
                for(int i=0;i<limit;i++)line+="\n  "+sanitize(error.getStackTrace()[i].toString());}
            try(Writer out=new OutputStreamWriter(new FileOutputStream(current,true),StandardCharsets.UTF_8)){out.write(line+"\n");}
        }catch(IOException ignored){}
    }
    public static synchronized String read(){
        if(directory==null)return "Aucun journal enregistré.";
        StringBuilder out=new StringBuilder();
        for(String name:new String[]{"previous.log","current.log"}){
            File file=new File(directory,name);if(!file.isFile())continue;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)out.append(line).append('\n');}
            catch(IOException e){out.append("Journal illisible.\n");}
        }
        return out.length()==0?"Aucune opération enregistrée.":out.toString();
    }
    public static synchronized void clear(){if(directory!=null)for(String name:new String[]{"previous.log","current.log"})new File(directory,name).delete();}
}
