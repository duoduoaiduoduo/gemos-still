import fs from 'node:fs';
import * as THREE from '../vendor/three.module.js';
import {buildComputer} from '../computer.js';
globalThis.document={createElement:()=>({width:0,height:0,getContext:()=>({createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4)}),putImageData(){},fillText(){}})})};
const {group}=buildComputer(new THREE.MeshBasicMaterial());group.updateMatrixWorld(true);
const parts=[];let index=0;
group.traverse(mesh=>{if(!mesh.isMesh)return;const id=index++;if(mesh.userData.aoExcluded||mesh.material.transparent)return;
const g=mesh.geometry.index?mesh.geometry.toNonIndexed():mesh.geometry;const p=g.attributes.position,n=g.attributes.normal;const matrix=new THREE.Matrix3().getNormalMatrix(mesh.matrixWorld),positions=[],normals=[];
for(let i=0;i<p.count;i++){positions.push(...new THREE.Vector3().fromBufferAttribute(p,i).applyMatrix4(mesh.matrixWorld).toArray());normals.push(...new THREE.Vector3().fromBufferAttribute(n,i).applyMatrix3(matrix).normalize().toArray());}
parts.push({id,positions,normals,color:mesh.material.color.toArray(),surface:mesh.material.userData.surface,roughness:mesh.material.roughness});});
fs.writeFileSync(new URL('../baked/source.json',import.meta.url),JSON.stringify(parts));console.log(`${parts.length} opaque parts exported.`);
