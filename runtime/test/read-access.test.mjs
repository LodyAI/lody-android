import test from 'node:test';
import assert from 'node:assert/strict';
import {createReadAccess} from '../src/read-access.mjs';
const config={gatewayBaseUrl:'https://streams.example.org',workspaceId:'workspace'};
test('closing a session or backgrounding does not report a network outage',async()=>{
 for(const cancel of ['session','background']) {
  const events=[];
  const access=createReadAccess(request=>new Promise((resolve,reject)=>{
   request.signal.addEventListener('abort',()=>reject(request.signal.reason),{once:true});
  }),event=>events.push(event));
  access.configure(config);
  const controller=new AbortController();
  const pending=access.fetch('https://streams.example.org/ds/lody/workspace%3As%3Asession-1',{signal:controller.signal});
  if(cancel==='session')controller.abort(new Error('session_closed'));
  else access.setActive(false);
  await assert.rejects(pending);
  assert.deepEqual(events,[],cancel);
 }
});
test('transport failures still report offline, even when named AbortError without cancellation',async()=>{
 for(const error of [new TypeError('network failed'),new DOMException('transport aborted','AbortError')]) {
  const events=[];
  const access=createReadAccess(async()=>{throw error},event=>events.push(event));
  access.configure(config);
  await assert.rejects(access.fetch('https://streams.example.org/ds/lody/workspace%3Ameta'),value=>value===error);
  assert.deepEqual(events,[{type:'networkStatus',status:'unavailable'}]);
 }
});
test('empty long-poll responses preserve cursor headers without constructing a forbidden body',async()=>{
 const events=[];
 let canceled=0;
 for (const status of [204,205,304]) {
  // A transport can expose an empty stream even for a null-body status.
  const access=createReadAccess(async()=>({
   status,statusText:'',headers:new Headers({'Stream-Next-Offset':'42'}),
   body:new ReadableStream({cancel(){canceled++;}}),
  }),event=>events.push(event));
  access.configure(config);
  for(let i=0;i<40;i++) {
   const response=await access.fetch('https://streams.example.org/ds/lody/workspace%3Ameta?live=long-poll');
   assert.equal(response.status,status);
   assert.equal(response.headers.get('Stream-Next-Offset'),'42');
   assert.equal(response.body,null);
   assert.equal(await response.text(),'');
  }
 }
 assert.equal(canceled,120);
 assert.deepEqual(events,[]);
});
test('only scoped catalog reads reach fetch, with cookies and redirects disabled',async()=>{
 const calls=[];const access=createReadAccess(async(request)=>{calls.push(request);return new Response('ok')});
 access.configure(config);
 const result=await access.fetch('https://streams.example.org/ds/lody/workspace%3Ameta/bootstrap');
 assert.equal(await result.text(),'ok');assert.equal(calls[0].redirect,'error');assert.equal(calls[0].credentials,'omit');
 for (const url of ['https://evil.example.org/ds/lody/workspace%3Ameta','https://streams.example.org/ds/lody/other%3Ameta','https://streams.example.org/ds/lody/workspace%3Arpc','https://streams.example.org/script.js']) {
  await assert.rejects(access.fetch(url),/network_denied/);
 }
 await assert.rejects(access.fetch('https://streams.example.org/ds/lody/workspace%3Amf%3Amachine',{method:'PUT',body:'write'}),/network_denied/);
 assert.equal(calls.length,1);
});
test('background aborts active response bodies and defers new requests until foreground',async()=>{
 let captured, calls=0;
 const access=createReadAccess(async request=>{captured=request;calls++;return new Response('ok')});access.configure(config);
 const response=await access.fetch('https://streams.example.org/ds/lody/workspace%3Ameta');
 access.setActive(false);assert.equal(captured.signal.aborted,true);
 const next=access.fetch('https://streams.example.org/ds/lody/workspace%3Ameta');
 await Promise.resolve();assert.equal(calls,1);
 access.setActive(true);await (await next).text();assert.equal(calls,2);
 await response.body.cancel();
});

test('workspace session reads are allowed while other workspaces and deletes are denied',async()=>{
 const access=createReadAccess(async()=>new Response('session'));access.configure(config);
 assert.equal(await (await access.fetch('https://streams.example.org/ds/lody/workspace%3As%3Asession-1/bootstrap')).text(),'session');
 await assert.rejects(access.fetch('https://streams.example.org/ds/lody/other%3As%3Asession-1'),/network_denied/);
 await assert.rejects(access.fetch('https://streams.example.org/ds/lody/workspace%3As%3Asession-1',{method:'DELETE',body:'write'}),/network_denied/);
});

test('published Roost history can be read only on the scoped gateway and workspace',async()=>{
 const access=createReadAccess(async()=>new Response('published history'));access.configure(config);
 const streamFor=workspace=>'https://streams.example.org/ds/lody-rh/'+encodeURIComponent(workspace+':rh:machine:session:'+('A'.repeat(43))+':root');
 const headers={Authorization:'Bearer exact-history-permit'};
 assert.equal(await (await access.fetch(streamFor('workspace')+'?direction=backward',{headers})).text(),'published history');
 await assert.rejects(access.fetch(streamFor('workspace')) ,/network_denied/);
 await assert.rejects(access.fetch(streamFor('other'),{headers}),/network_denied/);
 await assert.rejects(access.fetch(streamFor('workspace')+'/bootstrap',{headers}),/network_denied/);
 await assert.rejects(access.fetch(streamFor('workspace'),{method:'POST',headers,body:'write'}),/network_denied/);
});

test('creation permissions stay inside workspace and separate stream purposes', async()=>{
 const access=createReadAccess(async()=>new Response('ok'));access.configure(config);
 for(const [stream,method] of [['workspace:mf:m','POST'],['workspace:meta','POST'],['workspace:s:s1','PUT'],['workspace:s:s1','POST'],['workspace:rpc:req:m','POST'],['workspace:rpc:res:m:r','PUT'],['workspace:rpc:res:m:r','GET'],['workspace:wf:workspace','GET'],['workspace:wf:workspace','POST'],['workspace:wf:workspace','PUT'],['workspace:shortcut-index','GET'],['workspace:shortcut-index','POST'],['workspace:shortcut-index','PUT'],['workspace:shortcut:doc-1','GET'],['workspace:shortcut:doc-1','POST'],['workspace:shortcut:doc-1','PUT'],['shortcut-index:workspace:u1:private','GET'],['shortcut-index:workspace:u1:workspace','GET'],['shortcut-body:doc-1','GET']])assert.equal(await(await access.fetch('https://streams.example.org/ds/lody/'+encodeURIComponent(stream),{method})).text(),'ok');
 for(const [stream,method] of [['other:s:s1','POST'],['workspace:mf:m','PUT'],['workspace:mf:m','DELETE'],['other:mf:m','POST'],['workspace:meta','PUT'],['workspace:rpc:req:m','GET'],['workspace:rpc:res:m:r','POST'],['workspace:s:s1/bootstrap','POST'],['workspace:wf:workspace','DELETE'],['other:wf:workspace','GET'],['workspace:wf:other','GET'],['other:shortcut-index','GET'],['workspace:shortcut-index','DELETE'],['other:shortcut:doc-1','PUT'],['workspace:shortcut:doc-1','DELETE'],['shortcut-index:other:u1:private','GET'],['shortcut-index:workspace:u1:public','GET'],['shortcut-index:workspace:u1:workspace','POST'],['shortcut-index:workspace:u1:private','PUT'],['shortcut-body:doc-1','POST'],['shortcut-body:doc-1','PUT'],['shortcut-body:doc-1','DELETE']])await assert.rejects(access.fetch('https://streams.example.org/ds/lody/'+encodeURIComponent(stream),{method}),/network_denied/);
 access.setActive(false);await assert.rejects(access.fetch('https://streams.example.org/ds/lody/workspace%3As%3As1',{method:'POST'}),/inactive_write/);
});

test('schedule streams: the registry and one schedule doc are created, read and appended inside the workspace',async()=>{
 const access=createReadAccess(async()=>new Response('ok'));access.configure(config);
 for(const [stream,method] of [['workspace:sr','GET'],['workspace:sr','POST'],['workspace:sr','PUT'],['workspace:sr/bootstrap','GET'],['workspace:schedule:s1','PUT'],['workspace:schedule:s1','POST'],['workspace:schedule:s1','GET']])assert.equal(await(await access.fetch('https://streams.example.org/ds/lody/'+encodeURIComponent(stream).replace('%2F','/'),{method})).text(),'ok');
 for(const [stream,method] of [['workspace:sr','DELETE'],['other:sr','GET'],['other:schedule:s1','PUT'],['workspace:schedule:s1','DELETE'],['workspace:schedule:a:b','PUT'],['workspace:schedule:s1/bootstrap','POST']])await assert.rejects(access.fetch('https://streams.example.org/ds/lody/'+encodeURIComponent(stream).replace('%2F','/'),{method}),/network_denied/);
});

test('the guarded fetch forwards a caller abort by hand and unhooks it when the request ends',async()=>{
 // Without AbortSignal.any (WebView before 116) and without leaving a listener per request on the
 // caller's signal: a fallback any would keep one for that signal's whole life.
 const native=AbortSignal.any;delete AbortSignal.any;
 const added=new Set();
 const add=EventTarget.prototype.addEventListener, remove=EventTarget.prototype.removeEventListener;
 const ours=listener=>typeof listener==='function' && String(listener).includes('original.signal.reason');
 EventTarget.prototype.addEventListener=function(type,listener,options){if(type==='abort'&&ours(listener))added.add(listener);return add.call(this,type,listener,options);};
 EventTarget.prototype.removeEventListener=function(type,listener,options){if(type==='abort')added.delete(listener);return remove.call(this,type,listener,options);};
 try {
  let release;
  const access=createReadAccess(request=>new Promise((resolve,reject)=>{
   request.signal.addEventListener('abort',()=>reject(request.signal.reason),{once:true});
   release=()=>resolve(new Response('done'));
  }));
  access.configure(config);
  const caller=new AbortController();
  const url='https://streams.example.org/ds/lody/workspace%3Ameta';
  // Completed: the forwarding listener is gone.
  const done=access.fetch(url,{signal:caller.signal});
  await Promise.resolve();release();
  assert.equal(await (await done).text(),'done');
  assert.equal(added.size,0);
  // In flight: a caller abort still reaches the request.
  const pending=access.fetch(url,{signal:caller.signal});
  caller.abort(new Error('session_closed'));
  await assert.rejects(pending,/session_closed/);
  assert.equal(added.size,0);
 } finally {
  EventTarget.prototype.addEventListener=add;EventTarget.prototype.removeEventListener=remove;
  AbortSignal.any=native;
 }
});

test('only the exact credential-free server time read leaves the stream gateway',async()=>{
 const seen=[];
 const access=createReadAccess(async request=>{seen.push([request.method,request.url,request.credentials,request.redirect]);return new Response('{"serverTime":5}');},()=>{},{timeUrl:'https://worker.example.org/api/time'});
 access.configure(config);
 assert.equal(await (await access.fetch('https://worker.example.org/api/time')).text(),'{"serverTime":5}');
 assert.deepEqual(seen,[['GET','https://worker.example.org/api/time','omit','error']]);
 for(const [url,init] of [['https://worker.example.org/api/time?x=1'],['https://worker.example.org/api/other'],['https://evil.example.org/api/time'],['https://worker.example.org/api/time',{method:'POST',body:'x'}]])
  await assert.rejects(access.fetch(url,init),/network_denied/,url);
 // Suspended hosts make no reads, this one included.
 access.setActive(false);
 await assert.rejects(access.fetch('https://worker.example.org/api/time'),/inactive_read/);
 assert.throws(()=>createReadAccess(async()=>new Response(''),()=>{},{timeUrl:'http://worker.example.org/api/time'}),/network_denied/);
});
