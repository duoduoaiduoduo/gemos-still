package art.gemosdodo.still;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MemoryFileSamplingTest {
 @Test public void nativeDisplayBudgetIs250Thousand(){assertEquals(250000,MemoryFile.MAX_PARTICLES);}
 @Test public void largerImportsIncludeFirstAndLastWithMonotonicUniformIndices(){
  int total=1179648,limit=MemoryFile.MAX_PARTICLES,previous=-1;
  assertEquals(0,MemoryFile.sampleIndex(0,total,limit));assertEquals(total-1,MemoryFile.sampleIndex(limit-1,total,limit));
  for(int i=0;i<limit;i++){int value=MemoryFile.sampleIndex(i,total,limit);assertTrue(value>previous);if(i>0)assertTrue(value-previous<=5);previous=value;}
 }
 @Test public void smallerMemoriesAreNotResampled(){for(int i=0;i<100;i++)assertEquals(i,MemoryFile.sampleIndex(i,100,100));assertEquals(0,MemoryFile.sampleIndex(0,100,1));}
}
