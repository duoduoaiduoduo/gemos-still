import * as THREE from './vendor/three.module.js';
import { RoundedBoxGeometry } from './vendor/addons/geometries/RoundedBoxGeometry.js';
import { housingData, baseHousingData } from './housing.js';

function outline(w,h,r=.04){
 const s=new THREE.Shape(),x=-w/2,y=-h/2;
 s.moveTo(x+r,y);s.lineTo(x+w-r,y);s.quadraticCurveTo(x+w,y,x+w,y+r);
 s.lineTo(x+w,y+h-r);s.quadraticCurveTo(x+w,y+h,x+w-r,y+h);
 s.lineTo(x+r,y+h);s.quadraticCurveTo(x,y+h,x,y+h-r);
 s.lineTo(x,y+r);s.quadraticCurveTo(x,y,x+r,y);return s;
}
function windowOutline(w,h,c=.16){
 const s=new THREE.Shape(),x=w/2,y=h/2,r=.035;
 s.moveTo(-x+r,-y);s.lineTo(x-r,-y);s.quadraticCurveTo(x,-y,x,-y+r);
 s.lineTo(x,y-c);s.quadraticCurveTo(x,y-c+.025,x-.025,y-c+.05);
 s.lineTo(x-c+.05,y-.025);s.quadraticCurveTo(x-c+.025,y,x-c,y);
 s.lineTo(-x+c,y);s.quadraticCurveTo(-x+c-.025,y,-x+c-.05,y-.025);
 s.lineTo(-x+.025,y-c+.05);s.quadraticCurveTo(-x,y-c+.025,-x,y-c);
 s.lineTo(-x,-y+r);s.quadraticCurveTo(-x,-y,-x+r,-y);return s;
}
function hole(shape,path,x=0,y=0){shape.holes.push(new THREE.Path(path.getPoints(32).map(p=>new THREE.Vector2(p.x+x,p.y+y)).reverse()));}
function extrude(shape,d,b=.012){const g=new THREE.ExtrudeGeometry(shape,{depth:d,bevelEnabled:b>0,bevelSize:b,bevelThickness:b,bevelSegments:5,curveSegments:16});g.translate(0,0,-d/2);return g;}
function box(w,h,d,r=.02){return new RoundedBoxGeometry(w,h,d,5,Math.min(r,h*.24,d*.24,w*.24));}
function ring(w,h,iw,ih,d,b=.012,c=.16){const s=windowOutline(w,h,c);hole(s,windowOutline(iw,ih,Math.max(.06,c-.025)));return extrude(s,d,b);}
function keycap(w,h,d){
 const g=new RoundedBoxGeometry(w,h,d,6,.009),p=g.attributes.position;
 for(let i=0;i<p.count;i++){let x=p.getX(i),y=p.getY(i),z=p.getZ(i);const t=THREE.MathUtils.clamp(y/h+.5,0,1);x*=1-.20*t;z*=1-.17*t;if(y>h*.2)y-=.014*Math.exp(-((x/(w*.48))**2+(z/(d*.53))**2)*1.8);p.setXYZ(i,x,y,z);}
 g.computeVertexNormals();return g;
}

export function buildComputer(glassMaterial,absHeight=null){
 const group=new THREE.Group();group.name='Ivory memory terminal — detailed chassis';
 const ivory=new THREE.MeshPhysicalMaterial({color:'#d9ceba',roughness:.46,ior:1.46,specularIntensity:.62,clearcoat:.06,clearcoatRoughness:.42,envMapIntensity:.8});
 // Fine molded ABS: continuous object-space microrelief, no UV grid,
 // dark pixel dots, directional patina or stretched random bitmap.
 ivory.bumpMap=null;ivory.roughnessMap=null;ivory.roughness=.49;
 const absSurface=shader=>{
  shader.uniforms.absHeight={value:absHeight};
  shader.vertexShader='varying vec3 vAbsPoint,vAbsNormal;\n'+shader.vertexShader;
  shader.vertexShader=shader.vertexShader.replace('#include <begin_vertex>','#include <begin_vertex>\nvec3 absWorld=(modelMatrix*vec4(position,1.)).xyz;vec3 absN=mat3(modelMatrix)*normal;vAbsPoint=vec3(absWorld.x,-absWorld.z,absWorld.y);vAbsNormal=vec3(absN.x,-absN.z,absN.y);');
  shader.fragmentShader=`uniform sampler2D absHeight;varying vec3 vAbsPoint,vAbsNormal;
   float absRelief(vec3 p){vec3 w=pow(abs(normalize(vAbsNormal)),vec3(6.));w/=max(dot(w,vec3(1.)),.0001);
    return texture2D(absHeight,p.yz).r*w.x+texture2D(absHeight,p.xz).r*w.y+texture2D(absHeight,p.xy).r*w.z;}
  `+shader.fragmentShader;
  shader.fragmentShader=shader.fragmentShader.replace('#include <normal_fragment_maps>',`#include <normal_fragment_maps>
   float grain=absRelief(vAbsPoint/1.6);
   float height=grain*.0018;
   vec3 dpdx=dFdx(-vViewPosition),dpdy=dFdy(-vViewPosition);
   vec3 r1=cross(dpdy,normal),r2=cross(normal,dpdx);float det=dot(dpdx,r1);
   vec3 grad=sign(det)*(dFdx(height)*r1+dFdy(height)*r2);
   normal=normalize(abs(det)*normal-grad);
   roughnessFactor=clamp(roughnessFactor+(grain-.5)*.09,.38,.62);
  `);
 };
 ivory.onBeforeCompile=absSurface;ivory.userData.surface='molded-abs';
 const trim=new THREE.MeshStandardMaterial({color:'#a49371',roughness:.32,metalness:.67});
 const seam=new THREE.MeshStandardMaterial({color:'#655c4b',roughness:.84});
 const dark=new THREE.MeshPhysicalMaterial({color:'#242522',roughness:.48,clearcoat:.08});
 const keyMat=ivory.clone();keyMat.onBeforeCompile=absSurface;keyMat.color.set('#ded5c2');keyMat.bumpScale=.00065;
 const alt=keyMat.clone();alt.onBeforeCompile=absSurface;alt.color.set('#beb49f');const blue=keyMat.clone();blue.onBeforeCompile=absSurface;blue.color.set('#4e6879');
 function add(g,m,p=[0,0,0],r=null,parent=group){const mesh=new THREE.Mesh(g,m);mesh.position.set(...p);if(r)mesh.rotation.set(...r);mesh.castShadow=true;mesh.receiveShadow=true;parent.add(mesh);return mesh;}
 const cy=.84,front=.68,back=-.86;
 // Watertight hollow solids with shared edges at every frame intersection.
 function solid(data){const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(data.position,3));g.setAttribute('normal',new THREE.Float32BufferAttribute(data.normal,3));g.setAttribute('uv',new THREE.Float32BufferAttribute(new Float32Array(data.position.length/3*2),2));return g;}
 const upperShell=add(solid(housingData),ivory);upperShell.name='Continuous upper housing';
 add(ring(3.055,2.751,2.947,2.633,.046,.007,.14),dark,[0,cy,.629]);
 add(ring(3.075,2.76,3.035,2.72,.018,.003,.13),trim,[0,cy,.690]);
 for(const side of [-1,1])add(ring(1.267,2.732,1.237,2.702,.018,.003,.13),trim,[side*1.700,cy,-.085],[0,Math.PI/2,0]);
 // A shallow convex optical face catches a moving highlight at its edges.
 const glassGeometry=new THREE.BoxGeometry(3.31,2.935,1.405,64,64,12);
 const gp=glassGeometry.attributes.position;
 for(let i=0;i<gp.count;i++){const x=gp.getX(i)/1.655,y=gp.getY(i)/1.4675,z=gp.getZ(i);if(z>.70)gp.setZ(i,z+.075*Math.pow(Math.max(0,1-x*x),2)*Math.pow(Math.max(0,1-y*y),2));}
 glassGeometry.computeVertexNormals();
 const glass=add(glassGeometry,glassMaterial,[0,cy,-.065]);glass.castShadow=false;glass.userData.aoExcluded=true;
 // Countersunk fixings: actual annular recess, inset head, and crossed slots.
 function screw(x,y,z,rx=0){const parent=new THREE.Group();parent.position.set(x,y,z);parent.rotation.x=rx;group.add(parent);
  add(new THREE.TorusGeometry(.024,.007,8,24),trim,[0,0,0],null,parent);
  add(new THREE.CylinderGeometry(.019,.019,.007,24),seam,[0,0,-.006],[Math.PI/2,0,0],parent);
  add(new THREE.CylinderGeometry(.016,.016,.004,24),trim,[0,0,-.003],[Math.PI/2,0,0],parent);
  for(const r of [0,Math.PI/2])add(box(.022,.003,.002,.0004),dark,[0,0,0],[0,0,r],parent);
 }
 for(const x of [-1.636,1.636])screw(x,2.297,.764);
 for(const x of [-1.62,1.62])screw(x,2.420,-.66,-Math.PI/2);
 // Monitor plinth and the narrow shadow line separating it from the lower chassis.
 add(box(3.57,.105,1.68,.025),ivory,[0,-.789,-.085]);
 add(box(3.53,.019,1.64,.005),seam,[0,-.851,-.085]);
 const lowerShell=add(solid(baseHousingData),ivory);lowerShell.name='Continuous lower housing';
 // Two distinct planes: steep drive fascia, then a gently sloping keyboard deck.
 const fascia=new THREE.Group();fascia.position.set(0,-1.005,.813);fascia.rotation.x=-.14;group.add(fascia);
 const driveSlot=new THREE.Object3D();driveSlot.name='Card slot alignment';driveSlot.position.set(.98,.047,.018);fascia.add(driveSlot);
 add(box(.957,.241,.065,.007),dark,[.98,0,-.042],null,fascia);
 add(box(.805,.031,.022,.003),seam,[.98,.053,-.003],null,fascia);
 add(box(.806,.022,.024,.002),dark,[.98,.047,.005],null,fascia);
 add(box(.26,.057,.030,.004),dark,[.99,.083,.018],null,fascia);
 add(box(.75,.011,.018,.002),seam,[.954,-.040,.006],null,fascia);
 add(box(.13,.061,.035,.005),ivory,[1.336,-.079,.015],null,fascia);
 add(box(.029,.034,.016,.002),seam,[.592,-.073,.007],null,fascia);
 add(box(.010,.014,.005,.001),new THREE.MeshStandardMaterial({color:'#9a512e',roughness:.3}),[.592,-.073,.017],null,fascia);
 // Split casing seam to the left of the drive, following the fascia angle.
 const deck=new THREE.Group();deck.position.set(0,-1.335,1.42);deck.rotation.x=.27;group.add(deck);
 add(box(3.26,.04,1.035,.018),seam,[0,-.071,-.01],null,deck);
 add(box(3.20,.018,.988,.01),dark,[0,-.045,-.01],null,deck);
 // Small independent blue function button above the numeric block.
 add(box(.145,.025,.072,.008),blue,[1.437,.042,-.542],null,deck);
 // Staggered vintage layout: separate numeric block and individually dished keycaps.
 const pitch=.176,depth=.166,height=.133,cache=new Map(),keys=[];
 function key(x,z,u,label,material=keyMat){const w=u*pitch-.018,k=String(u);if(!cache.has(k))cache.set(k,keycap(w,height,.151));
  const cap=add(cache.get(k),material,[x,.027,z],null,deck);const entry={cap,rest:.027,print:null};keys.push(entry);
  if(!label)return;
  const cv=document.createElement('canvas');cv.width=128;cv.height=64;const c=cv.getContext('2d');c.fillStyle='#655e50';c.font=label.length>3?'14px monospace':'20px monospace';c.textAlign='left';c.fillText(label,16,29);
  const t=new THREE.CanvasTexture(cv);t.colorSpace=THREE.SRGBColorSpace;
  const print=add(new THREE.PlaneGeometry(Math.min(w*.73,.16),.064),new THREE.MeshBasicMaterial({map:t,transparent:true,depthWrite:false,polygonOffset:true,polygonOffsetFactor:-1}),[x,.084,z-.014],[-Math.PI/2,0,0],deck);print.castShadow=false;entry.print=print;
 }
 const rows=[
  [[1,'esc'],[1,'1'],[1,'2'],[1,'3'],[1,'4'],[1,'5'],[1,'6'],[1,'7'],[1,'8'],[1,'9'],[1,'0'],[1,'−'],[1,'='],[1.5,'←']],
  [[1.5,'tab'],[1,'Q'],[1,'W'],[1,'E'],[1,'R'],[1,'T'],[1,'Y'],[1,'U'],[1,'I'],[1,'O'],[1,'P'],[1,'['],[1,']'],[1,'\\']],
  [[1.75,'ctrl'],[1,'A'],[1,'S'],[1,'D'],[1,'F'],[1,'G'],[1,'H'],[1,'J'],[1,'K'],[1,'L'],[1,';'],[1,"'"],[1.75,'return']],
  [[2.25,'shift'],[1,'Z'],[1,'X'],[1,'C'],[1,'V'],[1,'B'],[1,'N'],[1,'M'],[1,','],[1,'.'],[1,'/'],[2.25,'shift']],
  [[1.25,'alt'],[1.25,''],[7,''],[1.25,''],[1.25,'←'],[1.25,'↑'],[1.25,'→']]
 ];
 rows.forEach((row,ri)=>{let x=-1.572;for(const [u,label]of row){key(x+u*pitch/2,-.36+ri*depth,u,label,(u>1.4||label==='esc')?alt:keyMat);x+=u*pitch;}});
 const numbers=[['7','8','9'],['4','5','6'],['1','2','3'],['0','.','↵']];
 numbers.forEach((row,ri)=>row.forEach((label,col)=>key(1.115+col*pitch,-.36+ri*depth,1,label,ri===0?alt:keyMat)));
 key(1.203,.304,2,'0',alt);key(1.467,.304,1,'.',keyMat);
 // Thick rounded front lip and low rubber feet, kept separate from the sloping deck.
 for(const x of [-1.46,1.46])for(const z of [-.72,1.66])add(box(.24,.069,.245,.022),dark,[x,-1.718,z]);
 // Extend the rear chamber by 1.90 units while retaining the front controls,
 // keyboard and front/rear bevel thicknesses. Deform shared solids continuously.
 group.updateMatrixWorld(true);
 group.traverse(mesh=>{
  if(!mesh.isMesh)return;
  mesh.geometry=mesh.geometry.clone();
  const p=mesh.geometry.attributes.position,n=mesh.geometry.attributes.normal;
  const inverse=mesh.matrixWorld.clone().invert(),normalMatrix=new THREE.Matrix3().getNormalMatrix(mesh.matrixWorld),normalInverse=normalMatrix.clone().invert();
  const v=new THREE.Vector3(),normal=new THREE.Vector3();
  for(let i=0;i<p.count;i++){
   v.fromBufferAttribute(p,i).applyMatrix4(mesh.matrixWorld);
   const z=v.z,k=THREE.MathUtils.clamp((.35-z)/.8,0,1);
   v.z-=1.90*k;v.applyMatrix4(inverse);p.setXYZ(i,v.x,v.y,v.z);
   normal.fromBufferAttribute(n,i).applyMatrix3(normalMatrix);
   if(z>-.45&&z<.35)normal.z/=3.375;
   normal.applyMatrix3(normalInverse).normalize();n.setXYZ(i,normal.x,normal.y,normal.z);
  }
  mesh.geometry.computeBoundingBox();mesh.geometry.computeBoundingSphere();
 });
 return {group,glass,driveSlot,keys};
}
