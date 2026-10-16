package art.gemosdodo.still;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Original SHARP research path. Each installed native stage exits before the next
 * starts; neither downloaded files nor any app-writable file is executable code.
 * A failed original run never falls back to a lower-resolution model.
 */
final class OriginalGenerationEngine {
 interface Listener { void phase(String message); }
 private static final long MIB=1024L*1024;
 private static final String PYRAMID="libgemos-pyramid-probe.so",PATCH="libgemos-patch-probe.so",REST="libgemos-rest-probe.so";
 private final Context context;
 private final BooleanSupplier cancelled;
 private final Listener listener;
 private volatile Process activeChild;
 private volatile String stopReason;
 OriginalGenerationEngine(Context context,BooleanSupplier cancelled,Listener listener){this.context=context.getApplicationContext();this.cancelled=cancelled;this.listener=listener;}

 static boolean available(Context context){return OriginalModelStore.hasFiles(context)&&installed(context,PYRAMID)&&installed(context,PATCH)&&installed(context,REST);}
 private static boolean installed(Context context,String name){
  String directory=context.getApplicationInfo().nativeLibraryDir;
  if(directory==null)return false;File file=new File(directory,name);
  return file.isFile()&&file.canExecute();
 }
 boolean hasLiveChild(){Process child=activeChild;return child!=null&&child.isAlive();}
 void cancel(){Process child=activeChild;if(child!=null)child.destroy();}
 private void check()throws IOException{
  if(stopReason!=null)throw new IOException(stopReason);
  if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");
 }
 private void phase(String message)throws IOException{check();CrashDiagnostics.append(context,"Original1536 · "+message);listener.phase(message);}

 File generate(File photo,File working,int width,int height,String title,double focalPx)throws Exception{
  if(!available(context))throw new IOException("原版实验资源尚未就绪，请保留缓存后重试");
  check();admit();
  if(working.getUsableSpace()<512*MIB)throw new IOException("原版实验需要至少 512 MB 临时存储空间");
  ScheduledExecutorService guard=Executors.newSingleThreadScheduledExecutor();
  ScheduledFuture<?> watching=guard.scheduleAtFixedRate(()->{
   try{String reason=unsafe(false);if(reason!=null&&stopReason==null){stopReason=reason;CrashDiagnostics.append(context,"Original guard: "+reason);cancel();}}
   catch(Throwable error){stopReason="无法继续读取设备状态，已停止原版实验。照片和模型缓存已保留。";cancel();}
  },0,500,TimeUnit.MILLISECONDS);
  try{
   phase("正在校验原版模型 · 首次可能需要片刻");
   OriginalModelStore.Models models=OriginalModelStore.ensure(context,()->cancelled.getAsBoolean()||stopReason!=null);
   check();if(!OriginalModelStore.ready(context))throw new IOException("原版模型校验尚未完成");
   phase("正在准备原尺寸照片…");
   OriginalImagePyramid.Files pyramid=OriginalImagePyramid.prepare(photo,working,focalPx,()->cancelled.getAsBoolean()||stopReason!=null);
   check();if(pyramid.width!=width||pyramid.height!=height)throw new IOException("照片尺寸已变化，请重新选择照片，避免相机参数不匹配");
   File inputs=new File(working,"inputs");mkdir(inputs);
   move(pyramid.imageRgb,new File(inputs,"image.f32"));move(pyramid.disparityFile,new File(inputs,"disparity_factor.f32"));
   File prefix=new File(working,"spn");
   phase("原版分析 · 正在读取照片空间 0 / 35");
   run("pyramid",95000,working,PYRAMID,models.patchParam,models.patchWeights,pyramid.normalized1536,pyramid.normalized768,pyramid.normalized384,prefix,"vulkan","fp16");
   for(String name:new String[]{"latent0","latent1","high","middle","low"})move(new File(prefix.getPath()+"."+name+".f32"),new File(inputs,"spn_"+name+".f32"));
   require(new File(inputs,"spn_latent0.f32"),1024L*96*96*4);require(new File(inputs,"spn_latent1.f32"),1024L*96*96*4);require(new File(inputs,"spn_high.f32"),1024L*96*96*4);
   require(new File(inputs,"spn_middle.f32"),1024L*48*48*4);require(new File(inputs,"spn_low.f32"),1024L*24*24*4);
   // The patch encoder process is gone before the independently weighted global
   // image encoder is loaded. Its 49 original LayerNorm operators stay on CPU.
   phase("原版分析 · 正在理解整张照片…");
   File imagePrefix=new File(working,"image-global");
   run("image",65000,working,PATCH,models.imageParam,models.imageWeights,pyramid.normalized384,imagePrefix,"vulkan","fp16","--image-only","--layernorm-cpu");
   move(new File(imagePrefix.getPath()+".out0.f32"),new File(inputs,"spn_image_global.f32"));require(new File(inputs,"spn_image_global.f32"),1024L*24*24*4);
   phase("原版重建 · 正在计算空间深度…");
   File depth=new File(working,"depth");mkdir(depth);
   run("depth",65000,working,REST,"--backend","opencl","--stage","depth","--model",models.depthModel,"--weights",models.depthWeights,"--inputs",inputs,"--output",depth);
   File disparity=new File(depth,"clamped_monodepth_disparity.f32");require(disparity,2L*1536*1536*4);move(disparity,new File(inputs,disparity.getName()));
   phase("原版重建 · 正在构建 3D 记忆…");
   File gaussian=new File(working,"gaussian");mkdir(gaussian);
   run("gaussian",65000,working,REST,"--backend","opencl","--stage","gaussian","--model",models.gaussianModel,"--weights",models.gaussianWeights,"--inputs",inputs,"--output",gaussian);
   check();phase("正在收藏原版记忆…");
   File result=MemoryWriter.writeNative(working,photo,gaussian,width,height,title,focalPx,()->cancelled.getAsBoolean()||stopReason!=null);
   check();return result;
  }catch(Exception error){if(stopReason!=null)throw new IOException(stopReason,error);throw error;}
  finally{
   try{Process child=activeChild;if(child!=null)finishChild(child,null);}
   finally{watching.cancel(false);guard.shutdownNow();}
  }
 }

 private void run(String stage,long timeout,File working,String executable,Object... arguments)throws Exception{
  check();String unsafe=unsafe(false);if(unsafe!=null)throw new IOException(unsafe);
  File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
  File code=new File(nativeDir,executable).getCanonicalFile();
  if(!code.getParentFile().equals(nativeDir)||!code.isFile()||!code.canExecute())throw new IOException("安装包中的原版引擎不可执行，请重新安装实验版");
  List<String> command=new ArrayList<>();command.add(code.getAbsolutePath());
  for(Object argument:arguments)command.add(argument instanceof File?((File)argument).getAbsolutePath():String.valueOf(argument));
  ProcessBuilder builder=new ProcessBuilder(command);builder.directory(working);builder.redirectErrorStream(true);
  builder.environment().put("LD_LIBRARY_PATH",nativeDir.getAbsolutePath());
  long began=SystemClock.elapsedRealtime();CrashDiagnostics.append(context,"Original stage start: "+stage);
  Process child=builder.start();activeChild=child;
  Thread reader=null;
  try{
  final String[] lastLine={""};
  reader=new Thread(()->{
   try(BufferedReader input=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){
    String line;while((line=input.readLine())!=null){
     lastLine[0]=line.length()>500?line.substring(0,500):line;
     if(line.contains("\"guardStopped\"")||line.contains("\"error\"")||line.contains("failed")||line.contains("Original pyramid probe failed"))CrashDiagnostics.append(context,"Original "+stage+": "+lastLine[0]);
     if(stage.equals("pyramid")&&line.startsWith("{"))try{
      JSONObject event=new JSONObject(line);if("original_patch".equals(event.optString("phase"))&&!cancelled.getAsBoolean()&&stopReason==null){int done=event.optInt("done",0);listener.phase("原版分析 · 正在读取照片空间 "+done+" / 35");}
     }catch(Exception ignored){}
    }
   }catch(IOException ignored){}
  },"gemos-original-"+stage+"-output");reader.setDaemon(true);reader.start();
   while(!child.waitFor(200,TimeUnit.MILLISECONDS)){
    check();if(SystemClock.elapsedRealtime()-began>timeout)throw new IOException("原版 "+stageName(stage)+"超时，已停止。照片和模型缓存已保留。");
   }
   check();int codeValue=child.exitValue();reader.join(1000);
   if(codeValue!=0){CrashDiagnostics.append(context,"Original stage failed: "+stage+" code="+codeValue+" last="+lastLine[0]);throw new IOException(codeValue==124?"设备保护已停止本次原版实验。请等手机冷却后重试，照片和模型缓存已保留。":"原版 "+stageName(stage)+"未完成（"+codeValue+"）。照片和模型缓存已保留，可导出诊断日志。");}
   CrashDiagnostics.append(context,"Original stage complete: "+stage+" elapsedMs="+(SystemClock.elapsedRealtime()-began));
  }finally{finishChild(child,reader);}
 }
 private void finishChild(Process child,Thread reader)throws IOException{
  boolean interrupted=Thread.interrupted();
  try{
   if(child.isAlive())child.destroy();
   long deadline=SystemClock.elapsedRealtime()+1000;
   while(child.isAlive()&&SystemClock.elapsedRealtime()<deadline)try{child.waitFor(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){interrupted=true;}
   if(child.isAlive()){
    child.destroyForcibly();deadline=SystemClock.elapsedRealtime()+1000;
    while(child.isAlive()&&SystemClock.elapsedRealtime()<deadline)try{child.waitFor(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){interrupted=true;}
   }
   try{child.getInputStream().close();}catch(IOException ignored){}
   if(reader!=null)try{reader.join(1000);}catch(InterruptedException ignored){interrupted=true;}
   if(child.isAlive())throw new IOException("原版阶段尚未完全停止，已禁止启动下一阶段。请稍后重试。");
  }finally{
   if(!child.isAlive()&&activeChild==child)activeChild=null;
   if(interrupted)Thread.currentThread().interrupt();
  }
 }

 private static String stageName(String name){switch(name){case "pyramid":return "照片分析";case "image":return "整体分析";case "depth":return "深度计算";default:return "空间重建";}}
 private void admit()throws IOException{String reason=unsafe(true);if(reason!=null)throw new IOException(reason);}
 private String unsafe(boolean entering){
  if(cancelled.getAsBoolean())return null;
  ActivityManager activity=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
  if(activity==null)return "无法读取设备内存，本次原版实验暂不运行。";
  ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();activity.getMemoryInfo(memory);
  Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
  int temperature=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1),thermal=0;
  if(Build.VERSION.SDK_INT>=29){PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);thermal=power==null?-1:power.getCurrentThermalStatus();}
  return safetyReason(memory.availMem,memory.lowMemory,temperature,thermal,entering);
 }
 static String safetyReason(long available,boolean lowMemory,int batteryTenths,int thermal,boolean entering){
  long required=(entering?2304:1024)*MIB;
  if(lowMemory||available<required)return entering?"原版实验需要约 2.25 GB 可用内存。请关闭其他大型应用后重试，照片和模型缓存已保留。":"可用内存偏低，已停止原版实验。照片和模型缓存已保留。";
  if(batteryTenths<0)return "无法读取设备温度，本次原版实验暂不运行。";
  if(batteryTenths>=390)return "手机温度偏高，已停止原版实验。请等手机冷却后重试，照片和模型缓存已保留。";
  if(thermal<0||thermal>=2)return "系统正在控制温度，已停止原版实验。请等手机冷却后重试。";
  return null;
 }
 private static void mkdir(File dir)throws IOException{if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备原版实验临时目录");}
 private static void require(File file,long bytes)throws IOException{if(!file.isFile()||file.length()!=bytes)throw new IOException("原版阶段输出不完整："+file.getName());}
 private void move(File from,File to)throws IOException{
  check();if(from.getCanonicalFile().equals(to.getCanonicalFile()))return;
  if(!from.isFile()||!from.renameTo(to))throw new IOException("无法准备原版阶段数据："+to.getName());
 }
}
