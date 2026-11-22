package com.chasmet.modeliseur3d.model;

import android.app.ActivityManager;
import android.content.Context;

/** Use available phone resources without treating physical RAM as Android's Java heap. */
public final class TripoComputePolicy {
    private TripoComputePolicy() { }
    public static boolean maximumPower(Context context){return maximumPower(context,context.getSharedPreferences("offline_workshop",0).getBoolean("maximumPower",true));}
    public static boolean maximumPower(Context context,boolean requested){
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);if(manager==null||!requested)return false;
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);
        return supportsMaximum(memory.totalMem,memory.availMem,Runtime.getRuntime().maxMemory(),memory.lowMemory);
    }
    public static boolean supportsMaximum(long total,long available,long heap,boolean low){return !low&&total>=8L*1024*1024*1024&&available>=2L*1024*1024*1024&&heap>=512L*1024*1024;}
    public static int threads(Context context) {
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        if(manager==null)return 2;
        manager.getMemoryInfo(memory);
        return threads(Runtime.getRuntime().availableProcessors(),memory.totalMem,memory.availMem,
                Runtime.getRuntime().maxMemory(),memory.lowMemory,maximumPower(context));
    }
    public static int threads(int cores,long total,long available,long heap,boolean low) {
        return threads(cores,total,available,heap,low,false);
    }
    public static int threads(int cores,long total,long available,long heap,boolean low,boolean maximum) {
        int requested=low||available<1024L*1024*1024||heap<384L*1024*1024?2:
                maximum&&supportsMaximum(total,available,heap,low)?8:total>=8L*1024*1024*1024?6:4;
        return Math.max(1,Math.min(requested,Math.max(1,cores-1)));
    }
    public static int imageLimit(){return Runtime.getRuntime().maxMemory()>=384L*1024*1024?2048:1024;}
    public static String summary(Context context){return threads(context)+" threads CPU · source jusqu’à "+imageLimit()+" px · mémoire Java "+(Runtime.getRuntime().maxMemory()/1048576)+" Mo";}
}
