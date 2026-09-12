// Back-to-front counting sort runs off the UI thread during camera movement.
let positions;
self.onmessage = ({data}) => {
  if (data.positions) { positions = new Float32Array(data.positions); return; }
  if (!positions) return;
  const n=positions.length/3, depth=new Float32Array(n), bins=new Uint32Array(65536);
  const [a,b,c]=data.direction;
  let low=Infinity,high=-Infinity;
  for(let i=0;i<n;i++){const d=a*positions[i*3]+b*positions[i*3+1]+c*positions[i*3+2];depth[i]=d;low=Math.min(low,d);high=Math.max(high,d);}
  const scale=65535/Math.max(high-low,1e-8);
  const keys=new Uint16Array(n);
  for(let i=0;i<n;i++){keys[i]=Math.min(65535,Math.max(0,(depth[i]-low)*scale));bins[keys[i]]++;}
  let offset=0;for(let i=0;i<65536;i++){const count=bins[i];bins[i]=offset;offset+=count;}
  const order=new Float32Array(n);
  for(let i=0;i<n;i++)order[bins[keys[i]]++]=i;
  self.postMessage({order:order.buffer},[order.buffer]);
};
