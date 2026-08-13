package com.chasmet.modeliseur3d.model;
/** V7.4: DA3 + correspondance 3D des composantes. */
public final class MultiViewDepthFusion{
 private static final float ISO=.5f;private MultiViewDepthFusion(){}
 public static Result refine(float[] b,boolean[][] m,float[][] d,float[][] c,int w,int h,int z){return refine(b,m,d,c,w,h,z,SubjectCategory.CHARACTER);}
 public static Result refine(float[] b,boolean[][] m,float[][] d,float[][] c,int w,int h,int z,SubjectCategory cat){return DepthV74Engine.run(b,m,d,c,w,h,z,cat);}
 static float debugInsetFraction(SubjectCategory c,float p,float f,float s){return DepthFusionPolicy.debugInsetFraction(c,p,f,s);}
 static boolean debugCorrespondenceAllowed(SubjectCategory c,float p,int nx,int nz,int x,int z){return DepthV74Gate.debug(c,p,nx,nz,x,z);}
 public static final class Result{
  private final float[] density;private final boolean applied;private final int validViews,changedVoxels,occupiedVoxels,correspondencePrunedVoxels;private final double meanSurfaceInset;private final boolean collapseGuardUsed;private final String reason;
  Result(float[] d,boolean a,int v,int ch,int o,double in,boolean g,int pr,String r){density=d;applied=a;validViews=v;changedVoxels=ch;occupiedVoxels=o;meanSurfaceInset=in;collapseGuardUsed=g;correspondencePrunedVoxels=pr;reason=r;}
  static Result unchanged(float[] d,int v,String r){int o=0;for(float x:d)if(x>=ISO)o++;return new Result(d,false,v,0,o,0,false,0,r);}
  public float[] getDensity(){return density;}public boolean isApplied(){return applied;}public int getValidViews(){return validViews;}public int getChangedVoxels(){return changedVoxels;}public int getOccupiedVoxels(){return occupiedVoxels;}public double getMeanSurfaceInset(){return meanSurfaceInset;}public boolean isCollapseGuardUsed(){return collapseGuardUsed;}public int getCorrespondencePrunedVoxels(){return correspondencePrunedVoxels;}public String getReason(){return reason;}
 }
}
