package com.chasmet.modeliseur3d.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Closed marching-tetrahedra surface, with canonical shared edge vertices. */
public final class OfflineHullMesher {
    private static final float ISO=.37f;
    private static final int[][] CORNERS={{0,0,0},{1,0,0},{1,1,0},{0,1,0},{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
    private static final int[][] TETRA={{0,5,1,6},{0,1,2,6},{0,2,3,6},{0,3,7,6},{0,7,4,6},{0,4,5,6}};
    private OfflineHullMesher(){}
    private static int index(int x,int y,int z,int w,int d){return (y*w+x)*d+z;}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static final class Floats {
        float[] values=new float[16384];int size;
        void add(float a,float b,float c){if(size+3>values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=a;values[size++]=b;values[size++]=c;}
        float[] array(){return Arrays.copyOf(values,size);}
    }
    private static final class Ints {
        int[] values=new int[16384];int size;
        void add(int a,int b,int c){if(size+3>values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=a;values[size++]=b;values[size++]=c;}
    }
    private static float[] smooth(boolean[] occupied,int w,int h,int d){
        float[] field=new float[occupied.length],temporary=new float[field.length];
        for(int i=0;i<field.length;i++)field[i]=occupied[i]?1:0;
        for(int axis=0;axis<3;axis++){
            for(int y=0;y<h;y++){check();for(int x=0;x<w;x++)for(int z=0;z<d;z++){
                int low=index(axis==0?Math.max(0,x-1):x,axis==1?Math.max(0,y-1):y,axis==2?Math.max(0,z-1):z,w,d);
                int high=index(axis==0?Math.min(w-1,x+1):x,axis==1?Math.min(h-1,y+1):y,axis==2?Math.min(d-1,z+1):z,w,d);
                int i=index(x,y,z,w,d);temporary[i]=(field[low]+2*field[i]+field[high])*.25f;
            }}
            float[] swap=field;field=temporary;temporary=swap;
        }
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++)if(y==0||y==h-1||x==0||x==w-1||z==0||z==d-1)field[index(x,y,z,w,d)]=0;
        return field;
    }
    private static float gradient(float[] f,int i,int axis,int w,int h,int d){
        int x=i/d%w,y=i/(w*d),z=i%d;
        int low=index(axis==0?Math.max(0,x-1):x,axis==1?Math.max(0,y-1):y,axis==2?Math.max(0,z-1):z,w,d);
        int high=index(axis==0?Math.min(w-1,x+1):x,axis==1?Math.min(h-1,y+1):y,axis==2?Math.min(d-1,z+1):z,w,d);
        return f[high]-f[low];
    }
    private static int vertex(int a,int b,float[] f,int w,int h,int d,Map<Long,Integer> edges,Floats p,Floats n,float iso){
        if(a>b){int swap=a;a=b;b=swap;}long key=((long)a<<32)|b;Integer found=edges.get(key);if(found!=null)return found;
        float t=(iso-f[a])/(f[b]-f[a]);
        float x=a/d%w+(b/d%w-a/d%w)*t,y=a/(w*d)+(b/(w*d)-a/(w*d))*t,z=a%d+(b%d-a%d)*t;
        int id=p.size/3;edges.put(key,id);
        p.add((x/(w-1)*2-1)*w/h,1-2*y/(h-1),(z/(d-1)*2-1)*d/h);
        float nx=-(gradient(f,a,0,w,h,d)*(1-t)+gradient(f,b,0,w,h,d)*t);
        float ny=gradient(f,a,1,w,h,d)*(1-t)+gradient(f,b,1,w,h,d)*t;
        float nz=-(gradient(f,a,2,w,h,d)*(1-t)+gradient(f,b,2,w,h,d)*t);
        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(length<1e-9f)n.add(0,0,1);else n.add(nx/length,ny/length,nz/length);return id;
    }
    private static void triangle(int a,int b,int c,Floats p,Floats n,Ints triangles){
        int i=a*3,j=b*3,k=c*3;float abx=p.values[j]-p.values[i],aby=p.values[j+1]-p.values[i+1],abz=p.values[j+2]-p.values[i+2];
        float acx=p.values[k]-p.values[i],acy=p.values[k+1]-p.values[i+1],acz=p.values[k+2]-p.values[i+2];
        float nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
        float dot=nx*(n.values[i]+n.values[j]+n.values[k])+ny*(n.values[i+1]+n.values[j+1]+n.values[k+1])+nz*(n.values[i+2]+n.values[j+2]+n.values[k+2]);
        if(dot<0)triangles.add(a,c,b);else triangles.add(a,b,c);
    }
    public static MeshData build(boolean[] occupied,int w,int h,int d){
        if(w<4||h<4||d<4||w>128||h>128||d>128||occupied==null||occupied.length!=w*h*d)throw new IllegalArgumentException("Volume mobile invalide.");
        return buildSurface(smooth(occupied,w,h,d),w,h,d,ISO,0);
    }
    /** Continuous learned density probabilities, without binary silhouette smoothing. */
    public static MeshData buildField(float[] values,int w,int h,int d){
        return buildField(values,w,h,d,128,120000);
    }
    /** Object-centred grids are bounded separately from the legacy full-cube path. */
    public static MeshData buildDetailedField(float[] values,int w,int h,int d){long heap=Runtime.getRuntime().maxMemory();return buildField(values,w,h,d,256,heap<192L*1024*1024?60000:heap<384L*1024*1024?100000:180000);}
    public static MeshData buildDetailedField(float[] values,int w,int h,int d,int triangleBudget){
        if(triangleBudget<60000||triangleBudget>320000)throw new IllegalArgumentException("Budget de maillage invalide.");
        return buildField(values,w,h,d,256,triangleBudget);
    }
    private static MeshData buildField(float[] values,int w,int h,int d,int limit,int triangleLimit){
        if(w<4||h<4||d<4||w>limit||h>limit||d>limit||values==null||values.length!=w*h*d)throw new IllegalArgumentException("Champ mobile invalide.");
        float[] f=values.clone();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){int i=index(x,y,z,w,d);
            if(!Float.isFinite(f[i])||f[i]<0||f[i]>1)throw new IllegalArgumentException("Probabilité IA invalide.");
            if(y==0||y==h-1||x==0||x==w-1||z==0||z==d-1)f[i]=0;
        }
        return buildSurface(f,w,h,d,.5f,triangleLimit);
    }
    public static final class TooComplexException extends IllegalArgumentException {
        TooComplexException(){super("Forme trop complexe pour ce détail mobile.");}
    }
    private static MeshData buildSurface(float[] f,int w,int h,int d,float iso,int triangleLimit){
        Floats p=new Floats(),n=new Floats();Ints triangles=new Ints();
        Map<Long,Integer> edges=new HashMap<>();int[] cube=new int[8],inside=new int[4],outside=new int[4];
        for(int y=0;y<h-1;y++){check();if(triangleLimit>0&&triangles.size>triangleLimit*3)throw new TooComplexException();for(int x=0;x<w-1;x++)for(int z=0;z<d-1;z++){
            for(int k=0;k<8;k++)cube[k]=index(x+CORNERS[k][0],y+CORNERS[k][1],z+CORNERS[k][2],w,d);
            for(int[] tetra:TETRA){
                int ni=0,no=0;for(int corner:tetra){int i=cube[corner];if(f[i]>=iso)inside[ni++]=i;else outside[no++]=i;}
                if(ni==0||ni==4)continue;
                if(ni==1||ni==3){int point=ni==1?inside[0]:outside[0];int[] other=ni==1?outside:inside;
                    int a=vertex(point,other[0],f,w,h,d,edges,p,n,iso),b=vertex(point,other[1],f,w,h,d,edges,p,n,iso),c=vertex(point,other[2],f,w,h,d,edges,p,n,iso);triangle(a,b,c,p,n,triangles);
                }else{
                    int a=vertex(inside[0],outside[0],f,w,h,d,edges,p,n,iso),b=vertex(inside[0],outside[1],f,w,h,d,edges,p,n,iso);
                    int c=vertex(inside[1],outside[0],f,w,h,d,edges,p,n,iso),e=vertex(inside[1],outside[1],f,w,h,d,edges,p,n,iso);
                    triangle(a,b,c,p,n,triangles);triangle(b,e,c,p,n,triangles);
                }
            }
        }}
        if(triangleLimit>0&&triangles.size>triangleLimit*3)throw new TooComplexException();
        return new MeshData(p.array(),n.array(),new float[p.size/3*2],Arrays.copyOf(triangles.values,triangles.size));
    }
}
