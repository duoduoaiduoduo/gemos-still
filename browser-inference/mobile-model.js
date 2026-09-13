// Only the explicitly published Android Lite package is accepted. Import streams to OPFS.
export const CACHE='still-sharp-lite256-v1';
export const FILES=[
 {name:'lite256int8.onnx',size:9033925,sha:'b4c7384ceed5587ff9b9813e44bae167c2a2081bd00e1ae7a9f1487618b821ac'},
 {name:'lite256int8.onnx.data',size:799551232,sha:'42fe29c22b0cb190cda01924277d7df2ea4c80f473839d71821a53e91704998a'}
];
export async function entries(file){
 if(!file||file.size!==808585405)throw Error('请选择 v0.4 的 Gemos-Still-Lite-256.gemosmodel 轻量模型包（约 809 MB）。');
 const start=Math.max(0,file.size-65557),tail=new DataView(await file.slice(start).arrayBuffer());let end=-1;
 for(let p=tail.byteLength-22;p>=0;p--)if(tail.getUint32(p,true)===0x06054b50&&p+22+tail.getUint16(p+20,true)===tail.byteLength){end=p;break;}
 if(end<0||tail.getUint16(end+10,true)!==2)throw Error('模型包目录损坏，请重新下载。');
 const offset=tail.getUint32(end+16,true),size=tail.getUint32(end+12,true);
 if(size>4096||offset+size>start+end)throw Error('模型包目录无效。');
 const bytes=await file.slice(offset,offset+size).arrayBuffer(),view=new DataView(bytes),result={};let p=0;
 for(let i=0;i<2;i++){
 if(p+46>size||view.getUint32(p,true)!==0x02014b50)throw Error('模型包目录无效。');
 const n=view.getUint16(p+28,true),x=view.getUint16(p+30,true),c=view.getUint16(p+32,true),len=view.getUint32(p+24,true),at=view.getUint32(p+42,true);
 if(p+46+n+x+c>size)throw Error('模型包目录无效。');
 const name=new TextDecoder().decode(new Uint8Array(bytes,p+46,n)),spec=FILES.find(f=>f.name===name);
 if(!spec||result[name]||spec.size!==len||view.getUint32(p+20,true)!==len||view.getUint16(p+10,true)!==0||(view.getUint16(p+8,true)&1))throw Error('模型包版本或格式不匹配。');
 const h=new DataView(await file.slice(at,at+30).arrayBuffer());if(h.byteLength!==30||h.getUint32(0,true)!==0x04034b50)throw Error('模型包文件头损坏。');
 const begin=at+30+h.getUint16(26,true)+h.getUint16(28,true);if(begin+len>offset)throw Error('模型包文件范围无效。');
 result[name]=file.slice(begin,begin+len);p+=46+n+x+c;
 }return result;
}
export async function cached(){
 try{const dir=await(await navigator.storage.getDirectory()).getDirectoryHandle(CACHE);const marker=JSON.parse(await(await(await dir.getFileHandle('ready.json')).getFile()).text());if(marker.version!==1)return null;const out={};for(const f of FILES){const blob=await(await dir.getFileHandle(f.name)).getFile();if(blob.size!==f.size)return null;out[f.name]=blob;}return out;}catch{return null;}
}
export async function importModel(file,report){
 const blobs=await entries(file);await import('./ort-cpu/sha256.js');let dir=null;
 try{const root=await navigator.storage.getDirectory();dir=await root.getDirectoryHandle(CACHE,{create:true});await dir.removeEntry('ready.json').catch(()=>{});}catch{}
 let loaded=0;const total=FILES.reduce((a,f)=>a+f.size,0);
 for(const f of FILES){const hash=await globalThis.hashwasm.createSHA256();hash.init();let writer=null;
 try{if(dir)writer=await(await dir.getFileHandle(f.name,{create:true})).createWritable();
 for(let p=0;p<f.size;p+=4*1024*1024){const chunk=new Uint8Array(await blobs[f.name].slice(p,p+4*1024*1024).arrayBuffer());hash.update(chunk);if(writer)await writer.write(chunk);loaded+=chunk.length;report({type:'status',phase:'download',loaded,total,text:'正在校验并导入轻量模型…'});}
 if(hash.digest()!==f.sha)throw Error('模型包校验失败，请重新下载完整文件。');if(writer)await writer.close();
 }catch(e){await writer?.abort().catch(()=>{});throw e;}}
 if(dir){const w=await(await dir.getFileHandle('ready.json',{create:true})).createWritable();await w.write(JSON.stringify({version:1}));await w.close();}
 return !!dir;
}
