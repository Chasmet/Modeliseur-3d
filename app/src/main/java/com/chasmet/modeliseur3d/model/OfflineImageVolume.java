package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Small bitmap buffers and a capped grid. All processing remains on the phone. */
public final class OfflineImageVolume {
    public static final class Result {
        public final MeshData mesh;public final Bitmap texture;public final String method;
        Result(MeshData mesh,Bitmap texture,String method){this.mesh=mesh;this.texture=texture;this.method=method;}
    }
    public static final class Prepared implements AutoCloseable {
        public final Bitmap bitmap;public final String method;
        Prepared(Bitmap bitmap,String method){this.bitmap=bitmap;this.method=method;}
        @Override public void close(){bitmap.recycle();}
    }
    private OfflineImageVolume() {}
    public static Result generate(Bitmap source,int detail,int tolerance,float depth,boolean rounded,AnimeSegmentationEngine.Mask ai) {
        try(Prepared prepared=prepare(source,tolerance,ai)){
            return buildPrepared(prepared.bitmap,detail,depth,rounded?0:1,prepared.method);
        }
    }
    /** The reusable cutout is independent of detail, thickness and shape. */
    public static Prepared prepare(Bitmap source,int tolerance,AnimeSegmentationEngine.Mask ai) {
        int w=source.getWidth(),h=source.getHeight();
        if(w>1024||h>1024||w<3||h<3)throw new IllegalArgumentException("Image locale limitée à 1 024 pixels par côté.");
        int[] pixels=new int[w*h];source.getPixels(pixels,0,w,0,0,w,h);
        boolean transparent=hasUsefulTransparency(source);
        boolean[] mask=new boolean[pixels.length];String method;
        if(ai!=null){method="Détourage IA local IS-Net";
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)mask[y*w+x]=Color.alpha(pixels[y*w+x])>40&&ai.sampleNormalized(x/(float)(w-1),y/(float)(h-1))>=.5f;
        }else if(transparent){method="Transparence PNG";for(int i=0;i<pixels.length;i++)mask[i]=Color.alpha(pixels[i])>40;
        }else{
            method="Fond uni retiré localement";
            long red=0,green=0,blue=0;int samples=0;
            for(int x=0;x<w;x++){for(int p:new int[]{pixels[x],pixels[(h-1)*w+x]}){red+=Color.red(p);green+=Color.green(p);blue+=Color.blue(p);samples++;}}
            for(int y=1;y<h-1;y++){for(int p:new int[]{pixels[y*w],pixels[y*w+w-1]}){red+=Color.red(p);green+=Color.green(p);blue+=Color.blue(p);samples++;}}
            int r=(int)(red/samples),g=(int)(green/samples),b=(int)(blue/samples),limit=Math.max(8,Math.min(140,tolerance));
            boolean[] background=new boolean[pixels.length];int[] queue=new int[pixels.length];int head=0,tail=0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(x==0||y==0||x==w-1||y==h-1){
                int i=y*w+x;if(similar(pixels[i],r,g,b,limit)){background[i]=true;queue[tail++]=i;}}
            while(head<tail){int i=queue[head++],x=i%w,y=i/w;
                for(int direction=0;direction<4;direction++){
                    int next=direction==0?(x>0?i-1:-1):direction==1?(x+1<w?i+1:-1):direction==2?(y>0?i-w:-1):(y+1<h?i+w:-1);
                    if(next>=0&&!background[next]&&similar(pixels[next],r,g,b,limit)){background[next]=true;queue[tail++]=next;}}}
            for(int i=0;i<mask.length;i++)mask[i]=!background[i];
        }
        int left=w,top=h,right=-1,bottom=-1,count=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(mask[y*w+x]){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);count++;}
        if(count<40||right-left<3||bottom-top<3)throw new IllegalArgumentException("Silhouette introuvable. Essaie un PNG détouré, un fond uni ou le détourage IA local.");
        if(ai==null&&!transparent&&count>pixels.length*.98f)throw new IllegalArgumentException("Le fond est trop complexe. Active le détourage IA local ou utilise un PNG transparent.");
        left=Math.max(0,left-2);top=Math.max(0,top-2);right=Math.min(w-1,right+2);bottom=Math.min(h-1,bottom+2);
        int cw=right-left+1,ch=bottom-top+1;
        int[] crop=new int[cw*ch];for(int y=0;y<ch;y++)for(int x=0;x<cw;x++){
            int i=(top+y)*w+left+x;crop[y*cw+x]=mask[i]?pixels[i]:Color.TRANSPARENT;}
        Bitmap texture=Bitmap.createBitmap(cw,ch,Bitmap.Config.ARGB_8888);texture.setPixels(crop,0,cw,0,0,cw,ch);
        return new Prepared(texture,method);
    }
    /** Uses a checked, cached local PNG cutout; no inference is repeated for adjustments. */
    public static Result buildPrepared(Bitmap source,int detail,float depth,int shape,String method){
        return buildPrepared(source,detail,depth,shape,method,null);
    }
    public static Result buildPrepared(Bitmap source,int detail,float depth,int shape,String method,OfflineDepthField field){
        int cw=source.getWidth(),ch=source.getHeight();
        if(cw<3||ch<3||cw>1024||ch>1024||shape<0||shape>2)throw new IllegalArgumentException("Détourage local invalide.");
        int[] pixels=new int[cw*ch];source.getPixels(pixels,0,cw,0,0,cw,ch);
        int cap=Runtime.getRuntime().maxMemory()<192L*1024*1024?80:144;
        int longest=Math.max(48,Math.min(cap,detail));
        int gw=Math.max(6,Math.round(longest*cw/(float)Math.max(cw,ch))),gh=Math.max(6,Math.round(longest*ch/(float)Math.max(cw,ch)));
        boolean[] grid=new boolean[gw*gh];
        for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
            int hits=0;for(int dy=0;dy<3;dy++)for(int dx=0;dx<3;dx++){
                int sx=Math.min(cw-1,(int)((x+(dx+.5f)/3)*cw/gw));
                int sy=Math.min(ch-1,(int)((y+(dy+.5f)/3)*ch/gh));if(Color.alpha(pixels[sy*cw+sx])>40)hits++;}
            grid[y*gw+x]=hits>=3;
        }
        MeshData mesh=shape==2?OfflineRevolutionMesher.build(grid,gw,gh,cw/(float)ch,Math.min(64,longest/2))
                :OfflineVolumeMesher.build(grid,gw,gh,cw/(float)ch,depth,shape==0,field);
        Bitmap texture=source.copy(Bitmap.Config.ARGB_8888,false);
        if(texture==null)throw new IllegalStateException("Texture locale indisponible.");
        return new Result(mesh,texture,method+(field!=null&&shape!=2?" + profondeur IA locale":"")+(shape==2?" • objet de révolution, symétrie supposée":" • volume approximatif, dos déduit"));
    }
    private static boolean similar(int p,int r,int g,int b,int tolerance){int dr=Color.red(p)-r,dg=Color.green(p)-g,db=Color.blue(p)-b;return dr*dr+dg*dg+db*db<=tolerance*tolerance;}
    /** Ignore isolated transparent pixels; require a meaningful transparent background. */
    public static boolean hasUsefulTransparency(Bitmap bitmap){
        int w=bitmap.getWidth(),h=bitmap.getHeight(),clear=0,border=0,borderClear=0;
        int[] row=new int[w];
        for(int y=0;y<h;y++){
            bitmap.getPixels(row,0,w,0,y,w,1);
            for(int x=0;x<w;x++){
                boolean empty=(row[x]>>>24)<40;if(empty)clear++;
                if(x==0||y==0||x==w-1||y==h-1){border++;if(empty)borderClear++;}
            }
        }
        return clear>=Math.max(8,w*h/200)&&borderClear>=border*.1f;
    }
}
