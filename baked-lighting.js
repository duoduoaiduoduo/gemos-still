import * as THREE from './vendor/three.module.js';

/** Fixed diffuse GI from Cycles; reflections and dielectric highlights stay live. */
export async function applyBakedLighting(group,floor){
 const [metaResponse,uvResponse]=await Promise.all([fetch('./baked/manifest.json?v=station-20260913-1'),fetch('./baked/lightmap-uv.bin?v=station-20260913-1')]);
 if(!metaResponse.ok||!uvResponse.ok)throw new Error('Baked lighting assets unavailable');
 const meta=await metaResponse.json(),uvs=new Float32Array(await uvResponse.arrayBuffer());
 if(meta.ready===false)return {pending:true};
 const [map,groundMap]=await Promise.all([new THREE.TextureLoader().loadAsync('./baked/irradiance.png?v=station-20260913-1'),new THREE.TextureLoader().loadAsync('./baked/ground.png?v=station-20260913-1')]);
 map.colorSpace=THREE.NoColorSpace;map.flipY=true;map.anisotropy=8;
 const meshes=[];group.traverse(o=>{if(o.isMesh)meshes.push(o);});
 const pending=meta.parts.map(part=>{
  const mesh=meshes[part.id];if(!mesh||mesh.userData.aoExcluded)throw new Error('Baked model topology mismatch');
  const geometry=mesh.geometry.index?mesh.geometry.toNonIndexed():mesh.geometry.clone();
  if(geometry.attributes.position.count!==part.count||part.offset+part.count*2>uvs.length)throw new Error('Baked UV layout mismatch');
  geometry.setAttribute('bakedUv',new THREE.BufferAttribute(uvs.slice(part.offset,part.offset+part.count*2),2));
  return {mesh,geometry};
 });
 const materials=new Map();
 for(const {mesh,geometry}of pending){
  mesh.geometry=geometry;
  if(!materials.has(mesh.material)){
   const source=mesh.material,mat=source.clone(),previous=source.onBeforeCompile;
   mat.onBeforeCompile=(shader,renderer)=>{
    previous.call(mat,shader,renderer);
    shader.uniforms.bakedLighting={value:map};shader.uniforms.bakedScale={value:meta.scale*.72};
    shader.vertexShader='attribute vec2 bakedUv;varying vec2 vBakeUv;\n'+shader.vertexShader;
    shader.vertexShader=shader.vertexShader.replace('#include <begin_vertex>','#include <begin_vertex>\nvBakeUv=bakedUv;');
    shader.fragmentShader='uniform sampler2D bakedLighting;uniform float bakedScale;varying vec2 vBakeUv;\n'+shader.fragmentShader;
    shader.fragmentShader=shader.fragmentShader.replace('vec3 totalDiffuse = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse;','vec3 totalDiffuse = texture2D(bakedLighting,vBakeUv).rgb*bakedScale*material.diffuseColor;');
   };
   mat.customProgramCacheKey=()=>`cycles-diffuse-v2:${previous.toString()}`;
   materials.set(source,mat);
  }
  mesh.material=materials.get(mesh.material);mesh.userData.bakedLighting=true;
 }
 groundMap.colorSpace=THREE.NoColorSpace;groundMap.flipY=true;groundMap.anisotropy=8;
 const previousFloor=floor.material.onBeforeCompile;
 floor.material.onBeforeCompile=(shader,renderer)=>{
  previousFloor(shader,renderer);shader.uniforms.bakedGround={value:groundMap};
  shader.fragmentShader='uniform sampler2D bakedGround;\n'+shader.fragmentShader;
  shader.fragmentShader=shader.fragmentShader.replace('outgoingLight *= 1.0-smoothstep','outgoingLight=texture2D(bakedGround,vec2(vGroundWorld.x,-vGroundWorld.z)/16.+.5).rgb*4.*diffuseColor.rgb+reflectedLight.directSpecular+reflectedLight.indirectSpecular;\noutgoingLight *= 1.0-smoothstep');
 };
 floor.material.customProgramCacheKey=()=> 'cycles-ground-v1';floor.material.needsUpdate=true;
 return {parts:pending.length,samples:meta.samples,bounces:meta.bounces};
}
