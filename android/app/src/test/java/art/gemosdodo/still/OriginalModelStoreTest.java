package art.gemosdodo.still;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class OriginalModelStoreTest {
 private static final String ABC="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
 private static File directory()throws IOException{return Files.createTempDirectory("original-model-proof-").toFile();}
 private static void put(File directory,String name,String text)throws IOException{try(FileOutputStream out=new FileOutputStream(new File(directory,name))){out.write(text.getBytes(StandardCharsets.UTF_8));}}
 private static void clean(File directory){File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}
 private static OriginalModelStore.Artifact[] catalog(){return new OriginalModelStore.Artifact[]{new OriginalModelStore.Artifact("tiny.bin",3,ABC)};}

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
