// Run with PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node tools/test-mobile-browser.mjs http://127.0.0.1:PORT/
// The test origin must serve the real, pinned model files at /models/gemos-still-lite-v1/.
import assert from 'node:assert/strict';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
const base=process.argv[2];if(!base)throw Error('Pass the locally served app URL');
const browser=await chromium.launch({channel:'chrome',headless:true});
try{for(const mobile of [true,false]){
 const context=await browser.newContext({viewport:mobile?{width:390,height:844}:{width:1440,height:1000},...(mobile?{userAgent:'Mozilla/5.0 (Linux; Android 16; V2509A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36',isMobile:true,hasTouch:true}:{})});
 const page=await context.newPage(),requests=[],errors=[];page.on('request',r=>requests.push(r.url()));page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base);await page.locator('#choose-photo').click();await page.waitForFunction(()=>!document.querySelector('#local-select').disabled);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 if(mobile){
 assert.match(await page.locator('#gpu-status').innerText(),/首次/);
 await page.locator('#local-example').click();await page.waitForFunction(()=>document.querySelector('#connection').textContent.includes('已收藏'),null,{timeout:300000});
 assert(requests.some(u=>u.includes('mobile-worker')));assert(!requests.some(u=>u.includes('ort.webgpu.min.js')));
 const before=requests.filter(u=>u.includes('/models/')).length;assert(before>=2);
 await page.reload();await page.locator('#choose-photo').click();await page.waitForFunction(()=>document.querySelector('#gpu-status').textContent.includes('轻量模型已就绪'));
 await page.locator('#local-example').click();await page.waitForFunction(()=>document.querySelector('#connection').textContent.includes('已收藏'),null,{timeout:300000});
 assert.equal(requests.filter(u=>u.includes('/models/')).length,before,'Cached inference must not redownload weights');
 }else{assert.match(await page.locator('#gpu-status').innerText(),/GPU/);assert(!requests.some(u=>u.includes('mobile-worker')||u.includes('ort-cpu')||u.includes('/models/')));}
 assert.deepEqual(errors,[]);console.log(`PASS ${mobile?'mobile: automatic download, inference, cache reuse':'desktop: original GPU route'}`);await context.close();
}}finally{await browser.close();}
