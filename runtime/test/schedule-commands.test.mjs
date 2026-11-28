import test from 'node:test';
import assert from 'node:assert/strict';
import {scheduleCommands} from '../src/schedule-commands.mjs';
const draft={title:'Daily review',prompt:'Review yesterday',machineId:'m',agentConfigId:'a',destination:'new_session',rule:{kind:'daily',hour:9,minute:0,timeZone:'Europe/Berlin'}};
function fixture(reply=async()=>({scheduleId:'s1'}), liveStatus=async a=>({sessionId:a.sessionId,state:'idle',source:'rpc'})) {
 const calls=[];
 const runtime={schedules:async a=>{calls.push(['schedules',a]);return {items:[],deviceTimeZone:'UTC',machineTimeZones:{}}},scheduleDetail:async a=>{calls.push(['detail',a]);return {item:{},prompt:''}},scheduleCommand:async a=>{calls.push(['command',a]);return reply(a)},sessionLiveStatus:async a=>{calls.push(['liveStatus',a]);return liveStatus(a)}};
 return {calls,commands:scheduleCommands(runtime,()=>({workspaceId:'w',userId:'u'}))};
}
test('schedule reads and writes carry the native workspace, never a caller-supplied one',async()=>{
 const {calls,commands}=fixture();
 await commands.schedules();await commands.scheduleDetail('s1');
 assert.deepEqual((await commands.scheduleCommand({action:'create',scheduleId:'s1',requestId:'r1',draft,workspaceId:'other'})),{scheduleId:'s1'});
 assert.deepEqual(calls.map(([,a])=>a.workspaceId),['w','w','w']);
 assert.deepEqual(calls[2][1],{workspaceId:'w',action:'create',scheduleId:'s1',requestId:'r1',draft});
});
test('only create and edit carry a draft, and malformed commands never reach the runtime',async()=>{
 const {calls,commands}=fixture();
 for(const input of [{action:'pause',scheduleId:'s1',requestId:'r1',draft},{action:'create',scheduleId:'s1',requestId:'r1'},{action:'archive',scheduleId:'s1',requestId:'r1'},{action:'run',scheduleId:'bad id',requestId:'r1'},{action:'edit',scheduleId:'s1',requestId:'r1',draft:{...draft,destination:'existing_session'}},{action:'edit',scheduleId:'s1',requestId:'r1',draft:{...draft,rule:{kind:'cron'}}}])
  assert.deepEqual(await commands.scheduleCommand(input),{error:'invalid_request'});
 assert.equal(calls.length,0);
 await commands.scheduleCommand({action:'resume',scheduleId:'s1',requestId:'r2'});
 assert.equal('draft' in calls[0][1],false);
});
test('schedule errors arrive in-band; an unrecognised write failure is an unknown outcome',async()=>{
 let error='schedule_not_owner';
 const {calls,commands}=fixture(async()=>{throw Error(error)});
 assert.deepEqual(await commands.scheduleCommand({action:'delete',scheduleId:'s1',requestId:'r1'}),{error:'schedule_not_owner'});
 error='runtime_timeout';
 assert.deepEqual(await commands.scheduleCommand({action:'delete',scheduleId:'s1',requestId:'r1'}),{error:'schedule_write_unknown'});
 assert.equal(calls.length,2);
});
test('without a verified identity nothing is read or written',async()=>{
 const calls=[];const commands=scheduleCommands({schedules:async()=>calls.push(1),scheduleCommand:async()=>calls.push(1),sessionLiveStatus:async()=>calls.push(1)},()=>undefined);
 assert.deepEqual(await commands.schedules(),{error:'not_connected'});
 assert.deepEqual(await commands.scheduleCommand({action:'run',scheduleId:'s1',requestId:'r1'}),{error:'not_connected'});
 assert.deepEqual(await commands.sessionLiveStatus('sess-1','m1'),{error:'not_connected'});
 assert.equal(calls.length,0);
});
test('sessionLiveStatus scopes workspace, validates ids and maps failures in-band',async()=>{
 const {calls,commands}=fixture(undefined,async a=>({sessionId:a.sessionId,state:'busy',detail:'running',source:'presence'}));
 assert.deepEqual(await commands.sessionLiveStatus('sess-1','m1'),{sessionId:'sess-1',state:'busy',detail:'running',source:'presence'});
 assert.deepEqual(await commands.sessionLiveStatus('sess-2',null),{sessionId:'sess-2',state:'busy',detail:'running',source:'presence'});
 assert.deepEqual(calls,[['liveStatus',{workspaceId:'w',sessionId:'sess-1',machineId:'m1'}],['liveStatus',{workspaceId:'w',sessionId:'sess-2'}]]);
 assert.deepEqual(await commands.sessionLiveStatus('','m1'),{error:'invalid_request'});
 assert.deepEqual(await commands.sessionLiveStatus('sess-1',''),{error:'invalid_request'});
 const failing=fixture(undefined,async()=>{throw Error('runtime_timeout');});
 assert.deepEqual(await failing.commands.sessionLiveStatus('sess-1','m1'),{error:'session_live_status_failed'});
});
