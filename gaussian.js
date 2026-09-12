import * as THREE from './vendor/three.module.js';

/** Anisotropic 3D Gaussian renderer, projected covariance + worker depth sorting. */
export class MemoryGaussians extends THREE.Mesh {
  constructor(buffer) {
    if (!buffer.byteLength || buffer.byteLength % 64) throw new Error('记忆模型数据不完整。');
    const data=new Float32Array(buffer),count=data.length/16;
    if(count>1500000)throw new Error('记忆模型超出浏览器支持的大小。');
    const width=2048,height=Math.ceil(count*4/width);
    const pixels=new Float32Array(width*height*4);pixels.set(data);
    const texture=new THREE.DataTexture(pixels,width,height,THREE.RGBAFormat,THREE.FloatType);
    texture.needsUpdate=true;
    const geometry=new THREE.InstancedBufferGeometry();
    geometry.setAttribute('position',new THREE.Float32BufferAttribute([-1,-1,0,1,-1,0,1,1,0,-1,1,0],3));
    geometry.setIndex([0,1,2,0,2,3]);
    const order=new Float32Array(count);for(let i=0;i<count;i++)order[i]=i;
    geometry.setAttribute('splatIndex',new THREE.InstancedBufferAttribute(order,1).setUsage(THREE.DynamicDrawUsage));
    geometry.instanceCount=count;
    const material=new THREE.ShaderMaterial({
      transparent:true,depthWrite:false,depthTest:false,side:THREE.DoubleSide,
      uniforms:{gaussians:{value:texture},viewport:{value:new THREE.Vector2(1,1)},reveal:{value:1},brightness:{value:1.2},perspective:{value:1},clipMin:{value:new THREE.Vector3(-10,-10,-10)},clipMax:{value:new THREE.Vector3(10,10,10)}},
      vertexShader:`
      attribute float splatIndex;
      uniform sampler2D gaussians;
      uniform vec2 viewport;
      uniform float brightness,perspective,reveal;
      uniform vec3 clipMin,clipMax;
      varying vec2 vGaussian;
      varying vec4 vColor;
      vec4 fetchData(int index){return texelFetch(gaussians,ivec2(index%2048,index/2048),0);}
      void main(){
        int base=int(splatIndex)*4;
        vec4 center=fetchData(base),a=fetchData(base+1),b=fetchData(base+2);
        vColor=vec4(fetchData(base+3).rgb*brightness,center.w);
        vec3 world=(modelMatrix*vec4(center.xyz,1.)).xyz;
        float height=mix(.035,.745,clamp((world.y-clipMin.y)/(clipMax.y-clipMin.y),0.,1.))+.014*sin(world.x*4.+world.z*3.);
        float appear=smoothstep(height-.005,height+.12,reveal);
        float settle=reveal>=1.?1.:smoothstep(height+.025,height+.225,reveal);
        vColor.a*=reveal>=1.?1.:appear;
        vColor.rgb=mix(mix(vColor.rgb,vec3(.63,.46,.88),.24),vColor.rgb,settle);
        if(any(lessThan(world,clipMin))||any(greaterThan(world,clipMax))){gl_Position=vec4(2.,2.,2.,1.);vGaussian=vec2(4.);vColor.a=0.;return;}
        mat3 covariance=mat3(a.x,a.y,a.z,a.y,a.w,b.x,a.z,b.x,b.y);
        mat3 rotation=mat3(modelViewMatrix);
        mat3 cv=rotation*covariance*transpose(rotation);
        vec2 focal=vec2(projectionMatrix[0][0],projectionMatrix[1][1])*viewport*.5;
        vec3 viewCenter=(modelViewMatrix*vec4(center.xyz,1.)).xyz;
        float depth=max(.05,-viewCenter.z);
        vec3 jx=mix(vec3(focal.x,0.,0.),vec3(focal.x/depth,0.,focal.x*viewCenter.x/(depth*depth)),perspective);
        vec3 jy=mix(vec3(0.,focal.y,0.),vec3(0.,focal.y/depth,focal.y*viewCenter.y/(depth*depth)),perspective);
        float xx=dot(jx,cv*jx)+.25;
        float xy=dot(jx,cv*jy);
        float yy=dot(jy,cv*jy)+.25;
        float middle=(xx+yy)*.5;
        float spread=sqrt(max(0.,(xx-yy)*(xx-yy)*.25+xy*xy));
        float l1=max(.1,middle+spread),l2=max(.1,middle-spread);
        vec2 eigen=abs(xy)>.00001?normalize(vec2(xy,l1-xx)):(xx>=yy?vec2(1.,0.):vec2(0.,1.));
        vec2 major=eigen*min(sqrt(l1),160.);
        vec2 minor=vec2(-eigen.y,eigen.x)*min(sqrt(l2),160.);
        // A compact seed unfurls into its own covariance ellipse, then settles exactly.
        float seed=fract(sin(splatIndex*12.9898)*43758.5453);
        float seedRadius=clamp(pow(l1*l2,.25),1.1,3.2);
        float turn=(1.-settle)*(seed-.5)*1.8;
        mat2 twist=mat2(cos(turn),sin(turn),-sin(turn),cos(turn));
        major=twist*eigen*mix(seedRadius,min(sqrt(l1),160.),settle);
        minor=twist*vec2(-eigen.y,eigen.x)*mix(seedRadius,min(sqrt(l2),160.),settle);
        float breath=1.+.10*sin(settle*3.14159);
        major*=breath;minor*=breath;
        vColor.a*=mix(min(1.,sqrt(l1*l2)/max(length(major)*length(minor),.001)),1.,settle);
        vGaussian=position.xy*3.;
        vec4 clip=projectionMatrix*modelViewMatrix*vec4(center.xyz,1.);
        clip.xy+=(major*vGaussian.x+minor*vGaussian.y)*2./viewport*clip.w;
        gl_Position=clip;
      }`,
      fragmentShader:`varying vec2 vGaussian;varying vec4 vColor;
      void main(){float r=dot(vGaussian,vGaussian);if(r>9.)discard;float alpha=min(.99,vColor.a*exp(-.5*r));if(alpha<.003)discard;gl_FragColor=vec4(vColor.rgb,alpha);}`
    });
    super(geometry,material);
    this.frustumCulled=false;this.renderOrder=2;
    this.rotation.y=0;
    this.texture=texture;
    this.count=count;
    this.sorting=false;
    this.disposed=false;
    this.lastDirection=null;
    this.worker=new Worker(new URL('./sort-worker.js',import.meta.url));
    const positions=new Float32Array(count*3);
    for(let i=0;i<count;i++)positions.set(data.subarray(i*16,i*16+3),i*3);
    this.contentBounds=new THREE.Box3();this.fullBounds=new THREE.Box3();let focusCount=0;
    const point=new THREE.Vector3();for(let i=0;i<count;i++){point.fromArray(positions,i*3);this.fullBounds.expandByPoint(point);if(data[i*16+10]>.5){this.contentBounds.expandByPoint(point);focusCount++;}}
    if(focusCount<100)this.contentBounds.copy(this.fullBounds);
    this.worker.postMessage({positions:positions.buffer},[positions.buffer]);
    this.worker.onmessage=({data})=>{
      if(this.disposed)return;
      this.geometry.attributes.splatIndex.array=new Float32Array(data.order);
      this.geometry.attributes.splatIndex.needsUpdate=true;
      this.sorting=false;
    };
    this.worker.onerror=()=>{this.sorting=false;};
    this.viewMatrix=new THREE.Matrix4();
  }
  update(camera,resolution){
    this.material.uniforms.viewport.value.copy(resolution);this.material.uniforms.perspective.value=camera.isPerspectiveCamera?1:0;
    if(this.sorting)return;
    camera.updateMatrixWorld();this.updateMatrixWorld();
    this.viewMatrix.multiplyMatrices(camera.matrixWorldInverse,this.matrixWorld);
    const m=this.viewMatrix.elements,direction=[m[2],m[6],m[10]];
    if(this.lastDirection&&direction.every((v,i)=>Math.abs(v-this.lastDirection[i])<.0005))return;
    this.lastDirection=direction;this.sorting=true;
    this.worker.postMessage({direction});
  }
  dispose(){this.disposed=true;this.worker.terminate();this.geometry.dispose();this.material.dispose();this.texture.dispose();}
}
