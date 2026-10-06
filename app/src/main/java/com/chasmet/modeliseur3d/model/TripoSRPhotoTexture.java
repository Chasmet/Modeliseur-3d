package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Six continuous planar UV islands. Source detail is projected only on visible front
 * surfaces; occluded/back surfaces use the actual neural RGB, never a mirrored photo. */
public final class TripoSRPhotoTexture {
    private static final int MAP=Runtime.getRuntime().maxMemory()<192L*1024*1024?256:512;
    private TripoSRPhotoTexture(){}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static int axisU(int view){return view<2||view>=4?0:2;}
    private static int axisV(int view){return view<4?1:2;}
    private static boolean flipU(int view){return view==1||view==2;}
    private static boolean flipV(int view){return view<4||view==5;}
    private static int depthAxis(int view){return view<2?2:view<4?0:1;}
    private static float sign(int view){return view%2==0?1:-1;}
    private static float coordinate(float value,int axis,boolean flip,float[] bounds){float out=(value-bounds[axis])/(bounds[axis+3]-bounds[axis]);return flip?1-out:out;}
    private static int view(float nx,float ny,float nz){return Math.abs(nz)>=Math.max(Math.abs(nx),Math.abs(ny))?(nz>=0?0:1):Math.abs(nx)>=Math.abs(ny)?(nx>=0?2:3):(ny>=0?4:5);}

    private static final class Maps {
        final float[][] depths=new float[6][],normalZ=new float[6][];
        final float[] bounds=new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        final int[] left=new int[MAP],right=new int[MAP];
        Maps(MeshData mesh){this(mesh,false);}
        Maps(MeshData mesh,boolean frontOnly){
            float[] p=mesh.getPositions(),n=mesh.getNormals();int[] indices=mesh.getIndices();
            for(int i=0;i<p.length;i++){int a=i%3;bounds[a]=Math.min(bounds[a],p[i]);bounds[a+3]=Math.max(bounds[a+3],p[i]);}
            for(int a=0;a<3;a++){float pad=Math.max(.001f,(bounds[a+3]-bounds[a])*.002f);bounds[a]-=pad;bounds[a+3]+=pad;}
            for(int v=0;v<(frontOnly?1:6);v++){
                depths[v]=new float[MAP*MAP];normalZ[v]=new float[MAP*MAP];Arrays.fill(depths[v],Float.NEGATIVE_INFINITY);
                int au=axisU(v),av=axisV(v),ad=depthAxis(v);
                for(int t=0;t<indices.length;t+=3){
                    if((t&3071)==0)check();int a=indices[t]*3,b=indices[t+1]*3,c=indices[t+2]*3;
                    float ax=coordinate(p[a+au],au,flipU(v),bounds)*(MAP-1),ay=coordinate(p[a+av],av,flipV(v),bounds)*(MAP-1);
                    float bx=coordinate(p[b+au],au,flipU(v),bounds)*(MAP-1),by=coordinate(p[b+av],av,flipV(v),bounds)*(MAP-1);
                    float cx=coordinate(p[c+au],au,flipU(v),bounds)*(MAP-1),cy=coordinate(p[c+av],av,flipV(v),bounds)*(MAP-1);
                    float den=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy);if(Math.abs(den)<1e-8f)continue;
                    int x0=Math.max(0,(int)Math.floor(Math.min(ax,Math.min(bx,cx)))),x1=Math.min(MAP-1,(int)Math.ceil(Math.max(ax,Math.max(bx,cx))));
                    int y0=Math.max(0,(int)Math.floor(Math.min(ay,Math.min(by,cy)))),y1=Math.min(MAP-1,(int)Math.ceil(Math.max(ay,Math.max(by,cy))));
                    for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){
                        float wa=((by-cy)*(x-cx)+(cx-bx)*(y-cy))/den,wb=((cy-ay)*(x-cx)+(ax-cx)*(y-cy))/den,wc=1-wa-wb;if(Math.min(wa,Math.min(wb,wc))<-.001f)continue;
                        float dd=sign(v)*(wa*p[a+ad]+wb*p[b+ad]+wc*p[c+ad]);int index=y*MAP+x;
                        if(dd>depths[v][index]){depths[v][index]=dd;normalZ[v][index]=wa*n[a+2]+wb*n[b+2]+wc*n[c+2];}
                    }
                }
            }
            Arrays.fill(left,MAP);Arrays.fill(right,-1);
            for(int y=0;y<MAP;y++)for(int x=0;x<MAP;x++)if(Float.isFinite(depths[0][y*MAP+x])){left[y]=Math.min(left[y],x);right[y]=Math.max(right[y],x);}
        }
        boolean visibleFront(float x,float y,float z){
            return visible(0,x,y,z);
        }
        boolean visible(int view,float x,float y,float z){
            int au=axisU(view),av=axisV(view),ad=depthAxis(view);float pu=au==0?x:au==1?y:z,pv=av==0?x:av==1?y:z,pd=ad==0?x:ad==1?y:z;
            float u=coordinate(pu,au,flipU(view),bounds),v=coordinate(pv,av,flipV(view),bounds);if(u<0||u>1||v<0||v>1)return false;
            float fx=u*(MAP-1),fy=v*(MAP-1);int ix=Math.min(MAP-2,(int)fx),iy=Math.min(MAP-2,(int)fy);fx-=ix;fy-=iy;float nearest=0,weights=0;
            // Interpolate at this point: a neighbourhood maximum wrongly rejects visible
            // pixels on steep noses, lips and folds as if they were behind another surface.
            for(int yy=0;yy<2;yy++)for(int xx=0;xx<2;xx++){
                float dd=depths[view][(iy+yy)*MAP+ix+xx],weight=(xx==0?1-fx:fx)*(yy==0?1-fy:fy);if(Float.isFinite(dd)){nearest+=dd*weight;weights+=weight;}
            }
            return weights>0&&pd*sign(view)>=nearest/weights-2*(bounds[ad+3]-bounds[ad])/(MAP-1);
        }
    }
    private static final class Photo {
        final int w,h;final int[] pixels,left,right;int top,bottom;
        final TripoDetailRegion region;float stableModelLeft,stableModelRight,stablePhotoLeft,stablePhotoRight;boolean stable;
        Photo(Bitmap image,Maps maps,TripoDetailRegion region){
            this.region=region;
            w=image.getWidth();h=image.getHeight();pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);left=new int[h];right=new int[h];Arrays.fill(left,w);Arrays.fill(right,-1);top=h;bottom=-1;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((pixels[y*w+x]>>>24)>128){left[y]=Math.min(left[y],x);right[y]=Math.max(right[y],x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        }
        void register(Maps maps){
            if(region==null||bottom<=top)return;
            float y0=(region.top*(h-1)-top)/(bottom-top),y1=(region.bottom*(h-1)-top)/(bottom-top);
            float[] ml=new float[MAP],mr=new float[MAP],pl=new float[h],pr=new float[h];int mc=0,pc=0;
            for(int row=0;row<MAP;row++)if(row/(float)(MAP-1)>y0+(y1-y0)*.2f&&row/(float)(MAP-1)<y1-(y1-y0)*.2f&&maps.right[row]>maps.left[row]){ml[mc]=maps.left[row];mr[mc++]=maps.right[row];}
            for(int row=0;row<h;row++)if(row/(float)(h-1)>region.top+(region.bottom-region.top)*.2f&&row/(float)(h-1)<region.bottom-(region.bottom-region.top)*.2f&&right[row]>left[row]){pl[pc]=left[row];pr[pc++]=right[row];}
            if(mc<2||pc<2)return;
            Arrays.sort(ml,0,mc);Arrays.sort(mr,0,mc);Arrays.sort(pl,0,pc);Arrays.sort(pr,0,pc);
            stableModelLeft=ml[mc/2];stableModelRight=mr[mc/2];stablePhotoLeft=pl[pc/2];stablePhotoRight=pr[pc/2];
            stable=stableModelRight>stableModelLeft&&stablePhotoRight>stablePhotoLeft;
        }
        // Each row uses a continuous transform. In the selected detail its scale is
        // constant, so a jagged hair/cheek silhouette cannot stretch individual eyes.
        boolean transform(float vv,Maps maps,float[] out){
            if(bottom<=top)return false;
            float py=top+vv*(bottom-top);int row=Math.max(0,Math.min(h-1,Math.round(py))),mr=Math.max(0,Math.min(MAP-1,Math.round(vv*(MAP-1))));
            float ml=0,mrgt=0,pl=0,pr=0,mc=0,pc=0;
            for(int r=Math.max(0,mr-2);r<=Math.min(MAP-1,mr+2);r++)if(maps.right[r]>maps.left[r]){ml+=maps.left[r];mrgt+=maps.right[r];mc++;}
            for(int r=Math.max(0,row-2);r<=Math.min(h-1,row+2);r++)if(right[r]>left[r]){pl+=left[r];pr+=right[r];pc++;}
            if(mc==0||pc==0)return false;ml/=mc;mrgt/=mc;pl/=pc;pr/=pc;
            if(stable){
                float v=py/(h-1),pad=(region.bottom-region.top)*.15f;
                float weight=clamp(Math.min((v-region.top+pad)/pad,(region.bottom+pad-v)/pad));weight=weight*weight*(3-2*weight);
                ml+=weight*(stableModelLeft-ml);mrgt+=weight*(stableModelRight-mrgt);pl+=weight*(stablePhotoLeft-pl);pr+=weight*(stablePhotoRight-pr);
            }
            if(mrgt<=ml||pr<=pl)return false;out[0]=ml;out[1]=mrgt;out[2]=pl;out[3]=pr;out[4]=py;return true;
        }
        boolean project(float x,float y,Maps maps,float[] out,float[] transform){
            float vv=coordinate(y,1,true,maps.bounds);if(!transform(vv,maps,transform))return false;
            float fraction=(coordinate(x,0,false,maps.bounds)*(MAP-1)-transform[0])/(transform[1]-transform[0]);
            out[0]=(transform[2]+fraction*(transform[3]-transform[2]))/(w-1);out[1]=transform[4]/(h-1);
            return fraction>=0&&fraction<=1;
        }
        boolean unproject(float u,float v,Maps maps,float[] out,float[] transform){
            if(bottom<=top)return false;float vv=(v*(h-1)-top)/(bottom-top);
            if(vv<0||vv>1||!transform(vv,maps,transform))return false;
            float fraction=(u*(w-1)-transform[2])/(transform[3]-transform[2]);
            float modelU=(transform[0]+fraction*(transform[1]-transform[0]))/(MAP-1);
            out[0]=maps.bounds[0]+modelU*(maps.bounds[3]-maps.bounds[0]);out[1]=maps.bounds[4]-vv*(maps.bounds[4]-maps.bounds[1]);return true;
        }
        int sample(float x,float y){
            if(x<0||y<0||x>w-1||y>h-1)return 0;
            int ix=Math.min(w-2,(int)x),iy=Math.min(h-2,(int)y);x-=ix;y-=iy;float alpha=0,r=0,g=0,b=0;
            for(int a=0;a<2;a++)for(int bb=0;bb<2;bb++){
                int c=pixels[(iy+bb)*w+ix+a];float weight=(a==0?1-x:x)*(bb==0?1-y:y),aw=weight*(c>>>24);alpha+=aw;r+=((c>>>16)&255)*aw;g+=((c>>>8)&255)*aw;b+=(c&255)*aw;
            }
            if(alpha<1)return 0;return (Math.round(alpha)<<24)|(Math.round(r/alpha)<<16)|(Math.round(g/alpha)<<8)|Math.round(b/alpha);
        }
        int projected(float x,float y,Maps maps,float[] uv,float[] transform){
            return project(x,y,maps,uv,transform)?sample(uv[0]*(w-1),uv[1]*(h-1)):0;
        }
    }
    /** Shared source registration for geometry, UVs and texture: all use the same crop. */
    public static final class FrontProjection {
        private final Maps maps;private final Photo photo;private final float[] transform=new float[5];
        FrontProjection(MeshData mesh,Bitmap image,TripoDetailRegion region){maps=new Maps(mesh,true);photo=new Photo(image,maps,region);photo.register(maps);}
        public boolean source(float x,float y,float[] out){return photo.project(x,y,maps,out,transform);}
        public boolean visible(float x,float y,float z){return maps.visibleFront(x,y,z);}
        public float height(){return maps.bounds[4]-maps.bounds[1];}
    }
    public static FrontProjection frontProjection(MeshData mesh,Bitmap image,TripoDetailRegion region){return new FrontProjection(mesh,image,region);}

    private static int blend(int neural,int photo,float weight){
        int out=0xff000000;for(int shift=0;shift<=16;shift+=8)out|=Math.round(((neural>>>shift)&255)*(1-weight)+((photo>>>shift)&255)*weight)<<shift;return out;
    }
    public static OfflineImageVolume.Result bake(MeshData mesh,TripoSRRefinedField field,Bitmap photo,String method){
        return bake(mesh,field,photo,method,TripoQualityOptions.defaults());
    }
    public static OfflineImageVolume.Result bake(MeshData mesh,TripoSRRefinedField field,Bitmap photo,String method,TripoQualityOptions options){
        return bake(mesh,field,photo,method,options,null);
    }
    public static OfflineImageVolume.Result bake(MeshData mesh,TripoSRRefinedField field,Bitmap photo,String method,TripoQualityOptions options,TripoDetailRegion region){
        int cell=options.effectiveTextureCell(),columns=region==null?3:4;
        Maps maps=new Maps(mesh);Photo source=new Photo(photo,maps,region);source.register(maps);float[] bounds=maps.bounds,p=mesh.getPositions(),n=mesh.getNormals();int[] original=mesh.getIndices();
        float[] outP=new float[original.length*3],outN=new float[outP.length],uv=new float[original.length*2];int[] ids=new int[original.length];int count=0;MeshEdgeMap remap=new MeshEdgeMap();
        float[] projected=new float[2],transform=new float[5];
        for(int t=0;t<original.length;t+=3){
            if((t&3071)==0)check();float nx=0,ny=0,nz=0,x=0,y=0,z=0;for(int k=0;k<3;k++){int at=original[t+k]*3;nx+=n[at];ny+=n[at+1];nz+=n[at+2];x+=p[at]/3;y+=p[at+1]/3;z+=p[at+2]/3;}int v=view(nx,ny,nz);
            // A continuous front chart avoids alternating top/side UV seams around eyes,
            // lips and cloth folds. Occluded triangles still choose a visible neural chart.
            float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            if(nz>length*.12f&&maps.visibleFront(x,y,z))v=0;
            else{
                float best=-Float.MAX_VALUE;float[] normal={nx,ny,nz};
                for(int chart=1;chart<6;chart++)if(maps.visible(chart,x,y,z)){float score=normal[depthAxis(chart)]*sign(chart);if(score>best){best=score;v=chart;}}
            }
            if(region!=null&&v==0){
                boolean inside=true;
                for(int k=0;k<3;k++){int at=original[t+k]*3;if(!source.project(p[at],p[at+1],maps,projected,transform)||!region.contains(projected[0],projected[1])||!maps.visibleFront(p[at],p[at+1],p[at+2])){inside=false;break;}}
                if(inside)v=6;
            }
            for(int k=0;k<3;k++){
                int index=original[t+k],at=index*3;long key=1+(((long)index<<3)|v);int cached=remap.get(key);if(cached>=0){ids[t+k]=cached;continue;}
                int dst=count++;remap.putIfAbsent(key,dst);ids[t+k]=dst;System.arraycopy(p,at,outP,dst*3,3);System.arraycopy(n,at,outN,dst*3,3);
                float u=clamp(coordinate(p[at+axisU(v)],axisU(v),flipU(v),bounds)),vv=clamp(coordinate(p[at+axisV(v)],axisV(v),flipV(v),bounds));
                if(v==6){source.project(p[at],p[at+1],maps,projected,transform);u=clamp((projected[0]-region.left)/(region.right-region.left));vv=clamp((projected[1]-region.top)/(region.bottom-region.top));}
                uv[dst*2]=((v%columns)*cell+2.5f+u*(cell-5))/(cell*columns);uv[dst*2+1]=((v/columns)*cell+2.5f+vv*(cell-5))/(cell*2);
            }
        }
        Bitmap atlas=Bitmap.createBitmap(cell*columns,cell*2,Bitmap.Config.ARGB_8888);
        try{
            int[] row=new int[cell];float[] point=new float[3],surface=new float[2];
            for(int v=0;v<(region==null?6:7);v++)for(int y=0;y<cell;y++){
                check();float vv=clamp((y-2f)/(cell-5));int my=Math.round(vv*(MAP-1));
                for(int x=0;x<cell;x++){
                    float uu=clamp((x-2f)/(cell-5));int mx=Math.round(uu*(MAP-1)),index=my*MAP+mx;
                    if(v==6){
                        float pu=region.left+uu*(region.right-region.left),pv=region.top+vv*(region.bottom-region.top);
                        if(!source.unproject(pu,pv,maps,point,transform)){row[x]=0xff303030;continue;}
                        float mu=coordinate(point[0],0,false,bounds),mv=coordinate(point[1],1,true,bounds);
                        if(!SurfaceDepthSampler.sample(maps.depths[0],maps.normalZ[0],MAP,mu,mv,6*(bounds[5]-bounds[2])/(MAP-1),surface)){row[x]=0xff303030;continue;}
                        point[2]=surface[0];int neural=field.color(point[0],point[1],point[2]),colour=source.sample(pu*(source.w-1),pv*(source.h-1));
                        float facing=clamp((surface[1]-.08f)/.47f);facing=facing*facing*(3-2*facing);
                        row[x]=(colour>>>24)>128?blend(neural,colour,facing*options.photoWeight):neural;continue;
                    }
                    float jump=6*(bounds[depthAxis(v)+3]-bounds[depthAxis(v)])/(MAP-1);
                    boolean sampled=SurfaceDepthSampler.sample(maps.depths[v],maps.normalZ[v],MAP,uu,vv,jump,surface);
                    // Dilation is restricted to unoccupied gutter pixels; no foreground layer is averaged into another.
                    if(!sampled)for(int dy=-2;dy<=2&&!sampled;dy++)for(int dx=-2;dx<=2;dx++){
                        int xx=Math.max(0,Math.min(MAP-1,mx+dx)),yy=Math.max(0,Math.min(MAP-1,my+dy)),nearby=yy*MAP+xx;
                        if(Float.isFinite(maps.depths[v][nearby])){surface[0]=maps.depths[v][nearby];surface[1]=maps.normalZ[v][nearby];sampled=true;break;}
                    }
                    if(!sampled){row[x]=0xff303030;continue;}
                    float dd=surface[0];
                    int au=axisU(v),av=axisV(v);point[au]=bounds[au]+(flipU(v)?1-uu:uu)*(bounds[au+3]-bounds[au]);point[av]=bounds[av]+(flipV(v)?1-vv:vv)*(bounds[av+3]-bounds[av]);point[depthAxis(v)]=dd*sign(v);
                    int neural=field.color(point[0],point[1],point[2]);float facing=surface[1];
                    if(v==0||maps.visibleFront(point[0],point[1],point[2])){
                        int colour=source.projected(point[0],point[1],maps,projected,transform);if((colour>>>24)>128){float weight=clamp((facing-.08f)/.47f);weight=weight*weight*(3-2*weight);neural=blend(neural,colour,weight*options.photoWeight);}
                    }
                    row[x]=neural;
                }
                atlas.setPixels(row,0,cell,(v%columns)*cell,(v/columns)*cell+y,cell,1);
            }
            return new OfflineImageVolume.Result(new MeshData(Arrays.copyOf(outP,count*3),Arrays.copyOf(outN,count*3),Arrays.copyOf(uv,count*2),ids),atlas,method+" · profondeur de texture interpolée · photo "+Math.round(options.photoWeight*100)+" % sur surfaces visibles + couleurs IA cachées · atlas continu "+(cell*columns)+" × "+(cell*2)+(region==null?"":" · zone détail : texture dédiée "+cell+" × "+cell+" · projection stable"));
        }catch(RuntimeException|Error e){atlas.recycle();throw e;}
    }
}
