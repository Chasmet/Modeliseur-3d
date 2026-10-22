package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

/** Optional Android/Robolectric smoke checks: -PofflineSmoke. */
@RunWith(RobolectricTestRunner.class)
public final class TripoInputDetailBoostAndroidTest {
    @Test public void unchangedPhotoAndAlphaWithBoundedContrast() {
        Bitmap image = Bitmap.createBitmap(31,31,Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.rgb(105,105,105));
        image.setPixel(15,15,Color.rgb(118,118,118));
        image.setPixel(9,9,Color.argb(90,220,20,40));
        Bitmap boosted = TripoInputDetailBoost.enhance(image,null,1f);
        try {
            assertTrue(Color.red(boosted.getPixel(15,15)) > 118);
            assertEquals(0x5a,Color.alpha(boosted.getPixel(9,9)));
            assertEquals(image.getPixel(9,9),boosted.getPixel(9,9));
            assertEquals(118,Color.red(image.getPixel(15,15)));
            assertEquals(image.getPixel(2,2),boosted.getPixel(2,2));
            assertEquals(31,boosted.getWidth());
        } finally { boosted.recycle();image.recycle(); }
    }
    @Test public void selectedFocusDoesNotChangePixelsOutsideTheRegion() {
        Bitmap image = Bitmap.createBitmap(31,31,Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.rgb(105,105,105));
        image.setPixel(15,15,Color.rgb(118,118,118));
        image.setPixel(3,3,Color.rgb(118,118,118));
        TripoDetailRegion region = new TripoDetailRegion(.3f,.3f,.7f,.7f);
        Bitmap boosted = TripoInputDetailBoost.enhance(image,region,1f);
        try {
            assertTrue(Color.red(boosted.getPixel(15,15)) > 118);
            assertEquals(image.getPixel(3,3),boosted.getPixel(3,3));
        } finally { boosted.recycle();image.recycle(); }
    }
    @Test public void disabledModeKeepsExistingTripoCacheAndFocusKeysRemainDistinct() {
        TripoDetailRegion a=new TripoDetailRegion(.1f,.1f,.4f,.4f);
        TripoDetailRegion b=new TripoDetailRegion(.2f,.1f,.5f,.4f);
        assertEquals("",TripoInputDetailBoost.cacheTag(false,a));
        assertNotEquals(TripoInputDetailBoost.cacheTag(true,a),TripoInputDetailBoost.cacheTag(true,b));
        assertNotEquals(TripoInputDetailBoost.cacheTag(true,null),TripoInputDetailBoost.cacheTag(true,a));
    }
}
