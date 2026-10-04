package app.line

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import app.line.crypto.SecureStore
import app.line.ui.Localized
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class UiFeaturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private val peer = "97000001"
    private val displayName = "Adel test contact"
    private val oldMessage = "Stored message should remain searchable"

    @Test fun languageSelectionPersistsAcrossActivityRecreationAndKeepsUserText() {
        setLanguage("ru")
        val oldId = "ui-language-${UUID.randomUUID()}"
        SecureStore(context).use { store ->
            store.clearConversation(peer)
            store.saveMessage(peer, oldId, oldMessage, false, "received")
        }
        context.getSharedPreferences("line-ui", 0).edit().putString("contact-$peer", displayName).commit()

        val russianActivity = start()
        var lastActivity: MainActivity = russianActivity
        try {
            openProfile(russianActivity)
            chooseLanguage(russianActivity, "Язык", "English", "en")
            val englishActivity = restart(russianActivity)
            lastActivity = englishActivity
            openProfile(englishActivity)
            await(englishActivity) { labels(englishActivity.window.decorView).contains("Language") }
            screenshot("ui-languages-en")

            clickTab(englishActivity, 1)
            await(englishActivity) { labels(englishActivity.window.decorView).contains(displayName) }
            assertTrue(labels(englishActivity.window.decorView).contains(oldMessage))

            openProfile(englishActivity)
            chooseLanguage(englishActivity, "Language", "Қазақша", "kk")
            val kazakhActivity = restart(englishActivity)
            lastActivity = kazakhActivity
            openProfile(kazakhActivity)
            await(kazakhActivity) { labels(kazakhActivity.window.decorView).contains("Тіл") }
            screenshot("ui-languages-kk")
            assertEquals("kk", selectedLanguage())

            chooseLanguage(kazakhActivity, "Тіл", "Орысша", "ru")
            val restoredRussianActivity = restart(kazakhActivity)
            lastActivity = restoredRussianActivity
            openProfile(restoredRussianActivity)
            await(restoredRussianActivity) { labels(restoredRussianActivity.window.decorView).contains("Язык") }
            assertEquals("ru", selectedLanguage())
        } finally {
            finish(lastActivity)
            SecureStore(context).use { it.clearConversation(peer) }
            context.getSharedPreferences("line-ui", 0).edit().remove("contact-$peer").commit()
            setLanguage("ru")
        }
    }

    @Test fun notificationsJournalShowsCallOutcomeAndSettingsDialogHasSoundToggles() {
        setLanguage("ru")
        val messageId = "ui-notification-message-${UUID.randomUUID()}"
        val failedId = "ui-notification-failed-${UUID.randomUUID()}"
        SecureStore(context).use { store ->
            store.saveMessage(peer, messageId, "Test body stays private", false, "received")
            store.recordMessageActivity(messageId, outgoing = false, outcome = "received")
            store.saveMessage(peer, failedId, "Failed message fixture", true, "failed")
            store.recordMessageActivity(failedId, outgoing = true, outcome = "failed")
            store.recordActivityEvent(ActivityEvent(
                "ui-declined-${UUID.randomUUID()}", ActivityEvent.CALL, peer, true, "declined",
                System.currentTimeMillis(),
            ))
        }
        val activity = start()
        try {
            openProfile(activity)
            click(activity, "Уведомления")
            await(activity) {
                val visible = labels(activity.window.decorView)
                visible.any { it.contains("Звонок отклонён") } && visible.contains("Новое сообщение")
            }
            val journal = labels(activity.window.decorView)
            assertTrue(journal.contains("Нужна настройка Firebase"))
            assertFalse(journal.contains("Не отправлено"))
            assertFalse(journal.contains("Test body stays private"))
            screenshot("ui-notifications")

            click(activity, "Настройки уведомлений")
            assertTrue(device.findObject(UiSelector().description("Звук сообщений")).waitForExists(5000))
            assertTrue(device.findObject(UiSelector().description("Звуки интерфейса")).exists())
            toggleAndRestore(activity, "Звук сообщений")
            toggleAndRestore(activity, "Звуки интерфейса")
            device.findObject(UiSelector().resourceId("android:id/button2")).click()
        } catch (error: Throwable) {
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "v7-notification-failure.xml"))
            screenshot("v7-notification-failure")
            throw error
        } finally {
            finish(activity)
            SecureStore(context).use { it.clearConversation(peer) }
        }
    }

    @Test fun recentCallsTabShowsDeclinedCall() {
        setLanguage("ru")
        SecureStore(context).use { store ->
            store.recordActivityEvent(ActivityEvent(
                "ui-recent-${UUID.randomUUID()}", ActivityEvent.CALL, peer, true, "declined",
                System.currentTimeMillis(),
            ))
        }
        val activity = start()
        try {
            assertTrue(labels(activity.window.decorView).contains("Набор"))
            assertTrue(labels(activity.window.decorView).contains("Недавние"))
            click(activity, "Недавние")
            await(activity) { labels(activity.window.decorView).any { it.contains("Звонок отклонён") } }
            assertTrue(labels(activity.window.decorView).any { it.contains("Входящий") })
            screenshot("ui-recent")
        } finally { finish(activity) }
    }

    @Test fun globalSearchFindsOlderStoredMessageAndOmitsDeletedMessage() {
        setLanguage("ru")
        val oldId = "ui-old-${UUID.randomUUID()}"
        val deletedId = "ui-deleted-${UUID.randomUUID()}"
        val deletedText = "A deleted fixture that must not appear"
        SecureStore(context).use { store ->
            store.clearConversation(peer)
            store.saveMessage(peer, oldId, oldMessage, false, "received")
            repeat(42) { index ->
                store.saveMessage(peer, "ui-page-$index-${UUID.randomUUID()}", "Newer fixture message $index", false, "received")
            }
            store.saveMessage(peer, deletedId, deletedText, false, "received")
            store.deleteMessage(deletedId)
            assertFalse(store.messages(peer).any { it.id == oldId })
            assertTrue(store.searchMessages("searchable", peer).any { it.id == oldId })
            assertFalse(store.searchMessages(deletedText, peer).any { it.id == deletedId })
        }

        val activity = start()
        try {
            clickTab(activity, 1)
            click(activity, "Поиск сообщений")
            val query = searchInput(activity, "Поиск сообщений")
            instrumentation.runOnMainSync { query.setText("searchable") }
            await(activity) { labels(activity.window.decorView).contains(oldMessage) }
            screenshot("ui-search")
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == oldMessage }.parent.let { (it as View).performClick() }
            }
            await(activity) {
                labels(activity.window.decorView).contains(oldMessage) && descendants(activity.window.decorView).any { it.contentDescription?.toString() == "Отправить сообщение" }
            }
            click(activity, "Назад")
            click(activity, "Поиск сообщений")
            val deletionQuery = searchInput(activity, "Поиск сообщений")
            instrumentation.runOnMainSync { deletionQuery.setText("deleted fixture") }
            await(activity) {
                val visible = labels(activity.window.decorView)
                visible.contains("Ничего не найдено") && visible.none { it == oldMessage || it == deletedText }
            }
        } finally {
            finish(activity)
            SecureStore(context).use { it.clearConversation(peer) }
        }
    }

    @Test fun conversationSearchUsesItsOwnSearchEntry() {
        setLanguage("ru")
        SecureStore(context).use { store ->
            store.clearConversation(peer)
            store.saveMessage(peer, "ui-scoped-${UUID.randomUUID()}", oldMessage, false, "received")
        }
        context.getSharedPreferences("line-ui", 0).edit().putString("contact-$peer", displayName).commit()
        val activity = start()
        try {
            clickTab(activity, 1)
            await(activity) { labels(activity.window.decorView).contains(displayName) }
            val dialogDescription = "Диалог $displayName"
            assertTrue(descendants(activity.window.decorView).any { it.contentDescription?.toString() == dialogDescription })
            click(activity, dialogDescription)
            await(activity) { labels(activity.window.decorView).contains(oldMessage) }
            click(activity, "Поиск в переписке")
            val query = searchInput(activity, "Поиск в переписке")
            instrumentation.runOnMainSync { query.setText("searchable") }
            await(activity) { labels(activity.window.decorView).contains(oldMessage) }
        } finally {
            finish(activity)
            SecureStore(context).use { it.clearConversation(peer) }
            context.getSharedPreferences("line-ui", 0).edit().remove("contact-$peer").commit()
        }
    }

    private fun start(): MainActivity = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    ) as MainActivity

    private fun restart(previous: MainActivity): MainActivity {
        finish(previous)
        return start()
    }

    private fun finish(activity: MainActivity) = instrumentation.runOnMainSync { activity.finish() }

    private fun openProfile(activity: MainActivity) = clickTab(activity, 2)

    private fun clickTab(activity: MainActivity, index: Int) = instrumentation.runOnMainSync {
        val label = Localized.text(context, listOf("Звонки", "Сообщения", "Профиль")[index])
        descendants(activity.window.decorView).first { it.contentDescription?.toString() == label }.performClick()
    }

    private fun chooseLanguage(activity: MainActivity, label: String, option: String, code: String) {
        click(activity, label)
        assertTrue(device.findObject(UiSelector().text(option)).waitForExists(5000))
        device.findObject(UiSelector().text(option)).click()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && selectedLanguage() != code) Thread.sleep(50)
        assertEquals("Language choice should be saved before recreation", code, selectedLanguage())
    }

    private fun toggleAndRestore(activity: MainActivity, label: String) {
        val selector = UiSelector().description(label)
        assertTrue("Missing notification switch: $label", device.findObject(selector).waitForExists(5000))
        val control = device.findObject(selector)
        val previous = control.isChecked
        control.click()
        assertNotEquals(previous, control.isChecked)
        control.click()
        assertEquals(previous, control.isChecked)
    }

    private fun click(activity: MainActivity, label: String) = instrumentation.runOnMainSync {
        val root = activity.window.decorView
        val target = descendants(root).firstOrNull { it.contentDescription?.toString() == label }
            ?: descendants(root).firstOrNull { it is TextView && it.text.toString() == label }
            ?: error("Missing UI element: $label")
        target.requestRectangleOnScreen(Rect(0, 0, root.width, root.height), true)
        var clickable = target
        while (!clickable.isClickable && clickable.parent is View) clickable = clickable.parent as View
        clickable.performClick()
    }

    private fun searchInput(activity: MainActivity, description: String): EditText {
        var result: EditText? = null
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                result = descendants(activity.window.decorView).filterIsInstance<EditText>().firstOrNull {
                    it.contentDescription?.toString() == description || it.hint?.toString() == description
                }
            }
            if (result != null) return result!!
            Thread.sleep(100)
        }
        error("Search input did not appear: $description")
    }

    private fun await(activity: MainActivity, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = check() }
            if (passed) return
            Thread.sleep(100)
        }
        fail("UI did not reach expected state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    private fun labels(root: View) = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }

    private fun selectedLanguage() = context.getSharedPreferences("line-ui", 0).getString("language", "ru")

    private fun setLanguage(code: String) {
        context.getSharedPreferences("line-ui", 0).edit().putString("language", code).commit()
    }
}
