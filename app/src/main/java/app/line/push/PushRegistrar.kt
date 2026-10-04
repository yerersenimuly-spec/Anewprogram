package app.line.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Push without Google services: the app registers with a UnifiedPush distributor (for example a self-hosted ntfy),
 * the distributor returns an HTTPS endpoint, and the Line server posts a tiny wake-up hint to it.
 */
object PushRegistrar {
    data class Endpoint(val url: String, val pubKey: String?, val auth: String?)

    private const val MESSAGE_FOR_DISTRIBUTOR = "Line"
    private fun prefs(context: Context) = context.getSharedPreferences("line-push", Context.MODE_PRIVATE)
    private fun fingerprint(endpoint: Endpoint) = "${endpoint.url}|${endpoint.pubKey.orEmpty()}|${endpoint.auth.orEmpty()}"

    fun endpoint(context: Context): Endpoint? {
        val saved = prefs(context)
        val url = saved.getString("endpoint", null) ?: return null
        return Endpoint(url, saved.getString("pub_key", null), saved.getString("auth", null))
    }

    fun store(context: Context, endpoint: Endpoint) {
        prefs(context).edit().putString("endpoint", endpoint.url).putString("pub_key", endpoint.pubKey).putString("auth", endpoint.auth)
            .remove("failure").apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun failed(context: Context, reason: String) {
        prefs(context).edit().putString("failure", reason).apply()
    }

    fun failure(context: Context): String? = prefs(context).getString("failure", null)

    fun isRegistered(context: Context, endpoint: Endpoint): Boolean = prefs(context).getString("registered", null) == fingerprint(endpoint)

    fun markSent(context: Context, endpoint: Endpoint) {
        prefs(context).edit().putString("sent", fingerprint(endpoint)).apply()
    }

    fun acknowledged(context: Context, active: Boolean) {
        val saved = prefs(context)
        if (active) saved.edit().putString("registered", saved.getString("sent", null)).apply()
        else saved.edit().remove("registered").apply()
    }

    fun distributors(context: Context): List<String> = runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())
    fun selectedDistributor(context: Context): String? = runCatching { UnifiedPush.getSavedDistributor(context) }.getOrNull()
    fun hasEndpoint(context: Context): Boolean = endpoint(context) != null

    fun select(context: Context, distributor: String) {
        UnifiedPush.saveDistributor(context, distributor)
        UnifiedPush.register(context, messageForDistributor = MESSAGE_FOR_DISTRIBUTOR)
    }

    /** Re-registers on every start, as the UnifiedPush guide recommends, and forgets a distributor that was uninstalled. */
    fun refresh(context: Context) {
        val saved = selectedDistributor(context) ?: return
        if (saved !in distributors(context)) {
            clear(context)
            runCatching { UnifiedPush.removeDistributor(context) }
            return
        }
        runCatching { UnifiedPush.register(context, messageForDistributor = MESSAGE_FOR_DISTRIBUTOR) }
    }

    fun disable(context: Context) {
        runCatching { UnifiedPush.removeDistributor(context) }
        clear(context)
    }
}
