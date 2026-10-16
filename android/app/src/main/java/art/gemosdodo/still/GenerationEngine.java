package art.gemosdodo.still;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** One retained sequential job. Native stages release GPU memory between processes. */
final class GenerationEngine {
 interface Listener { void changed(State state); }
 static final class State {
  final boolean running; final String message; final long bytes,total; final File result; final boolean cancelled,original;
  State(boolean running,String message,long bytes,long total,File result,boolean cancelled){this(running,message,bytes,total,result,cancelled,false);}
  State(boolean running,String message,long bytes,long total,File result,boolean cancelled,boolean original){this.running=running;this.message=message;this.bytes=bytes;this.total=total;this.result=result;this.cancelled=cancelled;this.original=original;}
 }
 private final Context context;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile State state=new State(false,"选择一张照片，开始收藏。",0,0,null,false);
 private volatile Listener listener;
 private volatile boolean cancelled;
 private volatile OrtSession.RunOptions activeRun;
 private volatile OriginalGenerationEngine activeOriginal;
 private volatile boolean originalMode;
 GenerationEngine(Context context){this.context=context.getApplicationContext();}
 State state(){return state;}
 boolean originalAvailable(){return OriginalGenerationEngine.available(context);}
 void attach(Listener listener){this.listener=listener;listener.changed(state);}
 void detach(){listener=null;}
 private void publish(State next){State event=new State(next.running,next.message,next.bytes,next.total,next.result,next.cancelled,originalMode);state=event;main.post(()->{Listener current=listener;if(current!=null)current.changed(event);});}
 private void phase(String message){CrashDiagnostics.append(context,message);publish(new State(true,message,0,0,null,false));}
 private void check()throws InterruptedIOException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");}
 synchronized void start(File photo,int width,int height,String title){start(photo,width,height,title,CameraIntrinsics.focalPixels(CameraIntrinsics.DEFAULT_35MM,width,height));}
 synchronized void start(File photo,int width,int height,String title,double focalPx){
  if(state.running)return;
  OriginalGenerationEngine previous=activeOriginal;
  if(previous!=null&&previous.hasLiveChild()){previous.cancel();publish(new State(false,"上一阶段尚未完全停止，请稍候再试。照片和模型缓存已保留。",0,0,null,false));return;}
  activeOriginal=null;
  cancelled=false;originalMode=originalAvailable();publish(new State(true,originalMode?"正在准备原版实验模型…":"正在准备本机模型…",0,0,null,false));
  final double calibratedFocal=CameraIntrinsics.pixelsOrDefault(focalPx,width,height);
  worker.execute(()->generate(photo,width,height,title,calibratedFocal));
 }
 void cancel(){
  cancelled=true;
  OriginalGenerationEngine original=activeOriginal;if(original!=null)original.cancel();
  OrtSession.RunOptions run=activeRun;
  if(run!=null)try{run.setTerminate(true);}catch(Exception ignored){}
  if(state.running)publish(new State(true,"正在停止，请稍候…",0,0,null,false));
 }
 void close(){detach();cancel();worker.shutdown();}
 private void generate(File photo,int width,int height,String title,double focalPx){
  File working=new File(context.getCacheDir(),"generation-"+System.nanoTime());
  ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
  ScheduledFuture<?> sampling=monitor.scheduleAtFixedRate(()->{try{CrashDiagnostics.sample(context);}catch(Throwable ignored){}},0,2,TimeUnit.SECONDS);
  File generated=null;
  try{
   check();
   CrashDiagnostics.append(context,"Camera intrinsics: "+width+"x"+height+" focalPx="+focalPx+" disparity="+(focalPx/width));
   if(!working.mkdirs())throw new IOException("无法准备临时存储空间");
   if(originalMode){
    OriginalGenerationEngine original=new OriginalGenerationEngine(context,()->cancelled,message->{if(!cancelled)phase(message);});
    activeOriginal=original;
    try{generated=original.generate(photo,working,width,height,title,focalPx);}finally{if(!original.hasLiveChild())activeOriginal=null;}
   }else{
   ModelStore.ensure(context,(message,bytes,total)->{if(!cancelled)publish(new State(true,message,bytes,total,null,false));},()->cancelled);
   check();
   phase("正在载入轻量模型…");
   OrtEnvironment env=OrtEnvironment.getEnvironment();
   try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
    // Keep the previously verified 256 × 256 INT8 CPU numerical path unchanged.
    options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
    options.addConfigEntry("session.disable_prepacking","1");
    options.addConfigEntry("session.intra_op.allow_spinning","0");
    options.addConfigEntry("session.inter_op.allow_spinning","0");
    options.setMemoryPatternOptimization(true);options.setCPUArenaAllocator(true);
    try(OrtSession session=env.createSession(new File(context.getFilesDir(),LiteModel.GRAPH).getAbsolutePath(),options)){
     check();phase("正在准备照片…");
     Bitmap raw=BitmapFactory.decodeFile(photo.getAbsolutePath());
     if(raw==null)throw new IOException("照片无法读取，请重新选择");
     Bitmap input=Bitmap.createScaledBitmap(raw,256,256,true);if(input!=raw)raw.recycle();
     int[] pixels=new int[256*256];input.getPixels(pixels,0,256,0,0,256,256);input.recycle();
     Map<String,OnnxTensor> feeds=new HashMap<>();
     try{
      for(Map.Entry<String,NodeInfo> entry:session.getInputInfo().entrySet()){
       TensorInfo info=(TensorInfo)entry.getValue().getInfo();boolean image=info.getShape().length==4;
       int length=image?256*256*3:1;
       ByteBuffer buffer=ByteBuffer.allocateDirect(length*(info.type==OnnxJavaType.FLOAT16?2:4)).order(ByteOrder.nativeOrder());
       for(int i=0;i<length;i++){
        float value=image?((pixels[i%(256*256)]>>(16-8*(i/(256*256))))&255)/255f:(float)(focalPx/width);
        if(info.type==OnnxJavaType.FLOAT16)buffer.putShort(toHalf(value));else buffer.putFloat(value);
       }
       buffer.rewind();feeds.put(entry.getKey(),OnnxTensor.createTensor(env,buffer,image?new long[]{1,3,256,256}:new long[]{1},info.type));
      }
      pixels=null;check();phase("正在本机生成 · 请保持应用在前台");
      try(OrtSession.RunOptions run=new OrtSession.RunOptions()){
       activeRun=run;check();
       try(OrtSession.Result outputs=session.run(feeds,run)){
        check();phase("正在保存这段记忆…");
        generated=MemoryWriter.write(working,photo,outputs,width,height,title,focalPx,()->cancelled);
       }finally{activeRun=null;}
      }
     }finally{for(OnnxTensor tensor:feeds.values())tensor.close();}
    }
   }
   }
   // Session and all native tensors are closed before the renderer loads the result.
   check();File destination=new File(context.getFilesDir(),"last-memory.still");
   File pending=new File(context.getFilesDir(),"last-memory.still.part");
   copy(generated,pending);MemoryFile.read(pending);check();
   if(!pending.renameTo(destination))throw new IOException("记忆文件未能保存，请检查可用空间");
   CrashDiagnostics.append(context,"生成完成："+destination.length()+" bytes");
   publish(new State(false,"记忆已收藏，可以转动看看。",0,0,destination,false));
  }catch(Throwable error){
   boolean stopped=cancelled||error instanceof InterruptedIOException;
   if(!stopped)CrashDiagnostics.append(context,android.util.Log.getStackTraceString(error));
   String message=stopped?"已停止生成。照片仍保存在本机。":"本次没有完成："+friendly(error);
   publish(new State(false,message,0,0,null,stopped));
  }finally{
   activeRun=null;OriginalGenerationEngine original=activeOriginal;if(original!=null)original.cancel();if(original==null||!original.hasLiveChild())activeOriginal=null;sampling.cancel(false);monitor.shutdown();delete(working);
   new File(context.getFilesDir(),"last-memory.still.part").delete();
  }
 }
 private static String friendly(Throwable error){
  if(error instanceof OutOfMemoryError)return "可用内存不足，请先关闭其他大型应用后再试。";
  String text=error.getMessage();if(text==null||text.trim().isEmpty())return "请稍后再试，或从更多菜单导出诊断日志。";
  return text.length()>160?text.substring(0,160)+"…":text;
 }
 private static void copy(File from,File to)throws IOException{
  try(InputStream in=new BufferedInputStream(new FileInputStream(from));OutputStream out=new BufferedOutputStream(new FileOutputStream(to))){byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
 }
 private static void delete(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);}file.delete();}
 static short toHalf(float value){int bits=Float.floatToIntBits(value),sign=(bits>>>16)&32768,exponent=((bits>>>23)&255)-112,mantissa=bits&8388607;return(short)(exponent<=0?(exponent< -10?sign:sign|(((mantissa|8388608)>>(1-exponent))+4096>>13)):exponent>=31?sign|31744:sign|(exponent<<10)+(mantissa+4096>>13));}
}
