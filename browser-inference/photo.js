// Decode before allocating the model. HTMLImageElement avoids mobile Worker decoder differences.
export async function preparePhoto(photo,resolution=1536){
 const image=new Image(),url=URL.createObjectURL(photo);let timer,canvas;
 try{
 await Promise.race([new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=()=>reject(Error('无法读取这张图片，请重新选择照片，或转换为 JPG / PNG 后重试。'));image.src=url;}),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('图片读取超时，请重新选择照片。')),20000);})]);
 const width=image.naturalWidth,height=image.naturalHeight;
 if(!width||!height||width*height>40000000)throw Error('请选择小于 4000 万像素的有效照片。');
 canvas=document.createElement('canvas');canvas.width=canvas.height=resolution;
 const ctx=canvas.getContext('2d',{willReadFrequently:true});if(!ctx)throw Error('手机无法分配图片处理内存，请关闭其他标签后重试。');
 ctx.fillStyle='#fff';ctx.fillRect(0,0,resolution,resolution);ctx.drawImage(image,0,0,resolution,resolution);
 return {width,height,pixels:ctx.getImageData(0,0,resolution,resolution).data};
 }finally{clearTimeout(timer);image.src='';URL.revokeObjectURL(url);if(canvas)canvas.width=canvas.height=1;}
}
