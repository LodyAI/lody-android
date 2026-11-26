package app.lody.android.chat

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.lody.android.R
import app.lody.android.designsystem.LodyButton
import app.lody.android.designsystem.LodyIconButton
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodyLoadingIndicator
import app.lody.android.designsystem.LodyRadius
import app.lody.android.designsystem.LodySize
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyStroke
import app.lody.android.designsystem.LodyTheme
import app.lody.android.runtime.ConversationShareData
import app.lody.android.runtime.ShareCardMessage
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class ActiveShareAction { Copy, Export }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatShareImageSheet(
    shareData: ConversationShareData,
    selectedMessages: List<ShareCardMessage>,
    fallbackAgentName: String?,
    canProceed: () -> Boolean,
    onDismiss: () -> Unit,
    onCompleted: (copied: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()

    var destination by rememberSaveable { mutableStateOf(ShareCardDestination.Chat) }
    var mat by rememberSaveable { mutableIntStateOf(ShareCardDestination.Chat.defaultMat) }
    var backdrop by rememberSaveable { mutableStateOf(ShareCardBackdrop.Lody) }
    var cardTheme by rememberSaveable { mutableStateOf(if (systemDark) ShareCardTheme.Dark else ShareCardTheme.Light) }
    var busyAction by remember { mutableStateOf<ActiveShareAction?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val tokensPattern = stringResource(R.string.share_image_tokens)
    val meta = remember(shareData, selectedMessages, fallbackAgentName, tokensPattern) {
        ShareCardModel.buildMeta(
            data = shareData,
            selectedMessages = selectedMessages,
            fallbackAgentName = fallbackAgentName,
            tokensFormat = { compact -> String.format(tokensPattern, compact) },
        )
    }

    val tooLargeText = stringResource(R.string.share_image_too_large)
    val exportFailedText = stringResource(R.string.share_image_export_failed)
    val onCompletedLatest by rememberUpdatedState(onCompleted)
    val onDismissLatest by rememberUpdatedState(onDismiss)

    var pendingShareSession by remember { mutableStateOf<PendingChooserSession?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            pendingShareSession?.let { session ->
                session.unregister(context)
                if (!session.targetChosen) {
                    ChatShareImageExporter.deleteStagedFile(session.file)
                }
            }
            pendingShareSession = null
        }
    }

    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val session = pendingShareSession
        pendingShareSession = null
        if (session != null) {
            session.unregister(context)
            if (session.targetChosen) {
                onCompletedLatest(false)
            } else {
                ChatShareImageExporter.deleteStagedFile(session.file)
            }
        }
    }

    fun performExport(action: ActiveShareAction) {
        if (busyAction != null || selectedMessages.isEmpty()) return
        errorMessage = null
        busyAction = action
        scope.launch {
            val outcome = ChatShareImageExporter.renderToPng(
                context = context,
                messages = selectedMessages,
                title = shareData.title,
                destination = destination,
                mat = mat,
                theme = cardTheme,
                backdrop = backdrop,
                meta = meta,
                canProceed = canProceed,
            )
            busyAction = null
            when (outcome) {
                is ShareImageExportResult.Success -> {
                    if (!canProceed()) {
                        ChatShareImageExporter.deleteStagedFile(outcome.file)
                        onDismissLatest()
                        return@launch
                    }
                    if (action == ActiveShareAction.Copy) {
                        ChatShareImageExporter.copyUriToClipboard(context, outcome.uri, outcome.file.name)
                        onCompletedLatest(true)
                    } else {
                        launchShareChooser(
                            context = context,
                            file = outcome.file,
                            uri = outcome.uri,
                            replacePending = { next ->
                                pendingShareSession?.let { old ->
                                    old.unregister(context)
                                    if (!old.targetChosen) ChatShareImageExporter.deleteStagedFile(old.file)
                                }
                                pendingShareSession = next
                            },
                            onTargetChosen = {
                                val current = pendingShareSession
                                if (current != null) {
                                    current.unregister(context)
                                    pendingShareSession = null
                                }
                                onCompletedLatest(false)
                            },
                            launchIntent = { chooserLauncher.launch(it) },
                            onLaunchError = {
                                ChatShareImageExporter.deleteStagedFile(outcome.file)
                                errorMessage = exportFailedText
                            },
                        )
                    }
                }
                ShareImageExportResult.TooLarge -> {
                    errorMessage = tooLargeText
                }
                ShareImageExportResult.Aborted -> {
                    onDismissLatest()
                }
                ShareImageExportResult.Failed -> {
                    errorMessage = exportFailedText
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (busyAction == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgBase,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .testTag("share-image-sheet"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = LodySpacing.lg, end = LodySpacing.sm, top = LodySpacing.md, bottom = LodySpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.share_image_title),
                    style = type.headline,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                LodyIconButton(
                    onClick = { if (busyAction == null) onDismiss() },
                    enabled = busyAction == null,
                    modifier = Modifier.size(LodySize.controlLg),
                ) {
                    Icon(
                        imageVector = LodyIcons.Close,
                        contentDescription = stringResource(R.string.share_image_cancel),
                        tint = colors.iconSecondary,
                        modifier = Modifier.size(LodyIconSize.md),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(colors.bgGrouped)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LodySpacing.lg, vertical = LodySpacing.lg),
                contentAlignment = Alignment.TopCenter,
            ) {
                ScaledCardPreview(
                    outerWidthDp = ShareCardModel.outerWidthDp(destination, backdrop, mat),
                ) {
                    ChatShareCard(
                        messages = selectedMessages,
                        title = shareData.title,
                        destination = destination,
                        mat = mat,
                        theme = cardTheme,
                        backdrop = backdrop,
                        meta = meta,
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderSubtle))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bgBase)
                    .padding(horizontal = LodySpacing.lg, vertical = LodySpacing.md),
                verticalArrangement = Arrangement.spacedBy(LodySpacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                        Text(stringResource(R.string.share_image_destination), style = type.footnote, color = colors.textSecondary)
                        TwoWaySegment(
                            firstLabel = stringResource(R.string.share_image_destination_chat),
                            secondLabel = stringResource(R.string.share_image_destination_post),
                            firstSelected = destination == ShareCardDestination.Chat,
                            enabled = busyAction == null,
                            onSelectFirst = {
                                if (destination != ShareCardDestination.Chat) {
                                    destination = ShareCardDestination.Chat
                                    mat = ShareCardDestination.Chat.defaultMat
                                    errorMessage = null
                                }
                            },
                            onSelectSecond = {
                                if (destination != ShareCardDestination.Post) {
                                    destination = ShareCardDestination.Post
                                    mat = ShareCardDestination.Post.defaultMat
                                    errorMessage = null
                                }
                            },
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                        Text(stringResource(R.string.share_image_theme), style = type.footnote, color = colors.textSecondary)
                        TwoWaySegment(
                            firstLabel = stringResource(R.string.share_image_theme_light),
                            secondLabel = stringResource(R.string.share_image_theme_dark),
                            firstSelected = cardTheme == ShareCardTheme.Light,
                            enabled = busyAction == null,
                            onSelectFirst = { cardTheme = ShareCardTheme.Light },
                            onSelectSecond = { cardTheme = ShareCardTheme.Dark },
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                    Text(stringResource(R.string.share_image_backdrop), style = type.footnote, color = colors.textSecondary)
                    BackdropSwatchRow(
                        selected = backdrop,
                        enabled = busyAction == null,
                        onSelect = {
                            backdrop = it
                            errorMessage = null
                        },
                    )
                }

                val matEnabled = backdrop != ShareCardBackdrop.None && busyAction == null
                val shownMat = ShareCardModel.effectiveMat(backdrop, mat)
                Column(
                    modifier = Modifier.alpha(if (backdrop == ShareCardBackdrop.None) 0.45f else 1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.share_image_padding), style = type.footnote, color = colors.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.share_image_padding_value, shownMat),
                            style = type.footnote.copy(fontFeatureSettings = "tnum"),
                            color = colors.textSecondary,
                        )
                    }
                    Slider(
                        value = mat.toFloat(),
                        onValueChange = { raw ->
                            mat = ShareCardModel.snapMat(raw)
                            errorMessage = null
                        },
                        valueRange = ShareCardModel.MIN_MAT.toFloat()..ShareCardModel.MAX_MAT.toFloat(),
                        steps = (ShareCardModel.MAX_MAT - ShareCardModel.MIN_MAT) / ShareCardModel.MAT_STEP - 1,
                        enabled = matEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentSolid,
                            activeTrackColor = colors.accentSolid,
                            inactiveTrackColor = colors.fillSecondary,
                        ),
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = type.footnote,
                        color = colors.dangerDefault,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LodyButton(
                        onClick = { performExport(ActiveShareAction.Copy) },
                        enabled = busyAction == null && selectedMessages.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.fillSecondary,
                            contentColor = colors.textPrimary,
                            disabledContainerColor = colors.fillDisabled,
                            disabledContentColor = colors.textDisabled,
                        ),
                        contentPadding = PaddingValues(horizontal = LodySpacing.md, vertical = LodySpacing.sm),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = LodySize.controlLg)
                            .testTag("share-image-copy"),
                    ) {
                        if (busyAction == ActiveShareAction.Copy) {
                            LodyLoadingIndicator(Modifier.size(LodyIconSize.sm), color = colors.textPrimary)
                        } else {
                            Icon(LodyIcons.Copy, contentDescription = null, modifier = Modifier.size(LodyIconSize.sm))
                        }
                        Spacer(Modifier.width(LodySpacing.xs))
                        Text(stringResource(R.string.share_image_copy), style = type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    LodyButton(
                        onClick = { performExport(ActiveShareAction.Export) },
                        enabled = busyAction == null && selectedMessages.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentSolid,
                            contentColor = colors.textOnAccent,
                            disabledContainerColor = colors.fillDisabled,
                            disabledContentColor = colors.textDisabled,
                        ),
                        contentPadding = PaddingValues(horizontal = LodySpacing.md, vertical = LodySpacing.sm),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = LodySize.controlLg)
                            .testTag("share-image-export"),
                    ) {
                        if (busyAction == ActiveShareAction.Export) {
                            LodyLoadingIndicator(Modifier.size(LodyIconSize.sm), color = colors.textOnAccent)
                        } else {
                            Icon(LodyIcons.Share, contentDescription = null, modifier = Modifier.size(LodyIconSize.sm))
                        }
                        Spacer(Modifier.width(LodySpacing.xs))
                        Text(stringResource(R.string.share_image_export), style = type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Measures the native share card at its fixed logical width and scales it down to fit the preview viewport. */
@Composable
private fun ScaledCardPreview(
    outerWidthDp: Int,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val cardWidthPx = outerWidthDp.dp.roundToPx().coerceAtLeast(1)
        val availableWidthPx = constraints.maxWidth.coerceAtLeast(1)
        val placeable = subcompose("card", content).first().measure(
            Constraints(minWidth = cardWidthPx, maxWidth = cardWidthPx),
        )
        val fitScale = minOf(1f, availableWidthPx.toFloat() / cardWidthPx.toFloat())
        val scaledW = (placeable.width * fitScale).roundToInt().coerceAtLeast(1)
        val scaledH = (placeable.height * fitScale).roundToInt().coerceAtLeast(1)
        val offsetX = ((availableWidthPx - scaledW) / 2).coerceAtLeast(0)
        layout(availableWidthPx, scaledH) {
            placeable.placeWithLayer(offsetX, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = fitScale
                scaleY = fitScale
            }
        }
    }
}

@Composable
private fun TwoWaySegment(
    firstLabel: String,
    secondLabel: String,
    firstSelected: Boolean,
    enabled: Boolean,
    onSelectFirst: () -> Unit,
    onSelectSecond: () -> Unit,
) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val trayShape = RoundedCornerShape(LodyRadius.md)
    val itemShape = RoundedCornerShape(LodyRadius.md - 2.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(trayShape)
            .background(colors.fillSecondary)
            .padding(2.dp),
    ) {
        SegmentButton(
            label = firstLabel,
            selected = firstSelected,
            enabled = enabled,
            shape = itemShape,
            onClick = onSelectFirst,
            modifier = Modifier.weight(1f),
        )
        SegmentButton(
            label = secondLabel,
            selected = !firstSelected,
            enabled = enabled,
            shape = itemShape,
            onClick = onSelectSecond,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LodyTheme.colors
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) colors.bgRaised else Color.Transparent)
            .then(
                if (selected) Modifier.border(0.5.dp, colors.borderSubtle, shape) else Modifier,
            )
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LodyTheme.typography.subhead,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackdropSwatchRow(
    selected: ShareCardBackdrop,
    enabled: Boolean,
    onSelect: (ShareCardBackdrop) -> Unit,
) {
    val colors = LodyTheme.colors
    val items = listOf(
        ShareCardBackdrop.None to stringResource(R.string.share_image_backdrop_none),
        ShareCardBackdrop.Lody to stringResource(R.string.share_image_backdrop_lody),
        ShareCardBackdrop.Welcome to stringResource(R.string.share_image_backdrop_welcome),
        ShareCardBackdrop.Aurora to stringResource(R.string.share_image_backdrop_aurora),
        ShareCardBackdrop.Ocean to stringResource(R.string.share_image_backdrop_ocean),
        ShareCardBackdrop.Sunset to stringResource(R.string.share_image_backdrop_sunset),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (item, label) ->
            val isChosen = item == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(item) }
                    .semantics {
                        contentDescription = label
                        this.selected = isChosen
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .then(
                            if (isChosen) {
                                Modifier.border(2.dp, colors.accentDefault, CircleShape).padding(3.dp)
                            } else {
                                Modifier.border(1.dp, colors.borderSubtle, CircleShape)
                            },
                        )
                        .clip(CircleShape)
                        .then(
                            if (item == ShareCardBackdrop.None) {
                                Modifier.background(colors.bgRaised)
                            } else {
                                Modifier.shareBackdrop(item)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item == ShareCardBackdrop.None) {
                        val slashColor = colors.textTertiary
                        Canvas(Modifier.size(16.dp)) {
                            drawLine(
                                color = slashColor,
                                start = Offset(size.width * 0.2f, size.height * 0.8f),
                                end = Offset(size.width * 0.8f, size.height * 0.2f),
                                strokeWidth = LodyStroke.strong.toPx(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private class PendingChooserSession(
    val file: File,
    private val receiver: BroadcastReceiver,
) {
    @Volatile var targetChosen: Boolean = false
    private var unregistered = false

    fun unregister(context: Context) {
        if (unregistered) return
        unregistered = true
        runCatching { context.unregisterReceiver(receiver) }
    }
}

private fun launchShareChooser(
    context: Context,
    file: File,
    uri: android.net.Uri,
    replacePending: (PendingChooserSession) -> Unit,
    onTargetChosen: () -> Unit,
    launchIntent: (Intent) -> Unit,
    onLaunchError: () -> Unit,
) {
    val token = UUID.randomUUID().toString()
    val action = "${context.packageName}.CHAT_SHARE_CHOSEN.$token"
    var sessionRef: PendingChooserSession? = null
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            sessionRef?.targetChosen = true
            onTargetChosen()
        }
    }
    val session = PendingChooserSession(file = file, receiver = receiver)
    sessionRef = session

    try {
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        replacePending(session)

        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)

        val callbackFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val chooser = runCatching {
            val callbackIntent = Intent(action).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, token.hashCode(), callbackIntent, callbackFlags)
            Intent.createChooser(send, null, pendingIntent.intentSender)
        }.getOrElse {
            Intent.createChooser(send, null)
        }
        launchIntent(chooser)
    } catch (_: Throwable) {
        session.unregister(context)
        onLaunchError()
    }
}
