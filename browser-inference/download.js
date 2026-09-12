// Byte-based progress across both ONNX files; storage is optional, never a prerequisite.
export async function bounded(p,ms,message){let timer;try{return await Promise.race([p,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error(message)),ms);})]);}finally{clearTimeout(timer);}}
export async function modelFile(config,name,size,{offset=0,total=size,report=()=>{},cacheMs=8000,networkMs=30000,stallMs=45000}={}){
 const emit=(text,loaded=0)=>report({type:'status',phase:'download',text,loaded:offset+loaded,total});
 emit('正在检查本机模型缓存…');
 let handle;
 try{handle=await bounded((async()=>{const root=await navigator.storage.getDirectory();const dir=await root.getDirectoryHandle('still-sharp-'+config.revision,{create:true});return dir.getFileHandle(name,{create:true});})(),cacheMs,'缓存检查超时');}catch{emit('缓存不可用，准备直接下载…');}
 if(handle){try{const file=await bounded(handle.getFile(),cacheMs,'缓存读取超时');if(file.size===size){emit('正在读取已缓存模型…');const data=new Uint8Array(await bounded(file.arrayBuffer(),30000,'缓存读取超时'));emit('已读取本机缓存',size);return data;}}catch{handle=null;}}
 emit('正在连接模型源，请稍候…');
 const controller=new AbortController();let reader,writer;
 try{
 const response=await bounded(fetch(config.base+name,{signal:controller.signal}),networkMs,'连接模型源超时，请检查网络后重试。照片未上传。');
 if(!response.ok)throw Error(`模型源返回 ${response.status}，请稍后重试。`);
 if(!response.body)throw Error('浏览器不支持流式下载，请换用新版 Chrome。');
 reader=response.body.getReader();
 if(handle)try{writer=await bounded(handle.createWritable(),cacheMs,'缓存写入准备超时');}catch{emit('无法写入缓存，本次直接下载…');}
 const data=writer?null:new Uint8Array(size);let loaded=0,last=0;
 while(true){const {done,value}=await bounded(reader.read(),stallMs,'模型下载长时间无数据，请检查网络后重试。');if(done)break;
 if(loaded+value.length>size)throw Error('模型文件大小不符，请重新下载。');
 if(writer)await bounded(writer.write(value),15000,'本机缓存写入超时，请清除模型缓存后重试。');else data.set(value,loaded);
 loaded+=value.length;
 if(performance.now()-last>200||loaded===size){emit(`正在下载模型 · ${Math.round((offset+loaded)/1048576)} / ${Math.round(total/1048576)} MB`,loaded);last=performance.now();}
 }
 if(loaded!==size)throw Error('模型下载不完整，请重试。');
 if(writer){await bounded(writer.close(),15000,'模型缓存保存超时');writer=null;emit('下载完成，正在读取模型…',size);const file=await bounded(handle.getFile(),cacheMs,'模型缓存读取超时');return new Uint8Array(await bounded(file.arrayBuffer(),30000,'模型缓存读取超时'));}
 emit('模型文件下载完成',size);return data;
 }catch(e){controller.abort();reader?.cancel().catch(()=>{});if(writer)writer.abort().catch(()=>{});throw e;}
}
