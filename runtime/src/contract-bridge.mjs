// Translate Android's native command surface to the shared runtime envelope contract.
export function contractBridge(runtime, emit) {
 const pending=new Map(), grants=new Map(), tokens=new Map(), localGrants=new Set(); let serial=0, workspace='';
 const tokenOperations=new Set(['previewToken','historyToken']);
 const receive=envelope=>runtime.receive(JSON.stringify(envelope));
 function call(method,payload={}) {
  const id=`android-${++serial}`;
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{pending.delete(id);reject(Error('runtime_timeout'));},method==='agentProviderOperation' ? 355000 : method==='agentProviderAuth' ? 65000 : method==='agentProviderProbe' ? 1225000 : method==='agentProviders' ? 140000 : method==='editSession' || method==='forkSession' || method==='promptShortcuts' || method==='decideProposal' ? 130000 : method==='scheduleCommand' ? 75000 : method==='sessionPreview' ? 335000 : method==='iosSimulatorControl' || method==='androidEmulatorControl' ? 105000 : 45000);
   pending.set(id,{resolve,reject,timer,method});
   receive({v:1,gen:1,kind:'request',id,method,payload});
  });
 }
 function message(message) {
  if(message.type!=='envelope')return false;
  const e=typeof message.envelope==='string'?JSON.parse(message.envelope):message.envelope;
  if(e.gen!==1)return true;
  if(e.kind==='result'||e.kind==='error') {
   const p=pending.get(e.id);if(!p||p.method!==e.method)return true;
   clearTimeout(p.timer);pending.delete(e.id);
   if(e.kind==='error')p.reject(Error(e.error.code));else p.resolve(e.payload);
  } else if(e.kind==='event')emit({type:e.method,...e.payload,...(e.method==='machinePresence'?{presence:JSON.stringify(e.payload)}:{})});
  else if(e.kind==='request') {
   if(e.method==='grant'){grants.set(e.id,e);emit({type:'grant'});}
   else if(e.method==='shareRequest' && tokenOperations.has(e.payload?.operation) && workspace && e.payload.workspaceId===workspace && e.payload.args?.intent?.workspaceId===workspace && tokens.size<4) {
    // Native returns only the short-lived proof for the verified account and workspace.
    tokens.set(e.id,{e,timer:setTimeout(()=>machineToken(e.id,null),60000)});
    emit({type:'machineToken',requestId:e.id,operation:e.payload.operation,intent:JSON.stringify(e.payload.args.intent)});
   }
   else receive({...e,kind:'error',payload:undefined,error:{code:'unsupported_method',retryable:false}});
  }
  return true;
 }
 // The native reply to one machine token request: {value} (the token, or {status} for an HTTP refusal), null on any failure.
 function machineToken(requestId,reply){
  const item=tokens.get(requestId);if(!item)return false;tokens.delete(requestId);clearTimeout(item.timer);
  const e=item.e, value=reply && typeof reply==='object' ? reply.value : undefined;
  const valid=(typeof value==='string' && value.length>0 && value.length<=8192) || (e.payload.operation==='previewToken' && value && typeof value==='object' && Number.isInteger(value.status));
  if(valid)receive({...e,kind:'result',payload:{value:typeof value==='string'?value:{status:value.status}}});
  else receive({...e,kind:'error',payload:undefined,error:{code:'machine_token_unavailable',retryable:false}});
  return true;
 }
 globalThis.androidRuntimeMessage=message;
 const ready=call('hello',{protocol:1,gen:1});
 const invoke=async(method,payload)=>{await ready;return call(method,payload);};
 return {
  ping:async()=>{await invoke('ping');return true;},
  setActive:async active=>{await invoke('setActive',{active});return true;},
  start:async(workspaceId,userId='',active=false,workspaceReadOnly=false)=>{workspace=workspaceId;await invoke('start',{workspaceId,userId,active,...(workspaceReadOnly?{workspaceReadOnly:true}:{})});return true;},
  probeSchema:()=>workspace?invoke('probeSchema',{workspaceId:workspace}):ready.then(()=>({creationOptionsTrial:{},machines:{}})),
  session:(id,historyStart)=>invoke('openSession',{sessionId:id,...(historyStart===undefined?{}:{historyStart})}),
  loadOlderSession:args=>invoke('loadOlderSession',args),
  forgetPendingInput:args=>invoke('forgetPendingInput',args),
  retryPendingInput:args=>invoke('retryPendingInput',args),
  ensureSession:args=>invoke('ensureSession',args),
  creationOptions:args=>invoke('creationOptions',args),
  // The shared contract requires the entitlement on writes that check quota. Native reads it per
  // write; null (an unavailable read) is the runtime's fail-open policy, as on iOS.
  createSession:args=>invoke('createSession',{...args,billingEntitlement:args.billingEntitlement??null}),
  editSession:args=>invoke('editSession',{...args,billingEntitlement:args.billingEntitlement??null}),
  forkSession:args=>invoke('forkSession',args),
  // The free-plan turn quota alone (no write), asked before native uploads a turn's attachments.
  checkTurnQuota:args=>invoke('checkTurnQuota',{sessionId:args.sessionId,billingEntitlement:args.billingEntitlement??null}),
  setTabClosed:args=>invoke('setTabClosed',args),
  archiveSession:args=>invoke('archiveSession',args),
  // Status view: one planned command (Later, Settle, Move to Focus, pin, move, sections) and the Later presets.
  statusViewCommand:args=>invoke('statusViewCommand',args),
  snoozePresets:args=>invoke('snoozePresets',args),
  markSessionRead:args=>invoke('markSessionRead',args),
  // A Tab is a session: the contract requires the entitlement (null fails open), as on createSession.
  createTab:args=>invoke('createTab',{...args,billingEntitlement:args.billingEntitlement??null}),
  // Agent Roles readable by this user, listed for the new-chat Role row.
  agentProviders:args=>invoke('agentProviders',args),
  agentProviderDetail:args=>invoke('agentProviderDetail',args),
  agentProviderProbe:args=>invoke('agentProviderProbe',args),
  agentProviderAuth:args=>invoke('agentProviderAuth',args),
  agentProviderOperation:args=>invoke('agentProviderOperation',args),
  agentRoles:args=>invoke('agentRoles',args),
  // The composer's @ panel: one mention category for a session.
  mentionCatalog:args=>invoke('mentionCatalog',args),
  // Add a local folder as a project: list computers, browse a computer's folders, register one.
  localProjects:args=>invoke('localProjects',args),
  // Prompt shortcuts: the readable index and one body (for writing a chosen shortcut into the composer).
  promptShortcuts:args=>invoke('promptShortcuts',args),
  // Multi-repository projects for the new-chat picker.
  projectGroups:args=>invoke('projectGroups',args),
  // A changed file's text before and after one turn, read from the session's computer.
  turnDiff:args=>invoke('turnDiff',args),
  // The working tree's changed files, and one file's current text against its base.
  workspaceChanges:args=>invoke('workspaceChanges',args),
  fileDiff:args=>invoke('fileDiff',args),
  // The browse-files sheet: one folder of the session's local project, and one file's contents.
  listDir:args=>invoke('listDir',args),
  readFile:args=>invoke('readFile',args),
  conversationShareMessages:args=>invoke('conversationShareMessages',args),
  // Schedules: the list with time zones, one schedule's detail, owner writes (the runtime's 70 s budget), and bound-chat live status.
  schedules:args=>invoke('schedules',args),
  scheduleDetail:args=>invoke('scheduleDetail',args),
  scheduleCommand:args=>invoke('scheduleCommand',args),
  sessionLiveStatus:args=>invoke('sessionLiveStatus',args),
  // Managed web preview (create / revoke) and device previews (list / start / status / stop); results carry {error} in band.
  sessionPreview:args=>invoke('sessionPreview',args),
  iosSimulatorControl:args=>invoke('iosSimulatorControl',args),
  androidEmulatorControl:args=>invoke('androidEmulatorControl',args),
  // An Agent's Role or schedule proposal: save / create or dismiss it and record the outcome.
  decideProposal:args=>invoke('decideProposal',args),
  inspectProposal:args=>invoke('inspectProposal',args),
  machineToken,
  grant(value){
   for(const e of grants.values())receive(value?{...e,kind:'result',payload:value}:{...e,kind:'error',payload:undefined,error:{code:'grant_unavailable',retryable:true}});grants.clear();
   for(const waiter of localGrants)waiter(value);localGrants.clear();
   return true;
  },
  // A workspace Streams grant for an Android adapter read (the turn's MCP defaults), asked of the
  // native host like the runtime's own grant requests and answered by the same `grant` reply.
  requestGrant(){
   return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{localGrants.delete(waiter);reject(Error('grant_unavailable'));},30000);
    const waiter=value=>{clearTimeout(timer);if(value)resolve(value);else reject(Error('grant_unavailable'));};
    localGrants.add(waiter);emit({type:'grant'});
   });
  },
 };
}
