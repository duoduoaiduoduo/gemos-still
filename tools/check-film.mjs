import assert from 'node:assert/strict';
import {FILM,exportSettings} from '../film-timeline.js';
assert.equal(FILM.duration,52);assert(FILM.montageEnd<FILM.duration);assert.equal(FILM.outroOffset,12);
for(const [id,width,height,fps] of [['1080p60',1920,1080,60],['4k30',3840,2160,30],['4k60',3840,2160,60]]){assert.deepEqual([exportSettings(id,false).width,exportSettings(id,false).height,exportSettings(id,false).fps],[width,height,fps]);assert.equal(exportSettings(id,true).width,height);assert.equal(exportSettings(id,true).height,width);assert.equal(FILM.duration*fps,id==='4k30'?1560:3120);}
assert.throws(()=>exportSettings('invalid',false));console.log('PASS: native resolutions, portrait dimensions, frame counts and timeline');
