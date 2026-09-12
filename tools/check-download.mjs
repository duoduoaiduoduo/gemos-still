import assert from 'node:assert/strict';
import {modelFile} from '../browser-inference/download.js';
const config={base:'https://example.invalid/',revision:'test'};
const hang=()=>new Promise(()=>{});
Object.defineProperty(globalThis,'navigator',{value:{storage:{getDirectory:hang}},configurable:true});
let events=[];globalThis.fetch=async()=>new Response(new Uint8Array([1,2,3,4]));
assert.deepEqual([...await modelFile(config,'x',4,{offset:2,total:6,cacheMs:5,report:e=>events.push(e)})],[1,2,3,4]);
assert.equal(events.at(-1).loaded,6);assert.equal(events.at(-1).total,6);
assert(events.some(e=>e.text.includes('缓存不可用')));
globalThis.fetch=hang;
await assert.rejects(modelFile(config,'x',4,{cacheMs:5,networkMs:5}),/连接模型源超时/);
globalThis.fetch=async()=>new Response(new ReadableStream({start(){}}));
await assert.rejects(modelFile(config,'x',4,{cacheMs:5,stallMs:5}),/长时间无数据/);
globalThis.fetch=async()=>new Response(new Uint8Array([1]));
await assert.rejects(modelFile(config,'x',4,{cacheMs:5}),/不完整/);
console.log('PASS: cache timeout fallback, byte progress, connection timeout, stalled stream, truncated model');

let attempts=0;globalThis.fetch=async()=>{attempts++;throw new TypeError('Failed to fetch');};await assert.rejects(modelFile(config,'x',4,{cacheMs:5}),/Hugging Face/);assert.equal(attempts,2);console.log('PASS: failed fetch retry and actionable error');
