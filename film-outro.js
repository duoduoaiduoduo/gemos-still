const smooth=t=>{t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);};
// Composited into the exported video, with layout measured in the shorter edge.
export function drawFilmOutro(ctx,w,h,t,logo){
 if(t<=31.5)return;
 const fade=smooth((t-31.5)/1.3),unit=Math.min(w,h),portrait=h>w;
 ctx.save();ctx.globalAlpha=fade;ctx.fillStyle='#000';ctx.fillRect(0,0,w,h);
 const center=h*.46, size=unit*.115;
 ctx.globalAlpha=smooth((t-33)/1.1);
 ctx.save();ctx.beginPath();ctx.roundRect(w/2-size/2,center-unit*.19,size,size,size*.24);ctx.clip();ctx.drawImage(logo,w/2-size/2,center-unit*.19,size,size);ctx.restore();
 ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillStyle='#f5f5f7';
 ctx.globalAlpha=smooth((t-33.5)/1.1);ctx.font=`600 ${unit*.055}px -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif`;ctx.fillText('Gemos Still',w/2,center+unit*.013);
 ctx.globalAlpha=smooth((t-34)/1.1);ctx.fillStyle='#949498';ctx.font=`400 ${unit*.023}px -apple-system, BlinkMacSystemFont, sans-serif`;ctx.fillText('把一刻，留成一个世界。',w/2,center+unit*.08);
 ctx.globalAlpha=smooth((t-34.6)/1.1);ctx.fillStyle='#f5f5f7';ctx.font=`500 ${unit*.033}px -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif`;ctx.fillText('gemosdodo.art',w/2,center+unit*.205);
 ctx.fillStyle='#86868b';ctx.font=`400 ${unit*(portrait?.021:.019)}px -apple-system, BlinkMacSystemFont, sans-serif`;ctx.fillText('亲自体验  /  vibecoding/still-memory-box',w/2,center+unit*.256);
 ctx.restore();
}
