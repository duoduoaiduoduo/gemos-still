import {FILM} from './film-timeline.js';
// Original felt-piano study. Deterministic synthesis; no external audio downloads.
export async function createFilmScore(){
 const sr=44100,ctx=new OfflineAudioContext(2,Math.ceil(FILM.duration*sr),sr);
 const bus=ctx.createGain(),tone=ctx.createBiquadFilter(),room=ctx.createConvolver(),wet=ctx.createGain(),out=ctx.createGain();
 tone.type='lowpass';tone.frequency.value=6200;tone.Q.value=.35;bus.connect(tone);tone.connect(out);tone.connect(room);room.connect(wet);wet.gain.value=.16;wet.connect(out);out.connect(ctx.destination);
 let seed=271828;const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
 const impulse=ctx.createBuffer(2,sr*2.4,sr);
 for(let c=0;c<2;c++){const a=impulse.getChannelData(c);let smooth=0;for(let i=0;i<a.length;i++){smooth=.65*smooth+.35*(random()*2-1);a[i]=smooth*Math.exp(-i/(sr*.48))*(1-Math.exp(-i/(sr*.018)));}}
 room.buffer=impulse;
 const samples=new Map();
 function sample(midi){
  if(samples.has(midi))return samples.get(midi);
  const f=440*2**((midi-69)/12),duration=7,buffer=ctx.createBuffer(1,sr*duration,sr),a=buffer.getChannelData(0);
  // Decaying, slightly inharmonic strings with quiet beating and a soft hammer transient.
  const partials=[1,.46,.2,.12,.066,.036,.019,.01];
  for(let h=1;h<=partials.length;h++){
   const freq=f*h*Math.sqrt(1+.000035*h*h),decay=(midi<60?2.45:1.8)/(1+(h-1)*.46),amp=partials[h-1]*.17;
   for(let i=0;i<a.length;i++){const t=i/sr;if(t>decay*7)break;const env=(1-Math.exp(-t/.004))*Math.exp(-t/decay);a[i]+=amp*env*(Math.sin(2*Math.PI*freq*t)+.28*Math.sin(2*Math.PI*freq*1.00065*t));}
  }
  let noise=0;for(let i=0;i<sr*.065;i++){const t=i/sr;noise=.6*noise+.4*(random()*2-1);a[i]+=noise*.025*Math.exp(-t/.008)*(1-Math.exp(-t/.001));}
  samples.set(midi,buffer);return buffer;
 }
 function note(t,midi,velocity=.45){const source=ctx.createBufferSource(),gain=ctx.createGain(),pan=ctx.createStereoPanner();source.buffer=sample(midi);gain.gain.value=velocity;pan.pan.value=Math.max(-.45,Math.min(.45,(midi-64)*.016));source.connect(gain);gain.connect(pan);pan.connect(bus);source.start(t);}
 // Slow 6/8 phrasing: a warm major-key progression, space between melody notes.
 const bars=[
  [0,[48,55,64,67]],[4,[43,55,62,67]],[8,[45,52,60,64]],
  [12,[41,53,60,65]],[16,[48,55,64,67]],[20,[43,55,62,67]],
  [24,[45,52,60,64]],[28,[41,53,60,65]],[31.5,[48,55,64,67]],
  [35.5,[43,55,62,67]],[39.5,[41,53,60,65]]
 ];
 for(const [t,chord]of bars){note(t,chord[0],.38);const order=[1,2,3,2,1];order.forEach((j,i)=>note(t+.58+i*.64,chord[j],.24+(i===1?.04:0)+(random()-.5)*.025));}
 const melody=[
  [.55,76,.39],[2.4,74,.32],[4.65,71,.36],[5.95,72,.32],
  [8.3,76,.42],[10.25,79,.34],[12.55,77,.39],[13.2,76,.3],[14.65,72,.31],
  [17,76,.4],[18.45,74,.31],[21,71,.37],[22.55,67,.28],
  [25,72,.4],[26.6,76,.32],[29,77,.37],[30.3,74,.3],
  [31.5,76,.41],[33.2,79,.32],[35.5,74,.36],[37.4,71,.28],
  [39.5,72,.36],[41.25,69,.29],[43,67,.26]
 ];
 for(const n of melody)note(...n);
 // Let the gallery settle into silence; a rolled C-major resolution accompanies the logo.
 [48,55,64,67,72].forEach((m,i)=>note(45.5+i*.055,m,i===4?.34:.23));
 out.gain.setValueAtTime(.85,0);out.gain.setValueAtTime(.85,48);out.gain.linearRampToValueAtTime(0,51.8);
 const result=await ctx.startRendering();
 // Peak normalization preserves piano dynamics without pumping or clipping.
 let peak=0;for(let c=0;c<2;c++){const a=result.getChannelData(c);for(let i=0;i<a.length;i++)peak=Math.max(peak,Math.abs(a[i]));}
 const scale=peak?Math.min(2.2,.78/peak):1;for(let c=0;c<2;c++){const a=result.getChannelData(c);for(let i=0;i<a.length;i++)a[i]*=scale;}
 return result;
}
