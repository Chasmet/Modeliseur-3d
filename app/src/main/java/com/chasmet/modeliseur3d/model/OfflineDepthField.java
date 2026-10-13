package com.chasmet.modeliseur3d.model;
import java.io.*;

/** Tiny reusable depth cache, with explicit dimensions and finite normalized samples. */
public final class OfflineDepthField {
    private static final int MAGIC=0x4f444631;
    public final int width,height;
    private final float[] values;
    public OfflineDepthField(float[] values,int width,int height){
        if(width<2||height<2||width>256||height>256||values.length!=width*height)throw new IllegalArgumentException("Carte de profondeur invalide.");
        for(float value:values)if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Valeur de profondeur invalide.");
        this.values=values.clone();this.width=width;this.height=height;
    }
    public float sample(float u,float v){
        float x=Math.max(0,Math.min(1,u))*(width-1),y=Math.max(0,Math.min(1,v))*(height-1);
        int a=(int)x,b=(int)y,c=Math.min(width-1,a+1),d=Math.min(height-1,b+1);float tx=x-a,ty=y-b;
        return (values[b*width+a]*(1-tx)+values[b*width+c]*tx)*(1-ty)+(values[d*width+a]*(1-tx)+values[d*width+c]*tx)*ty;
    }
    public void write(File file)throws IOException{
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){
            out.writeInt(MAGIC);out.writeInt(width);out.writeInt(height);for(float v:values)out.writeFloat(v);
        }
    }
    public static OfflineDepthField read(File file)throws IOException{
        if(file.length()<28||file.length()>12+256L*256*4)throw new IOException("Cache de profondeur incomplet.");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("Cache de profondeur incompatible.");int w=in.readInt(),h=in.readInt();
            if(w<2||h<2||w>256||h>256||file.length()!=12L+w*h*4L)throw new IOException("Dimensions du cache invalides.");
            float[] samples=new float[w*h];for(int i=0;i<samples.length;i++)samples[i]=in.readFloat();
            try{return new OfflineDepthField(samples,w,h);}catch(IllegalArgumentException e){throw new IOException("Cache de profondeur corrompu.",e);}
        }
    }
}
