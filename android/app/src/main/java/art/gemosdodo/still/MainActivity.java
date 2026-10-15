package art.gemosdodo.still;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

/** Native workspace: the memory is the main surface, actions stay within reach. */
public class MainActivity extends Activity implements GenerationEngine.Listener {
 private static final int PHOTO=1,SAVE=2,LOGS=3,MODEL=4,OPEN=5;
 private static final int INK=0xff272a25,MUTED=0xff73796f,PAPER=0xfff5f5ef;
 private final ExecutorService files=Executors.newSingleThreadExecutor();
 private GenerationEngine engine;
 private MemorySceneView scene;
 private TextView title,subtitle,status,stageTitle;
 private ImageView thumbnail;
 private Bitmap thumb;
 private ProgressBar progress;
 private Button primary,secondary,example,open,create,more;
 private File photo,currentMemory,displayedResult;
 private String photoTitle="一刻记忆";
 private int imageWidth,imageHeight,selectionTicket;
 private boolean preparing,resumed,destroyed,showingExample;

 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);
  Object retained=getLastNonConfigurationInstance();engine=retained instanceof GenerationEngine?(GenerationEngine)retained:new GenerationEngine(this);
  getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(PAPER);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  buildWorkspace();
  File savedPhoto=new File(getFilesDir(),"selected-photo.jpg");
  if(savedPhoto.isFile()){
   photo=savedPhoto;photoTitle=getPreferences(0).getString("photoTitle","一刻记忆");
   imageWidth=getPreferences(0).getInt("photoWidth",0);imageHeight=getPreferences(0).getInt("photoHeight",0);
   if(imageWidth<=0||imageHeight<=0)photo=null;
  }
  engine.attach(this);
  File latest=new File(getFilesDir(),"last-memory.still");
  if(latest.isFile()&&!engine.state().running&&!preparing)loadMemory(latest,false);else updateControls();
  CrashDiagnostics.append(this,"Mobile viewer beta · "+Build.MANUFACTURER+" "+Build.MODEL+" · Android "+Build.VERSION.RELEASE);
 }

 private void buildWorkspace(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(PAPER);
  root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
  setContentView(root);root.requestApplyInsets();
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),0,dp(12),0);
  TextView brand=text("Gemos Still",22,INK);brand.setTypeface(Typeface.create("sans-serif-medium",0));brand.setGravity(Gravity.CENTER_VERTICAL);header.addView(brand,new LinearLayout.LayoutParams(0,dp(64),1));
  create=button("＋ 新建",false);create.setContentDescription("选择照片，新建记忆");create.setOnClickListener(v->choosePhoto());header.addView(create,new LinearLayout.LayoutParams(dp(82),dp(44)));
  more=button("•••",false);more.setTextSize(19);more.setContentDescription("更多操作");more.setOnClickListener(this::menu);header.addView(more,new LinearLayout.LayoutParams(dp(46),dp(44)));root.addView(header);

  FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(0xff10110e);scene=new MemorySceneView(this);stage.addView(scene,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout caption=new LinearLayout(this);caption.setOrientation(LinearLayout.VERTICAL);caption.setPadding(dp(20),dp(14),dp(20),dp(14));caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x88000000,0x00000000}));
  TextView eyebrow=text("THE MEMORY TERMINAL",10,0xffa9ada3);eyebrow.setLetterSpacing(.15f);caption.addView(eyebrow);
  stageTitle=text("给下一段记忆，留一个位置。",14,0xffeeeee8);stageTitle.setMaxLines(1);stageTitle.setEllipsize(TextUtils.TruncateAt.END);stageTitle.setPadding(0,dp(6),0,0);caption.addView(stageTitle);stage.addView(caption,new FrameLayout.LayoutParams(-1,dp(86),Gravity.TOP));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

  LinearLayout navigation=new LinearLayout(this);navigation.setGravity(Gravity.CENTER_VERTICAL);navigation.setPadding(dp(20),dp(7),dp(12),dp(7));
  TextView hint=text("单指旋转 · 双指缩放",11,MUTED);hint.setGravity(Gravity.CENTER_VERTICAL);navigation.addView(hint,new LinearLayout.LayoutParams(0,dp(44),1));
  Button front=button("正面",false);front.setOnClickListener(v->scene.front());navigation.addView(front,new LinearLayout.LayoutParams(dp(64),dp(44)));
  Button reset=button("复位 ↺",false);reset.setOnClickListener(v->scene.reset());navigation.addView(reset,new LinearLayout.LayoutParams(dp(76),dp(44)));root.addView(navigation);

  LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(18),dp(20),dp(16));card.setBackground(shape(Color.WHITE,24,0xffe5e7de));
  LinearLayout.LayoutParams cardLayout=new LinearLayout.LayoutParams(-1,-2);cardLayout.setMargins(dp(12),0,dp(12),dp(12));root.addView(card,cardLayout);
  LinearLayout summary=new LinearLayout(this);summary.setGravity(Gravity.CENTER_VERTICAL);
  thumbnail=new ImageView(this);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);thumbnail.setClipToOutline(true);thumbnail.setBackground(shape(PAPER,10,0));thumbnail.setVisibility(View.GONE);
  LinearLayout.LayoutParams previewLayout=new LinearLayout.LayoutParams(dp(52),dp(52));previewLayout.setMarginEnd(dp(12));summary.addView(thumbnail,previewLayout);
  LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);
  title=text("把照片，收藏成 3D 记忆。",19,INK);title.setTypeface(Typeface.create("sans-serif-medium",0));title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);
  subtitle=text("从一张照片开始。照片全程留在本机。",12,MUTED);subtitle.setMaxLines(2);subtitle.setPadding(0,dp(6),0,0);copy.addView(subtitle);summary.addView(copy,new LinearLayout.LayoutParams(0,-2,1));card.addView(summary);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff7e8b73));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffe5e9e0));
  LinearLayout.LayoutParams progressLayout=new LinearLayout.LayoutParams(-1,dp(5));progressLayout.topMargin=dp(14);card.addView(progress,progressLayout);progress.setVisibility(View.GONE);
  status=text("",12,MUTED);status.setMaxLines(3);status.setEllipsize(TextUtils.TruncateAt.END);status.setLineSpacing(dp(3),1);status.setPadding(0,dp(10),0,dp(2));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(status);status.setVisibility(View.GONE);
  primary=button("选择一张照片",true);primary.setOnClickListener(v->{if(photo==null||currentMemory!=null||showingExample)choosePhoto();else begin();});
  LinearLayout.LayoutParams primaryLayout=new LinearLayout.LayoutParams(-1,dp(52));primaryLayout.topMargin=dp(16);card.addView(primary,primaryLayout);
  LinearLayout auxiliary=new LinearLayout(this);auxiliary.setGravity(Gravity.CENTER);
  example=button("查看示例",false);example.setOnClickListener(v->loadExample());open=button("打开记忆",false);open.setOnClickListener(v->openDocument(OPEN,"*/*"));secondary=button("换一张照片",false);secondary.setOnClickListener(v->{if(engine.state().running)confirmCancel();else choosePhoto();});
  auxiliary.addView(example,new LinearLayout.LayoutParams(0,dp(44),1));auxiliary.addView(open,new LinearLayout.LayoutParams(0,dp(44),1));auxiliary.addView(secondary,new LinearLayout.LayoutParams(0,dp(44),1));card.addView(auxiliary);
 }

 private boolean busy(){return preparing||engine.state().running;}
 private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setFontFeatureSettings("kern");return view;}
 private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));if(stroke!=0)drawable.setStroke(dp(1),stroke);return drawable;}
 private Button button(String value,boolean filled){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(filled?Color.WHITE:INK);button.setTypeface(Typeface.create("sans-serif-medium",0));button.setPadding(dp(8),0,dp(8),0);button.setMinHeight(0);button.setMinimumHeight(0);button.setMinWidth(0);button.setMinimumWidth(0);button.setStateListAnimator(null);button.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(filled?0x33ffffff:0x11272a25),shape(filled?INK:Color.TRANSPARENT,filled?26:12,0),null));return button;}
 private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private void message(String value){if(destroyed)return;status.setText(value);status.setVisibility(value==null||value.isEmpty()?View.GONE:View.VISIBLE);}
 private void ui(Runnable action){runOnUiThread(()->{if(!destroyed)action.run();});}
 private void updateControls(){
  if(destroyed)return;boolean running=engine.state().running,isBusy=busy();create.setEnabled(!isBusy);more.setEnabled(!isBusy);primary.setEnabled(!isBusy);example.setEnabled(!isBusy);open.setEnabled(!isBusy);thumbnail.setVisibility(thumb!=null?View.VISIBLE:View.GONE);
  if(running){title.setText("正在收藏这一刻…");subtitle.setText("本机 CPU · 轻量模型");primary.setText("正在生成…");example.setVisibility(View.GONE);open.setVisibility(View.GONE);secondary.setVisibility(View.VISIBLE);secondary.setText("取消生成");secondary.setEnabled(true);}
  else if(preparing){primary.setText("正在准备…");secondary.setEnabled(false);}
  else if(currentMemory!=null||showingExample){title.setText(showingExample?"这是一段示例记忆。":MemoryFile.displayTitle(getPreferences(0).getString("memoryTitle","一刻记忆")));subtitle.setText("转动看看，让照片多一个维度。");primary.setText("收藏我的照片");example.setVisibility(showingExample?View.GONE:View.VISIBLE);open.setVisibility(View.VISIBLE);secondary.setVisibility(View.GONE);}
  else if(photo!=null){title.setText(MemoryFile.displayTitle(photoTitle));subtitle.setText("照片已就绪 · 点击下方开始生成");primary.setText("生成 3D 记忆");example.setVisibility(View.GONE);open.setVisibility(View.GONE);secondary.setVisibility(View.VISIBLE);secondary.setText("换一张照片");secondary.setEnabled(true);}
  else{title.setText("把照片，收藏成 3D 记忆。");subtitle.setText("从一张照片开始。照片全程留在本机。");primary.setText("选择一张照片");example.setVisibility(View.VISIBLE);open.setVisibility(View.VISIBLE);secondary.setVisibility(View.GONE);}
  if(!running)progress.setVisibility(preparing?View.VISIBLE:View.GONE);
  scene.setPaused(!resumed||running);if(running)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 }

 private void choosePhoto(){if(!busy())openDocument(PHOTO,"image/*");}
 private void openDocument(int code,String type){try{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(type);startActivityForResult(intent,code);}catch(ActivityNotFoundException error){message("未找到文件选择器，请检查系统文件应用。");}}
 private void begin(){
  if(busy()||photo==null)return;
  if(!ModelStore.ready(this))new AlertDialog.Builder(this).setTitle("首次需要下载轻量模型").setMessage("约 809 MB，建议连接 Wi-Fi。下载后缓存在手机中，以后可离线生成。照片在本机处理。\n\n轻量版细节低于电脑版。生成时请保持应用在前台。").setNegativeButton("暂不下载",null).setPositiveButton("下载并生成",(dialog,which)->startGeneration()).show();
  else startGeneration();
 }
 private void startGeneration(){currentMemory=null;showingExample=false;displayedResult=null;engine.start(photo,imageWidth,imageHeight,photoTitle);updateControls();}
 private void confirmCancel(){if(engine.state().running)new AlertDialog.Builder(this).setTitle("停止这次生成？").setMessage("已下载的模型缓存会保留。停止后可以重新选择照片。").setNegativeButton("继续生成",null).setPositiveButton("停止生成",(dialog,which)->engine.cancel()).show();}
 @Override public void changed(GenerationEngine.State state){
  if(destroyed)return;message(state.message);progress.setVisibility(state.running?View.VISIBLE:View.GONE);
  if(state.running){boolean measured=state.total>0;progress.setIndeterminate(!measured);if(measured){progress.setProgress((int)Math.min(1000,state.bytes*1000/Math.max(1,state.total)));message(state.message+"\n"+String.format(Locale.US,"%.1f / %.1f MB",state.bytes/1000000d,state.total/1000000d));}}
  updateControls();if(!state.running&&state.result!=null&&!state.result.equals(displayedResult)){displayedResult=state.result;loadMemory(state.result,false);}
 }

 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==PHOTO)importPhoto(uri);else if(request==MODEL)importModel(uri);else if(request==OPEN)importMemory(uri);else if(request==SAVE||request==LOGS)saveTo(uri,request==SAVE?currentMemory:new File(getFilesDir(),"diagnostics.txt"));}
 private String displayName(Uri uri){try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(index>=0)return cursor.getString(index);}}catch(Exception ignored){}return "一刻记忆";}
 private void setPreparing(boolean value){preparing=value;if(value){progress.setIndeterminate(true);setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);}else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);updateControls();}
 private void importPhoto(Uri uri){
  if(busy())return;String name=displayName(uri);int ticket=++selectionTicket;setPreparing(true);message("正在准备照片…");
  files.execute(()->{
   File source=new File(getCacheDir(),"selected-"+ticket+".source"),pending=new File(getFilesDir(),"selected-photo.jpg.part");Bitmap bitmap=null;
   try{
    copyUri(uri,source,25L*1024*1024);
    bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source),(decoder,info,imageSource)->{int width=info.getSize().getWidth(),height=info.getSize().getHeight();if(width<=0||height<=0)throw new IllegalArgumentException("照片尺寸无效");float scale=Math.min(1f,1536f/Math.max(width,height));decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
    int width=bitmap.getWidth(),height=bitmap.getHeight();try(OutputStream output=new BufferedOutputStream(new FileOutputStream(pending))){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,94,output))throw new IOException("照片无法转换");}
    File selected=new File(getFilesDir(),"selected-photo.jpg");if(!pending.renameTo(selected))throw new IOException("照片未能保存");float ratio=Math.min(1f,160f/Math.max(width,height));Bitmap preview=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(width*ratio)),Math.max(1,Math.round(height*ratio)),true);if(preview==bitmap)bitmap=null;
    ui(()->{if(ticket!=selectionTicket){preview.recycle();return;}photo=selected;photoTitle=MemoryFile.displayTitle(name);imageWidth=width;imageHeight=height;currentMemory=null;showingExample=false;replaceThumbnail(preview);getPreferences(0).edit().putString("photoTitle",photoTitle).putInt("photoWidth",width).putInt("photoHeight",height).apply();message("轻量版适合在手机上体验，电脑版保留更多细节。");setPreparing(false);});
   }catch(Throwable error){ui(()->{message("照片未能打开："+shortError(error));setPreparing(false);});}finally{if(bitmap!=null)bitmap.recycle();source.delete();pending.delete();}
  });
 }
 private void replaceThumbnail(Bitmap next){thumbnail.setImageBitmap(next);Bitmap previous=thumb;thumb=next;if(previous!=null&&previous!=next)previous.recycle();}
 private void loadExample(){if(busy())return;setPreparing(true);message("正在打开示例…");files.execute(()->{try{MemoryFile.Content content=MemoryFile.example(this);ui(()->{scene.loadMemory(content.records);scene.reset();showingExample=true;currentMemory=null;replaceThumbnail(null);stageTitle.setText("示例 · "+MemoryFile.displayTitle(content.title));message("这是示例。点击下方“收藏我的照片”，就能开始。");setPreparing(false);});}catch(Throwable error){ui(()->{message("示例未能打开："+shortError(error));setPreparing(false);});}});}
 private void loadMemory(File file,boolean imported){
  if(preparing||destroyed)return;setPreparing(true);message("正在打开记忆…");
  files.execute(()->{try{MemoryFile.Content content=MemoryFile.read(file);ui(()->{currentMemory=file;showingExample=false;scene.loadMemory(content.records);scene.reset();stageTitle.setText(MemoryFile.displayTitle(content.title));getPreferences(0).edit().putString("memoryTitle",content.title).apply();replaceThumbnail(null);message(imported?"记忆已打开。可以旋转、缩放，或从更多菜单保存分享。":"已收藏。可以旋转、缩放，或从更多菜单保存分享。");setPreparing(false);});}catch(Throwable error){ui(()->{currentMemory=null;message("记忆未能打开："+shortError(error));setPreparing(false);});}});
 }
 private void importMemory(Uri uri){
  if(busy())return;setPreparing(true);message("正在读取记忆文件…");files.execute(()->{File pending=new File(getFilesDir(),"import-memory.still.part");try{copyUri(uri,pending,MemoryFile.MAX_BYTES);MemoryFile.Content content=MemoryFile.read(pending);File latest=new File(getFilesDir(),"last-memory.still");if(!pending.renameTo(latest))throw new IOException("记忆文件未能保存");ui(()->{currentMemory=latest;showingExample=false;scene.loadMemory(content.records);scene.reset();stageTitle.setText(MemoryFile.displayTitle(content.title));getPreferences(0).edit().putString("memoryTitle",content.title).apply();replaceThumbnail(null);message("记忆已打开，转动看看。");setPreparing(false);});}catch(Throwable error){ui(()->{message("记忆未能打开："+shortError(error));setPreparing(false);});}finally{pending.delete();}});
 }
 private void importModel(Uri uri){if(busy())return;setPreparing(true);message("正在校验并导入模型…");files.execute(()->{try{LiteModel.importPackage(getApplicationContext(),uri,value->ui(()->message(value)));ui(()->message("模型已校验并保存，可以开始本机生成。"));}catch(Throwable error){ui(()->message("模型未能导入："+shortError(error)));}finally{ui(()->setPreparing(false));}});}
 private void copyUri(Uri uri,File destination,long max)throws IOException{try(InputStream input=getContentResolver().openInputStream(uri);OutputStream output=new BufferedOutputStream(new FileOutputStream(destination))){if(input==null)throw new IOException("文件不可读取");byte[] buffer=new byte[65536];long total=0;int count;while((count=input.read(buffer))!=-1){total+=count;if(total>max)throw new IOException("文件过大，请选择较小的文件");output.write(buffer,0,count);}}}
 private String shortError(Throwable error){String value=error.getMessage();if(error instanceof OutOfMemoryError)return "可用内存不足，请换一张较小的照片。";if(value==null||value.trim().isEmpty())return "请换一个文件后再试。";return value.length()>120?value.substring(0,120)+"…":value;}

 private void menu(View anchor){
  PopupMenu popup=new PopupMenu(this,anchor);popup.getMenu().add(0,OPEN,0,"打开记忆文件");popup.getMenu().add(0,SAVE,1,"保存记忆到文件").setEnabled(currentMemory!=null);popup.getMenu().add(0,6,2,"分享记忆文件").setEnabled(currentMemory!=null);popup.getMenu().add(0,MODEL,3,"导入离线模型包");popup.getMenu().add(0,LOGS,4,"导出诊断日志");popup.getMenu().add(0,7,5,"关于 Gemos Still");
  popup.setOnMenuItemClickListener(item->{int id=item.getItemId();if(id==OPEN)openDocument(OPEN,"*/*");else if(id==MODEL)openDocument(MODEL,"*/*");else if(id==SAVE)export(SAVE,"application/octet-stream","Gemos-Still.still");else if(id==LOGS){CrashDiagnostics.previousExit(this);export(LOGS,"text/plain","Gemos-Still-diagnostics.txt");}else if(id==6)share();else if(id==7)new AlertDialog.Builder(this).setTitle("Gemos Still").setMessage("把照片收藏成可以转动的 3D 记忆。\n\nAndroid 原生预览版 · 轻量本机模型\n内部 256 × 256，约 3.3 万个高斯粒子。电脑版保留更多细节。\n\nMade by Gemosdodo").setPositiveButton("好",null).show();return true;});popup.show();
 }
 private void export(int request,String mime,String name){try{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);intent.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(intent,request);}catch(ActivityNotFoundException error){message("未找到系统文件保存器。");}}
 private void saveTo(Uri uri,File file){if(file==null||!file.isFile()){message("还没有可以保存的记忆。");return;}files.execute(()->{try(InputStream input=new BufferedInputStream(new FileInputStream(file));OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new IOException("所选位置不可写入");byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);ui(()->message("已保存到你选择的位置。"));}catch(Exception error){ui(()->message("保存未完成："+shortError(error)));}});}
 private void share(){if(currentMemory==null)return;try{Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("application/octet-stream");intent.putExtra(Intent.EXTRA_STREAM,MemoryProvider.uri(this));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(intent,"分享这段记忆"));}catch(Exception error){message("暂时无法分享，请先保存到文件。");}}

 @Override protected void onResume(){super.onResume();resumed=true;if(scene!=null){scene.onResume();scene.setPaused(engine.state().running);}updateControls();}
 @Override protected void onPause(){resumed=false;if(scene!=null){scene.setPaused(true);scene.onPause();}super.onPause();}
 @Override public Object onRetainNonConfigurationInstance(){engine.detach();return engine;}
 @Override protected void onDestroy(){destroyed=true;engine.detach();if(isFinishing())engine.close();files.shutdown();if(thumb!=null){thumbnail.setImageDrawable(null);thumb.recycle();thumb=null;}super.onDestroy();}
 @Override public void onBackPressed(){if(engine.state().running){confirmCancel();return;}if(preparing){message("正在准备文件，请稍候。");return;}super.onBackPressed();}
}
