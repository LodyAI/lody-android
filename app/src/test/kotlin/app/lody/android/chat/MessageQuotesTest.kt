package app.lody.android.chat

import app.lody.android.runtime.MessageQuoteCard
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageQuotesTest {
    @Test fun commentsAttachToTheirQuoteWhileSeparateBlocksStayApart() {
        val (text, blocks) = MessageQuotes.compose("Intro", listOf(
            MessageQuoteCard("a", "first passage", "first comment"),
            MessageQuoteCard("b", "second passage", ""),
            MessageQuoteCard("c", "third passage", "last comment"),
        ))
        assertEquals(
            listOf(
                MessageQuotes.Segment.Plain("Intro", emptyList(), comment = false),
                MessageQuotes.Segment.Quote("first passage"),
                MessageQuotes.Segment.Plain("first comment", emptyList(), comment = true),
                MessageQuotes.Segment.Quote("second passage"),
                MessageQuotes.Segment.Quote("third passage"),
                MessageQuotes.Segment.Plain("last comment", emptyList(), comment = true),
            ),
            MessageQuotes.segments(text, emptyList(), blocks),
        )
    }
}
