import * as THREE from './vendor/three.module.js';

/** A self-contained HDR studio. The same light cards illuminate the chassis
 * through PMREM and appear as view-dependent reflections in the glass. */
export function createStudio(renderer){
 const room=new THREE.Scene();room.background=new THREE.Color(.008,.009,.012);
 const enclosure=new THREE.Mesh(new THREE.BoxGeometry(30,20,28),new THREE.MeshBasicMaterial({color:new THREE.Color(.025,.025,.024),side:THREE.BackSide,toneMapped:false}));room.add(enclosure);
 function card(position,size,color){const mesh=new THREE.Mesh(new THREE.PlaneGeometry(...size),new THREE.MeshBasicMaterial({color:new THREE.Color(...color),side:THREE.DoubleSide,toneMapped:false}));mesh.position.set(...position);mesh.lookAt(0,1,0);room.add(mesh);return mesh;}
 // Restore the previous reflection layout, including the divided window.
 for(let row=0;row<3;row++)for(let col=0;col<2;col++)card([-4.4+col*.46,-.9+row*.64,5],[.40,.57],[3.8,3.6,3.3]);
 card([4,3,5],[.85,5],[3.0,3.2,3.5]);
 card([-4,.1,6],[.55,5],[1.4,1.3,1.2]);
 card([5,-.5,-4],[1.2,7],[2.2,2.3,2.5]);
 card([0,7,-1],[5,3],[3.0,3.0,3.0]);
 card([1,3,-6],[3,4],[1.2,1.35,1.6]);
 const ground=new THREE.Mesh(new THREE.PlaneGeometry(30,30),new THREE.MeshBasicMaterial({color:new THREE.Color(.19,.17,.145),toneMapped:false}));ground.rotation.x=-Math.PI/2;ground.position.y=-4;room.add(ground);
 const cubeTarget=new THREE.WebGLCubeRenderTarget(512,{type:THREE.HalfFloatType,generateMipmaps:true,minFilter:THREE.LinearMipmapLinearFilter});
 const capture=new THREE.CubeCamera(.1,50,cubeTarget);capture.position.set(0,1,0);capture.update(renderer,room);
 const pmrem=new THREE.PMREMGenerator(renderer);const environment=pmrem.fromCubemap(cubeTarget.texture);pmrem.dispose();
 return {environment:environment.texture,reflection:cubeTarget.texture};
}

/** Fine molded-ABS grain and low-amplitude roughness variations, generated
 * locally so downloaded memories never depend on external texture services. */
export function createMaterialTextures(){
 const size=512,cv=document.createElement('canvas');cv.width=size;cv.height=size;const ctx=cv.getContext('2d'),im=ctx.createImageData(size,size);
 let seed=718;const rnd=()=>{seed=(1664525*seed+1013904223)>>>0;return seed/4294967296;};
 for(let y=0;y<size;y++)for(let x=0;x<size;x++){const i=(y*size+x)*4;const n=127+(rnd()-.5)*72+Math.sin(x*.05)*3+Math.sin(y*.038)*3;im.data[i]=im.data[i+1]=im.data[i+2]=n;im.data[i+3]=255;}
 ctx.putImageData(im,0,0);
 const bump=new THREE.CanvasTexture(cv);bump.wrapS=bump.wrapT=THREE.RepeatWrapping;bump.repeat.set(6,6);bump.anisotropy=8;
 const roughCanvas=document.createElement('canvas');roughCanvas.width=size;roughCanvas.height=size;
 const rc=roughCanvas.getContext('2d'),ri=rc.createImageData(size,size);
 for(let i=0;i<ri.data.length;i+=4){const n=218+(rnd()-.5)*22;ri.data[i]=ri.data[i+1]=ri.data[i+2]=n;ri.data[i+3]=255;}
 rc.putImageData(ri,0,0);
 const rough=new THREE.CanvasTexture(roughCanvas);rough.wrapS=rough.wrapT=THREE.RepeatWrapping;rough.repeat.set(6,6);rough.anisotropy=8;
 return {bump,rough};
}

