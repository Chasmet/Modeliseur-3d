package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Four independent relative-depth maps refine only observed high-frequency geometry.
 * Zero network access; the TripoSR base shape is never replaced by a depth guess. */
public final class TripoFusionDepthRefiner {
    private TripoFusionDepthRefiner() {}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static float clamp(float a,float min,float max){return Math.max(min,Math.min(max,a));}
    public static final class Result {
        public final MeshData mesh;
        public final int movedVertices;
        public final float maximumMove;
        Result(MeshData mesh,int count,float move){this.mesh=mesh;movedVertices=count;maximumMove=move;}
        public String summary(){return "Depth Anything V2 · 4 cartes · "+movedVertices+" sommets affinés · déplacement max "+String.format(Locale.FRANCE,"%.5f",maximumMove);}
    }

    public static Result refine(MeshData mesh,OfflineDepthField[] fields,Bitmap[] images,
            FourViewCalibration alignment,float frontAspect,float sideAspect,float scale){
        if(mesh==null||fields==null||fields.length!=4||images==null||images.length!=4||alignment==null)
            throw new IllegalArgumentException("Tripo Fusion : quatre cartes de profondeur requises.");
        for(int view=0;view<4;view++)if(fields[view]==null||images[view]==null||images[view].isRecycled())
            throw new IllegalArgumentException("Tripo Fusion : vue ou carte absente.");
        if(!Float.isFinite(frontAspect)||!Float.isFinite(sideAspect)||!Float.isFinite(scale)
                ||frontAspect<=0||sideAspect<=0||scale<=0)throw new IllegalArgumentException("Projection invalide.");
        final float maxMove=.003f;
        float[] source=mesh.getPositions(),proposed=source.clone(),normals=mesh.getNormals();
        int vertices=source.length/3;int[] indices=mesh.getIndices();
        VisibilityPhotoTexture.DepthMaps visible=new VisibilityPhotoTexture.DepthMaps(mesh,128,frontAspect,sideAspect*scale);
        int[] offsets=new int[vertices+1];for(int id:indices)offsets[id+1]++;
        for(int k=1;k<offsets.length;k++)offsets[k]+=offsets[k-1];
        int[] at=offsets.clone(),incidence=new int[indices.length];
        for(int t=0;t<indices.length;t+=3)for(int k=0;k<3;k++)incidence[at[indices[t+k]]++]=t;
        int moved=0;float largest=0;
        for(int v=0;v<vertices;v++){
            if((v&2047)==0)check();
            int i=v*3;float x=source[i],y=source[i+1],z=source[i+2];
            float dx=0,dz=0,weightSum=0;
            for(int view=0;view<4;view++){
                float facing=view==0?normals[i+2]:view==1?-normals[i+2]:view==2?normals[i]:-normals[i];
                if(facing<.70f||!visible.visible(view,x,y,z))continue;
                float u=VisibilityPhotoTexture.u(view,x,z,frontAspect,sideAspect*scale),vv=(1-y)*.5f;
                u=alignment.sourceU(view,u);vv=alignment.sourceV(view,vv);
                u=.5f+(u-.5f)*(view<2?frontAspect:sideAspect)/(images[view].getWidth()/(float)images[view].getHeight());
                vv=(vv-.04f)/.92f;
                if(u<.06f||u>.94f||vv<.06f||vv>.94f)continue;
                OfflineDepthField field=fields[view];float d=field.sample(u,vv),mean=0;
                float du=2f/field.width,dv=2f/field.height;
                for(int yy=-2;yy<=2;yy++)for(int xx=-2;xx<=2;xx++)mean+=field.sample(u+xx*du,vv+yy*dv);
                // Relative maps cannot establish metric depth, so remove the local average.
                float residual=clamp(d-mean/25f,-.20f,.20f);
                float weight=(facing-.70f)/.30f;weight*=weight;
                float delta=residual*.025f*weight;
                if(view==0)dz+=delta;else if(view==1)dz-=delta;
                else if(view==2)dx+=delta;else dx-=delta;
                weightSum+=weight;
            }
            if(weightSum<.05f)continue;
            dx=clamp(dx/Math.max(1,weightSum),-maxMove,maxMove);
            dz=clamp(dz/Math.max(1,weightSum),-maxMove,maxMove);
            float norm=(float)Math.hypot(dx,dz);
            if(norm>maxMove){dx*=maxMove/norm;dz*=maxMove/norm;}
            if(Math.abs(dx)+Math.abs(dz)<1e-7f)continue;
            float attenuation=1;
            for(int k=0;k<12;k++){
                proposed[i]=x+dx*attenuation;proposed[i+2]=z+dz*attenuation;
                if(safe(v,offsets,incidence,indices,source,proposed))break;
                attenuation*=.5f;
            }
            if(!safe(v,offsets,incidence,indices,source,proposed)){proposed[i]=x;proposed[i+2]=z;continue;}
            moved++;largest=Math.max(largest,(float)Math.hypot(dx*attenuation,dz*attenuation));
        }
        if(moved==0)return new Result(mesh,0,0);
        try{
            MeshData candidate=new MeshData(proposed,mesh.getNormals(),mesh.getTexCoords(),indices);
            return new Result(OfflineMeshFinisher.finish(candidate,false,0).mesh,moved,largest);
        }catch(IllegalArgumentException geometryError){
            // Never replace a working reconstruction with damaged experimental geometry.
            return new Result(mesh,0,0);
        }
    }
    private static boolean safe(int v,int[] offsets,int[] incidence,int[] ids,float[] original,float[] proposed){
        for(int at=offsets[v];at<offsets[v+1];at++){
            int t=incidence[at],a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
            double ux=original[b]-original[a],uy=original[b+1]-original[a+1],uz=original[b+2]-original[a+2];
            double vx=original[c]-original[a],vy=original[c+1]-original[a+1],vz=original[c+2]-original[a+2];
            double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            double area=nx*nx+ny*ny+nz*nz;if(area<1e-20)continue;
            ux=proposed[b]-proposed[a];uy=proposed[b+1]-proposed[a+1];uz=proposed[b+2]-proposed[a+2];
            vx=proposed[c]-proposed[a];vy=proposed[c+1]-proposed[a+1];vz=proposed[c+2]-proposed[a+2];
            if(nx*(uy*vz-uz*vy)+ny*(uz*vx-ux*vz)+nz*(ux*vy-uy*vx)<.18*area)return false;
        }
        return true;
    }
}
