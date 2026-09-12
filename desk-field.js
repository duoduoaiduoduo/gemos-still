import * as THREE from './vendor/three.module.js';
const smooth=x=>{x=THREE.MathUtils.clamp(x,0,1);return x*x*(3-2*x);};
const hash=x=>{const n=Math.sin(x*127.1+311.7)*43758.5453;return n-Math.floor(n);};
export function createDeskField(scene,keys){
 const group=new THREE.Group();group.name='Desk keepsakes';scene.add(group);
 const silver=new THREE.MeshStandardMaterial({color:'#a7aab0',metalness:.85,roughness:.29});
 const ink=new THREE.MeshStandardMaterial({color:'#253646',metalness:.28,roughness:.32});
 const gold=new THREE.MeshStandardMaterial({color:'#bc9c5c',metalness:.78,roughness:.3});
 const rubber=new THREE.MeshStandardMaterial({color:'#cbc1ad',roughness:.85});
 function part(parent,geo,mat,x=0,y=0,z=0){const m=new THREE.Mesh(geo,mat);m.position.set(x,y,z);m.castShadow=true;m.receiveShadow=true;parent.add(m);return m;}
 const pen=new THREE.Group();
 part(pen,new THREE.CylinderGeometry(.047,.047,.84,24),ink);
 part(pen,new THREE.CylinderGeometry(.048,.048,.20,24),silver,0,.52);
 part(pen,new THREE.ConeGeometry(.045,.17,24),gold,0,-.505).rotation.z=Math.PI;
 part(pen,new THREE.CylinderGeometry(.051,.051,.024,24),gold,0,.405);
 part(pen,new THREE.BoxGeometry(.018,.25,.019),silver,.046,.42);
 pen.rotation.set(0,.2,Math.PI/2);pen.position.set(2.75,-1.707,.55);group.add(pen);
 const ring=new THREE.Group();part(ring,new THREE.TorusGeometry(.13,.018,10,36),silver).rotation.x=Math.PI/2;ring.position.set(-3.0,-1.736,.10);group.add(ring);
 const eraser=new THREE.Group();const e=part(eraser,new THREE.BoxGeometry(.23,.10,.15),rubber);e.rotation.y=.2;eraser.position.set(-2.30,-1.705,1.35);group.add(eraser);
 const objects=[pen,ring,eraser].map((mesh,i)=>{
  const shadow=new THREE.Mesh(new THREE.PlaneGeometry(i===0?1.35:.55,.48),new THREE.ShaderMaterial({transparent:true,depthWrite:false,uniforms:{strength:{value:.3}},vertexShader:'varying vec2 vUv;void main(){vUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);}',fragmentShader:'varying vec2 vUv;uniform float strength;void main(){vec2 p=(vUv-.5)*2.;gl_FragColor=vec4(0.,0.,0.,strength*exp(-dot(p,p)*3.)*(1.-smoothstep(.7,1.,length(p))));}'}));
  shadow.rotation.x=-Math.PI/2;shadow.position.set(mesh.position.x,-1.754,mesh.position.z);scene.add(shadow);
  return {mesh,shadow,rest:mesh.position.clone(),rotation:mesh.rotation.clone(),releasePosition:new THREE.Vector3(),releaseRotation:new THREE.Euler()};
 });
 let active=false,start=0,released=-100,lastTime=0;
 function reset(){active=false;released=-100;for(const o of objects){o.mesh.position.copy(o.rest);o.mesh.rotation.copy(o.rotation);}for(const k of keys){k.cap.position.y=k.rest;if(k.print)k.print.position.y=.084;}}
 function update(t,phase){
  if(t<lastTime)reset();lastTime=t;
  const on=phase==='generating'||phase==='revealing';
  if(on&&!active){active=true;start=t;}
  if(!on&&active){active=false;released=t;for(const o of objects){o.releasePosition.copy(o.mesh.position);o.releaseRotation.copy(o.mesh.rotation);}}
  for(let i=0;i<keys.length;i++){const k=keys[i],clock=t*(5+hash(i)*4)+i*1.73,cycle=Math.floor(clock),u=clock-cycle;const press=on&&hash(cycle+i*97)>.70?Math.sin(Math.PI*smooth(u))**2*.035:0;k.cap.position.y=k.rest-press;if(k.print)k.print.position.y=.084-press;}
  objects.forEach((o,i)=>{
   if(active){const lift=smooth((t-start)/(1.4+i*.22)),a=t*(.65+i*.13)+i*2;
    o.mesh.position.set(o.rest.x+lift*(Math.sin(a)*.075+Math.sin(a*1.7)*.025),o.rest.y+lift*([1.25,1.65,1.40][i]+Math.sin(a*1.2)*.085),o.rest.z+lift*Math.cos(a*.8)*.065);
    o.mesh.rotation.set(o.rotation.x+lift*Math.sin(a*.7)*.22,o.rotation.y+lift*Math.sin(a*.5)*.35,o.rotation.z+lift*Math.sin(a)*.18);
   }else{const dt=t-released,h=Math.max(0,o.releasePosition.y-o.rest.y),fall=Math.sqrt(2*h/2.8),after=dt-fall;const y=dt<fall?Math.max(0,h-1.4*dt*dt):(after<.45?Math.max(0,Math.sin(after/.45*Math.PI))*.035:0);const land=smooth(dt/1.25);o.mesh.position.copy(o.releasePosition).lerp(o.rest,land);o.mesh.position.y=o.rest.y+y;if(dt>2)o.mesh.position.copy(o.rest);o.mesh.rotation.set(THREE.MathUtils.lerp(o.releaseRotation.x,o.rotation.x,land),THREE.MathUtils.lerp(o.releaseRotation.y,o.rotation.y,land),THREE.MathUtils.lerp(o.releaseRotation.z,o.rotation.z,land));}
   const h=Math.max(0,o.mesh.position.y-o.rest.y);o.shadow.position.x=o.mesh.position.x;o.shadow.position.z=o.mesh.position.z;o.shadow.scale.setScalar(1+h*.45);o.shadow.material.uniforms.strength.value=.28/(1+h*4);
  });
 }
 return {group,update,reset};
}
