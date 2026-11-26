/**
 * Network permission comes only from the native-validated workspace grant, plus one exact
 * credential-free read: the server time ([timeUrl], `GET <worker>/api/time`) the Status view
 * clock calibrates against.
 */
export function createReadAccess(fetch, emit=()=>{}, {timeUrl}={}) {
 let config, active=true;
 const time=timeUrl ? new URL(timeUrl) : undefined;
 if(time && (time.protocol!=='https:' || time.username || time.password || time.search || time.hash || time.port || time.pathname!=='/api/time')) throw Error('network_denied');
 const requests=new Set(), waiters=new Set();
 function configure(value) {
  const gateway=new URL(value.gatewayBaseUrl);
  if(gateway.protocol!=='https:' || gateway.username || gateway.password || gateway.search || gateway.hash || gateway.port) throw Error('network_denied');
  if(!/^[a-zA-Z0-9_-]{1,128}$/.test(value.workspaceId)) throw Error('network_denied');
  const streamPath=gateway.pathname.replace(/\/$/, '')+'/ds/';
  config={origin:gateway.origin,prefix:streamPath+'lody/',roostPrefix:streamPath+'lody-rh/',workspace:value.workspaceId};
 }
 function allowed(request) {
  const url=new URL(request.url);
  if(!config || !['GET','HEAD','POST','PUT'].includes(request.method) || url.origin!==config.origin || url.username || url.password || url.hash) return false;
  if(url.pathname.startsWith(config.roostPrefix)) {
   if(!['GET','HEAD'].includes(request.method) || !request.headers.get('Authorization')?.startsWith('Bearer ')) return false;
   let stream;try{stream=decodeURIComponent(url.pathname.slice(config.roostPrefix.length))}catch{return false}
   const [workspace,kind,machine,session,author,generation,...rest]=stream.split(':');
   const id=/^[A-Za-z0-9_-]{1,256}$/;
   return workspace===config.workspace && kind==='rh' && !rest.length &&
    id.test(machine??'') && id.test(session??'') && /^[A-Za-z0-9_-]{43}$/.test(author??'') &&
    (generation==='root' || /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(generation??''));
  }
  if(!url.pathname.startsWith(config.prefix)) return false;
  const parts=url.pathname.slice(config.prefix.length).split('/');
  if(parts.length>2 || (parts.length===2 && parts[1]!=='bootstrap')) return false;
  let stream;try{stream=decodeURIComponent(parts[0])}catch{return false}
  const read=['GET','HEAD'].includes(request.method);
  if(parts.length===2 && !read)return false;
  if(stream===config.workspace+':meta')return read || request.method==='POST';
  if(new RegExp('^'+config.workspace+':mf:[a-zA-Z0-9_-]{1,128}$').test(stream))return read || request.method==='POST';
  // Schedules: the Flock registry and one schedule's Loro doc are each created, read and appended.
  // Agent Roles: the workspace Flock is read, created when missing and appended by Role edits.
  if(stream===config.workspace+':wf:workspace')return read || ['PUT','POST'].includes(request.method);
  // Prompt shortcuts: workspace-scoped index Flock and body docs.
  if(stream===config.workspace+':shortcut-index')return read || ['PUT','POST'].includes(request.method);
  if(new RegExp('^'+config.workspace+':shortcut:[^:/]{1,600}$').test(stream))return read || ['PUT','POST'].includes(request.method);
  if(stream===config.workspace+':sr')return true;
  if(new RegExp('^'+config.workspace+':schedule:[a-zA-Z0-9_-]{1,128}$').test(stream))return true;
  if(new RegExp('^'+config.workspace+':s:[a-zA-Z0-9_-]{1,128}$').test(stream))return true;
  if(new RegExp('^'+config.workspace+':rpc:req:[a-zA-Z0-9_-]{1,128}$').test(stream))return request.method==='POST';
  return new RegExp('^'+config.workspace+':rpc:res:[a-zA-Z0-9_-]{1,128}:[a-zA-Z0-9_-]{1,128}$').test(stream) && (read || request.method==='PUT');
 }
 function setActive(value) {
  active=value;
  // Distinguish host suspension from a transport AbortError after SDK wrapping.
  if(!active) for(const controller of requests) controller.abort(new DOMException('runtime_suspended','AbortError'));
  else {for(const wake of [...waiters]) wake();}
 }
 async function guardedFetch(input, init) {
  const original=new Request(input,init);
  if(time && original.method==='GET' && original.url===time.href) return timeRead(original);
  if(!allowed(original)) throw Error('network_denied');
  if(!active && !['GET','HEAD'].includes(original.method))throw Error('inactive_write');
  if(!active) await new Promise((resolve,reject)=>{
   const cleanup=()=>{waiters.delete(wake);original.signal.removeEventListener('abort',abort)};
   const wake=()=>{cleanup();resolve()};const abort=()=>{cleanup();reject(Error('cancelled'))};
   if(original.signal.aborted)return abort();
   if(waiters.size>=32)return reject(Error('network_busy'));
   waiters.add(wake);original.signal.addEventListener('abort',abort,{once:true});
  });
  original.signal.throwIfAborted();
  if(requests.size>=32)throw Error('network_busy');
  const controller=new AbortController();requests.add(controller);
  // The caller's signal is forwarded by hand and unhooked when the request ends, instead of
  // AbortSignal.any: a fallback any (WebView before 116) keeps its listener on the input for that
  // signal's whole life, so a long-lived caller signal would collect one per request.
  const forward=()=>controller.abort(original.signal.reason);
  original.signal.addEventListener('abort',forward,{once:true});
  const release=()=>{requests.delete(controller);original.signal.removeEventListener('abort',forward);};
  const request=new Request(original,{redirect:'error',credentials:'omit',signal:controller.signal});
  try {
   const response=await fetch(request);
   if(response.status===401 || response.status===403)emit({type:'networkStatus',status:'authorization_required'});
   // WebView may expose an empty stream for a null-body HTTP status.
   // Re-wrapping it with a ReadableStream makes Response throw a TypeError.
   if([204,205,304].includes(response.status)) {
    release();
    await response.body?.cancel();
    return new Response(null,{status:response.status,statusText:response.statusText,headers:response.headers});
   }
   if(!response.body){release();return response;}
   const reader=response.body.getReader();let bytes=0;
   const body=new ReadableStream({
    async pull(output){try {
     const part=await reader.read();
     if(part.done){release();output.close();return;}
     bytes+=part.value.byteLength;if(bytes>16*1024*1024)throw Error('response_too_large');
     output.enqueue(part.value);
    }catch(error){release();controller.abort();void reader.cancel().catch(()=>{});output.error(error);}},
    cancel(){release();controller.abort();return reader.cancel();}
   });
   return new Response(body,{status:response.status,statusText:response.statusText,headers:response.headers});
  } catch(error) {
   release();
   // Closing a session or suspending the app cancels its request, not the network.
   if(!request.signal.aborted)emit({type:'networkStatus',status:'unavailable'});
   throw error;
  }
 }
 // The server time read: no credentials, cookies or redirects, a bounded JSON body, never while suspended.
 async function timeRead(original) {
  if(!active)throw Error('inactive_read');
  const response=await fetch(new Request(original,{redirect:'error',credentials:'omit'}));
  const text=await response.text();
  if(text.length>4096)throw Error('response_too_large');
  return new Response(text,{status:response.status,statusText:response.statusText,headers:response.headers});
 }
 return {configure,setActive,fetch:guardedFetch};
}
