package com.chasmet.modeliseur3d.model;

import java.io.*;

/** Rectangular native neural grid: spend samples on the object, not on empty space.
 * Coordinates, proportions and colours remain in TripoSR's original frame. */
public final class TripoSRRefinedField {
    private static final int MAGIC=0x54535234;
    public final int nx,ny,nz;
    public final float[] bounds;
    private final float[] density;
    private final int[] colors;
    public TripoSRRefinedField(int nx,int ny,int nz,float[] bounds,float[] density,int[] colors){
        if(nx<4||ny<4||nz<4||nx>320||ny>320||nz>320||bounds==null||bounds.length!=6||density==null||density.length!=nx*ny*nz||colors==null||colors.length!=density.length)
            throw new IllegalArgumentException("Grille détaillée invalide.");
        for(int a=0;a<3;a++)if(!Float.isFinite(bounds[a])||!Float.isFinite(bounds[a+3])||bounds[a]<-1||bounds[a+3]>1||bounds[a+3]<=bounds[a])throw new IllegalArgumentException("Bornes détaillées invalides.");
        for(float value:density)if(!Float.isFinite(value))throw new IllegalArgumentException("Densité détaillée non finie.");
        this.nx=nx;this.ny=ny;this.nz=nz;this.bounds=bounds.clone();this.density=density;this.colors=colors;
    }
    public float probability(float x,float y,float z){return (float)(1/(1+Math.exp(Math.max(-40,Math.min(40,-2*(sampleDensity(z,x,y)-TripoSRField.ISO))))));}
    private float sampleDensity(float x,float y,float z){
        if(x<bounds[0]||x>bounds[3]||y<bounds[1]||y>bounds[4]||z<bounds[2]||z>bounds[5])return TripoSRField.ISO-20;
        float fx=(x-bounds[0])/(bounds[3]-bounds[0])*(nx-1),fy=(y-bounds[1])/(bounds[4]-bounds[1])*(ny-1),fz=(z-bounds[2])/(bounds[5]-bounds[2])*(nz-1);
        int ix=Math.min(nx-2,(int)fx),iy=Math.min(ny-2,(int)fy),iz=Math.min(nz-2,(int)fz);fx-=ix;fy-=iy;fz-=iz;float out=0;
        for(int a=0;a<2;a++)for(int b=0;b<2;b++)for(int c=0;c<2;c++)out+=density[((ix+a)*ny+iy+b)*nz+iz+c]*(a==0?1-fx:fx)*(b==0?1-fy:fy)*(c==0?1-fz:fz);
        return out;
    }
    public int color(float modelX,float modelY,float modelZ){
        float fx=Math.max(0,Math.min(nx-1,(modelZ-bounds[0])/(bounds[3]-bounds[0])*(nx-1))),fy=Math.max(0,Math.min(ny-1,(modelX-bounds[1])/(bounds[4]-bounds[1])*(ny-1))),fz=Math.max(0,Math.min(nz-1,(modelY-bounds[2])/(bounds[5]-bounds[2])*(nz-1)));
        int ix=Math.min(nx-2,(int)fx),iy=Math.min(ny-2,(int)fy),iz=Math.min(nz-2,(int)fz);fx-=ix;fy-=iy;fz-=iz;float r=0,g=0,blue=0;
        for(int a=0;a<2;a++)for(int b=0;b<2;b++)for(int c=0;c<2;c++){
            float w=(a==0?1-fx:fx)*(b==0?1-fy:fy)*(c==0?1-fz:fz);int col=colors[((ix+a)*ny+iy+b)*nz+iz+c];r+=((col>>>16)&255)*w;g+=((col>>>8)&255)*w;blue+=(col&255)*w;
        }
        return 0xff000000|(Math.round(r)<<16)|(Math.round(g)<<8)|Math.round(blue);
    }
    public void write(File file)throws IOException{
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){out.writeInt(MAGIC);out.writeInt(nx);out.writeInt(ny);out.writeInt(nz);for(float f:bounds)out.writeFloat(f);for(float f:density)out.writeFloat(f);for(int c:colors)out.writeInt(c);}
    }
    public static TripoSRRefinedField read(File file)throws IOException{
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("Cache détaillé invalide.");int nx=in.readInt(),ny=in.readInt(),nz=in.readInt();
            if(nx<4||ny<4||nz<4||nx>320||ny>320||nz>320||file.length()!=40L+8L*nx*ny*nz)throw new IOException("Cache détaillé incomplet.");
            float[] bounds=new float[6];for(int a=0;a<6;a++)bounds[a]=in.readFloat();float[] density=new float[nx*ny*nz];int[] colors=new int[density.length];for(int a=0;a<density.length;a++)density[a]=in.readFloat();for(int a=0;a<colors.length;a++)colors[a]=in.readInt();
            try{return new TripoSRRefinedField(nx,ny,nz,bounds,density,colors);}catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
        }
    }
}
