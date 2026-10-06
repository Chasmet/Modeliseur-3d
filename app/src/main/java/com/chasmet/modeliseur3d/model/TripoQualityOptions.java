package com.chasmet.modeliseur3d.model;

/** Post-processing choices: changing these never invalidates the learned encoder cache. */
public final class TripoQualityOptions {
    public final int textureCell;
    public final float photoWeight;
    public final boolean preserveFineDetail;
    public final boolean maximumPower;
    public TripoQualityOptions(int textureCell,float photoWeight,boolean preserveFineDetail) {
        this(textureCell,photoWeight,preserveFineDetail,false);
    }
    public TripoQualityOptions(int textureCell,float photoWeight,boolean preserveFineDetail,boolean maximumPower) {
        if(textureCell!=512&&textureCell!=768&&textureCell!=1024)throw new IllegalArgumentException("Texture invalide.");
        if(!Float.isFinite(photoWeight)||photoWeight<0||photoWeight>1)throw new IllegalArgumentException("Fidélité photo invalide.");
        this.textureCell=textureCell;this.photoWeight=photoWeight;this.preserveFineDetail=preserveFineDetail;this.maximumPower=maximumPower;
    }
    public static TripoQualityOptions defaults(){return new TripoQualityOptions(1024,1,true);}
    public int effectiveTextureCell(){return effectiveTextureCell(Runtime.getRuntime().maxMemory());}
    public int effectiveTextureCell(long heap){return Math.min(textureCell,heap<192L*1024*1024?512:heap<384L*1024*1024?768:1024);}
    public int triangleBudget(){return triangleBudget(Runtime.getRuntime().maxMemory());}
    public int triangleBudget(long heap){
        if(maximumPower&&preserveFineDetail&&heap>=512L*1024*1024)return heap>=768L*1024*1024?480000:384000;
        return heap<192L*1024*1024?60000:heap<384L*1024*1024?100000:
                preserveFineDetail?(heap>=768L*1024*1024?320000:240000):180000;
    }
    /** Leave room for conforming subdivisions instead of filling the budget with the body. */
    public int baseTriangleBudget(boolean closeup){return baseTriangleBudget(closeup,Runtime.getRuntime().maxMemory());}
    public int baseTriangleBudget(boolean closeup,long heap){int total=triangleBudget(heap);return closeup?Math.max(60000,total-total/5):total;}
    public int neuralResolution(int quality){if(quality<0||quality>2)throw new IllegalArgumentException("Qualité invalide.");return maximumPower&&preserveFineDetail&&quality==2?320:new int[]{128,192,256}[quality];}
}
