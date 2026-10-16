package art.gemosdodo.still;

import static org.junit.Assert.*;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Fixtures come directly from Torch F.interpolate, with synthetic pixels only. */
public final class OriginalImagePyramidTest {
 private static int integer(DataInputStream in)throws IOException{return Integer.reverseBytes(in.readInt());}
 private static float floating(DataInputStream in)throws IOException{return Float.intBitsToFloat(integer(in));}
 private static void compare(float[] expected,float[] actual,String label){
  assertEquals(label+" shape",expected.length,actual.length);
  for(int i=0;i<expected.length;i++)assertEquals(label+" pixel "+i,expected[i],actual[i],2e-6f);
 }
 @Test public void photoAndSpnResizeMatchSeparateOfficialTorchSemantics()throws Exception{
  InputStream resource=getClass().getResourceAsStream("/original-image-pyramid-v1.bin");assertNotNull(resource);
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(resource))){
   assertEquals(0x47504931,integer(in));assertEquals(1,integer(in));int count=integer(in);assertEquals(9,count);
   for(int test=0;test<count;test++){
    int kind=integer(in),iw=integer(in),ih=integer(in),ow=integer(in),oh=integer(in);boolean align=integer(in)!=0;
    float[] source=new float[3*iw*ih],expected=new float[3*ow*oh];for(int i=0;i<source.length;i++)source[i]=floating(in);
    int[] pixels=kind==1?new int[iw*ih]:null;if(pixels!=null)for(int i=0;i<pixels.length;i++)pixels[i]=integer(in);
    for(int i=0;i<expected.length;i++)expected[i]=floating(in);
    compare(expected,OriginalImagePyramid.resizeArray(source,iw,ih,ow,oh,align,()->false),"Torch case "+test);
    if(pixels!=null)compare(expected,OriginalImagePyramid.resizeArgb(pixels,iw,ih,ow,oh,align,()->false),"ARGB case "+test);
   }
   assertEquals(-1,in.read());
  }
 }
 @Test public void lowerLevelsAreWrittenInLittleEndianNchwWithoutAnotherFullAllocation()throws Exception{
  File dir=Files.createTempDirectory("original-pyramid-row-").toFile();File file=new File(dir,"half.f32");
  try{
   float[] source=new float[3*32*32];for(int i=0;i<source.length;i++)source[i]=(i%257)/128f-1f;
   float[] expected=OriginalImagePyramid.resizeArray(source,32,32,16,16,false,()->false);
   OriginalImagePyramid.writeResized(file,source,32,32,16,16,false,new float[1536],()->false);
   assertEquals(expected.length*4L,file.length());FloatBuffer bytes=ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
   float[] actual=new float[bytes.remaining()];bytes.get(actual);compare(expected,actual,"streamed level");
  }finally{file.delete();new File(file.getPath()+".part").delete();dir.delete();}
 }
 @Test public void cancellationNeverPublishesAPartialPyramidFile()throws Exception{
  File dir=Files.createTempDirectory("original-pyramid-cancel-").toFile();File file=new File(dir,"half.f32");
  try{
   float[] source=new float[3*64*64];AtomicInteger calls=new AtomicInteger();
   try{OriginalImagePyramid.writeResized(file,source,64,64,32,32,false,new float[32],()->calls.incrementAndGet()>2);fail("Cancelled pyramid was published");}catch(InterruptedIOException expected){}
   assertFalse(file.exists());assertFalse(new File(file.getPath()+".part").exists());
  }finally{file.delete();new File(file.getPath()+".part").delete();dir.delete();}
 }
 @Test public void cachedPhotoDimensionsAreRetainedForFocalCalibration()throws Exception{
  OriginalImagePyramid.Files files=new OriginalImagePyramid.Files(new File("unused-synthetic"),1379,1011);
  assertEquals(1379,files.width);assertEquals(1011,files.height);
  assertEquals("original-disparity-factor.f32",files.disparityFile.getName());
 }
}
