# Архитектура Line для Android

Документ описывает текущие исходники, а не гарантии production-сервиса. Клиент — нативный Android/Kotlin (min API 26), сигнализация — Node.js, многопользовательское аудио — LiveKit SFU. Публичные production-серверы здесь не предоставлены: для звонков нужны отдельно развёрнутые API, LiveKit и TURN.

## Структура

```text
app/src/main/java/app/line/
  MainActivity.kt            точка входа, хост экранов и SAS
  CallService.kt             WSS-сигнализация, ключ комнаты, lifecycle звонка
  CallState.kt               состояние интерфейса и вызова
  EndpointConfig.kt          WSS и SPKI pinning API/LiveKit
  Security.kt                проверка адреса и helper старого SDP fingerprint
  core/                      статусы сообщений, квитанции, профили, reconnect, приём медиа
  crypto/SecureStore.kt      libsignal, SQLite, AES-GCM, outbox, история
  media/                     LiveCallEngine (LiveKit Room, E2EE, Opus) и attachments (голос, медиа)
  push/                      UnifiedPush-сервис, worker, регистрация и wake
  ui/                        дизайн-система (тема, иконки, компоненты, sheets) и screens/
server/src/index.js          регистрация, bundles, mailbox, профили, звонки, пропущенные, blobs, push
server/src/push.js           отправка UnifiedPush (RFC 8291) и опционального APNs
server/src/blobs.js          шифрованные вложения: билеты, приём, выдача, TTL
server/test/                 тесты протокола, наборы v8 и фикстуры данных 0.7.1
deploy/livekit.yaml          пример self-hosted LiveKit
deploy/README.md             TLS, TURN, pins и эксплуатационные ограничения
```

| Компонент | Назначение и состояние |
| --- | --- |
| Android UI | Групповой вызов, чат, вложения и ручная сверка Signal safety number; при закрытом приложении доставка — через push (живой distributor не проверен). |
| `CallService` | WebSocket-регистрация/relay, фиксированные группы, lifecycle и закрытие при потере соединения. |
| `SecureStore` | Signal identity/prekeys/sessions, E2EE-сообщения, outbox и локальная история. Синхронизации устройств нет. |
| `LiveCallEngine` | LiveKit Android SDK, публикация аудио, frame encryption и остановка медиасессии при отказе. |
| Node API | Однопроцессная регистрация, публичные bundles, durable mailbox ciphertext, профили, квитанции, пропущенные звонки, blob-вложения, push-регистрации и room grants; не SFU. |
| LiveKit/TURN | Конфиги и runbook — шаблоны, не доказательство работающего production deployment. |

Зафиксированные версии: Android `io.livekit:livekit-android:2.29.0`, Signal `org.signal:libsignal-client`/`libsignal-android:0.104.0`, сервер `livekit-server-sdk:2.19.1`, Kotlin 2.2.20, JDK 21 (bytecode JVM 17). Источники: `app/build.gradle.kts`, `build.gradle.kts`, `server/package.json`.

## Групповой звонок и ключи

1. Создатель указывает 1–7 номеров: вместе с ним фиксированный roster до 8 участников. Приглашённые должны быть онлайн и свободны. Сервер создаёт комнату, менять состав на ходу нельзя.
2. Создатель генерирует новый случайный 32-байтовый `roomKey` и отдельно отправляет его приглашённым в Signal-зашифрованных envelopes. До создания комнаты инициатор вызывает `preparePeer` для каждого адресата; вызов проверяет сохранённое SAS-подтверждение. Перед принятием вызова приложение приглашённого проверяет SAS каждого другого участника roster, а перед расшифровкой проверяет, что identity отправителя совпадает с закреплённой. Таким образом клиенты требуют попарной проверки всех участников; сервер передаёт ciphertext, не ключ комнаты.
3. `join_call` выдаёт только члену roster room-scoped LiveKit JWT на 120 секунд с разрешением публиковать микрофон и подписываться на медиа. JWT сам по себе не включает E2EE; API secret хранится только на сервере.
4. Клиент задаёт ключ в LiveKit `BaseKeyProvider`/`E2EEOptions` и подключает `Room` по WSS. SDK шифрует кадры до отправки в SFU. Android использует нативный frame cryptor SDK (аналог Insertable Streams), а не браузерный JS API `RTCRtpScriptTransform`. Ошибка E2EE или медиа приводит к закрытию звонка, plaintext fallback не предусмотрен.
5. Выход, отклонение, disconnect участника или истечение приглашения через 45 секунд завершает группу. Для нового состава создаются комната и свежий ключ. Live-добавления/удаления и ротации без пересоздания комнаты нет.

**Проверка участников:** до создания комнаты инициатор вызывает `preparePeer` для каждого адресата; каждый такой вызов требует сохранённого SAS-подтверждения. На accept проверяется `state.members.filter { it != state.number }.all { verified(it) }`: это все остальные участники roster, включая инициатора, а не только организатор. Неподтверждённая identity блокирует создание/принятие звонка. Общего room-wide fingerprint нет: проверка попарная по Signal safety numbers, а новый room key передаётся этими индивидуальными Signal sessions. `Security.safetyCode(localSdp, remoteSdp)` — оставшийся helper прежней прямой WebRTC-схемы; LiveKit его не вызывает и общий ключ он не подтверждает.

**Изменения v8 в звонках:** закрытие сокета владельца или вошедшего участника v8 не завершает звонок сразу — сервер держит grace-окно `CALL_RESUME_GRACE_MS` (по умолчанию 15 с, допустимо 0–60 с, 0 отключает); вернувшаяся v8-сессия получает `call_resume` и выдаёт себе новый `room_grant`, медиа у остальных не прерывается. Приглашённые, не вошедшие в звонок, получают приглашение снова. Когда объявленный звонок завершается по причинам `timeout`/`left`/`disconnected`/`declined`, каждому приглашённому, кто не входил и не отклонил, записывается пропущенный вызов: после регистрации клиенту приходит `missed_call` (до 20 записей, 7 суток) до подтверждения `missed_ack`. Приглашённым офлайн уходит push вместо live-`incoming`.

## Инициализация LiveKit Android 2.29.0 с E2EE

Пример следует `LiveCallEngine.kt`. Ключ заранее доставляется каждому участнику только через проверенные индивидуальные Signal sessions; URL, JWT и OkHttp client должны браться из проверенной конфигурации. Room key — не LiveKit API secret.

```kotlin
import android.content.Context
import android.util.Base64
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import okhttp3.OkHttpClient

suspend fun connectEncryptedRoom(
    context: Context,
    url: String,
    token: String,
    roomKey: ByteArray,
    pinnedClient: OkHttpClient,
): Room {
    require(roomKey.size == 32)
    val keyProvider = BaseKeyProvider()
    check(keyProvider.setSharedKey(Base64.encodeToString(roomKey, Base64.NO_WRAP)))
    val room = LiveKit.create(
        appContext = context.applicationContext,
        options = RoomOptions(
            e2eeOptions = E2EEOptions(keyProvider = keyProvider),
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                echoCancellation = true, noiseSuppression = true, autoGainControl = true,
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 510_000, // encoder ceiling, not a guaranteed network bitrate
                dtx = false,
                red = true,
            ),
        ),
        overrides = LiveKitOverrides(okHttpClient = pinnedClient),
    )
    try {
        room.connect(url, token, ConnectOptions(audio = true, video = false))
        return room
    } catch (error: Exception) {
        room.release()
        throw error
    }
}
```

Второй режим приложения — речь 64 000 бит/с с DTX. SDK E2EE по умолчанию использует GCM. Код проверяет `setSharedKey`, отслеживает состояния ошибок E2EE и освобождает Room при завершении. Не логировать ключ, его Base64, токены, plaintext или ciphertext.

510 000 бит/с — верхний encoder/publish limit, не обещание фактической скорости, качества речи или низкой задержки. Результат зависит от устройства, SDK/SFU, полосы и потерь сети, Android scheduler и TURN hops. На произвольной сети нельзя обещать отсутствие lag; меньший speech-профиль может работать лучше.

## Чат и локальная история

- Чат один-на-один. При создании новой сессии сервер атомарно выбирает следующий one-time prekey и возвращает его в поле `preKey` (также в массиве `preKeys`); клиент проверяет подписи bundle относительно закреплённой identity и строит сессию через libsignal `SessionBuilder`/`SessionCipher`. Lookup с `consumePreKey: false` возвращает identity/signed/Kyber bundle без one-time prekeys и ничего не расходует; клиент использует его для проверки контакта или уже существующей сессии. `preKeyFloor` сохраняется с учётной записью: при обновлении bundle сервер отбрасывает ключи с уже выданными ID, поэтому refresh или рестарт не публикуют их повторно. libsignal реализует PQXDH и Double Ratchet. Начальное доверие требует сверить Signal safety number независимым каналом; изменение закреплённой identity блокируется.
- Транспорт — WSS/WebSocket, не gRPC. Сервер хранит durable-очередь ciphertext (до 1000 конвертов, 100 на получателя, 7 суток) до `delivery_ack` получателя; офлайн-получателю уходит push-подсказка «переподключись» без содержимого. Outbox для повтора хранится зашифрованным на устройстве отправителя. `queued` означает надёжную запись в mailbox, не расшифровку или прочтение; квитанции о прочтении (v8) идут silent-конвертами, которые не вызывают push.
- Текст, Signal state/prekeys/sessions и outbox шифруются AES-256-GCM; AES-ключ создаёт Android Keystore. SQLCipher нет: SQLite-схема/индексы и часть метаданных (peer, id, status, направление, sequence, timestamp) остаются открыты в файле. Включены WAL, `secure_delete`, `synchronous=FULL`; это не защищает открытые данные при компрометации работающего устройства/процесса. Android backup выключен; при наличии БД без Keystore-ключа приложение отказывается молча генерировать новый.
- `messages(peer, before, limit)` использует keyset cursor и индекс `(peer, sequence DESC)`: страница SQLite ограничена 100, UI загружает по 40 и держит окно до 200 записей с RecyclerView/ListAdapter/DiffUtil; более старые страницы загружаются отдельно. Это уменьшает объём операций, но не доказывает абсолютное отсутствие лагов. В 0.8 добавлены вложения и голосовые (сервер хранит только ciphertext до `blob_ack`), статусы Доставлено/Прочитано, профили и push; синхронизации между устройствами по-прежнему нет.

## Доверие, приватность и метаданные

| Актив | Что защищено | Что остаётся открытым/вне защиты |
| --- | --- | --- |
| Текст | Signal ciphertext при передаче и в durable-очереди; локальные тела и ratchet state — AES-GCM под ключом Keystore. | Получатель может сохранить текст. Компрометация endpoint раскрывает данные; часть SQLite-метаданных открыта; сервер видит метаданные очереди (отправитель, получатель, время). |
| Аудио | WebRTC transport encryption и LiveKit client E2EE с общим ключом конечных участников. | API/SFU/TURN видят соединения и IP; участник или модифицированный endpoint может записывать звук. |
| Идентичности | Приватные Signal identity/prekeys создаются на устройстве и локально хранятся зашифрованными; room key создаёт инициатор. | Сервер хранит публичные bundles, хеш install token и номер. Подписи/identity проверяет клиент; без внешней сверки SAS при первом контакте возможна подмена bundle. |
| Сеть | SPKI pinning для API и LiveKit WSS затрудняет MITM с сертификатом неизвестного ключа. Собственный TURN relay-only скрывает адреса абонентов друг от друга. | API видит номера, IP подключения, online/routing, roster и timing. SFU/TURN видят участников, IP, время и сетевые характеристики потока. Операторы/traffic analysis не скрываются. TURN не получает OkHttp SPKI pin из `EndpointConfig`. |
| Хранилище сервера | Нет plaintext истории и приватных ключей; durable mailbox хранит только Signal-ciphertext до подтверждения получателя. | JSON store сохраняет token hashes, номера и public bundles; отдельные файлы — имена профилей, push-регистрации, пропущенные звонки и зашифрованные вложения. IP/rate limits, online-сессии, roster, call state и dedup обрабатываются в памяти. Логи reverse proxy/хостинга/SFU/TURN требуют отдельной настройки. Это не zero-knowledge или анонимность. |

Pins API и LiveKit должны быть получены из доверенного источника, настроены отдельно по hostname и иметь заранее установленные backup pins для ротации. При ошибке проверку нельзя обходить или понижать до plaintext. Pinning не защищает от компрометации устройства, ключа клиента или сервера, которому уже доверяют. TURN использует отдельный сертификат: Android не пинит его SPKI.

Сервер проверяет структуру bundle, но криптографические подписи/identity проверяет клиент; сервер сам не доказывает, что любой сторонний клиент включил E2EE. Не включать запись/egress и недоверенных LiveKit-агентов, если они исключены из модели доверия.

## Неподтверждённые свойства

- Потеря последней сети, WebSocket или LiveKit media соединения должна завершить звонок, а не держать бесконечный reconnect. Код задаёт 30-секундный media connect timeout и около 45 секунд на приглашение; это не тест всех Android/OEM/network отказов.
- Активный звонок использует microphone foreground service и уведомление. Push реализован через UnifiedPush (RFC 8291) и опционально APNs без Firebase, но доставка через живой distributor и после force stop не проверена; ограничения фоновых служб требуют проверки на устройствах.
- API рассчитан на один Node процесс и локальный JSON store; call/rate-limit/dedup in-memory state теряется при рестарте. Конфиги `deploy/` — шаблоны, внешнее production-развёртывание не подтверждено.
- Unit и серверные тесты не доказывают latency/качество, нагрузку SFU, работу реального TURN или прохождение security audit. До релиза нужны физические телефоны/сети, испытания group-call flows и отказов, ротации pins, проверки инфраструктурных логов и подписанный release APK.

## Лицензирование

Корневой `LICENSE` содержит полный текст GNU AGPL-3.0 для проекта. Maven POM libsignal 0.104.0 также указывает AGPLv3; upstream предупреждает, что применение вне официальных клиентов Signal не поддерживается и API может меняться. LiveKit Android SDK 2.29.0 распространяется по Apache-2.0. Корневая лицензия не заменяет лицензии third-party зависимостей. Перед распространением APK или запуском модифицированного сетевого сервера проверьте требования к notices/source offer и условия всех компонентов; это не юридическое заключение.

## Источники

Локальные источники: `/tmp/hoplite/workspace/README.md`, `/tmp/hoplite/workspace/app/build.gradle.kts`, `/tmp/hoplite/workspace/build.gradle.kts`, `/tmp/hoplite/workspace/settings.gradle.kts`, `/tmp/hoplite/workspace/app/src/main/AndroidManifest.xml`, `/tmp/hoplite/workspace/app/src/main/java/app/line/CallService.kt`, `/tmp/hoplite/workspace/app/src/main/java/app/line/CallState.kt`, `/tmp/hoplite/workspace/app/src/main/java/app/line/EndpointConfig.kt`, `/tmp/hoplite/workspace/app/src/main/java/app/line/Security.kt`, `/tmp/hoplite/workspace/app/src/main/java/app/line/MainActivity.kt`, `/tmp/hoplite/workspace/app/src/main/java/app/line/crypto/SecureStore.kt`, `/tmp/hoplite/workspace/app/src/main/java/app/line/media/LiveCallEngine.kt`, `/tmp/hoplite/workspace/server/README.md`, `/tmp/hoplite/workspace/server/src/index.js`, `/tmp/hoplite/workspace/server/test/signaling.test.js`, `/tmp/hoplite/workspace/deploy/README.md`, `/tmp/hoplite/workspace/deploy/livekit.yaml`, `/tmp/hoplite/workspace/app/src/test/java/app/line/crypto/SignalProtocolTest.kt`.

Первичные внешние источники:

- LiveKit Android v2.29.0: [E2EEOptions](https://github.com/livekit/client-sdk-android/blob/v2.29.0/livekit-android-sdk/src/main/java/io/livekit/android/e2ee/E2EEOptions.kt), [KeyProvider](https://github.com/livekit/client-sdk-android/blob/v2.29.0/livekit-android-sdk/src/main/java/io/livekit/android/e2ee/KeyProvider.kt), [RoomOptions](https://github.com/livekit/client-sdk-android/blob/v2.29.0/livekit-android-sdk/src/main/java/io/livekit/android/RoomOptions.kt), [Android API reference](https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.e2ee/-e2-e-e-options/index.html), [encryption guide](https://docs.livekit.io/transport/encryption/start/).
- Signal: [PQXDH specification](https://signal.org/docs/specifications/pqxdh/), [Double Ratchet specification](https://signal.org/docs/specifications/doubleratchet/), [libsignal v0.104.0 README](https://github.com/signalapp/libsignal/blob/v0.104.0/README.md), [libsignal v0.104.0 LICENSE](https://github.com/signalapp/libsignal/blob/v0.104.0/LICENSE), [Maven POM](https://build-artifacts.signal.org/libraries/maven/org/signal/libsignal-client/0.104.0/libsignal-client-0.104.0.pom).
- Licenses: [full GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.txt), [LiveKit Android v2.29.0 Apache-2.0 license](https://github.com/livekit/client-sdk-android/blob/v2.29.0/LICENSE).
