import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {mkdtemp, rm, writeFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

// The network document exactly as build.mjs bundles it for NetworkRuntime.html.
async function bundle(entry) {
 const result = await build({entryPoints:[path.join(root,entry)],bundle:true,format:'esm',platform:'browser',target:'chrome100',write:false,
  define:{LODY_MOBILE_STORAGE_SUFFIX:'""'},loader:{'.wasm':'binary'},
  plugins:[{name:'inline-loro-wasm',setup(b){b.onResolve({filter:/^loro-crdt(?:\/base64)?$/},()=>({path:path.join(root,'src/loro-wasm.mjs')}));}}]});
 return result.outputFiles[0].text;
}

// A WebView without AbortSignal.any, AbortSignal.timeout or URL.canParse (Android System WebView
// 100-102): the document installs every fallback before its guarded fetch, the only network path,
// combines signals.
const HARNESS = `
delete AbortSignal.any;
delete AbortSignal.timeout;
delete URL.canParse;
const posted = [], requests = [];
// As prelude.mjs: the bridge consumes runtime envelopes; the rest goes to native.
globalThis.webkit = {messageHandlers:{dataRuntime:{postMessage:message=>{if(!globalThis.androidRuntimeMessage?.(message))posted.push(message);}}}};
globalThis.fetch = async request => { requests.push({url:request.url,aborted:request.signal.aborted}); return new Response('ok'); };
await import(process.argv[1]);
// connect configures read access synchronously; its runtime start then waits on a native grant.
void globalThis.androidDispatch({id:'connect',method:'connect',args:['fixture','https://gateway.example.org','user',false]});
const response = await fetch('https://gateway.example.org/ds/lody/fixture%3Ameta');
const canParse = [URL.canParse('https://gateway.example.org/ds/lody/x'), URL.canParse('not a url'), URL.canParse('/ds/lody/x','https://gateway.example.org'), URL.canParse('/ds/lody/x')];
console.log(JSON.stringify({installed:typeof AbortSignal.any, timeout:typeof AbortSignal.timeout, canParse, body:await response.text(), requests}));
process.exit(0);
`;

function run(file) {
 return new Promise((resolve,reject)=>execFile(process.execPath,['--input-type=module','-e',HARNESS,file],{timeout:60000},(error,stdout,stderr)=>{
  if(error)reject(Object.assign(error,{stderr}));else resolve(JSON.parse(stdout.trim().split('\n').at(-1)));
 }));
}

test('the network document reads through its guarded fetch on a WebView without AbortSignal.any or URL.canParse',async t=>{
 const dir = await mkdtemp(path.join(os.tmpdir(),'lody-network-document-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const file = path.join(dir,'network.mjs');
 await writeFile(file, await bundle('src/network.mjs'));
 const result = await run(file);
 assert.equal(result.installed, 'function');
 assert.equal(result.timeout, 'function');
 // URL.canParse answers as the URL standard: absolute, invalid, relative with and without a base.
 assert.deepEqual(result.canParse, [true, false, true, false]);
 assert.equal(result.body, 'ok');
 assert.deepEqual(result.requests, [{url:'https://gateway.example.org/ds/lody/fixture%3Ameta',aborted:false}]);
});

test('the timeout fallback aborts with a TimeoutError after its delay, and only installs where missing',async t=>{
 t.mock.timers.enable({apis:['setTimeout']});
 const {abortSignalTimeout,installAbortSignalTimeout}=await import('../src/abort-signal-timeout.mjs');
 const signal=abortSignalTimeout(30000);
 t.mock.timers.tick(29999);
 assert.equal(signal.aborted,false);
 t.mock.timers.tick(1);
 assert.equal(signal.aborted,true);
 assert.equal(signal.reason.name,'TimeoutError');
 for(const bad of [-1,1.5,Infinity,NaN,'5'])assert.throws(()=>abortSignalTimeout(bad),TypeError);
 const native=()=>{};
 assert.equal(installAbortSignalTimeout({timeout:native}),false);
 const bare={};
 assert.equal(installAbortSignalTimeout(bare),true);
 assert.equal(bare.timeout,abortSignalTimeout);
});
