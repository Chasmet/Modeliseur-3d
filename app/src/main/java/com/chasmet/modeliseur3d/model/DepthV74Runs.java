package com.chasmet.modeliseur3d.model;
import java.util.Arrays;
final class DepthV74Runs{
 static final class Run{final int a,b;Run(int a,int b){this.a=a;this.b=b;}int w(){return b-a+1;}}
 final Run[] r;final int[] at;
 private DepthV74Runs(Run[] r,int[] at){this.r=r;this.at=at;}
 static DepthV74Runs row(boolean[] m,int w,int y){Run[] t=new Run[Math.max(2,w/2+1)];int[] at=new int[w];Arrays.fill(at,-1);int n=0,x=0,o=y*w;
  while(x<w){while(x<w&&!m[o+x])x++;if(x>=w)break;int a=x;while(x+1<w&&m[o+x+1])x++;int b=x;if(n==t.length)t=Arrays.copyOf(t,t.length*2);t[n]=new Run(a,b);for(int p=a;p<=b;p++)at[p]=n;n++;x++;}
  return new DepthV74Runs(Arrays.copyOf(t,n),at);
 }
 static boolean[] union(boolean[] a,boolean[] b,int w,int h){boolean[] u=new boolean[w*h];for(int y=0;y<h;y++){int o=y*w;for(int x=0;x<w;x++)u[o+x]=a[o+x]||b[o+w-1-x];}return u;}
}
