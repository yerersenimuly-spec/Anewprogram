package app.line.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

object PushConfiguration {
    private const val PREFERENCES = "line-push"
    private const val PROJECT_ID = "project_id"
    private const val PROJECT_NUMBER = "project_number"
    private const val APP_ID = "mobilesdk_app_id"
    private const val API_KEY = "api_key"
    private const val REGISTRATION_TOKEN = "registration_token"
    private val initializationLock = Any()

    private data class Values(
        val projectId: String,
        val projectNumber: String,
        val appId: String,
        val apiKey: String,
    )

    /** Imports the public Android google-services.json contents, not server credentials. */
    fun `import`(context: Context, json: String) {
        val root = JSONObject(json)
        require(!containsServiceAccountMaterial(root)) { "Use the Firebase Android configuration, not a service-account key" }

        val project = root.optJSONObject("project_info")
            ?: throw IllegalArgumentException("Firebase project_info is missing")
        val projectId = project.optString("project_id").trim()
        val projectNumber = project.optString("project_number").trim()
        require(projectId.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]"))) { "Firebase project_id is invalid" }
        require(projectNumber.matches(Regex("[0-9]{6,24}"))) { "Firebase project_number is invalid" }

        val clients = root.optJSONArray("client")
            ?: throw IllegalArgumentException("Firebase Android client is missing")
        val matchingClients = (0 until clients.length()).mapNotNull { clients.optJSONObject(it) }
            .filter { it.optJSONObject("client_info")?.optJSONObject("android_client_info")
                ?.optString("package_name") == "app.line" }
        val client = matchingClients.singleOrNull()
            ?: throw IllegalArgumentException("Config must contain exactly one Android client for app.line")
        val appId = client.optJSONObject("client_info")?.optString("mobilesdk_app_id")?.trim().orEmpty()
        require(appId.matches(Regex("1:${Regex.escape(projectNumber)}:android:[A-Za-z0-9_-]+"))) {
            "Firebase mobilesdk_app_id is invalid"
        }

        val keys = client.optJSONArray("api_key")
            ?: throw IllegalArgumentException("Firebase Android api_key is missing")
        val currentKeys = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .map { it.optString("current_key").trim() }
            .filter { it.length in 20..256 && it.startsWith("AIza") && !it.containsWhitespace() }
        val apiKey = currentKeys.singleOrNull()
            ?: throw IllegalArgumentException("Config must contain exactly one valid Android current_key")

        val values = Values(projectId, projectNumber, appId, apiKey)
        preferences(context).edit()
            .putString(PROJECT_ID, values.projectId)
            .putString(PROJECT_NUMBER, values.projectNumber)
            .putString(APP_ID, values.appId)
            .putString(API_KEY, values.apiKey)
            .remove(REGISTRATION_TOKEN)
            .apply()
        initializeDefaultFirebase(context)
    }

    fun isConfigured(context: Context): Boolean = readValues(context) != null

    /** Returns a safe status string; Firebase API keys and registration tokens are never included. */
    fun status(context: Context): String? = when {
        !isConfigured(context) -> "Firebase Android configuration is not imported"
        initializeDefaultFirebase(context) -> "Firebase client is configured; backend delivery is not verified"
        else -> "Firebase configuration saved; restart Line to apply it"
    }

    fun initializeDefaultFirebase(context: Context): Boolean = synchronized(initializationLock) {
        val values = readValues(context) ?: return@synchronized false
        try {
            val appContext = context.applicationContext
            val existing = FirebaseApp.getApps(appContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (existing != null) {
                val options = existing.options
                return@synchronized options.projectId == values.projectId &&
                    options.gcmSenderId == values.projectNumber &&
                    options.applicationId == values.appId && options.apiKey == values.apiKey
            }

            FirebaseApp.initializeApp(appContext, FirebaseOptions.Builder()
                .setProjectId(values.projectId)
                .setGcmSenderId(values.projectNumber)
                .setApplicationId(values.appId)
                .setApiKey(values.apiKey)
                .build())
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun token(context: Context): String? {
        if (!initializeDefaultFirebase(context)) return null
        return try {
            val result = suspendCancellableCoroutine<String?> { continuation ->
                try {
                    FirebaseMessaging.getInstance().getToken().addOnCompleteListener { task ->
                        if (!continuation.isActive) return@addOnCompleteListener
                        continuation.resume(if (task.isSuccessful) task.result?.takeIf(String::isNotBlank) else null)
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            rememberToken(context, result)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    internal fun rememberToken(context: Context, token: String?) {
        if (token.isNullOrBlank() || token.length > 4096) return
        preferences(context).edit().putString(REGISTRATION_TOKEN, token).apply()
    }

    private fun readValues(context: Context): Values? {
        val prefs = preferences(context)
        val values = Values(
            prefs.getString(PROJECT_ID, "").orEmpty(),
            prefs.getString(PROJECT_NUMBER, "").orEmpty(),
            prefs.getString(APP_ID, "").orEmpty(),
            prefs.getString(API_KEY, "").orEmpty(),
        )
        return values.takeIf {
            it.projectId.isNotBlank() && it.projectNumber.isNotBlank() &&
                it.appId.isNotBlank() && it.apiKey.isNotBlank()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun containsServiceAccountMaterial(value: Any?): Boolean = when (value) {
        is JSONObject -> {
            val keys = value.keys()
            var found = false
            while (keys.hasNext() && !found) {
                val key = keys.next()
                found = key.lowercase() in setOf("private_key", "privatekey", "private_key_id", "client_email") ||
                    containsServiceAccountMaterial(value.opt(key))
            }
            found
        }
        is JSONArray -> (0 until value.length()).any { containsServiceAccountMaterial(value.opt(it)) }
        else -> false
    }

    private fun String.containsWhitespace() = any(Char::isWhitespace)
}
