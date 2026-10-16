package art.gemosdodo.still;

import android.content.Context;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;

/** Camera metadata belongs to the original selection, before JPEG normalization. */
final class CameraIntrinsics {
 static final double DEFAULT_35MM=30.0;
 private CameraIntrinsics(){}

 static double readEquivalent35mm(Context context,Uri uri){
  // A physical lens focal length cannot be converted without reliable sensor
  // dimensions. Deliberately do not read TAG_FOCAL_LENGTH as a substitute.
  try(InputStream input=context.getContentResolver().openInputStream(uri)){
   if(input!=null){
    ExifInterface exif=new ExifInterface(input);
    return equivalentOrDefault(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,Double.NaN));
   }
  }catch(IOException|RuntimeException ignored){
   // Missing, unsupported or malformed EXIF does not make an otherwise valid
   // photograph unusable. The image decoder will validate its actual pixels.
  }
  return DEFAULT_35MM;
 }

 static double equivalentOrDefault(double equivalent){
  // EXIF uses zero for unknown. Reject corrupt/sentinel values and implausible
  // metadata while allowing wide-angle through specialist telephoto cameras.
  return Double.isFinite(equivalent)&&equivalent>=1&&equivalent<=10000?equivalent:DEFAULT_35MM;
 }

 static double focalPixels(double equivalent35mm,int width,int height){
  if(width<=0||height<=0)throw new IllegalArgumentException("照片尺寸无效");
  return equivalentOrDefault(equivalent35mm)*Math.hypot(width,height)/Math.hypot(36,24);
 }

 static double pixelsOrDefault(double focalPx,int width,int height){
  return Double.isFinite(focalPx)&&focalPx>0?focalPx:focalPixels(DEFAULT_35MM,width,height);
 }
}
