import * as THREE from './vendor/three.module.js';
// A filled particle bed with non-periodic, multi-scale turbulent surface motion.
export function makeTide(mobile){
 const count=mobile?62000:135000,a=new Float32Array(count*3);let seed=42;const rand=()=>{seed=(1664525*seed+1013904223)>>>0;return seed/4294967296;};for(let i=0;i<count*3;i++)a[i]=rand();
 const geometry=new THREE.BufferGeometry();geometry.setAttribute('position',new THREE.BufferAttribute(a,3));
 geometry.setAttribute('destination',new THREE.BufferAttribute(new Float32Array(count*3),3));geometry.setAttribute('destinationColor',new THREE.BufferAttribute(new Float32Array(count*3),3));
 const material=new THREE.ShaderMaterial({transparent:true,depthWrite:true,blending:THREE.NormalBlending,uniforms:{time:{value:0},swirl:{value:0},fade:{value:1},pixels:{value:700},build:{value:-1},low:{value:-.53},high:{value:2.21}},vertexShader:`
 attribute vec3 destination,destinationColor;uniform float time,swirl,pixels,build,low,high;varying float handoff;varying float forming;varying vec2 flowDirection;varying vec3 color;varying vec3 sphereCenter;varying float sphereRadius;
 float hash(vec3 p){p=fract(p*.3183099+vec3(.17,.31,.73));p*=17.;return fract(p.x*p.y*p.z*(p.x+p.y+p.z));}
 float noise(vec3 p){vec3 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);}
 float field(vec3 p){return noise(p)*.60+noise(p*2.07+13.1)*.28+noise(p*4.19+7.7)*.12;}
 void main(){vec3 s=position;float t=time*.36;vec2 uv=s.xz*2.-1.;
 vec3 domain=vec3(uv.x*1.7,uv.y*1.6,t);
 float n=field(domain+vec3(field(domain+8.),field(domain+19.),0.)*.85);
 float detail=field(domain*1.8+vec3(6.,3.,-t*.7));
 float level=1.25+(n-.5)*1.4+(detail-.5)*.40+swirl*.2;
 // Most grains occupy the entire column; a small fraction emphasizes surface foam.
 float layer=s.y<.88?s.y/.88: .91+(s.y-.88)*.75;
 float eddy=field(vec3(uv*2.3,s.y*3.+t*.8));
 vec3 p=vec3(uv.x*1.44,-.50+layer*level,uv.y*1.56-.95);
 p.x+= (field(vec3(uv*2.,s.y*4.-t))-.5)*.18*(1.-abs(uv.x));
 p.z+= (eddy-.5)*.14*(1.-abs(uv.y));
 p.y+= (eddy-.5)*.15*sin(layer*3.14159);
 // Travel around the XY perimeter: top/right/bottom/left only.
 // The Z axis is the open tunnel; never place sheets on the front or back.
 if(swirl>.001){
 float angle=atan(s.y*2.-1.,s.x*2.-1.)-t*(1.7+s.z*.18);
 vec3 current=vec3(cos(angle)*2.,sin(angle)*2.,s.z*3.-t*.7);
 float roll=field(current+vec3(eddy,n,detail)*1.4);
 float churn=field(current*2.1+vec3(t*.23,7.,3.));
 angle+=(roll-.5)*1.5+(churn-.5)*.42;
 vec2 loop=vec2(cos(angle),sin(angle));loop/=max(abs(loop.x),abs(loop.y));
 // Uneven depths and traveling eddies, bounded to retain a clear central aperture.
 loop*=clamp(.86+(roll-.5)*.48+(churn-.5)*.20+(hash(s*37.)-.5)*.045,.73,.99);
 float alongDepth=clamp((s.z*2.-1.)*1.50+(churn-.5)*.40,-1.56,1.56);
 vec3 circulating=vec3(loop.x*1.44,.84+loop.y*1.33,alongDepth-.95);
 p=mix(p,circulating,swirl);
 }
 float crest=smoothstep(.74,.99,layer)*smoothstep(.38,.68,n+detail*.12);
 // Color travels with the same eddies that move the grains, throughout the bed.
 float flow=clamp((eddy-.28)*2.2+(detail-.5)*.16,0.,1.);
 vec3 submerged=mix(vec3(.09,.16,.62),vec3(.39,.12,.82),smoothstep(.08,.50,flow));
 submerged=mix(submerged,vec3(.66,.14,.53),smoothstep(.50,.88,flow));
 float ribbon=smoothstep(.56,.76,noise(domain+vec3(0.,layer*2.,-t*.35)));
 submerged=mix(submerged,vec3(.06,.43,.52),ribbon*.65);
 submerged*=.85+.15*layer;
 // Only the moving upper skin becomes pale: keep submerged colors saturated.
 color=mix(submerged,vec3(.62,.43,.96),smoothstep(.82,.98,layer));
 color=mix(color,vec3(1.,.90,1.),crest);
 float height=mix(.035,.745,clamp((destination.y-low)/(high-low),0.,1.))+.014*sin(destination.x*4.+destination.z*3.);
 float travel=build<0.?0.:smoothstep(height-.20,height+.025,build);
 handoff=build<0.?0.:smoothstep(height+.065,height+.225,build);
 forming=travel;
 vec3 direction=(modelViewMatrix*vec4(destination-p,0.)).xyz;flowDirection=normalize(direction.xy+vec2(.00001));
 p=mix(p,destination,travel);
 color=mix(color,destinationColor,travel);
 vec4 mv=modelViewMatrix*vec4(p,1.);gl_Position=projectionMatrix*mv;
 gl_PointSize=clamp(pixels*.156*(.65+s.y*.25)/-mv.z,13.2,43.2);
 gl_PointSize=mix(gl_PointSize,4.5,travel*travel);
 sphereCenter=mv.xyz;sphereRadius=gl_PointSize*(-mv.z)/(pixels*projectionMatrix[1][1]);
 }`,fragmentShader:`
 uniform float fade;uniform mat4 projectionMatrix;varying float handoff;varying float forming;varying vec2 flowDirection;
 varying vec3 color,sphereCenter;varying float sphereRadius;
 void main(){
  if(handoff>=.999)discard;
  vec2 q=gl_PointCoord*2.-1.;q.y=-q.y;
  q=vec2(dot(q,flowDirection),dot(q,vec2(-flowDirection.y,flowDirection.x)));
  q.y/=1.-.48*sin(forming*3.14159);float r2=dot(q,q);if(r2>=1.)discard;
  // Analytic sphere surface, with per-fragment depth and a solid unlit color.
  vec3 N=vec3(q,sqrt(1.-r2)),surface=sphereCenter+N*sphereRadius;
  vec4 clip=projectionMatrix*vec4(surface,1.);gl_FragDepth=clip.z/clip.w*.5+.5;
  float edge=1.-smoothstep(1.-fwidth(r2),1.,r2);
  gl_FragColor=vec4(color,fade*edge*mix(1.,exp(-r2*2.),smoothstep(.75,1.,forming))*(1.-handoff));
 }`});
 const mesh=new THREE.Points(geometry,material);mesh.frustumCulled=false;mesh.renderOrder=1;
 // Pair angular neighborhoods, then depth, to limit paths crossing the whole tank.
 const key=(x,y,z)=>Math.floor((Math.atan2(y,x)+Math.PI)/(2*Math.PI)*24)*100+z;
 const order=Array.from({length:count},(_,i)=>i).sort((i,j)=>key(a[i*3]*2-1,a[i*3+1]*2-1,a[i*3+2]*2-1)-key(a[j*3]*2-1,a[j*3+1]*2-1,a[j*3+2]*2-1));
 function setTarget(memory){
  memory.updateMatrixWorld(true);const data=memory.texture.image.data,min=memory.material.uniforms.clipMin.value,max=memory.material.uniforms.clipMax.value;
  const candidates=[],point=new THREE.Vector3();
  for(let i=0;i<memory.count;i++){
   const offset=i*16;if(data[offset+3]<.025)continue;
   point.fromArray(data,offset).applyMatrix4(memory.matrixWorld);
   if(point.x<min.x||point.x>max.x||point.y<min.y||point.y>max.y||point.z<min.z||point.z>max.z)continue;
   candidates.push(i);
  }
  const targets=[];
  for(let j=0;j<count&&candidates.length;j++){
   const i=candidates[Math.floor((j+.5)*candidates.length/count)],offset=i*16;
   point.fromArray(data,offset).applyMatrix4(memory.matrixWorld);
   targets.push({p:point.toArray(),c:[data[offset+12],data[offset+13],data[offset+14]].map(v=>v*memory.material.uniforms.brightness.value),key:key(point.x,(point.y-min.y)/(max.y-min.y)*2-1,point.z-(min.z+max.z)/2)});
  }
  targets.sort((a,b)=>a.key-b.key);
  const positions=geometry.attributes.destination,colors=geometry.attributes.destinationColor;
  for(let j=0;j<count;j++){const target=targets[j],i=order[j];positions.setXYZ(i,...(target?.p??[0,min.y,0]));colors.setXYZ(i,...(target?.c??[0,0,0]));}
  positions.needsUpdate=colors.needsUpdate=true;material.uniforms.low.value=min.y;material.uniforms.high.value=max.y;
 }

 return {mesh,setTarget,update(t,vortex,opacity,pixels,build=-1){material.uniforms.build.value=build;material.uniforms.time.value=t;material.uniforms.swirl.value=vortex;material.uniforms.fade.value=opacity;material.uniforms.pixels.value=pixels;mesh.visible=opacity>.001;}};
}
