package app.line

import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

class CertificatePinningTest {
    @Test fun trustedTlsStillFailsForWrongPinnedPublicKey() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val api = "wss://localhost:${server.port}/signal"
            val wrong = "sha256/" + "A".repeat(43) + "="
            val pin = CertificatePinner.pin(certificate.certificate)
            fun client(value: String) = EndpointConfig(api, value, "wss://rtc.example.org", pin).http().newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            val request = Request.Builder().url(server.url("/health")).build()
            assertThrows(SSLPeerUnverifiedException::class.java) { client(wrong).newCall(request).execute().close() }
            server.enqueue(MockResponse().setBody("ok"))
            client(pin).newCall(request).execute().use { assertEquals(200, it.code) }
        }
    }
}
