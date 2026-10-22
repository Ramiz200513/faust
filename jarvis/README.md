# Фауст — приложение для телефона и общий протокол (Kotlin)

Эта папка — проект Android Studio из двух модулей:

```
:shared      — протокол сообщений (Kotlin Multiplatform: android + jvm)
:androidApp  — приложение на Jetpack Compose
```

Место в системе:

```
Телефон (:androidApp)  <--WebSocket-->  Релей (jarvis-relay)  <--WebSocket-->  Агент (jarvis-agent, ПК)
```

Объяснение для защиты — `docs/объяснение/2-Android-приложение.md`
(всё вместе одной страницей — `docs/объяснение/faust-docs.html`).

## Протокол (`:shared`)

Телефон, релей и агент обмениваются одними и теми же JSON-сообщениями. Здесь
они описаны на Kotlin; в релее и агенте лежат Java-зеркала
(`jarvis-relay/.../protocol`, `jarvis-agent/.../protocol`) с теми же именами
полей. **Любое изменение протокола делается сразу в трёх местах.**

```
shared/src/commonMain/kotlin/com/jarvis/protocol/
  Protocol.kt    — конверт сообщений (JarvisMessage): Auth, CommandRequest/Result,
                   ScreenFrame, вопросы агента (agent_question), план (agent_plan),
                   список репозиториев (repo_list), Ping/Pong; настроенный JarvisJson
  Command.kt     — все команды: 1) NaturalLanguage — сырая фраза (разбирает релей);
                   2) системные: SetVolume, OpenUrl, OpenApp, KillProcess…;
                   3) ИИ-агент: AgentTask + Click/TypeText/Scroll, AnswerQuestion
  Result.kt      — статусы (OK/ERROR/DENIED/TIMEOUT) и типизированные ответы
  Whitelist.kt   — белый список команд (граница безопасности — та же, что в релее)
shared/src/commonTest/… — round-trip тесты сериализации (эталон JSON)
```

Тесты протокола и сборка приложения:
```
.\gradlew.bat :shared:allTests
.\gradlew.bat :androidApp:assembleDebug
```

## Пример сообщения на проводе

Голосовая команда «сделай потише» проходит так:

```json
// телефон -> релей
{"type":"command_request","requestId":"a1","command":{"type":"nl","text":"сделай потише"}}

// релей разобрал фразу (своя модель / Gemini), проверил белый список, шлёт агенту:
{"type":"command_request","requestId":"a1","command":{"type":"set_volume","level":30}}

// агент -> релей -> телефон
{"type":"command_result","requestId":"a1","status":"ok"}
```

---

## Android-приложение (`:androidApp`)

Клиент на Jetpack Compose. Зависит от `:shared`. Файлы в
`androidApp/src/main/kotlin/com/jarvis/app/`, главные:

| Файл | Что это |
|---|---|
| `MainActivity.kt` | хост-Activity, переключение экранов, меню с разделами |
| `ChatViewModel.kt` | чат: состояние, отправка команд, вопросы агента, план-чеклист |
| `JarvisClient.kt`, `JarvisConnectionService.kt` | WebSocket на OkHttp с переподключением; фоновая служба с уведомлением |
| `DevicesScreen.kt`, `DevicesViewModel.kt`, `DeviceRepository.kt` | привязанные ПК, статус онлайн, выбор активного (DataStore) |
| `PairingScreen.kt`, `PairingController.kt`, `RelayPairing.kt`, `PairingQr.kt`, `QrScan.kt` | привязка по коду или QR |
| `ScreenViewScreen.kt`, `FrameDecoding.kt` | живой экран ПК с точным нажатием |
| `CustomActionsScreen.kt`, `CustomActions.kt` | свои команды и навыки-рецепты с фразами-триггерами |
| `ProjectsScreen.kt`, `DiffText.kt` | экран «Проекты (git)» и подсветка диффа в вопросах |
| `HistoryScreen.kt`, `TaskHistory.kt` | история задач |
| `MultiPcScreen.kt` | одна команда на несколько ПК |
| `VoiceController.kt`, `VoskWakeWord.kt`, `ListeningOrb.kt` | голос: распознавание, слово-активатор (Vosk, офлайн-модель в `assets/model-ru`), анимация |
| `WakeOnLan.kt`, `FileTransfer.kt`, `Notifications.kt` | включение ПК по сети, передача файлов, уведомления |
| `FaustLogo.kt`, `Theme.kt` | логотип и тема |

### Привязка ПК (паринг) — протокол

Одноразовый код агент показывает на своём экране (текстом и/или QR). Дальше:

```json
// телефон -> релей (вместо auth — токена для этого ПК у телефона ещё нет)
{"type":"pair_request","requestId":"p1","code":"482931"}

// релей нашёл ПК, ожидающий этот код, выдаёт постоянный токен
{"type":"pair_result","requestId":"p1","ok":true,"deviceId":"pc-42","deviceToken":"…","deviceName":"Рабочий ПК"}
```

Телефон сохраняет `deviceId`/`deviceToken` в DataStore и дальше всегда
подключается через обычный `Auth` (role=phone), но уже с этими id/token —
формат `Auth`/`AuthResult` не менялся, кроме одного нового optional-поля:

```json
{"type":"auth_result","requestId":"a1","ok":true,"agentOnline":false}
```

`agentOnline` заполняет релей **только** для role=phone — на связи ли сейчас
агент этого ПК. `null` (поле не пришло) — старый релей, который ещё не умеет
это сообщать; UI в этом случае не показывает «офлайн», а считает соединение
рабочим. Экран «Устройства» опрашивает `agentOnline` для каждого привязанного
ПК короткими соединениями (`RelayPairing.checkStatus`), чтобы показать статус
в списке, не держа соединение открытым для каждого.

QR-код агент кодирует так (relay и name — необязательные подсказки):

```
jarvis-pair://pair?code=482931&relay=ws%3A%2F%2F192.168.0.5%3A8080%2Fws&name=%D0%A0%D0%B0%D0%B1%D0%BE%D1%87%D0%B8%D0%B9%20%D0%9F%D0%9A
```

Релей это поддерживает: `pair_request` обрабатывается как альтернатива `auth`
первым сообщением в сокете (`jarvis-relay/.../pairing`), `agentOnline` заполняется в `auth_result`.

### Запуск
1. На ПК: `start-faust.bat start` в корне проекта (релей + агент).
2. Приложение на телефон по USB: `start-faust.bat phone` (или кнопка Run в Android Studio).
3. Привязка: `start-faust.bat qr` показывает QR на ПК → в приложении «Привязать ПК» → навести камеру.
   QR несёт адрес релея в сети; на эмуляторе адрес релея — `ws://10.0.2.2:8080/ws`
   (**10.0.2.2** — «localhost компьютера» с точки зрения эмулятора).
4. Напиши `открой ютуб` → на ПК откроется сайт; `скрин` → в чат придёт скриншот экрана ПК.

### Важно
- В `AndroidManifest.xml` включён `usesCleartextTraffic="true"` — иначе Android
  блокирует незашифрованный `ws://`. Релей рассчитан на домашнюю сеть; для доступа
  из интернета нужен `wss://` (TLS) или VPN вроде Tailscale.
- Автотестов у `:androidApp` нет — экраны проверялись на эмуляторе (AVD `Medium_Phone`).
