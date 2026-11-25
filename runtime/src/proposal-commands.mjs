// Android adapter over the shared runtime's `decideProposal`: Save / Create / Apply or Dismiss an
// Agent's Role, schedule or schedule edit proposal in the watched session; `inspectProposal` reads
// what an edit proposal would change in the live schedule (a read, never a write). Failures travel in-band as `{error: code}`; an
// unrecognised write failure may have landed, so it is `proposal_write_unknown` and nothing retries.
const CODES = new Set([
 'session_not_ready','metadata_not_ready','workspace_changed','proposal_unavailable','proposal_not_requester',
 'role_duplicate','role_read_only','role_forbidden','role_target_unavailable','role_invalid','role_write_unknown',
 'schedule_target_unavailable','schedule_not_owner','schedule_machine_unsupported','schedule_trigger_invalid',
 'schedule_title_invalid','schedule_conflict','schedule_edit_stale','schedule_write_unknown','upload_failed','proposal_write_unknown',
]);
const KEY = /^[A-Za-z0-9_-]{1,128}$/;
const ID = value => typeof value === 'string' && value.length > 0 && value.length <= 512;

export function proposalCommands(runtime, identity) {
 const target = input => KEY.test(input?.sessionId ?? '') && ID(input?.entryId) && ID(input?.itemId);
 return {
  async inspectProposal(input, signal) {
   signal?.throwIfAborted();
   const scope = identity();
   if (!scope?.workspaceId || !scope?.userId) return {error:'not_connected'};
   if (!target(input)) return {error:'invalid_request'};
   try {
    return await runtime.inspectProposal({workspaceId:scope.workspaceId, sessionId:input.sessionId, entryId:input.entryId, itemId:input.itemId});
   } catch (error) {
    const code = String(error?.message ?? '');
    return {error:CODES.has(code) ? code : 'proposal_unavailable'};
   }
  },
  async decideProposal(input, signal) {
   signal?.throwIfAborted();
   const scope = identity();
   if (!scope?.workspaceId || !scope?.userId) return {error:'not_connected'};
   if (!target(input) || !['accept','dismiss'].includes(input.decision)) return {error:'invalid_request'};
   try {
    return await runtime.decideProposal({workspaceId:scope.workspaceId, sessionId:input.sessionId, entryId:input.entryId, itemId:input.itemId, decision:input.decision});
   } catch (error) {
    const code = String(error?.message ?? '');
    return {error:CODES.has(code) ? code : 'proposal_write_unknown'};
   }
  },
 };
}
