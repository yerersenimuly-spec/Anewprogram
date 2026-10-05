package app.line.ui.admin

import app.line.core.NumberInput
import org.json.JSONArray
import org.json.JSONObject

/** Server rules an administrator can change. */
data class AdminSettings(
    val callsEnabled: Boolean,
    val chatEnabled: Boolean,
    val registrationEnabled: Boolean,
    val maxParticipants: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("callsEnabled", callsEnabled).put("chatEnabled", chatEnabled)
        .put("registrationEnabled", registrationEnabled).put("maxParticipants", maxParticipants)

    companion object {
        const val MIN_PARTICIPANTS = 2
        const val MAX_PARTICIPANTS = 8

        fun from(json: JSONObject?): AdminSettings = AdminSettings(
            callsEnabled = json?.optBoolean("callsEnabled", true) ?: true,
            chatEnabled = json?.optBoolean("chatEnabled", true) ?: true,
            registrationEnabled = json?.optBoolean("registrationEnabled", true) ?: true,
            maxParticipants = (json?.optInt("maxParticipants", MAX_PARTICIPANTS) ?: MAX_PARTICIPANTS)
                .coerceIn(MIN_PARTICIPANTS, MAX_PARTICIPANTS),
        )
    }
}

data class AdminMetrics(
    val online: Int,
    val registered: Int,
    val activeCalls: Int,
    val mediaConfigured: Boolean,
    val uptimeSeconds: Long?,
)

data class AdminCall(val id: String, val participants: Int)
data class AdminEvent(val at: String, val event: String, val outcome: String)

data class AdminStatus(
    val settings: AdminSettings,
    val metrics: AdminMetrics,
    val blocked: List<String>,
    val calls: List<AdminCall>,
    val events: List<AdminEvent>,
    val raw: JSONObject,
) {
    companion object {
        /** Tolerant on purpose: a newer server may add fields, an older one may omit them. */
        fun parse(json: JSONObject): AdminStatus {
            val metrics = json.optJSONObject("metrics")
            return AdminStatus(
                settings = AdminSettings.from(json.optJSONObject("settings")),
                metrics = AdminMetrics(
                    online = metrics?.optInt("online") ?: 0,
                    registered = metrics?.optInt("registered") ?: 0,
                    activeCalls = metrics?.optInt("activeCalls") ?: 0,
                    mediaConfigured = metrics?.optBoolean("mediaConfigured") ?: false,
                    uptimeSeconds = metrics?.takeIf { it.has("uptimeSeconds") }?.optLong("uptimeSeconds"),
                ),
                blocked = strings(json.optJSONArray("blockedNumbers")),
                calls = objects(json.optJSONArray("calls")).mapNotNull {
                    val id = it.optString("id")
                    if (id.isEmpty()) null else AdminCall(id, it.optInt("participantCount"))
                },
                events = objects(json.optJSONArray("events")).map {
                    AdminEvent(it.optString("at"), it.optString("event"), it.optString("outcome"))
                },
                raw = json,
            )
        }

        private fun strings(array: JSONArray?): List<String> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}

object AdminRules {
    const val SECRET_MIN = 12
    const val SECRET_MAX = 128
    const val SESSION_MAX_MS = 300_000L
    const val LOG_LINES = 20

    fun secretValid(code: String): Boolean = code.length in SECRET_MIN..SECRET_MAX

    /** Same upper bound as the service applies, so the countdown matches when the session really ends. */
    fun sessionEnd(serverExpiresAt: Long, now: Long): Long = minOf(serverExpiresAt, now + SESSION_MAX_MS)

    fun remainingSeconds(expiresAt: Long, now: Long): Long = ((expiresAt - now + 999) / 1_000).coerceAtLeast(0)

    fun clock(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "$m:" + (if (s < 10) "0$s" else "$s")
    }

    /** Numbers an administrator may block: any well-formed number, including the administrator's own is rejected by the server. */
    fun number(raw: String): NumberInput.Result = NumberInput.single(raw, own = "")

    fun lastEvents(events: List<AdminEvent>): List<AdminEvent> = events.takeLast(LOG_LINES).asReversed()

    fun dirty(original: AdminSettings, edited: AdminSettings): Boolean = original != edited

    /** Disabling calls ends active groups; the confirmation says so only when it is true. */
    fun endsCalls(original: AdminSettings, edited: AdminSettings, activeCalls: Int): Boolean =
        activeCalls > 0 && ((original.callsEnabled && !edited.callsEnabled) || edited.maxParticipants < original.maxParticipants)
}

/** Messages the service raises in Russian; mapped to a kind so the panel can show them in the interface language. */
object AdminErrors {
    enum class Kind { NOT_CONFIGURED, RATE_LIMITED, INVALID_CODE, EXPIRED, REJECTED, CANCELLED, OTHER }

    fun classify(message: String?): Kind = when {
        message == null -> Kind.OTHER
        message.startsWith("Админ-доступ не настроен") -> Kind.NOT_CONFIGURED
        message.startsWith("Слишком много попыток") -> Kind.RATE_LIMITED
        message.startsWith("Неверный секретный код") -> Kind.INVALID_CODE
        message.startsWith("Войдите в админ-панель") || message.startsWith("Срок сессии") || message.startsWith("Админ-сессия") -> Kind.EXPIRED
        message.startsWith("Сервер отклонил") -> Kind.REJECTED
        message.startsWith("Вход отменён") -> Kind.CANCELLED
        else -> Kind.OTHER
    }
}

/** The end of the current admin session, as far as this process knows; never the secret itself. */
object AdminSession {
    @Volatile var expiresAt: Long = 0L
}
