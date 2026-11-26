package app.lody.android.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import app.lody.android.R
import app.lody.android.designsystem.LodyDarkColors
import app.lody.android.designsystem.LodyLightColors
import app.lody.android.runtime.ShareCardMessage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ShareImageExportResult {
    data class Success(val file: File, val uri: Uri) : ShareImageExportResult
    data object TooLarge : ShareImageExportResult
    data object Aborted : ShareImageExportResult
    data object Failed : ShareImageExportResult
}

/**
 * Renders [ChatShareCard] offscreen in bounded horizontal strips (`<= 2048 px` tall) and streams
 * straight RGBA scanlines into a PNG inside `cacheDir/shared-images/chat-share/<uuid>/`.
 */
internal object ChatShareImageExporter {
    private const val CACHE_DIR = "shared-images/chat-share"
    private const val STALE_MAX_AGE_MS = 15L * 60L * 1_000L

    fun clearCache(context: Context) {
        runCatching {
            File(context.cacheDir, CACHE_DIR).deleteRecursively()
        }
    }

    fun pruneStaleCache(context: Context, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val root = File(context.cacheDir, CACHE_DIR)
            if (!root.isDirectory) return
            root.listFiles()?.forEach { child ->
                if (nowMs - child.lastModified() > STALE_MAX_AGE_MS) {
                    child.deleteRecursively()
                }
            }
        }
    }

    fun deleteStagedFile(file: File?) {
        if (file == null) return
        runCatching {
            val parent = file.parentFile
            file.delete()
            if (parent != null && parent.name.length == 36) {
                parent.deleteRecursively()
            }
        }
    }

    suspend fun renderToPng(
        context: Context,
        messages: List<ShareCardMessage>,
        title: String,
        destination: ShareCardDestination,
        mat: Int,
        theme: ShareCardTheme,
        backdrop: ShareCardBackdrop,
        meta: ShareCardMeta,
        canProceed: () -> Boolean,
    ): ShareImageExportResult = withContext(Dispatchers.Main) {
        if (!canProceed() || messages.isEmpty()) return@withContext ShareImageExportResult.Aborted
        val activity = context.findActivity() ?: return@withContext ShareImageExportResult.Failed
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: return@withContext ShareImageExportResult.Failed

        withContext(Dispatchers.IO) { pruneStaleCache(context) }

        val diagramCache = prewarmMermaidDiagrams(context, messages, theme)
        if (!canProceed()) return@withContext ShareImageExportResult.Aborted

        val outerWidthDp = ShareCardModel.outerWidthDp(destination, backdrop, mat)
        var renderScale by mutableFloatStateOf(ShareCardModel.TARGET_EXPORT_SCALE)

        val host = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            alpha = 0f
            translationX = -100_000f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = renderScale, fontScale = 1f)) {
                    ChatShareCard(
                        messages = messages,
                        title = title,
                        destination = destination,
                        mat = mat,
                        theme = theme,
                        backdrop = backdrop,
                        meta = meta,
                        diagramCache = diagramCache,
                    )
                }
            }
        }
        host.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        var outputFile: File? = null
        try {
            rootView.addView(host, ViewGroup.LayoutParams(1, 1))
            // Allow WindowRecomposer to attach and complete initial composition.
            withFrameNanos { }
            withFrameNanos { }

            val initialWidthPx = ceil(outerWidthDp * ShareCardModel.TARGET_EXPORT_SCALE).toInt().coerceAtLeast(1)
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(initialWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val measuredHeightDp = composeView.measuredHeight / ShareCardModel.TARGET_EXPORT_SCALE
            val plan = when (val resolved = ShareCardModel.exportPlan(outerWidthDp.toFloat(), measuredHeightDp)) {
                is ShareExportPlan.Ready -> resolved
                ShareExportPlan.TooLarge -> return@withContext ShareImageExportResult.TooLarge
            }

            if (plan.scale != renderScale) {
                renderScale = plan.scale
                withFrameNanos { }
            }

            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(plan.pixelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val finalHeightPx = composeView.measuredHeight.coerceAtLeast(1)
            val finalPlan = if (finalHeightPx == plan.pixelHeight) {
                plan
            } else {
                when (
                    val adjusted = ShareCardModel.exportPlan(
                        outerWidthDp = outerWidthDp.toFloat(),
                        outerHeightDp = finalHeightPx / plan.scale,
                        targetScale = plan.scale,
                    )
                ) {
                    is ShareExportPlan.Ready -> adjusted
                    ShareExportPlan.TooLarge -> return@withContext ShareImageExportResult.TooLarge
                }
            }

            composeView.layout(0, 0, finalPlan.pixelWidth, finalPlan.pixelHeight)
            withFrameNanos { }

            if (!canProceed()) return@withContext ShareImageExportResult.Aborted

            val stageDir = File(File(context.cacheDir, CACHE_DIR), UUID.randomUUID().toString())
            withContext(Dispatchers.IO) { stageDir.mkdirs() }
            val file = File(stageDir, ShareCardModel.sanitizeFileName(title))
            outputFile = file

            val maxStripHeight = finalPlan.strips.maxOf { it.last - it.first + 1 }.coerceAtLeast(1)
            val stripBitmap = Bitmap.createBitmap(finalPlan.pixelWidth, maxStripHeight, Bitmap.Config.ARGB_8888)
            val stripCanvas = Canvas(stripBitmap)
            val rowArgb = IntArray(finalPlan.pixelWidth)

            try {
                BufferedOutputStream(FileOutputStream(file)).use { out ->
                    StreamingPngWriter(out, finalPlan.pixelWidth, finalPlan.pixelHeight).use { png ->
                        for (strip in finalPlan.strips) {
                            coroutineContext.ensureActive()
                            if (!canProceed()) {
                                deleteStagedFile(file)
                                return@withContext ShareImageExportResult.Aborted
                            }
                            val stripHeight = strip.last - strip.first + 1
                            stripCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
                            stripCanvas.save()
                            stripCanvas.clipRect(0, 0, finalPlan.pixelWidth, stripHeight)
                            stripCanvas.translate(0f, -strip.first.toFloat())
                            composeView.draw(stripCanvas)
                            stripCanvas.restore()

                            withContext(Dispatchers.Default) {
                                for (row in 0 until stripHeight) {
                                    stripBitmap.getPixels(rowArgb, 0, finalPlan.pixelWidth, 0, row, finalPlan.pixelWidth, 1)
                                    png.writeArgbRow(rowArgb)
                                }
                            }
                        }
                    }
                }
            } finally {
                stripBitmap.recycle()
            }

            if (!canProceed()) {
                deleteStagedFile(file)
                return@withContext ShareImageExportResult.Aborted
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
            ShareImageExportResult.Success(file = file, uri = uri)
        } catch (cancelled: CancellationException) {
            deleteStagedFile(outputFile)
            throw cancelled
        } catch (_: Throwable) {
            deleteStagedFile(outputFile)
            ShareImageExportResult.Failed
        } finally {
            runCatching { rootView.removeView(host) }
        }
    }

    fun copyUriToClipboard(context: Context, uri: Uri, label: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, label, uri))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.share_image_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun prewarmMermaidDiagrams(
        context: Context,
        messages: List<ShareCardMessage>,
        theme: ShareCardTheme,
    ): Map<String, MermaidDiagram> {
        val colors = if (theme == ShareCardTheme.Dark) LodyDarkColors else LodyLightColors
        val palette = shareMermaidPalette(colors)
        val diagrams = MermaidDiagrams.get(context)
        val rendered = LinkedHashMap<String, MermaidDiagram>()
        for (message in messages) {
            if (message.role != "assistant") continue
            for (block in MarkdownBlockSplitter.split(message.text)) {
                val source = mermaidSource(block) ?: continue
                val key = palette.key + source
                if (key in rendered) continue
                val diagram = diagrams.cached(source, palette) ?: diagrams.render(source, palette)
                if (diagram != null) rendered[key] = diagram
            }
        }
        return rendered
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * Minimal streaming RGBA PNG encoder (`colorType = 6`, `bitDepth = 8`) backed by `java.util.zip.Deflater`.
 * Encodes scanline-by-scanline so multi-strip 16,384 px exports never allocate a full-height Bitmap.
 */
internal class StreamingPngWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val scanline = ByteArray(1 + width * 4)
    private val deflateBuf = ByteArray(64 * 1024)
    private var rowsWritten = 0
    private var closed = false

    init {
        require(width > 0 && height > 0)
        out.write(PNG_SIGNATURE)
        val ihdr = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8.toByte()) // bit depth
            .put(6.toByte()) // RGBA
            .put(0.toByte()) // deflate
            .put(0.toByte()) // adaptive filter
            .put(0.toByte()) // non-interlaced
            .array()
        writeChunk("IHDR", ihdr, 0, ihdr.size)
    }

    fun writeArgbRow(argbRow: IntArray) {
        check(!closed && rowsWritten < height && argbRow.size >= width)
        // Filter type 1 (Sub) compresses flat card fills and text runs markedly smaller than 0.
        scanline[0] = 1
        var prevR = 0
        var prevG = 0
        var prevB = 0
        var prevA = 0
        var offset = 1
        for (x in 0 until width) {
            val px = argbRow[x]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val a = (px ushr 24) and 0xFF
            scanline[offset] = (r - prevR).toByte()
            scanline[offset + 1] = (g - prevG).toByte()
            scanline[offset + 2] = (b - prevB).toByte()
            scanline[offset + 3] = (a - prevA).toByte()
            prevR = r
            prevG = g
            prevB = b
            prevA = a
            offset += 4
        }
        deflater.setInput(scanline)
        drainDeflater(finish = false)
        rowsWritten++
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (rowsWritten == height) {
                deflater.finish()
                drainDeflater(finish = true)
                writeChunk("IEND", ByteArray(0), 0, 0)
                out.flush()
            }
        } finally {
            deflater.end()
        }
    }

    private fun drainDeflater(finish: Boolean) {
        while (if (finish) !deflater.finished() else !deflater.needsInput()) {
            val count = deflater.deflate(deflateBuf)
            if (count > 0) {
                writeChunk("IDAT", deflateBuf, 0, count)
            } else if (!finish) {
                break
            }
        }
    }

    private fun writeChunk(type: String, data: ByteArray, offset: Int, length: Int) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val lenBytes = ByteBuffer.allocate(4).putInt(length).array()
        out.write(lenBytes)
        out.write(typeBytes)
        if (length > 0) out.write(data, offset, length)
        val crc = CRC32()
        crc.update(typeBytes)
        if (length > 0) crc.update(data, offset, length)
        val crcBytes = ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
        out.write(crcBytes)
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
