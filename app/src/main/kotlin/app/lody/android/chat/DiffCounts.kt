package app.lody.android.chat

/**
 * The +/− on a diff page's header: the diff's own counts once it loads, so a file the list did
 * not count still shows totals; the list's counts are the fallback. Both halves of a pair are
 * required, so the header never shows half a count.
 */
internal fun diffCounts(
    listedAdded: Int?,
    listedRemoved: Int?,
    loadedAdded: Int?,
    loadedRemoved: Int?,
): Pair<Int, Int>? = when {
    loadedAdded != null && loadedRemoved != null -> loadedAdded to loadedRemoved
    listedAdded != null && listedRemoved != null -> listedAdded to listedRemoved
    else -> null
}
