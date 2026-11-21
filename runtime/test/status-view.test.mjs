import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {buildStatusViewLayout,planStatusViewCommand} from '../vendor/cloud-runtime/src/protocol/lody-shared/status-view/index.ts';
import {StatusViewLayout,StatusViewCommand} from '../vendor/cloud-runtime/src/contract/status-view.ts';

// The shared Lody golden vectors, run against the layout and planner this Android runtime bundles.
const vectors=async name=>JSON.parse(await readFile(new URL(`../vendor/cloud-runtime/src/protocol/lody-shared/status-view/vectors/${name}.json`,import.meta.url),'utf8'));
const cycling=bytes=>{let next=0;return ()=>bytes[next++%bytes.length];};

test('the bundled layout reproduces every shared golden layout vector',async()=>{
 const {cases}=await vectors('layout');
 assert.ok(cases.length>0);
 for(const entry of cases){
  const layout=buildStatusViewLayout(entry.input);
  assert.deepEqual(layout,entry.expected,entry.name);
  // Native decodes exactly the contract shape the shared layout produces.
  assert.deepEqual(StatusViewLayout.parse(layout),layout,entry.name);
 }
});

test('the bundled planner reproduces every shared golden command vector',async()=>{
 const {cases}=await vectors('commands');
 assert.ok(cases.length>0);
 for(const entry of cases){
  const writes=planStatusViewCommand(entry.state,entry.command,{nowMs:entry.env.nowMs,randomByte:cycling(entry.env.randomBytes)});
  assert.deepEqual(writes,entry.expected,entry.name);
  // The commands native may send are exactly the contract's; runtime-only ones never cross.
  const accepted=StatusViewCommand.safeParse(entry.command).success;
  if(['touchOnSend','newSessionOrder'].includes(entry.command.type))assert.equal(accepted,false,entry.name);
  else if(!accepted)assert.deepEqual(writes,[],entry.name);
 }
});

test('the Kotlin golden catalog is the snapshot runtime fixture, byte for byte in content',async()=>{
 const fixtures=JSON.parse(await readFile(new URL('../contract/fixtures.json',import.meta.url),'utf8'));
 const vendored=JSON.parse(await readFile(new URL('../vendor/cloud-runtime/contract/fixtures/catalog-status-view.json',import.meta.url),'utf8'));
 assert.deepEqual(JSON.parse(fixtures.catalogStatusView.catalog),vendored);
});
