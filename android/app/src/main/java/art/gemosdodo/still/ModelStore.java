package art.gemosdodo.still;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** First-party HTTPS, resumable transfers and verified atomic model installation. */
final class ModelStore {
 interface Listener {void update(String stage,long downloaded,long total);}
 private static final String BASE="https://gemosdodo.art/models/gemos-still-lite-v1/";
 private static final String[] NAMES={LiteModel.GRAPH,LiteModel.WEIGHTS};
 private static final long[] SIZES={LiteModel.GRAPH_BYTES,LiteModel.WEIGHT_BYTES};
 private static final String[] HASHES={"b4c7384ceed5587ff9b9813e44bae167c2a2081bd00e1ae7a9f1487618b821ac","42fe29c22b0cb190cda01924277d7df2ea4c80f473839d71821a53e91704998a"};
 static boolean ready(Context context){return LiteModel.ready(context);}
 static void ensure(Context context,Listener listener,BooleanSupplier cancelled)throws Exception {
  if(ready(context))return;
  File dir=context.getFilesDir();long total=SIZES[0]+SIZES[1],done=0;
  for(int i=0;i<NAMES.length;i++) {
   check(cancelled);File target=new File(dir,NAMES[i]),part=new File(dir,NAMES[i]+".download");
   if(target.length()==SIZES[i]) {
    listener.update("正在校验模型",done,total);
    if(matches(target,HASHES[i],cancelled)){done+=SIZES[i];continue;}
   }
   long offset=part.exists()?part.length():0;
   if(offset>SIZES[i]){if(!part.delete())throw new IOException("无法清理未完成的模型缓存");offset=0;}
   if(offset==SIZES[i]) {
    listener.update("正在校验模型",done+SIZES[i],total);
    if(matches(part,HASHES[i],cancelled)) {
     if(target.exists()&&!target.delete())throw new IOException("无法更新模型缓存");
     if(!part.renameTo(target))throw new IOException("无法保存模型缓存");
     done+=SIZES[i];listener.update("模型已就绪",done,total);continue;
    }
    if(!part.delete())throw new IOException("无法清理未完成的模型缓存");offset=0;
   }
   if(dir.getUsableSpace()<SIZES[i]-offset+128L*1024*1024)throw new IOException("请为模型留出至少 1GB 存储空间");
   HttpURLConnection connection=(HttpURLConnection)new URL(BASE+NAMES[i]).openConnection();
   connection.setConnectTimeout(20000);connection.setReadTimeout(20000);connection.setRequestProperty("Accept-Encoding","identity");
   if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
   try {
    int code=connection.getResponseCode();
    if(code==206&&offset>0){String range=connection.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+offset+"-"))throw new IOException("模型续传响应异常，请稍后重试");}
    else if(code==200)offset=0;
    else throw new IOException("模型下载暂不可用（"+code+"），已保留下载进度");
    long count=offset,notice=0;
    try(InputStream in=new BufferedInputStream(connection.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(part,offset>0))) {
     byte[] bytes=new byte[262144];int n;
     while((n=in.read(bytes))!=-1) {
      check(cancelled);count+=n;if(count>SIZES[i])throw new IOException("模型文件大小异常");out.write(bytes,0,n);
      long now=System.nanoTime();if(now-notice>150000000L){listener.update("正在下载手机模型",done+count,total);notice=now;}
     }
    }
    if(count!=SIZES[i])throw new IOException("模型下载中断，可重试继续下载");
   } finally {connection.disconnect();}
   listener.update("正在校验模型",done+SIZES[i],total);
   if(!matches(part,HASHES[i],cancelled)){part.delete();throw new IOException("模型校验失败，请重新下载");}
   if(target.exists()&&!target.delete())throw new IOException("无法更新模型缓存");
   if(!part.renameTo(target))throw new IOException("无法保存模型缓存");
   done+=SIZES[i];listener.update("模型已就绪",done,total);
  }
 }
 private static boolean matches(File file,String expected,BooleanSupplier cancelled)throws Exception {
  MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(file))){byte[] data=new byte[262144];int n;while((n=in.read(data))!=-1){check(cancelled);hash.update(data,0,n);}}
  StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.US,"%02x",b&255));return expected.equals(hex.toString());
 }
 private static void check(BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean())throw new IOException("已取消，下载进度已保留");}
}
