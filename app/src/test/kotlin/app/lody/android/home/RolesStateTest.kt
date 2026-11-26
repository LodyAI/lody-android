package app.lody.android.home

import app.lody.android.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RolesStateTest {
    private val scope = RuntimeScope("account-roles", "workspace-roles")
    private val agent = RoleAgent("a1", "Coder", "m1", "Studio", "builtin", "codex")
    private val role = RoleSetting(
        id = "r1",
        ownerUserId = "account-roles",
        shared = false,
        name = "Reviewer",
        description = "Find regressions",
        promptPrefix = "Review carefully.",
        emoji = "🧪",
        revision = 1,
        targets = listOf(RoleSettingTarget("m1", "a1", "Studio", "Coder", "configured")),
        raw = """{"id":"r1","ownerUserId":"account-roles","visibility":"private","name":"Reviewer","description":"Find regressions","promptPrefix":"Review carefully.","emoji":"🧪","revision":1,"updatedAt":1,"targets":[{"machineId":"m1","agentConfigId":"a1","machineName":"Studio","agentName":"Coder","state":"configured"}]}""",
    )
    private val snapshot = RoleSettingsSnapshot(listOf(role), listOf(agent))

    private class MemoryStore : SettingsSnapshotStore {
        val values = mutableMapOf<Pair<RuntimeScope, String>, JSONObject>()
        override suspend fun readSettings(scope: RuntimeScope, kind: String): JSONObject? = values[scope to kind]
        override suspend fun writeSettings(scope: RuntimeScope, kind: String, value: JSONObject) {
            values[scope to kind] = value
        }
    }

    @Test fun diskCacheRestoresReadOnlyUntilLiveReadAndPersistsAcceptedSnapshot() = runTest {
        val diskScope = RuntimeScope("account-roles-disk", "workspace-roles-disk")
        val store = MemoryStore()
        store.writeSettings(diskScope, "agent-roles", RoleSettingsJson.encode(snapshot))
        var listed = false
        val liveRole = role.copy(
            name = "Live Reviewer",
            raw = role.raw.replace("\"Reviewer\"", "\"Live Reviewer\""),
        )
        val service = object : RoleSettingsService {
            override suspend fun perform(scope: RuntimeScope, action: RoleSettingsAction): RoleSettingsSnapshot {
                check(action == RoleSettingsAction.List) { "cached snapshot must not authorize write" }
                listed = true
                return snapshot.copy(roles = listOf(liveRole))
            }
        }
        val state = RolesState(service, diskScope, store)
        state.restore()
        assertEquals(listOf("Reviewer"), state.snapshot?.roles?.map { it.name })
        assertFalse(state.live)
        assertFalse(state.perform(RoleSettingsAction.Delete(role), role.id, null))
        assertFalse(listed)
        state.refresh()
        assertTrue(state.live)
        assertEquals(listOf("Live Reviewer"), state.snapshot?.roles?.map { it.name })
        val persisted = RoleSettingsJson.result(store.values.getValue(diskScope to "agent-roles"))
        assertEquals(listOf("Live Reviewer"), persisted.roles.map { it.name })
    }

    @Test fun unknownWriteFailureRollsBackLocksEditorAndReconcilesList() = runTest {
        var stored = snapshot
        val submitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var listCalls = 0
        val service = object : RoleSettingsService {
            override suspend fun perform(scope: RuntimeScope, action: RoleSettingsAction): RoleSettingsSnapshot = when (action) {
                RoleSettingsAction.List -> {
                    listCalls++
                    stored
                }
                is RoleSettingsAction.Delete -> {
                    submitted.complete(Unit)
                    finish.await()
                    stored = snapshot.copy(roles = emptyList())
                    throw app.lody.android.runtime.RuntimeException(RuntimeFault("role_write_unknown", RuntimeErrorCategory.BUSINESS))
                }
                else -> error("unexpected action")
            }
        }
        val state = RolesState(service, scope)
        state.refresh()
        assertEquals(1, listCalls)
        val deleting = async { state.perform(RoleSettingsAction.Delete(role), role.id, null) }
        submitted.await()
        // Optimistic update removes the row while the write is in flight.
        assertTrue(state.snapshot!!.roles.isEmpty())
        assertTrue(state.mutating)
        finish.complete(Unit)
        assertFalse(deleting.await())
        assertEquals("role_write_unknown", state.writeError)
        assertTrue(state.uncertain)
        // Reconciled against server truth after failure, and locked against replay.
        assertEquals(2, listCalls)
        assertTrue(state.snapshot!!.roles.isEmpty())
        assertFalse(state.perform(RoleSettingsAction.Delete(role), role.id, null))
    }

    @Test fun knownWriteRefusalKeepsDraftEditableAndRollsBack() = runTest {
        val service = object : RoleSettingsService {
            override suspend fun perform(scope: RuntimeScope, action: RoleSettingsAction): RoleSettingsSnapshot = when (action) {
                RoleSettingsAction.List -> snapshot
                is RoleSettingsAction.Save -> throw app.lody.android.runtime.RuntimeException(RuntimeFault("role_duplicate", RuntimeErrorCategory.BUSINESS))
                else -> error("unexpected action")
            }
        }
        val state = RolesState(service, scope)
        state.refresh()
        val fields = RoleFields("Duplicate", "", "", false, "🧪")
        val preview = role.copy(name = "Duplicate")
        assertFalse(state.perform(RoleSettingsAction.Save(role.id, role, fields, null), role.id, preview))
        assertEquals("role_duplicate", state.writeError)
        assertFalse(state.uncertain)
        assertEquals(listOf("Reviewer"), state.snapshot?.roles?.map { it.name })
    }

    @Test fun runtimeProtocolDecodesAgentRolesChangedEvent() {
        assertEquals(RuntimeEvent.RolesChanged, RuntimeProtocol.event(JSONObject().put("type", "agentRolesChanged")))
    }
}
