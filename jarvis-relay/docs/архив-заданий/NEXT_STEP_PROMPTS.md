# Jarvis — следующий шаг: «включил и работает» + привязка по QR

Как пользоваться: каждому ИИ-агенту давай **блок «Общий контекст»** + **промпт своего подпроекта**.
Порядок строго такой: **Релей → Агент → Приложение**. Каждый этап проверяется отдельно, прежде чем идти дальше.

---

## Общий контекст (вставлять в начало КАЖДОГО промпта)

```
Проект Jarvis — управление ПК с телефона естественным языком (дипломная, тема
«AI-агент для автоматизации многоэтапных пользовательских задач»).
Автор учится: объясняй код по ходу, общайся по-русски, двигайся маленькими
проверяемыми шагами, после каждого шага — как проверить.

Архитектура (Windows 11):
  Телефон (KMP/Compose) <--WS--> Релей (Spring Boot, Java 21) <--WS--> Агент (Java 21, java.awt.Robot)
  C:\diplom\jarvis          — KMP: :shared (протокол, Kotlin) + :androidApp (Compose, OkHttp WebSocket)
  C:\diplom\jarvis-relay    — релей + PostgreSQL в Docker (db/user/pass = jarvis)
  C:\diplom\jarvis-agent    — агент на ПК

ИНВАРИАНТЫ (не ломать):
 1. Протокол (JarvisMessage/Command) меняется синхронно в 3 местах: Kotlin :shared,
    Java релей, Java агент. JSON 1:1, дискриминатор "type". В ЭТОМ шаге протокол НЕ меняем.
 2. CommandWhitelist — граница безопасности; shutdown/reboot вне списка.
 3. Ключ LLM только на бэке в env, не в APK/агенте, не логировать. Токены тоже не логировать.
 4. Агент сам звонит на релей (не наоборот).
 5. Постоянные токены и deviceId переживают перезапуск.

ТЕКУЩЕЕ СОСТОЯНИЕ (важно — это уже сделано):
- Привязка устройств по одноразовому коду: POST /api/pairing-codes (заголовок
  X-Admin-Token, тело {"label":..., "agentId":...}). Код 6 цифр, 10 минут, один раз.
  Код БЕЗ agentId — для агента (гасится в Auth). Код С agentId — для телефона (гасится в PairRequest).
- Таблицы: devices (id, name, role, token_hash BCrypt, paired_at) — агенты (+ старые телефоны);
  phone_bindings (id, agent_id, name, token_hash, paired_at) — связь «телефон -> ПК»;
  pairing_codes (+ agent_id); audit_log (FK на devices).
- Протокол: PairRequest{code} -> PairResult{ok, deviceId(=id агента), deviceToken, deviceName, reason};
  AuthResult{ok, reason, agentOnline}. Телефон входит Auth(deviceId = id агента, token = свой токен, role=PHONE).
- Релей: DeviceAuthService (3 пути: phone_bindings / devices / pairPhone), SessionRegistry
  (агенты по id, телефоны с targetAgentId), JarvisWebSocketHandler.
- Агент: TokenStore -> $HOME\.jarvis-agent\token.properties (token, deviceId). Запуск:
  gradlew run --args="ws://localhost:8080/ws [код]".
- Приложение: DeviceRepository (список PairedDevice, активный), DevicesScreen (привязка по коду
  и QR через zxing ScanContract), RelayPairing (PairRequest), PairingQr.parse
  (формат jarvis-pair://pair?code=..&relay=..&name=..). Но есть СТАРЫЙ путь: PairingScreen/
  PairingViewModel (Auth с кодом, deviceId "phone-android"), SettingsRepository.token,
  поле «токен» в SettingsDialog — источник постоянной путаницы с токенами.
- Скрипты: C:\diplom\jarvis-relay\scripts\start-jarvis.ps1/.bat (Docker, Postgres, релей,
  агент, печатает адрес), pair-phone.ps1/.bat. .ps1 сохранять в UTF-8 С BOM (иначе
  Windows PowerShell 5.1 ломает кириллицу).
- НЕ ПРОВЕРЕНО до конца: привязка телефона по новой схеме end-to-end.

Сеть: адрес для телефона — IP физического Wi-Fi/Ethernet адаптера (НЕ Radmin 26.x, НЕ vEthernet/
WSL/Hyper-V 172.x). Дома был 192.168.1.171, в университете 10.82.206.76 (там возможна изоляция
клиентов). Рекомендуется Tailscale (постоянный адрес 100.x). Порт 8080 может требовать правило
брандмауэра.
```

---

## Цель шага (что должен получить пользователь)

1. **Включил ноутбук — всё поднялось само.** Docker Desktop стартует с Windows, Postgres и релей — контейнеры с автоперезапуском, агент — в автозапуске, без окон консоли, со значком в трее.
2. **Привязка телефона без токенов и кодов вручную.** Трей агента → «Привязать телефон» → окно с QR. В приложении «Сканировать QR» → готово. Адрес релея приезжает внутри QR.
3. **Если что-то отвязалось — понятное сообщение и одна кнопка «Привязать заново»**, без SQL и PowerShell.
4. Вне рамок: пробуждение ПК, ESP32, AI-ядро (это следующий шаг).

---

## Промпт 1 — РЕЛЕЙ (`C:\diplom\jarvis-relay`)

```
[Общий контекст]

Задача: сделать релей «всегда включённым» и дать агенту самому выписывать коды для телефона.
Протокол WebSocket НЕ менять. Делай по одному пункту, после каждого — как проверить.

R1. Релей в Docker.
  - Dockerfile (multi-stage): стадия сборки gradle + JDK 21 -> bootJar; стадия запуска
    eclipse-temurin:21-jre, запуск java -jar. Объясни каждую строку.
  - docker-compose.yml: добавить сервис relay (build: .), depends_on postgres с
    condition: service_healthy; env JARVIS_DB_URL=jdbc:postgresql://postgres:5432/jarvis,
    JARVIS_DB_USER/PASSWORD, JARVIS_ADMIN_TOKEN и JARVIS_LLM_API_KEY из файла .env
    (.env в .gitignore, рядом .env.example без секретов); ports "8080:8080";
    restart: unless-stopped у ОБОИХ сервисов. Существующий volume jarvis-db-data сохранить
    (данные не терять).
  - Обновить scripts\start-jarvis.ps1: шаг «релей» = docker compose up -d --wait --build
    вместо окна gradlew bootRun (bootRun оставить в README для разработки).
  Проверка: docker compose up -d --build; docker compose logs -f relay; перезапуск Docker
  Desktop -> релей поднимается сам.

R2. Агент сам выписывает код для телефона (без админ-токена).
  - POST /api/agent/phone-pairing-code, аутентификация заголовками X-Device-Id и
    X-Device-Token (постоянные данные агента; проверка BCrypt по devices, role=AGENT).
  - Ответ: {code, expiresAt, agentId, agentName}. Внутри — PairingService.generateCode(label, agentId).
  - Защита от спама: не больше 3 активных (неиспользованных и неистёкших) кодов на агента.
  Проверка: Invoke-RestMethod с заголовками из token.properties -> код; этот код проходит PairRequest.

R3. Управление устройствами без SQL (X-Admin-Token):
  - GET /api/devices — агенты: id, name, pairedAt, online (из SessionRegistry),
    список phone_bindings (id, name, pairedAt).
  - DELETE /api/devices/{id} — удалить агента: его phone_bindings, audit_log, саму строку;
    закрыть его открытую WS-сессию.
  - DELETE /api/phone-bindings/{id} — отвязать один телефон; закрыть сессии этого телефона.
  Проверка: удалить привязку -> приложение получает AuthResult ok=false.

R4. Восстановление токена агента: если deviceId известен (devices), токен не подошёл, но
  прислан валидный неиспользованный код БЕЗ agentId — сменить token_hash, вернуть новый
  токен (как при первом паринге). Роль должна совпадать. Залогировать факт (без токена).

R5. Тесты (JUnit, без реальной БД — Mockito или @DataJpaTest с H2, выбери и объясни):
  DeviceAuthService — вход телефона по phone_bindings; pairPhone; коды двух типов не
  взаимозаменяемы; R4 ротация. SessionRegistry — команда уходит нужному агенту, результат
  только телефонам этого агента.

Не делать: изменения JarvisMessage/Command, whitelist, логирование токенов.
```

---

## Промпт 2 — АГЕНТ (`C:\diplom\jarvis-agent`)

```
[Общий контекст]

Задача: агент запускается сам при входе в Windows, живёт в трее и показывает QR для
привязки телефона. Протокол НЕ менять. Релей уже умеет
POST /api/agent/phone-pairing-code (заголовки X-Device-Id, X-Device-Token).

A1. Конфиг вместо аргументов: $HOME\.jarvis-agent\agent.properties
    (relayUrl, по умолчанию ws://localhost:8080/ws; phoneRelayUrl — необязательно).
    Аргументы командной строки, если есть, важнее файла (первый запуск с кодом продолжает работать).

A2. Логи в файл $HOME\.jarvis-agent\agent.log (UTF-8) + консоль. Консоль в UTF-8
    (applicationDefaultJvmArgs -Dstdout.encoding=UTF-8) — убрать кракозябры.

A3. Значок в трее (java.awt.SystemTray): цвет/подсказка = статус (подключено / нет связи /
    не привязан). Меню: «Привязать телефон», «Открыть лог», «Выход».

A4. Окно «Привязать телефон» (Swing):
  - запрос кода у релея (http-адрес выводится из relayUrl: ws->http, путь /api/agent/phone-pairing-code);
  - адрес релея ДЛЯ ТЕЛЕФОНА (localhost не годится): phoneRelayUrl из конфига, иначе
    автоопределение через java.net.NetworkInterface — сначала Tailscale (100.64.0.0/10),
    потом поднятые физические адаптеры; исключить виртуальные (isVirtual, имена/описания с
    Radmin, Hyper-V, vEthernet, WSL, VPN, TAP, Bluetooth, Docker) и 169.254.x;
  - QR (библиотека com.google.zxing:core) со строкой СТРОГО в формате, который уже
    понимает приложение (см. PairingQr.kt и KDoc PairRequest в :shared):
      jarvis-pair://pair?code=<код>&relay=<url-encoded ws://IP:8080/ws>&name=<url-encoded имя ПК>
  - под QR: код цифрами, адрес, обратный отсчёт 10 минут, кнопка «Новый код»;
    если адресов несколько — выпадающий список, QR перестраивается.
  Флаг запуска --pair-phone открывает это окно сразу.

A5. Если сохранённый токен отвергнут (AuthResult ok=false): уведомление в трее с понятным
    текстом («ПК отвязан на сервере — запусти start-jarvis.bat для перепривязки»),
    token.properties НЕ удалять автоматически, не долбить релей реконнектами.

A6. Автозапуск: gradlew installDist; скрипт scripts\install-autostart.ps1 (UTF-8 с BOM):
    копирует сборку в %LOCALAPPDATA%\Jarvis\agent и создаёт ярлык в shell:startup, который
    запускает агента БЕЗ окна консоли (javaw или скрытое окно). Скрипт uninstall-autostart.ps1.
    Первый запуск на чистом ПК (нет token.properties) делает start-jarvis.ps1 (он сам выписывает код).

Проверка: перезагрузка -> значок в трее «подключено»; меню «Привязать телефон» -> QR
открывается; содержимое QR проверить любым сканером.
```

---

## Промпт 3 — ПРИЛОЖЕНИЕ (`C:\diplom\jarvis\androidApp`)

```
[Общий контекст]

Задача: одна понятная привязка по QR, никаких токенов в UI. Модуль :shared (протокол) НЕ менять.
Автор новичок в Compose — объясняй composable, состояние, ViewModel, Flow по ходу.

P1. Экран первого запуска = новая привязка. Условие в MainActivity: показывать чат, если
    DeviceRepository.devices не пуст (а НЕ SettingsRepository.token).
    Экран: большая кнопка «Сканировать QR» (переиспользовать zxing ScanContract и запрос
    камеры из DevicesScreen — вынести в общий composable, не копировать) и ссылка
    «Ввести код вручную» (адрес релея + код). Логика — через RelayPairing.pair / PairRequest
    (как DevicesViewModel.pairByQr/pairByCode — вынести общую часть, не дублировать).

P2. Удалить старый путь: PairingViewModel с Auth-кодом, DEV_FALLBACK_DEVICE_ID,
    SettingsRepository.token, fallback-подключение в ChatViewModel. ChatViewModel подключается
    только к активному PairedDevice.

P3. Диалог ⚙: убрать поле «токен». Показать имя активного ПК и его адрес релея (можно
    отредактировать — сохраняется в PairedDevice.relayUrl через DeviceRepository), кнопка
    «Отвязать этот ПК» (DeviceRepository.remove). Подсказка: «Сменилась сеть? Отсканируй QR заново».

P4. Отказ в авторизации: если AuthResult ok=false — JarvisClient прекращает реконнекты
    (сейчас после закрытия 1003 он переподключается бесконечно), в чате статус
    «Этот ПК отвязан» и кнопка «Привязать заново» (открывает сканер).

P5. Повторный скан QR того же ПК обновляет токен и адрес (addAndActivate уже заменяет по id —
    проверить и оставить).

Проверка: «Очистить данные» -> экран привязки -> скан QR из окна агента -> «подключено ✓»
-> «скриншот» работает -> закрыть/открыть приложение -> подключается само.
```

---

## Финальная проверка шага (end-to-end)

| # | Действие | Ожидание |
|---|---|---|
| 1 | Перезагрузить ноутбук, ничего не трогать 2–3 мин | `docker ps`: relay и db запущены; значок агента «подключено» |
| 2 | Телефон: очистить данные → открыть приложение | Экран «Сканировать QR» |
| 3 | Трей → «Привязать телефон» → скан | Чат, «подключено ✓», «скриншот» приходит |
| 4 | Закрыть и открыть приложение | Подключается само |
| 5 | `docker compose restart relay` | Агент и телефон переподключаются сами |
| 6 | `DELETE /api/phone-bindings/{id}` | В приложении «Этот ПК отвязан» + кнопка «Привязать заново» |
| 7 | Сменить сеть → «Привязать телефон» → скан | Работает с новым адресом |

Следующий шаг после этого: включить AI-ядро (ключ Gemini в `.env` релея как `JARVIS_LLM_API_KEY`), затем датасет и замеры для диплома.
