package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.Locale;

/** Informational checks only: no photo is rejected based on a heuristic blur score. */
public final class InputPhotoDiagnostics {
    private InputPhotoDiagnostics(){}
    public static String inspect(Bitmap image){
        int w=image.getWidth(),h=image.getHeight();boolean alpha=OfflineImageVolume.hasUsefulTransparency(image);
        int touches=0,foreground=0;double detail=0;int samples=0;
        int[] row=new int[w],previous=new int[w];
        for(int y=0;y<h;y++){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            image.getPixels(row,0,w,0,y,w,1);
            for(int x=0;x<w;x++)if((row[x]>>>24)>128){
                foreground++;if(alpha&&(x==0||y==0||x==w-1||y==h-1))touches++;
                if(x>0&&y>0&&(previous[x]>>>24)>128&&(row[x-1]>>>24)>128){
                    detail+=Math.abs(luma(row[x])-luma(row[x-1]))+Math.abs(luma(row[x])-luma(previous[x]));samples++;
                }
            }
            int[] swap=previous;previous=row;row=swap;
        }
        StringBuilder out=new StringBuilder(w+" × "+h+" pixels · "+(alpha?"fond transparent":"détourage à vérifier"));
        if(Math.max(w,h)<512)out.append("\nConseil : source plus grande pour les textures fines.");
        if(touches>2)out.append("\nAttention : le sujet touche le bord ; une partie peut être coupée.");
        if(alpha)out.append(String.format(Locale.FRANCE,"\nOccupation du sujet : %.0f %%",foreground*100.0/(w*h)));
        if(samples>0&&detail/samples<3)out.append("\nPeu de contraste local : vérifie le flou et les détails. Un objet uni peut aussi donner ce résultat.");
        return out.toString();
    }
    private static float luma(int pixel){return .2126f*((pixel>>16)&255)+.7152f*((pixel>>8)&255)+.0722f*(pixel&255);}
}
