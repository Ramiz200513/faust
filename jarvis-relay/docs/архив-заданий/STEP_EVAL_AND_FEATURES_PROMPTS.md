# Jarvis — следующий шаг: научная часть (замеры для диплома) + открытие файлов + история в приложении

Как пользоваться: каждому ИИ-агенту — «Общий контекст» + промпт своего подпроекта.
Порядок: **Релей → Агент → Приложение**. После каждого пункта — как проверить.

Этот шаг закрывает то, что пропустил предыдущий (AI-ядро): тогда добавили умный разбор фраз
и vision через прокси, но **журнал для замера точности так и не появился** — сейчас разбор
только пишется в консольный лог (`log.info`), в базу не сохраняется, отчёта по точности нет.
Это и есть главная задача этого шага — без неё не из чего делать таблицы для диплома.

---

## Общий контекст (вставлять в начало КАЖДОГО промпта)

```
Проект Jarvis — управление ПК с телефона естественным языком (дипломная, тема
«AI-агент для автоматизации многоэтапных пользовательских задач»).
Автор учится: объясняй код по ходу, по-русски, маленькими проверяемыми шагами,
после каждого шага — как проверить.

Архитектура (Windows 11):
  Телефон (KMP/Compose) <--WS--> Релей (Spring Boot, Java 21, Docker) <--WS--> Агент (Java 21, трей)
  C:\diplom\jarvis          — :shared (протокол Kotlin) + :androidApp (Compose)
  C:\diplom\jarvis-relay    — релей + PostgreSQL, docker compose (restart: unless-stopped), секреты в .env
  C:\diplom\jarvis-agent    — агент: AgentTray, PairPhoneDialog (QR), CommandExecutor, AppCatalog,
                              пакет vision/ (BrowserAgent/ScreenAgent/RunJournal/SensitiveInputGuard),
                              пакет llm/ (RelayLlmClient)

ИНВАРИАНТЫ (не ломать):
 1. Протокол WebSocket (JarvisMessage/Command, пакет protocol/) меняется синхронно в 3 местах:
    Kotlin :shared, Java релей, Java агент; JSON 1:1, дискриминатор "type". REST-эндпоинты
    релея (/api/...) — НЕ протокол, их можно менять свободно.
 2. CommandWhitelist (релей) — граница безопасности; каждая новая команда добавляется туда
    явно. shutdown/reboot вне списка. AppCatalog (агент) — та же идея для open_app: только то,
    что в словаре, произвольные пути с диска не запускать.
 3. Ключ LLM только в .env релея (JARVIS_LLM_API_KEY, JARVIS_LLM_MODEL). В агенте и APK его
    нет — vision-агент ходит на relay POST /api/agent/llm/generate через RelayLlmClient,
    аутентификация заголовками X-Device-Id/X-Device-Token (AgentAuthService на релее).
 4. Агент сам звонит на релей. Токены/deviceId переживают перезапуск, не логировать их.

ТЕКУЩЕЕ СОСТОЯНИЕ (важно — проверено чтением кода, не с чужих слов):
- Работает целиком: привязка по QR (агент в трее → «Привязать телефон»), релей и Postgres в
  Docker с автозапуском, разбор фраз через GeminiIntentParser (@Primary, модель из
  jarvis.llm.model / JARVIS_LLM_MODEL, ключ заголовком x-goog-api-key, НЕ в query!), при сбое —
  откат на StubIntentParser (regex). Пользователь остаётся на облачном Gemini (не локальной
  модели) — решение принято сознательно.
- Разбор фразы -> команда происходит в JarvisWebSocketHandler.handleCommand(): при
  Command.NaturalLanguage вызывается intentParser.parse(nl.text()), результат идёт через
  CommandWhitelist.isAllowed(), дальше агенту. Ошибка разбора/whitelist уже пишется в audit_log
  через AuditService — но это лог "кто/что/статус", НЕ материал для замера точности LLM
  (там нет ни исходной фразы+ожидаемого типа, ни того, какой парсер сработал: llm или stub).
- open_app работает по словарю AppCatalog (программы notepad/calc/explorer/mspaint/taskmgr,
  браузер по умолчанию, папки Downloads/Documents/Desktop/Pictures). open_file КОМАНДЫ НЕТ.
- set_volume реализован (не заглушка). agent_task ограничен (SensitiveInputGuard блокирует
  ввод в поля вида пароль/оплата), каждый запуск агента пишет JSONL-журнал через RunJournal в
  $HOME\.jarvis-agent\runs\<дата>-<id>.jsonl: goal, scope, maxSteps, completed, stepsTaken,
  durationMs, trace[] — это и есть сырьё для success rate, но общего отчёта по всем файлам нет.
- Приложение: единая привязка по QR (PairingScreen/PairingViewModel/QrScan.kt), у ChatViewModel
  уже есть таймер долгих задач. Экрана «История» нет — весь audit_log виден только через SQL.
```

---

## Промпт 1 — РЕЛЕЙ (`C:\diplom\jarvis-relay`)

```
[Общий контекст]

Задача: журнал разбора фраз для замера точности LLM (главное), плюс команда open_file
(часть протокола — меняем синхронно и здесь, и в KMP :shared, и в агенте её ИСПОЛНЯЕТ агент,
на релее только объявление + whitelist). По одному пункту, после каждого — проверка.

E1. Внутренний контракт IntentParser — не протокол (см. инварианты), можно менять свободно.
  Поменяй IntentParser.parse(String phrase) так, чтобы он возвращал не просто Command, а
  небольшую запись ParseOutcome(Command command, String parserUsed) — parserUsed = "llm" или
  "stub". Обнови StubIntentParser (всегда "stub") и GeminiIntentParser (когда падает на
  regex-fallback — тоже "stub", когда ответила модель — "llm"). Поправь единственное место
  вызова — JarvisWebSocketHandler.handleCommand.

E2. Таблица intent_log (сущность IntentLog + репозиторий):
  id, occurred_at, device_id, source ("live" | "eval"), phrase, parser ("llm"|"stub"),
  predicted_type (например "set_volume", или "unknown"/"error"), predicted_json (весь Command
  сериализованный в JSON, nullable при ошибке), expected_type (nullable — заполняется только
  для source=eval), correct (Boolean, nullable — вычисляется только для eval: predicted_type
  equals expected_type), latency_ms, error (nullable).
  В handleCommand замерь время вокруг intentParser.parse(...) и запиши строку с source="live",
  expected_type/correct = null. Не ломай существующий audit_log — это ДОПОЛНИТЕЛЬНАЯ таблица.

E3. POST /api/eval/intents (X-Admin-Token) — прогон датасета БЕЗ WebSocket, напрямую через
  тот же бин IntentParser (внедри его в контроллер).
  Тело: {"phrases":[{"phrase":"сделай потише","expectedType":"set_volume"}, ...]}.
  Для каждой строки: intentParser.parse(phrase) -> сохранить в intent_log с source="eval",
  expected_type и correct. Ответ: {"total":N,"correct":K,"accuracy":K/N,
  "byParser":{"llm":{...},"stub":{...}}, "rows":[{phrase, expectedType, predictedType,
  parser, correct, latencyMs}, ...]}. Таймаут на весь прогон не нужен — считай синхронно,
  для 100+ фраз это нормально (пара минут максимум).

E4. GET /api/intent-log?format=csv&source=live|eval (X-Admin-Token) — выгрузка таблицы в CSV
  (occurred_at, source, phrase, parser, predicted_type, expected_type, correct, latency_ms) —
  открывается в Excel для таблиц в дипломе.

E5. Команда open_file — протокол (СИНХРОННО тут и в :shared, и в Java-зеркале агента):
  добавь Command.OpenFile(String name) с name()="open_file" в protocol/Command.java (релей) —
  такую же запись понадобится добавить в KMP :shared/Command.kt и в jarvis-agent
  protocol/Command.java (это делает следующий промпт, но название и поля согласуй с ним:
  один параметр name — как в open_app). Добавь "open_file" в CommandWhitelist.ALLOWED.
  Обнови системный промпт GeminiIntentParser и правила StubIntentParser: «открой файл отчёт»,
  «покажи документ ...» -> {"type":"open_file","name":"..."} (только явное упоминание файла/
  документа, не путать с open_url и open_app).

E6. Тесты: ParseOutcome.parserUsed корректен для обоих парсеров; POST /api/eval/intents
  на маленьком датасете (3-4 фразы, StubIntentParser без ключа) даёт ожидаемую accuracy;
  GET /api/intent-log?format=csv отдаёт корректный CSV-заголовок.

Проверка: голосом/текстом 5 разных фраз в чате -> в intent_log 5 строк source=live с верными
parser/latency; POST /api/eval/intents с 10 фразами (сам придумай, с ожидаемыми типами) ->
в ответе accuracy и per-row детали; выгрузка CSV открывается в Excel.
```

---

## Промпт 2 — АГЕНТ (`C:\diplom\jarvis-agent`)

```
[Общий контекст]

Задача: команда open_file (безопасно, только из разрешённых папок) + отчёт по журналу
запусков agent_task (success rate/шаги/задержка для диплома). Протокол open_file уже
согласован с релеем (см. его промпт, пункт E5): Command.OpenFile(String name), name()="open_file".

A1. Протокол: добавь record OpenFile(String name) в jarvis-agent
  protocol/Command.java (Java-зеркало, ДОЛЖНО совпадать 1:1 с релеем — те же поля и "type").
  В :shared (C:\diplom\jarvis\shared, Kotlin) добавь такой же класс в Command.kt — без этого
  приложение не сможет даже прочитать команду, если она когда-то придёт ему во ScreenFrame/
  CommandResult с эхом (сейчас команды идут только телефон -> релей -> агент, но протокол
  общий, рассинхронизация ломает Jackson на любой стороне).

A2. FileCatalog — аналог AppCatalog, но для файлов: разрешённые КОРНЕВЫЕ папки —
  Downloads/Documents/Desktop/Pictures пользователя (те же, что уже в AppCatalog — вынеси общие
  константы, чтобы не дублировать пути). Ищем файл по имени (без учёта регистра, можно
  частичное совпадение) РЕКУРСИВНО, но только внутри этих папок — никакого выхода за них
  (проверка через Path.normalize()+startsWith на канонический путь, отдельно отбить попытки
  с "..", абсолютные пути, диски типа C:\Windows). Если найдено несколько совпадений — взять
  самый недавно изменённый и упомянуть остальные в ответе; если ни одного — ERROR с понятным
  текстом. Открывать через Desktop.getDesktop().open(file) (как уже сделано для openUrl).

A3. CommandExecutor: case Command.OpenFile c -> openFile(requestId, c.name()) — по аналогии с
  openApp. Тот же принцип: имя, а не путь, приходит от LLM/пользователя, разрешает всё
  ТОЛЬКО FileCatalog.

A4. Отчёт по RunJournal — новый класс RunReport с main(): сканирует
  $HOME\.jarvis-agent\runs\*.jsonl (см. формат в RunJournal.write — там уже completed,
  stepsTaken, durationMs, goal, scope), считает: всего запусков, success rate (completed=true
  / всего), по scope (browser/screen) отдельно, среднее число шагов, средняя длительность,
  медиана. Печатает таблицу в консоль И пишет $HOME\.jarvis-agent\runs\report-<дата>.csv
  (один запуск = одна строка: id файла, goal, scope, completed, stepsTaken, durationMs).
  Запуск: gradlew run -PmainClass=com.jarvis.agent.vision.RunReport (или отдельная задача
  runReport в build.gradle.kts — выбери сам, что проще, и объясни).

Проверка: «открой файл <существующее имя из Загрузок>» -> файл открывается; «открой файл
qwertyне_существует» -> понятная ошибка; после нескольких прогонов agent_task —
RunReport выводит таблицу и создаёт CSV.
```

---

## Промпт 3 — ПРИЛОЖЕНИЕ (`C:\diplom\jarvis\androidApp`)

```
[Общий контекст]

Задача: экран «История» — видно, что реально происходило (для демонстрации на защите), плюс
поддержка новой команды open_file на стороне UI. Автор новичок в Compose — объясняй по ходу.

P1. На релее нет готового REST для чтения audit_log под телефон — добавь на релее (в промпте
  релея это НЕ описано, сделай сам маленьким шагом, по образцу AgentPairingController):
  GET /api/agent/{agentId}/audit?limit=50 с заголовками X-Device-Id/X-Device-Token САМОГО
  ТЕЛЕФОНА (телефон уже умеет их слать? если нет — телефон аутентифицируется токеном из
  PairedDevice, тем же что в Auth по WebSocket; сделай проверку через DeviceAuthService,
  как в AgentAuthService, но для роли PHONE и её привязки к agentId). Ответ: список
  {occurredAt, command, status, details} — по убыванию времени.

P2. Экран «История» (новый, доступен из чата — иконка рядом с 🖥/⚙): список строк из P1,
  иконка по статусу (SENT/DENIED/ERROR — переиспользуй цвета из CommandProgress, где похоже),
  pull-to-refresh. Открывается для активного устройства (activeDevice.id).

P3. Подсказка-чип «открой файл …» рядом с уже существующими (скриншот/процессы/…) — просто
  подставляет текст в поле ввода, как остальные.

Проверка: несколько команд в чате (в том числе одна DENIED, например «выключи компьютер») ->
на экране «История» видно все, с правильными иконками; «открой файл <имя>» подставляется
чипом и отправляется.
```

---

## Финальная проверка шага

| # | Действие | Ожидание |
|---|---|---|
| 1 | 5 разных фраз в чате | 5 строк в `intent_log` (source=live), верные parser/latency |
| 2 | `POST /api/eval/intents` с 15–20 фразами (сам подготовь, с ожидаемыми типами) | accuracy в ответе, per-row детали |
| 3 | `GET /api/intent-log?format=csv` | открывается в Excel, читаемые колонки |
| 4 | «открой файл <имя из Загрузок>» | файл открывается на ПК |
| 5 | «открой файл несуществующий» | понятная ошибка, не падение |
| 6 | 3–5 запусков `agent_task` подряд | `RunReport` печатает success rate и создаёт CSV |
| 7 | Экран «История» в приложении | видно последние команды со статусами |

**После этого шага** данных достаточно для главы «Экспериментальная оценка»: точность
распознавания намерений (по `intent_log`/`/api/eval/intents`, с разбивкой llm/stub) и
success rate агента (по `RunReport`). Датасет фраз для E3 — собери сам 100+ строк на русском:
разные формулировки одних и тех же команд, разговорные варианты, намеренно неоднозначные.
