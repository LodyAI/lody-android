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
    @Test fun aShownTranscriptSurvivesForegroundResubscribeUntilReadableAgain() = runTest {
        val scope = RuntimeScope("account", "workspace")
        val fake = Fake(scope)
        val owner = RuntimeOwner({ fake }, StandardTestDispatcher(testScheduler))
        owner.activate(scope); runCurrent()
        var readable: SessionSnapshot? = null
        val shown = mutableListOf<SessionSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ChatRepository(owner).observe(scope, "s") { readable?.historyStart }.collect { raw ->
                shown += raw.shown(readable)
                if (raw.readable) readable = raw
            }
        }
        runCurrent()
        assertEquals("A first open without data still shows loading", emptyList<SessionMessage>(), shown.last().messages)
        val hello = SessionMessage("u", "user", "hello", false)
        val loaded = SessionSnapshot("s", "live", listOf(hello), historyHasMore = true, historyStart = 6)
        fake.emitted.emit(RuntimeEmission(1, RuntimeEvent.SessionUpdated(loaded)))
        runCurrent(); assertEquals(loaded, shown.last())
        fake.mutable.value = fake.mutable.value.copy(phase = RuntimePhase.SUSPENDED)
        runCurrent(); assertEquals("offline", shown.last().status); assertEquals(listOf(hello), shown.last().messages)
        // Foreground with a replaced runtime: the reopened replica starts empty and syncing.
        fake.mutable.value = RuntimeStatus(phase = RuntimePhase.READY, generation = 2)
        runCurrent()
        assertEquals(RuntimeCommand.WatchSession("s", historyStart = 6), fake.commands.last())
        fake.emitted.emit(RuntimeEmission(2, RuntimeEvent.SessionUpdated(SessionSnapshot("s", "syncing", emptyList()))))
        runCurrent()
        assertEquals("syncing", shown.last().status)
        assertFalse(shown.last().sendReady || shown.last().controlReady)
        assertEquals(listOf(hello), shown.last().messages)
        assertTrue(shown.last().historyHasMore)
        val reply = SessionMessage("a", "assistant", "new", false)
        val caughtUp = SessionSnapshot("s", "live", listOf(hello, reply))
        fake.emitted.emit(RuntimeEmission(2, RuntimeEvent.SessionUpdated(caughtUp)))
        runCurrent(); assertEquals(caughtUp, shown.last())
        val other = SessionSnapshot("t", "syncing", emptyList())
        assertEquals("Another conversation never shows this transcript", other, other.shown(caughtUp))
        job.cancelAndJoin(); owner.clear()
    }
}
