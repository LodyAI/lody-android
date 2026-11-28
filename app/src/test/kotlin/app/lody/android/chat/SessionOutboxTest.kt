package app.lody.android.chat

import app.lody.android.runtime.RoleSend
import app.lody.android.runtime.RuntimeScope
import app.lody.android.runtime.SessionMessage
import org.junit.Assert.*
import org.junit.Test

class SessionOutboxTest {
    private fun record(phase: String, turnId: String = "turn-1") = SessionOutbox.Record(
        scope = RuntimeScope("account", "workspace"),
        sessionId = "s1",
        turnId = turnId,
        text = "hello",
        shownText = "hello",
        shownReferences = emptyList(),
        quotes = emptyList(),
        files = emptyList(),
        previews = emptyList(),
        at = "2026-01-01T00:00:00Z",
        machineId = "m1",
        cliType = "builtin",
        agentType = "claude",
        resume = null,
        modelId = null,
        modeId = null,
        effort = null,
        explicitChoice = false,
        role = RoleSend.Inherit,
        agentConfigId = null,
        configOptionValues = emptyMap(),
        persist = true,
        phase = phase,
    )

    @Test fun reconcileCoversEveryAuthoritativePendingInputState() {
        val unknown = record("unknown", "orig-id")
        assertTrue(unknown.blocksInput)
        assertEquals(Delivery.Unknown, unknown.delivery(sessionLive = true))

        assertNull(reconcileOutboxRecord(unknown, "settled"))

        val accepted = reconcileOutboxRecord(unknown, "accepted")!!
        assertEquals("sent", accepted.phase)
        assertFalse(accepted.blocksInput)
        assertEquals("orig-id", accepted.turnId)

        val queued = reconcileOutboxRecord(unknown, "queued")!!
        assertEquals("queued", queued.phase)
        assertFalse(queued.blocksInput)
        assertEquals("orig-id", queued.turnId)

        val notSaved = reconcileOutboxRecord(unknown, "not_saved")!!
        assertEquals("failed", notSaved.phase)
        assertEquals("not_saved", notSaved.reason)
        assertFalse(notSaved.blocksInput)
        assertEquals("orig-id", notSaved.turnId)

        val terminal = reconcileOutboxRecord(unknown, "terminal") { "fresh-uuid" }!!
        assertEquals("failed", terminal.phase)
        assertEquals("input_already_terminal", terminal.reason)
        assertFalse(terminal.blocksInput)
        assertEquals("fresh-uuid", terminal.turnId)

        val pending = reconcileOutboxRecord(unknown, "pending")!!
        assertEquals(unknown, pending)

        val uploaded = record("uploaded", "orig-id")
        assertTrue(uploaded.blocksInput)
        assertEquals(Delivery.Unknown, uploaded.delivery(sessionLive = true))
        assertEquals("sent", reconcileOutboxRecord(uploaded, "accepted")!!.phase)
    }

    @Test fun unconfirmedOutboxIsNotAcknowledgedByUnsettledPendingEntry() {
        val pendingUser = SessionMessage(id = "turn-1", role = "user", text = "hello", streaming = false, status = "pending")
        val settledEmpty = settledTurnIds(listOf(pendingUser))
        assertFalse("turn-1" in settledEmpty)

        // Confirmed sent/queued records clear once landed in the transcript.
        assertTrue(shouldAcknowledgeOutbox(record("sent"), setOf("turn-1"), settledEmpty))
        assertTrue(shouldAcknowledgeOutbox(record("queued"), setOf("turn-1"), settledEmpty))

        // Unconfirmed unknown/uploaded records must NOT be silently dropped by an unsettled pending entry.
        assertFalse(shouldAcknowledgeOutbox(record("unknown"), setOf("turn-1"), settledEmpty))
        assertFalse(shouldAcknowledgeOutbox(record("uploaded"), setOf("turn-1"), settledEmpty))

        // Once the computer picks up the turn or replies, unconfirmed records acknowledge cleanly.
        val seen = settledTurnIds(listOf(pendingUser.copy(status = "seen")))
        assertTrue(shouldAcknowledgeOutbox(record("unknown"), setOf("turn-1"), seen))
        val replied = settledTurnIds(listOf(
            pendingUser,
            SessionMessage(id = "a1", role = "assistant", text = "reply", streaming = false),
        ))
        assertTrue(shouldAcknowledgeOutbox(record("uploaded"), setOf("turn-1", "a1"), replied))

        // Roost pendingRoostInputs takeover clears the outbox duplicate.
        val roostOwned = settledTurnIds(listOf(pendingUser.copy(status = "sending")))
        assertTrue(shouldAcknowledgeOutbox(record("uploaded"), setOf("turn-1"), roostOwned))
    }
}
