package app.line

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.line.crypto.SecureStore
import app.line.ui.Localized
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class NotificationTest {
    @Test fun incomingMessagePostsPrivatelyAndOpensItsConversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val peer = "96000001"
        val id = "notice-${UUID.randomUUID()}"
        val manager = context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences("line-ui", 0).edit().putString("language", "ru").commit()
        SecureStore(context).use { store ->
            store.saveMessage(peer, id, "Notification test fixture", false, "received")
            store.recordMessageActivity(id, outgoing = false, outcome = "received")
        }
        AppNotifications.createChannels(context)
        AppNotifications.showIncomingMessage(context, peer, id)
        val title = Localized.text(context, "Новое сообщение")
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && manager.activeNotifications.none { it.notification.extras.getString(Notification.EXTRA_TITLE) == title }) Thread.sleep(50)
        val notification = manager.activeNotifications.first { it.notification.extras.getString(Notification.EXTRA_TITLE) == title }.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(peer))
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("fixture"))
        val device = UiDevice.getInstance(instrumentation)
        device.openNotification(); instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.filesDir, "ui-system-notification.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); device.pressBack()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            notification.contentIntent.send()
            val activity = monitor.waitForActivityWithTimeout(15000) as? MainActivity ?: error("Notification did not open Line")
            assertEquals(peer, activity.intent.getStringExtra("peer"))
            instrumentation.runOnMainSync { activity.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            AppNotifications.cancelMessage(context, id)
            SecureStore(context).use { it.clearConversation(peer) }
        }
    }

    @Test fun incomingCallAlertIsReplacedByMissedCallNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        AppNotifications.createChannels(context)
        try {
            AppNotifications.showIncomingCall(context, "notification-call-${UUID.randomUUID()}")
            val incoming = awaitCategory(manager, Notification.CATEGORY_CALL)
            assertNotNull(incoming.deleteIntent)
            assertEquals(Notification.VISIBILITY_PRIVATE, incoming.visibility)
            AppNotifications.showMissedCall(context)
            val missed = awaitCategory(manager, Notification.CATEGORY_MISSED_CALL)
            assertEquals(Localized.text(context, "Пропущенный звонок"), missed.extras.getString(Notification.EXTRA_TITLE))
        } finally { AppNotifications.cancelIncomingCall(context) }
    }

    private fun awaitCategory(manager: NotificationManager, category: String): Notification {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == 2 && it.notification.category == category }?.let { return it.notification }
            Thread.sleep(50)
        }
        error("Notification category $category was not posted")
    }

    @Test fun channelsExistAndOngoingCallHidesItsPrivateContentOnLockScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppNotifications.createChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            listOf("calls", "call_alerts", "call_history", "messages").forEach {
                assertNotNull(manager.getNotificationChannel(it))
            }
        }
        val notification = AppNotifications.ongoingCall(context, muted = false, connected = true)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("peer"))
    }
}
