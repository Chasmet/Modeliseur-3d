package com.chasmet.modeliseur3d.model;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TripoFaceLocatorTest {
    @Test public void eyeLandmarksYieldUsableHeadRegion(){
        TripoDetailRegion r=TripoFaceLocator.fromEyes(200,120,60,400,500);
        assertNotNull(r);
        assertTrue(r.contains(.5f,.3f));
        assertTrue(r.right-r.left>.1f);
        assertTrue(r.top>=0f&&r.bottom<=1f);
    }
    @Test public void incompleteOrInvalidLandmarksAreRejected(){
        assertNull(TripoFaceLocator.fromEyes(Float.NaN,50,20,200,200));
        assertNull(TripoFaceLocator.fromEyes(50,50,1,200,200));
        assertNull(TripoFaceLocator.fromEyes(50,50,20,0,200));
    }
}
