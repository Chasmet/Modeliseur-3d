package com.chasmet.modeliseur3d.model;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TripoAdaptiveQualityTest {
    @Test public void candidateGridPreservesAspectAndDoesNotExceedNeuralVolume(){
        assertEquals(256,TripoAdaptiveMesher.dimension(256,1f));
        assertEquals(166,TripoAdaptiveMesher.dimension(256,.65f));
        assertEquals(103,TripoAdaptiveMesher.dimension(158,.65f));
        assertEquals(8,TripoAdaptiveMesher.dimension(30,.01f));
    }
    @Test public void denoiserKeepsSolidMaterialAndSharpBoundary(){
        int size=7,green=0xff086a09,red=0xffdc0808;
        int[] pixels=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)
            pixels[y*size+x]=x<3?green:red;
        int[] result=TripoHiddenTextureDenoiser.clean(pixels,size);
        assertEquals(green,result[3*size+1]);
        assertEquals(red,result[3*size+5]);
        assertEquals(green,result[3*size+2]);
        assertEquals(red,result[3*size+3]);
    }
}
