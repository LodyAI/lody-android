package app.lody.android.chat

import app.lody.android.designsystem.LodyIconButton as IconButton
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import app.lody.android.R
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodyLoadingIndicator
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyTheme
import app.lody.android.runtime.RuntimeScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A file a turn carries: an agent's shared file (`lody_upload_files`, web `SessionFileCard`) or
 * a user's attachment. Only uploaded files (`transport` absent or `r2`) can be downloaded.
 */
data class SessionFileRef(
    val id: String,
    val fileName: String,
    val storageSessionId: String? = null,
    val transport: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
) {
    val downloadable: Boolean get() = id.isNotEmpty() && (transport == null || transport == "r2")
    val markdown: Boolean get() = ProjectFiles.isMarkdown(fileName)
}

/** A session file that could not be downloaded; [code] picks its message ([SessionFiles.message]). */
class SessionFileFailure(val code: String) : Exception(code)

/** Downloads one session file into [directory] with the verified account. */
fun interface SessionFileReader {
    suspend fun download(scope: RuntimeScope, sessionId: String, file: SessionFileRef, directory: File): File
}

/**
 * iOS `SessionAttachments.download`, natively: a GET to the fixed Worker session-files endpoint
 * with the account bearer, no redirects or cookies, at most 100 MiB and the listed size.
 */
internal class SessionFileHttp {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    suspend fun download(token: String, workspace: String, session: String, file: SessionFileRef, directory: File): File {
        if (!file.downloadable) throw SessionFileFailure(if (file.id.isEmpty()) "invalid" else "pending")
        val expected = file.sizeBytes
        if (expected != null && (expected <= 0 || expected > LIMIT)) throw SessionFileFailure("too_large")
        fun segment(value: String) = SessionAttachmentUploads.segment(value)
        val url = "${app.lody.android.BackendEnvironment.worker}/api/workspaces/${segment(workspace)}/session-files/${segment(session)}/${segment(file.id)}"
        val request = Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()
        val call = client.newCall(request)
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(SessionFileFailure("download"))
                override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            })
        }
        return withContext(Dispatchers.IO) {
            response.use {
                when (it.code) {
                    200 -> Unit
                    404, 410 -> throw SessionFileFailure("unavailable")
                    401, 403 -> throw SessionFileFailure("permission")
                    else -> throw SessionFileFailure("download")
                }
                val body = it.body
                if (body.contentLength() > LIMIT) throw SessionFileFailure("too_large")
                directory.mkdirs()
                val target = File(directory, SessionFiles.safeName(file.fileName))
                var count = 0L
                try {
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                if (count > LIMIT) throw SessionFileFailure("too_large")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (failure: SessionFileFailure) {
                    target.delete(); throw failure
                } catch (_: IOException) {
                    target.delete(); throw SessionFileFailure("download")
                }
                if (count <= 0 || (expected != null && expected != count)) { target.delete(); throw SessionFileFailure("download") }
                target
            }
        }
    }

    private companion object {
        const val LIMIT = 100L * 1024 * 1024
    }
}

internal object SessionFiles {
    /** The file's last path component; never empty, `.` or `..`. */
    fun safeName(fileName: String): String {
        val name = fileName.replace('\\', '/').substringAfterLast('/')
        return if (name.isEmpty() || name == "." || name == "..") "file" else name
    }

    fun mimeType(file: SessionFileRef): String =
        file.mimeType?.takeIf { '/' in it }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** UTF-8 text, or null when the bytes are not valid UTF-8 (the sheet then reports a failure). */
    fun text(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    fun message(code: String?): Int = when (code) {
        "unavailable" -> R.string.chat_file_error_unavailable
        "permission" -> R.string.chat_file_error_permission
        "too_large" -> R.string.chat_file_error_too_large
        "pending" -> R.string.chat_file_error_pending
        "not_text" -> R.string.files_error_binary
        else -> R.string.chat_file_error_download
    }

    /**
     * A fresh private cache directory behind `res/xml/shared_images.xml` (`session-files/`). Copies
     * handed to another app outlive this page, so ones older than a day are removed here.
     */
    fun directory(context: Context): File {
        val root = File(context.cacheDir, "session-files")
        val stale = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.deleteRecursively() }
        return File(root, UUID.randomUUID().toString())
    }

    fun uri(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.images", file)

    fun share(context: Context, file: File, mime: String) {
        val uri = uri(context, file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, null))
    }
}

/**
 * Opens a conversation's files (iOS `LodyChatView.openAttachment`): Markdown in
 * [MarkdownAttachmentSheet], any other file downloaded and handed to an app that views it.
 */
@Stable
class SessionFileOpener internal constructor(
    private val context: Context,
    private val coroutines: CoroutineScope,
    private val reader: SessionFileReader,
    private val scope: RuntimeScope,
    private val sessionId: String,
) {
    /** The Markdown file the sheet shows. */
    var markdown by mutableStateOf<SessionFileRef?>(null)
    /** Files being downloaded for another app, by id. */
    var busy by mutableStateOf(emptySet<String>())
        private set

    fun open(file: SessionFileRef) {
        if (file.markdown) { markdown = file; return }
        if (file.id in busy) return
        if (!file.downloadable) { toast(SessionFiles.message("pending")); return }
        busy = busy + file.id
        coroutines.launch {
            try {
                val downloaded = download(file, SessionFiles.directory(context))
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(SessionFiles.uri(context, downloaded), SessionFiles.mimeType(file))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: ActivityNotFoundException) { toast(R.string.chat_file_no_app) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toast(SessionFiles.message((error as? SessionFileFailure)?.code)) }
            finally { busy = busy - file.id }
        }
    }

    suspend fun download(file: SessionFileRef, directory: File): File = reader.download(scope, sessionId, file, directory)

    private fun toast(text: Int) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

@Composable
fun rememberSessionFileOpener(reader: SessionFileReader, scope: RuntimeScope, sessionId: String): SessionFileOpener {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    return remember(reader, scope, sessionId) { SessionFileOpener(context, coroutines, reader, scope, sessionId) }
}

/** The conversation's file opener; null outside a conversation (previews, probes). */
val LocalSessionFiles = staticCompositionLocalOf<SessionFileOpener?> { null }

private sealed interface FileLoad {
    data object Loading : FileLoad
    data class Failed(val code: String?) : FileLoad
    data class Ready(val file: File, val blocks: List<String>) : FileLoad
}

/**
 * iOS `ChatMarkdownAttachmentSheet`: a Markdown attachment rendered natively in a sheet, with
 * Save (a document the reader picks) and Share of the original file once it has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownAttachmentSheet(file: SessionFileRef, opener: SessionFileOpener, onDismiss: () -> Unit) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val directory = remember(file) { SessionFiles.directory(context) }
    DisposableEffect(directory) { onDispose { directory.deleteRecursively() } }
    var attempt by remember(file) { mutableIntStateOf(0) }
    var state by remember(file) { mutableStateOf<FileLoad>(FileLoad.Loading) }
    LaunchedEffect(file, attempt) {
        state = FileLoad.Loading
        state = try {
            val downloaded = opener.download(file, directory)
            val blocks = withContext(Dispatchers.Default) {
                SessionFiles.text(downloaded.readBytes())?.let(MarkdownBlockSplitter::split)
            }
            if (blocks == null) FileLoad.Failed("not_text") else FileLoad.Ready(downloaded, blocks)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { FileLoad.Failed((error as? SessionFileFailure)?.code) }
    }
    val ready = state as? FileLoad.Ready
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SessionFiles.mimeType(file))) { uri ->
        val source = ready?.file
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        coroutines.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } } != null }.getOrDefault(false)
            }
            Toast.makeText(context, if (saved) R.string.chat_file_saved else R.string.chat_file_save_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.bgBase) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(start = TranscriptMargin, end = LodySpacing.sm, bottom = LodySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(file.fileName, style = type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { save.launch(SessionFiles.safeName(file.fileName)) }, enabled = ready != null) {
                    Icon(LodyIcons.Download, contentDescription = stringResource(R.string.chat_file_save), tint = if (ready != null) colors.iconPrimary else colors.iconTertiary, modifier = Modifier.size(LodyIconSize.md))
                }
                IconButton(onClick = { ready?.let { SessionFiles.share(context, it.file, SessionFiles.mimeType(file)) } }, enabled = ready != null) {
                    Icon(LodyIcons.Share, contentDescription = stringResource(R.string.chat_file_share), tint = if (ready != null) colors.iconPrimary else colors.iconTertiary, modifier = Modifier.size(LodyIconSize.md))
                }
            }
            when (val current = state) {
                FileLoad.Loading -> Box(Modifier.fillMaxWidth().padding(LodySpacing.xl), contentAlignment = Alignment.Center) {
                    LodyLoadingIndicator(Modifier.size(LodyIconSize.md), color = colors.iconTertiary)
                }
                is FileLoad.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = TranscriptMargin, vertical = LodySpacing.lg), verticalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                    Text(stringResource(SessionFiles.message(current.code)), style = type.subhead, color = colors.textSecondary)
                    // A file still on the computer cannot be read by retrying.
                    if (file.downloadable && current.code != "not_text") TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.files_retry)) }
                }
                is FileLoad.Ready -> if (current.blocks.isEmpty()) {
                    Text(stringResource(R.string.files_empty_file), style = type.subhead, color = colors.textSecondary, modifier = Modifier.padding(horizontal = TranscriptMargin, vertical = LodySpacing.lg))
                } else SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = TranscriptMargin, end = TranscriptMargin, bottom = LodySpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(LodySpacing.sm),
                    ) {
                        itemsIndexed(current.blocks) { _, block -> MarkdownBlock(block) }
                    }
                }
            }
        }
    }
}
