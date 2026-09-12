import * as THREE from './vendor/three.module.js';
// Real silhouette geometry: the original drawing is preserved, including white faces.
// No square transparent decal or recoloring of the supplied artwork.
export function addRearStickers(parent){
 const texture=new THREE.TextureLoader().load(new URL('./rear-sticker-art.jpg',import.meta.url).href);texture.colorSpace=THREE.SRGBColorSpace;texture.anisotropy=8;
 const paper=new THREE.MeshStandardMaterial({map:texture,roughness:.78,metalness:0,polygonOffset:true,polygonOffsetFactor:-2,polygonOffsetUnits:-2});
 const contours=[
 [[44,421],[66,388],[79,363],[76,318],[83,289],[115,268],[145,267],[159,224],[175,211],[193,219],[207,255],[212,267],[281,275],[302,256],[323,252],[345,270],[358,311],[386,335],[399,375],[390,418],[397,501],[375,513],[363,498],[355,541],[329,537],[308,522],[276,539],[234,531],[203,519],[161,519],[125,506],[98,493],[70,485],[71,449],[47,438]],
 [[478,413],[490,378],[510,345],[511,305],[524,263],[550,230],[580,215],[607,221],[643,254],[666,232],[704,218],[735,226],[762,248],[780,287],[787,311],[818,326],[840,366],[837,407],[818,439],[803,450],[804,483],[783,521],[750,542],[718,551],[691,540],[668,516],[643,555],[612,577],[581,578],[551,554],[533,522],[521,487],[524,466],[491,452]],
 [[903,440],[914,399],[921,357],[939,307],[972,280],[1008,260],[1055,251],[1091,255],[1094,232],[1106,215],[1131,216],[1152,230],[1184,233],[1224,246],[1259,274],[1267,307],[1251,326],[1264,343],[1258,365],[1236,380],[1209,377],[1214,408],[1198,450],[1187,470],[1184,516],[1166,533],[1150,530],[1128,560],[1091,578],[1048,582],[1004,568],[975,550],[954,518],[943,484],[920,467]]
 ];
 const stickers=new THREE.Group();stickers.name='Rear chassis — three die-cut easter eggs';stickers.position.set(0,-1.27,-2.913);stickers.rotation.y=Math.PI;parent.add(stickers);
 for(const points of contours){const shape=new THREE.Shape();const n=points.length;const mid=(a,b)=>[(a[0]+b[0])/2,(a[1]+b[1])/2];let m=mid(points[n-1],points[0]);shape.moveTo(m[0],-m[1]);for(let i=0;i<n;i++){m=mid(points[i],points[(i+1)%n]);shape.quadraticCurveTo(points[i][0],-points[i][1],m[0],-m[1]);}shape.closePath();const geometry=new THREE.ShapeGeometry(shape,8),p=geometry.attributes.position,uv=geometry.attributes.uv;
 for(let i=0;i<p.count;i++){const x=p.getX(i),y=-p.getY(i);uv.setXY(i,x/1280,1-y/905);p.setXYZ(i,(x-640)*.00135,(400-y)*.00135,0);}geometry.computeBoundingSphere();
 const sticker=new THREE.Mesh(geometry,paper);sticker.name='Contour-cut printed paper';sticker.userData.aoExcluded=true;stickers.add(sticker);
 }
 return stickers;
}
