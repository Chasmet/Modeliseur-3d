package com.chasmet.modeliseur3d.update;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
public final class UpdateWorker extends Worker {
    public UpdateWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    @NonNull @Override public Result doWork(){
        if(!UpdateManager.automatic(getApplicationContext()))return Result.success();
        try{UpdateManager.check(getApplicationContext());return Result.success();}
        catch(Exception ignored){return Result.retry();}
    }
}
