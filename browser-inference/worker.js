import {bounded,modelFile} from './download.js?v=mirror-20260913-1';
let ort;
import {prepare,half} from './prepare.js';
let session=null,busy=false;
let lastStep='启动本机任务';
const status=(text,phase='loading')=>{lastStep=text;postMessage({type:'status',text,phase});};
async function load(){
 if(session)return session;
 status('正在检测后台 GPU 支持');
 const adapter=await bounded(navigator.gpu?.requestAdapter({powerPreference:'high-performance'}),15000,'手机浏览器后台 GPU 检测超时，请更新浏览器后重试');
 if(!adapter?.features.has('shader-f16'))throw Error('这台设备不支持所需的 WebGPU 半精度计算，请更新支持 WebGPU 的浏览器');
 status('正在读取模型配置');
 const config=await bounded(fetch(new URL('./model.json?v=mirror-20260913-1',import.meta.url)).then(r=>{if(!r.ok)throw Error('模型配置加载失败');return r.json();}),20000,'模型配置加载超时，请检查网络');
 const total=config.graphBytes+config.weightsBytes;
 const graph=await modelFile(config,config.graph,config.graphBytes,{total,report:postMessage.bind(self)});
 const weights=await modelFile(config,config.weights,config.weightsBytes,{offset:config.graphBytes,total,report:postMessage.bind(self)});
 status('正在加载本机推理组件');
 ort=await bounded(import('./ort/ort.webgpu.min.js'),30000,'推理组件加载超时，请刷新页面后重试');
 ort.env.wasm.numThreads=1;
ort.env.wasm.wasmPaths={mjs:new URL('./ort/ort-wasm-simd-threaded.asyncify.js',import.meta.url).href,wasm:new URL('./ort/ort-wasm-simd-threaded.asyncify.wasm',import.meta.url).href};
 status('正在初始化本机 GPU，首次可能需要几分钟','initializing');
 session=await ort.InferenceSession.create(graph,{executionProviders:[{name:'webgpu',preferredLayout:'NHWC'}],externalData:[{path:config.weights,data:weights}],graphOptimizationLevel:'all',enableMemPattern:false,enableCpuMemArena:false,executionMode:'sequential',extra:{session:{disable_prepacking:'1',use_device_allocator_for_initializers:'0',use_ort_model_bytes_directly:'1',use_ort_model_bytes_for_initializers:'1'}}});
 return session;
}
self.onmessage=async({data})=>{
 if(busy)return;busy=true;status('本机任务已启动');
 try{
 if(data.type==='probe'){const adapter=await bounded(navigator.gpu?.requestAdapter({powerPreference:'high-performance'}),10000,'后台 GPU 检测超时');if(!adapter?.features.has('shader-f16'))throw Error('浏览器后台不支持所需 GPU 计算');postMessage({type:'probe-ready'});return;}
 const {width,height}=data.prepared||{};let pixels=data.prepared?.pixels;
 if(!width||!height||pixels?.length!==1536*1536*4)throw Error('图片预处理未完成，请重新选择照片。');
 const s=await load();
 status('正在用你的 GPU 重建照片空间','inference');
 const N=1536*1536,isHalf=s.inputMetadata[0].type==='float16',a=isHalf?new Uint16Array(N*3):new Float32Array(N*3),lut=isHalf?half(Float32Array.from({length:256},(_,i)=>i/255)):null;for(let i=0;i<N;i++)for(let c=0;c<3;c++)a[c*N+i]=isHalf?lut[pixels[i*4+c]]:pixels[i*4+c]/255;pixels=null;data.prepared=null;
 const focal=30*Math.hypot(width,height)/Math.hypot(36,24),feeds={};
 for(const [idx,values,dims]of [[0,a,[1,3,1536,1536]],[1,new Float32Array([focal/width]),[1]]]){const type=s.inputMetadata[idx].type;feeds[s.inputNames[idx]]=new ort.Tensor(type,type==='float16'&&!(values instanceof Uint16Array)?half(values):values,dims);}
 let outputs;try{outputs=await s.run(feeds);}finally{Object.values(feeds).forEach(t=>t.dispose());}
 await s.release();session=null;
 status('正在整理粒子、构图和空间层次','packing');
 let buffer;try{buffer=prepare(outputs,width,height,focal);}finally{Object.values(outputs).forEach(t=>t.dispose());}
 postMessage({type:'complete',buffer},[buffer]);
 }catch(e){postMessage({type:'error',text:e instanceof TypeError?lastStep+'失败：网络请求未完成，请切换网络并刷新后重试。照片未上传。':String(e.message||e)});}finally{busy=false;}
};
