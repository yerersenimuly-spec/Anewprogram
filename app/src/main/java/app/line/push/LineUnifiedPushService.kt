package app.line.push

import app.line.core.PushPayload
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class LineUnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        PushRegistrar.store(this, PushRegistrar.Endpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth))
        PushWake.sync(this)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        PushPayload.parse(message.content)?.let { PushWake.handle(this, it) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        PushRegistrar.failed(this, reason.name)
    }

    override fun onUnregistered(instance: String) {
        PushRegistrar.clear(this)
    }
}
