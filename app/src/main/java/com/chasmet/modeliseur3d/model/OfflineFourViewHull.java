package com.chasmet.modeliseur3d.model;

/** Pure CPU visual hull. Order: front, back, right (+X), left (-X). */
public final class OfflineFourViewHull {
    private OfflineFourViewHull() {}
    public static boolean[] intersect(boolean[][] masks,int width,int height,int depth) {
        if(width<4||height<4||depth<4||width>128||height>128||depth>128)
            throw new IllegalArgumentException("Grille mobile invalide.");
        if(masks==null||masks.length!=4)throw new IllegalArgumentException("Les quatre silhouettes sont requises.");
        for(int i=0;i<4;i++)if(masks[i]==null||masks[i].length!=height*(i<2?width:depth))
            throw new IllegalArgumentException("Silhouette invalide.");
        boolean[] volume=new boolean[width*height*depth];int count=0;
        for(int y=1;y<height-1;y++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            for(int x=1;x<width-1;x++) {
                if(!masks[0][y*width+x]||!masks[1][y*width+width-1-x])continue;
                for(int z=1;z<depth-1;z++)if(masks[2][y*depth+depth-1-z]&&masks[3][y*depth+z]) {
                    volume[(y*width+x)*depth+z]=true;count++;
                }
            }
        }
        if(count<24)throw new IllegalArgumentException("Les quatre silhouettes ne se recoupent pas. Utilise le même objet entier, dans la même pose, sur les quatre vues.");
        return volume;
    }
}
