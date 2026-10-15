package art.gemosdodo.still;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import java.io.*;
import org.json.JSONObject;

/** One bounded native rendering check. Never starts model inference. */
public final class ViewerSmokeTest extends Instrumentation {
 @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
 private void require(boolean value,String message)throws Exception {if(!value)throw new IOException(message);}
 private MemorySceneView find(View view){if(view instanceof MemorySceneView)return (MemorySceneView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){MemorySceneView result=find(group.getChildAt(i));if(result!=null)return result;}}return null;}
 private void capture(String name)throws Exception {
  Bitmap bitmap=getUiAutomation().takeScreenshot();require(bitmap!=null,"Screenshot unavailable");
  try(OutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
 }
 private void drag(MemorySceneView scene){
  long start=android.os.SystemClock.uptimeMillis();float x=scene.getWidth()*.55f,y=scene.getHeight()*.5f;
  for(int i=0;i<=8;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==8?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;MotionEvent event=MotionEvent.obtain(start,start+i*32,action,x+i*scene.getWidth()*.03f,y,0);scene.dispatchTouchEvent(event);event.recycle();}
 }
 @Override public void onStart(){
  Bundle report=new Bundle();
  try {
   Context context=getTargetContext();File memory=new File(context.getFilesDir(),"last-memory.still");
   MemoryFile.Content content=MemoryFile.read(memory);require(content.records.length>=1600,"No particles in input memory");
   char[] longName=new char[200];java.util.Arrays.fill(longName,'a');String title=MemoryFile.displayTitle(new String(longName)+".jpg");
   require(title.codePointCount(0,title.length())<=25,"Long title is not bounded");
   File broken=new File(context.getCacheDir(),"smoke-truncated.still");
   try(RandomAccessFile source=new RandomAccessFile(memory,"r");OutputStream out=new FileOutputStream(broken)){byte[] bytes=new byte[32768];long remaining=memory.length()-1;while(remaining>0){int n=source.read(bytes,0,(int)Math.min(bytes.length,remaining));out.write(bytes,0,n);remaining-=n;}}
   boolean rejected=false;try{MemoryFile.read(broken);}catch(Exception expected){rejected=true;}finally{broken.delete();}require(rejected,"Truncated memory was accepted");
   Intent intent=new Intent();intent.setClassName(context.getPackageName(),"art.gemosdodo.still.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   Activity activity=startActivitySync(intent);waitForIdleSync();
   final MemorySceneView[] reference=new MemorySceneView[1];runOnMainSync(()->reference[0]=find(activity.getWindow().getDecorView()));
   require(reference[0]!=null,"Native GLSurfaceView not found");MemorySceneView scene=reference[0];
   JSONObject state=null;long end=System.currentTimeMillis()+20000;
   while(System.currentTimeMillis()<end){state=new JSONObject(scene.diagnostics());require(state.optString("error","").isEmpty(),"Renderer: "+state.optString("error"));if(state.optLong("frameCount")>3&&state.optBoolean("memoryLoaded")&&state.optInt("particleCount")>0&&state.optInt("partCount")>0)break;Thread.sleep(150);}
   require(state!=null&&state.optLong("frameCount")>3&&state.optBoolean("memoryLoaded")&&state.optInt("particleCount")>0&&state.optInt("partCount")>0,"Scene did not load a real memory and terminal");
   capture("viewer-smoke-default.png");
   runOnMainSync(scene::front);Thread.sleep(1800);capture("viewer-smoke-front.png");
   runOnMainSync(scene::reset);Thread.sleep(1800);capture("viewer-smoke-reset.png");
   long before=new JSONObject(scene.diagnostics()).optLong("frameCount");
   runOnMainSync(()->drag(scene));Thread.sleep(1500);capture("viewer-smoke-orbit.png");
   require(new JSONObject(scene.diagnostics()).optLong("frameCount")>before,"Orbit gesture did not wake rendering");
   runOnMainSync(scene::onPause);runOnMainSync(()->scene.loadMemory(content.records));Thread.sleep(200);
   runOnMainSync(scene::onResume);Thread.sleep(2200);capture("viewer-smoke-resumed.png");
   state=new JSONObject(scene.diagnostics());require(state.optString("error","").isEmpty(),"GL error after camera movement");
   require(state.optInt("glError")==0&&state.optBoolean("ready")&&state.optBoolean("memoryLoaded"),"GL state invalid after pause/resume");
   report.putString("result","PASS");report.putString("renderer",state.toString());report.putInt("inputParticleCount",content.records.length/16);report.putString("scope","one viewer session, no inference, camera controls, orbit gesture and paused data upload");
   finish(Activity.RESULT_OK,report);
  } catch(Throwable error){report.putString("result","FAIL");report.putString("error",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,report);}
 }
}
