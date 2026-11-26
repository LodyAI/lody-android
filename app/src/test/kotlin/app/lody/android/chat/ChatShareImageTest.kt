package app.lody.android.chat

import app.lody.android.runtime.ConversationShareData
import app.lody.android.runtime.RuntimeProtocol
import app.lody.android.runtime.ShareCardMessage
import app.lody.android.runtime.ShareCardReference
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatShareImageTest {

    @Test
    fun decodesConversationShareMessagesAndDropsMalformedEntries() {
        val raw = JSONObject(
            """
            {
              "title": "Refactor stream sync",
              "createdAt": 1731650400000,
              "cliType": "builtin",
              "agentType": "claude-code",
              "agentName": "My Claude",
              "messages": [
                {
                  "id": "u1",
                  "role": "user",
                  "text": "Check @ChatScreen.kt\n\nQuoted snapshot",
                  "references": [
                    {"start": 6, "end": 20, "kind": "mention", "label": "@ChatScreen.kt"},
                    {"start": 22, "end": 37, "kind": "quote", "label": "Quoted snapshot"},
                    {"start": 10, "end": 5, "kind": "mention", "label": "bad"}
                  ],
                  "estimatedTokens": 42
                },
                {
                  "id": "s1",
                  "role": "system",
                  "text": "ignored",
                  "references": [],
                  "estimatedTokens": 5
                },
                {
                  "id": "a1",
                  "role": "assistant",
                  "text": "Done.",
                  "references": [],
                  "estimatedTokens": 1280,
                  "modelName": "Claude Sonnet 4.5",
                  "userTurnId": "u1"
                }
              ]
            }
            """.trimIndent(),
        )

        val decoded = RuntimeProtocol.conversationShare(raw)
        assertEquals("Refactor stream sync", decoded.title)
        assertEquals(1731650400000L, decoded.createdAt)
        assertEquals("My Claude", decoded.agentName)
        assertEquals(2, decoded.messages.size)
        assertEquals(
            listOf(
                ShareCardReference(6, 20, "mention", "@ChatScreen.kt"),
                ShareCardReference(22, 37, "quote", "Quoted snapshot"),
            ),
            decoded.messages[0].references,
        )
        assertEquals("Claude Sonnet 4.5", decoded.messages[1].modelName)
        assertEquals("u1", decoded.messages[1].userTurnId)
    }

    @Test
    fun selectionPreservesChronologicalOrderAndTracksLoadedTranscriptWindow() {
        val data = ConversationShareData(
            messages = listOf(
                ShareCardMessage(id = "u1", role = "user", text = "older prompt"),
                ShareCardMessage(id = "a1", role = "assistant", text = "older reply"),
                ShareCardMessage(id = "u2", role = "user", text = "recent prompt"),
                ShareCardMessage(id = "a2", role = "assistant", text = "recent reply"),
            ),
            title = "Session",
            createdAt = 1_700_000_000_000L,
        )

        // Initially only the recent page (u2, a2) is loaded in the UI transcript.
        val initialWindow = setOf("u2", "a2", "ops-1")
        val initialSelectable = ChatMessageSelection.selectableIds(data, initialWindow)
        assertEquals(listOf("u2", "a2"), initialSelectable)

        var selected = emptySet<String>()
        selected = ChatMessageSelection.toggle(selected, "a2")
        selected = ChatMessageSelection.toggle(selected, "u2")
        // Tapped in reverse order (a2 then u2), exported in chronological order (u2 then a2).
        assertEquals(
            listOf("u2", "a2"),
            ChatMessageSelection.selectedMessages(data, selected, initialWindow).map { it.id },
        )

        // Select all while all visible items are already selected clears selection.
        assertEquals(emptySet<String>(), ChatMessageSelection.toggleAll(selected, initialSelectable))

        // After paging older history, u1 and a1 automatically join selectable candidates.
        val expandedWindow = setOf("u1", "a1", "u2", "a2", "ops-1")
        val expandedSelectable = ChatMessageSelection.selectableIds(data, expandedWindow)
        assertEquals(listOf("u1", "a1", "u2", "a2"), expandedSelectable)
        val allSelected = ChatMessageSelection.toggleAll(selected, expandedSelectable)
        assertEquals(setOf("u1", "a1", "u2", "a2"), allSelected)
    }

    @Test
    fun verticalRhythmUsesReplyGapOnlyForPairedPromptAndReply() {
        val layout = ShareCardModel.layout(ShareCardDestination.Chat)
        val nonContiguous = listOf(
            ShareCardMessage(id = "u1", role = "user", text = "first question"),
            ShareCardMessage(id = "a2", role = "assistant", text = "second answer", userTurnId = "u2"),
            ShareCardMessage(id = "u3", role = "user", text = "third question"),
            ShareCardMessage(id = "a3", role = "assistant", text = "third answer", userTurnId = "u3"),
        )
        assertEquals(0, ShareCardModel.itemTopGapDp(layout, nonContiguous, 0))
        // a2 belongs to u2 (not u1), so it opens a separate exchange rather than binding to u1.
        assertEquals(layout.exchangeGap, ShareCardModel.itemTopGapDp(layout, nonContiguous, 1))
        assertEquals(layout.exchangeGap, ShareCardModel.itemTopGapDp(layout, nonContiguous, 2))
        // a3 belongs to u3 directly above it, so it gets replyGap.
        assertEquals(layout.replyGap, ShareCardModel.itemTopGapDp(layout, nonContiguous, 3))
    }

    @Test
    fun captionMetadataAndSignOffFollowWebCardContract() {
        val data = ConversationShareData(
            messages = emptyList(),
            title = "Card meta",
            createdAt = 1_732_615_200_000L, // 2024-11-26 10:00 UTC
            cliType = "builtin",
            agentType = "codex",
            agentName = null,
        )
        val selected = listOf(
            ShareCardMessage(id = "u1", role = "user", text = "prompt", estimatedTokens = 350),
            ShareCardMessage(id = "a1", role = "assistant", text = "first", estimatedTokens = 900, modelName = "GPT-5"),
            ShareCardMessage(id = "a2", role = "assistant", text = "second", estimatedTokens = 1_250, modelName = "GPT-5.2 Codex"),
            ShareCardMessage(id = "a3", role = "assistant", text = "tail without model", estimatedTokens = 100, modelName = null),
        )
        val metaEn = ShareCardModel.buildMeta(
            data = data,
            selectedMessages = selected,
            fallbackAgentName = null,
            tokensFormat = { "~$it tokens" },
            locale = Locale.US,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals("Codex", metaEn.name)
        assertEquals(listOf("GPT-5.2 Codex", "~2.6K tokens"), metaEn.params)
        assertEquals("2024-11-26 10:00", metaEn.date)

        assertEquals("1.3万", ShareCardModel.formatCompactTokens(12_600, Locale.SIMPLIFIED_CHINESE))
        assertFalse(ShareCardModel.signOffOnMat(ShareCardBackdrop.None, 32))
        assertFalse(ShareCardModel.signOffOnMat(ShareCardBackdrop.Lody, 8))
        assertTrue(ShareCardModel.signOffOnMat(ShareCardBackdrop.Lody, 12))
    }

    @Test
    fun exportPlanCapsPixelHeightSlicesStripsAndRejectsOversizedSelections() {
        // Normal card: @2x scale.
        val normal = ShareCardModel.exportPlan(outerWidthDp = 392f, outerHeightDp = 1200f) as ShareExportPlan.Ready
        assertEquals(2.0f, normal.scale, 0.001f)
        assertEquals(784, normal.pixelWidth)
        assertEquals(2400, normal.pixelHeight)
        assertEquals(listOf(0 until 2048, 2048 until 2400), normal.strips)

        // Tall card (10,000 dp): reduces scale to fit 16,384 px ceiling (>= 1.25x).
        val tall = ShareCardModel.exportPlan(outerWidthDp = 560f, outerHeightDp = 10_000f) as ShareExportPlan.Ready
        assertTrue(tall.scale in 1.25f..1.64f)
        assertTrue(tall.pixelHeight <= ShareCardModel.MAX_EXPORT_HEIGHT_PX)
        assertEquals(0, tall.strips.first().first)
        assertEquals(tall.pixelHeight - 1, tall.strips.last().last)
        assertTrue(tall.strips.all { (it.last - it.first + 1) <= ShareCardModel.STRIP_HEIGHT_PX })

        // Extremely tall card (> 13,108 dp -> scale < 1.25x): rejected cleanly before bitmap allocation.
        assertEquals(ShareExportPlan.TooLarge, ShareCardModel.exportPlan(outerWidthDp = 360f, outerHeightDp = 14_000f))

        assertEquals("Fix_a_b_c.png", ShareCardModel.sanitizeFileName("Fix_a/b:c").replace(' ', '_'))
        assertEquals("lody-chat.png", ShareCardModel.sanitizeFileName("   ///   "))
    }

    @Test
    fun streamingPngWriterProducesValidExactRgbaPng() {
        val out = ByteArrayOutputStream()
        val row0 = intArrayOf(0xFF112233.toInt(), 0x80445566.toInt(), 0x00000000)
        val row1 = intArrayOf(0xFFFFFFFF.toInt(), 0xFF161618.toInt(), 0xFF35C8B0.toInt())
        StreamingPngWriter(out, width = 3, height = 2).use { writer ->
            writer.writeArgbRow(row0)
            writer.writeArgbRow(row1)
        }
        val image = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(image)
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertEquals(0xFF112233.toInt(), image.getRGB(0, 0))
        assertEquals(0x80445566.toInt(), image.getRGB(1, 0))
        assertEquals(0xFF161618.toInt(), image.getRGB(1, 1))
        assertEquals(0xFF35C8B0.toInt(), image.getRGB(2, 1))
    }

    @Test
    fun shareImageStringKeysMatchAcrossEnglishAndChineseResources() {
        fun keysOf(file: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val root = doc.documentElement
            val out = LinkedHashSet<String>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                if (name.startsWith("share_image_")) {
                    out += "${node.nodeName}:$name"
                }
            }
            return out
        }

        val enFile = File("src/main/res/values/strings.xml")
        val zhFile = File("src/main/res/values-b+zh+Hans/strings.xml")
        val enKeys = keysOf(enFile)
        val zhKeys = keysOf(zhFile)
        assertTrue(enKeys.isNotEmpty())
        assertEquals(enKeys, zhKeys)
    }
}
