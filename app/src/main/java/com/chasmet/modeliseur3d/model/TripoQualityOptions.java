package com.chasmet.modeliseur3d.model;

/** Post-processing choices: changing these never invalidates the learned encoder cache. */
public final class TripoQualityOptions {
    public final int textureCell;
    public final float photoWeight;
    public final boolean preserveFineDetail;
    public TripoQualityOptions(int textureCell,float photoWeight,boolean preserveFineDetail) {
        if(textureCell!=512&&textureCell!=768&&textureCell!=1024)throw new IllegalArgumentException("Texture invalide.");
        if(!Float.isFinite(photoWeight)||photoWeight<0||photoWeight>1)throw new IllegalArgumentException("Fidélité photo invalide.");
        this.textureCell=textureCell;this.photoWeight=photoWeight;this.preserveFineDetail=preserveFineDetail;
    }
    public static TripoQualityOptions defaults(){return new TripoQualityOptions(1024,1,true);}
    public int effectiveTextureCell(){return effectiveTextureCell(Runtime.getRuntime().maxMemory());}
    public int effectiveTextureCell(long heap){return Math.min(textureCell,heap<192L*1024*1024?512:heap<384L*1024*1024?768:1024);}
    public int triangleBudget(){return triangleBudget(Runtime.getRuntime().maxMemory());}
    public int triangleBudget(long heap){
        return heap<192L*1024*1024?60000:heap<384L*1024*1024?100000:
                preserveFineDetail?(heap>=768L*1024*1024?320000:240000):180000;
    }
}
