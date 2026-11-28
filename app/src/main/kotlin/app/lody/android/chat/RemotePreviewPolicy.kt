package app.lody.android.chat

import app.lody.android.runtime.DevicePlatform

internal enum class RemotePreviewTarget {
    BROWSER,
    IOS_SIMULATOR,
    ANDROID_EMULATOR,
}

internal sealed interface RemotePreviewDecision {
    data object OpenBrowser : RemotePreviewDecision
    data object BrowserEmpty : RemotePreviewDecision
    data class OpenDevice(val platform: DevicePlatform) : RemotePreviewDecision
    data object RequiresMacOS : RemotePreviewDecision
    data class UpgradeRequired(val target: RemotePreviewTarget) : RemotePreviewDecision
    data object OwnerOnly : RemotePreviewDecision
    data object MachineOffline : RemotePreviewDecision
}

/** Pure eligibility and routing rules for the conversation More menu's Remote preview submenu. */
internal object RemotePreviewPolicy {
    fun isIosSimulatorDimmed(machineSimulator: String?): Boolean = machineSimulator == null

    fun isBrowserServerStoppedError(errorCode: String?): Boolean =
        errorCode == "port_not_listening" || errorCode == "local_server_unreachable"

    /** Appends a prompt paragraph without discarding existing composer text. */
    fun appendDraftPrompt(existing: String, addition: String): String {
        val trimmedAddition = addition.trim()
        if (trimmedAddition.isEmpty()) return existing
        return if (existing.isBlank()) trimmedAddition else "${existing.trimEnd()}\n\n$trimmedAddition"
    }

    fun evaluate(
        target: RemotePreviewTarget,
        sessionUserId: String?,
        currentUserId: String?,
        machineSimulator: String?,
        machineEmulator: Boolean,
        machineCapabilities: Map<String, Int>,
        machineOnline: Boolean?,
        hasBrowserCandidate: Boolean,
        hasCachedBrowser: Boolean,
        iosRunning: Boolean,
        androidRunning: Boolean,
    ): RemotePreviewDecision {
        // Non-macOS computers always explain why the dimmed iOS Simulator entry cannot start.
        if (target == RemotePreviewTarget.IOS_SIMULATOR && isIosSimulatorDimmed(machineSimulator)) {
            return RemotePreviewDecision.RequiresMacOS
        }

        if (!sessionUserId.isNullOrEmpty() && !currentUserId.isNullOrEmpty() && sessionUserId != currentUserId) {
            return RemotePreviewDecision.OwnerOnly
        }

        return when (target) {
            RemotePreviewTarget.BROWSER -> when {
                !hasBrowserCandidate && machineCapabilities.isNotEmpty() && (machineCapabilities["previewControl"] ?: 0) < 1 ->
                    RemotePreviewDecision.UpgradeRequired(RemotePreviewTarget.BROWSER)
                hasBrowserCandidate ->
                    if (!hasCachedBrowser && machineOnline == false) RemotePreviewDecision.MachineOffline
                    else RemotePreviewDecision.OpenBrowser
                else -> RemotePreviewDecision.BrowserEmpty
            }

            RemotePreviewTarget.IOS_SIMULATOR -> when {
                machineSimulator == "upgrade-required" ->
                    RemotePreviewDecision.UpgradeRequired(RemotePreviewTarget.IOS_SIMULATOR)
                machineOnline == false && !iosRunning -> RemotePreviewDecision.MachineOffline
                else -> RemotePreviewDecision.OpenDevice(DevicePlatform.IOS)
            }

            RemotePreviewTarget.ANDROID_EMULATOR -> when {
                !machineEmulator -> RemotePreviewDecision.UpgradeRequired(RemotePreviewTarget.ANDROID_EMULATOR)
                machineOnline == false && !androidRunning -> RemotePreviewDecision.MachineOffline
                else -> RemotePreviewDecision.OpenDevice(DevicePlatform.ANDROID)
            }
        }
    }
}
