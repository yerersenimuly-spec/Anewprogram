package app.line.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import app.line.CallService
import app.line.ConnectionProfile
import app.line.ui.Localized
import kotlinx.coroutines.*
import org.json.JSONObject

class AdminPanel(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val service: CallService,
    private val editConnection: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var job: Job? = null
    private var sessionWatch: Job? = null
    private lateinit var body: LinearLayout

    fun show() {
        check(service.isAdmin())
        body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), dp(20)) }
        dialog = AlertDialog.Builder(activity).setTitle(text("Администратор"))
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton(text("Закрыть"), null).create()
        dialog?.setOnDismissListener { job?.cancel(); sessionWatch?.cancel(); service.lockAdmin() }
        dialog?.show()
        sessionWatch = scope.launch {
            while (isActive) {
                delay(1000)
                if (!service.isAdmin()) { dismiss(); break }
            }
        }
        refresh()
    }

    fun dismiss() { dialog?.dismiss(); dialog = null }

    private fun refresh() = run { render(service.adminCommand("status")) }

    private fun render(status: JSONObject) {
        if (dialog?.isShowing != true) return
        body.removeAllViews()
        val settings = status.getJSONObject("settings")
        val metrics = status.getJSONObject("metrics")
        label("Сессия до 5 минут. При сворачивании вход закрывается.", 12)
        label(Localized.format(activity, "В сети: %1\$d · Аккаунтов: %2\$d\nЗвонков: %3\$d · LiveKit: %4\$s",
            metrics.optInt("online"), metrics.optInt("registered"), metrics.optInt("activeCalls"),
            text(if (metrics.optBoolean("mediaConfigured")) "настроен" else "не настроен")), 14)
        button("Обновить состояние") { refresh() }
        section("Правила сервиса")
        fun setting(title: String, key: String) = Switch(activity).apply {
            text = this@AdminPanel.text(title); contentDescription = text; textSize = 14f; isChecked = settings.getBoolean(key); body.addView(this)
        }
        val calls = setting("Разрешить звонки", "callsEnabled")
        val chat = setting("Разрешить сообщения", "chatEnabled")
        val registration = setting("Разрешить новые аккаунты", "registrationEnabled")
        label("Максимум участников в звонке", 12)
        val members = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, (2..8).map(Int::toString))
            setSelection(settings.getInt("maxParticipants") - 2)
        }
        body.addView(members)
        button("Применить правила") {
            AlertDialog.Builder(activity).setTitle(text("Применить к серверу?"))
                .setMessage(text("Отключение звонков завершит активные группы. Настройки сохраняются на сервере, не только на этом телефоне."))
                .setNegativeButton(text("Отмена"), null).setPositiveButton(text("Применить")) { _, _ -> run {
                    service.adminCommand("update_settings", JSONObject().put("settings", JSONObject()
                        .put("callsEnabled", calls.isChecked).put("chatEnabled", chat.isChecked)
                        .put("registrationEnabled", registration.isChecked).put("maxParticipants", members.selectedItemPosition + 2)))
                    render(service.adminCommand("status"))
                } }.show()
        }
        section("Аккаунты")
        val number = EditText(activity).apply {
            hint = text("Номер из 8 цифр"); inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(8)); textSize = 16f; body.addView(this)
        }
        val blocked = status.optJSONArray("blockedNumbers")
        if (blocked != null && blocked.length() > 0) label(Localized.format(activity, "Заблокированы: %1\$s", (0 until blocked.length()).joinToString(", ") { blocked.getString(it) }), 12)
        button("Заблокировать аккаунт") {
            val target = number.text.toString()
            if (!target.matches(Regex("[0-9]{8}"))) { number.error = text("Введите 8 цифр"); return@button }
            AlertDialog.Builder(activity).setTitle(Localized.format(activity, "Заблокировать %1\$s?", target))
                .setMessage(text("Подключение аккаунта и его текущий звонок будут закрыты."))
                .setNegativeButton(text("Отмена"), null).setPositiveButton(text("Заблокировать")) { _, _ -> run {
                    service.adminCommand("block", JSONObject().put("number", target)); render(service.adminCommand("status"))
                } }.show()
        }
        button("Разблокировать аккаунт") {
            val target = number.text.toString()
            if (!target.matches(Regex("[0-9]{8}"))) { number.error = text("Введите 8 цифр"); return@button }
            run { service.adminCommand("unblock", JSONObject().put("number", target)); render(service.adminCommand("status")) }
        }
        section("Активные звонки")
        val activeCalls = status.optJSONArray("calls")
        if (activeCalls == null || activeCalls.length() == 0) label("Нет активных звонков", 12)
        else (0 until activeCalls.length()).forEach { index ->
            val call = activeCalls.getJSONObject(index)
            button(Localized.format(activity, "Завершить группу %1\$d · %2\$d участников", index + 1, call.optInt("participantCount"))) {
                AlertDialog.Builder(activity).setTitle(text("Завершить эту группу?"))
                    .setNegativeButton(text("Отмена"), null)
                    .setPositiveButton(text("Завершить")) { _, _ -> run {
                        service.adminCommand("end_call", JSONObject().put("callId", call.getString("id")))
                        render(service.adminCommand("status"))
                    } }.show()
            }
        }
        section("Диагностика")
        label("Журнал содержит только тип события, время и результат. Без сообщений, аудио, ключей, IP и номеров участников.", 12)
        val events = status.optJSONArray("events")
        if (events == null || events.length() == 0) label("Журнал пуст", 12)
        else label((maxOf(0, events.length() - 20) until events.length()).joinToString("\n") { index ->
            val event = events.getJSONObject(index)
            "${event.optString("at")} · ${event.optString("event")} · ${event.optString("outcome")}" }, 11)
        button("Скопировать диагностический отчёт") {
            val report = JSONObject().put("appVersion", "0.5.0").put("online", service.state.online)
                .put("metrics", metrics).put("settings", settings).put("events", events).toString(2)
            copy("Диагностика Line", report)
        }
        button("Очистить журнал") { run { service.adminCommand("clear_events"); render(service.adminCommand("status")) } }
        section("Подключение приложения")
        button("Изменить настройки подключения") { if (service.isAdmin()) editConnection() else expired() }
        button("Скопировать код для пользователей") {
            if (!service.isAdmin()) { expired(); return@button }
            val config = service.config() ?: return@button
            copy("Подключение Line", ConnectionProfile.encode(config))
        }
        button("Переподключить приложение") { dismiss(); service.reconnectNow() }
        button("Выйти из админ-панели") { dismiss() }
    }

    private fun run(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try { block() } catch (_: CancellationException) { }
            catch (error: Exception) {
                if (dialog?.isShowing == true) AlertDialog.Builder(activity).setTitle(text("Действие не выполнено"))
                    .setMessage(error.message?.let(::text) ?: text("Проверьте подключение"))
                    .setPositiveButton(text("Понятно"), null).show()
            }
        }
    }
    private fun expired() = Toast.makeText(activity, text("Войдите заново: сессия истекла"), Toast.LENGTH_LONG).show()
    private fun copy(title: String, text: String) {
        if (!service.isAdmin()) { expired(); return }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(this.text(title), text))
        Toast.makeText(activity, text("Скопировано"), Toast.LENGTH_SHORT).show()
    }
    private fun section(title: String) { label(title, 17) }
    private fun label(value: String, size: Int) { body.addView(TextView(activity).apply {
        text = this@AdminPanel.text(value); textSize = size.toFloat(); setTextColor(Color.BLACK); setPadding(0, dp(12), 0, dp(8)); setTextIsSelectable(true)
    }) }
    private fun button(title: String, action: () -> Unit) { body.addView(Button(activity).apply {
        text = this@AdminPanel.text(title); contentDescription = text; isAllCaps = false; textSize = 13f; setOnClickListener { action() }
    }, LinearLayout.LayoutParams(-1, -2)) }
    private fun text(source: String) = Localized.text(activity, source)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
