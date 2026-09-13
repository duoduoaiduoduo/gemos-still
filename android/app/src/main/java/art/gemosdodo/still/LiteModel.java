package art.gemosdodo.still;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import java.util.zip.*;
import java.util.HashSet;

final class LiteModel {
 static final String GRAPH="lite256int8.onnx", WEIGHTS=GRAPH+".data";
 static final long GRAPH_BYTES=9033925L, WEIGHT_BYTES=799551232L;
 static boolean ready(Context context) {return new File(context.getFilesDir(),GRAPH).length()==GRAPH_BYTES&&new File(context.getFilesDir(),WEIGHTS).length()==WEIGHT_BYTES;}
 interface Progress {void update(String text);}
 static void importPackage(Context context,Uri uri,Progress progress)throws Exception {
  File dir=context.getFilesDir();if(dir.getUsableSpace()<GRAPH_BYTES+WEIGHT_BYTES+128L*1024*1024)throw new IOException("请先留出至少 1 GB 应用存储空间");
  HashSet<String> found=new HashSet<>();
  try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(context.getContentResolver().openInputStream(uri)))) {
   ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
    String name=entry.getName();if((!name.equals(GRAPH)&&!name.equals(WEIGHTS))||!found.add(name))throw new IOException("模型包内容不匹配");
    long expected=name.equals(GRAPH)?GRAPH_BYTES:WEIGHT_BYTES;
    String expectedHash=name.equals(GRAPH)?"b4c7384ceed5587ff9b9813e44bae167c2a2081bd00e1ae7a9f1487618b821ac":"42fe29c22b0cb190cda01924277d7df2ea4c80f473839d71821a53e91704998a";
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,last=0;
    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(new File(dir,name+".part")))) {
     byte[] bytes=new byte[262144];int n;while((n=zip.read(bytes))!=-1){count+=n;if(count>expected)throw new IOException("模型大小异常");out.write(bytes,0,n);digest.update(bytes,0,n);if(count-last>8*1024*1024){progress.update("正在校验并导入模型 · "+count/1048576+" / "+expected/1048576+" MB");last=count;}}
    }
    StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.US,"%02x",b&255));
    if(count!=expected||!hash.toString().equals(expectedHash))throw new IOException("模型包校验失败，请重新传输完整文件");
   }
   if(found.size()!=2)throw new IOException("模型包缺少文件");
   for(String name:new String[]{GRAPH,WEIGHTS})if(!new File(dir,name+".part").renameTo(new File(dir,name)))throw new IOException("无法保存模型");
  } finally {for(String name:new String[]{GRAPH,WEIGHTS})new File(dir,name+".part").delete();}
 }
}
