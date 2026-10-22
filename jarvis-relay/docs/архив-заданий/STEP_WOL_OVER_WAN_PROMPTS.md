# Шаг: Wake-on-LAN не только в домашней сети (пункт 5 бэклога)

## Общий контекст

Сейчас 🔌 в "Устройства" шлёт magic packet широковещательно (`255.255.255.255:9`)
— работает, только если телефон в той же Wi-Fi-сети, что и ПК. Если ты в
мобильном интернете где-то далеко — разбудить ПК так нельзя, широковещательный
адрес физически не выходит за пределы локальной сети.

Чтобы разбудить ПК из интернета, нужны две вещи:
1. **Знать, куда стучаться** — публичный IP домашнего роутера, за которым
   стоит ПК. Он может со временем меняться (не у всех провайдеров статический
   IP).
2. **Роутер должен переслать** пришедший снаружи UDP-пакет на локальный IP ПК
   (port forwarding) — иначе пакет придёт на роутер и там же и умрёт.

Пункт 2 — чисто ручная настройка роутера, кода тут нет и быть не может (ниже
подробная инструкция). А вот пункт 1 неожиданно решается почти бесплатно —
**без отдельного DDNS для домашней сети**, в отличие от того DuckDNS, что
заводили для облачного сервера: агент и так каждый раз подключается к relay
по WebSocket, а значит relay и так видит его публичный IP на каждом успешном
логине. Просто раньше эта информация никуда не сохранялась. Теперь:

- Relay при каждой успешной аутентификации АГЕНТА (не телефона) запоминает
  IP, с которого тот пришёл, в базе (`devices.last_public_ip`).
- Телефон, когда жмёт 🔌, спрашивает у relay: "какой последний известный
  публичный IP у этого ПК?" — через новый маленький REST-эндпоинт, тем же
  токеном телефона, которым он и так авторизуется (отдельного секрета заводить
  не пришлось — переиспользовал существующую проверку из `DeviceAuthService`).
- Дальше телефон шлёт magic packet ПРИЦЕЛЬНО на этот адрес (а не
  широковещательно) — и заодно, для подстраховки, всё равно шлёт и
  локальный широковещательный пакет тоже. Так кнопка работает в обоих
  случаях без необходимости выбирать "локально" или "через интернет" вручную.

**Честное ограничение** (стоит явно проговорить на защите): IP свежий ровно
настолько, насколько давно ПК последний раз был на связи. Если ПК выключен
надолго и провайдер за это время выдал роутеру новый IP — пакет уйдёт не
туда, и это фундаментальное ограничение Wake-on-LAN вообще (разбудить
выключенную машину, спросив её саму, где она — невозможно). У провайдера со
статическим IP (или у бизнес-тарифа) эта проблема не встаёт вообще.

Протокол (`shared`/websocket-сообщения) трогать не пришлось — это обычный
REST-эндпоинт, как уже существующие `/api/pairing-codes` и
`/api/agent/phone-pairing-code`, а не что-то, что течёт через `JarvisMessage`.
`ddl-auto: update` в `application.yml` уже стоит — новая колонка в БД появится
сама при следующем запуске relay, миграцию писать не нужно.

## Промпт 1 — relay запоминает публичный IP агента

Файл: `jarvis-relay/src/main/java/com/jarvis/relay/persistence/Device.java`.

Было (конец файла):

```java
    public Instant getPairedAt() {
        return pairedAt;
    }
}
```

Стало:

```java
    public Instant getPairedAt() {
        return pairedAt;
    }

    public String getLastPublicIp() {
        return lastPublicIp;
    }

    /**
     * Обновляется при каждой успешной аутентификации АГЕНТА (см.
     * DeviceAuthService.authenticate) — нужно для Wake-on-LAN через интернет:
     * пока ПК выключен, спросить его текущий адрес не у кого, поэтому
     * запоминаем последний, который видели, пока он ещё был на связи.
     */
    public void updateLastPublicIp(String ip) {
        this.lastPublicIp = ip;
    }
}
```

И добавь само поле рядом с остальными `@Column`:

Было:

```java
    @Column(name = "paired_at", nullable = false, updatable = false)
    private Instant pairedAt;

    protected Device() {
```

Стало:

```java
    @Column(name = "paired_at", nullable = false, updatable = false)
    private Instant pairedAt;

    /** Публичный IP, с которого АГЕНТ последний раз успешно логинился. Null,
     *  пока ни разу не подключался (или это запись телефона, а не ПК). */
    @Column(name = "last_public_ip")
    private String lastPublicIp;

    protected Device() {
```

Файл: `jarvis-relay/src/main/java/com/jarvis/relay/pairing/DeviceAuthService.java`.

Было (сигнатура метода):

```java
    @Transactional
    public Result authenticate(String deviceId, String presentedToken, DeviceRole role) {
```

Стало:

```java
    @Transactional
    public Result authenticate(String deviceId, String presentedToken, DeviceRole role, String remoteIp) {
```

Было (путь 2, существующее устройство):

```java
        // Путь 2: устройство из таблицы devices.
        Optional<Device> existing = devices.findById(deviceId);
        if (existing.isPresent()) {
            Device device = existing.get();
            if (device.getRole() != role) {
                return Result.fail("device registered with a different role");
            }
            if (!passwordEncoder.matches(presentedToken, device.getTokenHash())) {
                return Result.fail("bad token");
            }
            return Result.success(null);
        }
```

Стало:

```java
        // Путь 2: устройство из таблицы devices.
        Optional<Device> existing = devices.findById(deviceId);
        if (existing.isPresent()) {
            Device device = existing.get();
            if (device.getRole() != role) {
                return Result.fail("device registered with a different role");
            }
            if (!passwordEncoder.matches(presentedToken, device.getTokenHash())) {
                return Result.fail("bad token");
            }
            // Публичный IP нужен только у АГЕНТА (Wake-on-LAN через интернет) —
            // у телефона "публичный IP" бессмысленное понятие, не пишем его туда.
            if (role == DeviceRole.AGENT && remoteIp != null) {
                device.updateLastPublicIp(remoteIp);
                devices.save(device);
            }
            return Result.success(null);
        }
```

Было (свежий паринг, конец метода):

```java
        String newToken = generateToken();
        String label = pairingCode.get().getLabel();
        Device device = new Device(deviceId, label != null ? label : deviceId, role,
                passwordEncoder.encode(newToken), Instant.now());
        devices.save(device);
        return Result.paired(newToken);
```

Стало:

```java
        String newToken = generateToken();
        String label = pairingCode.get().getLabel();
        Device device = new Device(deviceId, label != null ? label : deviceId, role,
                passwordEncoder.encode(newToken), Instant.now());
        if (role == DeviceRole.AGENT) {
            device.updateLastPublicIp(remoteIp);
        }
        devices.save(device);
        return Result.paired(newToken);
```

Файл: `jarvis-relay/src/main/java/com/jarvis/relay/ws/JarvisWebSocketHandler.java`.

Было:

```java
import java.util.UUID;
```

Стало:

```java
import java.net.InetSocketAddress;
import java.util.UUID;
```

Было:

```java
        DeviceAuthService.Result result = deviceAuthService.authenticate(
                auth.deviceId(), auth.token(), auth.role());
```

Стало:

```java
        DeviceAuthService.Result result = deviceAuthService.authenticate(
                auth.deviceId(), auth.token(), auth.role(), resolveRemoteIp(session));
```

И добавь новый приватный метод рядом с `reqId()`:

Было:

```java
    private static String reqId() {
        return UUID.randomUUID().toString();
    }
```

Стало:

```java
    private static String reqId() {
        return UUID.randomUUID().toString();
    }

    /**
     * IP клиента. За обратным прокси (см. docs/DEPLOY_PUBLIC_RELAY_PROMPTS.md —
     * Caddy) реальный TCP-адрес сокета — это адрес самого Caddy, а не агента;
     * Caddy по умолчанию прокидывает исходный адрес в заголовке X-Forwarded-For
     * ещё на этапе HTTP-хендшейка (до апгрейда в WebSocket), поэтому смотрим
     * туда в первую очередь и только потом — на голый адрес сокета (для
     * локальной разработки без Caddy, где заголовка не будет).
     */
    private static String resolveRemoteIp(WebSocketSession session) {
        String forwarded = session.getHandshakeHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = session.getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : null;
    }
```

## Промпт 2 — эндпоинт, которым телефон спрашивает адрес

Новый файл
`jarvis-relay/src/main/java/com/jarvis/relay/pairing/WakeInfoController.java`:

```java
package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.Device;
import com.jarvis.relay.persistence.DeviceRepository;
import com.jarvis.relay.protocol.DeviceRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Самообслуживание для телефона: узнать последний известный публичный IP ПК,
 * чтобы разбудить его через интернет (в локальной сети телефон и так умеет
 * сам — WakeOnLan.kt, широковещательно). MAC-адрес телефон уже знает из QR
 * при паринге (PairedDevice.mac) — тут нужен только адрес.
 *
 * Авторизация — теми же данными, которыми телефон и так представляется
 * релею при обычном Auth (deviceId = id ПК, token = токен привязки этого
 * телефона к нему): переиспользуем DeviceAuthService.authenticate с ролью
 * PHONE, отдельного секрета заводить не пришлось.
 */
@RestController
@RequestMapping("/api/devices")
public class WakeInfoController {

    private final DeviceAuthService deviceAuthService;
    private final DeviceRepository devices;

    public WakeInfoController(DeviceAuthService deviceAuthService, DeviceRepository devices) {
        this.deviceAuthService = deviceAuthService;
        this.devices = devices;
    }

    public record WakeAddressResponse(String lastPublicIp) {}

    @GetMapping("/{agentId}/wake-address")
    public ResponseEntity<?> wakeAddress(@PathVariable String agentId,
                                          @RequestHeader("X-Device-Id") String deviceId,
                                          @RequestHeader("X-Device-Token") String token) {
        if (!agentId.equals(deviceId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "deviceId mismatch"));
        }
        DeviceAuthService.Result auth = deviceAuthService.authenticate(deviceId, token, DeviceRole.PHONE, null);
        if (!auth.ok()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad token"));
        }
        Optional<Device> agent = devices.findById(agentId);
        if (agent.isEmpty() || agent.get().getLastPublicIp() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "адрес ещё неизвестен — ПК ни разу не подключался после этого шага"));
        }
        return ResponseEntity.ok(new WakeAddressResponse(agent.get().getLastPublicIp()));
    }
}
```

## Промпт 3 — телефон шлёт пакет и в LAN, и через интернет

Файл: `androidApp/src/main/kotlin/com/jarvis/app/WakeOnLan.kt`. Выносим сборку
пакета в общий приватный метод и добавляем прицельную (unicast) отправку.

Было:

```kotlin
object WakeOnLan {

    /**
     * Собрать и отправить magic packet на [mac] (формат "AA:BB:CC:DD:EE:FF").
     * Вызывать на Dispatchers.IO — это сетевая операция.
     */
    fun sendMagicPacket(mac: String) {
        val macBytes = parseMac(mac)

        // Magic packet: 6 байт 0xFF, затем MAC-адрес повторён 16 раз подряд.
        val packetBytes = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
        for (i in 0 until 16) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + i * 6, 6)
        }

        val address = InetAddress.getByName("255.255.255.255")
        val packet = DatagramPacket(packetBytes, packetBytes.size, address, 9)
        DatagramSocket().apply { broadcast = true }.use { it.send(packet) }
    }

    /** "AA:BB:CC:DD:EE:FF" -> 6 байт. Разделитель — обычно ":", но принимаем и "-". */
    private fun parseMac(mac: String): ByteArray {
        val hex = mac.replace(":", "").replace("-", "")
        require(hex.length == 12) { "неверный MAC-адрес: $mac" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
```

Стало:

```kotlin
object WakeOnLan {

    /**
     * Отправить magic packet широковещательно в локальную сеть — работает,
     * только если телефон сейчас в той же Wi-Fi, что и ПК.
     * Вызывать на Dispatchers.IO — это сетевая операция.
     */
    fun sendMagicPacket(mac: String) {
        val packetBytes = buildPacket(mac)
        val address = InetAddress.getByName("255.255.255.255")
        val packet = DatagramPacket(packetBytes, packetBytes.size, address, 9)
        DatagramSocket().apply { broadcast = true }.use { it.send(packet) }
    }

    /**
     * Тот же magic packet, но ПРИЦЕЛЬНО на конкретный [host] (публичный IP
     * домашнего роутера ПК, см. RelayPairing.fetchWakeAddress) — так можно
     * разбудить ПК из любой сети, не только из домашней. Требует, чтобы на
     * роутере был настроен проброс порта [port] на локальный IP ПК (см.
     * инструкцию в STEP_WOL_OVER_WAN_PROMPTS.md).
     */
    fun sendMagicPacketTo(mac: String, host: String, port: Int = 9) {
        val packetBytes = buildPacket(mac)
        val address = InetAddress.getByName(host)
        val packet = DatagramPacket(packetBytes, packetBytes.size, address, port)
        DatagramSocket().use { it.send(packet) }
    }

    /** Magic packet: 6 байт 0xFF, затем MAC-адрес повторён 16 раз подряд. */
    private fun buildPacket(mac: String): ByteArray {
        val macBytes = parseMac(mac)
        val packetBytes = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
        for (i in 0 until 16) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + i * 6, 6)
        }
        return packetBytes
    }

    /** "AA:BB:CC:DD:EE:FF" -> 6 байт. Разделитель — обычно ":", но принимаем и "-". */
    private fun parseMac(mac: String): ByteArray {
        val hex = mac.replace(":", "").replace("-", "")
        require(hex.length == 12) { "неверный MAC-адрес: $mac" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
```

Файл: `androidApp/src/main/kotlin/com/jarvis/app/RelayPairing.kt`. Добавляем
запрос адреса — обычный HTTP GET, не через WebSocket (это не команда и не
Auth, отдельный REST-эндпоинт с Промпта 2).

Было (импорты):

```kotlin
import android.util.Log
import com.jarvis.protocol.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.coroutines.resume
```

Стало:

```kotlin
import android.util.Log
import com.jarvis.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume
```

Было (сразу после `checkStatus`, перед `oneShot`):

```java
    /**
     * Открыть сокет, отправить первое сообщение через [onOpen] и вернуть
```

(это начало комментария метода `oneShot` — используем его как якорь)

Стало (новый метод и `@Serializable`-класс вставлены прямо перед `oneShot`):

```kotlin
    /**
     * Последний известный публичный IP ПК (для Wake-on-LAN через интернет) —
     * относительно свежий ТОЛЬКО настолько, насколько давно ПК был на связи в
     * последний раз (см. WakeInfoController на релее). null — либо сеть
     * недоступна, либо ПК ещё ни разу не подключался после этого шага.
     */
    suspend fun fetchWakeAddress(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(httpBase(device.relayUrl) + "/api/devices/${device.id}/wake-address")
                .header("X-Device-Id", device.id)
                .header("X-Device-Token", device.token)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JarvisJson.decodeFromString<WakeAddressResponse>(body).lastPublicIp
            }
        }.getOrNull()
    }

    @Serializable
    private data class WakeAddressResponse(val lastPublicIp: String?)

    /** ws://host:port/ws -> http://host:port — тот же приём, что httpBase в PairPhoneDialog на агенте. */
    private fun httpBase(relayUrl: String): String {
        val uri = URI.create(relayUrl)
        val scheme = if (uri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://${uri.host}$port"
    }

    /**
     * Открыть сокет, отправить первое сообщение через [onOpen] и вернуть
```

## Промпт 4 — кнопка "разбудить" пробует оба пути

Файл: `androidApp/src/main/kotlin/com/jarvis/app/DevicesScreen.kt`.

Было:

```kotlin
                        onWake = {
                            ui.device.mac?.let { mac ->
                                scope.launch(Dispatchers.IO) { WakeOnLan.sendMagicPacket(mac) }
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Отправлено. Если телефон в той же Wi-Fi, а ПК подключён кабелем — проснётся за ~20 секунд"
                                    )
                                }
                            }
                        },
```

Стало:

```kotlin
                        onWake = {
                            ui.device.mac?.let { mac ->
                                scope.launch(Dispatchers.IO) {
                                    // Локальная сеть — сработает мгновенно, если телефон и ПК
                                    // в одном Wi-Fi. Не мешает попытке через интернет ниже —
                                    // пробуем оба пути сразу, вместо того чтобы заставлять
                                    // человека выбирать вручную "локально" или "через интернет".
                                    runCatching { WakeOnLan.sendMagicPacket(mac) }
                                    // Через интернет — на последний известный публичный IP
                                    // роутера ПК; сработает, только если на роутере настроен
                                    // проброс порта (см. STEP_WOL_OVER_WAN_PROMPTS.md).
                                    RelayPairing.fetchWakeAddress(ui.device)?.let { ip ->
                                        runCatching { WakeOnLan.sendMagicPacketTo(mac, ip) }
                                    }
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Отправлено — и в локальную сеть, и через интернет (если на роутере настроен проброс порта)"
                                    )
                                }
                            }
                        },
```

## Часть Б — ручная настройка домашнего роутера (не код)

Один раз, на роутере, за которым стоит ПК:

1. **Статическая привязка IP по MAC** (DHCP reservation / "статическая аренда"
   — называется по-разному у разных производителей, обычно в разделе DHCP
   настроек роутера): привязать LAN IP ПК к его MAC-адресу навсегда. Без
   этого IP ПК в локальной сети может со временем смениться, и правило
   проброса порта из следующего шага начнёт указывать в пустоту.
2. **Проброс порта** (Port Forwarding / Virtual Server): внешний UDP-порт
   **9** → внутренний IP ПК (тот, что закрепили в шаге 1) : порт **9**.
   Дальше в коде используется именно порт 9 по умолчанию — если хочешь другой
   внешний порт, поменяй третий аргумент в вызове `sendMagicPacketTo` на
   стороне приложения на тот, что выбрал на роутере.
3. **Дальше ничего заводить не нужно** — ни отдельный DDNS для дома, ни
   ручное обновление IP: relay сам увидит новый IP роутера при следующем
   подключении агента (после смены IP просто открой Jarvis Agent на ПК один
   раз, чтобы он переподключился и relay обновил `last_public_ip`).
4. Убедись, что в свойствах сетевой карты ПК включено "Wake on Magic Packet"
   (то же самое условие, что и для локального Wake-on-LAN — это ты уже
   настраивал на шаге, когда заводили саму кнопку 🔌).

## Проверка

- [ ] Перезапускаешь relay (`docker compose up -d --build` или просто рестарт
      контейнера) — в логах Hibernate не должно быть ошибок; в базе у таблицы
      `devices` появилась колонка `last_public_ip` (ddl-auto: update сам
      добавит).
- [ ] Агент подключается к relay как обычно — никаких видимых изменений в его
      поведении.
- [ ] `SELECT id, last_public_ip FROM devices WHERE role = 'AGENT';` в
      Postgres (`docker exec -it jarvis-relay-db psql -U jarvis -d jarvis`)
      после того, как агент хоть раз успешно авторизовался, показывает
      непустой IP.
- [ ] С телефона, который В ДОМАШНЕЙ Wi-Fi, жмёшь 🔌 — как и раньше, ПК
      просыпается (это путь LAN, ничего не сломали).
- [ ] Настроил проброс порта на роутере (Часть Б) → выключил ПК → вышел из
      дома, телефон на мобильном интернете → жмёшь 🔌 — ПК просыпается через
      интернет. Если нет: проверь вручную, что `GET
      https://<твой-домен>/api/devices/<id-пк>/wake-address` с заголовками
      `X-Device-Id`/`X-Device-Token` от телефона возвращает реальный IP (можно
      curl'ом с любой машины, подставив те же значения) — если IP пустой или
      404, значит агент ещё ни разу не подключался ПОСЛЕ применения этого шага,
      подключи и попробуй заново.
- [ ] Если на этапе применения деплой-гайда (docs\DEPLOY_PUBLIC_RELAY_PROMPTS.md)
      relay уже стоит за Caddy — проверь, что `X-Forwarded-For` реально
      доходит: временно залогируй `remoteIp` в `resolveRemoteIp` и посмотри
      `docker compose logs relay`, там должен быть настоящий IP агента, а не
      внутренний адрес контейнера Caddy (`172.x.x.x`). Caddy пробрасывает этот
      заголовок сам по умолчанию, но если вдруг увидишь именно IP контейнера
      — значит конфигурация Caddyfile нестандартная, дай знать, разберёмся отдельно.
