package app.lody.android.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.lody.android.designsystem.LodyColors
import app.lody.android.designsystem.LodyMonoFontFamily
import app.lody.android.designsystem.LodyRadius
import app.lody.android.designsystem.LodySansFontFamily
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyStroke
import app.lody.android.designsystem.LodyTheme
import app.lody.android.home.AgentMark
import app.lody.android.runtime.ConversationShareData
import app.lody.android.runtime.MessageQuoteBlock
import app.lody.android.runtime.MessageReference
import app.lody.android.runtime.ShareCardMessage
import app.lody.android.runtime.ShareCardReference
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.intellij.markdown.MarkdownTokenTypes

enum class ShareCardDestination(val widthDp: Int, val defaultMat: Int) {
    Chat(widthDp = 360, defaultMat = 16),
    Post(widthDp = 560, defaultMat = 56),
}

enum class ShareCardBackdrop {
    None,
    Lody,
    Welcome,
    Aurora,
    Ocean,
    Sunset,
}

enum class ShareCardTheme {
    Light,
    Dark,
}

data class ShareCardLayout(
    val width: Int,
    val radius: Int,
    val gutter: Int,
    val top: Int,
    val titleSize: Int,
    val afterTitle: Int,
    val bottom: Int,
    val exchangeGap: Int,
    val replyGap: Int,
    val promptRadius: Int,
    val promptPadH: Int,
    val promptPadV: Int,
    val captionPadV: Int,
    val signatureTop: Int,
)

data class ShareCardMeta(
    val name: String,
    val cliType: String?,
    val agentType: String?,
    val params: List<String>,
    val date: String?,
)

sealed interface ShareExportPlan {
    data class Ready(
        val scale: Float,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val strips: List<IntRange>,
    ) : ShareExportPlan

    data object TooLarge : ShareExportPlan
}

/** Pure template dimensions, metadata formatting, and strip-rasterization math matching Web `ChatShareCard`. */
internal object ShareCardModel {
    const val MIN_MAT = 0
    const val MAX_MAT = 96
    const val MAT_STEP = 4
    const val MIN_SIGN_OFF_MAT = 12
    const val BODY_FONT_SIZE_SP = 15
    const val MAX_EXPORT_HEIGHT_PX = 16_384
    const val MIN_EXPORT_SCALE = 1.25f
    const val TARGET_EXPORT_SCALE = 2.0f
    const val STRIP_HEIGHT_PX = 2_048

    private val ChatLayout = ShareCardLayout(
        width = 360,
        radius = 22,
        gutter = 20,
        top = 24,
        titleSize = 17,
        afterTitle = 16,
        bottom = 20,
        exchangeGap = 24,
        replyGap = 12,
        promptRadius = 14,
        promptPadH = 14,
        promptPadV = 10,
        captionPadV = 14,
        signatureTop = 16,
    )

    private val PostLayout = ShareCardLayout(
        width = 560,
        radius = 26,
        gutter = 28,
        top = 28,
        titleSize = 20,
        afterTitle = 20,
        bottom = 24,
        exchangeGap = 28,
        replyGap = 14,
        promptRadius = 16,
        promptPadH = 16,
        promptPadV = 12,
        captionPadV = 16,
        signatureTop = 20,
    )

    private val DatePattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    fun layout(destination: ShareCardDestination): ShareCardLayout = when (destination) {
        ShareCardDestination.Chat -> ChatLayout
        ShareCardDestination.Post -> PostLayout
    }

    fun snapMat(raw: Float): Int {
        val snapped = (raw / MAT_STEP).roundToInt() * MAT_STEP
        return snapped.coerceIn(MIN_MAT, MAX_MAT)
    }

    fun effectiveMat(backdrop: ShareCardBackdrop, mat: Int): Int =
        if (backdrop == ShareCardBackdrop.None) 0 else mat.coerceIn(MIN_MAT, MAX_MAT)

    fun signOffOnMat(backdrop: ShareCardBackdrop, mat: Int): Boolean =
        backdrop != ShareCardBackdrop.None && effectiveMat(backdrop, mat) >= MIN_SIGN_OFF_MAT

    fun outerWidthDp(destination: ShareCardDestination, backdrop: ShareCardBackdrop, mat: Int): Int =
        layout(destination).width + effectiveMat(backdrop, mat) * 2

    fun itemTopGapDp(layout: ShareCardLayout, messages: List<ShareCardMessage>, index: Int): Int {
        if (index <= 0 || index >= messages.size) return 0
        val current = messages[index]
        val previous = messages[index - 1]
        val pairedReply = current.role == "assistant" &&
            previous.role == "user" &&
            (current.userTurnId == null || current.userTurnId == previous.id)
        return if (pairedReply) layout.replyGap else layout.exchangeGap
    }

    fun formatCompactTokens(tokens: Long, locale: Locale = Locale.getDefault()): String {
        val safe = tokens.coerceAtLeast(0L)
        val isZh = locale.language.equals("zh", ignoreCase = true)
        return if (isZh) {
            when {
                safe < 10_000L -> safe.toString()
                safe < 100_000_000L -> compactUnit(safe, 10_000.0, "万")
                else -> compactUnit(safe, 100_000_000.0, "亿")
            }
        } else {
            when {
                safe < 1_000L -> safe.toString()
                safe < 1_000_000L -> compactUnit(safe, 1_000.0, "K")
                safe < 1_000_000_000L -> compactUnit(safe, 1_000_000.0, "M")
                else -> compactUnit(safe, 1_000_000_000.0, "B")
            }
        }
    }

    private fun compactUnit(value: Long, divisor: Double, suffix: String): String {
        val scaled = value / divisor
        val tenths = (scaled * 10.0).roundToInt()
        return if (tenths % 10 == 0 || tenths >= 1000) {
            "${tenths / 10}$suffix"
        } else {
            "${tenths / 10}.${tenths % 10}$suffix"
        }
    }

    fun buildMeta(
        data: ConversationShareData,
        selectedMessages: List<ShareCardMessage>,
        fallbackAgentName: String?,
        tokensFormat: (String) -> String,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ShareCardMeta {
        val resolvedName = data.agentName?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackAgentName?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayAgentType(data.cliType, data.agentType)
            ?: "Lody"
        val lastModel = selectedMessages
            .lastOrNull { it.role == "assistant" && !it.modelName.isNullOrBlank() }
            ?.modelName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val totalTokens = selectedMessages.sumOf { it.estimatedTokens.coerceAtLeast(0L) }
        val tokenLabel = if (totalTokens > 0L) tokensFormat(formatCompactTokens(totalTokens, locale)) else null
        val params = listOfNotNull(lastModel, tokenLabel)
        val date = if (data.createdAt > 0L) {
            Instant.ofEpochMilli(data.createdAt).atZone(zoneId).format(DatePattern)
        } else {
            null
        }
        return ShareCardMeta(
            name = resolvedName,
            cliType = data.cliType,
            agentType = data.agentType,
            params = params,
            date = date,
        )
    }

    private fun displayAgentType(cliType: String?, agentType: String?): String? {
        if (cliType == "custom") return "Custom"
        val raw = agentType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (raw) {
            "claude", "claude-code", "claude-p" -> "Claude Code"
            "codex" -> "Codex"
            "gemini" -> "Gemini CLI"
            "kimi", "kimi-code" -> "Kimi Code"
            "deepseek" -> "DeepSeek"
            "opencode", "opencode-v2" -> "OpenCode"
            "pi", "pi-acp" -> "Pi"
            "qwen-code" -> "Qwen Code"
            "cursor" -> "Cursor"
            else -> raw.split('-', '_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        }
    }

    fun sanitizeFileName(title: String): String {
        val cleaned = title
            .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(60)
            .trim()
        val base = cleaned.ifEmpty { "lody-chat" }
        return "$base.png"
    }

    fun exportPlan(
        outerWidthDp: Float,
        outerHeightDp: Float,
        targetScale: Float = TARGET_EXPORT_SCALE,
    ): ShareExportPlan {
        if (outerWidthDp <= 0f || outerHeightDp <= 0f) return ShareExportPlan.TooLarge
        val rawScale = if (outerHeightDp * targetScale <= MAX_EXPORT_HEIGHT_PX) {
            targetScale
        } else {
            floor((MAX_EXPORT_HEIGHT_PX / outerHeightDp) * 100f) / 100f
        }
        if (rawScale < MIN_EXPORT_SCALE) return ShareExportPlan.TooLarge
        val pixelWidth = ceil(outerWidthDp * rawScale).toInt().coerceAtLeast(1)
        val pixelHeight = ceil(outerHeightDp * rawScale).toInt().coerceAtLeast(1)
        if (pixelHeight > MAX_EXPORT_HEIGHT_PX) return ShareExportPlan.TooLarge
        val strips = ArrayList<IntRange>()
        var y = 0
        while (y < pixelHeight) {
            val end = minOf(pixelHeight, y + STRIP_HEIGHT_PX)
            strips += y until end
            y = end
        }
        return ShareExportPlan.Ready(
            scale = rawScale,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            strips = strips,
        )
    }
}

/**
 * Pure native conversation share card matching Web `ChatShareCard`.
 * Scopes both palette ([theme]) and font scale (`1f`) strictly to the card.
 */
@Composable
internal fun ChatShareCard(
    messages: List<ShareCardMessage>,
    title: String,
    destination: ShareCardDestination,
    mat: Int,
    theme: ShareCardTheme,
    backdrop: ShareCardBackdrop,
    meta: ShareCardMeta,
    modifier: Modifier = Modifier,
    diagramCache: Map<String, MermaidDiagram> = emptyMap(),
) {
    val layout = ShareCardModel.layout(destination)
    val matDp = ShareCardModel.effectiveMat(backdrop, mat)
    val signOffOnMat = ShareCardModel.signOffOnMat(backdrop, mat)
    val trimmedTitle = title.trim()
    val hostDensity = LocalDensity.current
    val fixedDensity = remember(hostDensity.density) { Density(density = hostDensity.density, fontScale = 1f) }

    CompositionLocalProvider(
        LocalDensity provides fixedDensity,
        LocalMessageQuoter provides null,
        LocalUriHandler provides InertUriHandler,
    ) {
        LodyTheme(darkTheme = theme == ShareCardTheme.Dark) {
            val colors = LodyTheme.colors
            val isDark = theme == ShareCardTheme.Dark
            val cardBg = if (isDark) Color(0xFF161618) else Color(0xFFFFFFFF)
            val cardRing = if (isDark) Color(0x1AFFFFFF) else Color(0x0F000000)
            val cardShape = RoundedCornerShape(layout.radius.dp)
            val cardModifier = Modifier
                .width(layout.width.dp)
                .then(
                    if (matDp >= ShareCardModel.MIN_SIGN_OFF_MAT && backdrop != ShareCardBackdrop.None) {
                        Modifier.dropShadow(
                            cardShape,
                            Shadow(radius = 24.dp, color = Color(0x66020A12), offset = DpOffset(0.dp, 14.dp)),
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(cardShape)
                .background(cardBg)
                .border(1.dp, cardRing, cardShape)

            val cardContent: @Composable () -> Unit = {
                Column(cardModifier) {
                    if (trimmedTitle.isNotEmpty()) {
                        Text(
                            text = trimmedTitle,
                            style = TextStyle(
                                fontFamily = LodySansFontFamily,
                                fontWeight = FontWeight.W600,
                                fontSize = layout.titleSize.sp,
                                lineHeight = (layout.titleSize * 1.375f).sp,
                                letterSpacing = (-0.35).sp,
                            ),
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = layout.gutter.dp,
                                    end = layout.gutter.dp,
                                    top = layout.top.dp,
                                ),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = layout.gutter.dp,
                                end = layout.gutter.dp,
                                top = (if (trimmedTitle.isNotEmpty()) layout.afterTitle else layout.top).dp,
                                bottom = layout.bottom.dp,
                            ),
                    ) {
                        messages.forEachIndexed { index, message ->
                            val gap = ShareCardModel.itemTopGapDp(layout, messages, index)
                            if (gap > 0) Spacer(Modifier.height(gap.dp))
                            if (message.role == "user") {
                                ShareUserPrompt(message, layout, isDark)
                            } else {
                                ShareAssistantProse(
                                    text = message.text,
                                    innerWidthDp = layout.width - layout.gutter * 2,
                                    diagramCache = diagramCache,
                                )
                            }
                        }
                    }

                    ShareCaptionBand(
                        meta = meta,
                        layout = layout,
                        signOffInCaption = !signOffOnMat,
                        isDark = isDark,
                    )
                }
            }

            if (backdrop == ShareCardBackdrop.None) {
                Box(modifier = modifier.width(layout.width.dp)) {
                    cardContent()
                }
            } else {
                Column(
                    modifier = modifier
                        .width((layout.width + matDp * 2).dp)
                        .shareBackdrop(backdrop)
                        .padding(matDp.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    cardContent()
                    if (signOffOnMat) {
                        Spacer(Modifier.height(layout.signatureTop.dp))
                        ShareBackdropSignOff(backdrop = backdrop)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareUserPrompt(
    message: ShareCardMessage,
    layout: ShareCardLayout,
    isDark: Boolean,
) {
    val promptFill = if (isDark) Color(0x12FFFFFF) else Color(0x0C1C1C1C)
    val promptBorder = if (isDark) Color(0x1CFFFFFF) else Color(0x121C1C1C)
    val shape = RoundedCornerShape(layout.promptRadius.dp)
    val mentions = remember(message.references) {
        message.references.filter { it.kind == "mention" }.map { MessageReference(it.start, it.end) }
    }
    val quotes = remember(message.references) {
        message.references.filter { it.kind == "quote" }.map { MessageQuoteBlock(it.start, it.end, it.label) }
    }
    val segments = remember(message.text, mentions, quotes) {
        MessageQuotes.segments(message.text, mentions, quotes)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(promptFill)
            .border(1.dp, promptBorder, shape)
            .padding(horizontal = layout.promptPadH.dp, vertical = layout.promptPadV.dp),
    ) {
        if (quotes.isEmpty()) {
            ShareReferenceText(text = message.text, references = mentions)
        } else {
            segments.forEachIndexed { index, segment ->
                val topGap = when {
                    index == 0 -> 0.dp
                    segment is MessageQuotes.Segment.Plain && segment.comment -> LodySpacing.xs
                    else -> LodySpacing.md
                }
                if (topGap > 0.dp) Spacer(Modifier.height(topGap))
                when (segment) {
                    is MessageQuotes.Segment.Plain -> ShareReferenceText(text = segment.text, references = segment.references)
                    is MessageQuotes.Segment.Quote -> ShareExpandedQuote(snapshot = segment.snapshot)
                }
            }
        }
    }
}

@Composable
private fun ShareExpandedQuote(snapshot: String) {
    val colors = LodyTheme.colors
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm),
    ) {
        Box(
            Modifier
                .width(LodyStroke.focus + LodyStroke.default)
                .fillMaxHeight()
                .background(colors.borderStrong, RoundedCornerShape(LodyRadius.full)),
        )
        Text(
            text = snapshot,
            style = ShareBodyTextStyle,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun ShareReferenceText(
    text: String,
    references: List<MessageReference>,
) {
    val colors = LodyTheme.colors
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = buildAnnotatedString {
            append(text)
            references.forEach { ref ->
                if (ref.start >= 0 && ref.end <= length && ref.end > ref.start) {
                    addStyle(
                        SpanStyle(color = colors.accentOnSubtle, fontWeight = FontWeight.Medium),
                        ref.start,
                        ref.end,
                    )
                }
            }
        },
        style = ShareBodyTextStyle,
        color = colors.textPrimary,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val measured = layout ?: return@drawBehind
                val len = measured.layoutInput.text.length
                val pad = 3.dp.toPx()
                val inset = 1.dp.toPx()
                references.forEach { ref ->
                    if (ref.start < 0 || ref.end > len || ref.end <= ref.start) return@forEach
                    val first = measured.getLineForOffset(ref.start)
                    val last = measured.getLineForOffset(ref.end - 1)
                    for (line in first..last) {
                        if (line >= measured.lineCount) break
                        val start = maxOf(ref.start, measured.getLineStart(line))
                        val end = minOf(ref.end, measured.getLineEnd(line, visibleEnd = true))
                        if (end <= start) continue
                        val left = measured.getBoundingBox(start).left
                        val right = measured.getBoundingBox(end - 1).right
                        val top = measured.getLineTop(line) + inset
                        val bottom = measured.getLineBottom(line) - inset
                        if (right <= left || bottom <= top) continue
                        val radius = minOf(6.dp.toPx(), (bottom - top) / 2f)
                        drawRoundRect(
                            color = colors.accentSubtle,
                            topLeft = Offset(left - pad, top),
                            size = Size(right - left + pad * 2f, bottom - top),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    }
                }
            },
    )
}

@Composable
private fun ShareAssistantProse(
    text: String,
    innerWidthDp: Int,
    diagramCache: Map<String, MermaidDiagram>,
) {
    val blocks = remember(text) { MarkdownBlockSplitter.split(text) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        blocks.forEach { block ->
            val mermaid = remember(block) { mermaidSource(block) }
            when {
                mermaid != null -> ShareMermaidBlock(mermaid, diagramCache) {
                    ShareMarkdownBlock(block)
                }
                looksLikeMarkdownTable(block) -> ScaleToFitWidth(maxWidthDp = innerWidthDp) {
                    ShareMarkdownBlock(block)
                }
                else -> ShareMarkdownBlock(block)
            }
        }
    }
}

private fun looksLikeMarkdownTable(block: String): Boolean {
    val lines = block.trim().lines()
    return lines.size >= 2 && '|' in lines[0] && lines[1].contains(Regex("\\|?\\s*:?-{3,}:?\\s*\\|"))
}

/** Synchronously backed [MarkdownState] so live previews and offscreen captures never show Loading. */
private class ImmediateMarkdownState(content: String) : MarkdownState {
    private val parsed: State = parseMarkdown(content)
    override val state: StateFlow<State> = MutableStateFlow(parsed)
    override val links: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override suspend fun parse(): State = parsed
}

@Composable
private fun ShareMarkdownBlock(text: String, modifier: Modifier = Modifier) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val markdownState = remember(text) { ImmediateMarkdownState(text) }
    val (fileLinks, fileLinkIcons) = rememberFileLinkMarkdown()
    Markdown(
        markdownState = markdownState,
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(
            text = colors.textPrimary,
            codeBackground = colors.codeBg,
            inlineCodeBackground = colors.codeInlineBg,
            dividerColor = colors.borderSubtle,
            tableBackground = colors.fillTertiary,
        ),
        typography = markdownTypography(
            h1 = type.title.copy(fontSize = 20.sp, lineHeight = 26.sp),
            h2 = type.headline.copy(fontSize = 17.sp, lineHeight = 23.sp),
            h3 = type.bodyEmphasis.copy(fontSize = 15.sp, lineHeight = 22.sp),
            h4 = type.bodyEmphasis.copy(fontSize = 15.sp, lineHeight = 22.sp),
            h5 = type.subhead,
            h6 = type.subhead,
            text = ShareBodyTextStyle,
            code = ShareCodeTextStyle,
            inlineCode = ShareBodyTextStyle.copy(fontFamily = LodyMonoFontFamily, fontSize = 13.5.sp),
            quote = ShareBodyTextStyle.copy(color = colors.textSecondary, fontStyle = FontStyle.Italic),
            paragraph = ShareBodyTextStyle,
            ordered = ShareBodyTextStyle,
            bullet = ShareBodyTextStyle,
            list = ShareBodyTextStyle,
            textLink = TextLinkStyles(style = SpanStyle(color = colors.textLink)),
            table = type.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        ),
        components = markdownComponents(
            codeFence = { model -> ShareSoftWrappedCodeBlock(model, fenced = true) },
            codeBlock = { model -> ShareSoftWrappedCodeBlock(model, fenced = false) },
        ),
        annotator = fileLinks,
        inlineContent = fileLinkIcons,
    )
}

/**
 * Code blocks on share cards soft-wrap unconditionally (no horizontal scrollbars and no copy
 * buttons) and place any language label on its own top row so line 1 is never occluded.
 */
@Composable
private fun ShareSoftWrappedCodeBlock(model: MarkdownComponentModel, fenced: Boolean) {
    val colors = LodyTheme.colors
    val (language, body) = remember(model.content, model.node, fenced) {
        extractCodeBlock(model, fenced)
    }
    val shape = RoundedCornerShape(12.dp)
    val isDiff = language.equals("diff", ignoreCase = true) || language.equals("patch", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBg)
            .border(1.dp, colors.codeBorder, shape),
    ) {
        if (language.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = language,
                    style = LodyTheme.typography.micro.copy(fontFamily = LodyMonoFontFamily),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        if (isDiff) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                body.lines().forEach { line ->
                    val (lineBg, lineFg) = when {
                        line.startsWith("+") && !line.startsWith("+++") -> colors.diffAddedBg to colors.diffAddedText
                        line.startsWith("-") && !line.startsWith("---") -> colors.diffRemovedBg to colors.diffRemovedText
                        line.startsWith("@@") -> colors.diffHunkBg to colors.diffHunkText
                        else -> Color.Transparent to colors.codeText
                    }
                    Text(
                        text = line.ifEmpty { " " },
                        style = ShareCodeTextStyle,
                        color = lineFg,
                        softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(lineBg)
                            .padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
        } else {
            Text(
                text = body,
                style = ShareCodeTextStyle,
                color = colors.codeText,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (language.isNotEmpty()) 4.dp else 10.dp,
                        bottom = 10.dp,
                    ),
            )
        }
    }
}

private fun extractCodeBlock(model: MarkdownComponentModel, fenced: Boolean): Pair<String, String> {
    val raw = model.content.substring(model.node.startOffset, model.node.endOffset)
    if (!fenced) {
        val unindented = raw.lines().joinToString("\n") { line ->
            when {
                line.startsWith("    ") -> line.drop(4)
                line.startsWith("\t") -> line.drop(1)
                else -> line
            }
        }.trimEnd('\n', '\r')
        return "" to unindented
    }
    val langNode = model.node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
    val language = langNode?.let { model.content.substring(it.startOffset, it.endOffset).trim() }.orEmpty()
    val lines = raw.lines()
    val inner = if (lines.size >= 2) {
        val lastLine = lines.last().trim()
        val closed = lastLine.startsWith("```") || lastLine.startsWith("~~~")
        lines.subList(1, if (closed) lines.size - 1 else lines.size).joinToString("\n")
    } else {
        ""
    }
    return language to inner.trimEnd('\n', '\r')
}

@Composable
private fun ShareMermaidBlock(
    source: String,
    diagramCache: Map<String, MermaidDiagram>,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val diagrams = remember { MermaidDiagrams.get(context) }
    val colors = LodyTheme.colors
    val palette = remember(colors) { shareMermaidPalette(colors) }
    var diagram by remember(source, palette, diagramCache) {
        mutableStateOf(diagramCache[palette.key + source] ?: diagrams.cached(source, palette))
    }
    LaunchedEffect(source, palette) {
        if (diagram == null) diagram = diagrams.render(source, palette)
    }
    val shown = diagram
    if (shown == null || shown.width <= 0f || shown.height <= 0f) {
        fallback()
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = min(maxWidth, shown.width.dp)
        Canvas(Modifier.size(width, width * (shown.height / shown.width))) {
            val scale = size.width / shown.width
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.apply {
                    save()
                    scale(scale, scale)
                    drawPicture(shown.picture)
                    restore()
                }
            }
        }
    }
}

internal fun shareMermaidPalette(colors: LodyColors): MermaidPalette = MermaidPalette(
    mapOf(
        "pele-bg" to colors.bgBase,
        "pele-fg" to colors.textPrimary,
        "pele-muted" to colors.textSecondary,
        "pele-line" to colors.textSecondary,
        "pele-surface" to colors.fillTertiary,
        "pele-surface-alt" to colors.fillSecondary,
        "pele-border" to colors.borderDefault,
        "pele-accent" to colors.accentDefault,
    ),
    colors.bgBase,
)

/** Measures wide Markdown tables at up to 1200dp and scales them down proportionally if wider than [maxWidthDp]. */
@Composable
private fun ScaleToFitWidth(maxWidthDp: Int, content: @Composable () -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val targetWidthPx = constraints.maxWidth.coerceAtLeast(1)
        val naturalMaxPx = maxOf(targetWidthPx, 1200.dp.roundToPx())
        val placeable = subcompose("table", content).first().measure(
            Constraints(minWidth = targetWidthPx, maxWidth = naturalMaxPx),
        )
        val scale = if (placeable.width > targetWidthPx) targetWidthPx.toFloat() / placeable.width.toFloat() else 1f
        val scaledHeight = (placeable.height * scale).roundToInt().coerceAtLeast(1)
        layout(targetWidthPx, scaledHeight) {
            placeable.placeWithLayer(0, 0) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

@Composable
private fun ShareCaptionBand(
    meta: ShareCardMeta,
    layout: ShareCardLayout,
    signOffInCaption: Boolean,
    isDark: Boolean,
) {
    val colors = LodyTheme.colors
    val dividerColor = if (isDark) Color(0x1CFFFFFF) else Color(0x17000000)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.gutter.dp, vertical = layout.captionPadV.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (meta.cliType != null || meta.agentType != null) {
                AgentMark(
                    cliType = meta.cliType,
                    agentType = meta.agentType,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    imageVector = LodyBrandVector,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.name,
                    style = TextStyle(
                        fontFamily = LodySansFontFamily,
                        fontWeight = FontWeight.W600,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (signOffInCaption) {
                    Text(
                        text = "lody.ai",
                        style = ShareCaptionSubStyle,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (meta.params.isNotEmpty() || meta.date != null) {
                Column(
                    modifier = Modifier.widthIn(max = ((layout.width - layout.gutter * 2) * 0.62f).dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (meta.params.isNotEmpty()) {
                        Text(
                            text = meta.params.joinToString(" · "),
                            style = TextStyle(
                                fontFamily = LodySansFontFamily,
                                fontWeight = FontWeight.W500,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontFeatureSettings = "tnum",
                            ),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                    if (meta.date != null) {
                        Text(
                            text = meta.date,
                            style = ShareCaptionSubStyle.copy(fontFeatureSettings = "tnum"),
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(top = if (meta.params.isNotEmpty()) 2.dp else 0.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareBackdropSignOff(backdrop: ShareCardBackdrop) {
    val ink = if (backdrop == ShareCardBackdrop.Welcome) Color(0xC720424C) else Color(0xD9FFFFFF)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = LodyBrandVector,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "lody.ai",
            style = TextStyle(
                fontFamily = LodySansFontFamily,
                fontWeight = FontWeight.W500,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.3.sp,
            ),
            color = ink,
        )
    }
}

/** Paints the five named backdrops from `CHAT_SHARE_BACKDROP_STYLES` in Web `chat-share-card.tsx`. */
internal fun Modifier.shareBackdrop(backdrop: ShareCardBackdrop): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    when (backdrop) {
        ShareCardBackdrop.None -> Unit
        ShareCardBackdrop.Lody -> {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0A1C2B), Color(0xFF0C2438), Color(0xFF081626)),
                    start = Offset(w * 0.2f, 0f),
                    end = Offset(w * 0.8f, h),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x7335C8B0), Color.Transparent),
                    center = Offset(w * 0.18f, h * 0.12f),
                    radius = maxOf(w, h) * 0.52f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x802F77BF), Color.Transparent),
                    center = Offset(w * 0.86f, h * 0.16f),
                    radius = maxOf(w, h) * 0.48f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xA61F4F7F), Color.Transparent),
                    center = Offset(w * 0.68f, h * 0.96f),
                    radius = maxOf(w, h) * 0.68f,
                ),
            )
        }
        ShareCardBackdrop.Welcome -> {
            drawRect(color = Color(0xFFDCE5E7))
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x33FFFFFF), Color(0x0F21444F)),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x94FFFFFF), Color.Transparent),
                    center = Offset(w * 0.18f, h * 0.18f),
                    radius = maxOf(w, h) * 0.46f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x212A5D6F), Color.Transparent),
                    center = Offset(w * 0.82f, h * 0.72f),
                    radius = maxOf(w, h) * 0.56f,
                ),
            )
            val step = 88.dp.toPx().coerceAtLeast(1f)
            var x = 0f
            while (x <= w) {
                drawLine(
                    color = Color(0x24193A44),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.dp.toPx(),
                )
                x += step
            }
        }
        ShareCardBackdrop.Aurora -> {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFFDB2777)),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
            )
        }
        ShareCardBackdrop.Ocean -> {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0369A1), Color(0xFF0891B2), Color(0xFF34D399)),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
            )
        }
        ShareCardBackdrop.Sunset -> {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF9A3412), Color(0xFFEA580C), Color(0xFFF59E0B)),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
            )
        }
    }
}

private val ShareBodyTextStyle = TextStyle(
    fontFamily = LodySansFontFamily,
    fontWeight = FontWeight.W400,
    fontSize = ShareCardModel.BODY_FONT_SIZE_SP.sp,
    lineHeight = 23.sp,
    letterSpacing = (-0.13).sp,
)

private val ShareCodeTextStyle = TextStyle(
    fontFamily = LodyMonoFontFamily,
    fontWeight = FontWeight.W400,
    fontSize = 12.5.sp,
    lineHeight = 19.sp,
)

private val ShareCaptionSubStyle = TextStyle(
    fontFamily = LodySansFontFamily,
    fontWeight = FontWeight.W400,
    fontSize = 10.5.sp,
    lineHeight = 14.sp,
)

private object InertUriHandler : UriHandler {
    override fun openUri(uri: String) = Unit
}

/** Crisp Lody jellyfish brand vector from `packages/components/src/assets/lody.svg`. */
internal val LodyBrandVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "LodyBrand",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 626f,
        viewportHeight = 626f,
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M324.871 624.669C312.676 620.688 304.346 608.622 305.472 596.568C306.016 590.744 308.032 585.769 312.519 579.185C330.839 552.303 332.888 523.055 319.069 485.672C316.215 477.95 310.566 463.878 307.245 456.217C300.289 440.171 298.504 435.666 294.754 424.69C288.666 406.872 284.737 390.219 282.204 371.49C281.203 364.094 280.884 357.187 280.91 343.49C280.948 323.965 282.065 311.967 285.244 296.945C286.175 292.545 287.471 285.225 288.125 280.68C288.779 276.134 290.115 268.48 291.095 263.671C292.311 257.698 292.876 252.524 292.876 247.346V239.766L285.785 240.265C281.885 240.539 277.385 240.976 275.785 241.236L272.876 241.708L272.623 247.144C272.366 252.667 273.369 272.801 274.722 289.308C275.245 295.682 275.231 301.008 274.677 307.127C274.242 311.927 273.604 319.454 273.259 323.854C272.452 334.162 270.612 342.379 266.053 356.03C261.066 370.963 257.885 378.946 253.113 388.509C248.54 397.673 238.511 414.179 230.975 424.945C213.442 449.992 204.462 467.795 201.547 483.29C198.796 497.915 203.107 513.707 214.177 529.557C220.727 538.934 222.264 546.571 219.418 555.595C214.428 571.416 196.907 579.333 182.188 572.419C176.658 569.822 173.401 566.629 167.693 558.21C158.931 545.286 152.481 530.125 149.054 514.399C147.386 506.746 147.148 503.971 147.121 491.854C147.094 480.079 147.331 476.96 148.727 470.763C152.895 452.261 158.327 439.466 171.121 418.009C173.634 413.795 176.403 409.704 177.274 408.918C178.145 408.133 181.257 404.508 184.19 400.864C187.124 397.22 193.145 390.25 197.571 385.376C203.151 379.232 206.428 374.906 208.257 371.274C216.927 354.06 223.67 332.263 225.885 314.292C229.608 284.092 229.87 276.075 227.5 264.945C226.648 260.945 225.427 256.089 224.788 254.153C223.789 251.129 223.407 250.693 222.069 251.049C221.213 251.277 217.804 252.029 214.493 252.721C209.635 253.736 208.562 254.194 208.932 255.098C209.184 255.714 209.98 259.654 210.701 263.854C211.422 268.054 212.501 273.454 213.099 275.854C214.699 282.273 214.97 284.274 215.774 295.615C217.92 325.889 210.057 353.03 193.043 374.076C181.172 388.76 167.157 399.121 149.685 406.13C140.24 409.919 135.743 411.124 125.292 412.666C104.837 415.686 82.2443 410.81 65.5044 399.764C63.5586 398.48 58.3508 393.803 53.9314 389.369C43.685 379.09 39.2657 371.764 35.1271 358.196C31.2317 345.425 30.9589 329.664 34.4025 316.333C38.9358 298.784 44.4076 290.373 54.2971 285.753C57.7577 284.136 59.3113 283.871 65.2394 283.882C71.3662 283.893 72.6408 284.134 76.4963 286.006C82.0501 288.702 87.2226 293.88 89.7396 299.263C91.4427 302.905 91.7211 304.447 91.7506 310.399C91.7829 316.911 91.6183 317.685 88.8873 323.854C86.3412 329.605 85.9912 331.061 86.0008 335.854C86.0154 343.073 88.0572 347.718 93.3377 352.544C103.118 361.483 117.73 362.103 133.589 354.253C139.141 351.505 141.555 349.762 145.781 345.452C153.977 337.09 157.784 330.794 160.381 321.308C162.644 313.043 163.043 306.994 161.735 300.798C161.119 297.879 160.072 292.054 159.408 287.854C158.19 280.153 156.36 272.062 154.472 266.036L153.447 262.763L145.525 262.289C135.568 261.693 126.984 259.541 119.074 255.656C110.633 251.51 101.637 242.664 97.8257 234.763C87.8598 214.1 88.3479 190.42 99.5271 152.217C104.666 134.657 115.976 111.123 126.607 95.8716C137.353 80.4552 154.199 62.0187 167.643 50.9608C189.168 33.257 206.74 23.1145 233.994 12.6636C254.99 4.61237 288.876 -0.720045 313.967 0.0790846C333.043 0.686675 353.91 3.6251 366.33 7.45299C368.13 8.00772 372.058 9.18186 375.058 10.0622C378.058 10.9425 381.494 12.0919 382.694 12.6165C383.894 13.141 387.167 14.4595 389.967 15.5464C407.211 22.2401 429.871 35.2096 443.058 45.9336C475.23 72.0968 497.526 101.824 511.593 137.308C521.679 162.751 526.251 186.158 525.374 207.854C524.882 220.007 523.338 226.016 518.31 235.338C514.261 242.845 510.099 247.554 503.447 252.152C497.097 256.542 491.991 258.76 483.899 260.643C477.518 262.127 477.595 262.052 471.003 273.308C466.425 281.126 465.55 283.271 463.364 292.028C458.435 311.779 461.056 331.838 470.314 345.202C480.403 359.765 494.741 367.961 510.056 367.919C520.217 367.892 525.635 366.085 530.887 360.973C532.392 359.508 534.507 356.61 535.584 354.536C537.263 351.305 537.544 349.875 537.545 344.581C537.545 338.921 537.304 337.877 534.688 332.217C532.008 326.418 531.83 325.608 531.808 319.127C531.788 313.123 532.04 311.672 533.734 308.059C539.326 296.128 552.194 289.698 564.683 292.594C576.248 295.276 582.225 302.087 587.651 318.763C598.15 351.033 588.231 384.759 562.194 405.323C547.716 416.757 531.881 421.847 510.694 421.875C491.512 421.901 475.223 417.138 457.397 406.29C436.488 393.568 419.641 371.65 411.918 347.127C408.44 336.081 407.42 327.991 407.469 311.854C407.511 298.032 407.749 294.951 409.522 285.308C410.626 279.308 411.694 272.927 411.896 271.127C412.098 269.327 413.265 265.297 414.488 262.172C415.764 258.911 416.437 256.23 416.067 255.88C415.712 255.544 412.831 254.702 409.666 254.008C404.167 252.802 403.869 252.803 402.964 254.04C402.014 255.34 396.043 272.899 393.321 282.399C389.543 295.587 388.707 313.02 391.058 329.573C392.43 339.234 393.062 342.009 396.294 352.581C402.064 371.452 413.612 389.71 429.645 405.308C453.775 428.785 468.14 447.318 479.219 469.265C482.701 476.162 484.359 485.573 484.419 498.763C484.498 516.497 481.317 529.797 472.919 546.834C467.213 558.411 463.874 562.487 457.16 566.074C451.911 568.877 451.615 568.944 444.512 568.913C438.155 568.886 436.707 568.633 433.012 566.902C424.447 562.891 418.35 554.363 417.494 545.196C416.908 538.923 418.504 533.103 422.986 525.164C427.086 517.9 429.181 512.137 430.327 504.968C431.534 497.41 429.55 485.958 425.485 477.013C419.132 463.034 406.006 443.026 397.293 434.036C391.455 428.013 379.757 413.708 375.289 407.127C366.89 394.758 358.642 370.121 356.542 351.127C355.106 338.147 354.603 319.686 355.362 307.854C355.998 297.93 359.443 270.086 360.183 268.889C360.522 268.34 363.419 247.138 363.42 245.189C363.421 244.153 351.666 241.285 349.615 241.822C347.277 242.433 344.749 252.945 341.994 273.515C340.988 281.028 339.021 294.119 337.622 302.606C333.218 329.336 333.373 355.943 338.061 378.036C342.03 396.737 345.9 408.24 358 437.308C365.661 455.713 369.134 464.478 371.398 471.127C374.993 481.681 379.227 498.716 380.618 508.217C383.199 525.848 381.895 550.364 377.601 564.945C373.642 578.387 372.154 582.41 368.05 590.763C358.76 609.67 351.681 619.11 343.955 622.89C340.558 624.551 338.752 624.921 333.28 625.077C329.658 625.18 325.874 624.997 324.871 624.669ZM147.626 174.136C151.211 172.619 152.108 171.188 156.413 160.11C161.16 147.894 162.464 145.25 169.729 133.117C177.79 119.656 190.02 105.372 203.473 93.7083C219.095 80.1632 241.954 67.8778 261.756 62.3845C266.272 61.1316 270.948 59.7924 272.148 59.4083C276.228 58.1027 291.821 55.8993 300.181 55.4472C304.763 55.1994 309.565 54.6768 310.852 54.2858C314.459 53.1898 316.876 49.8385 316.876 45.932C316.876 41.8718 316.106 40.3955 312.981 38.4642C306.198 34.272 273.288 38.5814 249.052 46.8353C212.73 59.2058 180.445 83.964 157.542 117.012C151.238 126.109 149.211 129.578 144.571 139.211C133.418 162.365 132.532 169.735 140.431 173.645C143.968 175.396 144.555 175.436 147.626 174.136Z",
            ),
            fill = SolidColor(Color.White),
        )
    }.build()
}
