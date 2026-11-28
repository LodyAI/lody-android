import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { LoroDoc } from 'loro-crdt/base64';
import { IDBFactory, IDBKeyRange } from 'fake-indexeddb';

const bundle = await build({
  stdin: { contents: "export * from './session.ts'; export { openPayload, sealPayload } from './machine-rpc.ts';", resolveDir: new URL('../vendor/cloud-runtime/src/runtime/', import.meta.url).pathname, loader: 'ts' },
  bundle: true, format: 'esm', platform: 'browser', write: false,
  plugins: [{ name: 'stream-fixture', setup(build) {
    build.onResolve({ filter: /^loro-crdt\/base64$/ }, () => ({ path: pathToFileURL(createRequire(import.meta.url).resolve('loro-crdt/base64')).href, external: true }));
    build.onResolve({ filter: /^@loro-dev\/streams-client$/ }, () => ({ path: 'fake', namespace: 'fixture' }));
    build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: 'export class StreamsClient { constructor(args) { return new globalThis.__androidStream(args); } }' }));
  } }],
});
const runtime = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const ok = (result = {}) => ({ ok: true, result });
const args = { id: '22222222-2222-4222-8222-222222222222', sessionId: 's', machineId: 'm', userId: 'u', text: 'hello', cliType: 'builtin', agentType: 'codex', modelId: 'model' };

async function fixture({ failAt, target, owner, subscribe, readControl, observe } = {}) {
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const locks = new Map();
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: { request(name, { signal }, work) {
    const previous = locks.get(name) ?? Promise.resolve();
    const current = previous.catch(() => {}).then(() => { signal.throwIfAborted(); return work(); });
    locks.set(name, current); return current;
  } } } });
  globalThis.indexedDB = new IDBFactory();
  globalThis.IDBKeyRange = IDBKeyRange;
  const doc = new LoroDoc(), calls = [], replies = new Map();
  globalThis.__androidStream = class {
    constructor({ url }) { this.url = decodeURIComponent(url); }
    async bootstrap() { return ok({ snapshotOffset: '1', nextOffset: '1', upToDate: true, snapshot: { body: doc.export({ mode: 'snapshot' }) }, updates: [] }); }
    async create() { calls.push('reply-stream'); return ok(); }
    async append({ part }) {
      if (this.url.includes(':rpc:req:')) {
        calls.push('rpc');
        const request = JSON.parse(part.body);
        if (failAt === 'rpc') throw new Error('lost_ack');
        if (request.method !== 'session/history-unsubscribe') replies.set(request.replyTo, request);
      } else {
        calls.push('history');
        if (failAt === 'history-unsaved') throw new Error('network_error');
        doc.import(part.body.subarray(4));
        if (failAt === 'history') throw new Error('lost_ack');
      }
      return ok();
    }
    async *live({ signal, mode }) {
      assert.equal(mode, 'long-poll');
      const request = replies.get(this.url.split('/ds/lody/')[1]);
      assert.equal(request.method, 'session/history-subscribe');
      for await (const result of subscribe(request, signal)) {
        yield { type: 'data', mode, payload: {
          body: new TextEncoder().encode(JSON.stringify({ id: request.id, ...result })),
        } };
      }
    }
    async readOnce({ signal, live } = {}) {
      if (this.url.includes(':rpc:res:')) {
        const request = replies.get(this.url.split('/ds/lody/')[1]);
        const result = owner ? await owner(request, signal) : { result: { accepted: true } };
        return ok({ nextOffset: '1', payload: { body: new TextEncoder().encode(JSON.stringify({ id: request.id, ...result })) } });
      }
      if (this.url.endsWith(':s:s')) {
        const read = readControl?.();
        if (read) return read;
        if (!live) return ok({ nextOffset: '1', upToDate: true, closed: false });
      }
      return new Promise((_resolve, reject) => signal?.addEventListener('abort', () => reject(new Error('cancelled')), { once: true }));
    }
  };
  let ready;
  const opened = new Promise((resolve) => { ready = resolve; });
  const resolver = target ? () => ({
    incremental: true, live: !!subscribe, ...target,
    getHistoryProof: async (intent) => {
      assert.deepEqual([intent.workspaceId, intent.machineId, intent.sessionId], ['w', 'm', 's']);
      return intent.requestId ? 'android-write-proof' : 'android-read-proof';
    },
  }) : undefined;
  const getGrant = async () => ({ token: 'fixture', gatewayBaseUrl: 'https://x.invalid' });
  const emit = (event) => {
    if (event.session && JSON.parse(event.session).status === 'live') ready();
    observe?.(event);
  };
  const markDispatch = async () => {
    calls.push('dispatch-pointer');
    if (failAt === 'dispatch-pointer') throw new Error('meta_failed');
  };
  const reopen = () => runtime.openSession('s', 'w', getGrant, emit, markDispatch, true, resolver);
  await reopen();
  await opened;
  if (target?.backend === 'roost') runtime.startAcceptedInputs({
    accountId: 'u', workspaceId: 'w', getGrant, emit, markDispatch, resolveTarget: () => resolver,
    async getDocMeta(room) {
      if (room === 'session-s') return { exists: true, meta: { id: 's', machineId: target.machineId, historyBackend: 'roost' } };
      if (room === 'machine-' + target.machineId) return { exists: true, meta: { protocolCapabilities: {
        sessionInputAcceptance: target.acceptsInput ? 1 : 0,
        sessionInputDispatch: target.dispatchesInput ? 1 : 0,
      } } };
    },
  });
  return { doc, calls, reopen, close() { runtime.stopSessions(); void runtime.stopAcceptedInputs(); doc.free(); delete globalThis.__androidStream; if (previousNavigator) Object.defineProperty(globalThis, 'navigator', previousNavigator); else delete globalThis.navigator; } };
}

for (const [failure, expected, rpcAccepted] of [
  [undefined, 'accepted', undefined],
  ['history', 'unknown', undefined],
  ['dispatch-pointer', 'uploaded', undefined],
  ['rpc', 'accepted', false],
]) {
  test(`Loro send preserves durable ordering and does not replay after ${failure ?? 'success'}`, async () => {
    const f = await fixture({ failAt: failure });
    try {
      const result = await runtime.sendTurn(args), duplicate = await runtime.sendTurn(args);
      assert.equal(result.state, expected);
      assert.equal(result.rpcAccepted, rpcAccepted);
      assert.equal(duplicate.state, 'unknown');
      assert.equal(f.doc.toJSON().history[0].items[0].text, 'hello');
      const expectedCalls = failure === 'history'
        ? ['history']
        : failure === 'dispatch-pointer'
          ? ['history', 'dispatch-pointer']
          : ['history', 'dispatch-pointer', 'reply-stream', 'rpc'];
      assert.deepEqual(f.calls, expectedCalls);
    } finally { f.close(); }
  });
}

test('Loro staging fork keeps local doc clean on unsaved upload and recovery returns deterministic states', async () => {
  let latestEnvelope = null;
  const f = await fixture({
    failAt: 'history-unsaved',
    observe(event) {
      if (event.type === 'session') latestEnvelope = JSON.parse(event.session);
    },
  });
  try {
    const failed = await runtime.sendTurn(args);
    assert.equal(failed.state, 'unknown');
    assert.deepEqual(latestEnvelope?.entries ?? [], [], 'unconfirmed write must not pollute local transcript projection');
    const duplicate = await runtime.sendTurn(args);
    assert.equal(duplicate.state, 'unknown');
    assert.equal(duplicate.reason, 'turn_already_exists');
    const recovered = await runtime.actOnPendingInput('s', args.id, 'retry', () => ({ userId: 'u', machineId: 'm', latestUserMsgId: null }));
    assert.deepEqual(recovered, { state: 'not_saved' });
    const tombstoned = await runtime.actOnPendingInput('s', args.id, 'retry', () => ({ userId: 'u', machineId: 'm', missingHistory: true }));
    assert.deepEqual(tombstoned, { state: 'terminal' });
  } finally { f.close(); }
});

test('Android Roost waits for control-stream recovery even after owner history catches up', { timeout: 8_000 }, async (t) => {
  const blocked = Promise.withResolvers();
  const recovered = Promise.withResolvers();
  const retrying = Promise.withResolvers();
  const nextHistory = Promise.withResolvers();
  const synced = Promise.withResolvers();
  let polls = 0, revision = 1, controlUnavailable = false, latest;
  const earlySync = [];
  const turn = () => ({
    id: 'assistant-1', role: 'assistant', timestamp: '2026-01-01T00:00:00Z',
    items: [{ type: 'text', text: 'Synthetic reply' }], finished: revision === 2,
  });
  const f = await fixture({
    target: { backend: 'roost', machineId: 'm' },
    readControl: () => [blocked, recovered][polls++]?.promise,
    observe(event) {
      if (event.type !== 'session') return;
      latest = JSON.parse(event.session);
      if (controlUnavailable) {
        if (event.synced) earlySync.push(latest.status);
        if (latest.status === 'syncing' && latest.reason === 'network_error') retrying.resolve();
      } else if (event.synced && latest.entries[0]?.finished) {
        synced.resolve();
      }
    },
    async *subscribe(request, signal) {
      const { sessionId, readProof, subscription } = await runtime.openPayload(request.params);
      assert.equal(readProof, 'android-read-proof');
      assert.deepEqual([subscription.action, subscription.checkpoint, subscription.includeBodiesFrom], ['open', 'c1', 0]);
      const frame = async (publication) => ({ result: await runtime.sealPayload('s', {
        type: 'session/history-subscription_response', sessionId,
        subscriptionId: subscription.id, event: 'publication', publication,
      }) });
      yield await frame({ checkpoint: 'c1', revision: 1 });
      await nextHistory.promise;
      yield await frame({ checkpoint: 'c2', revision: 2, delta: {
        baseCheckpoint: 'c1', includeBodiesFrom: 0,
        response: { type: 'session/history-read_response', sessionId, success: true,
          historyRevision: 2, historyCount: 1, result: {
            state: 'ready', checkpoint: 'c2', ids: ['assistant-1'],
            bodies: { changed: [turn()], suffix: [] },
          },
        },
      } });
      await new Promise((resolve) => {
        if (signal.aborted) resolve();
        else signal.addEventListener('abort', resolve, { once: true });
      });
    },
    async owner(request) {
      const { query, readProof } = await runtime.openPayload(request.params);
      assert.equal(readProof, 'android-read-proof');
      assert.notEqual(query.kind, 'readChanges');
      const result = {
        checkpoint: `c${revision}`, startPosition: 0, totalCount: 1,
        rows: [{ position: 0, state: 'ready', turnId: 'assistant-1' }],
        hasMoreOlder: false, cursor: null,
      };
      const pageTurns = [turn()];
      return { result: await runtime.sealPayload('s', {
        type: 'session/history-read_response', sessionId: 's', success: true,
        historyRevision: revision, historyCount: 1, result, pageTurns,
      }) };
    },
  });
  t.after(() => {
    recovered.resolve({ ok: false, result: { code: 'cancelled' } });
    f.close();
  });
  assert.equal(latest.entries[0].finished, false);
  controlUnavailable = true;
  blocked.resolve({ ok: false, result: { code: 'network_error' } });
  await retrying.promise;
  revision = 2;
  nextHistory.resolve();
  // Reopening the retained session exposes its snapshot while the retry is held.
  do {
    t.signal.throwIfAborted();
    await new Promise((resolve) => setImmediate(resolve));
    await f.reopen();
  } while (!latest.entries[0]?.finished);
  assert.equal(latest.status, 'syncing');
  assert.equal(latest.sendReady, false);
  assert.equal(latest.controlReady, false);
  assert.deepEqual(earlySync, []);
  controlUnavailable = false;
  recovered.resolve(ok({ nextOffset: '2', upToDate: true, closed: false }));
  await synced.promise;
  assert.equal(latest.status, 'live');
  assert.equal(latest.sendReady, true);
  assert.equal(latest.controlReady, true);
});

test('Android snapshot opens a Roost session and writes input through the owner', async () => {
  const operations = [];
  let accepted;
  const handoff = new Promise((resolve) => { accepted = resolve; });
  const f = await fixture({
    target: { backend: 'roost', machineId: 'm', acceptsInput: true, dispatchesInput: true },
    async owner(request) {
      const params = await runtime.openPayload(request.params);
      operations.push({ method: request.method, params });
      if (request.method === 'session/history-write') accepted();
      const response = request.method === 'session/history-read'
        ? { type: 'session/history-read_response', sessionId: 's', success: true, historyRevision: 1, historyCount: 0,
            result: { checkpoint: 'c1', startPosition: 0, totalCount: 0, rows: [], hasMoreOlder: false, cursor: null }, pageTurns: [] }
        : { type: 'session/history-write_response', sessionId: 's', success: true, operation: params.operation,
            result: { status: 'accepted', turnId: params.payload.entry.id, active: true } };
      return { result: await runtime.sealPayload('s', response) };
    },
  });
  try {
    assert.equal((await runtime.sendTurn(args)).state, 'uploaded');
    await handoff;
    assert.deepEqual(operations.filter((item) => item.method === 'session/history-write').map(({ params }) => [params.operation, params.writeProof]), [['accept_input_and_dispatch', 'android-write-proof']]);
    assert.deepEqual(f.doc.toJSON().history ?? [], []);
  } finally { f.close(); }
});

test('Android snapshot checks the owner receipt after a lost Roost acknowledgement', async () => {
  const operations = [];
  let firstWrite, recoveredWrite;
  const firstHandoff = new Promise((resolve) => { firstWrite = resolve; });
  const recovery = new Promise((resolve) => { recoveredWrite = resolve; });
  const f = await fixture({
    target: { backend: 'roost', machineId: 'm', acceptsInput: true, dispatchesInput: true },
    async owner(request) {
      const params = await runtime.openPayload(request.params);
      operations.push({ method: request.method, params });
      if (request.method === 'session/history-write') {
        if (operations.filter((item) => item.method === 'session/history-write').length === 1) firstWrite(); else recoveredWrite();
        throw new Error('lost_ack');
      }
      const response = params.query.kind === 'inputAcceptance'
        ? { type: 'session/history-read_response', sessionId: 's', success: true, historyRevision: 1, historyCount: 0,
            result: { status: 'accepted', turnId: params.query.entry.id, active: true } }
        : { type: 'session/history-read_response', sessionId: 's', success: true, historyRevision: 1, historyCount: 0,
            result: { checkpoint: 'c1', startPosition: 0, totalCount: 0, rows: [], hasMoreOlder: false, cursor: null }, pageTurns: [] };
      return { result: await runtime.sealPayload('s', response) };
    },
  });
  try {
    assert.equal((await runtime.sendTurn(args)).state, 'uploaded');
    await firstHandoff;
    runtime.resumeAcceptedInputs();
    await recovery;
    assert.equal(operations.filter((item) => item.method === 'session/history-write').length, 2);
    const queries = operations.filter((item) => item.params.query?.kind === 'inputAcceptance');
    assert.ok(queries.length >= 1);
    assert.ok(queries.every((item) => item.params.query.entry.id === args.id));
  } finally { f.close(); }
});
