# Jarvis — передача проекта в новый чат

Вставь это первым сообщением в новом чате. Стиль работы: объясняй код по ходу
(автор учится, особенно Compose), общайся по-русски, двигайся маленькими
проверяемыми шагами.

## Что за проект
Дипломная (бакалавриат). **Jarvis** — управление ПК с телефона по естественному
языку. Рамка темы (утверждённый список кафедры, Вариант A, п.8):
«AI-агент для автоматизации многоэтапных пользовательских задач». Центр — LLM +
computer-use агент (сам находит элементы на экране), а не «пульт». Нужна
измеримая часть (точность распознавания намерений, success rate агента).

## Архитектура и где лежит код (Windows 11)
```
Телефон (KMP/Compose) <--WS--> Релей (Spring Boot, Java) <--WS--> Агент (Java, java.awt.Robot)
```
- `C:\diplom\jarvis`        — KMP: модуль `:shared` (протокол) + `:androidApp` (Compose-клиент, WebSocket на OkHttp)
- `C:\diplom\jarvis-relay`  — сервер-посредник (Spring Boot, JDK 21, PostgreSQL в Docker)
- `C:\diplom\jarvis-agent`  — агент на ПК (Java 21, java.net.http.WebSocket, Robot)

Транспорт: агент сам звонит на релей (работает из-за NAT). Протокол — единый
sealed-набор сообщений: Kotlin в `:shared`, Java-зеркало (records+Jackson) в
релее и агенте; JSON 1:1, поле "type".

## Текущее состояние
Работает сквозной путь телефон→релей→агент. Реализованные команды агента:
open_url, screenshot, list_processes, kill_process, lock_screen, click,
type_text, scroll. Заглушки: set_volume, open_app. Голос (SpeechRecognizer +
TTS) в приложении есть. Whitelist блокирует shutdown/reboot.

Сделано на шаге "безопасность/паринг":
- Релей: PostgreSQL (Docker: db/user/pass = jarvis/jarvis/jarvis), паринг по
  одноразовому коду, аудит-лог. Таблица `devices` (id, name, paired_at, role,
  token_hash BCrypt).
- Паринг: админ генерит код `POST /api/pairing-codes` с заголовком
  `X-Admin-Token: dev-admin-change-me`; устройство шлёт код в поле `token`
  первого Auth; релей возвращает ПОСТОЯННЫЙ токен в поле `reason` ответа
  AuthResult; устройство сохраняет и дальше шлёт его. Код одноразовый, 10 минут,
  один код = одно устройство.
- Агент: TokenStore хранит token И deviceId в $HOME/.jarvis-agent/token.properties;
  RelayClient переиспользует их при реконнекте (только что починили баг: раньше
  deviceId был случайным при каждом запуске → после перезапуска "unknown device").

Vision-агент НАПИСАН, но НЕ протестирован: в jarvis-agent есть GeminiVisionAgent,
BrowserAgent (scope=BROWSER, через Selenium/DOM), ScreenAgent (scope=SCREEN, по
пикселям). Команда agent_task их вызывает. В релее есть GeminiIntentParser.
Оба требуют переменную окружения GEMINI_API_KEY — ключа пока нет.

## Инварианты (НЕ ЛОМАТЬ)
1. Протокол меняется синхронно в 3 местах (Kotlin :shared + Java релей + Java агент).
2. Whitelist — граница безопасности; shutdown/reboot вне списка.
3. Ключ LLM только на бэке/в env, не в APK/агенте, не логировать.
4. Агент звонит на релей (не наоборот).
5. Постоянный токен и deviceId устройства должны переживать перезапуск.

## Грабли Windows/сеть (уже прошли)
- Нужен JDK 21 (Temurin). Docker Desktop для Postgres.
- В приложении адрес релея — Wi-Fi IP ПК `ws://192.168.1.171:8080/ws`. НЕ виртуальные
  адаптеры (172.x/10.x — WSL/Hyper-V/Docker), НЕ Radmin VPN (26.x). Телефон и ПК в одной Wi-Fi.
- Приложение на OkHttp (Ktor давал ошибку рукопожатия); ws://→http:// внутри; в манифесте usesCleartextTraffic=true.
- Лимит WebSocket-сообщения на релее поднят до 8 МБ (скриншоты); скриншот в агенте ужимается до 1280px/JPEG50.
- PowerShell ломает кавычки в curl JSON — использовать Invoke-RestMethod:
  `Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/pairing-codes -Headers @{ "X-Admin-Token"="dev-admin-change-me" } -ContentType "application/json" -Body '{"label":"pc"}'`

## Порядок запуска
1. `cd C:\diplom\jarvis-relay; docker compose up -d`  (Postgres)
2. `cd C:\diplom\jarvis-relay; .\gradlew bootRun`      (релей, ws://localhost:8080/ws)
3. Сгенерить код (Invoke-RestMethod выше), запустить агент:
   `cd C:\diplom\jarvis-agent; .\gradlew run --args="ws://localhost:8080/ws <код>"`
   (после первой привязки — без кода: он берёт сохранённый токен)
4. Приложение из Android Studio, адрес ws://192.168.1.171:8080/ws, привязка своим кодом.

## Ближайшие задачи (в порядке приоритета)
1. ДОВЕСТИ ПАРИНГ ДО КОНЦА:
   - Агент: удалить старый token.properties (`Remove-Item $env:USERPROFILE\.jarvis-agent\token.properties`),
     привязать заново свежим кодом, проверить, что ПЕРЕЗАПУСК без кода подключается
     (это тест только что внесённого фикса deviceId). Опционально почистить orphan-строку в devices.
   - Приложение: убедиться, что экран привязки сохраняет постоянный токен (DataStore) и
     после перезапуска подключается без кода; адрес по умолчанию 192.168.1.171.
2. ВКЛЮЧИТЬ AI-ЯДРО: получить бесплатный GEMINI_API_KEY (Google AI Studio),
   задать переменной окружения релею и агенту, проверить:
   - умный разбор фраз (GeminiIntentParser) — любые формулировки, не только regex;
   - vision-агент: agent_task "найди на youtube видео про <X> и открой" (scope=BROWSER).
3. НАУЧНАЯ ЧАСТЬ: датасет 100+ фраз RU + замер accuracy распознавания намерений;
   success rate агента (доля выполненных задач, число шагов, задержка) → таблицы для диплома.
4. Введение диплома под тему A8 (актуальность, цель, задачи, научная новизна).

## Как редактировать код
Пользователь работает через ИИ-агентов в своих проектах (даёт им этот контекст +
конкретную задачу). Claude может и напрямую читать/править файлы на ПК, если
подключена папка (были ограничения по глубине вложенности — коннектить конкретный
подпроект, напр. C:\diplom\jarvis-agent).
