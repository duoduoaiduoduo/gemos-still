import {addRearStickers} from './rear-stickers.js?v=gallery-20260913-1';
import {createDeskField} from './desk-field.js?v=gallery-20260913-1';
import {BokehPass} from './vendor/addons/postprocessing/BokehPass.js';
import {makeTide} from './tide.js?v=gallery-20260913-1';
import * as THREE from './vendor/three.module.js';
import { MemoryGaussians } from './gaussian.js?v=gallery-20260913-1';
import { applyBakedLighting } from './baked-lighting.js?v=gallery-20260913-1';
import { buildComputer } from './computer.js?v=gallery-20260913-1';
import { addLogoSticker } from './logo-sticker.js';
import { createCeremony } from './ceremony.js?v=glass-progress-1';
import { createStudio } from './studio.js';
import { EffectComposer } from './vendor/addons/postprocessing/EffectComposer.js';
import { GTAOPass } from './vendor/addons/postprocessing/GTAOPass.js';
import { RenderPass } from './vendor/addons/postprocessing/RenderPass.js';
import { OutputPass } from './vendor/addons/postprocessing/OutputPass.js';

const $=id=>document.getElementById(id);
const stage=$('stage');
const mobile=matchMedia('(pointer:coarse)').matches;let computing=false;let inferencePaused=false;let frozenPreview=null;
stage.style.touchAction='none';if(mobile)$('view-hint').textContent='单指旋转 · 双指缩放';
const renderer=new THREE.WebGLRenderer({antialias:!mobile,preserveDrawingBuffer:true,powerPreference:mobile?'low-power':'high-performance'});
renderer.setPixelRatio(Math.min(devicePixelRatio,2));
renderer.outputColorSpace=THREE.SRGBColorSpace;
renderer.toneMapping=THREE.NeutralToneMapping;renderer.toneMappingExposure=1.0;
renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFSoftShadowMap;
stage.appendChild(renderer.domElement);
const scene=new THREE.Scene();scene.background=new THREE.Color('#000000');
const inside=new THREE.Scene();inside.background=new THREE.Color('#151713');
const studio=createStudio(renderer);scene.environment=studio.environment;scene.environmentIntensity=.48;inside.environment=studio.environment;inside.environmentIntensity=.6;
const camera=new THREE.PerspectiveCamera(29,1,.1,200);
let cameraDistance=10;
const target=new THREE.Vector3(0,.15,.1);
const HOME={azimuth:.56,elevation:.28,zoom:1};
let azimuth=HOME.azimuth,elevation=HOME.elevation,zoom=1,time=0,paused=false,dragging=false;
let filming=false,filmSaved=null;
let cameraMove=null;
function moveCamera(view){
 const turn=Math.atan2(Math.sin(view.azimuth-azimuth),Math.cos(view.azimuth-azimuth));
 cameraMove={start:performance.now(),duration:matchMedia('(prefers-reduced-motion: reduce)').matches?120:850,from:{azimuth,elevation,zoom},to:{...view,azimuth:azimuth+turn}};
}
function updateCamera(now){
 if(!cameraMove)return;
 const m=cameraMove,t=Math.min(1,Math.max(0,(now-m.start)/m.duration)),k=t*t*t*(t*(t*6-15)+10);
 azimuth=THREE.MathUtils.lerp(m.from.azimuth,m.to.azimuth,k);elevation=THREE.MathUtils.lerp(m.from.elevation,m.to.elevation,k);zoom=THREE.MathUtils.lerp(m.from.zoom,m.to.zoom,k);setCamera();
 if(t===1)cameraMove=null;
}
let memoryMesh=null,loadVersion=0,fill='cover',contentScale=.65,depthVolume=1;
const displaySize=new THREE.Vector2();
let modelBounds=new THREE.Box3(new THREE.Vector3(-1.9,-1.76,-1.15),new THREE.Vector3(1.9,2.5,2.1));
function setCamera(){
 const back=new THREE.Vector3(Math.cos(elevation)*Math.sin(azimuth),Math.sin(elevation),Math.cos(elevation)*Math.cos(azimuth));
 const right=new THREE.Vector3().crossVectors(new THREE.Vector3(0,1,0),back).normalize(),up=new THREE.Vector3().crossVectors(back,right);
 modelBounds.getCenter(target);
 const tanY=Math.tan(THREE.MathUtils.degToRad(camera.fov)*.5),tanX=tanY*camera.aspect;
 cameraDistance=0;
 for(const x of [modelBounds.min.x,modelBounds.max.x])for(const y of [modelBounds.min.y,modelBounds.max.y])for(const z of [modelBounds.min.z,modelBounds.max.z]){
  const v=new THREE.Vector3(x,y,z).sub(target);
  cameraDistance=Math.max(cameraDistance,v.dot(back)+Math.max(Math.abs(v.dot(right))/tanX,Math.abs(v.dot(up))/tanY)/.84);
 }
 camera.position.copy(target).addScaledVector(back,cameraDistance);camera.lookAt(target);camera.zoom=zoom;camera.updateProjectionMatrix();
}
setCamera();
const rtOptions={type:THREE.HalfFloatType,minFilter:THREE.LinearFilter,magFilter:THREE.LinearFilter};
const innerRT=new THREE.WebGLRenderTarget(1,1,rtOptions),blurA=innerRT.clone(),blurB=innerRT.clone();
const quadScene=new THREE.Scene(),quadCamera=new THREE.OrthographicCamera(-1,1,1,-1,0,1);
const vertex=`varying vec2 vUv;void main(){vUv=uv;gl_Position=vec4(position.xy,0.,1.);}`;
const blurMaterial=new THREE.ShaderMaterial({depthTest:false,depthWrite:false,uniforms:{map:{value:null},direction:{value:new THREE.Vector2()}},vertexShader:vertex,fragmentShader:`varying vec2 vUv;uniform sampler2D map;uniform vec2 direction;void main(){vec3 c=texture2D(map,vUv).rgb*.227027;c+=(texture2D(map,vUv+direction*1.384615).rgb+texture2D(map,vUv-direction*1.384615).rgb)*.316216;c+=(texture2D(map,vUv+direction*3.230769).rgb+texture2D(map,vUv-direction*3.230769).rgb)*.070270;gl_FragColor=vec4(c,1.);}`});
const quad=new THREE.Mesh(new THREE.PlaneGeometry(2,2),blurMaterial);quadScene.add(quad);
function blur(){quad.material=blurMaterial;blurMaterial.uniforms.map.value=innerRT.texture;blurMaterial.uniforms.direction.value.set(2.2/blurA.width,0);renderer.setRenderTarget(blurA);renderer.render(quadScene,quadCamera);blurMaterial.uniforms.map.value=blurA.texture;blurMaterial.uniforms.direction.value.set(0,2.2/blurA.height);renderer.setRenderTarget(blurB);renderer.render(quadScene,quadCamera);}
const glassMaterial=new THREE.ShaderMaterial({uniforms:{sharp:{value:innerRT.texture},soft:{value:blurB.texture},studioReflection:{value:studio.reflection},viewProjection:{value:new THREE.Matrix4()},resolution:{value:displaySize},frost:{value:.025},glow:{value:.12},time:{value:0}},vertexShader:`
 varying vec3 vWorld,vNormal,vPosition;
 void main(){vPosition=position;vWorld=(modelMatrix*vec4(position,1.)).xyz;vNormal=normalize(mat3(modelMatrix)*normal);gl_Position=projectionMatrix*viewMatrix*vec4(vWorld,1.);}`,fragmentShader:`
 varying vec3 vWorld,vNormal,vPosition;
 uniform sampler2D sharp,soft;uniform samplerCube studioReflection;
 uniform mat4 viewProjection;uniform vec2 resolution;uniform float frost,glow,time;
 float glassNoise(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
 vec2 screenAt(vec3 p){vec4 q=viewProjection*vec4(p,1.);return q.xy/q.w*.5+.5;}
 void main(){
  vec3 V=normalize(cameraPosition-vWorld),N=normalize(vNormal);
  float cosI=clamp(dot(V,N),0.,1.);
  // Snell refraction through a 24 mm optical slab, rather than a flat overlay.
  float ior=1.52,thickness=.024;
  vec3 T=refract(-V,N,1./ior);
  vec3 exitPoint=vWorld+T*thickness/max(abs(dot(T,N)),.12);
  vec2 uv=screenAt(exitPoint);
  vec2 baseUV=gl_FragCoord.xy/resolution;
  vec2 dispersion=(uv-baseUV)*.018;
  vec3 transmitted=vec3(texture2D(sharp,uv+dispersion).r,texture2D(sharp,uv).g,texture2D(sharp,uv-dispersion).b);
  transmitted=mix(transmitted,texture2D(soft,uv).rgb,frost*.82);
  transmitted*=exp(-vec3(.12,.025,.06)*thickness/max(cosI,.20));
  float fresnel=.04258+.95742*pow(1.-cosI,5.);
  vec3 R=reflect(-V,N);
  vec3 reflected=textureCube(studioReflection,R).rgb;
  // A weak displaced second reflection communicates the two sheet surfaces.
  reflected+=textureCube(studioReflection,normalize(R+N*.008)).rgb*.035;
  // Sparse surface dust is strongest against a light reflected in the glass.
  vec2 dustUV=vPosition.xy;
  float dust=step(.9975,glassNoise(floor(dustUV*780.)));
  float dustAA=1.-smoothstep(.7,2.,length(fwidth(dustUV*780.)));
  float border=max(abs(vPosition.x)/1.655,abs(vPosition.y)/1.4675);
  float edgeShade=1.-.17*smoothstep(.86,1.,border);
  vec3 c=transmitted*(1.-fresnel)*edgeShade+reflected*fresnel;
  c+=vec3(.10,.095,.08)*dust*dustAA*(.1+length(reflected)*.25);
  c+=texture2D(soft,uv).rgb*glow*.035*(.98+.02*sin(time*.35));
  gl_FragColor=vec4(c,1.);
 }`});
const absHeight=new THREE.TextureLoader().load('./baked/abs-height.png');absHeight.colorSpace=THREE.NoColorSpace;absHeight.wrapS=absHeight.wrapT=THREE.RepeatWrapping;absHeight.anisotropy=8;
const computer=buildComputer(glassMaterial,absHeight);scene.add(computer.group);const deskField=createDeskField(scene,computer.keys);modelBounds.setFromObject(computer.group);modelBounds.union(new THREE.Box3().setFromObject(deskField.group));setCamera();
const ceremony=createCeremony(scene,inside,camera,stage,computer.driveSlot);
addLogoSticker(computer.group);addRearStickers(computer.group);
// Retain the established studio look independently of reflection cards.
scene.add(new THREE.HemisphereLight('#ffffff','#6c665c',.10));
for(let i=0;i<8;i++){
 const a=(i+.5)*2.399963,radius=.85*Math.sqrt((i+.5)/8);
 const light=new THREE.DirectionalLight('#fff6eb',2.65/8);
 light.position.set(-3.8+Math.cos(a)*radius,6+Math.sin(a)*radius,5.2);
 light.castShadow=true;light.shadow.mapSize.set(mobile?1024:2048,mobile?1024:2048);
 Object.assign(light.shadow.camera,{left:-4.5,right:4.5,top:4.5,bottom:-4.5,near:.1,far:20});
 light.shadow.normalBias=.003;light.shadow.bias=-.000025;light.shadow.radius=3;
 light.shadow.autoUpdate=false;light.shadow.needsUpdate=true;scene.add(light);
}
const fillLight=new THREE.DirectionalLight('#e5edff',.24);fillLight.position.set(5,3,-3);scene.add(fillLight);
// A radial light falloff reaches exact black before the finite ground ends.
// World-space fading stays continuous while orbiting, zooming, and resizing.
const floorMaterial=new THREE.MeshStandardMaterial({color:'#484641',roughness:.72,metalness:0,envMapIntensity:.32});
floorMaterial.onBeforeCompile=shader=>{
 shader.vertexShader='varying vec3 vGroundWorld;\n'+shader.vertexShader;
 shader.vertexShader=shader.vertexShader.replace('#include <project_vertex>','#include <project_vertex>\nvGroundWorld=(modelMatrix*vec4(transformed,1.0)).xyz;');
 shader.fragmentShader='varying vec3 vGroundWorld;\n'+shader.fragmentShader;
 shader.fragmentShader=shader.fragmentShader.replace('#include <opaque_fragment>','outgoingLight *= 1.0-smoothstep(4.5,23.0,length(vGroundWorld.xz));\n#include <opaque_fragment>');
};
const floor=new THREE.Mesh(new THREE.PlaneGeometry(2000,2000),floorMaterial);floor.rotation.x=-Math.PI/2;floor.position.y=-1.758;floor.receiveShadow=true;scene.add(floor);
const tide=makeTide(mobile),demo=tide.mesh;inside.add(demo);
let tideSwirl=0,tideAlpha=1,filmStart=0,filmRevealed=false,filmDof=null;
function updateTide(dt=.033){const phase=ceremony.phase,vortex=['generating','revealing'].includes(phase)?1:0;const reveal=memoryMesh?.material.uniforms.reveal.value??0;const constructing=phase==='revealing';const opacity=(!memoryMesh||['card','inserting','generating','revealing'].includes(phase))?1:0;tideSwirl=THREE.MathUtils.damp(tideSwirl,vortex,2.2,dt);tideAlpha=constructing?1:THREE.MathUtils.damp(tideAlpha,opacity,3,dt);tide.update(time,tideSwirl,tideAlpha,displaySize.y,constructing?reveal:(memoryMesh&&opacity===0?1:-1));}
// Multisampled linear render -> ground-truth AO -> highlight-preserving display transform.
const composer=new EffectComposer(renderer,new THREE.WebGLRenderTarget(1,1,{type:THREE.HalfFloatType,samples:mobile?2:4}));
composer.addPass(new RenderPass(scene,camera));
const ao=mobile?null:new GTAOPass(scene,camera,1,1);
if(ao){
ao.updateGtaoMaterial({radius:.12,thickness:.12,distanceExponent:1.1,distanceFallOff:1,scale:1,samples:32,screenSpaceRadius:false});
ao.updatePdMaterial({radius:5,depthPhi:2,normalPhi:4});ao.blendIntensity=.65;
const originalOverride=ao._overrideVisibility.bind(ao);
ao._overrideVisibility=()=>{originalOverride();scene.traverse(o=>{if(o.visible&&(o.userData.aoExcluded||o.material?.transparent)){o.visible=false;ao._visibilityCache.push(o);}});};
composer.addPass(ao);}
composer.addPass(new OutputPass());
applyBakedLighting(computer.group,floor).then(result=>{if(!result.pending){if(ao){ao.blendIntensity=0;ao.enabled=false;}stage.dataset.lighting="baked";}}).catch(error=>console.warn("离线光照未加载，使用实时材质",error));
function resize(){if(filming||inferencePaused)return;const w=stage.clientWidth,h=stage.clientHeight;if(!w||!h)return;const ratio=mobile?Math.min(devicePixelRatio,computing?1.75:2.75,Math.sqrt(2800000/(w*h))):Math.min(2.5,Math.max(devicePixelRatio,1.5),Math.sqrt(5000000/(w*h)));if(renderer.getPixelRatio()!==ratio){renderer.setPixelRatio(ratio);composer.setPixelRatio(ratio);}renderer.setSize(w,h);renderer.getDrawingBufferSize(displaySize);innerRT.setSize(displaySize.x,displaySize.y);for(const rt of [blurA,blurB])rt.setSize(Math.max(1,Math.round(displaySize.x*(mobile?.5:1))),Math.max(1,Math.round(displaySize.y*(mobile?.5:1))));composer.setSize(w,h);camera.aspect=w/h;setCamera();}
new ResizeObserver(resize).observe(stage);window.addEventListener('resize',resize);resize();
function renderScene(){deskField.update(time,ceremony.phase);updateTide();camera.updateMatrixWorld();glassMaterial.uniforms.viewProjection.value.multiplyMatrices(camera.projectionMatrix,camera.matrixWorldInverse);if(memoryMesh)memoryMesh.update(camera,displaySize);renderer.setRenderTarget(innerRT);renderer.clear(true,true,true);renderer.render(inside,camera);blur();renderer.setRenderTarget(null);composer.render();}
let last=performance.now();function animate(now){requestAnimationFrame(animate);if(document.hidden||filming)return;if(inferencePaused){ceremony.update(now);return;}if(mobile&&now-last<(computing?1000/12:1000/30))return;const dt=Math.min((now-last)/1000,.04);last=now;updateCamera(now);ceremony.update(now);if(!paused)time+=dt;glassMaterial.uniforms.time.value=time;renderScene();}
requestAnimationFrame(animate);
const pointers=new Map();let gestureDistance=0;
function span(){const p=[...pointers.values()];return p.length===2?Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y):0;}
stage.addEventListener('pointerdown',e=>{if(e.pointerType==='mouse'&&e.button!==0)return;cameraMove=null;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});dragging=true;gestureDistance=span();stage.setPointerCapture(e.pointerId);});
stage.addEventListener('pointermove',e=>{const old=pointers.get(e.pointerId);if(!old)return;const dx=e.clientX-old.x,dy=e.clientY-old.y;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size===1){azimuth-=dx*.006;elevation=Math.max(.08,Math.min(1.35,elevation+dy*.004));}else if(pointers.size===2){const d=span();if(gestureDistance>5&&d>5)zoom=Math.max(.6,Math.min(1.7,zoom*d/gestureDistance));gestureDistance=d;}setCamera();});
for(const ev of ['pointerup','pointercancel','lostpointercapture'])stage.addEventListener(ev,e=>{pointers.delete(e.pointerId);gestureDistance=span();dragging=pointers.size>0;});
window.addEventListener('blur',()=>{pointers.clear();gestureDistance=0;dragging=false;});
stage.addEventListener('wheel',e=>{e.preventDefault();cameraMove=null;zoom=Math.max(.6,Math.min(1.7,zoom*Math.exp(-e.deltaY*.001)));setCamera();},{passive:false});
function fitContent(){
 if(!memoryMesh)return;const b=fill==='contain'?memoryMesh.fullBounds:memoryMesh.contentBounds,size=new THREE.Vector3();b.getSize(size);
 const fitX=3.04/Math.max(size.x,.01),fitY=2.68/Math.max(size.y,.01);
 const scale=(fill==='cover'?Math.max(fitX,fitY):Math.min(fitX,fitY))*(fill==='cover'?1.07:.99)*contentScale;
 const center=b.getCenter(new THREE.Vector3());
 memoryMesh.rotation.y=0;
 // Give the subject the usable depth of the case; earlier XY scaling flattened it twice.
 memoryMesh.scale.set(scale,scale,3.22*depthVolume/Math.max(size.z,.01));
 memoryMesh.position.copy(center).multiply(memoryMesh.scale).negate().add(new THREE.Vector3(0,.84,-.97));
 memoryMesh.material.uniforms.clipMin.value.set(-1.54,-.53,-2.66);
 memoryMesh.material.uniforms.clipMax.value.set(1.54,2.21,.66);
 tide.setTarget(memoryMesh);
 memoryMesh.lastDirection=null;
 $('fit-cover').setAttribute('aria-pressed',String(fill==='cover'));$('fit-contain').setAttribute('aria-pressed',String(fill==='contain'));
}
function applySettings(settings={},keepCamera=false){
 const modern=settings.designVersion===2;
 for(const id of ['glow','frost','brightness']){
 const value=modern?(settings[id]??{glow:.12,frost:.025,brightness:1.05}[id]):{glow:.12,frost:.025,brightness:1.05}[id];
 $(id).value=value;$(id+'Value').textContent=value.toFixed(2);
 if(id==='brightness'){if(memoryMesh)memoryMesh.material.uniforms.brightness.value=value;}else glassMaterial.uniforms[id].value=value;
 }
 fill=modern?(settings.fit??'cover'):'cover';contentScale=modern?(settings.contentScale??.65):.65;
 depthVolume=settings.depthVolume??1;$('depth-volume').value=depthVolume;$('depth-volumeValue').textContent=Math.round(depthVolume*100)+'%';
 $('content-scale').value=contentScale;$('content-scaleValue').textContent=Math.round(contentScale*100)+'%';fitContent();
 if(keepCamera)return;cameraMove=null;
 const savedCamera=settings.renderVersion===2;azimuth=savedCamera?(settings.azimuth??HOME.azimuth):HOME.azimuth;elevation=savedCamera?(settings.elevation??HOME.elevation):HOME.elevation;zoom=savedCamera?(settings.zoom??1):1;setCamera();
}
for(const id of ['glow','frost','brightness'])$(id).addEventListener('input',e=>{const n=Number(e.target.value);$(id+'Value').textContent=n.toFixed(2);if(id==='brightness'){if(memoryMesh)memoryMesh.material.uniforms.brightness.value=n;}else glassMaterial.uniforms[id].value=n;});
$('content-scale').oninput=e=>{contentScale=Number(e.target.value);$('content-scaleValue').textContent=Math.round(contentScale*100)+'%';fitContent();};
$('depth-volume').oninput=e=>{depthVolume=Number(e.target.value);$('depth-volumeValue').textContent=Math.round(depthVolume*100)+'%';fitContent();};
$('fit-cover').onclick=()=>{fill='cover';fitContent();};$('fit-contain').onclick=()=>{fill='contain';fitContent();};
$('motion').onclick=e=>{paused=!paused;e.target.textContent=paused?'继续流光':'暂停流光';e.target.setAttribute('aria-pressed',String(!paused));};
$('reset').onclick=()=>moveCamera(HOME);
$('front-view').onclick=()=>moveCamera({azimuth:0,elevation:.08,zoom:1});
export const memoryBox={
 beginShowcase(w,h){
  if(filming||!memoryMesh)throw Error('Showcase requires a loaded memory');
  filmSaved={azimuth,elevation,zoom,time,fov:camera.fov,bounds:modelBounds.clone()};filming=true;
  modelBounds.setFromObject(computer.group);renderer.setPixelRatio(1);composer.setPixelRatio(1);renderer.setSize(w,h,false);composer.setSize(w,h);displaySize.set(w,h);innerRT.setSize(w,h);blurA.setSize(w,h);blurB.setSize(w,h);camera.aspect=w/h;
  ceremony.cancel();deskField.reset();memoryMesh.visible=true;memoryMesh.material.uniforms.reveal.value=1;tideAlpha=0;
 },
 showcaseFrame(view,t){azimuth=view.azimuth;elevation=view.elevation;zoom=view.zoom;time=filmSaved.time+t;camera.fov=29;setCamera();renderScene();return renderer.domElement.toDataURL('image/jpeg',.94);},

 async beginFilm(w,h){if(filming||computing||ceremony.active||!memoryMesh)throw Error('请等待记忆加载或收藏动画完成后再制作影片');filmSaved={azimuth,elevation,zoom,time,fov:camera.fov};filming=true;cameraMove=null;renderer.setPixelRatio(1);composer.setPixelRatio(1);renderer.setSize(w,h,false);composer.setSize(w,h);displaySize.set(w,h);innerRT.setSize(w,h);blurA.setSize(w,h);blurB.setSize(w,h);camera.aspect=w/h;setCamera();filmDof=new BokehPass(scene,camera,{focus:10,aperture:0,maxblur:.006});filmDof.setSize(w,h);composer.passes.splice(composer.passes.length-1,0,filmDof);filmRevealed=false;memoryMesh.visible=false;tideAlpha=1;tideSwirl=0;const photo=await(await fetch(document.getElementById('photo-preview').src)).blob();await new Promise((resolve,reject)=>{ceremony.begin(photo,document.getElementById('memory-name').textContent,start=>{filmStart=start;resolve();}).catch(reject);});},
 filmFrame(view,t){azimuth=view.azimuth;elevation=view.elevation;zoom=view.zoom;time=filmSaved.time+t;glassMaterial.uniforms.time.value=time;camera.fov=view.fov??29;setCamera();if(view.lift){camera.position.y+=view.lift;camera.lookAt(target.clone().add(new THREE.Vector3(0,view.lift,0)));}// Leave the whole-object framing for a physical close-up through the front glass.
 const macro=THREE.MathUtils.smoothstep(t,8,11)*(1-THREE.MathUtils.smoothstep(t,17,21));
 const travel=THREE.MathUtils.smoothstep(t,9,18);
 const macroTarget=new THREE.Vector3(0,THREE.MathUtils.lerp(.35,1.10,travel),-.8);
 const wideTarget=target.clone().add(new THREE.Vector3(0,view.lift??0,0));
 const orbit=THREE.MathUtils.lerp(-.24,.28,travel),radius=3.9;
 const macroPosition=new THREE.Vector3(Math.sin(orbit)*radius,macroTarget.y+.10+Math.sin(travel*Math.PI)*.18,macroTarget.z+Math.cos(orbit)*radius);
 camera.position.lerp(macroPosition,macro);camera.lookAt(wideTarget.lerp(macroTarget,macro));
 camera.fov=THREE.MathUtils.lerp(view.fov??29,27,macro);camera.zoom=THREE.MathUtils.lerp(view.zoom,1,macro);camera.updateProjectionMatrix();
 if(t>=8&&!filmRevealed){filmRevealed=true;memoryMesh.visible=true;ceremony.reveal(memoryMesh,filmStart+8000);}ceremony.update(filmStart+t*1000);const focusPoint=new THREE.Vector3(0,.65+(view.lift??0),-.35).lerp(macroTarget,macro),forward=camera.getWorldDirection(new THREE.Vector3());filmDof.uniforms.focus.value=focusPoint.sub(camera.position).dot(forward);filmDof.uniforms.aperture.value=.00065*THREE.MathUtils.smoothstep(t,7,9)*(1-THREE.MathUtils.smoothstep(t,16,20));renderScene();return renderer.domElement;},
 endFilm(){if(!filmSaved)return;deskField.reset();if(filmSaved.bounds)modelBounds.copy(filmSaved.bounds);({azimuth,elevation,zoom,time}=filmSaved);camera.fov=filmSaved.fov;if(filmDof){composer.removePass(filmDof);filmDof.dispose();filmDof=null;}ceremony.cancel();memoryMesh.visible=true;tideAlpha=0;filmSaved=null;filming=false;resize();},
 setGenerationProgress(value){ceremony.setProgress(value);},
 setInferencePaused(value){
  if(!mobile||inferencePaused===!!value)return;
  if(value){frozenPreview=document.createElement('img');frozenPreview.alt='正在本机生成，场景暂时暂停';frozenPreview.src=renderer.domElement.toDataURL('image/jpeg',.8);Object.assign(frozenPreview.style,{position:'absolute',inset:'0',width:'100%',height:'100%',objectFit:'fill',pointerEvents:'none'});stage.append(frozenPreview);inferencePaused=true;renderer.setSize(1,1,false);composer.setSize(1,1);for(const rt of [innerRT,blurA,blurB])rt.setSize(1,1);}
  else{inferencePaused=false;frozenPreview?.remove();frozenPreview=null;resize();}
 },
 setComputing(value){computing=!!value;resize();},
 async beginCreation(file,name){loadVersion++;if(memoryMesh)memoryMesh.visible=false;demo.visible=false;try{await ceremony.begin(file,name);}catch(e){this.cancelCreation();throw e;}},
 waiting(message){if(memoryMesh)memoryMesh.visible=false;demo.visible=false;ceremony.wait(message);},
 cancelCreation(){loadVersion++;ceremony.cancel();if(memoryMesh)memoryMesh.visible=true;else demo.visible=true;},

 async load(url,settings,reveal=false){const version=++loadVersion;const response=await fetch(url);if(!response.ok)throw new Error('无法读取 3D 记忆，请重试。');const buffer=await response.arrayBuffer();if(version!==loadVersion)return;const next=new MemoryGaussians(buffer);if(memoryMesh){inside.remove(memoryMesh);memoryMesh.dispose();}memoryMesh=next;inside.add(next);demo.visible=false;applySettings(settings,reveal);if(reveal)ceremony.reveal(next);},
 getSettings(){return {designVersion:2,renderVersion:2,fit:fill,contentScale,depthVolume,glow:glassMaterial.uniforms.glow.value,frost:glassMaterial.uniforms.frost.value,brightness:Number($('brightness').value),azimuth,elevation,zoom};},
 capture(){return renderer.domElement.toDataURL('image/png');}
};
window.__prismatic={renderer,scene,camera,composer,ready:true};
