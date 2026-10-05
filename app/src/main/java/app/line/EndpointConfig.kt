package app.line

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

data class EndpointConfig(val apiUrl: String, val apiPins: String, val mediaUrl: String, val mediaPins: String) {
    fun validate() {
        require(Security.validEndpoint(apiUrl)) { "Нужен адрес API wss://домен/signal" }
        require(validMediaUrl(mediaUrl)) { "Нужен адрес LiveKit wss://домен" }
        pins(apiPins); pins(mediaPins)
    }

    fun http(): OkHttpClient {
        validate()
        val pinner = CertificatePinner.Builder()
        pins(apiPins).forEach { pinner.add(URI(apiUrl).host, it) }
        pins(mediaPins).forEach { pinner.add(URI(mediaUrl).host, it) }
        return OkHttpClient.Builder().certificatePinner(pinner.build())
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }

    companion object {
        fun validMediaUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "wss" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && (uri.path.isEmpty() || uri.path == "/")
        }.getOrDefault(false)

        fun pins(value: String): List<String> {
            val pins = value.split(',').map(String::trim).filter(String::isNotEmpty)
            require(pins.isNotEmpty() && pins.size <= 3 && pins.all { it.matches(Regex("sha256/[A-Za-z0-9+/]{43}=")) }) {
                "Укажите SHA-256 SPKI pin и, желательно, резервный pin сертификата"
            }
            return pins
        }
    }
}
