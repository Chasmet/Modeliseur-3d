package com.chasmet.modeliseur3d.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.IOException;
import java.io.InputStream;

/** EXIF-aware import used only by the third workshop; original modes stay untouched. */
public final class OfflineImageImporter {
    private OfflineImageImporter(){}
    public static Bitmap decode(ContentResolver resolver,Uri uri)throws IOException{
        return decode(resolver,uri,1024);
    }
    public static Bitmap decode(ContentResolver resolver,Uri uri,int limit)throws IOException{
        if(limit!=1024&&limit!=2048)throw new IOException("Résolution d’import invalide.");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=resolver.openInputStream(uri)){
            if(in!=null)orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
        }catch(IOException ignored){/* Formats without EXIF still decode normally. */}
        Bitmap bitmap=BitmapUtils.decodeBitmapFromUri(resolver,uri,limit);
        return orient(bitmap,orientation);
    }
    public static Bitmap orient(Bitmap bitmap,int orientation){
        Matrix m=new Matrix();
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:m.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:m.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:m.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:m.setRotate(90);m.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_90:m.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:m.setRotate(-90);m.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_270:m.setRotate(-90);break;
            default:return bitmap;
        }
        Bitmap corrected=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);
        if(corrected!=bitmap)bitmap.recycle();return corrected;
    }
}
