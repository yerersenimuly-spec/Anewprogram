package app.line.ui

import app.line.CallState

object UiPresentation {
    fun numberTitle(state: CallState): String = when {
        state.number.isNotEmpty() -> state.number.chunked(4).joinToString(" ")
        !state.configReady -> "Подключите аккаунт"
        else -> "Получаем ваш номер"
    }

    fun numberHint(state: CallState): String = when {
        state.number.isNotEmpty() -> "Нажмите, чтобы скопировать"
        !state.configReady -> "Номер появится после подключения к Line"
        else -> "Он появится, когда сервис ответит"
    }

    fun connection(state: CallState): String = when {
        !state.configReady -> "Не подключён"
        !state.online -> "Нет связи"
        !state.mediaReady -> "Доступны сообщения"
        else -> "В сети"
    }

    fun canCall(state: CallState): Boolean = state.online && state.mediaReady && state.number.isNotEmpty()
}
