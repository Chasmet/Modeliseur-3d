package com.chasmet.modeliseur3d.model;

/** Continuous surface sampling without mixing two layers across an occlusion boundary. */
public final class SurfaceDepthSampler {
    private SurfaceDepthSampler(){}
    public static boolean sample(float[] depth,float[] normal,int side,float u,float v,float maximumJump,float[] out){
        if(u<0||u>1||v<0||v>1){out[0]=Float.NaN;out[1]=0;return false;}
        float x=u*(side-1),y=v*(side-1);int ix=Math.min(side-2,(int)x),iy=Math.min(side-2,(int)y);
        float fx=x-ix,fy=y-iy,reference=Float.NaN,nearest=Float.POSITIVE_INFINITY;
        for(int b=0;b<2;b++)for(int a=0;a<2;a++){
            int i=(iy+b)*side+ix+a;float distance=(a-fx)*(a-fx)+(b-fy)*(b-fy);
            if(Float.isFinite(depth[i])&&distance<nearest){nearest=distance;reference=depth[i];}
        }
        if(!Float.isFinite(reference)){out[0]=Float.NaN;out[1]=0;return false;}
        float sum=0,z=0,n=0;
        for(int b=0;b<2;b++)for(int a=0;a<2;a++){
            int i=(iy+b)*side+ix+a;float w=(a==0?1-fx:fx)*(b==0?1-fy:fy);
            if(Float.isFinite(depth[i])&&Math.abs(depth[i]-reference)<=maximumJump){sum+=w;z+=w*depth[i];n+=w*normal[i];}
        }
        if(sum<=1e-8f){out[0]=reference;out[1]=0;return true;}
        out[0]=z/sum;out[1]=n/sum;return true;
    }
}
