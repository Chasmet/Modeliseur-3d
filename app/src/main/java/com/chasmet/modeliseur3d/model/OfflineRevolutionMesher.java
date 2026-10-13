package com.chasmet.modeliseur3d.model;

/** A bounded, closed surface of revolution for upright bottles, vases and similar objects. */
public final class OfflineRevolutionMesher {
    private OfflineRevolutionMesher() {}
    public static MeshData build(boolean[] mask,int width,int height,float aspect,int slices){
        if(width<3||height<3||width>160||height>160||mask.length!=width*height
                ||!Float.isFinite(aspect)||aspect<=0||aspect>8||slices<12||slices>64)
            throw new IllegalArgumentException("Profil de révolution invalide.");
        int top=height,bottom=-1;float[] left=new float[height],right=new float[height],radius=new float[height];
        for(int y=0;y<height;y++){
            int l=width,r=-1;for(int x=0;x<width;x++)if(mask[y*width+x]){l=Math.min(l,x);r=Math.max(r,x);}
            if(r>=l){top=Math.min(top,y);bottom=Math.max(bottom,y);left[y]=l/(float)width;right[y]=(r+1f)/width;radius[y]=(r-l+1f)/width*aspect;}
        }
        if(bottom-top<2)throw new IllegalArgumentException("Objet vertical introuvable.");
        // Fill missing rows and gently smooth the profile while keeping a narrow neck.
        for(int y=top;y<=bottom;y++)if(radius[y]==0){
            int a=y-1,b=y+1;while(b<=bottom&&radius[b]==0)b++;
            float t=(y-a)/(float)(b-a);radius[y]=radius[a]+t*(radius[b]-radius[a]);
            left[y]=left[a]+t*(left[b]-left[a]);right[y]=right[a]+t*(right[b]-right[a]);
        }
        float[] smooth=radius.clone();for(int y=top+1;y<bottom;y++)smooth[y]=(radius[y-1]+2*radius[y]+radius[y+1])*.25f;
        int rows=bottom-top+1,ring=slices+1,vertices=rows*ring+2,upper=vertices-2,lower=vertices-1;
        float[] p=new float[vertices*3],n=new float[vertices*3],uv=new float[vertices*2];
        for(int row=0;row<rows;row++){
            int y=top+row;float v=(y+.5f)/height,yy=1-2*v;
            float previous=smooth[Math.max(top,y-1)],next=smooth[Math.min(bottom,y+1)];
            float slope=(next-previous)/Math.max(1,Math.min(bottom,y+1)-Math.max(top,y-1))*height*.5f;
            for(int s=0;s<=slices;s++){
                // Repeated endpoint forms a UV seam; positions/normals remain exactly coincident.
                double angle=2*Math.PI*(s==slices?0:s)/(double)slices;float x=(float)Math.cos(angle),z=(float)Math.sin(angle);
                int id=row*ring+s;float length=(float)Math.sqrt(1+slope*slope);
                p[id*3]=smooth[y]*x;p[id*3+1]=yy;p[id*3+2]=smooth[y]*z;
                n[id*3]=x/length;n[id*3+1]=slope/length;n[id*3+2]=z/length;
                uv[id*2]=left[y]+(right[y]-left[y])*(x+1)*.5f;uv[id*2+1]=v;
            }
        }
        p[upper*3+1]=p[1];p[lower*3+1]=p[(rows-1)*ring*3+1];n[upper*3+1]=1;n[lower*3+1]=-1;
        uv[upper*2]=(left[top]+right[top])*.5f;uv[upper*2+1]=(top+.5f)/height;
        uv[lower*2]=(left[bottom]+right[bottom])*.5f;uv[lower*2+1]=(bottom+.5f)/height;
        int[] indices=new int[((rows-1)*slices*2+slices*2)*3];int count=0;
        for(int row=0;row<rows-1;row++)for(int s=0;s<slices;s++){
            int a=row*ring+s,b=a+1,c=a+ring,d=c+1;
            indices[count++]=a;indices[count++]=b;indices[count++]=d;
            indices[count++]=a;indices[count++]=d;indices[count++]=c;
        }
        for(int s=0;s<slices;s++){
            indices[count++]=upper;indices[count++]=s+1;indices[count++]=s;
            indices[count++]=lower;indices[count++]=(rows-1)*ring+s;indices[count++]=(rows-1)*ring+s+1;
        }
        return new MeshData(p,n,uv,indices);
    }
}
