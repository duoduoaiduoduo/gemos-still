import {cached,entries,importModel,FILES} from './mobile-model.js';
import {downloadModel} from './mobile-download.js';
import {prepare} from './prepare.js?v=lite-1';
let busy=false;
const status=(text,phase='loading')=>postMessage({type:'status',text,phase});
self.onmessage=async({data})=>{
 if(busy)return;busy=true;let session,outputs,feeds;
 try{
 if(data.type==='probe'){if(typeof WebAssembly!=='object')throw Error('浏览器不支持本机 CPU 计算，请更新浏览器。');postMessage({type:'probe-ready',cached:!!await cached()});return;}
 if(data.type==='import-model'){const stored=await importModel(data.file,postMessage.bind(self));postMessage({type:'model-ready',cached:stored});return;}
 const prepared=data.prepared;if(prepared?.pixels?.length!==256*256*4)throw Error('图片预处理失败，请重新选择照片。');
 status('正在检查本机轻量模型');let blobs=await cached();if(!blobs&&data.file)blobs=await entries(data.file);if(!blobs)blobs=await downloadModel(postMessage.bind(self));
 const ort=await import('./ort-cpu/ort.wasm.min.js');ort.env.wasm.numThreads=1;ort.env.wasm.proxy=false;
 ort.env.wasm.wasmPaths={mjs:new URL('./ort-cpu/ort-wasm-simd-threaded.js',import.meta.url).href,wasm:new URL('./ort-cpu/ort-wasm-simd-threaded.wasm',import.meta.url).href};
 status('正在初始化轻量 CPU 模型，请保持前台','initializing');
 session=await ort.InferenceSession.create(new Uint8Array(await blobs[FILES[0].name].arrayBuffer()),{executionProviders:['wasm'],externalData:[{path:FILES[1].name,data:blobs[FILES[1].name]}],graphOptimizationLevel:'all',enableMemPattern:true,enableCpuMemArena:true,executionMode:'sequential',extra:{session:{disable_prepacking:'1'}}});blobs=null;data.file=null;
 const N=256*256,a=new Float32Array(N*3);for(let i=0;i<N;i++)for(let c=0;c<3;c++)a[c*N+i]=prepared.pixels[i*4+c]/255;prepared.pixels=null;
 const focal=30*Math.hypot(prepared.width,prepared.height)/Math.hypot(36,24);
 feeds={image:new ort.Tensor('float32',a,[1,3,256,256]),disparity_factor:new ort.Tensor('float32',new Float32Array([focal/prepared.width]),[1])};
 status('正在用手机 CPU 构筑空间，可能需要数分钟','inference');outputs=await session.run(feeds);
 Object.values(feeds).forEach(t=>t.dispose());feeds=null;await session.release();session=null;
 status('正在整理空间与粒子','packing');const buffer=prepare(outputs,prepared.width,prepared.height,focal,32768,128);postMessage({type:'complete',buffer},[buffer]);
 }catch(e){postMessage({type:'error',text:String(e.message||e)});}finally{Object.values(feeds||{}).forEach(t=>t.dispose());Object.values(outputs||{}).forEach(t=>t.dispose());await session?.release();busy=false;}
};
