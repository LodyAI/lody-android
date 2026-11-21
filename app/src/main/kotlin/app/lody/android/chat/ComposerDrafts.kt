package app.lody.android.chat

import android.content.Context
import app.lody.android.BackendEnvironment
import app.lody.android.runtime.MessageQuoteCard
import app.lody.android.runtime.RuntimeScope

/**
 * Device-local composer text, per account, workspace and conversation (or the new-chat page),
 * as iOS keeps its composer drafts: leaving the page, the process dying or a restart keeps what
 * was typed until it is sent, with its quote cards. Picked attachments are content grants that do
 * not outlive the page. Cleared with the rest of the account's local state at logout.
 */
object ComposerDrafts {
    private const val FILE = "composer-drafts-v1"
    const val CREATE = "create"

    fun session(id: String) = "session:$id"

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE + BackendEnvironment.storageSuffix, Context.MODE_PRIVATE)

    private fun key(scope: RuntimeScope, target: String) = "${scope.accountId}\u0000${scope.workspaceId}\u0000$target"

    fun read(context: Context, scope: RuntimeScope, target: String): String =
        preferences(context).getString(key(scope, target), null).orEmpty()

    /** Blank text removes the draft. */
    fun write(context: Context, scope: RuntimeScope, target: String, text: String) {
        val editor = preferences(context).edit()
        if (text.isBlank()) editor.remove(key(scope, target)) else editor.putString(key(scope, target), text)
        editor.apply()
    }

    /** The conversation composer's quote cards (snapshot and comment), in order. */
    fun readQuotes(context: Context, scope: RuntimeScope, target: String): List<MessageQuoteCard> {
        val raw = preferences(context).getString(key(scope, target) + QUOTES, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val card = array.optJSONObject(index) ?: return@mapNotNull null
                val id = card.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val snapshot = (card.opt("snapshot") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MessageQuoteCard(id, snapshot, card.optString("comment"))
            }
        }.getOrDefault(emptyList())
    }

    /** No cards removes them. */
    fun writeQuotes(context: Context, scope: RuntimeScope, target: String, quotes: List<MessageQuoteCard>) {
        val editor = preferences(context).edit()
        if (quotes.isEmpty()) editor.remove(key(scope, target) + QUOTES)
        else editor.putString(key(scope, target) + QUOTES, org.json.JSONArray(quotes.map { org.json.JSONObject().put("id", it.id).put("snapshot", it.snapshot).put("comment", it.comment) }).toString())
        editor.apply()
    }

    private const val QUOTES = "\u0000quotes"

    fun clear(context: Context) { preferences(context).edit().clear().apply() }
}
