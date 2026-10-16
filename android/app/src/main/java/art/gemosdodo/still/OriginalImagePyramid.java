package art.gemosdodo.still;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

/** Official photo resize and original SPN pyramid; never uses Bitmap scaling. */
final class OriginalImagePyramid {
 private OriginalImagePyramid(){}
 private static final int INTERNAL=1536,MAX_PHOTO=1536;

 static final class Files {
  final File imageRgb,normalized1536,normalized768,normalized384,disparityFile;
  final int width,height;
  Files(File working,int width,int height){
   this.width=width;this.height=height;
   imageRgb=new File(working,"original-image-rgb-1536.f32");
   normalized1536=new File(working,"original-normalized-1536.f32");
   normalized768=new File(working,"original-normalized-768.f32");
   normalized384=new File(working,"original-normalized-384.f32");
   disparityFile=new File(working,"original-disparity-factor.f32");
  }
 }
 private static final class Decoded {
  final int width,height;final float[] rgb;
  Decoded(int width,int height,float[] rgb){this.width=width;this.height=height;this.rgb=rgb;}
 }

 /** Photo is the already oriented, bounded JPEG selected by MainActivity. */
 static Files prepare(File photo,File working,double focalPx,BooleanSupplier cancelled)throws IOException{
  OriginalModelStore.check(cancelled);
  if(!Double.isFinite(focalPx)||focalPx<=0)throw new IOException("照片的相机参数无效，请重新选择");
  if(!working.isDirectory()&&!working.mkdirs())throw new IOException("无法准备照片的临时存储空间");
  Decoded decoded=decode(photo,cancelled);
  Files files=new Files(working,decoded.width,decoded.height);
  // Exactly one full 3×1536×1536 float workspace. Write RGB before reusing
  // that same allocation for the normalized pyramid source.
  float[] rgb=decoded.rgb;
  writeArray(files.imageRgb,rgb,cancelled);
  for(int i=0;i<rgb.length;i++){
   if((i&65535)==0)OriginalModelStore.check(cancelled);
   rgb[i]=rgb[i]*2f-1f;
  }
  writeArray(files.normalized1536,rgb,cancelled);
  float[] row=new float[INTERNAL];
  // Upstream SPN downsamples each lower level directly from level0.
  writeResized(files.normalized768,rgb,INTERNAL,INTERNAL,768,768,false,row,cancelled);
  writeResized(files.normalized384,rgb,INTERNAL,INTERNAL,384,384,false,row,cancelled);
  writeArray(files.disparityFile,new float[]{(float)(focalPx/decoded.width)},cancelled);
  OriginalModelStore.check(cancelled);
  return files;
 }

 private static Decoded decode(File photo,BooleanSupplier cancelled)throws IOException{
  if(!photo.isFile()||photo.length()<1||photo.length()>25L*1024*1024)throw new IOException("照片文件无法读取，请重新选择");
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
  BitmapFactory.decodeFile(photo.getAbsolutePath(),bounds);
  if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>MAX_PHOTO||bounds.outHeight>MAX_PHOTO)
   throw new IOException("请重新选择照片，应用需要先准备尺寸合适的本机副本");
  OriginalModelStore.check(cancelled);
  BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
  Bitmap bitmap=BitmapFactory.decodeFile(photo.getAbsolutePath(),options);
  if(bitmap==null)throw new IOException("照片无法解码，请重新选择");
  int width=bitmap.getWidth(),height=bitmap.getHeight();
  int[] pixels=new int[width*height];
  try{bitmap.getPixels(pixels,0,width,0,0,width,height);}finally{bitmap.recycle();}
  OriginalModelStore.check(cancelled);
  // Official sharp/cli/predict.py uses align_corners=True for photo→1536.
  float[] rgb=resizeArgb(pixels,width,height,INTERNAL,INTERNAL,true,cancelled);
  return new Decoded(width,height,rgb);
 }

 private interface PixelSource {float get(int channel,int y,int x);}
 static final class ResizePlan {
  final int inputWidth,inputHeight,outputWidth,outputHeight;
  final int[] x0,x1,y0,y1;final float[] wx0,wx1,wy0,wy1;
  ResizePlan(int iw,int ih,int ow,int oh,boolean alignCorners){
   if(iw<1||ih<1||ow<1||oh<1)throw new IllegalArgumentException("Invalid resize shape");
   inputWidth=iw;inputHeight=ih;outputWidth=ow;outputHeight=oh;
   x0=new int[ow];x1=new int[ow];y0=new int[oh];y1=new int[oh];
   wx0=new float[ow];wx1=new float[ow];wy0=new float[oh];wy1=new float[oh];
   axis(iw,ow,alignCorners,x0,x1,wx0,wx1);axis(ih,oh,alignCorners,y0,y1,wy0,wy1);
  }
  private static void axis(int input,int output,boolean align,int[] low,int[] high,float[] a,float[] b){
   // PyTorch's non-antialiased bilinear pixel centers and edge clamping.
   float scale=align?(output>1?(float)(input-1)/(output-1):0f):(float)input/output;
   for(int i=0;i<output;i++){
    float source=align?scale*i:Math.max(0f,scale*(i+.5f)-.5f);
    int first=Math.min((int)source,input-1);
    low[i]=first;high[i]=Math.min(first+1,input-1);
    b[i]=Math.max(0f,Math.min(1f,source-first));a[i]=1f-b[i];
   }
  }
 }
 private static void sampleRow(PixelSource source,ResizePlan plan,int channel,int y,float[] row){
  int top=plan.y0[y],bottom=plan.y1[y];float upper=plan.wy0[y],lower=plan.wy1[y];
  for(int x=0;x<plan.outputWidth;x++){
   int left=plan.x0[x],right=plan.x1[x];float a=plan.wx0[x],b=plan.wx1[x];
   float first=source.get(channel,top,left)*a+source.get(channel,top,right)*b;
   float second=source.get(channel,bottom,left)*a+source.get(channel,bottom,right)*b;
   row[x]=first*upper+second*lower;
  }
 }
 static float[] resizeArray(float[] input,int iw,int ih,int ow,int oh,boolean align,BooleanSupplier cancelled)throws IOException{
  if(input.length!=3L*iw*ih)throw new IllegalArgumentException("RGB tensor shape mismatch");
  return resize((c,y,x)->input[(c*ih+y)*iw+x],new ResizePlan(iw,ih,ow,oh,align),cancelled);
 }
 static float[] resizeArgb(int[] input,int iw,int ih,int ow,int oh,boolean align,BooleanSupplier cancelled)throws IOException{
  if(input.length!=(long)iw*ih)throw new IllegalArgumentException("ARGB image shape mismatch");
  return resize((c,y,x)->((input[y*iw+x]>>>(16-8*c))&255)/255f,new ResizePlan(iw,ih,ow,oh,align),cancelled);
 }
 private static float[] resize(PixelSource input,ResizePlan plan,BooleanSupplier cancelled)throws IOException{
  long count=3L*plan.outputWidth*plan.outputHeight;
  if(count>Integer.MAX_VALUE)throw new IllegalArgumentException("Resize tensor is too large");
  float[] output=new float[(int)count],row=new float[plan.outputWidth];
  for(int c=0;c<3;c++)for(int y=0;y<plan.outputHeight;y++){
   if((y&7)==0)OriginalModelStore.check(cancelled);
   sampleRow(input,plan,c,y,row);System.arraycopy(row,0,output,(c*plan.outputHeight+y)*plan.outputWidth,plan.outputWidth);
  }
  return output;
 }

 private static void writeArray(File destination,float[] values,BooleanSupplier cancelled)throws IOException{
  File pending=new File(destination.getPath()+".part");boolean complete=false;
  try{
   ByteBuffer bytes=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
   try(BufferedOutputStream output=new BufferedOutputStream(new FileOutputStream(pending),65536)){
    for(int offset=0;offset<values.length;){
     OriginalModelStore.check(cancelled);bytes.clear();int count=Math.min(bytes.capacity()/4,values.length-offset);
     for(int j=0;j<count;j++)bytes.putFloat(values[offset+j]);output.write(bytes.array(),0,count*4);offset+=count;
    }
   }
   OriginalModelStore.check(cancelled);
   if(pending.length()!=values.length*4L||!pending.renameTo(destination))throw new IOException("照片数据未能完整保存");
   complete=true;
  }finally{if(!complete)pending.delete();}
 }
 static void writeResized(File destination,float[] input,int iw,int ih,int ow,int oh,boolean align,float[] row,BooleanSupplier cancelled)throws IOException{
  if(input.length!=3L*iw*ih||row.length<ow)throw new IllegalArgumentException("Resize workspace shape mismatch");
  ResizePlan plan=new ResizePlan(iw,ih,ow,oh,align);PixelSource source=(c,y,x)->input[(c*ih+y)*iw+x];
  File pending=new File(destination.getPath()+".part");boolean complete=false;
  try{
   ByteBuffer bytes=ByteBuffer.allocate(ow*4).order(ByteOrder.LITTLE_ENDIAN);
   try(BufferedOutputStream output=new BufferedOutputStream(new FileOutputStream(pending),65536)){
    for(int c=0;c<3;c++)for(int y=0;y<oh;y++){
     if((y&7)==0)OriginalModelStore.check(cancelled);
     sampleRow(source,plan,c,y,row);bytes.clear();for(int x=0;x<ow;x++)bytes.putFloat(row[x]);output.write(bytes.array());
    }
   }
   OriginalModelStore.check(cancelled);
   if(pending.length()!=3L*ow*oh*4||!pending.renameTo(destination))throw new IOException("照片金字塔未能完整保存");
   complete=true;
  }finally{if(!complete)pending.delete();}
 }
}
