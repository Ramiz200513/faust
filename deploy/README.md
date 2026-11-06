# «Фауст» на сервере (VPS)

Зачем: сейчас релей живёт на ноутбуке — выключил ноутбук, и связи нет, а
снаружи домашней сети до него не достучаться. На сервере в интернете релей
работает круглосуточно по постоянному адресу, и схема становится такой:

```
Телефон (где угодно) ──wss──► VPS: Caddy → релей → PostgreSQL ◄──wss── ПК дома
```

Что в этой папке:

| Файл | Что это |
|---|---|
| `docker-compose.prod.yml` | релей + база + Caddy (сертификат и `wss`), наружу открыт только Caddy |
| `Caddyfile` | настройка Caddy: домен → релей |
| `.env.prod.example` | образец секретов; копируется в `.env` на сервере |
| `scripts/backup.sh` | резервная копия базы (14 последних) |
| `scripts/update.sh` | обновление релея до свежего образа (его вызывает пайплайн) |

Пайплайн — `.github/workflows/ci.yml`: тесты всех частей → APK во вложениях →
образ релея в реестр GitHub → обновление на сервере.

---

## 1. Арендовать сервер

- ОС: **Ubuntu 24.04**, 1–2 ядра, **2 ГБ памяти** (1 ГБ хватит без своей
  модели), 20 ГБ диска.
- Хостинг — любой: PS.kz / Hoster.kz (Казахстан, ближе — быстрее отклик),
  Hetzner, DigitalOcean (кредиты по GitHub Student Pack).
- При создании добавьте свой SSH-ключ. Нет ключа — создайте на ПК:
  `ssh-keygen -t ed25519` (открытая часть — файл `~\.ssh\id_ed25519.pub`).

## 2. Домен

Нужен для сертификата (без него не будет `wss`). Два пути:
- свой домен у регистратора (~1–3 тыс. ₸ в год): A-запись `faust` → IP сервера;
- бесплатно: поддомен на duckdns.org → IP сервера.

Проверка с ПК: `nslookup faust.ваш-домен` показывает IP сервера.

## 3. Подготовить сервер (один раз)

```bash
ssh root@IP-СЕРВЕРА
# Docker
curl -fsSL https://get.docker.com | sh
# Отдельный пользователь для работы и для пайплайна — не root
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
mkdir -p /home/deploy/.ssh && cp ~/.ssh/authorized_keys /home/deploy/.ssh/ && chown -R deploy: /home/deploy/.ssh
# Файрвол: только SSH и веб. База и релей наружу не открыты и так.
ufw allow 22 && ufw allow 80 && ufw allow 443 && ufw --force enable
mkdir -p /opt/faust && chown deploy: /opt/faust
```

## 4. Положить файлы и секреты

С ПК (PowerShell, из `C:\diplom`):
```powershell
scp -r deploy deploy@IP-СЕРВЕРА:/opt/faust/
```
На сервере:
```bash
cd /opt/faust/deploy
cp .env.prod.example .env
openssl rand -base64 36   # -> JARVIS_ADMIN_TOKEN
openssl rand -base64 24   # -> JARVIS_DB_PASSWORD
nano .env                 # домен, два секрета, ключ Gemini, RELAY_IMAGE
chmod 600 .env
```
Слабые секреты релей не примет — не запустится и напишет, что не так
(`StrongSecretsCheck`).

## 5. Запуск

```bash
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps        # все healthy / running
docker compose -f docker-compose.prod.yml logs -f caddy   # «certificate obtained»
```
Проверка с ПК: `https://faust.ваш-домен/` открывается без предупреждения о
сертификате (ответ релея на `/` может быть 404 — это нормально, важен замок).

## 6. Переключить ПК и телефон на сервер

Новый сервер — новая база, поэтому один из двух путей:

**А. Перенести базу с ноутбука** (привязки сохранятся):
```powershell
docker exec jarvis-relay-db pg_dump -U jarvis -d jarvis > faust.sql
scp faust.sql deploy@IP-СЕРВЕРА:/opt/faust/deploy/backups/
```
```bash
cd /opt/faust/deploy
docker compose -f docker-compose.prod.yml exec -T postgres psql -U jarvis -d jarvis < backups/faust.sql
docker compose -f docker-compose.prod.yml restart relay
```
**Б. Привязать заново** — ПК по коду, телефон по QR.

Затем:
- ПК: в `%USERPROFILE%\.jarvis-agent\agent.properties` строка
  `relayUrl=wss://faust.ваш-домен/ws`, перезапустить агента;
- телефон: «Настройки → адрес релея» → `wss://faust.ваш-домен/ws`
  (или заново QR-код — в нём уже будет новый адрес).

## 7. Пайплайн и автодеплой

1. Создать репозиторий на GitHub (приватный) и отправить код:
   `git remote add origin https://github.com/ЛОГИН/faust.git` → `git push -u origin main`.
   Тесты и APK заработают сразу.
2. Образ релея публикуется как `ghcr.io/логин/faust-relay`. Репозиторий
   приватный — сервер должен войти в реестр (токен GitHub с правом
   `read:packages`): `docker login ghcr.io -u ЛОГИН` на сервере, один раз.
   В `.env` на сервере: `RELAY_IMAGE=ghcr.io/логин/faust-relay:latest`.
3. Автодеплой — в репозитории Settings → Secrets and variables → Actions:
   - секреты `VPS_HOST`, `VPS_USER` (= `deploy`), `VPS_SSH_KEY` (закрытый
     ключ, созданный ТОЛЬКО для пайплайна: `ssh-keygen -t ed25519 -f faust_ci`,
     открытую часть дописать в `/home/deploy/.ssh/authorized_keys`),
     `VPS_KNOWN_HOSTS` (вывод `ssh-keyscan IP-СЕРВЕРА`);
   - переменная `DEPLOY_ENABLED` = `true`;
   - Settings → Environments → `production`: можно включить «Required
     reviewers» — тогда каждый деплой ждёт вашего нажатия.

Теперь push в `main` → тесты → образ → `scripts/update.sh` на сервере
(резервная копия базы, новый релей, ожидание healthy).

**Откат:** в `.env` на сервере `RELAY_IMAGE=ghcr.io/логин/faust-relay:<sha
прошлого коммита>`, затем `sh scripts/update.sh`.

## 8. Своя модель на сервере (необязательно)

На дешёвом VPS нет видеокарты: модель на процессоре ответит медленнее, чем
дома (дома ~0,13 с на фразу). Перед решением — замер.
Файл модели (GGUF) после `ollama create` хранится внутри Ollama на ПК.
Где он — покажет строка `FROM` в выводе:
```powershell
ollama show faust-intent --modelfile     # FROM C:\Users\...\.ollama\models\blobs\sha256-…
# скопировать этот файл как model.gguf, а Modelfile — из jarvis-ml\runs\qwen15-v1
scp "<путь из FROM>" deploy@IP-СЕРВЕРА:/opt/faust/deploy/model/model.gguf
scp C:\diplom\jarvis-ml\runs\qwen15-v1\Modelfile deploy@IP-СЕРВЕРА:/opt/faust/deploy/model/
```
```bash
docker compose -f docker-compose.prod.yml --profile model up -d
docker compose -f docker-compose.prod.yml exec -w /model ollama ollama create faust-intent -f /model/Modelfile
```
(`Modelfile` ссылается на `./model.gguf` — поэтому `-w /model`.)
Без этого шага `JARVIS_INTENT_ENGINE=auto` сам уходит к Gemini.

## 9. Резервные копии

```bash
crontab -e
0 4 * * * cd /opt/faust/deploy && sh scripts/backup.sh >> backups/backup.log 2>&1
```
Копии лежат на том же сервере; важные иногда забирайте себе:
`scp deploy@IP-СЕРВЕРА:/opt/faust/deploy/backups/*.sql.gz .`

## Что защищает сервер

- наружу открыты только 22/80/443; база и релей — внутри сети Docker;
- весь трафик шифрован (`wss`/`https`, сертификат Let's Encrypt);
- релей не стартует со слабым админ-токеном или паролем базы;
- перебор кода привязки: 10 неудач с адреса и 100 на всех за 10 минут;
- телефон видит только свой ПК (старая схема выключена);
- ключ Gemini только в `.env` на сервере;
- деплой — только из `main` и только после зелёных тестов.
