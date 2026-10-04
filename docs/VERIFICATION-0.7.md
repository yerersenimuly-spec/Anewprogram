# Line 0.7 — результат проверки

Дата: 2026-10-04. В основу импортированы предоставленные пользователем исходники окончательной 0.6.0. Настройки и данные действующего VPS не изменялись.

## Найденная первопричина доставки

На Android два настоящих SecureStore/libsignal-профиля не смогли доставить первый пакет: `decryptAndStore`/`decryptInternal` требовали `containsSession` до PreKey decrypt. Это неправильно для получателя, поскольку PreKey создаёт сессию. После разделения проверки доверия и проверки готовой сессии тесты прошли. Identity/SAS, подписи prekeys и pins не отключены.

## Подтверждено

- Android-сборка, test APK, JVM-тесты и Android Lint проходят.
- 33 серверных теста: ciphertext сохраняется до acknowledgement, offline/restart, чужой ACK отклонён, receipt replay/dedup, TTL/лимиты, optional FCM, привязка токена аккаунту, offline call wake-up и принятие после reconnect. Живой FCM в тестах заменяется injectable отправителем.
- `MessageExchangeTest`: **2 Android-теста прошли**, 51.153 секунды. Настоящий PQXDH/Double Ratchet, восемь сообщений в обе стороны, повторное открытие хранилищ, дедупликация, отказ до SAS и при смене identity. Нет fake crypto.
- `UiFeaturesTest`: **5 тестов прошли**, 55.411 секунды. Языки, профиль, поиск/переход к старому hit, «Недавние», входящие-only центр уведомлений и переключатели звуков. Первоначальный прогон был прерван ANR System UI software-эмулятора и несовпадением UI-selector с системными ON/OFF-суффиксами; после снижения нагрузки и accessibility-селекторов повторный полный класс прошёл.
- `CallServiceLifecycleTest.conversationCallButtonReachesCallSetupAndConnectedState`: **1 тест прошёл**, 12.042 секунды. Реальная кнопка в чате, pinned mock WebSocket-сигналинг и нормальная state-machine. Медиаengine в этом тесте подменён: это не слышимый звонок через публичный LiveKit.
- Свежий профиль проверен визуально на работающем Android. Языковые/screenshots fixtures не входят в APK.

## Push и ограничения

Добавлены Firebase Messaging 25.0.1, реальный FCM registration token (не Firebase Installation ID), data-only обработчик, приватные уведомления и bounded WorkManager-sync. Worker ждёт `inbox_complete`, не отвечает микрофоном в фоне. Автоматического запуска микрофонного foreground service из push нет.

**Живой облачный push не проверен:** нет Firebase-проекта/client configuration/service-account владельца. Конфигурация публичного API/LiveKit уже принадлежит владельцу и не заменялась. Не выполнены тесты на двух физических телефонах через его действующий VPS, внешний TURN/TLS, независимый аудит или измерение FPS/звука.

Для доставки обновите API v7, сохранив identity/admin файлы и свои секреты. Для push настройте Firebase по двум отдельным документам. Очередь хранит только ciphertext, но маршрутизационные метаданные остаются видимыми серверу/FCM-инфраструктуре.

Подпись присланного APK 0.6 отличается от debug-ключа этой среды. Для update без потери данных требуется исходный signing key. Не рекомендуются uninstall/`pm clear` на телефоне пользователя; очистка в тестах применялась только к disposable эмулятору.
