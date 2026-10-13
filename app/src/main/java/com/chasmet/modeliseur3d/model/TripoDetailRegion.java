package com.chasmet.modeliseur3d.model;

import org.json.JSONArray;
import org.json.JSONException;
import java.util.Locale;

/** User-selected rectangle in the prepared cutout, never an assumed face detection. */
public final class TripoDetailRegion {
    public final float left,top,right,bottom;
    public TripoDetailRegion(float left,float top,float right,float bottom){
        if(!Float.isFinite(left)||!Float.isFinite(top)||!Float.isFinite(right)||!Float.isFinite(bottom)
                ||left<0||top<0||right>1||bottom>1||right-left<.02f||bottom-top<.02f)
            throw new IllegalArgumentException("Encadre une zone d’au moins 2 % de l’image, à l’intérieur du détourage.");
        this.left=left;this.top=top;this.right=right;this.bottom=bottom;
    }
    public boolean contains(float u,float v){return u>=left&&u<=right&&v>=top&&v<=bottom;}
    public float feather(float u,float v){
        float edge=Math.min(Math.min((u-left)/(right-left),(right-u)/(right-left)),
                Math.min((v-top)/(bottom-top),(bottom-v)/(bottom-top)));
        float t=Math.max(0,Math.min(1,edge/.15f));return t*t*(3-2*t);
    }
    public String cacheKey(){return String.format(Locale.ROOT,"%.6f:%.6f:%.6f:%.6f",left,top,right,bottom);}
    public JSONArray json(){return new JSONArray().put((Object)left).put((Object)top).put((Object)right).put((Object)bottom);}
    public static TripoDetailRegion fromJson(JSONArray value)throws JSONException{
        if(value==null)return null;
        if(value.length()!=4)throw new IllegalArgumentException("Zone de détail : quatre coordonnées normalisées requises.");
        for(int i=0;i<4;i++)if(!(value.get(i) instanceof Number))throw new IllegalArgumentException("Coordonnées de détail numériques requises.");
        return new TripoDetailRegion((float)value.getDouble(0),(float)value.getDouble(1),(float)value.getDouble(2),(float)value.getDouble(3));
    }
}
