# API сервера Line: протоколы 6, 7 и 8

Документ описывает всё, что сервер `server/` принимает и отправляет: WebSocket `/signal`, HTTP `/blob/<uuid>`, файлы данных и порядок безопасного обновления действующего VPS. Источник истины — код в [`server/src`](../server/src) и тесты в [`server/test`](../server/test); при расхождении прав код.

Версия 0.8.0 добавляет протокол **8**. Всё новое включается только для сессий, зарегистрировавшихся с `protocolVersion: 8`. Клиенты 0.7.x (протоколы 6 и 7) работают с сервером 0.8.0 так же, как с 0.7.1, с двумя отличиями из раздела 8: порядок ответа на `envelope` и удалённый Firebase.

## 1. Транспорт

| Что | Значение |
| --- | --- |
| Сигнальный канал | WebSocket `wss://<api-хост>/signal` (TLS завершает Caddy, сервер слушает `HOST:PORT`, по умолчанию `0.0.0.0:3000`). Другие пути на `upgrade` получают `404`. |
| Кадры | Только текстовые JSON-объекты с полем `type`. Двоичный кадр → `invalid_message`, не JSON → `malformed_json`. Максимум 64 KiB на кадр. |
| Регистрация | Первым должен прийти `register` в течение 10 с, иначе `registration_timeout` и закрытие `4002`. Любое другое сообщение до регистрации → `registration_required`. |
| Частота | 40 сообщений в секунду на сокет; превышение → `rate_limited` и закрытие `4003`. |
| Подключения | До 1000 одновременных сокетов (иначе HTTP `503` на `upgrade`). Пинг каждые 10 с, молчащий сокет закрывается. |
| Один номер — одна сессия | Новая регистрация того же номера закрывает прежнюю: ей уходит `error: replaced`, код закрытия `4001`. |
| Коды закрытия | `4001` заменён, `4002` не зарегистрировался вовремя, `4003` заблокирован или превышена частота, `4004` очередь исходящих (512 KiB) переполнена. |
| HTTP | `GET /` и `GET /health` → `{"status":"ok"}` (ровно этот текст). `PUT` и `GET /blob/<uuid>` — вложения (раздел 3.7). Остальное → `404 Not found`. |
| Порт | Один: Caddy проксирует и WebSocket, и `/blob/…` на `127.0.0.1:3000`. Новых хостов и портов не требуется. |

Идентификаторы сообщений, звонков и запросов — UUID (регистр не важен), номера — ровно 8 цифр, `requestId` у `lookup`, `profile_get`, `admin*` — UUID, у `blob_*` — строка `[A-Za-z0-9_-]{1,64}`.

## 2. Жизненный цикл

```text
клиент                                       сервер
  | -- register {token, bundle, protocolVersion} -->
  | <-- registered {number, …}
  | <-- missed_call* , call_resume?            (только v8)
  | <-- incoming?, delivered*, envelope*       (v7+: ожидающее, по порядку)
  | <-- inbox_complete                         (v7+)
  | … обычная работа …
```

1. `register`: токен установки — 64 hex-символа, сервер хранит только SHA-256 токена. Номер (8 случайных цифр) выдаётся при первой регистрации и не меняется. `protocolVersion` по умолчанию 6; значения больше 8 приводятся к 8.
2. После `registered` сервер отдаёт накопленное: для v8 — `missed_call` (все неподтверждённые) и, если звонок ждёт возвращения участника, `call_resume`; для v7+ — ожидающий `incoming`, `delivered` по сохранённым квитанциям, `envelope` из почтового ящика и затем `inbox_complete`. Клиент v6 получает только `incoming` и `envelope` без `inbox_complete`.
3. `inbox_sync` (v7+) повторяет тот же проход вручную: не чаще 10 раз в минуту на номер.
4. Доставка сообщений — «как минимум один раз» до `delivery_ack`; клиент удаляет дубликаты по `id`.

## 3. Каталог сообщений

Обозначения: **C→S** — клиент серверу, **S→C** — сервер клиенту. «v8» — только для сессий с протоколом 8; на v6/v7 такое сообщение отвечает `invalid_message`, как любое неизвестное.

### 3.1 Регистрация и ключи

**C→S `register`** `{ type, token, bundle, protocolVersion? }` — `bundle` = `{ identityKey, registrationId, signedPreKey{id,publicKey,signature}, kyberPreKey{id,publicKey,signature}, preKeys[{id,publicKey}] }`, все ключи в base64, до 1000 одноразовых ключей. Сервер проверяет форму и кодировку, но не подписи. Ошибки: `invalid_token`, `invalid_bundle`, `identity_mismatch` (у установки уже другой `identityKey`), `blocked`, `registration_disabled`, `capacity`, `rate_limited` (30 регистраций в минуту с одного IP), `already_registered`.

**S→C `registered`**

| Поле | Версии | Смысл |
| --- | --- | --- |
| `number` | все | номер из 8 цифр |
| `mediaReady`, `callsEnabled`, `chatEnabled`, `registrationEnabled`, `maxParticipants` | все | возможности и настройки администратора |
| `pushEnabled` | v7, v8 | у номера есть пригодная push-регистрация |
| `protocol` | v8 | `8` |
| `serverTime` | v8 | время сервера, мс с эпохи Unix |
| `features` | v8 | `["profile","push","receipts","missed_calls","attachments","call_resume"]` |
| `pushProviders` | v8 | `["unifiedpush"]`, плюс `"apns"`, если настроен APNs |
| `name` | v8 | собственное имя профиля, если задано |

**C→S `keys`** `{ bundle }` → **S→C** `keys_updated`. Ошибки: `invalid_bundle`, `identity_mismatch`, `chat_disabled`. Идентификаторы одноразовых ключей должны расти: выданные ключи повторно не публикуются.

**C→S `lookup`** `{ to, requestId, consumePreKey? }` → **S→C `bundle`** `{ peer, requestId, bundle, profile? }`. По умолчанию выдаётся и расходуется один одноразовый ключ (`bundle.preKey`); `consumePreKey: false` возвращает только долговременные ключи. Ошибки приходят как `error` с тем же `requestId`: `not_found` (в том числе заблокированный номер), `prekeys_exhausted`, `chat_disabled`. **v8:** если у номера есть запись профиля, в ответе `profile: { name, updatedAt, proto }` (`name` может быть пустым, `proto` — наибольшая версия протокола, с которой номер регистрировался).

### 3.2 Сообщения и квитанции

**C→S `envelope`** `{ to, id, cipherType, body, silent? }` — `cipherType` 2 или 3, `body` — base64, до 24 KiB в декодированном виде. **v8:** `silent: true` — служебный конверт (квитанция о прочтении): доставляется как обычный, но никогда не вызывает push; в почтовом ящике хранится в том же формате, что и любой другой конверт.

Ответ отправителю после **надёжной** записи в почтовый ящик: `queued` `{id}` (v7+) или `sent` `{id}` (v6). Повтор с тем же `id` и тем же содержимым безопасен и снова даёт ответ; тот же `id` с другим содержимым — `id_conflict`. Ошибки (всегда с `id`): `invalid_message`, `chat_disabled`, `blocked`, `not_found`, `mailbox_full`, `id_conflict`, `storage_unavailable`.

Порядок для онлайн-получателя в 0.8.0: сервер **сначала** отправляет ему `envelope`, затем записывает на диск и только после этого отвечает отправителю. Поэтому при `storage_unavailable` получатель мог уже получить конверт — повтор отправителя доставит его ещё раз с тем же `id`, получатель дедуплицирует.

**S→C `envelope`** `{ from, id, cipherType, body }`; **v8** дополнительно `fromName` (имя отправителя, если задано) и `sentAt` (мс, когда сервер принял конверт). v6/v7 получают ровно прежние пять полей.

**C→S `delivery_ack`** `{ id }` (v7+) — получатель сохранил сообщение. Сервер удаляет конверт, хранит квитанцию 7 суток и шлёт отправителю **S→C `delivered`** `{ id }`; квитанции повторяются при следующей регистрации. Чужой или неизвестный `id` → `unauthorized`, просроченный → `expired`.

**C→S `inbox_sync`** `{}` (v7+) → ожидающий `incoming`, `delivered`, `envelope` и `inbox_complete`; `rate_limited` при превышении 10 в минуту.

Хранение: до 1000 конвертов всего, 100 на получателя, 7 суток; до 5000 квитанций.

### 3.3 Профили (v8)

**C→S `profile_set`** `{ name }` → **S→C** `profile_updated` `{ name, updatedAt }`. Имя: Unicode NFC, обрезка пробелов по краям, любая серия пробельных символов (включая табуляцию и перевод строки) становится одним пробелом, от 1 до 32 кодовых точек. Недопустимы управляющие символы, символы форматирования (в том числе нулевой ширины и соединитель ZWJ), суррогаты, символы частного использования, неназначенные и bidi-переопределения U+202A–202E, U+2066–2069 → `invalid_profile`. Пустая строка (после нормализации) стирает имя: ответ `{ name: "", updatedAt: 0 }`. Повтор того же имени не меняет `updatedAt`. Лимит — 10 запросов в минуту на номер (`rate_limited`). Ошибки: `invalid_message` (нет `name` или не строка), `invalid_profile`, `blocked`, `chat_disabled`.

**C→S `profile_get`** `{ requestId, numbers }` — до 32 уникальных номеров → **S→C `profiles`** `{ requestId, profiles: [{ number, name, updatedAt, proto }] }`. В списке только номера, у которых есть запись; незарегистрированные и заблокированные пропускаются. `name` пустое, если имя не задано (запись есть, потому что известна версия протокола). Лимит — 30 запросов в минуту на сессию, `rate_limited` приходит с `requestId`.

Имя доходит до собеседников четырьмя путями: `bundle.profile`, `envelope.fromName`, `incoming.ownerName`, `missed_call.fromName` (все — только v8-получателям). Блокировка номера удаляет его профиль и push-регистрацию; после разблокировки имя нужно задать заново.

### 3.4 Push

Сервер больше не использует Firebase. Push — короткая подсказка «переподключись»; содержимое сообщений и номера в ней не передаются. Подробная настройка — в [`PUSH-SETUP.md`](PUSH-SETUP.md).

**C→S `push_register` (v8)**

- UnifiedPush: `{ provider: "unifiedpush", endpoint, pubKey?, auth? }`. `endpoint` — только `https`, без логина и пароля, не длиннее 2048 символов, порт не `0`, хост — публичный. Адреса `localhost`, частных диапазонов, loopback, link-local, CGNAT, ULA, мультикаст и зарезервированных, а также имена без точки отклоняются, если не включено `PUSH_ALLOW_PRIVATE_ENDPOINTS=true`. `pubKey` — несжатая точка P-256 (65 байт), `auth` — 16 байт; оба в base64url (обычный base64 тоже принимается), задаются только вместе. С ключами тело push шифруется по RFC 8291.
- APNs: `{ provider: "apns", token, environment? }` — `token` 64–200 hex-символов, `environment` — `production` (по умолчанию) или `sandbox`. Работает только если APNs настроен на сервере.
- Ответ `push_registered` `{ pushEnabled: true, provider }`. Ошибки: `invalid_push_endpoint` (в том числе лишние поля), `push_provider_unsupported` (чужой провайдер или APNs не настроен), `rate_limited` (20 регистраций в минуту на номер), `invalid_message`.
- У номера одна регистрация: новая заменяет прежнюю. Повтор той же регистрации ничего не записывает.
- Старый вид `{ token }` (FCM, v7 и v8) принимается и отвечает `push_registered` `{ pushEnabled: false }`, ничего не сохраняя. Версии 6 получают `invalid_message`, как и раньше.

**C→S `push_unregister`** `{}` (v8) → `push_unregistered`. Не ошибка, если регистрации не было.

Когда сервер отправляет push: получатель сообщения не в сети; приглашённый в звонок не в сети; участник звонка не получил `ended`. Полезная нагрузка — ровно JSON:

```json
{"kind":"message","id":"<id конверта>"}
{"kind":"call","id":"<callId>"}
{"kind":"call_ended","id":"<callId>","reason":"timeout|declined|left|disconnected|media_unavailable"}
```

`reason` присутствует только у `call_ended` и только из этого списка. Другие причины завершения (`admin_ended`, `admin_disabled`, `blocked`) уходят без `reason`.

Правила отправки в UnifiedPush: `POST` на endpoint, заголовки `TTL` (86400 для сообщений, 45 для звонков) и `Urgency: high`; без ключей `Content-Type: application/json`, с ключами `Content-Encoding: aes128gcm` и `application/octet-stream`; тело не больше 4 KiB; таймаут 5 с; один повтор через 1 с при сетевой ошибке или ответе 5xx; редиректы не выполняются; `404` и `410` удаляют сохранённый endpoint. Защита от SSRF: DNS-ответ проверяется собственной функцией `lookup`, и соединение открывается ровно на проверенный адрес, поэтому подмена DNS после проверки не перенаправит запрос во внутреннюю сеть.

Склейка: push о сообщениях для одного номера не чаще раза в 1500 мс — первый уходит сразу, остальные за это время сливаются в один следующий с последним `id` (он отменяется, если пользователь уже в сети). Push о звонках никогда не склеиваются.

### 3.5 Звонки

Не изменились для v6/v7.

**C→S `create_call`** `{ members }` — от 1 до `maxParticipants − 1` уникальных номеров, кроме своего. Ответ владельцу — **S→C `call_created`** `{ callId, room, members, owner }` (`members` включает владельца). Ошибки: `calls_disabled`, `invalid_message`, `busy`, `not_found`, `blocked`, `offline` (у приглашённого нет сокета и нет пригодной push-регистрации; с `to`), `media_not_configured`, `media_unavailable`. Онлайн-приглашённым уходит **S→C `incoming`** `{ callId, room, members, owner }` (v8 дополнительно `ownerName`), офлайн — push `call`.

**C→S `join_call`** `{ callId }` → **S→C `room_grant`** `{ callId, room, members, owner, url, token }` — токен LiveKit на 120 с, только микрофон. Повторный `join_call` участника, который уже вошёл, безопасен: выдаётся новый `room_grant` (так же вёл себя и 0.7.1). Ошибки: `unauthorized`, `call_not_ready`, `calls_disabled`, `media_not_configured`, `media_unavailable`.

**C→S `leave_call` / `decline_call`** `{ callId }` завершают звонок для всех. Звонок без ответа заканчивается через 45 с. **S→C `ended`** `{ callId, reason }`, причины: `timeout`, `declined`, `left`, `disconnected`, `media_unavailable`, `admin_ended`, `admin_disabled`, `blocked`.

**Возврат в звонок (v8).** Если у v8-сессии владельца или вошедшего участника закрылся сокет, звонок не завершается сразу: сервер ждёт `CALL_RESUME_GRACE_MS` (по умолчанию 15000 мс, допустимо 0–60000; 0 отключает). Если тот же номер заново зарегистрировался с протоколом 8 за это время, после `registered` приходит **S→C `call_resume`** `{ callId, room, members, owner, joined }`; клиент отправляет `join_call` и получает новый `room_grant`. Звонок продолжают остальные участники, медиа не прерывается. По истечении срока звонок завершается с причиной `disconnected`. Участник, вернувшийся как v6/v7, завершает звонок сразу. v6/v7-участники и не вошедшие приглашённые обрабатываются как в 0.7.1: сокет закрылся — звонок завершён (для вошедших) либо продолжает звонить (для приглашённых).

**Пропущенные звонки (v8).** Когда объявленный звонок завершается по причинам `timeout`, `left`, `disconnected` или `declined`, каждому приглашённому (кроме владельца), кто не входил в звонок и не отклонял его, сервер записывает пропущенный вызов. После каждой регистрации v8-сессии приходит **S→C `missed_call`** `{ callId, from, at, fromName? }` для каждой неподтверждённой записи — пока клиент не пришлёт **C→S `missed_ack`** `{ callId }` (чужой или неизвестный `callId` игнорируется без ответа). Хранятся не более 20 записей на получателя (остаются новейшие) и 7 суток. Онлайн-сессии `missed_call` вживую не получают: они узнают о завершении из `ended`, поэтому клиент обязан дедуплицировать по `callId`.

### 3.6 Администратор

Не изменился. `admin_login` `{ requestId, code }` → `admin_result` `{ requestId, ok, expiresAt }` (сессия 5 минут, привязана к сокету; 5 неудач с адреса за минуту — блокировка на минуту). `admin` `{ requestId, action, … }` с действиями `status`, `update_settings` (`callsEnabled`, `chatEnabled`, `registrationEnabled`, `maxParticipants` 2–8), `block`/`unblock` `{ number }`, `end_call` `{ callId }`, `clear_events`, `logout` → `admin_result` `{ requestId, ok, result | error }`. После `update_settings` все сессии получают **S→C `capabilities`** `{ callsEnabled, chatEnabled, registrationEnabled, maxParticipants, mediaReady }`. Блокировка закрывает сессию (`error: blocked`, `4003`) и удаляет сообщения, push-регистрацию, профиль, пропущенные звонки и вложения номера. Ошибки администратора: `registration_required`, `admin_disabled`, `rate_limited`, `invalid_credentials`, `login_cancelled`, `invalid_message`, `invalid_settings`, `unauthorized`, `expired`, `not_found`, `self_block_denied`, `unknown_action`, `storage_unavailable`.

### 3.7 Вложения (v8)

Сервер хранит только шифртекст, выбранный клиентом; ключи и содержимое ему недоступны. Ссылка на блоб и ключ передаются получателю внутри обычного зашифрованного конверта. Всё создаётся лениво: каталог `BLOB_DIR` появляется при первой загрузке.

**C→S `blob_create`** `{ requestId, id, to, size }` — `id` выбирает клиент (UUID), `to` — существующий незаблокированный номер, `size` — целое от 1 до `BLOB_MAX_BYTES` (по умолчанию 26 MiB). **S→C `blob_ticket`** для загрузки:

```json
{ "type": "blob_ticket", "requestId": "…", "id": "<uuid>", "method": "PUT", "path": "/blob/<uuid>",
  "token": "<43 символа base64url>", "expiresAt": 1791140000000, "offset": 0, "size": 1000 }
```

`offset` — сколько байт сервер уже хранит (0 для нового блоба, больше для прерванной загрузки, `size` для завершённого). Повторный `blob_create` с теми же `id`, `to` и `size` выдаёт новый токен и актуальный `offset` — так возобновляют загрузку; другие параметры → `id_conflict`. Билет действует 10 минут, новый билет отменяет прежний.

**C→S `blob_get`** `{ requestId, id }` → **S→C `blob_ticket`** для скачивания, того же вида, но `method: "GET"`, `offset: 0`, `size` — размер блоба и `from` — номер отправителя. Скачать может получатель и отправитель, только завершённый блоб.

**C→S `blob_ack`** `{ id }` — получатель сохранил вложение: сервер удаляет блоб и все билеты. Успех без ответа; чужой номер → `error: unauthorized` с `id`; неизвестный `id` — без ответа.

Ошибки `blob_create` и `blob_get` приходят как `error` с `requestId` (если он корректен): `invalid_message`, `chat_disabled`, `blocked`, `rate_limited` (20 `blob_create` в минуту на номер), `blob_too_large`, `not_found`, `blob_quota`, `id_conflict`, `unauthorized`, `expired`, `storage_unavailable`.

**HTTP `PUT /blob/<uuid>`** — заголовок `Authorization: Bearer <token>`. Тело — шифртекст с позиции `offset`. Если `offset > 0`, обязателен `Content-Range: bytes <offset>-<size-1>/<size>`; без `Content-Range` загрузка начинается с нуля и затирает прежнюю частичную.

| Статус | Когда |
| --- | --- |
| `201` | Блоб завершён. Тело `{"id":"<uuid>","size":<size>}`. |
| `202` | Принята часть: `Upload-Offset` — сколько хранится. Продолжайте с этого места. |
| `400` | Неверные `Content-Range`/`Content-Length`, пустое тело, тело короче заявленного. |
| `401` | Нет, неверный или просроченный токен. Запросите новый билет. |
| `403` | Токен от другого метода (например, билет на скачивание), чат выключен или номер заблокирован. |
| `404` | Блоб неизвестен, удалён или просрочен; путь не UUID. |
| `409` | `Upload-Offset` не совпадает с хранимым, блоб уже завершён или идёт другая загрузка. Заголовок `Upload-Offset` — реальное состояние; запросите билет заново. |
| `413` | Тело больше, чем осталось до `size`. |
| `429`, `503` | Слишком много одновременных передач (16 загрузок, 64 скачивания) или временный сбой; `Retry-After: 5`. |

**HTTP `GET /blob/<uuid>`** — тот же `Authorization`. `200` с телом целиком и `Content-Length`; `Range: bytes=<с>-[<по>]` → `206` с `Content-Range: bytes <с>-<по>/<size>`; начало за пределами → `416` с `Content-Range: bytes */<size>`; `401`, `403`, `404`, `429` — как выше. Ответы всегда с `Cache-Control: no-store`. Перенаправлений нет.

Лимиты по умолчанию: блоб до 26 MiB (`BLOB_MAX_BYTES`, не более 64 MiB), хранение 7 суток после завершения (`BLOB_TTL_MS`, не более 14), незавершённые загрузки — 1 час, общая квота 1 GiB (`BLOB_QUOTA_BYTES`), на отправителя в хранении — 200 MiB (`BLOB_SENDER_PENDING_BYTES`) и 1000 блобов, всего 10000 блобов, простой загрузки больше 30 с обрывает соединение. Файлы блобов: `<uuid>.part` (идёт загрузка), `<uuid>.bin` (готов), `<uuid>.json` (метаданные), права `0600`, каталог `0700`.

## 4. Коды ошибок

Формат: `{ "type": "error", "code": "<код>", … }` и, по случаю, `id`, `requestId`, `callId` или `to`.

| Код | Смысл |
| --- | --- |
| `invalid_message`, `malformed_json` | сообщение не соответствует схеме, неизвестный тип, не JSON |
| `registration_required`, `registration_timeout`, `already_registered` | порядок регистрации |
| `invalid_token`, `invalid_bundle`, `identity_mismatch` | регистрация и ключи |
| `registration_disabled`, `capacity`, `blocked`, `replaced` | отказ в регистрации; сессия вытеснена |
| `rate_limited` | превышен лимит (раздел 5) |
| `chat_disabled`, `calls_disabled` | функция выключена администратором |
| `not_found`, `prekeys_exhausted`, `unauthorized`, `expired` | адресат, ключи, права, срок |
| `id_conflict`, `mailbox_full` | почтовый ящик |
| `storage_unavailable` | не удалось записать на диск; повторите позже |
| `busy`, `offline`, `call_not_ready`, `media_not_configured`, `media_unavailable` | звонки |
| `invalid_push_endpoint`, `push_provider_unsupported` | push (v8) |
| `invalid_profile` | имя профиля (v8) |
| `blob_too_large`, `blob_quota` | вложения (v8) |

## 5. Лимиты

| Лимит | Значение |
| --- | --- |
| Регистрации | 30 в минуту с одного IP |
| Сообщения сокета | 40 в секунду |
| `inbox_sync` | 10 в минуту на номер |
| `profile_set` | 10 в минуту на номер |
| `profile_get` | 30 в минуту на сессию, до 32 номеров |
| `push_register` (v8) | 20 в минуту на номер |
| `blob_create` | 20 в минуту на номер |
| Вход администратора | 5 неудач в минуту с адреса, затем блокировка на минуту |
| Очередь записи | 1000 операций, затем `rate_limited` |

## 6. Файлы данных

Все файлы пишутся атомарной заменой (временный файл, `fsync`, `rename`) с правами `0600`. Формат существующих файлов в 0.8.0 **не изменён**. Серверный процесс один; не запускайте несколько копий на одних файлах.

| Файл | Назначение | Формат | Кто пишет |
| --- | --- | --- | --- |
| `DATA_FILE` | личности: хеш токена → номер, публичный набор ключей | `{ "version": 2, "identities": { "<sha256 токена>": { "number", "bundle", "preKeyFloor" } } }`; версия 1: `{ "version": 1, "identities": { "<sha256>": "<номер>" } }` читается, версия 2 пишется при следующей регистрации | как в 0.7.1 |
| `ADMIN_DATA_FILE` (`${DATA_FILE}.admin.json`) | настройки и список блокировок | `{ "version": 1, "settings": {…}, "blockedNumbers": […] }` | как в 0.7.1 |
| `MAILBOX_FILE` (`${DATA_FILE}.mailbox.json`) | конверты и квитанции | `{ "version": 1, "envelopes": […], "receipts": […] }` | как в 0.7.1; при старте убирает просроченное, как и раньше |
| `PUSH_TOKEN_FILE` (`${DATA_FILE}.push.json`) | **устаревший** файл FCM-токенов | `{ "version": 1, "tokens": { "<номер>": "<токен>" } }` | **не читается, не изменяется и не удаляется** |
| `PROFILE_FILE` (`${DATA_FILE}.profiles.json`) | имена профилей и версия протокола | `{ "version": 1, "profiles": { "<номер>": { "name", "updatedAt", "proto" } } }` | новый, создаётся при первой записи |
| `PUSH_ENDPOINT_FILE` (`${DATA_FILE}.push-endpoints.json`) | push-регистрации | `{ "version": 1, "endpoints": { "<номер>": { "provider", "endpoint" \| "token", … , "registeredAt" } } }` | новый |
| `MISSED_FILE` (`${DATA_FILE}.missed.json`) | неподтверждённые пропущенные звонки | `{ "version": 1, "calls": [{ "to", "from", "callId", "at", "reason" }] }` | новый |
| `BLOB_DIR` (`${DATA_FILE}.blobs/`) | шифртекст вложений | см. раздел 3.7 | новый |

Все восемь путей (`DATA_FILE`, администратор, почта, `PUSH_TOKEN_FILE`, профили, push-регистрации, пропущенные звонки, `BLOB_DIR`) должны различаться, иначе сервер не запустится. Новые файлы и каталог создаются только при первой записи; пока ими никто не пользовался, сервер на диске ничего не добавляет.

Токены установок в открытом виде нигде не хранятся. Резервные копии содержат хеши токенов, публичные ключи, зашифрованные сообщения и служебные метаданные (кто кому писал и когда) — защищайте их как секрет.

## 7. Переменные окружения

Существующие переменные не меняются: `HOST`, `PORT`, `DATA_FILE`, `ADMIN_DATA_FILE`, `MAILBOX_FILE`, `PUSH_TOKEN_FILE`, `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `ADMIN_PASSWORD_HASH`. `FCM_PROJECT_ID` и `GOOGLE_APPLICATION_CREDENTIALS` теперь игнорируются без сообщений и без ошибок: оставьте их в окружении как есть, это нужно для отката.

Новые, все необязательные:

| Переменная | По умолчанию | Смысл |
| --- | --- | --- |
| `PROFILE_FILE`, `PUSH_ENDPOINT_FILE`, `MISSED_FILE`, `BLOB_DIR` | рядом с `DATA_FILE`, см. раздел 6 | расположение новых хранилищ |
| `BLOB_MAX_BYTES` | 27262976 (26 MiB), максимум 64 MiB | размер одного блоба |
| `BLOB_TTL_MS` | 604800000 (7 суток), максимум 14 суток | срок хранения завершённого блоба |
| `BLOB_QUOTA_BYTES` | 1 GiB | общий объём вложений |
| `BLOB_SENDER_PENDING_BYTES` | 200 MiB | объём вложений одного отправителя |
| `CALL_RESUME_GRACE_MS` | 15000 (0–60000, вне диапазона обрезается) | ожидание возврата участника звонка; нечисловое значение останавливает запуск |
| `PUSH_ALLOW_PRIVATE_ENDPOINTS` | выключено | `true` разрешает push на частные адреса (нужно только если дистрибьютор доступен серверу лишь по внутреннему адресу) |
| `APNS_KEY_FILE`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_TOPIC` | не заданы | включают APNs; задавать только все четыре сразу, иначе сервер не запустится с понятной ошибкой |

## 8. Совместимость

| Что | v6 | v7 | v8 |
| --- | --- | --- | --- |
| `register`, `lookup`, `envelope`, звонки, администратор | как в 0.7.1 | как в 0.7.1 | как в 0.7.1 |
| Ответ на `envelope` | `sent` | `queued` | `queued` |
| `delivery_ack`, `delivered`, `inbox_sync`, `inbox_complete`, `pushEnabled` | – | да | да |
| Push | – | `{token}` → `pushEnabled: false` (Firebase удалён) | UnifiedPush / APNs |
| `protocol`, `serverTime`, `features`, `pushProviders`, `name` в `registered` | – | – | да |
| Профили, `silent`, `missed_call`, `call_resume`, `blob_*`, `push_unregister`, `fromName`, `sentAt`, `ownerName`, `profile` | – | – | да |
| Возврат в звонок | завершает сразу | завершает сразу | ожидает `CALL_RESUME_GRACE_MS` |

Отличия от 0.7.1 для старых клиентов два. Первое: ответ `queued`/`sent` отправителю приходит после того, как онлайн-получатель уже получил конверт (раньше — до этого). Содержание ответов и гарантии «надёжно записано» те же. Второе: установки 0.7.1, которые зарегистрировали FCM-токен, перестают получать push: Firebase удалён по требованию проекта. Сообщения при этом не теряются — они ждут в почтовом ящике до следующего подключения.

## 9. Обновление действующего VPS без потери данных

Что гарантирует код: формат и смысл файлов личностей, администратора и почты не менялись; токены, правила хеширования, выдача номеров, `register` и API администратора прежние; устаревший `push.json` не читается и не перезаписывается; новые данные пишутся только в новые файлы. Переменные окружения и секреты остаются как есть.

Ниже пути для примера: код `/opt/line`, данные `/var/lib/line`, окружение `/etc/line/api.env`, юнит `line-api`. Подставьте свои. Пока вы не дошли до пункта 5, действующий сервер не затрагивается.

1. **Резервная копия.** Остановите сервис на минуту, чтобы копия была согласованной, и заархивируйте каталог данных вместе с окружением:

   ```sh
   sudo systemctl stop line-api
   sudo tar -C /var/lib -czpf ~/line-data-$(date +%F).tgz line
   sudo cp -a /etc/line/api.env ~/api.env.$(date +%F)    # секреты: храните только у себя
   sudo systemctl start line-api
   ```

2. **Новый код рядом со старым.** Не заменяйте работающий каталог: положите релиз в отдельный и поставьте зависимости (Node 24, как и прежде):

   ```sh
   # распакуйте релиз 0.8.0 (git clone нужного тега или архив) в /opt/line-0.8.0
   cd /opt/line-0.8.0/server && sudo -u line npm ci --omit=dev
   ```

3. **Проверка данных на копии.** Скрипт только читает файлы теми же загрузчиками, что и сервер, и завершается с кодом 0 («compatible») либо 1:

   ```sh
   sudo rm -rf /tmp/line-check && sudo cp -a /var/lib/line /tmp/line-check
   cd /opt/line-0.8.0/server
   sudo -u line env ADMIN_DATA_FILE=/tmp/line-check/admin.json \
     node scripts/check-data-compat.mjs /tmp/line-check/identities.json
   ```

   Если вы переопределяли другие пути (`MAILBOX_FILE`, `PUSH_TOKEN_FILE`, …), передайте их так же, указав на копию. Скрипт печатает проверенные пути — убедитесь, что это `/tmp/line-check`. Любая строка `FAIL` — остановка: данные не меняйте, пришлите вывод разработчику. Запускать проверку на боевых файлах не нужно.

4. **Тест на копии (по желанию).** `DATA_FILE=/tmp/line-check/identities.json ADMIN_DATA_FILE=/tmp/line-check/admin.json PORT=3100 HOST=127.0.0.1 node src/index.js` и `curl http://127.0.0.1:3100/health` — на копии, не на боевых данных; остановите процесс и удалите `/tmp/line-check`.

5. **Переключение.** Окружение `/etc/line/api.env` и юнит не меняются.

   ```sh
   sudo systemctl stop line-api
   sudo mv /opt/line /opt/line-0.7.1 && sudo mv /opt/line-0.8.0 /opt/line
   sudo systemctl start line-api
   curl -fsS https://api.<домен>/health          # {"status":"ok"}
   sudo journalctl -u line-api -n 50 --no-pager  # без ошибок запуска
   ```

   Если юнит запускает код из `/opt/line/server`, а каталог данных вне него, ничего больше менять не нужно. Если в юните включён `ProtectSystem=strict`, каталог данных должен быть в `ReadWritePaths` (рядом с `identities.json` появятся новые файлы).

6. **Проверка.** Подключите телефон 0.7.1: он должен войти под тем же номером, получить накопленные сообщения и звонить. Подключите телефон 0.8.0: в `registered` будут `protocol: 8` и `features`.

**Откат.** Данные не требуют восстановления: остановите сервис, верните старый каталог кода (`mv /opt/line /opt/line-0.8.0 && mv /opt/line-0.7.1 /opt/line`), запустите. Файлы 0.8.0 совместимы с 0.7.1: он игнорирует `profiles.json`, `push-endpoints.json`, `missed.json` и `blobs/`, а `push.json` не менялся, так что FCM снова заработает с прежним окружением. Если и данные нужно вернуть на момент обновления — распакуйте архив из пункта 1 при остановленном сервисе. Новые файлы можно оставить или удалить вручную: в них только имена профилей, push-регистрации, пропущенные звонки и зашифрованные вложения.

Перед выпуском сервера и после изменений в нём выполняйте в `server/`: `npm ci && npm test` (включает проверку на фикстурах данных 0.7.1) и `npm run check:data -- <копия DATA_FILE>`.
