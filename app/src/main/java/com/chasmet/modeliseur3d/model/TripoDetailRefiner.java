package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Conforming local subdivision followed by bounded relative-depth relief on visible surfaces. */
public final class TripoDetailRefiner {
    private TripoDetailRefiner(){ }
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static long edge(int a,int b){return ((long)Math.min(a,b)<<32)|Math.max(a,b);}
    public static final class Result {
        public final MeshData mesh;public final int addedTriangles,movedVertices;public final float maximumMove;
        Result(MeshData mesh,int added,int moved,float maximum){this.mesh=mesh;addedTriangles=added;movedVertices=moved;maximumMove=maximum;}
        public String summary(){return "Depth Anything V2 en gros plan · "+addedTriangles+" triangles locaux ajoutés · "+movedVertices+" sommets affinés · déplacement maximal "+String.format(Locale.FRANCE,"%.5f",maximumMove);}
    }
    public static Result refine(MeshData original,Bitmap photo,TripoDetailRegion region,OfflineDepthField depth,
            float strength,int triangleBudget){
        if(region==null||depth==null||!Float.isFinite(strength)||strength<0||strength>1)throw new IllegalArgumentException("Assistant de détail invalide.");
        if(strength==0)return new Result(original,0,0,0);
        TripoSRPhotoTexture.FrontProjection projection=TripoSRPhotoTexture.frontProjection(original,photo,region);
        MeshData mesh=original;
        for(int pass=0;pass<2;pass++){check();MeshData next=subdivide(mesh,projection,region,triangleBudget);if(next==mesh)break;mesh=next;}
        float[] before=mesh.getPositions(),positions=before.clone(),normals=mesh.getNormals(),uv=new float[2];
        float limit=projection.height()*(region.bottom-region.top)*.04f*strength;
        for(int i=0;i<positions.length;i+=3){
            if((i&4095)==0)check();
            if(normals[i+2]<.2f||!projection.visible(before[i],before[i+1],before[i+2])||!projection.source(before[i],before[i+1],uv)||!region.contains(uv[0],uv[1]))continue;
            float u=(uv[0]-region.left)/(region.right-region.left),v=(uv[1]-region.top)/(region.bottom-region.top);
            // Preserve TripoSR's large-scale head shape; only bounded local depth contrast is added.
            float low=0,weight=0;
            for(int yy=-2;yy<=2;yy++)for(int xx=-2;xx<=2;xx++){float w=1f/(1+xx*xx+yy*yy);low+=depth.sample(u+xx*.055f,v+yy*.055f)*w;weight+=w;}
            float residual=depth.sample(u,v)-low/weight;
            float facing=Math.max(0,Math.min(1,(normals[i+2]-.2f)/.4f));
            float movement=Math.max(-limit,Math.min(limit,residual*limit*3))*region.feather(uv[0],uv[1])*facing;
            positions[i+2]+=movement;
        }
        // Reject triangle reversals rather than allowing a crop inference to damage topology.
        boolean[] rollback=new boolean[positions.length/3];int[] ids=mesh.getIndices();
        for(int pass=0;pass<4;pass++){
            Arrays.fill(rollback,false);boolean good=true;
            for(int t=0;t<ids.length;t+=3){
                if((t&8191)==0)check();int a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
                float ux=before[b]-before[a],uy=before[b+1]-before[a+1],uz=before[b+2]-before[a+2],vx=before[c]-before[a],vy=before[c+1]-before[a+1],vz=before[c+2]-before[a+2];
                float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
                uz=positions[b+2]-positions[a+2];vz=positions[c+2]-positions[a+2];
                float xx=uy*vz-uz*vy,yy=uz*vx-ux*vz;
                if((double)nx*xx+(double)ny*yy+(double)nz*nz<.1*((double)nx*nx+(double)ny*ny+(double)nz*nz)){good=false;rollback[a/3]=rollback[b/3]=rollback[c/3]=true;}
            }
            if(good)break;
            if(pass==3){System.arraycopy(before,0,positions,0,before.length);break;}
            for(int i=0;i<rollback.length;i++)if(rollback[i])positions[i*3+2]=before[i*3+2];
        }
        int moved=0;float maximum=0;
        for(int i=2;i<positions.length;i+=3){float move=Math.abs(positions[i]-before[i]);if(move>1e-7f)moved++;maximum=Math.max(maximum,move);}
        MeshData finished=OfflineMeshFinisher.finish(new MeshData(positions,mesh.getNormals(),mesh.getTexCoords(),ids),false,0).mesh;
        return new Result(finished,finished.getTriangleCount()-original.getTriangleCount(),moved,maximum);
    }
    private static MeshData subdivide(MeshData mesh,TripoSRPhotoTexture.FrontProjection projection,TripoDetailRegion region,int budget){
        float[] p=mesh.getPositions(),n=mesh.getNormals();int[] ids=mesh.getIndices();Map<Long,Integer> midpoints=new HashMap<>();
        float[] point=new float[2],u=new float[3],v=new float[3];
        for(int t=0;t<ids.length;t+=3){
            if((t&8191)==0)check();boolean selected=true;float cx=0,cy=0,cz=0,nz=0;
            for(int k=0;k<3;k++){int a=ids[t+k]*3;cx+=p[a]/3;cy+=p[a+1]/3;cz+=p[a+2]/3;nz+=n[a+2]/3;
                if(!projection.source(p[a],p[a+1],point)||!region.contains(point[0],point[1])){selected=false;break;}
                u[k]=(point[0]-region.left)/(region.right-region.left);v[k]=(point[1]-region.top)/(region.bottom-region.top);
            }
            if(!selected||nz<.3f||!projection.visible(cx,cy,cz))continue;
            float longest=0;for(int k=0;k<3;k++){int j=(k+1)%3;float dx=u[k]-u[j],dy=v[k]-v[j];longest=Math.max(longest,dx*dx+dy*dy);}
            if(longest<1f/(48*48))continue;
            for(int k=0;k<3;k++)midpoints.put(edge(ids[t+k],ids[t+(k+1)%3]),0);
        }
        if(midpoints.isEmpty())return mesh;
        long triangles=mesh.getTriangleCount();
        for(int t=0;t<ids.length;t+=3)for(int k=0;k<3;k++)if(midpoints.containsKey(edge(ids[t+k],ids[t+(k+1)%3])))triangles++;
        if(triangles>budget)return mesh;
        int original=p.length/3,total=original+midpoints.size();float[] outP=Arrays.copyOf(p,total*3),outN=Arrays.copyOf(n,total*3),outUV=Arrays.copyOf(mesh.getTexCoords(),total*2);int vertex=original;
        for(Map.Entry<Long,Integer> item:midpoints.entrySet()){
            int a=(int)(item.getKey()>>>32),b=(int)(long)item.getKey(),dst=vertex++;item.setValue(dst);
            for(int axis=0;axis<3;axis++){outP[dst*3+axis]=(p[a*3+axis]+p[b*3+axis])*.5f;outN[dst*3+axis]=(n[a*3+axis]+n[b*3+axis])*.5f;}
            float length=(float)Math.sqrt(outN[dst*3]*outN[dst*3]+outN[dst*3+1]*outN[dst*3+1]+outN[dst*3+2]*outN[dst*3+2]);if(length>0)for(int axis=0;axis<3;axis++)outN[dst*3+axis]/=length;
        }
        int[] out=new int[(int)triangles*3];int offset=0;
        for(int t=0;t<ids.length;t+=3){
            int a=ids[t],b=ids[t+1],c=ids[t+2],ab=midpoints.getOrDefault(edge(a,b),-1),bc=midpoints.getOrDefault(edge(b,c),-1),ca=midpoints.getOrDefault(edge(c,a),-1);
            int count=(ab>=0?1:0)+(bc>=0?1:0)+(ca>=0?1:0);
            if(count==0){out[offset++]=a;out[offset++]=b;out[offset++]=c;continue;}
            if(count==3){offset=triangle(out,offset,a,ab,ca);offset=triangle(out,offset,ab,b,bc);offset=triangle(out,offset,ca,bc,c);offset=triangle(out,offset,ab,bc,ca);continue;}
            while(count==1?ab<0:ab<0||bc<0){int swap=a;a=b;b=c;c=swap;swap=ab;ab=bc;bc=ca;ca=swap;}
            if(count==1){offset=triangle(out,offset,a,ab,c);offset=triangle(out,offset,ab,b,c);}
            else{offset=triangle(out,offset,b,bc,ab);offset=triangle(out,offset,a,ab,c);offset=triangle(out,offset,ab,bc,c);}
        }
        return new MeshData(outP,outN,outUV,out);
    }
    private static int triangle(int[] out,int offset,int a,int b,int c){out[offset++]=a;out[offset++]=b;out[offset++]=c;return offset;}
}
