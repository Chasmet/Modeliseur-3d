package com.chasmet.modeliseur3d.model;
final class DepthV74Shape{
 final int top,bot;final float[] fr,sr,ff,sf,cx,cz,hx,hz;
 private DepthV74Shape(int t,int b,int h){top=t;bot=b;fr=new float[h];sr=new float[h];ff=new float[h];sf=new float[h];cx=new float[h];cz=new float[h];hx=new float[h];hz=new float[h];}
 static DepthV74Shape make(boolean[][] m,int w,int h,int d){boolean[] f=DepthV74Runs.union(m[0],m[2],w,h),s=DepthV74Runs.union(m[1],m[3],d,h);int top=h,bot=-1,mf=1,ms=1;int[] fs=new int[h],ss=new int[h],fc=new int[h],sc=new int[h];
  for(int y=0;y<h;y++){int[] a=stats(f,w,y),b=stats(s,d,y);fs[y]=a[1];fc[y]=a[2];ss[y]=b[1];sc[y]=b[2];if(a[0]>=0||b[0]>=0){top=Math.min(top,y);bot=Math.max(bot,y);}mf=Math.max(mf,a[1]);ms=Math.max(ms,b[1]);}
  if(bot<top){top=0;bot=h-1;}DepthV74Shape q=new DepthV74Shape(top,bot,h);for(int y=0;y<h;y++){int[] a=stats(f,w,y),b=stats(s,d,y);if(a[0]>=0){q.fr[y]=fs[y]/(float)mf;q.ff[y]=fc[y]/(float)Math.max(1,fs[y]);q.cx[y]=a[0]+(fs[y]-1)*.5f;q.hx[y]=fs[y]*.5f;}if(b[0]>=0){q.sr[y]=ss[y]/(float)ms;q.sf[y]=sc[y]/(float)Math.max(1,ss[y]);q.cz[y]=b[0]+(ss[y]-1)*.5f;q.hz[y]=ss[y]*.5f;}}return q;
 }
 float p(int y){return bot<=top?.5f:Math.max(0,Math.min(1,(y-top)/(float)(bot-top)));}
 private static int[] stats(boolean[] m,int w,int y){int o=y*w,min=w,max=-1,n=0;for(int x=0;x<w;x++)if(m[o+x]){min=Math.min(min,x);max=Math.max(max,x);n++;}return max<min?new int[]{-1,0,0}:new int[]{min,max-min+1,n};}
}
