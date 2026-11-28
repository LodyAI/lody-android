package app.lody.android.create

import app.lody.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreationRefreshTest {
    @Test fun presenceEventRecoversUnknownOptionsWithoutTimerAndDoesNotEraseCache() = runTest {
        val scope = RuntimeScope("account", "workspace")
        val events = MutableSharedFlow<RuntimeEmission>()
        var options = CreationOptions(emptyList(), emptyList(), "unknown")
        var calls = 0
        val runtime = object : CloudRuntime {
            override val scope = scope
            override val state = MutableStateFlow(RuntimeStatus(phase = RuntimePhase.READY, generation = 1))
            override val events = events
            override suspend fun start() {}
            override suspend fun stop() {}
            override suspend fun setActive(active: Boolean) {}
            override suspend fun execute(command: RuntimeCommand): RuntimeResult { calls++; return RuntimeResult.Options(options) }
        }
        var saved: CreationOptions? = null
        val store = object : CreationOptionsStore {
            override suspend fun readOptions(scope: RuntimeScope, projectId: String?) = saved
            override suspend fun writeOptions(scope: RuntimeScope, projectId: String?, options: CreationOptions) { saved = options }
        }
        val owner = RuntimeOwner({ runtime }, StandardTestDispatcher(testScheduler))
        owner.activate(scope); runCurrent()
        val received = mutableListOf<CreationOptions?>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            CreateSessionRepository(owner, store).observeOptions(scope).toList(received)
        }
        advanceTimeBy(200); runCurrent()
        assertNull(saved); assertNull(received.last())
        options = CreationOptions(listOf(CreationAgent("g", "Grok", "mac", "builtin", "grok")), emptyList())
        events.emit(RuntimeEmission(1, RuntimeEvent.CreationChanged("online")))
        advanceTimeBy(200); runCurrent()
        assertEquals(options, received.last()); assertEquals(options, saved)
        val before = calls
        advanceTimeBy(10_000); runCurrent(); assertEquals(before, calls)
        job.cancelAndJoin(); owner.clear()
    }

    @Test fun observeRolesRecoversFromStartingRuntimeUpdatesOnRoleEventsAndKeepsCacheOnFailure() = runTest {
        val scope = RuntimeScope("account", "workspace")
        val state = MutableStateFlow(RuntimeStatus(phase = RuntimePhase.STARTING, generation = 1))
        val events = MutableSharedFlow<RuntimeEmission>()
        var failRoles = false
        var roles = listOf(AgentRole("r1", "Reviewer", "🧪", 1, listOf(AgentRoleTarget("mac", "c1", "configured"))))
        var calls = 0
        val runtime = object : CloudRuntime {
            override val scope = scope
            override val state = state
            override val events = events
            override suspend fun start() {}
            override suspend fun stop() {}
            override suspend fun setActive(active: Boolean) {}
            override suspend fun execute(command: RuntimeCommand): RuntimeResult {
                assertEquals(RuntimeCommand.ListAgentRoles, command)
                calls++
                if (failRoles) throw IllegalStateException("metadata_not_ready")
                return RuntimeResult.Roles(roles)
            }
        }
        var cachedRoles: List<AgentRole>? = null
        val store = object : CreationOptionsStore {
            override suspend fun readOptions(scope: RuntimeScope, projectId: String?): CreationOptions? = null
            override suspend fun writeOptions(scope: RuntimeScope, projectId: String?, options: CreationOptions) {}
            override suspend fun readRoles(scope: RuntimeScope) = cachedRoles
            override suspend fun writeRoles(scope: RuntimeScope, roles: List<AgentRole>) { cachedRoles = roles }
        }
        val owner = RuntimeOwner({ runtime }, StandardTestDispatcher(testScheduler))
        owner.activate(scope); runCurrent()
        val repository = CreateSessionRepository(owner, store)
        val received = mutableListOf<List<AgentRole>?>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeRoles(scope).toList(received)
        }
        // STARTING runtime emits null without calling ListAgentRoles before READY.
        advanceTimeBy(200); runCurrent()
        assertEquals(0, calls)
        assertNull(cachedRoles)
        assertEquals(listOf<List<AgentRole>?>(null), received)

        // Runtime reaches READY: Roles load automatically and populate the cache.
        state.value = RuntimeStatus(phase = RuntimePhase.READY, generation = 1)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, calls)
        assertEquals(roles, received.last())
        assertEquals(roles, repository.cachedRoles(scope))

        // A transient failure on Synced emits null without overwriting the cached Role list.
        failRoles = true
        events.emit(RuntimeEmission(1, RuntimeEvent.Synced(1L, 1)))
        advanceTimeBy(200); runCurrent()
        assertEquals(2, calls)
        assertNull(received.last())
        assertEquals(roles, repository.cachedRoles(scope))

        // `agentRolesChanged` refreshes the Role list once healthy again, with no background polling.
        failRoles = false
        roles = listOf(AgentRole("r2", "Architect", "🏛️", 2, listOf(AgentRoleTarget("mac", "c1", "configured"))))
        events.emit(RuntimeEmission(1, RuntimeEvent.RolesChanged))
        advanceTimeBy(200); runCurrent()
        assertEquals(3, calls)
        assertEquals(roles, received.last())
        assertEquals(roles, repository.cachedRoles(scope))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, calls)
        job.cancelAndJoin(); owner.clear()
    }
}
