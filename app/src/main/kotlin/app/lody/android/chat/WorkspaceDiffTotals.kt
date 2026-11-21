package app.lody.android.chat

import app.lody.android.runtime.DiffScope
import app.lody.android.runtime.WorkspaceChange

/**
 * The working tree list's totals, summed from the machine's per-file line stats ([files] also
 * counts binary files, whose ± is zero). Null when any file omitted a side, because a partial
 * total would read as the whole change.
 */
internal data class WorkspaceDiffTotals(val added: Int, val removed: Int, val files: Int)

internal fun workspaceDiffTotals(files: List<WorkspaceChange>): WorkspaceDiffTotals? {
    var added = 0
    var removed = 0
    for (file in files) {
        added += file.added ?: return null
        removed += file.removed ?: return null
    }
    return WorkspaceDiffTotals(added = added, removed = removed, files = files.size)
}

/**
 * The menu's change rows. The live working tree list can add the Uncommitted row before the
 * catalog reports the change, but never removes rows: it covers HEAD against the working tree, so
 * it cannot tell whether the branch also has committed changes.
 */
internal fun workspaceDiffScopes(catalogHasDiff: Boolean, liveFiles: Int): List<DiffScope> = buildList {
    if (catalogHasDiff) add(DiffScope.Branch)
    if (catalogHasDiff || liveFiles > 0) add(DiffScope.Uncommitted)
}
