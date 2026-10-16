package art.gemosdodo.still;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.JSONObject;

final class MemoryWriter {
 static FloatBuffer values(OrtSession.Result out,String name)throws Exception{return ((OnnxTensor)out.get(name).orElseThrow()).getFloatBuffer();}
 static File write(File dir,File photo,OrtSession.Result result,int width,int height)throws Exception{return write(dir,photo,result,width,height,"一刻记忆");}
 static File write(File dir,File photo,OrtSession.Result result,int width,int height,String title)throws Exception{return write(dir,photo,result,width,height,title,CameraIntrinsics.focalPixels(CameraIntrinsics.DEFAULT_35MM,width,height));}
 static File write(File dir,File photo,OrtSession.Result result,int width,int height,String title,double focalPx)throws Exception{
  FloatBuffer mean=values(result,"mean_vectors_ndc"),scale=values(result,"singular_values_ndc"),quat=values(result,"quaternions_ndc"),color=values(result,"colors"),alpha=values(result,"opacities");int n=alpha.remaining();
  if(mean.remaining()!=n*3||scale.remaining()!=n*3||quat.remaining()!=n*4||color.remaining()!=n*3)throw new IOException("模型输出形状不匹配");
  double focal=CameraIntrinsics.pixelsOrDefault(focalPx,width,height);
  GaussianDisplayPreparer.Input input=new GaussianDisplayPreparer.Input(mean,scale,quat,color,alpha,width,height,focal);
  GaussianDisplayPreparer.Analysis analysis=GaussianDisplayPreparer.analyze(input);
  File model=new File(dir,"model.memorygs");int written;
  try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(model))){written=GaussianDisplayPreparer.write(input,analysis,250000,stream);}
  if(written<100)throw new IOException("有效粒子不足");JSONObject meta=new JSONObject();meta.put("name",MemoryFile.displayTitle(title));meta.put("created_at",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(new Date()));meta.put("settings",new JSONObject().put("designVersion",2).put("contentScale",.65).put("depthVolume",1));meta.put("photoType","image/jpeg");meta.put("photoBytes",photo.length());meta.put("modelBytes",model.length());byte[] json=meta.toString().getBytes("UTF-8");File output=new File(dir,"generated-memory.still.part");
  try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0x4c4c5453).putInt(1).putInt(json.length).array());out.write(json);for(File f:new File[]{photo,model})try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[65536];int read;while((read=in.read(buf))!=-1)out.write(buf,0,read);}}
  return output;
 }
}
