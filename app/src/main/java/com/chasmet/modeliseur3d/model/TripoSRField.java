package com.chasmet.modeliseur3d.model;

import java.io.*;

/** Raw learned density in TripoSR's X/Y/Z coordinate system (Z up). */
public final class TripoSRField {
    public static final float ISO=(float)(1+Math.log(25));
    private static final int MAGIC=0x54535232;
    public final int side;
    private final float[] density;
    private final float[] low=new float[3],high=new float[3];
    public TripoSRField(float[] density,int side){
        if(side<16||side>80||density==null||density.length!=side*side*side)throw new IllegalArgumentException("Champ TripoSR invalide.");
        this.density=density;this.side=side;
        int[] min={side,side,side},max={-1,-1,-1};int count=0;
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)for(int z=0;z<side;z++){
            float value=density[(x*side+y)*side+z];if(!Float.isFinite(value))throw new IllegalArgumentException("Sortie TripoSR non finie.");
            if(value>=ISO){count++;int[] p={x,y,z};for(int a=0;a<3;a++){min[a]=Math.min(min[a],p[a]);max[a]=Math.max(max[a],p[a]);}}
        }
        if(count<16||count>density.length*.7f)throw new IllegalArgumentException("TripoSR ne trouve pas une forme exploitable. Vérifie le détourage ou choisis le moteur Silhouettes.");
        for(int a=0;a<3;a++){
            if(max[a]-min[a]<2)throw new IllegalArgumentException("Forme TripoSR trop fine. Vérifie la photo entière.");
            low[a]=-1+2f*Math.max(0,min[a]-1)/(side-1);high[a]=-1+2f*Math.min(side-1,max[a]+1)/(side-1);
        }
    }
    /** Exact trilinear interpolation of the cached raw decoder output. */
    public float sample(float x,float y,float z){
        if(x< -1||x>1||y< -1||y>1||z< -1||z>1)return ISO-20;
        float fx=(x+1)*(side-1)*.5f,fy=(y+1)*(side-1)*.5f,fz=(z+1)*(side-1)*.5f;
        int ix=Math.min(side-2,(int)fx),iy=Math.min(side-2,(int)fy),iz=Math.min(side-2,(int)fz);fx-=ix;fy-=iy;fz-=iz;
        float result=0;
        for(int a=0;a<2;a++)for(int b=0;b<2;b++)for(int c=0;c<2;c++)result+=density[((ix+a)*side+iy+b)*side+iz+c]*(a==0?1-fx:fx)*(b==0?1-fy:fy)*(c==0?1-fz:fz);
        return result;
    }
    private float fit(float v,int axis){return low[axis]+(v+1)*.5f*(high[axis]-low[axis]);}
    /** Face, back, right, left are rotated before combination, never averaged as unaligned codes. */
    public float probability(int view,float x,float y,float z){
        float tx,ty;
        switch(view){case 0:tx=z;ty=x;break;case 1:tx=-z;ty=-x;break;case 2:tx=x;ty=-z;break;case 3:tx=-x;ty=z;break;default:throw new IllegalArgumentException("Vue invalide.");}
        float raw=sample(fit(tx,0),fit(ty,1),fit(y,2));
        return (float)(1/(1+Math.exp(Math.max(-40,Math.min(40,-2*(raw-ISO))))));
    }
    public void write(File file)throws IOException{
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){out.writeInt(MAGIC);out.writeInt(side);for(float f:density)out.writeFloat(f);}
    }
    public static TripoSRField read(File file)throws IOException{
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("Cache TripoSR invalide.");int side=in.readInt();
            if(side<16||side>80||file.length()!=8L+4L*side*side*side)throw new IOException("Cache TripoSR incomplet.");
            float[] values=new float[side*side*side];for(int i=0;i<values.length;i++)values[i]=in.readFloat();
            try{return new TripoSRField(values,side);}catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
        }
    }
}
