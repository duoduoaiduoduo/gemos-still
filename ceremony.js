import * as THREE from './vendor/three.module.js';
const smooth=t=>{t=THREE.MathUtils.clamp(t,0,1);return t*t*(3-2*t);};
const image=src=>new Promise((resolve,reject)=>{const im=new Image();im.onload=()=>resolve(im);im.onerror=()=>reject(new Error('无法读取风景卡图片。'));im.src=src;});
/** Camera-facing keepsake becomes a physical card entering the real drive slot. */
export function createCeremony(scene,inside,camera,stage,driveSlot){
 let card=null,start=0,resolveArrival=null,epoch=0,active=false,phase='idle',revealStart=0,revealing=null,fadeTarget=0,previousTime=0;
 const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
 const label=document.createElement('div');label.className='ceremony-status';label.setAttribute('role','status');label.setAttribute('aria-live','polite');label.hidden=true;stage.append(label);
 let progress=-1;
 const mat=new THREE.ShaderMaterial({transparent:true,depthWrite:false,toneMapped:false,uniforms:{time:{value:0},fade:{value:0},progress:{value:-1}},vertexShader:`varying vec2 vUv;void main(){vUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);}`,fragmentShader:`varying vec2 vUv;uniform float time,fade,progress;
 void main(){float edge=1.-smoothstep(.30,.48,abs(vUv.y-.5));float fill=progress<0.?exp(-pow((vUv.x-(.5+.36*sin(time*1.4)))*9.,2.)):1.-smoothstep(progress,progress+max(fwidth(vUv.x),.002),vUv.x);gl_FragColor=vec4(vec3(.94,.96,.89),fade*edge*(.18+.7*fill));}`});
 const waiting=new THREE.Mesh(new THREE.PlaneGeometry(1.85,.026),mat);waiting.name='glass-generation-progress';waiting.position.set(0,-.34,.73);waiting.visible=false;waiting.renderOrder=20;scene.add(waiting);
 function setProgress(value){progress=Number.isFinite(value)?THREE.MathUtils.clamp(value,0,1):-1;}
 const initial=new THREE.Vector3(),initialQ=new THREE.Quaternion(),slotQ=new THREE.Quaternion();
 const slotPoint=new THREE.Vector3(),slotNormal=new THREE.Vector3(),slotRotation=new THREE.Quaternion();
 const cardWidth=.71,halfLength=cardWidth*1.24/2;
 function alignSlot(){driveSlot.updateWorldMatrix(true,false);driveSlot.getWorldPosition(slotPoint);driveSlot.getWorldQuaternion(slotRotation);slotNormal.set(0,0,1).applyQuaternion(slotRotation);slotQ.copy(slotRotation).multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1,0,0),-Math.PI/2));}
 function atSlot(distance){return slotPoint.clone().addScaledVector(slotNormal,distance);}
 let initialScale=1;
 function disposeCard(){if(card){scene.remove(card);card.geometry.dispose();card.material.map.dispose();card.material.dispose();card=null;}}
 function state(value,text){if(phase===value&&label.textContent===text)return;phase=value;stage.dataset.creation=value;label.textContent=text;label.hidden=!text;}
 function cancel(){epoch++;active=false;fadeTarget=0;progress=-1;disposeCard();if(revealing)revealing.material.uniforms.reveal.value=1;revealing=null;resolveArrival?.();resolveArrival=null;state('idle','');}
 function wait(text='正在重建这一刻'){active=true;fadeTarget=1;state('generating',text);}
 async function begin(file,name,onReady){
  cancel();alignSlot();const ticket=epoch;active=true;state('card','将这一刻，装入记忆');const url=URL.createObjectURL(file);
  let photo,logo;try{[photo,logo]=await Promise.all([image(url),image('./avatar.png')]);}finally{URL.revokeObjectURL(url);}
  if(ticket!==epoch)return;
  const cv=document.createElement('canvas');cv.width=1000;cv.height=1240;const c=cv.getContext('2d');
  c.fillStyle='#f9f8f4';c.beginPath();c.roundRect(0,0,1000,1240,65);c.fill();
  c.save();c.beginPath();c.roundRect(30,30,940,930,43);c.clip();const s=Math.max(940/photo.width,930/photo.height);c.drawImage(photo,30+(940-photo.width*s)/2,30+(930-photo.height*s)/2,photo.width*s,photo.height*s);c.restore();
  c.fillStyle='#292923';c.font='500 35px sans-serif';let title=/^[a-f0-9]{20,}$/i.test(name)?'一刻风景':name;while(c.measureText(title).width>875)title=title.slice(0,-2)+'…';c.fillText(title,55,1020);
  c.strokeStyle='#ddddd5';c.beginPath();c.moveTo(55,1060);c.lineTo(945,1060);c.stroke();
  c.save();c.beginPath();c.arc(87,1136,32,0,Math.PI*2);c.clip();c.drawImage(logo,55,1104,64,64);c.restore();
  c.fillStyle='#292923';c.font='600 32px sans-serif';c.fillText('Gemosdodo',139,1134);c.fillStyle='#929288';c.font='18px sans-serif';c.fillText('A MOMENT, KEPT.',139,1163);
  c.textAlign='right';c.font='23px monospace';c.fillStyle='#67675d';c.fillText(new Date().toLocaleDateString('sv-SE').replaceAll('-','.'),945,1134);c.font='16px sans-serif';c.fillText('MEMORY / 001',945,1163);
  const texture=new THREE.CanvasTexture(cv);texture.colorSpace=THREE.SRGBColorSpace;texture.anisotropy=8;
  const material=new THREE.MeshBasicMaterial({map:texture,transparent:true,side:THREE.DoubleSide,toneMapped:false});
  material.onBeforeCompile=shader=>{shader.uniforms.swallow={value:0};shader.uniforms.slotPoint={value:slotPoint};shader.uniforms.slotNormal={value:slotNormal};material.userData.shader=shader;shader.vertexShader=shader.vertexShader.replace('#include <common>','#include <common>\nvarying vec3 cardWorld;').replace('#include <begin_vertex>','#include <begin_vertex>\ncardWorld=(modelMatrix*vec4(position,1.)).xyz;');shader.fragmentShader=shader.fragmentShader.replace('#include <common>','#include <common>\nvarying vec3 cardWorld;uniform float swallow;uniform vec3 slotPoint,slotNormal;').replace('#include <clipping_planes_fragment>','#include <clipping_planes_fragment>\nif(swallow>.5 && dot(cardWorld-slotPoint,slotNormal)<0.)discard;');};
  card=new THREE.Mesh(new THREE.PlaneGeometry(1,1.24),material);scene.add(card);camera.updateMatrixWorld();initial.set(0,0,-4).applyMatrix4(camera.matrixWorld);initialQ.copy(camera.quaternion);
  initialScale=Math.min(1.7,2*4*Math.tan(THREE.MathUtils.degToRad(camera.fov)/2)*Math.min(camera.aspect*.8,.66));
  card.position.copy(initial);card.quaternion.copy(initialQ);card.scale.setScalar(initialScale*.92);start=performance.now();onReady?.(start);
  return new Promise(resolve=>{resolveArrival=resolve;});
 }
 function reveal(mesh,now=performance.now()){fadeTarget=0;revealing=mesh;revealStart=now;mesh.material.uniforms.reveal.value=0;state('revealing','记忆，正在浮现');active=true;}
 function update(now){
  const dt=previousTime?Math.min((now-previousTime)/1000,.1):0;previousTime=now;
  mat.uniforms.fade.value=THREE.MathUtils.damp(mat.uniforms.fade.value,fadeTarget,4.5,dt);
  waiting.visible=mat.uniforms.fade.value>.005;
  mat.uniforms.progress.value=progress;
  mat.uniforms.time.value=reduced?0:now/1000;
  if(card){const t=(now-start)/1000,hold=reduced?.3:2.4,travel=reduced?.25:1.65,approach=reduced?.2:.65,insert=reduced?.25:1.25;
   if(t<hold){camera.updateMatrixWorld();initial.set(0,0,-4).applyMatrix4(camera.matrixWorld);initialQ.copy(camera.quaternion);card.position.copy(initial);card.quaternion.copy(initialQ);card.scale.setScalar(initialScale*(.92+.08*smooth(t/.65)));}
   else if(t<hold+travel){const k=smooth((t-hold)/travel);card.position.lerpVectors(initial,atSlot(1.92),k);card.quaternion.slerpQuaternions(initialQ,slotQ,k);card.scale.setScalar(THREE.MathUtils.lerp(initialScale,cardWidth,smooth(k*1.7)));state('inserting','正在存入这一刻');}
   else if(t<hold+travel+approach){const k=smooth((t-hold-travel)/approach);card.quaternion.copy(slotQ);card.scale.setScalar(cardWidth);card.position.copy(atSlot(THREE.MathUtils.lerp(1.92,halfLength+.035,k)));}
   else {const k=smooth((t-hold-travel-approach)/insert);card.position.copy(atSlot(THREE.MathUtils.lerp(halfLength+.035,-halfLength-.025,k)));if(card.material.userData.shader)card.material.userData.shader.uniforms.swallow.value=1;if(k===1){disposeCard();wait();resolveArrival?.();resolveArrival=null;}}
  }
  if(revealing){const p=Math.min(1,(now-revealStart)/(reduced?350:5200));revealing.material.uniforms.reveal.value=p;if(p===1){revealing=null;active=false;fadeTarget=0;state('complete','这一刻，已收藏');setTimeout(()=>{if(phase==='complete')state('idle','');},2400);}}
 }
 return {begin,cancel,wait,reveal,update,setProgress,get active(){return active;},get phase(){return phase;}};
}
