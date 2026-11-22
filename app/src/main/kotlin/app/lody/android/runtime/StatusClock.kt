package app.lody.android.runtime

/**
 * The server clock as the runtime sees it, for everything Home times against the Status view
 * layout (Later countdowns, the custom wake-up check, read receipts). Each live catalog carries
 * `statusView.nowMs`, the server-clock instant its layout was computed at; the offset to the
 * device clock is taken from the latest one received. Cached catalogs never set it. Until a live
 * catalog arrives the device clock is used, as the runtime does before its first time read.
 */
object StatusClock {
    @Volatile private var offset = 0L

    /** A live catalog computed at [nowMs] (server clock) arrived at [receivedAt] (device clock). */
    fun observe(nowMs: Long?, receivedAt: Long = System.currentTimeMillis()) {
        if (nowMs != null && nowMs > 0) offset = nowMs - receivedAt
    }

    fun now(device: Long = System.currentTimeMillis()): Long = device + offset

    internal fun reset() { offset = 0 }
}
