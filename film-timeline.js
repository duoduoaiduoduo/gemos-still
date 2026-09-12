export const FILM={duration:52,montageStart:31.5,montageEnd:44,outroOffset:12};
export const EXPORT_PRESETS={
 '1080p30':{label:'1080P · 30 帧',width:1920,height:1080,fps:30,bitrate:12000000},
 '1080p60':{label:'1080P · 60 帧',width:1920,height:1080,fps:60,bitrate:20000000},
 '4k30':{label:'4K · 30 帧',width:3840,height:2160,fps:30,bitrate:35000000},
 '4k60':{label:'4K · 60 帧',width:3840,height:2160,fps:60,bitrate:55000000}
};
export function exportSettings(id,portrait){const p=EXPORT_PRESETS[id];if(!p)throw Error('请选择导出规格');return {...p,width:portrait?p.height:p.width,height:portrait?p.width:p.height};}
