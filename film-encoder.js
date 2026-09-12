import {Output,Mp4OutputFormat,BufferTarget,CanvasSource,AudioBufferSource,Quality,canEncodeVideo,canEncodeAudio} from './vendor/mediabunny.js';
export async function checkExportSupport(settings,music){
 if(!globalThis.VideoEncoder)throw Error('此浏览器不支持逐帧视频导出，请用最新版 Chrome 或 Safari');
 if(!await canEncodeVideo('avc',{width:settings.width,height:settings.height,quality:new Quality({bitrate:settings.bitrate})}))throw Error('此设备不支持所选分辨率，请选择 1080P 或换用电脑');
 if(music&&!await canEncodeAudio('aac',{sampleRate:44100,numberOfChannels:2}))throw Error('此浏览器无法编码配乐，请关闭配乐或换用支持 AAC 编码的浏览器');
}
export async function encodeFilm({canvas,settings,duration,audio,draw,signal,progress}){
 const target=new BufferTarget(),output=new Output({target,format:new Mp4OutputFormat({fastStart:'in-memory'})});
 const video=new CanvasSource(canvas,{codec:'avc',quality:new Quality({bitrate:settings.bitrate}),keyFrameInterval:2});output.addVideoTrack(video,{frameRate:settings.fps});
 const sound=audio?new AudioBufferSource({codec:'aac',quality:new Quality({bitrate:192000})}):null;if(sound)output.addAudioTrack(sound);
 try{await output.start();if(sound){await sound.add(audio);sound.close();}
 const count=Math.round(duration*settings.fps);
 for(let i=0;i<count;i++){signal?.throwIfAborted();await draw(i/settings.fps);await video.add(i/settings.fps,1/settings.fps);if(i%8===0){progress((i+1)/count);await new Promise(r=>setTimeout(r,0));}}
 signal?.throwIfAborted();video.close();await output.finalize();progress(1);return new Blob([target.buffer],{type:'video/mp4'});
 }catch(e){if(output.state!=='finalized'&&output.state!=='canceled')await output.cancel();throw e;}
}
