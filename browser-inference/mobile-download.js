import {CACHE,FILES,cached} from './mobile-model.js';
const BASE='/models/gemos-still-lite-v1/';
const total=FILES.reduce((sum,f)=>sum+f.size,0);
async function verified(blob,spec){
 if(blob.size!==spec.size)return false;await import('./ort-cpu/sha256.js');const hash=await globalThis.hashwasm.createSHA256();hash.init();
 for(let p=0;p<blob.size;p+=4*1024*1024)hash.update(new Uint8Array(await blob.slice(p,p+4*1024*1024).arrayBuffer()));
 return hash.digest()===spec.sha;
}
export async function downloadModel(report){
 const existing=await cached();if(existing)return existing;
 let dir;try{dir=await(await navigator.storage.getDirectory()).getDirectoryHandle(CACHE,{create:true});}catch{throw Error('当前浏览器无法缓存轻量模型，请更新浏览器，或在「模型与缓存」中导入已下载的模型包。');}
 await dir.removeEntry('ready.json').catch(()=>{});let offset=0;
 for(const spec of FILES){
 const handle=await dir.getFileHandle(spec.name,{create:true});let complete=false;
 for(let attempt=0;attempt<3&&!complete;attempt++){
 let file=await handle.getFile(),start=file.size;
 if(start===spec.size){report({type:'status',phase:'download',loaded:offset+start,total,text:'正在校验轻量模型…'});if(await verified(file,spec)){complete=true;break;}start=0;}
 if(start>spec.size)start=0;
 const controller=new AbortController();let timer=setTimeout(()=>controller.abort(),30000),writer=null,reader;
 try{
 report({type:'status',phase:'download',loaded:offset+start,total,text:attempt?'连接中断，正在续传轻量模型…':start?'正在续传轻量模型…':'正在从本站下载轻量模型…'});
 const response=await fetch(BASE+spec.name,{headers:start?{Range:`bytes=${start}-`}:{},signal:controller.signal});clearTimeout(timer);
 if(!response.ok||![200,206].includes(response.status))throw Error('模型下载暂不可用');
 if(response.status===200)start=0;
 else if(!start||response.headers.get('Content-Range')!==`bytes ${start}-${spec.size-1}/${spec.size}`)throw Error('模型续传响应无效');
 const length=Number(response.headers.get('Content-Length'));if(length!==spec.size-start)throw Error('模型下载大小不匹配');
 writer=await handle.createWritable({keepExistingData:start>0});await writer.seek(start);reader=response.body.getReader();let received=start,last=0;
 while(true){timer=setTimeout(()=>controller.abort(),45000);const {value,done}=await reader.read();clearTimeout(timer);if(done)break;received+=value.length;if(received>spec.size)throw Error('模型下载超出预期大小');await writer.write(value);if(Date.now()-last>150){last=Date.now();report({type:'status',phase:'download',loaded:offset+received,total,text:'正在从本站下载轻量模型…'});}}
 await writer.close();writer=null;file=await handle.getFile();report({type:'status',phase:'download',loaded:offset+file.size,total,text:'正在校验轻量模型…'});
 if(!await verified(file,spec)){const reset=await handle.createWritable();await reset.close();throw Error('模型文件不完整，正在重新获取');}complete=true;
 }catch(e){await writer?.close().catch(()=>{});if(attempt===2)throw Error('轻量模型下载未完成。请检查网络后重试，已保存的部分会继续下载。');}
 finally{clearTimeout(timer);controller.abort();reader?.releaseLock();}
 }
 offset+=spec.size;
 }
 const marker=await(await dir.getFileHandle('ready.json',{create:true})).createWritable();await marker.write(JSON.stringify({version:1}));await marker.close();return await cached();
}
