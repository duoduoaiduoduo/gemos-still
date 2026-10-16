package art.gemosdodo.still;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class CameraIntrinsicsTest {
 @Test public void knownEquivalentCameraPreservesFieldOfView(){
  // A 36x24 sensor represented by a 1536x1024 image has 42 2/3 pixels/mm.
  double focal=CameraIntrinsics.focalPixels(24,1536,1024);
  assertEquals(1024.0,focal,1e-9);
  assertEquals(2.0/3.0,focal/1536,1e-12);
 }

 @Test public void rotationAndDownsamplingPreserveCalibration(){
  double landscape=CameraIntrinsics.focalPixels(24,1536,1024);
  assertEquals(landscape,CameraIntrinsics.focalPixels(24,1024,1536),1e-9);
  assertEquals(landscape/2,CameraIntrinsics.focalPixels(24,768,512),1e-9);
  assertEquals(landscape/1536,CameraIntrinsics.focalPixels(24,768,512)/768,1e-12);
 }

 @Test public void missingOrCorruptMetadataHasOneStableFallback(){
  for(double metadata:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,65535}){
   assertEquals(30.0,CameraIntrinsics.equivalentOrDefault(metadata),0);
  }
  assertEquals(16.0,CameraIntrinsics.equivalentOrDefault(16),0);
  assertEquals(240.0,CameraIntrinsics.equivalentOrDefault(240),0);
  assertEquals(CameraIntrinsics.focalPixels(30,1280,720),
    CameraIntrinsics.pixelsOrDefault(Double.NaN,1280,720),0);
 }

 @Test(expected=IllegalArgumentException.class) public void invalidImageCannotProduceCamera(){
  CameraIntrinsics.focalPixels(24,0,1024);
 }
}
