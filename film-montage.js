import {Input,BlobSource,MP4,CanvasSink} from './vendor/mediabunny.js';
import {FILM} from './film-timeline.js';
const ease=t=>{t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);};
export async function prepareMontage(fps,signal){
 const r=await fetch(new URL('./artwork/montage/montage-60.mp4?v=gallery-1',import.meta.url),{signal});if(!r.ok)throw Error('九宫格片段未能加载，请重试');
 const input=new Input({source:new BlobSource(await r.blob()),formats:[MP4]});
 try{const track=await input.getPrimaryVideoTrack();if(!track||!await track.canDecode())throw Error('浏览器无法解码九宫格片段，请更新浏览器');
 const sink=new CanvasSink(track,{poolSize:1});
 const times=Array.from({length:Math.ceil((FILM.montageEnd-FILM.montageStart)*fps)},(_,i)=>Math.min(i/fps,12-1/60));const frames=sink.canvasesAtTimestamps(times);let current=null;
 return {async draw(ctx,w,h,t){if(t<FILM.montageStart||t>=FILM.montageEnd)return;const item=await frames.next();if(item.value)current=item.value.canvas;if(!current)throw Error('九宫格视频缺少可读取的帧');
 const u=t-FILM.montageStart,alpha=ease(u)*ease(FILM.montageEnd-t);ctx.save();ctx.globalAlpha=alpha;ctx.fillStyle='#000';ctx.fillRect(0,0,w,h);
 const portrait=h>w,size=portrait?w*.92:h*.87,gap=size*.012,tile=(size-2*gap)/3,x=portrait?(w-size)/2:w-size-w*.05,y=(h-size)/2;
 ctx.fillStyle='#f5f5f7';ctx.textBaseline='middle';ctx.textAlign=portrait?'center':'left';ctx.font=`500 ${Math.min(w,h)*.037}px -apple-system, BlinkMacSystemFont, sans-serif`;
 if(portrait)ctx.fillText('每一刻，都有不同的世界。',w/2,y-w*.11);else{ctx.fillText('每一刻，',w*.07,h*.46);ctx.fillText('都有不同的世界。',w*.07,h*.52);}
 const sw=current.width/3,sh=current.height/3;
 for(let i=0;i<9;i++){const col=i%3,row=Math.floor(i/3);ctx.drawImage(current,col*sw,row*sh,sw,sh,x+col*(tile+gap),y+row*(tile+gap),tile,tile);}ctx.restore();},async close(){await frames.return();input.dispose();}};
 }catch(e){input.dispose();throw e;}
}
