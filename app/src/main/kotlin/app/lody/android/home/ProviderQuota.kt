package app.lody.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import app.lody.android.R
import app.lody.android.chat.ResetTime
import app.lody.android.chat.usageWindowName
import app.lody.android.chat.usageWindowShortName
import app.lody.android.designsystem.*
import app.lody.android.runtime.UsageQuota
import app.lody.android.runtime.UsageWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Remaining at or below this percent reads as a warning, as on Web. */
private const val QUOTA_WARNING_PERCENT = 20.0

private fun UsageWindow.remaining() = (100 - usedPercent).coerceIn(0.0, 100.0)

/** Every window of the provider's quotas, tightest (least remaining) first. */
internal fun tightestQuotaWindows(quotas: List<UsageQuota>): List<UsageWindow> =
    quotas.flatMap { it.windows }.sortedBy { it.remaining() }

@Composable
private fun quotaInk(window: UsageWindow): Color =
    if (window.remaining() <= QUOTA_WARNING_PERCENT) LodyTheme.colors.warningDefault else LodyTheme.colors.textSecondary

@Composable
private fun tabular(style: TextStyle) = style.copy(fontFeatureSettings = "tnum")

/** `Remaining  5h ▬ 82%  7d ▬ 40%  +1`: the two tightest windows; tapping opens [ProviderQuotaSheet]. */
@Composable
internal fun ProviderQuotaLine(quotas: List<UsageQuota>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val windows = tightestQuotaWindows(quotas)
    if (windows.isEmpty()) return
    val colors = LodyTheme.colors
    val caption = LodyTheme.typography.caption
    val description = stringResource(R.string.settings_agent_quota_open)
    Row(modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(vertical = LodySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
        Text(stringResource(R.string.settings_agent_quota_remaining), style = caption, color = colors.textSecondary)
        windows.take(2).forEach { window ->
            val ink = quotaInk(window)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                Text(window.duration?.let { usageWindowShortName(it) } ?: window.label ?: stringResource(R.string.usage_title), style = caption, color = colors.textSecondary, maxLines = 1)
                Box(Modifier.width(LodySize.controlSm).height(LodySize.progress).background(colors.fillSecondary, CircleShape)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((window.remaining() / 100).toFloat()).background(ink, CircleShape))
                }
                Text("${window.remaining().roundToInt()}%", style = tabular(caption), color = ink)
            }
        }
        if (windows.size > 2) Text(stringResource(R.string.settings_agent_quota_more, windows.size - 2), style = tabular(caption), color = colors.textSecondary)
    }
}

/**
 * Every reported window of one provider, grouped by quota (model/bucket). [refresh] runs the
 * provider's `rate-limits-refresh` operation and is null when it is unsupported or not allowed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderQuotaSheet(providerName: String, quotas: List<UsageQuota>, offline: Boolean, refresh: (suspend () -> Boolean)?, onDismiss: () -> Unit) {
    val colors = LodyTheme.colors
    val tasks = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.bgBase) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = LodyLayout.screenPaddingX).padding(bottom = LodySpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(LodySpacing.lg)) {
            Text(stringResource(R.string.settings_agent_quota_title, providerName), style = LodyTheme.typography.headline, color = colors.textPrimary)
            quotas.forEach { quota ->
                Column(verticalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
                    if (quota.name != null || quotas.size > 1) Text(quota.name ?: stringResource(R.string.usage_model), style = LodyTheme.typography.footnote, color = colors.textSecondary)
                    if (quota.windows.isEmpty()) Text(stringResource(R.string.usage_unavailable), style = LodyTheme.typography.footnote, color = colors.textSecondary)
                    quota.windows.sortedByDescending { it.duration ?: 0.0 }.forEach { window ->
                        val ink = quotaInk(window)
                        Column(verticalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(listOfNotNull(usageWindowName(window.duration), window.label).joinToString(" · "), Modifier.weight(1f), style = LodyTheme.typography.body, color = colors.textPrimary)
                                Text(stringResource(R.string.settings_agent_quota_left, window.remaining().roundToInt()), style = tabular(LodyTheme.typography.footnote), color = ink)
                            }
                            LinearProgressIndicator(progress = { (window.remaining() / 100).toFloat() }, modifier = Modifier.fillMaxWidth(), color = ink, trackColor = colors.fillSecondary)
                            window.resetsAt?.let { ResetTime(it) }
                        }
                    }
                }
            }
            Text(stringResource(if (offline) R.string.settings_agent_quota_offline else R.string.settings_agent_quota_shared), style = LodyTheme.typography.footnote, color = colors.textSecondary)
            if (failed) Text(stringResource(R.string.settings_agent_operation_failed), style = LodyTheme.typography.footnote, color = colors.textSecondary)
            if (refresh != null) OutlinedButton(enabled = !refreshing, modifier = Modifier.fillMaxWidth(), onClick = {
                tasks.launch {
                    refreshing = true; failed = false
                    failed = try { !refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { true }
                    finally { refreshing = false }
                }
            }) {
                if (refreshing) CircularProgressIndicator(Modifier.size(LodyIconSize.sm), color = colors.textSecondary, strokeWidth = LodyStroke.strong)
                else Text(stringResource(R.string.settings_agent_refresh_usage))
            }
        }
    }
}
