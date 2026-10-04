package app.line.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import app.line.R
import java.util.Locale

object Localized {
    private val russianText = mapOf(
        "Язык" to R.string.language,
        "Русский" to R.string.language_russian,
        "English" to R.string.language_english,
        "Қазақша" to R.string.language_kazakh,
        "Недавнее" to R.string.tab_recent,
        "Недавние" to R.string.recent,
        "Набор" to R.string.tab_dial,
        "Звонки" to R.string.tab_calls,
        "Сообщения" to R.string.tab_messages,
        "Профиль" to R.string.tab_profile,
        "Пока нет сообщений" to R.string.messages_empty_title,
        "Начните новый разговор." to R.string.messages_empty_hint,
        "Имя (необязательно)" to R.string.name_optional,
        "Поиск сообщений" to R.string.search_messages,
        "Ничего не найдено" to R.string.search_no_results,
        "Поиск в переписке" to R.string.search_conversation,
        "Уведомления" to R.string.notifications,
        "Новые звонки и сообщения" to R.string.notifications_new_calls_messages,
        "Пока нет уведомлений" to R.string.notifications_empty,
        "Здесь появятся новые сообщения и результаты звонков." to R.string.notifications_empty_hint,
        "Пока нет звонков" to R.string.calls_empty,
        "Здесь появятся ваши недавние звонки." to R.string.calls_empty_hint,
        "Входящий звонок" to R.string.call_incoming,
        "Исходящий звонок" to R.string.call_outgoing,
        "Входящий" to R.string.call_incoming_short,
        "Исходящий" to R.string.call_outgoing_short,
        "Завершён" to R.string.call_completed,
        "Пропущен" to R.string.call_missed,
        "Отклонён" to R.string.call_declined,
        "Отменён" to R.string.call_cancelled,
        "Неуспешный" to R.string.call_failed,
        "Звонок завершён" to R.string.call_completed_full,
        "Пропущенный звонок" to R.string.call_missed_full,
        "Звонок отклонён" to R.string.call_declined_full,
        "Звонок отменён" to R.string.call_cancelled_full,
        "Неуспешный звонок" to R.string.call_failed_full,
        "Звук сообщений" to R.string.sound_messages,
        "Звуки интерфейса" to R.string.sound_interface,
        "Показывать уведомления" to R.string.notifications_show,
        "Настройки уведомлений" to R.string.notification_settings,
        "Настройки Android" to R.string.android_settings,
        "Уведомления разрешены" to R.string.notifications_allowed,
        "Уведомления выключены" to R.string.notifications_disabled,
        "Уведомления доступны, пока приложение подключено. При закрытом приложении новые входящие пока не принимаются." to R.string.notifications_connection_note,
        "Звук входящих настраивается в Android." to R.string.incoming_sound_android_settings,
        "Разрешить" to R.string.allow,
        "Открыть чат" to R.string.open_chat,
        "Недавние звонки" to R.string.recent_calls,
        "Не подключён" to R.string.status_disconnected,
        "В сети" to R.string.status_online,
        "Подключить" to R.string.connect,
        "Подключиться" to R.string.connect_button,
        "Копировать" to R.string.copy,
        "О приложении" to R.string.about,
        "Качество звука" to R.string.sound_quality,
        "Подключение" to R.string.connection,
        "Название контакта" to R.string.contact_name,
        "Номер" to R.string.phone_number,
        "Приватное сообщение" to R.string.private_message,
        "Ответить" to R.string.answer,
        "Отклонить" to R.string.decline,
        "Завершить" to R.string.end_call,
        "Удалить на этом устройстве" to R.string.delete_on_device,
        "Не назначен" to R.string.status_not_assigned,
        "Номер не назначен" to R.string.number_not_assigned,
        "Назад" to R.string.back,
        "Позвонить контакту" to R.string.call_contact,
        "О контакте" to R.string.contact_details,
        "Новое сообщение" to R.string.new_message,
        "Ваш номер" to R.string.your_number,
        "Введите номер" to R.string.enter_number,
        "Номера участников" to R.string.participant_numbers,
        "Добавить участника" to R.string.add_participant,
        "Удалить цифру" to R.string.delete_digit,
        "Позвонить" to R.string.call,
        "Нет сообщений" to R.string.no_messages,
        "Ваши разговоры появятся здесь" to R.string.old_empty_inbox_hint,
        "Написать" to R.string.write_message,
        "Сообщение" to R.string.message,
        "Отправить сообщение" to R.string.send_message,
        "Сообщения отключены" to R.string.messages_disabled,
        "Администратор сервиса временно отключил сообщения." to R.string.messages_disabled_admin,
        "Нет подключения" to R.string.no_connection,
        "Не удалось открыть историю" to R.string.history_open_failed,
        "Не удалось открыть сообщения" to R.string.messages_open_failed,
        "Номер Line" to R.string.line_number,
        "Имя · необязательно" to R.string.old_name_optional,
        "Новый диалог" to R.string.new_conversation,
        "Отмена" to R.string.cancel,
        "Открыть" to R.string.open,
        "Введите номер другого человека" to R.string.enter_other_number,
        "Проверить код безопасности" to R.string.verify_security_code,
        "Изменить имя" to R.string.edit_name,
        "Очистить переписку на этом устройстве" to R.string.clear_conversation,
        "Удалить локальную историю?" to R.string.delete_local_history_title,
        "Копия у собеседника останется. Доверие и ключи контакта не сбрасываются." to R.string.delete_local_history_message,
        "Удалить" to R.string.delete,
        "Сервис не готов" to R.string.service_not_ready,
        "Имя" to R.string.name,
        "Имя контакта" to R.string.contact_name_title,
        "Сохранить" to R.string.save,
        "Сравните код с другом лично или через другой доверенный канал." to R.string.compare_safety_code,
        "Проверка контакта" to R.string.contact_verification,
        "Позже" to R.string.later,
        "Готово" to R.string.done,
        "Код совпадает" to R.string.code_matches,
        "Мой аккаунт" to R.string.my_account,
        "Настроено" to R.string.configured,
        "Высокое" to R.string.high_quality,
        "Для слабой сети" to R.string.low_bandwidth_quality,
        "настроен" to R.string.media_configured,
        "не настроен" to R.string.media_not_configured,
        "Звонки и сообщения. Содержимое защищено на устройствах; сервис и сеть могут видеть участников и время соединений. Новые входящие доступны при открытом приложении." to R.string.about_app_description,
        "Сначала подключите Line" to R.string.connect_line_first,
        "Админ-код проверяется сервером. Получите код подключения сервиса и подключитесь в профиле." to R.string.admin_code_description,
        "Секретный код" to R.string.secret_code,
        "Вход администратора" to R.string.admin_login,
        "Войти" to R.string.sign_in,
        "Не менее 12 символов" to R.string.at_least_12_chars,
        "Не удалось войти" to R.string.login_failed,
        "Групповой звонок" to R.string.group_call,
        "Звонок" to R.string.call_title,
        "Вызов…" to R.string.calling,
        "Соединяем…" to R.string.connecting,
        "Микрофон" to R.string.microphone,
        "Динамик" to R.string.speaker,
        "Завершить звонок" to R.string.end_call_full,
        "Вы" to R.string.you,
        "В звонке" to R.string.in_call,
        "Ожидание" to R.string.waiting,
        "Код подключения выдаёт администратор вашего сервиса Line." to R.string.connect_code_issued_by_admin,
        "Код подключения" to R.string.connection_code,
        "Подключить Line" to R.string.connect_line,
        "Продолжить" to R.string.continue_setup,
        "Подключиться?" to R.string.connect_confirm_title,
        "Используйте код только из доверенного источника." to R.string.trusted_code_warning,
        "%1\$s\n\nИспользуйте код только из доверенного источника." to R.string.connect_host_warning_format,
        "Сервис запускается" to R.string.service_starting,
        "Адрес сервиса сообщений" to R.string.message_service_address,
        "Ключи сертификата сообщений" to R.string.message_service_pins,
        "Адрес сервиса звонков" to R.string.call_service_address,
        "Ключи сертификата звонков" to R.string.call_service_pins,
        "Настройки сервиса" to R.string.service_settings,
        "Админ-сессия истекла" to R.string.admin_session_expired,
        "Сервис пока не готов" to R.string.service_not_ready_yet,
        "Проверьте настройки" to R.string.check_settings,
        "Звонки отключены" to R.string.calls_disabled,
        "Администратор сервиса временно отключил звонки." to R.string.calls_disabled_admin,
        "Звонок недоступен" to R.string.call_unavailable,
        "Проверьте подключение. Сервис звонков должен быть настроен администратором." to R.string.call_unavailable_hint,
        "Введите номер из 8 цифр" to R.string.enter_8_digits,
        "Нужен доступ к микрофону" to R.string.microphone_permission_required,
        "Не удалось выполнить действие. Проверьте подключение" to R.string.action_failed_connection,
        "Сначала завершите звонок" to R.string.finish_call_first,
        "Отправлено" to R.string.sent,
        "Не отправлено" to R.string.not_sent,
        "Отправляется" to R.string.sending,
        "Время сообщения" to R.string.message_time,
        "Удалить сообщение?" to R.string.delete_message_title,
        "Оно исчезнет из вашей истории. Копия собеседника останется." to R.string.delete_message_description,
        "Номер скопирован" to R.string.number_copied,
        "Понятно" to R.string.ok,
        "Подключите аккаунт" to R.string.connect_account,
        "Получаем ваш номер" to R.string.fetching_number,
        "Нажмите, чтобы скопировать" to R.string.tap_to_copy,
        "Номер появится после подключения к Line" to R.string.number_after_connect,
        "Он появится, когда сервис ответит" to R.string.number_after_service_response,
        "Нет связи" to R.string.no_network,
        "Доступны сообщения" to R.string.messages_available,
        "Администратор" to R.string.admin_panel,
        "Закрыть" to R.string.close,
        "Сессия до 5 минут. При сворачивании вход закрывается." to R.string.admin_session_note,
        "В сети: %1\$d · Аккаунтов: %2\$d\nЗвонков: %3\$d · LiveKit: %4\$s" to R.string.admin_metrics_format,
        "Обновить состояние" to R.string.refresh_status,
        "Правила сервиса" to R.string.service_rules,
        "Разрешить звонки" to R.string.allow_calls,
        "Разрешить сообщения" to R.string.allow_messages,
        "Разрешить новые аккаунты" to R.string.allow_registrations,
        "Максимум участников в звонке" to R.string.max_call_participants,
        "Применить правила" to R.string.apply_rules,
        "Применить к серверу?" to R.string.apply_to_server_title,
        "Отключение звонков завершит активные группы. Настройки сохраняются на сервере, не только на этом телефоне." to R.string.apply_to_server_message,
        "Применить" to R.string.apply,
        "Аккаунты" to R.string.accounts,
        "Номер из 8 цифр" to R.string.number_8_digits,
        "Заблокированы: %1\$s" to R.string.blocked_numbers_format,
        "Заблокировать аккаунт" to R.string.block_account,
        "Введите 8 цифр" to R.string.enter_8_digits_short,
        "Заблокировать %1\$s?" to R.string.block_account_confirm_format,
        "Подключение аккаунта и его текущий звонок будут закрыты." to R.string.block_account_message,
        "Заблокировать" to R.string.block,
        "Разблокировать аккаунт" to R.string.unblock_account,
        "Активные звонки" to R.string.active_calls,
        "Нет активных звонков" to R.string.no_active_calls,
        "Завершить группу %1\$d · %2\$d участников" to R.string.end_group_format,
        "Завершить эту группу?" to R.string.end_group_confirm,
        "Диагностика" to R.string.diagnostics,
        "Журнал содержит только тип события, время и результат. Без сообщений, аудио, ключей, IP и номеров участников." to R.string.diagnostics_privacy_note,
        "Журнал пуст" to R.string.empty_log,
        "Скопировать диагностический отчёт" to R.string.copy_diagnostic_report,
        "Диагностика Line" to R.string.diagnostics_line,
        "Очистить журнал" to R.string.clear_log,
        "Подключение приложения" to R.string.app_connection,
        "Изменить настройки подключения" to R.string.edit_connection_settings,
        "Скопировать код для пользователей" to R.string.copy_user_code,
        "Подключение Line" to R.string.line_connection,
        "Переподключить приложение" to R.string.reconnect_app,
        "Выйти из админ-панели" to R.string.exit_admin_panel,
        "Действие не выполнено" to R.string.action_not_completed,
        "Проверьте подключение" to R.string.check_connection,
        "Войдите заново: сессия истекла" to R.string.admin_session_relogin,
        "Скопировано" to R.string.copied,
        "Активный звонок" to R.string.call_channel,
        "Входящие звонки" to R.string.incoming_calls_channel,
        "Пропущенные звонки" to R.string.missed_calls_channel,
        "Откройте Line, чтобы ответить" to R.string.open_line_to_answer,
        "Микрофон выключен" to R.string.microphone_muted,
        "Интернет отключён. Звонок завершён" to R.string.service_call_ended_internet,
        "Неверное или повторное зашифрованное сообщение отклонено" to R.string.service_invalid_envelope,
        "Ошибка протокола звонка" to R.string.service_protocol_error,
        "Неверное сообщение сервера отклонено" to R.string.service_invalid_message,
        "Ошибка защищённого соединения. Проверьте SAS участников" to R.string.service_secure_connection_error,
        "Операция не выполнена: проверьте сеть, ключи и SAS" to R.string.service_operation_failed,
        "Для другого сервера нужен отдельный профиль/очистка данных: номера и доверие не переносятся" to R.string.service_profile_server_mismatch,
        "Подключитесь и введите секретный код" to R.string.service_admin_code_prompt,
        "Вход отменён" to R.string.service_login_cancelled,
        "Срок сессии истёк" to R.string.service_session_expired,
        "Войдите в админ-панель заново" to R.string.service_admin_relogin,
        "Админ-сессия закрыта" to R.string.service_admin_session_closed,
        "Админ-доступ не настроен на сервере" to R.string.service_admin_not_configured,
        "Слишком много попыток. Подождите минуту" to R.string.service_admin_rate_limited,
        "Неверный секретный код" to R.string.service_invalid_secret,
        "Сервер отклонил действие" to R.string.service_rejected_action,
        "Нет ответа или соединения. Звонок завершён" to R.string.service_call_no_response,
        "Подключите Line, чтобы получить номер" to R.string.service_connect_for_number,
        "Подключение…" to R.string.service_connecting,
        "Соединение потеряно. Звонок завершён" to R.string.service_lost_call,
        "Не удалось подключиться. Проверьте настройки сервиса" to R.string.service_connection_failed,
        "Администратор отключил сообщения" to R.string.service_messages_disabled,
        "Администратор отключил звонки" to R.string.service_calls_disabled,
        "Сообщение не отправлено: адресат недоступен" to R.string.service_message_unavailable,
        "Учётная запись открыта на другом устройстве" to R.string.service_account_elsewhere,
        "Звонок невозможен: участник недоступен или LiveKit не настроен" to R.string.service_call_unavailable,
        "Соединяем зашифрованную группу…" to R.string.service_group_connecting,
        "Входящий групповой звонок" to R.string.service_incoming_group_call,
        "LiveKit · устанавливаем E2EE…" to R.string.service_media_connecting,
        "Групповой звонок завершён" to R.string.service_group_ended,
        "Медиасоединение потеряно. Звонок завершён" to R.string.service_media_lost,
        "Ошибка E2EE или медиасервера. Звонок завершён" to R.string.service_media_error,
        "Создаём группу…" to R.string.service_group_creating,
        "Не удалось установить звонок" to R.string.service_call_failed,
        "Готов к звонку" to R.string.service_call_ready,
        "Нужен адрес API wss://домен/signal" to R.string.service_api_endpoint_required,
        "Нужен адрес LiveKit wss://домен" to R.string.service_media_endpoint_required,
        "Укажите SHA-256 SPKI pin и, желательно, резервный pin сертификата" to R.string.service_certificate_pin_required,
        "Проверьте код подключения" to R.string.service_profile_code_check,
        "Код подключения повреждён" to R.string.service_profile_code_corrupt,
        "Этот код подключения не поддерживается" to R.string.service_profile_code_unsupported,
        "Неверный формат кода" to R.string.service_profile_code_format,
        "Укажите сервер, чтобы получить номер" to R.string.service_server_required,
        "Сообщение от %1\$s отклонено: сначала сверьте SAS" to R.string.service_rejected_message_format,
        "Новое зашифрованное сообщение от %1\$s" to R.string.service_new_message_format,
        "Голос E2EE · %1\$s" to R.string.voice_e2ee_format,
        "Диалог %s" to R.string.dialog_title_format,
        "Диалог %1\$s" to R.string.dialog_title_format,
        "%s · %s" to R.string.two_values_format,
        "Вы: %s" to R.string.you_prefix_format,
        "Вы: " to R.string.you_prefix,
    )

    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(language(context)))
        return context.createConfigurationContext(config)
    }

    fun text(context: Context, source: String): String =
        russianText[source]?.let { wrap(context).getString(it) } ?: source

    fun format(context: Context, source: String, vararg args: Any): String =
        russianText[source]?.let { wrap(context).getString(it, *args) }
            ?: String.format(Locale.forLanguageTag(language(context)), source, *args)

    private fun language(context: Context): String = context
        .getSharedPreferences("line-ui", Context.MODE_PRIVATE)
        .getString("language", "ru")
        ?.takeIf { it == "ru" || it == "en" || it == "kk" }
        ?: "ru"
}

class LocalizedDialog(private val context: Context) {
    private val builder = AlertDialog.Builder(context)

    fun setTitle(source: String) = apply { builder.setTitle(Localized.text(context, source)) }

    fun setRawTitle(value: String) = apply { builder.setTitle(value) }

    fun setMessage(source: String) = apply { builder.setMessage(Localized.text(context, source)) }

    fun setView(view: View) = apply { builder.setView(view) }

    fun setNegativeButton(source: String, listener: DialogInterface.OnClickListener?) = apply {
        builder.setNegativeButton(Localized.text(context, source), listener)
    }

    fun setPositiveButton(source: String, listener: DialogInterface.OnClickListener?) = apply {
        builder.setPositiveButton(Localized.text(context, source), listener)
    }

    fun setItems(items: Array<String>, listener: DialogInterface.OnClickListener) = apply {
        builder.setItems(items.map { Localized.text(context, it) }.toTypedArray(), listener)
    }

    fun setSingleChoiceItems(items: Array<String>, checkedItem: Int, listener: DialogInterface.OnClickListener) = apply {
        builder.setSingleChoiceItems(items.map { Localized.text(context, it) }.toTypedArray(), checkedItem, listener)
    }

    fun create(): AlertDialog = builder.create()
    fun setNeutralButton(text: String, listener: DialogInterface.OnClickListener?) = apply {
        builder.setNeutralButton(Localized.text(context, text), listener)
    }

    fun show(): AlertDialog = builder.show()
}
