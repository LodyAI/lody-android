import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { createDispatcher } from '../src/dispatch.mjs';
import { withDiffScope } from '../src/diff-commands.mjs';

test('workspace and file diffs forward their comparison scope to the machine', async () => {
  const bundle = await build({
    stdin: {
      contents: "export {workspaceChanges, fileDiff} from './files';",
      resolveDir: new URL('../vendor/cloud-runtime/src/runtime/', import.meta.url).pathname,
      loader: 'ts',
    },
    bundle: true, format: 'cjs', platform: 'browser', write: false,
    plugins: [{ name: 'machine-transport', setup(builder) {
      builder.onResolve({ filter: /^\.\/machine-rpc$/ }, () => ({ path: 'machine-rpc-fixture', external: true }));
      builder.onResolve({ filter: /^\.\/local-projects$/ }, () => ({ path: 'unused-project-control', external: true, sideEffects: false }));
    }}],
  });
  const calls = [];
  const module = { exports: {} };
  new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(() => ({
    async sealedRpc(_workspace, _machine, method, _owner, params) {
      calls.push({ method, params });
      if (method === 'code-collab/open-all-changes-diff') {
        return { status: 'ok', base: 'HEAD', entries: [] };
      }
      return {
        status: 'ok',
        path: 'a.ts',
        oldSnapshot: { kind: 'missing' },
        newSnapshot: { kind: 'missing' },
      };
    },
  }), module, module.exports);
  const context = {
    workspaceId: 'workspace',
    machineId: 'machine',
    ownerSessionId: 'owner',
    getGrant: async () => ({}),
    signal: new AbortController().signal,
  };
  await module.exports.workspaceChanges(context, { sessionId: 's', scope: 'uncommitted' });
  await module.exports.fileDiff(context, { sessionId: 's', path: 'a.ts', scope: 'uncommitted' });
  assert.deepEqual(calls, [
    { method: 'code-collab/open-all-changes-diff', params: { sessionId: 's', scope: 'uncommitted' } },
    { method: 'code-collab/open-current-diff', params: { sessionId: 's', path: 'a.ts', scope: 'uncommitted' } },
  ]);
});

test('an omitted scope is not mistaken for the dispatcher signal', async () => {
  const replies = [];
  const dispatch = createDispatcher({}, (reply) => replies.push(reply), {
    workspaceChanges: (sessionId, scope) => withDiffScope({ sessionId }, scope),
    fileDiff: (sessionId, path, scope) => withDiffScope({ sessionId, path }, scope),
  });
  await dispatch({ id: 'changes-branch', method: 'workspaceChanges', args: ['s'] });
  await dispatch({ id: 'changes-uncommitted', method: 'workspaceChanges', args: ['s', 'uncommitted'] });
  await dispatch({ id: 'file-branch', method: 'fileDiff', args: ['s', 'a.ts'] });
  assert.deepEqual(replies, [
    { type: 'reply', id: 'changes-branch', value: { sessionId: 's' } },
    { type: 'reply', id: 'changes-uncommitted', value: { sessionId: 's', scope: 'uncommitted' } },
    { type: 'reply', id: 'file-branch', value: { sessionId: 's', path: 'a.ts' } },
  ]);
});
