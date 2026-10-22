# Фауст

Дипломный проект: **AI-агент для автоматизации многоэтапных пользовательских задач.**
Человек говорит или пишет в телефон («скачай отчёт и отправь Пете в Telegram»),
а программа на ПК сама выполняет это через несколько приложений: планирует шаги,
смотрит на окна, нажимает, переспрашивает человека, когда не уверена.
(Раньше проект назывался Jarvis — это имя осталось в пакетах и папках.)

```
Телефон (Kotlin/Compose)  ⇄  Релей (Spring Boot + PostgreSQL в Docker)  ⇄  Агент на ПК (Java 21)
                                    │
                     своя модель разбора фраз (Ollama) · Gemini
```

## Папки

| Папка | Что внутри | README |
|---|---|---|
| `jarvis/` | приложение для телефона и общий протокол (`:shared`) | [jarvis/README.md](jarvis/README.md) |
| `jarvis-relay/` | сервер-посредник: привязка, маршрутизация, разбор фраз, белый список | [jarvis-relay/README.md](jarvis-relay/README.md) |
| `jarvis-agent/` | агент на ПК: команды, ИИ-агент, планировщик, оверлеи | [jarvis-agent/README.md](jarvis-agent/README.md) |
| `jarvis-ml/` | обучение своей модели разбора фраз (Qwen2.5 + LoRA) | [jarvis-ml/README.md](jarvis-ml/README.md) |
| `jarvis/docs/объяснение/` | объяснение проекта простыми словами для защиты (1–6) + `faust-docs.html` | — |
| `Claude outputs/` | старые записки о передаче работы между сессиями | — |

## Запуск

```
start-faust.bat          меню
start-faust.bat start    релей + агент (каждый день)
start-faust.bat qr       QR для привязки телефона
start-faust.bat demo     анимации без релея и телефона
start-faust.bat phone    поставить приложение на телефон по USB
start-faust.bat status   что запущено
start-faust.bat stop     остановить (база сохраняется)
```
Нужны Docker Desktop, JDK 21 (скрипт ищет её сам), для своей модели — Ollama.
Вся логика — в `faust.ps1`.

## Тесты

```
cd jarvis-agent  && .\gradlew.bat test                     137 тестов
cd jarvis-relay  && .\gradlew.bat test                     37 тестов
cd jarvis        && .\gradlew.bat :shared:allTests :androidApp:assembleDebug
```
(числа — на 22.10.2026)

## Правила, которые нельзя нарушать

1. Протокол меняется синхронно в трёх местах: `jarvis/shared`, Java-зеркало в релее, Java-зеркало в агенте.
2. Белый список команд на релее — граница безопасности; выключение/перезагрузка ПК в него не входят.
3. Ключ ИИ — только на релее (`jarvis-relay/.env`), не в приложении и не в агенте.
4. Агент сам подключается к релею (работает из-за NAT).
5. Токен и deviceId переживают перезапуск.

## Что не хранится в git

Секреты (`.env`, `local.properties`, токены в `%USERPROFILE%\.jarvis-agent`), сборки,
веса моделей и конвертер llama.cpp — см. `.gitignore` и раздел «Что не хранится в git»
в [jarvis-ml/README.md](jarvis-ml/README.md).
