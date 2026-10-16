package art.gemosdodo.still;

import android.content.Context;
import java.io.*;
import java.nio.*;
import org.json.JSONObject;

/** Bounded STLL reader; never decodes the full-resolution photo just to view 3D. */
final class MemoryFile {
 static final int MAX_PARTICLES=250000;
 static final long MAX_BYTES=150L*1024*1024;
 static final class Content {
  final String title;
  final float[] records;
  Content(String title,float[] records){this.title=title;this.records=records;}
 }
 static Content read(File file)throws Exception {
  long size=file.length();if(size<12||size>MAX_BYTES)throw new IOException("记忆文件大小不支持");
  try(InputStream in=new BufferedInputStream(new FileInputStream(file),262144)) {
   byte[] header=new byte[12];fully(in,header);ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
   if(h.getInt()!=0x4c4c5453||h.getInt()!=1)throw new IOException("这不是兼容的 .still 记忆文件");
   int jsonSize=h.getInt();if(jsonSize<2||jsonSize>100000||12L+jsonSize>=size)throw new IOException("记忆信息不完整");
   byte[] json=new byte[jsonSize];fully(in,json);JSONObject meta=new JSONObject(new String(json,"UTF-8"));
   long photoBytes=meta.getLong("photoBytes"),modelBytes=meta.getLong("modelBytes");
   if(photoBytes<=0||modelBytes<6400||modelBytes%64!=0||12L+jsonSize+photoBytes+modelBytes!=size)throw new IOException("记忆文件不完整，请重新导入");
   skip(in,photoBytes);
   int total=(int)(modelBytes/64),limit=Math.min(total,MAX_PARTICLES),written=0;
   float[] records=new float[limit*16];byte[] row=new byte[64];ByteBuffer floats=ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
   int next=0;
   for(int i=0;i<total;i++) {
    fully(in,row);
    if(i!=next||written>=limit)continue;
    floats.position(0);int offset=written*16;boolean finite=true;
    for(int k=0;k<16;k++){float v=floats.getFloat();records[offset+k]=v;finite&=Float.isFinite(v);}
    if(!finite)throw new IOException("记忆中存在无法显示的粒子数据");
    if(records[offset+3]<0||records[offset+3]>1.001f)throw new IOException("记忆透明度数据异常");
    if(records[offset+4]<0||records[offset+7]<0||records[offset+9]<0)throw new IOException("记忆空间数据异常");
    written++;next=written<limit?sampleIndex(written,total,limit):total;
   }
   if(written<100)throw new IOException("记忆中的有效粒子不足");
   return new Content(displayTitle(meta.optString("name","一刻记忆")),records);
  }
 }
 static Content example(Context context)throws IOException {
  try(InputStream in=new BufferedInputStream(context.getAssets().open("example.memorygs"))) {
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[32768];int n;
   while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>MAX_PARTICLES*64)throw new IOException("示例数据过大");}
   byte[] raw=bytes.toByteArray();if(raw.length<6400||raw.length%64!=0)throw new IOException("示例不完整");
   FloatBuffer source=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] records=new float[source.remaining()];source.get(records);
   return new Content("站内人潮 · 示例",records);
  }
 }
 /** Uniform budget sampling includes both endpoints, without a prefix bias. */
 static int sampleIndex(int index,int total,int limit){
  if(total<1||limit<1||limit>total||index<0||index>=limit)throw new IllegalArgumentException("Invalid display sampling bounds");
  return limit==1?0:(int)((long)index*(total-1)/(limit-1));
 }
 static String displayTitle(String name) {
  if(name==null)return "一刻记忆";
  String s=name.replaceAll("[\\p{Cntrl}\\r\\n]+"," ").replaceAll("(?i)\\.(jpg|jpeg|png|webp|heic|heif|still)$","").replaceAll("[_\\s]+"," ").trim();
  // Camera hashes, timestamps and descriptive generation filenames stay in the
  // original document, while the stage uses a short human-readable title.
  if(s.isEmpty()||s.matches("(?i)(IMG|DSC|PXL|Screenshot|微信图片|mmexport)[ _-]?[0-9a-z_-]+")||s.matches("[0-9a-fA-F-]{24,}"))return "一刻记忆";
  if(s.codePointCount(0,s.length())>24)s=s.substring(0,s.offsetByCodePoints(0,24))+"…";
  return s;
 }
 static void fully(InputStream in,byte[] bytes)throws IOException {int at=0,n;while(at<bytes.length){n=in.read(bytes,at,bytes.length-at);if(n<0)throw new EOFException("文件传输不完整");at+=n;}}
 private static void skip(InputStream in,long count)throws IOException {while(count>0){long n=in.skip(count);if(n>0)count-=n;else if(in.read()<0)throw new EOFException("照片数据不完整");else count--;}}
}
