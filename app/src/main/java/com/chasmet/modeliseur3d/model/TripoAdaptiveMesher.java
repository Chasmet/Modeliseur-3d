package com.chasmet.modeliseur3d.model;

import java.util.concurrent.CancellationException;

/**
 * Reuses one cached TripoSR neural volume and selects a mesh sampling density within
 * the existing triangle cap. Nothing is generated remotely. The classic mode remains.
 */
public final class TripoAdaptiveMesher {
    private TripoAdaptiveMesher() {}
    public static final class Result {
        public final MeshData mesh;
        public final int w,h,d,attempts;
        private Result(MeshData mesh,int w,int h,int d,int attempts){
            this.mesh=mesh;this.w=w;this.h=h;this.d=d;this.attempts=attempts;
        }
    }
    public static int dimension(int full,float factor){
        return Math.max(8,Math.min(full,Math.round(full*factor)));
    }
    public static Result build(TripoSRRefinedField field,int triangleBudget,boolean adaptive){
        if(field==null)throw new IllegalArgumentException("Champ neuronal absent");
        float factor=1f,passed=0f,failed=1.01f;
        Result best=null;int attempts=adaptive?7:4;
        for(int attempt=0;attempt<attempts;attempt++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            int w=dimension(field.ny,factor),h=dimension(field.nz,factor),d=dimension(field.nx,factor);
            if(best!=null&&best.w==w&&best.h==h&&best.d==d)break;
            // Native probabilities are sampled from the same TripoSR field for every trial.
            // Sampling here does not invoke the image encoder or re-download model weights.
            float[] values=new float[w*h*d],b=field.bounds;
            for(int y=1;y<h-1;y++){
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                float yy=b[5]-(b[5]-b[2])*y/(h-1);
                for(int x=1;x<w-1;x++)for(int z=1;z<d-1;z++)
                    values[(y*w+x)*d+z]=field.probability(
                        b[1]+(b[4]-b[1])*x/(w-1),yy,b[0]+(b[3]-b[0])*z/(d-1));
            }
            try {
                MeshData candidate=OfflineHullMesher.buildDetailedField(values,w,h,d,triangleBudget);
                best=new Result(candidate,w,h,d,attempt+1);
                passed=factor;
                if(!adaptive||factor>=.999f)break;
                factor=(passed+failed)*.5f;
            }catch(OfflineHullMesher.TooComplexException e){
                failed=Math.min(failed,factor);
                if(adaptive)factor=passed>0?(passed+failed)*.5f:factor*.65f;
                else factor*=.8f;
            }
            if(factor<.20f)break;
        }
        if(best==null)throw new IllegalArgumentException("Maillage trop complexe : réduis la qualité ou désactive la précision adaptative.");
        return best;
    }
}
