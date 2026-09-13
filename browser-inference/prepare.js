// Metric Gaussian covariance conversion and bounded subject framing.
// Uses the same camera-ray compression and J C J^T math as prepare_memory.py.
const quantile=(a,q)=>a[Math.min(a.length-1,Math.floor(q*(a.length-1)))];
export function toFloat(t){
 if(t.data instanceof Float32Array)return t.data;
 if(t.type!=='float16')throw Error('模型输出格式不兼容');
 const bits=new Uint16Array(t.data.buffer,t.data.byteOffset,t.data.byteLength/2),a=new Float32Array(bits.length);
 for(let i=0;i<a.length;i++){const b=bits[i],s=b&32768?-1:1,e=(b>>10)&31,f=b&1023;a[i]=s*(e===0?f*2**-24:e===31?(f?NaN:Infinity):(1+f/1024)*2**(e-15));}return a;
}
export function half(a){const out=new Uint16Array(a.length),v=new Float32Array(1),u=new Uint32Array(v.buffer);for(let i=0;i<a.length;i++){v[0]=a[i];const b=u[0],sign=(b>>>16)&32768,e=((b>>>23)&255)-112,m=b&8388607;out[i]=e<=0?(e< -10?sign:sign|(((m|8388608)>>(1-e))+4096>>13)):e>=31?sign|31744:sign|((e<<10)+(m+4096>>13));}return out;}
export function prepare(outputs,width,height,focal,limit=500000,gridWidth=768){
 const means=toFloat(outputs.mean_vectors_ndc),scales=toFloat(outputs.singular_values_ndc),q=toFloat(outputs.quaternions_ndc),colors=toFloat(outputs.colors),alpha=toFloat(outputs.opacities),n=alpha.length;
 if(means.length!==n*3||scales.length!==n*3||q.length!==n*4||colors.length!==n*3)throw Error('模型输出尺寸不匹配');
 const sx=width/(2*focal),sy=height/(2*focal),samples=[];
 for(let i=0;i<n;i+=Math.max(1,Math.floor(n/60000))){if(alpha[i]>.015&&means[i*3+2]>0&&Number.isFinite(means[i*3]))samples.push(i);}
 if(samples.length<100)throw Error('照片未能生成有效的空间内容');
 const depths=samples.map(i=>means[i*3+2]).sort((a,b)=>a-b),median=quantile(depths,.5),adaptive=quantile(depths,.95)/quantile(depths,.1)>3;
 // Favor near, opaque detail over a distant sky; regular image-grid neighbors estimate contrast.
 const weights=samples.map(i=>{let detail=0;for(const j of [i-1,i+1,i-gridWidth,i+gridWidth])if(j>=0&&j<n)for(let c=0;c<3;c++)detail+=Math.abs(colors[i*3+c]-colors[j*3+c]);return alpha[i]*(.07+Math.min(1,detail*3))*Math.min(6,(median/means[i*3+2])**1.25);});
 const ranked=samples.map((i,k)=>[means[i*3+2],weights[k]]).sort((a,b)=>a[0]-b[0]);let total=weights.reduce((a,b)=>a+b,0),sum=0,reference=median;for(const [z,w]of ranked){sum+=w;if(sum>=total*.5){reference=z;break;}}
 function warp(x,y,z){const t=Math.tanh(Math.log(Math.max(z,1e-6)/reference)/1.5),g=adaptive?reference*(1+.6*t):z,f=g/z;return [x*sx*f,-y*sy*f,-g];}
 const points=samples.map(i=>warp(...means.subarray(i*3,i*3+3))),bounds=[0,1,2].map(c=>points.map(p=>p[c]).sort((a,b)=>a-b)),lo=bounds.map(a=>quantile(a,.005)),hi=bounds.map(a=>quantile(a,.995));
 const threshold=quantile([...weights].sort((a,b)=>a-b),.40);
 const focus=points.filter((p,i)=>!adaptive||weights[i]>=threshold),fb=[0,1,2].map(c=>focus.map(p=>p[c]).sort((a,b)=>a-b)),fl=fb.map(a=>quantile(a,.02)),fh=fb.map(a=>quantile(a,.98));
 const norm=1.85/Math.max(hi[0]-lo[0],hi[1]-lo[1],1e-5),tz=Math.min(norm,.72/Math.max(hi[2]-lo[2],1e-5)),center=lo.map((v,i)=>(v+hi[i])/2);
 const out=new Float32Array(Math.min(n,limit)*16),stride=n/Math.min(n,limit);let count=0;
 for(let j=0;j<Math.min(n,limit);j++){
  const i=Math.floor(j*stride),k=i*3,z=means[k+2];if(alpha[i]<=.015||!(z>0))continue;
  const p=warp(means[k],means[k+1],z);if(!p.every((v,c)=>Number.isFinite(v)&&v>=lo[c]&&v<=hi[c]))continue;
  const t=Math.tanh(Math.log(z/reference)/1.5),g=adaptive?reference*(1+.6*t):z,dg=adaptive?reference*.6/1.5*(1-t*t)/z:1,f=g/z,df=dg/z-g/(z*z);
  const J=[norm*sx*f,0,norm*sx*means[k]*df,0,-norm*sy*f,-norm*sy*means[k+1]*df,0,0,-tz*dg];
  let [w,x,y,zz]=q.subarray(i*4,i*4+4);const len=Math.hypot(w,x,y,zz)||1;w/=len;x/=len;y/=len;zz/=len;
  const R=[1-2*(y*y+zz*zz),2*(x*y-zz*w),2*(x*zz+y*w),2*(x*y+zz*w),1-2*(x*x+zz*zz),2*(y*zz-x*w),2*(x*zz-y*w),2*(y*zz+x*w),1-2*(x*x+y*y)],B=new Float64Array(9);
  for(let r=0;r<3;r++)for(let c=0;c<3;c++)for(let v=0;v<3;v++)B[r*3+c]+=J[r*3+v]*R[v*3+c]*scales[k+c];
  const cov=(r,c)=>B[r*3]*B[c*3]+B[r*3+1]*B[c*3+1]+B[r*3+2]*B[c*3+2],base=count*16;
  out[base]=(p[0]-center[0])*norm;out[base+1]=(p[1]-center[1])*norm;out[base+2]=(p[2]-center[2])*tz;out[base+3]=alpha[i];
  out[base+4]=cov(0,0);out[base+5]=cov(0,1);out[base+6]=cov(0,2);out[base+7]=cov(1,1);out[base+8]=cov(1,2);out[base+9]=cov(2,2);
  out[base+10]=adaptive&&p.every((v,c)=>v>=fl[c]&&v<=fh[c])?1:0;out.set(colors.subarray(k,k+3),base+12);
  if(out.subarray(base,base+16).every(Number.isFinite))count++;
 }
 if(count<100)throw Error('有效粒子不足，请换一张照片');return out.buffer.slice(0,count*64);
}
