package art.gemosdodo.still;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** The same ray-depth-contrast-v2 display preparation used by Web and Mac.
 *
 * Model predictions remain untouched. This display copy compresses depth along
 * camera rays (including the covariance Jacobian), records salient content
 * bounds, and samples uniformly only after every valid point was analyzed.
 * This class deliberately has no Android/ONNX dependencies so its complete
 * mathematics can be checked against the actual Python prepare_memory oracle.
 */
final class GaussianDisplayPreparer {
 private GaussianDisplayPreparer(){}

 static final class Input {
  final FloatBuffer mean,scale,quat,color,alpha;
  final int count;
  final float sx,sy;
  Input(FloatBuffer mean,FloatBuffer scale,FloatBuffer quat,FloatBuffer color,FloatBuffer alpha,
        int width,int height,double focalPx){
   this.mean=mean.slice();this.scale=scale.slice();this.quat=quat.slice();this.color=color.slice();this.alpha=alpha.slice();count=this.alpha.remaining();
   if(width<1||height<1||!Double.isFinite(focalPx)||focalPx<=0)throw new IllegalArgumentException("Invalid camera calibration");
   if(this.mean.remaining()!=count*3||this.scale.remaining()!=count*3||this.quat.remaining()!=count*4||this.color.remaining()!=count*3)throw new IllegalArgumentException("Gaussian output shapes disagree");
   sx=(float)(width/(2*focalPx));sy=(float)(height/(2*focalPx));
  }
 }

 static final class Analysis {
  final int[] sources,kept;
  final float[] x,y,z,importance;
  final boolean adaptive;
  final float reference;
  final double[] low,high,focusLow,focusHigh;
  final float importanceThreshold;
  Analysis(int[] sources,int[] kept,float[] x,float[] y,float[] z,float[] importance,
           boolean adaptive,float reference,double[] low,double[] high,double[] focusLow,double[] focusHigh,float threshold){
   this.sources=sources;this.kept=kept;this.x=x;this.y=y;this.z=z;this.importance=importance;
   this.adaptive=adaptive;this.reference=reference;this.low=low;this.high=high;this.focusLow=focusLow;this.focusHigh=focusHigh;this.importanceThreshold=threshold;
  }
  boolean selected(int point){return adaptive&&importance[point]>=importanceThreshold&&inside(x[point],y[point],z[point],focusLow,focusHigh);}
 }

 static Analysis analyze(Input input)throws IOException{return analyze(input,null);}
 static Analysis analyze(Input input,BooleanSupplier cancelled)throws IOException{
  check(cancelled);
  int valid=0;
  for(int i=0;i<input.count;i++){if((i&4095)==0)check(cancelled);if(valid(input,i))valid++;}
  if(valid<100)throw new IOException("有效粒子不足");
  int[] sources=new int[valid];float[] x=new float[valid],y=new float[valid],z=new float[valid];
  for(int i=0,j=0;i<input.count;i++){if((i&4095)==0)check(cancelled);if(valid(input,i)){
   sources[j]=i;x[j]=input.mean.get(i*3)*input.sx;y[j]=input.mean.get(i*3+1)*input.sy;z[j]=input.mean.get(i*3+2);j++;
  }}
  float[] weights=new float[valid];Arrays.fill(weights,1);
  int positive=0;for(int i=0;i<z.length;i++){if((i&4095)==0)check(cancelled);if(z[i]>1e-5f)positive++;}
  boolean adaptive=false;float reference=0;
  if(positive>=(int)Math.ceil(valid*.98)){
   float[] rayX=new float[valid],rayY=new float[valid];
   for(int i=0;i<valid;i++){if((i&4095)==0)check(cancelled);float depth=Math.max(z[i],1e-5f);rayX[i]=x[i]/depth;rayY[i]=y[i]/depth;}
   double x0=quantile(rayX,.005,cancelled),x1=quantile(rayX,.995,cancelled),y0=quantile(rayY,.005,cancelled),y1=quantile(rayY,.995,cancelled);
   int[] ids=new int[valid],counts=new int[4096];double[][] sums=new double[3][4096];double[] squares=new double[4096];
   for(int i=0;i<valid;i++){
    if((i&4095)==0)check(cancelled);int cx=clamp((int)((rayX[i]-x0)/Math.max(x1-x0,1e-5)*63),0,63),cy=clamp((int)((rayY[i]-y0)/Math.max(y1-y0,1e-5)*63),0,63);
    int cell=cx+64*cy;ids[i]=cell;counts[cell]++;int p=sources[i]*3;
    float r=input.color.get(p),g=input.color.get(p+1),b=input.color.get(p+2);
    sums[0][cell]+=r;sums[1][cell]+=g;sums[2][cell]+=b;squares[cell]+=(r*r+g*g)+b*b;
   }
   double[] detail=new double[4096];
   for(int cell=0;cell<4096;cell++){
    for(int c=0;c<3;c++)sums[c][cell]/=Math.max(counts[cell],1);
    detail[cell]=Math.max(0,squares[cell]/Math.max(counts[cell],1)-sums[0][cell]*sums[0][cell]-sums[1][cell]*sums[1][cell]-sums[2][cell]*sums[2][cell]);
   }
   for(int cell=0;cell<4096;cell++)if(counts[cell]>0){
    if(cell>=64&&counts[cell-64]>0)detail[cell]+=colorDelta(sums,cell,cell-64)*.25;
    if(cell%64!=0&&counts[cell-1]>0)detail[cell]+=colorDelta(sums,cell,cell-1)*.25;
   }
   float[] occupiedCounts=new float[4096];int occupied=0;for(int count:counts)if(count>0)occupiedCounts[occupied++]=count;
   double densityScale=Math.max(quantile(Arrays.copyOf(occupiedCounts,occupied),.75,cancelled),1);
   float[] contrast=new float[valid];for(int i=0;i<valid;i++){if((i&4095)==0)check(cancelled);contrast[i]=(float)Math.sqrt(detail[ids[i]]);}
   double contrastScale=Math.max(quantile(contrast,.90,cancelled),.015);float median=(float)quantile(z,.5,cancelled);
   for(int i=0;i<valid;i++){
    if((i&4095)==0)check(cancelled);double contrastValue=clamp(contrast[i]/contrastScale,0,1);
    float proximity=(float)clamp(Math.pow(median/Math.max(z[i],1e-5f),1.25),.025,6);
    double density=clamp(Math.sqrt(counts[ids[i]]/densityScale),.5,1.5);
    weights[i]=(float)(input.alpha.get(sources[i])*(.07+.93*contrastValue)*proximity*density);
   }
   adaptive=quantile(z,.95,cancelled)/Math.max(quantile(z,.10,cancelled),1e-5)>3;
   if(adaptive){
    reference=(float)weightedQuantile(z,weights,.5,cancelled);
    for(int i=0;i<valid;i++){
     if((i&4095)==0)check(cancelled);float depth=Math.max(z[i],1e-5f),warped=warpedDepth(depth,reference),factor=warped/depth;
     x[i]*=factor;y[i]*=factor;z[i]=warped;
    }
   }
  }
  double[] low={quantile(x,.005,cancelled),quantile(y,.005,cancelled),quantile(z,.005,cancelled)};
  double[] high={quantile(x,.995,cancelled),quantile(y,.995,cancelled),quantile(z,.995,cancelled)};
  int keptCount=0;for(int i=0;i<valid;i++){if((i&4095)==0)check(cancelled);if(inside(x[i],y[i],z[i],low,high))keptCount++;}
  if(keptCount<100)throw new IOException("有效粒子不足");
  int[] kept=new int[keptCount];float[] keptWeights=new float[keptCount];
  for(int i=0,j=0;i<valid;i++){if((i&4095)==0)check(cancelled);if(inside(x[i],y[i],z[i],low,high)){kept[j]=i;keptWeights[j]=weights[i];j++;}}
  double[] focusLow=low.clone(),focusHigh=high.clone();float threshold=0;
  if(adaptive){
   for(int axis=0;axis<3;axis++){
    float[] original=axis==0?x:axis==1?y:z,values=new float[keptCount];for(int i=0;i<keptCount;i++){if((i&4095)==0)check(cancelled);values[i]=original[kept[i]];}
    focusLow[axis]=weightedQuantile(values,keptWeights,.02,cancelled);focusHigh[axis]=weightedQuantile(values,keptWeights,.98,cancelled);
   }
   threshold=(float)quantile(keptWeights,.40,cancelled);
  }
  return new Analysis(sources,kept,x,y,z,weights,adaptive,reference,low,high,focusLow,focusHigh,threshold);
 }

 static int write(Input input,Analysis analysis,int limit,OutputStream output)throws IOException{return write(input,analysis,limit,output,null);}
 static int write(Input input,Analysis analysis,int limit,OutputStream output,BooleanSupplier cancelled)throws IOException{
  check(cancelled);
  if(limit<1)throw new IllegalArgumentException("Display particle limit must be positive");
  int count=Math.min(limit,analysis.kept.length);double boost=Math.min(1.65,Math.sqrt((double)analysis.kept.length/count));
  ByteBuffer row=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);float[] record=new float[16];
  for(int j=0;j<count;j++){
   if((j&4095)==0)check(cancelled);
   // np.linspace(0, kept-1, count, dtype=int64), including both endpoints.
   int at=count==1?0:(int)((long)j*(analysis.kept.length-1)/(count-1));
   record(input,analysis,analysis.kept[at],boost,record);
   row.clear();for(float value:record){if(!Float.isFinite(value))throw new IOException("显示粒子包含无效数值");row.putFloat(value);}output.write(row.array());
  }
  return count;
 }

 /** A = box-axis transform * ray-depth Jacobian * original NDC unprojection.
  * Covariance is A R diag(scale^2) R^T A^T; opacity and linear RGB are unchanged.
  */
 static void record(Input input,Analysis a,int point,double boost,float[] out){
  Arrays.fill(out,0);int source=a.sources[point],k=source*3;
  double xy=1.85/Math.max(Math.max(a.high[0]-a.low[0],a.high[1]-a.low[1]),1e-6);
  double[] transform={xy,-xy,-Math.min(xy,.72/Math.max(a.high[2]-a.low[2],1e-6))};
  out[0]=(float)((a.x[point]-(a.low[0]+a.high[0])/2)*transform[0]);
  out[1]=(float)((a.y[point]-(a.low[1]+a.high[1])/2)*transform[1]);
  out[2]=(float)((a.z[point]-(a.low[2]+a.high[2])/2)*transform[2]);out[3]=input.alpha.get(source);
  double w=input.quat.get(source*4),x=input.quat.get(source*4+1),y=input.quat.get(source*4+2),z=input.quat.get(source*4+3);
  double length=Math.sqrt(w*w+x*x+y*y+z*z);if(length<1e-8)length=1;w/=length;x/=length;y/=length;z/=length;
  double[] r={1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)};
  double factor=1,derivative=1,jx=0,jy=0;
  if(a.adaptive){
   float depth=Math.max(input.mean.get(k+2),1e-5f),t=(float)Math.tanh((float)(Math.log(depth/a.reference)/1.5));
   float warped=a.reference*(1+.60f*t);float d=a.reference*.60f/1.5f*(1-t*t)/depth;
   factor=warped/depth;derivative=d;float df=d/depth-warped/(depth*depth);
   jx=input.mean.get(k)*input.sx*df;jy=input.mean.get(k+1)*input.sy*df;
  }
  double[] basis=new double[9];
  for(int column=0;column<3;column++){
   double scale=input.scale.get(k+column),bx=input.sx*r[column]*scale,by=input.sy*r[3+column]*scale,bz=r[6+column]*scale;
   basis[column]=transform[0]*(factor*bx+jx*bz);basis[3+column]=transform[1]*(factor*by+jy*bz);basis[6+column]=transform[2]*derivative*bz;
  }
  for(int i=0,at=4;i<3;i++)for(int j=i;j<3;j++)out[at++]=(float)((basis[i*3]*basis[j*3]+basis[i*3+1]*basis[j*3+1]+basis[i*3+2]*basis[j*3+2])*boost);
  out[10]=a.selected(point)?1:0;out[12]=input.color.get(k);out[13]=input.color.get(k+1);out[14]=input.color.get(k+2);
 }

 private static boolean valid(Input input,int point){
  if(!(input.alpha.get(point)>.015f)||!Float.isFinite(input.alpha.get(point)))return false;
  for(int c=0;c<3;c++)if(!Float.isFinite(input.mean.get(point*3+c))||!Float.isFinite(input.scale.get(point*3+c))||!Float.isFinite(input.color.get(point*3+c)))return false;
  for(int c=0;c<4;c++)if(!Float.isFinite(input.quat.get(point*4+c)))return false;
  return true;
 }
 static float warpedDepth(float depth,float reference){
  float logarithm=(float)Math.log(Math.max(depth,1e-5f)/reference);
  float t=(float)Math.tanh(logarithm/1.5f);
  return reference*(1+.60f*t);
 }
 private static boolean inside(float x,float y,float z,double[] low,double[] high){return x>=low[0]&&x<=high[0]&&y>=low[1]&&y<=high[1]&&z>=low[2]&&z<=high[2];}
 private static int clamp(int x,int low,int high){return Math.max(low,Math.min(high,x));}
 private static double clamp(double x,double low,double high){return Math.max(low,Math.min(high,x));}
 private static double colorDelta(double[][] colors,int a,int b){double r=colors[0][a]-colors[0][b],g=colors[1][a]-colors[1][b],c=colors[2][a]-colors[2][b];return r*r+g*g+c*c;}
 static double quantile(float[] values,double q){float[] sorted=values.clone();Arrays.sort(sorted);return sortedQuantile(sorted,q);}
 private static double quantile(float[] values,double q,BooleanSupplier cancelled)throws InterruptedIOException{
  check(cancelled);double result=quantile(values,q);check(cancelled);return result;
 }
 private static double sortedQuantile(float[] sorted,double q){double p=(sorted.length-1)*q;int low=(int)p,high=Math.min(low+1,sorted.length-1);return sorted[low]+(sorted[high]-sorted[low])*(p-low);}
 static double weightedQuantile(float[] values,float[] weights,double q){
  try{return weightedQuantile(values,weights,q,null);}catch(InterruptedIOException stopped){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Display preparation cancelled");}
 }
 private static double weightedQuantile(float[] values,float[] weights,double q,BooleanSupplier cancelled)throws InterruptedIOException{
  check(cancelled);
  int n=values.length;int[] order=new int[n],scratch=new int[n];for(int i=0;i<n;i++){if((i&4095)==0)check(cancelled);order[i]=i;}
  stableSort(order,scratch,values,0,n,cancelled);float[] sortedWeights=new float[n];for(int i=0;i<n;i++){if((i&4095)==0)check(cancelled);sortedWeights[i]=Math.max(weights[order[i]],1e-12f);}
  float total=pairwiseSum(sortedWeights,0,n,cancelled),cumulative=0,previous=0;
  for(int i=0;i<n;i++){
   if((i&4095)==0)check(cancelled);cumulative+=sortedWeights[i];float at=(cumulative-sortedWeights[i]*.5f)/total;
   if(q<=at){if(i==0)return values[order[0]];double fraction=(q-previous)/Math.max((double)at-previous,1e-30);return values[order[i-1]]+(values[order[i]]-values[order[i-1]])*fraction;}
   previous=at;
  }
  return values[order[n-1]];
 }
 private static void stableSort(int[] order,int[] scratch,float[] values,int start,int end,BooleanSupplier cancelled)throws InterruptedIOException{
  if(end-start<2)return;int middle=(start+end)>>>1;stableSort(order,scratch,values,start,middle,cancelled);stableSort(order,scratch,values,middle,end,cancelled);
  int i=start,j=middle,p=start;while(i<middle&&j<end){if((p&4095)==0)check(cancelled);scratch[p++]=values[order[i]]<=values[order[j]]?order[i++]:order[j++];}while(i<middle){if((p&4095)==0)check(cancelled);scratch[p++]=order[i++];}while(j<end){if((p&4095)==0)check(cancelled);scratch[p++]=order[j++];}System.arraycopy(scratch,start,order,start,end-start);
 }
 private static void check(BooleanSupplier cancelled)throws InterruptedIOException{
  if(Thread.currentThread().isInterrupted()||(cancelled!=null&&cancelled.getAsBoolean()))throw new InterruptedIOException("已取消");
 }
 // Match NumPy's float32 pairwise denominator and float32 cumulative weights.
 private static float pairwiseSum(float[] a,int start,int n,BooleanSupplier cancelled)throws InterruptedIOException{
  if(n>=4096)check(cancelled);
  if(n<8){float s=-0.0f;for(int i=0;i<n;i++)s+=a[start+i];return s;}
  if(n<=128){float[] s=Arrays.copyOfRange(a,start,start+8);int i=8;for(;i<n-7;i+=8)for(int j=0;j<8;j++)s[j]+=a[start+i+j];float total=((s[0]+s[1])+(s[2]+s[3]))+((s[4]+s[5])+(s[6]+s[7]));for(;i<n;i++)total+=a[start+i];return total;}
  int half=(n/2)&~7;return pairwiseSum(a,start,half,cancelled)+pairwiseSum(a,start+half,n-half,cancelled);
 }
}
