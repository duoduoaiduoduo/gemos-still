package art.gemosdodo.still;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.JSONObject;

final class MemoryWriter {
 static FloatBuffer values(OrtSession.Result out,String name)throws Exception{return ((OnnxTensor)out.get(name).orElseThrow()).getFloatBuffer();}
 static File write(File dir,File photo,OrtSession.Result result,int width,int height)throws Exception{
  FloatBuffer mean=values(result,"mean_vectors_ndc"),scale=values(result,"singular_values_ndc"),quat=values(result,"quaternions_ndc"),color=values(result,"colors"),alpha=values(result,"opacities");int n=alpha.remaining();
  if(mean.remaining()!=n*3||scale.remaining()!=n*3||quat.remaining()!=n*4||color.remaining()!=n*3)throw new IOException("模型输出形状不匹配");
  double focal=30*Math.hypot(width,height)/Math.hypot(36,24),sx=width/(2*focal),sy=height/(2*focal);float[][] sample=new float[3][Math.min(n,60000)];int count=0;
  for(int i=0;i<n&&count<sample[0].length;i+=Math.max(1,n/60000)){float z=mean.get(i*3+2);if(alpha.get(i)>.015&&z>0&&Float.isFinite(z)){sample[0][count]=(float)(mean.get(i*3)*sx);sample[1][count]=(float)(-mean.get(i*3+1)*sy);sample[2][count]=-z;count++;}}
  if(count<100)throw new IOException("有效粒子不足");double[] lo=new double[3],hi=new double[3];for(int j=0;j<3;j++){Arrays.sort(sample[j],0,count);lo[j]=sample[j][(int)(count*.005)];hi[j]=sample[j][Math.min(count-1,(int)(count*.995))];}
  double norm=1.85/Math.max(1e-5,Math.max(hi[0]-lo[0],hi[1]-lo[1])),tz=Math.min(norm,.72/Math.max(hi[2]-lo[2],1e-5));double[] transform={norm*sx,-norm*sy,-tz};
  File model=new File(dir,"model.memorygs");int written=0;ByteBuffer row=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
  try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(model))){int limit=Math.min(n,250000);for(int j=0;j<limit;j++){int i=(int)((long)j*n/limit),k=i*3;double x=mean.get(k)*sx,y=-mean.get(k+1)*sy,z=-mean.get(k+2);float opacity=alpha.get(i);if(opacity<=.015||!Double.isFinite(x+y+z)||x<lo[0]||x>hi[0]||y<lo[1]||y>hi[1]||z<lo[2]||z>hi[2])continue;
   double w=quat.get(i*4),qx=quat.get(i*4+1),qy=quat.get(i*4+2),qz=quat.get(i*4+3),len=Math.sqrt(w*w+qx*qx+qy*qy+qz*qz);if(len<1e-8)len=1;w/=len;qx/=len;qy/=len;qz/=len;
   double[] r={1-2*(qy*qy+qz*qz),2*(qx*qy-qz*w),2*(qx*qz+qy*w),2*(qx*qy+qz*w),1-2*(qx*qx+qz*qz),2*(qy*qz-qx*w),2*(qx*qz-qy*w),2*(qy*qz+qx*w),1-2*(qx*qx+qy*qy)};
   for(int a=0;a<3;a++)for(int b=0;b<3;b++)r[a*3+b]*=transform[a]*scale.get(k+b);
   float[] v=new float[16];v[0]=(float)((x-(lo[0]+hi[0])/2)*norm);v[1]=(float)((y-(lo[1]+hi[1])/2)*norm);v[2]=(float)((z-(lo[2]+hi[2])/2)*tz);v[3]=opacity;int pos=4;for(int a=0;a<3;a++)for(int b=a;b<3;b++)v[pos++]=(float)(r[a*3]*r[b*3]+r[a*3+1]*r[b*3+1]+r[a*3+2]*r[b*3+2]);v[12]=color.get(k);v[13]=color.get(k+1);v[14]=color.get(k+2);boolean finite=true;for(float f:v)finite&=Float.isFinite(f);if(!finite)continue;row.clear();for(float f:v)row.putFloat(f);stream.write(row.array());written++;}}
  if(written<100)throw new IOException("有效粒子不足");JSONObject meta=new JSONObject();meta.put("name","Android 本机记忆");meta.put("created_at",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(new Date()));meta.put("settings",new JSONObject().put("designVersion",2).put("contentScale",.65).put("depthVolume",1));meta.put("photoType","image/jpeg");meta.put("photoBytes",photo.length());meta.put("modelBytes",model.length());byte[] json=meta.toString().getBytes("UTF-8");File output=new File(dir,"Gemos-Still.still");
  try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0x4c4c5453).putInt(1).putInt(json.length).array());out.write(json);for(File f:new File[]{photo,model})try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[65536];int read;while((read=in.read(buf))!=-1)out.write(buf,0,read);}}
  return output;
 }
}
