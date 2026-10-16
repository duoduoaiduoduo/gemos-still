package art.gemosdodo.still;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class OriginalModelStoreTest {
 private static final String ABC="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
 private static File directory()throws IOException{return Files.createTempDirectory("original-model-proof-").toFile();}
 private static void put(File directory,String name,String text)throws IOException{try(FileOutputStream out=new FileOutputStream(new File(directory,name))){out.write(text.getBytes(StandardCharsets.UTF_8));}}
 private static void clean(File directory){File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}
 private static OriginalModelStore.Artifact[] catalog(){return new OriginalModelStore.Artifact[]{new OriginalModelStore.Artifact("tiny.bin",3,ABC)};}

 @Test public void catalogPinsAllTwelveArtifactsIncludingTheScheduledThreeWayDepth()throws Exception{
  OriginalModelStore.Artifact[] artifacts=OriginalModelStore.requiredArtifacts();
  assertEquals(12,artifacts.length);Set<String> names=new HashSet<>();long total=0;
  for(OriginalModelStore.Artifact artifact:artifacts){assertTrue(names.add(artifact.name));total+=artifact.bytes;}
  assertEquals(1361125778L,total);
  // Reviewed depth manifest SHA256 80397e3b050e641353ad15860e98c5b8dc21e62aea2c3527293122ca6b706f2b.
  assertEquals("391e7b01db72aaa072a96169b14b5a9aa910391c3a20c1c3be9328383e8b6037",OriginalModelStore.fingerprint(artifacts));
  assertFalse(names.contains("A-monodepth-fp16.mnn"));
  OriginalModelStore.Models models=new OriginalModelStore.Models(new File("private-models"));
  File[] bound={models.patchParam,models.patchWeights,models.imageParam,models.imageWeights,
    models.depthCoarseModel,models.depthCoarseWeights,models.depthRefineModel,models.depthRefineWeights,
    models.depthFineModel,models.depthFineWeights,models.gaussianModel,models.gaussianWeights};
  for(int i=0;i<bound.length;i++)assertEquals(artifacts[i].name,bound[i].getName());
  assertEquals(4032,artifacts[6].bytes); // Reject the earlier unscheduled 4816-byte refine graph.
  artifacts[0]=null;assertNotNull(OriginalModelStore.requiredArtifacts()[0]);
 }

 @Test public void verifiedDigestIsBoundToCatalogSizeAndModificationTime()throws Exception{
  File dir=directory();try{
   put(dir,"tiny.bin","abc");OriginalModelStore.Artifact[] catalog=catalog();
   assertTrue(OriginalModelStore.hasFiles(dir,catalog));assertFalse(OriginalModelStore.unchanged(dir,catalog,null));
   OriginalModelStore.Proof[] proof=OriginalModelStore.verify(dir,catalog,()->false,null);
   assertTrue(OriginalModelStore.unchanged(dir,catalog,proof));
   assertEquals(ABC,proof[0].digest);
   File file=new File(dir,"tiny.bin");assertTrue(file.setLastModified(proof[0].modified+2000));
   assertFalse(OriginalModelStore.unchanged(dir,catalog,proof));
   OriginalModelStore.Proof[] refreshed=OriginalModelStore.verify(dir,catalog,()->false,proof);
   assertTrue(OriginalModelStore.unchanged(dir,catalog,refreshed));
  }finally{clean(dir);}
 }
 @Test public void corruptionIsReportedWithoutDeletingTheModel()throws Exception{
  File dir=directory();try{
   put(dir,"tiny.bin","xyz");boolean refused=false;
   try{OriginalModelStore.verify(dir,catalog(),()->false,null);}catch(IOException expected){refused=true;}
   assertTrue(refused);assertTrue(new File(dir,"tiny.bin").isFile());assertEquals(3,new File(dir,"tiny.bin").length());
  }finally{clean(dir);}
 }
 @Test public void missingAndWrongSizeNeverCountAsReady()throws Exception{
  File dir=directory();try{
   assertFalse(OriginalModelStore.hasFiles(dir,catalog()));put(dir,"tiny.bin","ab");
   assertFalse(OriginalModelStore.hasFiles(dir,catalog()));
   try{OriginalModelStore.verify(dir,catalog(),()->false,null);fail("Incomplete model accepted");}catch(IOException expected){}
  }finally{clean(dir);}
 }
 @Test public void cancellationIsCheckedBeforeAndDuringHashing()throws Exception{
  File dir=directory();try{
   put(dir,"tiny.bin","abc");
   try{OriginalModelStore.verify(dir,catalog(),()->true,null);fail("Cancelled hash ran");}catch(InterruptedIOException expected){}
   AtomicInteger checks=new AtomicInteger();
   try{OriginalModelStore.verify(dir,catalog(),()->checks.incrementAndGet()>1,null);fail("Hash ignored cancellation");}catch(InterruptedIOException expected){}
   assertTrue(new File(dir,"tiny.bin").isFile());
  }finally{clean(dir);}
 }
 @Test public void changedManifestInvalidatesAnOtherwiseMatchingProof()throws Exception{
  File dir=directory();try{
   put(dir,"tiny.bin","abc");OriginalModelStore.Proof[] proof=OriginalModelStore.verify(dir,catalog(),()->false,null);
   OriginalModelStore.Artifact[] different={new OriginalModelStore.Artifact("tiny.bin",3,"0000000000000000000000000000000000000000000000000000000000000000")};
   assertNotEquals(OriginalModelStore.fingerprint(catalog()),OriginalModelStore.fingerprint(different));
   assertFalse(OriginalModelStore.unchanged(dir,different,proof));
  }finally{clean(dir);}
 }
}
