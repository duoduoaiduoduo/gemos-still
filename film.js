import {memoryBox} from './main.js?v=glass-progress-1';
import {drawFilmOutro} from './film-outro.js';
import {createFilmScore} from './film-score.js?v=piano-20260913-1';
import {FILM,EXPORT_PRESETS,exportSettings} from './film-timeline.js';
export const FILM_DURATION=FILM.duration;
const ease=t=>{t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);};
export function filmView(t){
 const keys=[
  [0,.56,.28,.92,0,29], [2.4,.56,.28,.92,0,29],
  [5.95,.24,.18,1.12,-.18,34], [8,-.22,.13,1.34,-.18,38],
  [10.4,-.43,.15,1.55,.02,40], [13.2,-.58,.23,1.42,.48,38],
  [17,-.18,.40,1.08,.08,32], [21,.65,.32,1.02,0,30],
  [25,1.18,.23,1.02,0,29], [29,.62,.30,.92,0,29],
  [32,.62,.30,.92,0,29]
 ];
 t=Math.max(0,Math.min(32,t));
 const i=Math.min(keys.length-2,Math.max(0,keys.findIndex((k,n)=>n<keys.length-1&&t<=keys[n+1][0]))),a=keys[i],b=keys[i+1],dt=b[0]-a[0],u=(t-a[0])/dt;
 const slope=(k,j)=>{if(k===0||k===keys.length-1)return 0;const l=(keys[k][j]-keys[k-1][j])/(keys[k][0]-keys[k-1][0]),r=(keys[k+1][j]-keys[k][j])/(keys[k+1][0]-keys[k][0]);return l*r<=0?0:2*l*r/(l+r);};
 return Object.fromEntries(['azimuth','elevation','zoom','lift','fov'].map((n,j)=>{const c=j+1;return [n,(2*u**3-3*u*u+1)*a[c]+(u**3-2*u*u+u)*dt*slope(i,c)+(-2*u**3+3*u*u)*b[c]+(u**3-u*u)*dt*slope(i+1,c)];}));
}
const $=id=>document.getElementById(id);
const button=document.createElement('button');button.textContent='制作展示影片 ↗';button.id='make-film';$('memory-actions').append(button);
const dialog=document.createElement('dialog');dialog.className='local-generation';dialog.innerHTML=`<button id="film-close" class="dialog-close" aria-label="关闭影片制作">×</button><p class="eyebrow">GEMOS STILL / MOTION</p><h2>让记忆，成为影片。</h2><p>52 秒空间影片 · 收藏构筑、微距绕拍、九宫格作品墙与品牌片尾。</p><label>画幅 <select id="film-format"><option value="wide">横屏 16:9</option><option value="portrait">竖屏 9:16</option></select></label><label>画质 <select id="film-quality">${Object.entries(EXPORT_PRESETS).map(([id,p])=>`<option value="${id}" ${id==='1080p60'?'selected':''}>${p.label}</option>`).join('')}</select></label><label><input id="film-music" type="checkbox" checked> 同步配乐 · 柔和钢琴</label><p id="film-status" role="status">逐帧制作，照片无需上传。4K 与 60 帧需要更长时间，请保持页面打开。</p><canvas id="film-canvas" hidden style="width:100%;max-height:45vh;object-fit:contain"></canvas><video id="film-video" controls playsinline hidden style="width:100%;max-height:45vh"></video><button id="film-start" class="primary">生成展示影片</button><a id="film-save" hidden class="primary">保存视频 ↓</a><button id="film-cancel" hidden>取消渲染</button>`;document.body.append(dialog);
let running=false,controller=null,videoURL=null;
button.onclick=()=>dialog.showModal();
function close(){if(running){controller?.abort();return;}dialog.close();$('film-video').pause();}
$('film-close').onclick=close;dialog.addEventListener('cancel',e=>{if(running){e.preventDefault();controller?.abort();}});$('film-cancel').onclick=()=>controller?.abort();
$('film-start').onclick=async()=>{
 if(running)return;running=true;controller=new AbortController();const {signal}=controller;let montage,begun=false;
 const controls=['film-start','film-format','film-quality','film-music'];controls.forEach(id=>$(id).disabled=true);$('film-cancel').hidden=false;$('film-save').hidden=true;$('film-video').hidden=true;$('film-video').pause();
 try{
 const settings=exportSettings($('film-quality').value,$('film-format').value==='portrait'),music=$('film-music').checked;
 $('film-status').textContent='正在加载影片组件…';
 const [{prepareMontage},{checkExportSupport,encodeFilm}]=await Promise.all([import('./film-montage.js?v=film-fix-20260913-1'),import('./film-encoder.js?v=film-fix-20260913-1')]);signal.throwIfAborted();
 $('film-status').textContent='正在检查导出规格…';await checkExportSupport(settings,music);signal.throwIfAborted();
 $('film-status').textContent='正在准备九宫格与配乐…';montage=await prepareMontage(settings.fps,signal);const audio=music?await createFilmScore():null;signal.throwIfAborted();
 const logo=new Image();logo.src=new URL('./avatar.png',import.meta.url).href;await logo.decode();signal.throwIfAborted();
 const canvas=$('film-canvas'),{width:w,height:h,fps}=settings;canvas.width=w;canvas.height=h;const ctx=canvas.getContext('2d',{alpha:false});
 begun=true;await memoryBox.beginFilm(w,h);signal.throwIfAborted();canvas.hidden=false;
 if(videoURL){URL.revokeObjectURL(videoURL);videoURL=null;}
 const title=($('memory-name').textContent||'一刻记忆').slice(0,45);
 const blob=await encodeFilm({canvas,settings,duration:FILM.duration,audio,signal,progress:p=>$('film-status').textContent=`正在逐帧制作 ${Math.round(p*100)}% · ${settings.label}`,draw:async t=>{
  ctx.fillStyle='#000';ctx.fillRect(0,0,w,h);
  if(t<32.8)ctx.drawImage(memoryBox.filmFrame(filmView(t),t),0,0,w,h);
  if(t>=29&&t<FILM.montageStart){const pad=w*.055;ctx.save();ctx.globalAlpha=ease((t-29)/2);ctx.fillStyle='#f5f3e7';ctx.font=`500 ${w*.028}px sans-serif`;let label=title;while(ctx.measureText(label).width>w*.78)label=label.slice(0,-2)+'…';ctx.fillText(label,pad,h-pad-w*.032);ctx.font=`${w*.014}px sans-serif`;ctx.fillText('GEMOS STILL  /  A MOMENT, KEPT.',pad,h-pad);ctx.restore();}
  await montage.draw(ctx,w,h,t);drawFilmOutro(ctx,w,h,t-FILM.outroOffset,logo);
 }});
 videoURL=URL.createObjectURL(blob);$('film-video').src=videoURL;$('film-video').hidden=false;canvas.hidden=true;$('film-save').href=videoURL;$('film-save').download=`Gemos Still-${title.replace(/[\\/:*?"<>|]/g,'_')}-${h}p${fps}.mp4`;$('film-save').hidden=false;$('film-status').textContent=`影片已完成 · ${w} × ${h} · ${fps} 帧 · ${(blob.size/1048576).toFixed(1)} MB`;
 }catch(e){$('film-status').textContent=signal.aborted?'制作已取消，记忆保持不变。':'制作未完成：'+e.message;$('film-canvas').hidden=true;}
 finally{try{await montage?.close();}finally{if(begun)memoryBox.endFilm();running=false;controller=null;controls.forEach(id=>$(id).disabled=false);$('film-cancel').hidden=true;}}
};
