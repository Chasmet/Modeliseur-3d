package com.chasmet.modeliseur3d.model;
public final class V74CorrespondenceSelfTest{
 public static void main(String[] a){
  check(MultiViewDepthFusion.debugCorrespondenceAllowed(SubjectCategory.COMPOSITE_VEHICLE,.4f,3,3,0,0));
  check(!MultiViewDepthFusion.debugCorrespondenceAllowed(SubjectCategory.COMPOSITE_VEHICLE,.4f,3,3,0,2));
  check(!MultiViewDepthFusion.debugCorrespondenceAllowed(SubjectCategory.COMPOSITE_VEHICLE,.82f,3,3,0,2));
  check(MultiViewDepthFusion.debugCorrespondenceAllowed(SubjectCategory.COMPOSITE_VEHICLE,.82f,2,2,0,1));
  check(MultiViewDepthFusion.debugCorrespondenceAllowed(SubjectCategory.ANIMAL,.8f,2,2,0,1));
  checkDepth();System.out.println("V74CorrespondenceSelfTest: OK");
 }
 static void checkDepth(){int w=12,h=12,d=12;float[] b=new float[w*h*d];boolean[][] m={new boolean[w*h],new boolean[d*h],new boolean[w*h],new boolean[d*h]};float[][] dep={new float[w*h],new float[d*h],new float[w*h],new float[d*h]},c={new float[w*h],new float[d*h],new float[w*h],new float[d*h]};for(int y=2;y<10;y++)for(int x=2;x<10;x++){m[0][y*w+x]=m[2][y*w+x]=true;dep[0][y*w+x]=dep[2][y*w+x]=x;c[0][y*w+x]=c[2][y*w+x]=1;}for(int y=2;y<10;y++)for(int z=2;z<10;z++){m[1][y*d+z]=m[3][y*d+z]=true;dep[1][y*d+z]=dep[3][y*d+z]=z;c[1][y*d+z]=c[3][y*d+z]=1;}for(int y=2;y<10;y++)for(int x=2;x<10;x++)for(int z=2;z<10;z++)b[(y*w+x)*d+z]=1;check(MultiViewDepthFusion.refine(b,m,dep,c,w,h,d,SubjectCategory.CHARACTER).isApplied());}
 static void check(boolean v){if(!v)throw new AssertionError("V7.4 correspondence regression");}
}
