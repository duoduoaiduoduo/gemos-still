package art.gemosdodo.still;

import static org.junit.Assert.*;
import org.junit.Test;

public final class OriginalGenerationPolicyTest {
 private static final long MIB=1024L*1024;
 @Test public void originalStagesNeedOneGiBTemporaryFreeSpaceBeforePreparingInputs(){
  assertNotNull(OriginalGenerationEngine.storageReason(1024*MIB-1));
  assertNull(OriginalGenerationEngine.storageReason(1024*MIB));
  assertNull(OriginalGenerationEngine.storageReason(2048*MIB));
 }
 @Test public void entryNeeds2304MiBButRunningRetainsOneGiBReserve(){
  assertNull(OriginalGenerationEngine.safetyReason(2304*MIB,false,389,1,true));
  assertNotNull(OriginalGenerationEngine.safetyReason(2304*MIB-1,false,389,1,true));
  assertNull(OriginalGenerationEngine.safetyReason(1024*MIB,false,389,1,false));
  assertNotNull(OriginalGenerationEngine.safetyReason(1024*MIB-1,false,389,1,false));
  assertNotNull(OriginalGenerationEngine.safetyReason(4096*MIB,true,300,0,false));
 }
 @Test public void battery39AndModerateThermalStopBothEntryAndRunning(){
  for(boolean entering:new boolean[]{true,false}){
   assertNotNull(OriginalGenerationEngine.safetyReason(4096*MIB,false,390,0,entering));
   assertNotNull(OriginalGenerationEngine.safetyReason(4096*MIB,false,300,2,entering));
   assertNull(OriginalGenerationEngine.safetyReason(4096*MIB,false,389,1,entering));
  }
 }
 @Test public void missingTemperatureOrThermalMetricsFailClosed(){
  assertNotNull(OriginalGenerationEngine.safetyReason(4096*MIB,false,-1,0,true));
  assertNotNull(OriginalGenerationEngine.safetyReason(4096*MIB,false,300,-1,false));
 }
}
