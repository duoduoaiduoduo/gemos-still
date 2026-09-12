import * as THREE from './vendor/three.module.js';
/** A thin printed paper decal on the flat right-hand chassis panel. */
export function addLogoSticker(group){
 const image=new Image();image.onload=()=>{
  const canvas=document.createElement('canvas');canvas.width=512;canvas.height=600;const ctx=canvas.getContext('2d');
  ctx.fillStyle='#f6f3e9';ctx.beginPath();ctx.roundRect(8,8,496,584,42);ctx.fill();
  ctx.save();ctx.beginPath();ctx.roundRect(30,30,452,452,27);ctx.clip();ctx.drawImage(image,30,30,452,452);ctx.restore();
  ctx.fillStyle='#34302c';ctx.textAlign='center';ctx.font='600 41px sans-serif';ctx.fillText('Gemosdodo',256,550);
  const texture=new THREE.CanvasTexture(canvas);texture.colorSpace=THREE.SRGBColorSpace;texture.anisotropy=8;
  const material=new THREE.MeshStandardMaterial({map:texture,transparent:true,roughness:.72,metalness:0,depthWrite:false,side:THREE.FrontSide,polygonOffset:true,polygonOffsetFactor:-2,polygonOffsetUnits:-2});
  const sticker=new THREE.Mesh(new THREE.PlaneGeometry(.30,.352),material);sticker.name='Gemosdodo paper sticker';sticker.userData.aoExcluded=true;
  sticker.position.set(1.822,-1.445,1.03);sticker.rotation.y=Math.PI/2;sticker.rotateZ(-.075);sticker.renderOrder=2;group.add(sticker);
 };image.src=new URL('./avatar.png',import.meta.url).href;
}
