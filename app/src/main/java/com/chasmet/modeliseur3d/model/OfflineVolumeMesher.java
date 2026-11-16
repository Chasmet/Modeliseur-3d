package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/** Bounded CPU geometry, not a TRELLIS inference. Closed, textured silhouette volume. */
public final class OfflineVolumeMesher {
    private OfflineVolumeMesher() {}
    public static MeshData build(boolean[] mask, int width, int height, float aspect, float thickness, boolean rounded) {
        return build(mask,width,height,aspect,thickness,rounded,null);
    }
    public static MeshData build(boolean[] mask,int width,int height,float aspect,float thickness,boolean rounded,OfflineDepthField depth) {
        if(width<3||height<3||width>160||height>160||mask.length!=width*height
                ||!Float.isFinite(aspect)||aspect<=0||aspect>8||!Float.isFinite(thickness)||thickness<0.01f||thickness>0.6f)
            throw new IllegalArgumentException("Dimensions de volume mobile invalides.");
        int occupied=0;for(boolean value:mask)if(value)occupied++;
        if(occupied<4)throw new IllegalArgumentException("Aucune silhouette utilisable. Choisis une image avec un fond uni ou transparent.");
        // Distance to the real silhouette boundary, including holes and gaps between limbs.
        int[] distance=new int[mask.length];Arrays.fill(distance,10000);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int p=y*width+x;if(!mask[p])distance[p]=0;
            else {distance[p]=Math.min(distance[p],x==0||y==0?1:10000);
                if(x>0)distance[p]=Math.min(distance[p],distance[p-1]+1);
                if(y>0)distance[p]=Math.min(distance[p],distance[p-width]+1);}
        }
        int max=1;
        for(int y=height-1;y>=0;y--)for(int x=width-1;x>=0;x--){
            int p=y*width+x;if(mask[p]){
                if(x==width-1||y==height-1)distance[p]=1;
                if(x+1<width)distance[p]=Math.min(distance[p],distance[p+1]+1);
                if(y+1<height)distance[p]=Math.min(distance[p],distance[p+width]+1);
                max=Math.max(max,distance[p]);}
        }
        int stride=width+1, corners=stride*(height+1), vertices=0;
        int[] ids=new int[corners];Arrays.fill(ids,-1);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(mask[y*width+x]){
            ids[y*stride+x]=0;ids[y*stride+x+1]=0;ids[(y+1)*stride+x]=0;ids[(y+1)*stride+x+1]=0;
        }
        for(int i=0;i<corners;i++)if(ids[i]==0)ids[i]=vertices++;
        float[] positions=new float[vertices*6],normals=new float[vertices*6],uv=new float[vertices*4];
        for(int y=0;y<=height;y++)for(int x=0;x<=width;x++){
            int id=ids[y*stride+x];if(id<0)continue;
            float d=0;int samples=0;
            for(int dy=-1;dy<=0;dy++)for(int dx=-1;dx<=0;dx++){
                int sx=x+dx,sy=y+dy;if(sx>=0&&sx<width&&sy>=0&&sy<height){d+=distance[sy*width+sx];samples++;}}
            d/=Math.max(1,samples);
            float z=rounded?0.012f+thickness*(float)Math.sqrt(Math.min(1,d/max)):thickness;
            float u=x/(float)width,v=y/(float)height;
            for(int side=0;side<2;side++){
                int p=(id+side*vertices)*3,t=(id+side*vertices)*2;
                float front=depth==null?z:(rounded?z*(.45f+.55f*depth.sample(u,v)):thickness*(.12f+.88f*depth.sample(u,v)));
                positions[p]=(u-.5f)*2*aspect;positions[p+1]=1-v*2;positions[p+2]=side==0?front:(depth!=null&&!rounded?-.015f:-z);
                uv[t]=u;uv[t+1]=v;
            }
        }
        int[] faces=new int[occupied*36];int count=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(mask[y*width+x]){
            int a=ids[y*stride+x],b=ids[y*stride+x+1],c=ids[(y+1)*stride+x+1],d=ids[(y+1)*stride+x];
            count=quad(faces,count,a,d,c,b);count=quad(faces,count,a+vertices,b+vertices,c+vertices,d+vertices);
            if(y==0||!mask[(y-1)*width+x])count=quad(faces,count,a,b,b+vertices,a+vertices);
            if(x+1==width||!mask[y*width+x+1])count=quad(faces,count,b,c,c+vertices,b+vertices);
            if(y+1==height||!mask[(y+1)*width+x])count=quad(faces,count,c,d,d+vertices,c+vertices);
            if(x==0||!mask[y*width+x-1])count=quad(faces,count,d,a,a+vertices,d+vertices);
        }
        int[] indices=Arrays.copyOf(faces,count);
        for(int i=0;i<count;i+=3){
            int a=indices[i]*3,b=indices[i+1]*3,c=indices[i+2]*3;
            float x=positions[b]-positions[a],y=positions[b+1]-positions[a+1],z=positions[b+2]-positions[a+2];
            float xx=positions[c]-positions[a],yy=positions[c+1]-positions[a+1],zz=positions[c+2]-positions[a+2];
            float nx=y*zz-z*yy,ny=z*xx-x*zz,nz=x*yy-y*xx;
            for(int vertex=0;vertex<3;vertex++){int p=indices[i+vertex]*3;normals[p]+=nx;normals[p+1]+=ny;normals[p+2]+=nz;}
        }
        for(int i=0;i<normals.length;i+=3){
            float length=(float)Math.sqrt(normals[i]*normals[i]+normals[i+1]*normals[i+1]+normals[i+2]*normals[i+2]);
            if(length>1e-8f){normals[i]/=length;normals[i+1]/=length;normals[i+2]/=length;}else normals[i+2]=1;
        }
        return new MeshData(positions,normals,uv,indices);
    }
    private static int quad(int[] out,int p,int a,int b,int c,int d){out[p++]=a;out[p++]=b;out[p++]=c;out[p++]=a;out[p++]=c;out[p++]=d;return p;}
}
