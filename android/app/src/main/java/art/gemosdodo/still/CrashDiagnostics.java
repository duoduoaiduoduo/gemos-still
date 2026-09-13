package art.gemosdodo.still;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import java.io.File;
import java.io.FileWriter;
import java.util.Date;

final class CrashDiagnostics {
 static synchronized void append(Context context,String message) {
  try(FileWriter w=new FileWriter(new File(context.getFilesDir(),"diagnostics.txt"),true)) {
   w.write(new Date()+" "+message+"\n");
  } catch(Exception ignored) {}
 }
 static void sample(Context context) {
  ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
  ActivityManager.MemoryInfo system=new ActivityManager.MemoryInfo();am.getMemoryInfo(system);
  Debug.MemoryInfo process=new Debug.MemoryInfo();Debug.getMemoryInfo(process);
  append(context,"MEM pssKiB="+process.getTotalPss()+" nativeBytes="+Debug.getNativeHeapAllocatedSize()+" javaUsedBytes="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+" systemAvailableBytes="+system.availMem+" systemTotalBytes="+system.totalMem+" lowMemory="+system.lowMemory);
 }
 static String previousExit(Context context) {
  if(Build.VERSION.SDK_INT<30)return "此 Android 版本无法读取系统退出原因。";
  try {
   ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
   java.util.List<ApplicationExitInfo> exits=am.getHistoricalProcessExitReasons(context.getPackageName(),0,5);
   String latest="系统暂未提供退出记录。";
   for(int i=0;i<exits.size();i++) {
    ApplicationExitInfo e=exits.get(i);String reason;
    switch(e.getReason()) {
     case ApplicationExitInfo.REASON_LOW_MEMORY: reason="系统因内存不足结束应用";break;
     case ApplicationExitInfo.REASON_CRASH_NATIVE: reason="原生推理引擎崩溃";break;
     case ApplicationExitInfo.REASON_CRASH: reason="应用异常崩溃";break;
     case ApplicationExitInfo.REASON_ANR: reason="应用无响应";break;
     case ApplicationExitInfo.REASON_USER_REQUESTED: reason="用户或系统操作结束应用";break;
     default: reason="其他退出原因（"+e.getReason()+"），需要结合日志判断";
    }
    String message="EXIT "+new Date(e.getTimestamp())+" "+reason+" status="+e.getStatus()+" pssKiB="+e.getPss()+" rssKiB="+e.getRss()+" description="+e.getDescription();append(context,message);
    if(i==0)latest="最近退出："+reason+"\n"+new Date(e.getTimestamp());
   }
   return latest;
  } catch(Exception e){append(context,"Exit info unavailable: "+e);return "无法读取系统退出记录，请导出日志。";}
 }
}
