// Keep mobile actions and progress in one place, without obscuring the 3D viewport.
const panel=document.getElementById('memory-panel'),progress=document.getElementById('generation-progress'),importButton=document.getElementById('import-memory'),creation=document.getElementById('creation');
const actions=document.createElement('div');actions.className='mobile-panel-actions';const example=document.getElementById('view-example');
const media=matchMedia('(max-width:760px)');
const head=document.createElement('div');head.className='mobile-panel-head';head.innerHTML='<strong>记忆与调整</strong><button type="button" aria-label="关闭调整面板">完成</button>';panel.prepend(head);head.after(actions);head.querySelector('button').onclick=()=>document.getElementById('toggle-sidebar').click();
function layout(){
 const open=document.body.classList.contains('sidebar-open');
 if(media.matches){if(importButton.parentElement!==actions)actions.append(importButton,example);if(open){if(progress.parentElement!==panel)actions.after(progress);}else if(progress.parentElement!==document.body)document.body.append(progress);}
 else{if(example.parentElement!==panel)document.getElementById('library-section').before(example);if(importButton.parentElement!==creation)creation.append(importButton);if(progress.parentElement!==document.body)document.body.append(progress);}
 document.body.classList.toggle('has-generation-progress',!progress.hidden);
 const h=media.matches&&!open&&!progress.hidden?progress.getBoundingClientRect().height+20:0;
 document.documentElement.style.setProperty('--generation-space',`${h}px`);
}
new MutationObserver(layout).observe(progress,{attributes:true,attributeFilter:['hidden']});
new MutationObserver(layout).observe(document.body,{attributes:true,attributeFilter:['class']});
new ResizeObserver(()=>{const open=document.body.classList.contains('sidebar-open');const h=media.matches&&!open&&!progress.hidden?progress.getBoundingClientRect().height+20:0;document.documentElement.style.setProperty('--generation-space',`${h}px`);}).observe(progress);
media.addEventListener('change',layout);document.addEventListener('keydown',e=>{if(e.key==='Escape'&&document.body.classList.contains('sidebar-open'))head.querySelector('button').click();});layout();
