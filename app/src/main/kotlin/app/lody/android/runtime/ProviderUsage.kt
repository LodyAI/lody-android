package app.lody.android.runtime

import org.json.JSONObject

/** Runtime owns capability/auth eligibility. Never match quota by display name. */
/** [name] is the Agent Config's configured name, synced whether or not the computer is online. */
data class ProviderUsageConfig(val id: String, val provider: String, val eligible: Boolean, val rateLimitsAvailable: Boolean = false, val name: String = "")
data class ProviderQuota(val provider: String, val agentConfigId: String?, val quota: UsageQuota, val id: String = "")
data class ProviderUsage(val configs: List<ProviderUsageConfig>, val quotas: List<ProviderQuota>) {
    fun forProvider(provider: AgentProvider): List<UsageQuota> {
        val config = configs.firstOrNull { it.id == provider.id && it.eligible } ?: return emptyList()
        if (provider.agentType != config.provider) return emptyList()
        return quotas.filter { it.provider == config.provider && (it.agentConfigId == null || it.agentConfigId == provider.id) }.map { it.quota }
    }

    /**
     * The exact config's subscription windows for [model], as web
     * `resolveAgentRateLimitForModel` and iOS `SessionUsageSelection`: requires eligibility and the rate-limits capability, and
     * never substitutes another configured account.
     */
    fun forSelection(configId: String?, provider: String, model: String?): UsageQuota? {
        val config = configs.firstOrNull { it.id == configId } ?: return null
        if (config.provider != provider || !config.eligible || !config.rateLimitsAvailable) return null
        val entries = quotas.filter { it.provider == provider && it.agentConfigId == configId }
        val wanted = normalize(model)
        if (wanted.isNotEmpty()) {
            entries.filter {
                val name = normalize(it.quota.name)
                name.isNotEmpty() && (name == wanted || (name.length >= 4 && wanted.contains(name)) || (wanted.length >= 4 && name.contains(wanted)))
            }.maxByOrNull { normalize(it.quota.name).length }?.let { return it.quota }
            entries.firstOrNull { normalize(it.id) == wanted }?.let { return it.quota }
            if (provider == "codex" && wanted.contains("spark")) {
                return entries.firstOrNull { it.id == "codex_bengalfox" || normalize(it.quota.name).contains("spark") }?.quota
            }
        }
        entries.firstOrNull { it.id.isEmpty() || it.id == provider }?.let { return it.quota }
        return if (wanted.isEmpty()) entries.firstOrNull()?.quota else null
    }

    private fun normalize(value: String?) = value.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "")
}

internal object ProviderUsageJson {
    fun parse(catalog: JSONObject): Map<String, ProviderUsage> {
        val machines = catalog.optJSONObject("agentUsage") ?: return emptyMap()
        return machines.keys().asSequence().mapNotNull { machine ->
            val value = machines.optJSONObject(machine) ?: return@mapNotNull null
            val configs = value.optJSONArray("configs")
            val quotas = value.optJSONArray("quotas")
            machine to ProviderUsage(
                (0 until (configs?.length() ?: 0)).mapNotNull { i ->
                    val c = configs?.optJSONObject(i) ?: return@mapNotNull null
                    val id = c.opt("id") as? String ?: return@mapNotNull null
                    val provider = c.opt("provider") as? String ?: return@mapNotNull null
                    ProviderUsageConfig(id, provider, c.opt("eligible") == true, c.opt("rateLimitsAvailable") == true, c.opt("name") as? String ?: "")
                },
                (0 until (quotas?.length() ?: 0)).mapNotNull { i ->
                    val q = quotas?.optJSONObject(i) ?: return@mapNotNull null
                    val provider = q.opt("provider") as? String ?: return@mapNotNull null
                    val quota = SessionUsageJson.parse(JSONObject().put("quotaEligible", true).put("quota", q))?.quota ?: return@mapNotNull null
                    ProviderQuota(provider, (q.opt("agentConfigId") as? String)?.takeIf { it.isNotBlank() }, quota, q.opt("id") as? String ?: "")
                })
        }.toMap()
    }
}
