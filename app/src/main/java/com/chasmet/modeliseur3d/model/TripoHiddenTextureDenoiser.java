package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.concurrent.CancellationException;

/**
 * One restrained edge-aware pass on estimated back/side UV charts ONLY.
 * The observed front chart (0) and detail chart (6) are never touched.
 */
public final class TripoHiddenTextureDenoiser {
    private TripoHiddenTextureDenoiser(){}
    static int channel(int rgb,int shift){return (rgb>>>shift)&255;}
    static int mix(int a,int b,float weight){
        int color=0xff000000;
        for(int shift=0;shift<=16;shift+=8)
            color|=Math.round(channel(a,shift)*(1-weight)+channel(b,shift)*weight)<<shift;
        return color;
    }
    static int[] clean(int[] source,int size){
        int[] result=source.clone();
        for(int y=1;y<size-1;y++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            for(int x=1;x<size-1;x++){
                int i=y*size+x,c=source[i];
                if(c==0xff303030)continue;
                float sumR=0,sumG=0,sumB=0,weights=0;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int near=source[(y+dy)*size+x+dx];if(near==0xff303030)continue;
                    int diff=Math.abs(channel(c,16)-channel(near,16))
                            +Math.abs(channel(c,8)-channel(near,8))
                            +Math.abs(channel(c,0)-channel(near,0));
                    if(diff>90)continue; // preserve material edges and hair colour boundaries
                    float weight=(dx==0&&dy==0)?2f:(dx==0||dy==0?1f:.55f);
                    weight*=Math.max(.05f,1f-diff/105f);
                    sumR+=channel(near,16)*weight;sumG+=channel(near,8)*weight;
                    sumB+=channel(near,0)*weight;weights+=weight;
                }
                if(weights>0){
                    int averaged=0xff000000|(Math.round(sumR/weights)<<16)
                            |(Math.round(sumG/weights)<<8)|Math.round(sumB/weights);
                    result[i]=mix(c,averaged,.45f);
                }
            }
        }
        return result;
    }
    public static void improve(Bitmap atlas,int cell,int columns){
        if(atlas==null||atlas.isRecycled()||cell<4||columns<3)return;
        for(int v=1;v<=5;v++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            int px=(v%columns)*cell,py=(v/columns)*cell;
            if(px+cell>atlas.getWidth()||py+cell>atlas.getHeight())continue;
            int[] pixels=new int[cell*cell];
            atlas.getPixels(pixels,0,cell,px,py,cell,cell);
            atlas.setPixels(clean(pixels,cell),0,cell,px,py,cell,cell);
        }
    }
}
