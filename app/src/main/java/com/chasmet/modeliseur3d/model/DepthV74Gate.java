package com.chasmet.modeliseur3d.model;
final class DepthV74Gate{
 final Row[] rows;
 private DepthV74Gate(Row[] rows){this.rows=rows;}
 static DepthV74Gate make(boolean[][] m,int w,int h,int d,SubjectCategory cat){
  boolean[] f=DepthV74Runs.union(m[0],m[2],w,h),s=DepthV74Runs.union(m[1],m[3],d,h);int top=h,bot=-1;
  for(int y=0;y<h;y++)if(any(f,w,y)||any(s,d,y)){top=Math.min(top,y);bot=Math.max(bot,y);}if(bot<top){top=0;bot=h-1;}
  Row[] rows=new Row[h];for(int y=0;y<h;y++){float p=bot<=top?.5f:(y-top)/(float)(bot-top);DepthV74Runs xr=DepthV74Runs.row(f,w,y),zr=DepthV74Runs.row(s,d,y);rows[y]=new Row(xr,zr,pairs(cat,p,xr.r.length,zr.r.length));}return new DepthV74Gate(rows);
 }
 static boolean debug(SubjectCategory c,float p,int nx,int nz,int x,int z){boolean[][] a=pairs(c,p,nx,nz);return x>=0&&z>=0&&x<nx&&z<nz&&(a==null||a[x][z]);}
 private static boolean[][] pairs(SubjectCategory c,float p,int nx,int nz){
  if(nx<1||nz<1)return new boolean[Math.max(0,nx)][Math.max(0,nz)];
  boolean composite=c==SubjectCategory.COMPOSITE_VEHICLE&&p<.68f;
  boolean complexLowerComposite=c==SubjectCategory.COMPOSITE_VEHICLE&&p>=.68f&&(nx>=3||nz>=3);
  boolean unique=nx>1&&nz>1&&(composite||complexLowerComposite||(c==SubjectCategory.CHARACTER&&p>=.30f)||c==SubjectCategory.PLANT||(c==SubjectCategory.ANIMAL&&p<.50f));
  if(!unique)return null;
  boolean[][] a=new boolean[nx][nz];int n=Math.max(nx,nz);
  for(int k=0;k<n;k++){int x=Math.round(k*(nx-1)/(float)Math.max(1,n-1));int z=Math.round(k*(nz-1)/(float)Math.max(1,n-1));a[x][z]=true;}
  for(int x=0;x<nx;x++){boolean q=false;for(int z=0;z<nz;z++)q|=a[x][z];if(!q)a[x][Math.round(x*(nz-1)/(float)Math.max(1,nx-1))]=true;}
  for(int z=0;z<nz;z++){boolean q=false;for(int x=0;x<nx;x++)q|=a[x][z];if(!q)a[Math.round(z*(nx-1)/(float)Math.max(1,nz-1))][z]=true;}
  return a;
 }
 private static boolean any(boolean[] m,int w,int y){int o=y*w;for(int x=0;x<w;x++)if(m[o+x])return true;return false;}
 static final class Row{final DepthV74Runs x,z;final boolean[][] a;Row(DepthV74Runs x,DepthV74Runs z,boolean[][] a){this.x=x;this.z=z;this.a=a;}boolean ok(int px,int pz){if(px<0||pz<0||px>=x.at.length||pz>=z.at.length)return false;int i=x.at[px],j=z.at[pz];return i>=0&&j>=0&&(a==null||a[i][j]);}}
}
