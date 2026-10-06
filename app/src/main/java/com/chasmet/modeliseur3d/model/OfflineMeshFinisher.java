package com.chasmet.modeliseur3d.model;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Gentle, bounded Taubin smoothing and consistent winding before UV seams are created.
 * Topology and separate parts are preserved; this never invents a connection or anatomy. */
public final class OfflineMeshFinisher {
    private OfflineMeshFinisher(){}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    public static final class Result {
        public final MeshData mesh;
        public final int components,boundaryEdges,repairedTriangles;
        Result(MeshData mesh,int components,int boundaryEdges,int repaired){this.mesh=mesh;this.components=components;this.boundaryEdges=boundaryEdges;repairedTriangles=repaired;}
    }
    public static Result finish(MeshData source,boolean smoothing,float maximumMove){
        if(!Float.isFinite(maximumMove)||maximumMove<0)throw new IllegalArgumentException("Lissage invalide.");
        float[] original=source.getPositions(),p=original.clone();int[] ids=source.getIndices().clone();int triangles=ids.length/3;
        if(ids.length%3!=0)throw new IllegalArgumentException("Triangles incomplets.");
        for(float value:p)if(!Float.isFinite(value))throw new IllegalArgumentException("Position non finie.");
        int[] adjacent=new int[ids.length];Arrays.fill(adjacent,-1);boolean[] same=new boolean[ids.length];
        MeshEdgeMap edges=new MeshEdgeMap();
        for(int i=0;i<ids.length;i++){
            if((i&8191)==0)check();int t=i/3*3,k=i%3,a=ids[i],b=ids[t+(k+1)%3];
            if(a<0||b<0||a==b||a>=p.length/3||b>=p.length/3)throw new IllegalArgumentException("Indice de maillage invalide.");
            long key=((long)Math.min(a,b)<<32)|Math.max(a,b);int first=edges.putIfAbsent(key,i);
            if(first>=0){if(adjacent[first]>=0)throw new IllegalArgumentException("Maillage non manifold. Réduis le détail ou vérifie les détourages.");adjacent[i]=first;adjacent[first]=i;same[i]=same[first]=ids[first]==a;}
        }
        boolean[] seen=new boolean[triangles],flip=new boolean[triangles];int[] queue=new int[triangles];int components=0,boundaries=0;
        for(int a:adjacent)if(a<0)boundaries++;
        for(int start=0;start<triangles;start++)if(!seen[start]){
            check();components++;int end=1;queue[0]=start;seen[start]=true;boolean closed=true;
            for(int head=0;head<end;head++){
                int t=queue[head];for(int k=0;k<3;k++){
                    int edge=t*3+k,other=adjacent[edge];if(other<0){closed=false;continue;}
                    int next=other/3;boolean wanted=flip[t]^same[edge];
                    if(!seen[next]){seen[next]=true;flip[next]=wanted;queue[end++]=next;}
                    else if(flip[next]!=wanted)throw new IllegalArgumentException("Orientation du maillage incohérente.");
                }
            }
            double volume=0;
            for(int q=0;q<end;q++){
                int t=queue[q]*3,a=ids[t]*3,b=ids[t+(flip[queue[q]]?2:1)]*3,c=ids[t+(flip[queue[q]]?1:2)]*3;
                volume+=(double)p[a]*(p[b+1]*p[c+2]-p[b+2]*p[c+1])+(double)p[a+1]*(p[b+2]*p[c]-p[b]*p[c+2])+(double)p[a+2]*(p[b]*p[c+1]-p[b+1]*p[c]);
            }
            if(closed&&volume<0)for(int q=0;q<end;q++)flip[queue[q]]=!flip[queue[q]];
        }
        int repaired=0;for(int t=0;t<triangles;t++)if(flip[t]){int b=ids[t*3+1];ids[t*3+1]=ids[t*3+2];ids[t*3+2]=b;repaired++;}
        if(smoothing&&maximumMove>0){
            int vertices=p.length/3;int[] degree=new int[vertices];int[][] neighbours=new int[vertices][];
            for(long edge:edges.keys())if(edge!=0){degree[(int)(edge>>>32)]++;degree[(int)edge]++;}
            for(int i=0;i<vertices;i++)neighbours[i]=new int[degree[i]];Arrays.fill(degree,0);
            boolean[] pinned=new boolean[vertices];float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(int i=0;i<p.length;i++){min[i%3]=Math.min(min[i%3],p[i]);max[i%3]=Math.max(max[i%3],p[i]);}
            for(long edge:edges.keys())if(edge!=0){int a=(int)(edge>>>32),b=(int)edge;neighbours[a][degree[a]++]=b;neighbours[b][degree[b]++]=a;}
            for(int i=0;i<adjacent.length;i++)if(adjacent[i]<0){pinned[ids[i]]=true;pinned[ids[i/3*3+(i%3+1)%3]]=true;}
            for(int i=0;i<vertices;i++)for(int axis=0;axis<3;axis++)if(Math.abs(p[i*3+axis]-min[axis])<1e-6||Math.abs(p[i*3+axis]-max[axis])<1e-6)pinned[i]=true;
            float[] next=new float[p.length];
            for(int pass=0;pass<4;pass++){
                float factor=pass%2==0?.25f:-.26f;
                for(int i=0;i<vertices;i++){
                    if((i&2047)==0)check();int at=i*3;
                    if(pinned[i]||neighbours[i].length<3){System.arraycopy(p,at,next,at,3);continue;}
                    for(int axis=0;axis<3;axis++){double sum=0;for(int neighbour:neighbours[i])sum+=p[neighbour*3+axis];next[at+axis]=Math.max(min[axis],Math.min(max[axis],p[at+axis]+factor*((float)(sum/neighbours[i].length)-p[at+axis])));}
                    float dx=next[at]-original[at],dy=next[at+1]-original[at+1],dz=next[at+2]-original[at+2],length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if(length>maximumMove)for(int axis=0;axis<3;axis++)next[at+axis]=original[at+axis]+(next[at+axis]-original[at+axis])*maximumMove/length;
                }
                if(preserveFaces(original,p,next,ids)){float[] swap=p;p=next;next=swap;}
            }
        }
        float[] n=new float[p.length];
        for(int t=0;t<ids.length;t+=3){
            if((t&8191)==0)check();int a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
            float x=p[b]-p[a],y=p[b+1]-p[a+1],z=p[b+2]-p[a+2],xx=p[c]-p[a],yy=p[c+1]-p[a+1],zz=p[c+2]-p[a+2];
            float nx=y*zz-z*yy,ny=z*xx-x*zz,nz=x*yy-y*xx;
            float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<1e-12f)continue;
            // Corner-angle weights resist the very unequal triangle areas of marching tetrahedra.
            float wa=(float)Math.atan2(length,x*xx+y*yy+z*zz);
            float wb=(float)Math.atan2(length,-x*(xx-x)-y*(yy-y)-z*(zz-z));
            float wc=Math.max(0,(float)Math.PI-wa-wb);nx/=length;ny/=length;nz/=length;
            n[a]+=nx*wa;n[a+1]+=ny*wa;n[a+2]+=nz*wa;
            n[b]+=nx*wb;n[b+1]+=ny*wb;n[b+2]+=nz*wb;
            n[c]+=nx*wc;n[c+1]+=ny*wc;n[c+2]+=nz*wc;
        }
        for(int i=0;i<n.length;i+=3){float length=(float)Math.sqrt(n[i]*n[i]+n[i+1]*n[i+1]+n[i+2]*n[i+2]);if(length>1e-12f){n[i]/=length;n[i+1]/=length;n[i+2]/=length;}else{n[i]=0;n[i+1]=0;n[i+2]=1;}}
        return new Result(new MeshData(p,n,source.getTexCoords().clone(),ids),components,boundaries,repaired);
    }
    /** Reject local triangle inversions; rollback a whole pass if neighbouring rollbacks conflict. */
    private static boolean preserveFaces(float[] before,float[] current,float[] after,int[] ids){
        boolean[] pinned=new boolean[before.length/3];
        for(int attempt=0;attempt<4;attempt++){
            boolean good=true;Arrays.fill(pinned,false);
            for(int t=0;t<ids.length;t+=3){
                if((t&8191)==0)check();int a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
                float ux=before[b]-before[a],uy=before[b+1]-before[a+1],uz=before[b+2]-before[a+2],vx=before[c]-before[a],vy=before[c+1]-before[a+1],vz=before[c+2]-before[a+2];
                float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
                ux=after[b]-after[a];uy=after[b+1]-after[a+1];uz=after[b+2]-after[a+2];vx=after[c]-after[a];vy=after[c+1]-after[a+1];vz=after[c+2]-after[a+2];
                float xx=uy*vz-uz*vy,yy=uz*vx-ux*vz,zz=ux*vy-uy*vx;
                double dot=(double)nx*xx+(double)ny*yy+(double)nz*zz,area=(double)nx*nx+(double)ny*ny+(double)nz*nz;
                if(dot<=area*.05){good=false;pinned[a/3]=pinned[b/3]=pinned[c/3]=true;}
            }
            if(good)return true;if(attempt==3)return false;
            for(int i=0;i<pinned.length;i++)if(pinned[i])System.arraycopy(current,i*3,after,i*3,3);
        }
        return false;
    }
}
