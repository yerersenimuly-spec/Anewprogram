package app.line

import android.content.*
import android.graphics.Bitmap
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdminAccessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var service: CallService

    @Test fun serverAuthenticationHiddenEntryAndRealPolicyChanges() {
        val pin = InstrumentationRegistry.getArguments().getString("pin") ?: error("Run scripts/test-android-admin.sh")
        context.getSharedPreferences("line", 0).edit().putString("endpoint", "wss://localhost:8443/signal")
            .putString("api_pins", pin).putString("media_endpoint", "wss://localhost:8443").putString("media_pins", pin).commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as CallService.LocalBinder).service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, CallService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        try {
            await { service.state.online }
            assertFalse(service.isAdmin())
            var refused = false
            try { runBlocking { withContext(Dispatchers.Main) { service.adminCommand("status") } } } catch (_: IllegalStateException) { refused = true }
            assertTrue("Normal account cannot perform admin actions", refused)
            instrumentation.runOnMainSync {
                val root = activity.window.decorView
                repeat(3) { find(root, "Профиль").performClick() }
            }
            instrumentation.waitForIdleSync()
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.findObject(UiSelector().text("Вход администратора")).waitForExists(5000))
            device.findObject(UiSelector().className(EditText::class.java.name)).setText("Local-test-admin-2026")
            device.findObject(UiSelector().resourceId("android:id/button1")).click()
            assertTrue(device.findObject(UiSelector().text("Администратор")).waitForExists(10000))
            await { service.isAdmin() }
            assertTrue(service.isAdmin())
            val scroller = UiScrollable(UiSelector().className("android.widget.ScrollView"))
            assertTrue("Admin status loaded", device.findObject(UiSelector().description("Разрешить звонки")).waitForExists(15000))
            scroller.scrollIntoView(UiSelector().description("Разрешить звонки")); device.findObject(UiSelector().description("Разрешить звонки")).click()
            scroller.scrollIntoView(UiSelector().description("Разрешить сообщения")); device.findObject(UiSelector().description("Разрешить сообщения")).click()
            scroller.scrollIntoView(UiSelector().description("Разрешить новые аккаунты")); device.findObject(UiSelector().description("Разрешить новые аккаунты")).click()
            scroller.scrollIntoView(UiSelector().description("Применить правила")); device.findObject(UiSelector().description("Применить правила")).click()
            device.findObject(UiSelector().resourceId("android:id/button1")).click()
            await { !service.state.callsEnabled && !service.state.chatEnabled }
            val status = runBlocking { withContext(Dispatchers.Main) { service.adminCommand("status") } }
            assertFalse(status.getJSONObject("settings").getBoolean("registrationEnabled"))
            scroller.scrollToBeginning(5)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
            File(context.getExternalFilesDir(null), "admin-panel-test-server.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            runBlocking { withContext(Dispatchers.Main) { service.adminCommand("clear_events") } }
            instrumentation.runOnMainSync { service.lockAdmin() }
            assertFalse(service.isAdmin())
            refused = false
            try { runBlocking { withContext(Dispatchers.Main) { service.adminCommand("status") } } } catch (_: IllegalStateException) { refused = true }
            assertTrue("Logout revokes client permission", refused)
        } catch (error: Throwable) {
            val device = UiDevice.getInstance(instrumentation)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "admin-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "admin-failure.xml"))
            throw error
        } finally {
            instrumentation.runOnMainSync { service.lockAdmin(); activity.finish() }
            context.unbindService(connection)
        }
    }

    private fun await(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) { var ok = false; instrumentation.runOnMainSync { ok = check() }; if (ok) return; Thread.sleep(100) }
        fail("Admin test did not reach expected state")
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun find(root: View, name: String) = descendants(root).first { it.contentDescription?.toString() == name }
}
