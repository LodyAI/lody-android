package app.lody.android.chat

import app.lody.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {
    private class Fake(override val scope: RuntimeScope) : CloudRuntime {
        val mutable = MutableStateFlow(RuntimeStatus(phase = RuntimePhase.READY, generation = 1))
        override val state = mutable.asStateFlow()
        val emitted = MutableSharedFlow<RuntimeEmission>(extraBufferCapacity = 8)
        override val events = emitted.asSharedFlow()
        val commands = mutableListOf<RuntimeCommand>()
        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun setActive(active: Boolean) {}
        override suspend fun execute(command: RuntimeCommand): RuntimeResult {
            commands += command
            return when (command) {
                is RuntimeCommand.WorkspaceChanges -> RuntimeResult.WorkspaceChanges(WorkspaceChanges.Ready(emptyList()))
                is RuntimeCommand.FileDiff -> RuntimeResult.Diff(FileDiff(command.path, "", ""))
                else -> RuntimeResult.Accepted
            }
        }
    }

    @Test fun workspaceReadsCarryTheirComparisonScope() = runTest {
        val scope = RuntimeScope("account", "workspace")
        val fake = Fake(scope)
        val owner = RuntimeOwner({ fake }, StandardTestDispatcher(testScheduler))
        owner.activate(scope); runCurrent()
        val repository = ChatRepository(owner)
        repository.workspaceChanges(scope, "s", DiffScope.Uncommitted)
        repository.fileDiff(scope, "s", "a.ts", DiffScope.Uncommitted)
        repository.workspaceChanges(scope, "s", DiffScope.Branch)
        assertEquals(RuntimeCommand.WorkspaceChanges("s", DiffScope.Uncommitted), fake.commands[0])
        assertEquals(RuntimeCommand.FileDiff("s", "a.ts", DiffScope.Uncommitted), fake.commands[1])
        assertEquals(RuntimeCommand.WorkspaceChanges("s", DiffScope.Branch), fake.commands[2])
        owner.clear()
    }
    @Test fun filtersOtherSessionsAndOldGenerationsReopensAfterRecoveryAndClosesOnExit() = runTest {
        val scope = RuntimeScope("account", "workspace")
        val fake = Fake(scope)
        val owner = RuntimeOwner({ fake }, StandardTestDispatcher(testScheduler))
        owner.activate(scope); runCurrent()
        val received = mutableListOf<SessionSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ChatRepository(owner).observe(scope,"s").toList(received) }
        runCurrent()
        assertEquals(listOf(RuntimeCommand.WatchSession("s")), fake.commands)
        val expected = SessionSnapshot("s", "live", listOf(SessionMessage("u","user","hello",false)))
        fake.emitted.emit(RuntimeEmission(1,RuntimeEvent.SessionUpdated(expected)))
        runCurrent(); assertEquals(expected, received.last())
        fake.emitted.emit(RuntimeEmission(0,RuntimeEvent.SessionUpdated(expected.copy(messages=emptyList()))))
        fake.emitted.emit(RuntimeEmission(1,RuntimeEvent.SessionUpdated(expected.copy(id="other"))))
        runCurrent(); assertEquals(expected, received.last())
        fake.mutable.value = fake.mutable.value.copy(generation=2)
        runCurrent(); assertEquals(2, fake.commands.count { it is RuntimeCommand.WatchSession })
        job.cancelAndJoin(); runCurrent()
        assertEquals(RuntimeCommand.CloseSession("s"),fake.commands.last())
        owner.clear()
    }
}
