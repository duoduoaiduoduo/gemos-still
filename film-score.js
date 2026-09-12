import {FILM} from './film-timeline.js';
// Original score rendered offline; sample timing is independent of device speed.
export async function createFilmScore(){
 const offline=new OfflineAudioContext(2,FILM.duration*44100,44100),mix=offline.createGain();mix.gain.value=.55;
 const limiter=offline.createDynamicsCompressor();limiter.threshold.value=-12;limiter.ratio.value=4;mix.connect(limiter);const output=offline.createGain();output.gain.value=3.2;limiter.connect(output);output.connect(offline.destination);
 const delay=offline.createDelay(1),feedback=offline.createGain(),wet=offline.createGain();delay.delayTime.value=.375;feedback.gain.value=.25;wet.gain.value=.16;delay.connect(feedback);feedback.connect(delay);delay.connect(wet);wet.connect(mix);
 const hz=n=>440*2**((n-69)/12);
 function note(t,n,d,level,type='sine',pan=0){const o=offline.createOscillator(),g=offline.createGain(),p=offline.createStereoPanner();o.type=type;o.frequency.value=hz(n);p.pan.value=pan;g.gain.setValueAtTime(0,t);g.gain.linearRampToValueAtTime(level,t+.018);g.gain.exponentialRampToValueAtTime(.0001,t+d);o.connect(g);g.connect(p);p.connect(mix);p.connect(delay);o.start(t);o.stop(t+d+.02);}
 const chords=[[50,57,62,65],[46,53,58,62],[53,60,65,69],[48,55,60,64]];
 for(let bar=0;bar<11;bar++){const t=bar*4,chord=chords[bar%4];for(let j=0;j<4;j++)note(t,chord[j],4.5,.048,'sine',(j-1.5)*.35);}
 for(let i=0;i<72;i++){const t=6+i*.5,chord=chords[Math.floor(t/4)%4];note(t,chord[[0,2,1,3,2,1,3,2][i%8]]+12,.8,i%4===0?.11:.055,'sine',Math.sin(i*1.7)*.6);}
 for(let t=8;t<43;t+=.5){if(t%1!==0)continue;const o=offline.createOscillator(),g=offline.createGain();o.frequency.setValueAtTime(110,t);o.frequency.exponentialRampToValueAtTime(42,t+.15);g.gain.setValueAtTime(.23,t);g.gain.exponentialRampToValueAtTime(.0001,t+.28);o.connect(g);g.connect(mix);o.start(t);o.stop(t+.3);}
 // Exact visual landmarks receive their own accent rather than shifting the animation.
 for(const [i,t]of [2.4,5.95,8,13.2,17,21,25,29,31.5,35.5,39.5,43,45.5,46.6].entries()){note(t,74+(i%3)*3,1.6,.12);note(t+.035,81,1.3,.035,'sine',.3);}
 for(const n of [50,57,62,65,69])note(45.5,n,5.8,.055);
 mix.gain.setValueAtTime(.55,49);mix.gain.linearRampToValueAtTime(0,51.8);
 return offline.startRendering();
}
