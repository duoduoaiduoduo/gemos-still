package art.gemosdodo.still;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/** Validates the locally provisioned, original model stages. No remote source. */
final class OriginalModelStore {
 private OriginalModelStore(){}
 private static final String DIRECTORY="original-model-v1",PREFERENCES="original-model-validation-v1";

 static final class Models {
  final File patchParam,patchWeights,imageParam,imageWeights,depthModel,depthWeights,gaussianModel,gaussianWeights;
  Models(File directory){
   patchParam=new File(directory,ARTIFACTS[0].name);patchWeights=new File(directory,ARTIFACTS[1].name);
   imageParam=new File(directory,ARTIFACTS[2].name);imageWeights=new File(directory,ARTIFACTS[3].name);
   depthModel=new File(directory,ARTIFACTS[4].name);depthWeights=new File(directory,ARTIFACTS[5].name);
   gaussianModel=new File(directory,ARTIFACTS[6].name);gaussianWeights=new File(directory,ARTIFACTS[7].name);
  }
 }
 static final class Artifact {
  final String name,sha256;final long bytes;
  Artifact(String name,long bytes,String sha256){this.name=name;this.bytes=bytes;this.sha256=sha256;}
 }
 static final class Proof {
  final String digest;final long bytes,modified;
  Proof(String digest,long bytes,long modified){this.digest=digest;this.bytes=bytes;this.modified=modified;}
 }
 private static final Artifact[] ARTIFACTS={
  new Artifact("patch_encoder_fp16.ncnn.param",46390,"2f0abd2464210cec8b498ce2b0806e7de43858b06999299aa832a466ec26a39e"),
  new Artifact("patch_encoder_fp16.ncnn.bin",608965380,"26bd6abc3fbfb2e4accf6627c570ad19e5005255ace4652962711f5a5001f977"),
  new Artifact("image_encoder_fp16.ncnn.param",46380,"33c54448782b7f46a9a7aae3944400c8f9d2e3fff7d8c2a162afba8fa3c9656e"),
  new Artifact("image_encoder_fp16.ncnn.bin",608965380,"d734a3800f0d81ec7dfd83808f2190c246100eefa629dbba5774c78031e98258"),
  new Artifact("A-monodepth-fp16.mnn",17656,"6908e3e04fe8e0024a32d54a46cd4286855156c263abb01d07661289e0fb7a21"),
  new Artifact("A-monodepth-fp16.mnn.weight",78195976,"c418408e0365ec0404ec83d5ebe39c56fd1a418209a11b3b2ac21d30a7cd8aa2"),
  new Artifact("B-gaussians-fp16.mnn",9493192,"9b974946041a6a0952566c7e31491554378d0bccd3ee0086178bd254552472bf"),
  new Artifact("B-gaussians-fp16.mnn.weight",55394416,"5b10c8dc3a8eb53c8f856b2cbc5bc750d8f5d88fb80407675d713b6d068a8552")
 };
 private static final String MANIFEST_DIGEST=fingerprint(ARTIFACTS);

 /** Fast candidate check only. Newly provisioned files still need ensure(). */
 static boolean hasFiles(Context context){return hasFiles(new File(context.getFilesDir(),DIRECTORY),ARTIFACTS);}

 /** No model hashing on the UI thread; proof is invalidated by size/mtime/catalog changes. */
 static boolean ready(Context context){
  SharedPreferences preferences=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
  if(!MANIFEST_DIGEST.equals(preferences.getString("manifest",null)))return false;
  return unchanged(new File(context.getFilesDir(),DIRECTORY),ARTIFACTS,readProofs(preferences));
 }

 /** Call from the generation worker. Files are retained after failure/cancellation. */
 static Models ensure(Context context,BooleanSupplier cancelled)throws IOException{
  if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("原版模型校验需要在后台执行");
  File directory=new File(context.getFilesDir(),DIRECTORY);
  SharedPreferences preferences=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
  Proof[] previous=MANIFEST_DIGEST.equals(preferences.getString("manifest",null))?readProofs(preferences):null;
  Proof[] verified=verify(directory,ARTIFACTS,cancelled,previous);
  check(cancelled);
  SharedPreferences.Editor editor=preferences.edit().clear().putString("manifest",MANIFEST_DIGEST);
  for(int i=0;i<ARTIFACTS.length;i++){
   String name=ARTIFACTS[i].name;Proof proof=verified[i];
   editor.putString(name+".digest",proof.digest).putLong(name+".bytes",proof.bytes).putLong(name+".modified",proof.modified);
  }
  if(!editor.commit())throw new IOException("无法保存原版模型的本机校验记录");
  return new Models(directory);
 }

 private static Proof[] readProofs(SharedPreferences preferences){
  Proof[] proofs=new Proof[ARTIFACTS.length];
  for(int i=0;i<ARTIFACTS.length;i++){
   String name=ARTIFACTS[i].name,digest=preferences.getString(name+".digest",null);
   if(digest!=null)proofs[i]=new Proof(digest,preferences.getLong(name+".bytes",-1),preferences.getLong(name+".modified",-1));
  }
  return proofs;
 }
 static boolean hasFiles(File directory,Artifact[] expected){
  for(Artifact artifact:expected){File file=new File(directory,artifact.name);if(!file.isFile()||file.length()!=artifact.bytes)return false;}
  return expected.length>0;
 }
 static boolean unchanged(File directory,Artifact[] expected,Proof[] proofs){
  if(proofs==null||proofs.length!=expected.length||!hasFiles(directory,expected))return false;
  for(int i=0;i<expected.length;i++)if(!matches(new File(directory,expected[i].name),expected[i],proofs[i]))return false;
  return true;
 }
 private static boolean matches(File file,Artifact artifact,Proof proof){
  return proof!=null&&artifact.sha256.equals(proof.digest)&&proof.bytes==artifact.bytes&&file.isFile()
    &&file.length()==proof.bytes&&file.lastModified()==proof.modified;
 }
 static Proof[] verify(File directory,Artifact[] expected,BooleanSupplier cancelled,Proof[] previous)throws IOException{
  Proof[] verified=new Proof[expected.length];
  for(int i=0;i<expected.length;i++){
   check(cancelled);Artifact artifact=expected[i];File file=new File(directory,artifact.name);
   if(!file.isFile()||file.length()!=artifact.bytes)throw new IOException("原版模型文件缺失或不完整："+artifact.name);
   Proof old=previous!=null&&i<previous.length?previous[i]:null;
   if(matches(file,artifact,old)){verified[i]=old;continue;}
   long bytes=file.length(),modified=file.lastModified();MessageDigest digest=sha256();
   try(FileInputStream input=new FileInputStream(file)){
    byte[] buffer=new byte[65536];int count;
    while((count=input.read(buffer))!=-1){check(cancelled);digest.update(buffer,0,count);}
   }
   check(cancelled);String actual=hex(digest.digest());
   if(file.length()!=bytes||file.lastModified()!=modified)throw new IOException("模型文件正在变更，请稍后重试");
   if(!artifact.sha256.equals(actual))throw new IOException("原版模型文件校验失败："+artifact.name);
   verified[i]=new Proof(actual,bytes,modified);
  }
  return verified;
 }
 static void check(BooleanSupplier cancelled)throws InterruptedIOException{
  if(Thread.currentThread().isInterrupted()||(cancelled!=null&&cancelled.getAsBoolean()))throw new InterruptedIOException("已取消");
 }
 static String fingerprint(Artifact[] expected){
  MessageDigest digest=sha256();
  for(Artifact artifact:expected)digest.update((artifact.name+"\t"+artifact.bytes+"\t"+artifact.sha256+"\n").getBytes(StandardCharsets.UTF_8));
  return hex(digest.digest());
 }
 private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
 private static String hex(byte[] bytes){char[] out=new char[bytes.length*2];char[] alphabet="0123456789abcdef".toCharArray();for(int i=0;i<bytes.length;i++){int v=bytes[i]&255;out[i*2]=alphabet[v>>>4];out[i*2+1]=alphabet[v&15];}return new String(out);}
}
