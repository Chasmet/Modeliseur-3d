package com.chasmet.modeliseur3d.model;
import java.util.Arrays;
final class DepthV74View{
 final float[] d,c; final boolean[] ok; final boolean valid;
 private DepthV74View(float[] d,float[] c,boolean[] ok,boolean valid){this.d=d;this.c=c;this.ok=ok;this.valid=valid;}
 static DepthV74View make(float[] raw,float[] rc,boolean[] mask){
  int n=0;for(int i=0;i<raw.length;i++)if(mask[i]&&Float.isFinite(raw[i]))n++;
  float[] d=new float[raw.length],c=new float[raw.length];boolean[] ok=new boolean[raw.length];
  if(n<Math.max(24,raw.length/220))return new DepthV74View(d,c,ok,false);
  float[] v=new float[n];int k=0;for(int i=0;i<raw.length;i++)if(mask[i]&&Float.isFinite(raw[i]))v[k++]=raw[i];Arrays.sort(v);
  float lo=p(v,.08f),hi=p(v,.92f),r=hi-lo,s=Math.max(1f,Math.max(Math.abs(lo),Math.abs(hi)));
  if(!Float.isFinite(r)||r<=s*1e-5f)return new DepthV74View(d,c,ok,false);
  for(int i=0;i<raw.length;i++)if(mask[i]&&Float.isFinite(raw[i])){d[i]=clamp((raw[i]-lo)/r);float q=rc[i];c[i]=Float.isFinite(q)?clamp(q):.75f;ok[i]=true;}
  return new DepthV74View(d,c,ok,true);
 }
 boolean usable(int i){return i>=0&&i<ok.length&&ok[i];}
 float inset(int i,float max){if(!valid||!usable(i))return 0;return max*(.08f+.92f*d[i])*(.6f+.4f*c[i]);}
 float conf(int i){return valid&&usable(i)?c[i]:0;}
 private static float p(float[] v,float f){float x=f*(v.length-1);int a=(int)x,b=Math.min(v.length-1,a+1);return v[a]*(1-(x-a))+v[b]*(x-a);}
 private static float clamp(float v){return Math.max(0,Math.min(1,v));}
}
