# Фауст — релей (Spring Boot / Java 21)

WebSocket-сервер, который стоит между телефоном и агентом на ПК:

```
Телефон (Kotlin) --WS--> [ РЕЛЕЙ ] <--WS-- Агент (Java, на ПК)
                              |
                     PostgreSQL · Ollama (своя модель) · Gemini
```

Релей делает пять вещей:
1. **Авторизует** устройство — по одноразовому коду привязки (первое подключение) либо по постоянному токену (все следующие). Первое сообщение сокета — всегда `Auth`.
2. **Маршрутизирует** сообщения телефон ⇄ агент.
3. **Разбирает фразу** («сделай потише») в конкретную команду. Цепочка `HybridIntentParser`: навык/скрипт по имени → память уроков → своя дообученная модель в Ollama (`faust-intent`) → Gemini, если своя модель не уверена (порог 0,9) → регулярки, если нет ни того, ни другого.
4. **Проверяет белый список** (`CommandWhitelist` + `ForbiddenGoals`) — запрещённое (выключение ПК, форматирование, переводы денег) до агента не доходит; каждая команда пишется в аудит-лог в PostgreSQL.
5. **Держит ключ ИИ у себя.** Агент ходит к модели только через `POST /api/agent/llm/generate` — в APK и в агенте ключа нет.

## Требования
- **Docker** — теперь и релей, и PostgreSQL поднимаются в контейнерах (см. `Dockerfile`, `docker-compose.yml`).
- **JDK 21** — нужен только для разработки (сборка вне Docker, `./gradlew bootRun`).

## Как запустить (обычный способ — всё в Docker)
```
copy .env.example .env      REM один раз; впиши свои секреты, если нужно
docker compose up -d --wait --build
```
Поднимутся оба сервиса: `postgres` (том `jarvis-db-data` сохраняет данные между перезапусками) и `relay` на `ws://localhost:8080/ws`. У обоих `restart: unless-stopped` — они сами поднимаются после перезапуска Docker Desktop/Windows, отдельно ничего запускать не надо.

Секреты (`JARVIS_ADMIN_TOKEN`, `JARVIS_LLM_API_KEY`) и данные БД (`JARVIS_DB_USER/PASSWORD`) читаются из `.env` рядом с `docker-compose.yml` (см. `.env.example`; `.env` — в `.gitignore`, в репозиторий не попадает). Без `.env` используются те же значения по умолчанию, что и раньше (`dev-admin-change-me`, `jarvis`/`jarvis`) — для localhost этого достаточно.

Логи: `docker compose logs -f relay`. Пересобрать после правки кода: `docker compose up -d --build`.

## Как запустить для разработки (без Docker для релея)
Так удобнее, если часто правишь код релея: не нужно пересобирать образ на каждое изменение.
```
docker compose up -d postgres --wait   REM только база, в Docker
./gradlew bootRun                      REM релей — обычным процессом на хосте
```
Переменные окружения (админ-токен, ключ LLM и т.д.) передаются так же, как и раньше:
```
JARVIS_ADMIN_TOKEN=... JARVIS_LLM_API_KEY=... ./gradlew bootRun
```

## Паринг нового устройства
1. Администратор генерирует одноразовый код (живёт 10 минут по умолчанию):
   ```
   curl -X POST http://localhost:8080/api/pairing-codes \
     -H "X-Admin-Token: dev-admin-change-me" \
     -H "Content-Type: application/json" \
     -d "{\"label\":\"мой ПК\"}"
   ```
   Ответ: `{"code":"482913","expiresAt":"..."}`.
2. Устройство подключается и в поле `token` первого `Auth`-сообщения присылает этот код вместо токена.
3. Релей находит код в таблице `pairing_codes`, гасит его (второй раз использовать нельзя), создаёт запись в `devices` и **один раз** возвращает постоянный токен устройства — в поле `reason` сообщения `AuthResult` (формат сообщения не меняется, туда просто кладётся значение).
4. Устройство сохраняет этот токен у себя и во всех следующих подключениях присылает уже его — релей сверяет его с BCrypt-хэшем в БД.

## Проверить без телефона и агента
Поставь `wscat` (`npm i -g wscat`).

Сначала получи код паринга через `curl` (см. выше), затем открой ДВА терминала.

Терминал 1 — «агент» (парится по коду и получает постоянный токен в `reason`):
```
wscat -c ws://localhost:8080/ws
> {"type":"auth","requestId":"1","deviceId":"pc","token":"482913","role":"agent","protocolVersion":1}
```

Терминал 2 — «телефон» (получи свой код паринга тем же способом):
```
wscat -c ws://localhost:8080/ws
> {"type":"auth","requestId":"1","deviceId":"phone","token":"<код_телефона>","role":"phone","protocolVersion":1}
> {"type":"command_request","requestId":"a1","command":{"type":"nl","text":"сделай потише"}}
```
В терминале «агента» прилетит:
```
{"type":"command_request","requestId":"a1","command":{"type":"set_volume","level":30}}
```
А если послать запрещённое (`shutdown`) — телефон получит `status:"denied"`, и это тоже попадёт строкой в `audit_log`.

## Структура
```
protocol/    — Java-зеркало Kotlin-протокола (records + Jackson), 1:1 по JSON.
               Меняется ВМЕСТЕ с jarvis/shared и jarvis-agent/protocol.
security/    — CommandWhitelist (ядро безопасности), ForbiddenGoals (опасные цели
               ИИ-задач), BCrypt-бин для хэширования токенов
llm/         — разбор фраз: HybridIntentParser (@Primary, цепочка выше),
               LocalIntentParser (Ollama), GeminiIntentParser, StubIntentParser
               (регулярки), QuickPhrases, IntentLessons (уроки из успешных команд);
               AgentLlmController — прокси к Gemini для агента;
               AdminLlmController / IntentEvalController — для замеров (X-Admin-Token)
persistence/ — JPA: Device, PhoneBinding, PairingCode, AuditLog, AgentLlmLog, IntentLesson
pairing/     — проверка Auth, выдача кодов привязки, регистрация агента
audit/       — AuditService: пишет каждую команду в audit_log
ws/          — WebSocketConfig, SessionRegistry, JarvisWebSocketHandler (вся маршрутизация)
eval/        — экзамены разбора фраз (А: intent_test.jsonl, Б: intent_test_b.jsonl)
               и замер intent_eval.py --engine stub|gemini|local|system
```

## HTTP-эндпоинты
| Путь | Кто зовёт | Зачем |
|---|---|---|
| `POST /api/pairing-codes` | админ (`X-Admin-Token`) | одноразовый код привязки |
| `POST /api/agent/phone-pairing-code` | агент | код для QR привязки телефона |
| `POST /api/agent/register` | новый агент | саморегистрация (по умолчанию выключена) |
| `POST /api/agent/llm/generate` | агент | запрос к Gemini через релей (ключ не покидает релей) |
| `POST /api/admin/intent/parse`, `GET /api/admin/intent/lessons` | замеры | разбор фразы без телефона, уроки |
| `POST /api/admin/llm/generate` | `jarvis-ml` | учитель-Gemini для обучения своей модели |

## Тесты
```
.\gradlew.bat test
```
37 тестов (на 22.10.2026): белый список, запрещённые цели, разбор фраз, уроки.

## Известные ограничения
- Схема БД ведётся `ddl-auto: update`, без миграций Flyway/Liquibase — для диплома
  достаточно, для продакшена стоило бы перейти на явные миграции.
- Соединение `ws://` без TLS: релей рассчитан на домашнюю сеть / Tailscale.
