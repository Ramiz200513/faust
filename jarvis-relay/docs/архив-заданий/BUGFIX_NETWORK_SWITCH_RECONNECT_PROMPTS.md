# Багфix: приложение не переподключается при смене сети (Wi-Fi ↔ мобильный интернет)

## Общий контекст

Прочитал текущий `JarvisClient.kt` — там уже есть автопереподключение с
нарастающей паузой (1→2→4…→30 с), но оно срабатывает только от двух
событий: `onFailure` и `onClosed` у самого WebSocket-сокета. Проблема в
том, КАК Android обрывает связь при переключении Wi-Fi → мобильный
интернет (или наоборот): старая сеть пропадает **молча** — телефон не
шлёт TCP FIN/RST по старому соединению (сети физически уже нет, слать
через неё нечего), поэтому OkHttp формально не считает сокет закрытым, а
продолжает ждать. Реального разрыва он дождётся только когда либо (а)
попробует что-то записать в мёртвый сокет и получит таймаут, либо
(б) сработает `pingInterval` — а он сейчас **не настроен вообще**
(`OkHttpClient()` с пустыми настройками = `pingInterval` = 0 = выключен).
Без пинга «тихая смерть» соединения может не обнаруживаться очень долго
(иногда по TCP-таймаутам ОС — это минуты), поэтому со стороны выглядит
так, будто приложение просто перестало подключаться.

Второй недостающий кусок — приложение никак не следит за самим фактом
смены активной сети. Даже если бы пинг сработал через 15-20 секунд, это
всё ещё не так быстро, как могло бы быть: Android даёт явное системное
событие «активная сеть сменилась» (`ConnectivityManager.NetworkCallback`),
и на него никто не подписан.

Два независимых, дополняющих друг друга исправления:

1. **Включить пинг** на WebSocket-клиенте — чтобы мёртвый сокет
   обнаруживался за секунды, а не за неопределённое время, даже если
   пропустим момент смены сети.
2. **Слушать смену активной сети** через `ConnectivityManager` и сразу
   форсировать переподключение (`reconnectNow()`), не дожидаясь ни пинга,
   ни backoff-паузы — самый быстрый и надёжный путь именно для сценария
   «переключил Wi-Fi/мобильные данные».

Правки только в `androidApp`: `JarvisClient.kt`, `ChatViewModel.kt`,
`AndroidManifest.xml`.

---

## Промпт 1 — `JarvisClient.kt`: пинг + немедленное переподключение

**1.1.** Добавь импорт вверху файла (рядом с остальными):

```kotlin
import java.util.concurrent.TimeUnit
```

**1.2.** Замени

```kotlin
    private val http = OkHttpClient()
```

на

```kotlin
    // pingInterval — без него "тихий" обрыв связи (сеть пропала без TCP FIN/RST,
    // именно так происходит при переключении Wi-Fi <-> моб. интернет) может
    // не обнаруживаться очень долго. С пингом каждые 15 с мёртвый сокет
    // обнаруживается быстро — не отвечает на пинг -> onFailure -> reconnect.
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
```

**1.3.** Добавь новый публичный метод — например, сразу после `disconnect()`:

```kotlin
    /**
     * Немедленно переподключиться, не дожидаясь текущей паузы backoff.
     * Вызывается при смене активной сети (Wi-Fi -> моб. интернет и обратно) —
     * старый сокет, скорее всего, уже мёртв, но сам WebSocket мог этого ещё
     * не заметить (см. pingInterval выше — до 15 с задержки).
     */
    fun reconnectNow() {
        if (!wantConnected) return
        cancelPendingReconnect()
        nextDelayMs = START_DELAY_MS
        openSocket()
    }
```

---

## Промпт 2 — `ChatViewModel.kt`: подписка на смену активной сети

**2.1.** Добавь импорты (рядом с остальными в начале файла):

```kotlin
import android.net.ConnectivityManager
import android.net.Network
```

**2.2.** Добавь поля — например, сразу после `private var isBackgrounded = false`:

```kotlin
    // Следим за сменой активной сети (Wi-Fi <-> моб. интернет), чтобы форсировать
    // переподключение сразу, а не ждать, пока сам WebSocket заметит обрыв.
    private val connectivityManager = app.getSystemService(ConnectivityManager::class.java)

    // null до первого onAvailable. Первый вызов — это просто "сеть есть при
    // старте", его уже обрабатывает обычный connect() из init; реагировать
    // нужно только когда СЕТЬ РЕАЛЬНО СМЕНИЛАСЬ (network не равен предыдущему).
    private var lastActiveNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = lastActiveNetwork != null && lastActiveNetwork != network
            lastActiveNetwork = network
            if (changed) client.reconnectNow()
        }
    }
```

**2.3.** Зарегистрируй колбэк — добавь первой строкой в `init { ... }`, перед
`viewModelScope.launch { ... }`:

```kotlin
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
```

То есть `init` целиком становится:

```kotlin
    init {
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        viewModelScope.launch {
            val activeDeviceFlow = combine(devicesRepo.devices, devicesRepo.activeDeviceId) { devices, id ->
                devices.find { it.id == id }
            }
            // ...остальное без изменений...
```

**2.4.** Добавь `onCleared()` — например, в самом конце класса, после
`private fun add(m: ChatMessage) { ... }`:

```kotlin
    /** ViewModel уничтожается вместе с Activity — отписываемся, чтобы не утечь. */
    override fun onCleared() {
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        super.onCleared()
    }
```

---

## Промпт 3 — `AndroidManifest.xml`: разрешение на чтение состояния сети

`registerDefaultNetworkCallback` требует `ACCESS_NETWORK_STATE` (обычное,
не runtime-разрешение — не будет всплывающего диалога). Добавь рядом с
`INTERNET`:

```xml
    <!-- Нужен для сети. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Нужен, чтобы ловить смену активной сети (Wi-Fi <-> моб. интернет) и
         сразу форсировать переподключение — см. ChatViewModel.networkCallback. -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## Проверка

1. Собери и запусти приложение, дождись «подключено ✓».
2. Открой шторку быстрых настроек, включи авиарежим на 2-3 секунды и
   выключи (или просто выключи Wi-Fi) — статус должен пройти через
   «нет связи…» / «переподключение…» и вернуться в «подключено ✓» в
   течение нескольких секунд.
3. Основной сценарий бага: находясь на Wi-Fi и подключённым, выключи
   Wi-Fi так, чтобы телефон тут же перескочил на мобильный интернет (не
   авиарежим, а именно переключение сети на живую) — статус должен
   практически сразу показать переподключение и снова дойти до
   «подключено ✓», без зависания на «нет связи» на долгое время.
4. То же самое в обратную сторону — включи Wi-Fi обратно, находясь на
   мобильном интернете.
5. Пока идёт `agent_task` (несколько шагов) — переключи сеть посреди
   выполнения. Задача не должна «потеряться»: после восстановления связи
   финальный `CommandResult` должен всё равно дойти (это уже гарантирует
   relay/агент — они не знают о смене сети телефона, просто ждут, когда
   клиент снова будет на связи).
6. Сверни приложение (шаг 5 из прошлого промпта — фоновый сервис) и
   переключи сеть, пока оно свёрнуто — переподключение должно сработать
   так же, пока жив foreground-сервис/ViewModel.
7. Убедись, что при обычном закрытии приложения (или отвязке ПК через
   «Отвязать этот ПК») ничего не падает и не остаётся висящих подписок —
   `onCleared()` должен вызываться штатно вместе с уничтожением `ViewModel`.
