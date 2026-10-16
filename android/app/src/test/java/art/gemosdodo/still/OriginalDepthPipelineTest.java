package art.gemosdodo.still;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Sparse synthetic files only: no photos, weights or native inference. */
public final class OriginalDepthPipelineTest {
 private static final long DECODER=256L*384*384*4,HEAD=128L*768*768*4,DEPTH=2L*1536*1536*4;
 private static File directory()throws IOException{return Files.createTempDirectory("original-depth-chain-").toFile();}
 private static File mkdir(File parent,String name)throws IOException{File file=new File(parent,name);if(!file.mkdir())throw new IOException("mkdir failed");return file;}
 private static void sparse(File file,long bytes)throws IOException{try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(bytes);}}
 private static void clean(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)clean(child);file.delete();}
 private static File[] retained(File inputs,File root)throws IOException{
  List<File> files=new ArrayList<>();
  for(String name:new String[]{"image","disparity_factor","spn_latent0","spn_latent1","spn_high","spn_middle","spn_low","spn_image_global"})files.add(new File(inputs,name+".f32"));
  files.add(new File(root,"selected-photo.jpg"));files.add(new File(root,"cached-model.weight"));
  for(File file:files)sparse(file,4);
  return files.toArray(new File[0]);
 }
 private static void assertRetained(File[] files){for(File file:files)assertTrue("Deleted retained file "+file,file.isFile());}

 @Test public void currentPhotoOutputsFeedEachNextStageAndReleaseOnlyFinishedIntermediates()throws Exception{
  File root=directory();try{
   File working=mkdir(root,"working"),inputs=mkdir(working,"inputs");File[] retained=retained(inputs,root);
   OriginalModelStore.Models models=new OriginalModelStore.Models(mkdir(root,"models"));
   List<String> ran=new ArrayList<>();
   File depth=OriginalGenerationEngine.rebuildDepth(models,working,inputs,(stage,model,weights,shared,output)->{
    ran.add(stage);assertEquals(inputs,shared);assertRetained(retained);
    if(stage.equals("depth-coarse")){
     assertEquals(models.depthCoarseModel,model);assertEquals(models.depthCoarseWeights,weights);
     assertFalse(new File(inputs,"depth_decoder_features.f32").exists());
     sparse(new File(output,"depth_decoder_features.f32"),DECODER);
    }else if(stage.equals("depth-refine")){
     assertEquals(models.depthRefineModel,model);assertEquals(models.depthRefineWeights,weights);
     assertEquals(DECODER,new File(inputs,"depth_decoder_features.f32").length());
     sparse(new File(output,"depth_head_features.f32"),HEAD);
    }else{
     assertEquals("depth-fine",stage);assertEquals(models.depthFineModel,model);assertEquals(models.depthFineWeights,weights);
     assertFalse(new File(inputs,"depth_decoder_features.f32").exists());
     assertEquals(HEAD,new File(inputs,"depth_head_features.f32").length());
     sparse(new File(output,"clamped_monodepth_disparity.f32"),DEPTH);
    }
   },()->false);
   assertEquals(Arrays.asList("depth-coarse","depth-refine","depth-fine"),ran);
   assertEquals(new File(inputs,"clamped_monodepth_disparity.f32"),depth);assertEquals(DEPTH,depth.length());
   assertFalse(new File(inputs,"depth_decoder_features.f32").exists());assertFalse(new File(inputs,"depth_head_features.f32").exists());
   assertRetained(retained);
  }finally{clean(root);}
 }

 @Test public void truncatedRefineCannotDeleteDecoderOrStartFine()throws Exception{
  File root=directory();try{
   File working=mkdir(root,"working"),inputs=mkdir(working,"inputs");File[] retained=retained(inputs,root);
   List<String> ran=new ArrayList<>();
   try{
    OriginalGenerationEngine.rebuildDepth(new OriginalModelStore.Models(mkdir(root,"models")),working,inputs,(stage,model,weights,shared,output)->{
     ran.add(stage);
     sparse(new File(output,stage.equals("depth-coarse")?"depth_decoder_features.f32":"depth_head_features.f32"),stage.equals("depth-coarse")?DECODER:HEAD-4);
    },()->false);
    fail("Truncated depth output accepted");
   }catch(IOException expected){assertTrue(expected.getMessage().contains("输出不完整"));}
   assertEquals(Arrays.asList("depth-coarse","depth-refine"),ran);
   assertEquals(DECODER,new File(inputs,"depth_decoder_features.f32").length());
   assertFalse(new File(inputs,"depth_head_features.f32").exists());assertRetained(retained);
  }finally{clean(root);}
 }

 @Test public void cancellationAfterAChildStopsTheChainBeforeAnyNextStage()throws Exception{
  File root=directory();try{
   File working=mkdir(root,"working"),inputs=mkdir(working,"inputs");File[] retained=retained(inputs,root);
   AtomicBoolean cancelled=new AtomicBoolean();List<String> ran=new ArrayList<>();
   try{
    OriginalGenerationEngine.rebuildDepth(new OriginalModelStore.Models(mkdir(root,"models")),working,inputs,(stage,model,weights,shared,output)->{
     ran.add(stage);sparse(new File(output,"depth_decoder_features.f32"),DECODER);cancelled.set(true);
    },cancelled::get);
    fail("Cancelled depth pipeline continued");
   }catch(InterruptedIOException expected){}
   assertEquals(Arrays.asList("depth-coarse"),ran);assertFalse(new File(inputs,"depth_decoder_features.f32").exists());
   assertRetained(retained);
  }finally{clean(root);}
 }
}
