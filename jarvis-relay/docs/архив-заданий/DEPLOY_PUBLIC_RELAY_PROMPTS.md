# Деплой: чтобы Jarvis работал у любого человека без Docker на его стороне

## Общий контекст

Сейчас "сервер" Jarvis (relay + PostgreSQL) поднимается через `docker-compose.yml`
**на твоей машине** — `start-jarvis.ps1` включает Docker Desktop, поднимает контейнеры
и запускает агента локально. Это отлично для разработки, но у другого человека:

- скорее всего нет Docker Desktop (и его установка — это уже "надо поднимать сервер",
  чего ты и хочешь избежать);
- он физически не в одной сети с релеем — `ws://localhost:8080/ws` или домашний IP
  ему не подходят;
- сам ПК-агент сейчас запускается только через `./gradlew run --args="..."` — это
  требует установленного JDK/Gradle и работы с консолью, а не "скачал и открыл".

**Про Vercel** (ты предлагал "там бесплатные серверы"): я проверил — не подойдёт
именно для этой части проекта. Vercel — это serverless-функции (Node.js/Python/Go/Bun,
Java там вообще не запустить), каждый вызов живёт максимум 300 секунд на бесплатном
плане, и даже их бета-поддержка WebSocket работает только через Node.js-функции с тем
же лимитом по времени жизни. Наш релей — это постоянно работающий Spring Boot процесс,
который держит WebSocket-соединения с телефонами и ПК-агентами часами и сверяется с
PostgreSQL — это ровно то, для чего serverless-платформы (Vercel, Netlify, Cloudflare
Workers) не предназначены. Для такой задачи нужна виртуальная машина (VPS), на которой
что-то работает постоянно, а не по запросу.

Хорошая новость: тебе не нужно ничего переписывать с нуля. Идея простая —

1. **Тот же `docker-compose.yml`, что уже есть, поднимается один раз не у тебя на
   ноутбуке, а на маленькой виртуальной машине в облаке**, которая работает 24/7.
   Ты (или кто угодно) её больше не трогаешь — она просто всегда включена.
2. У этой машины появляется постоянный домен (бесплатно, через DuckDNS) и
   HTTPS/WSS-сертификат (бесплатно, через Caddy + Let's Encrypt) — без них Android
   не даст подключиться по незащищённому `ws://` к чужому IP, да и просто нечестно
   было бы гонять пароли/токены открытым текстом через интернет.
3. **В коде agent'а и приложения меняется всего два адреса по умолчанию** —
   `ws://localhost:8080/ws` → `wss://<твой-поддомен>.duckdns.org/ws`. После этого
   свежая установка сама подключается к твоему серверу, а не ищет localhost.
4. Для ПК-агента добавляется маленькое окошко первого запуска (сейчас код привязки
   передаётся только аргументом командной строки — `--args="... <код>"`, что для
   человека без Gradle не вариант). Дальше агент упаковывается в обычный `.exe`
   через `jpackage` — двойной клик, JRE уже внутри, Java на компьютере пользователя
   не нужна.

Честно про то, что НЕ становится полностью самостоятельным: привязать **новый** ПК
к серверу всё ещё может только ты (или кто-то с `JARVIS_ADMIN_TOKEN`) — ты выписываешь
одноразовый код и отдаёшь его человеку. Это нормально для диплома и демонстрации
(десяток тестовых пользователей), но это не "любой в интернете сам регистрируется".
А вот привязать **телефон** к уже зарегистрированному ПК — это уже полностью
самообслуживание, оно у тебя и так работает (`PairPhoneDialog` сам просит у релея
код через `/api/agent/phone-pairing-code`, админ-токен для этого не нужен).

---

## Часть А. Сервер — ручные шаги (это делаешь ты сам, не ИИ-агент)

### А1. Выбор и создание виртуальной машины

Два нормальных варианта:

- **Oracle Cloud Always Free** — по-настоящему $0/мес навсегда (ARM-инстанс, сейчас
  это 2 OCPU / ~12 ГБ RAM — Oracle пару раз урезал халяву, раньше давали больше, но
  для relay + Postgres этого с большим запасом хватает). Минус — регистрация
  капризная: просят карту для верификации (без списаний), иногда сайт пишет "нет
  мощностей в этом регионе" при попытке создать именно бесплатный инстанс — тогда
  просто пробуешь другой регион/через день-два.
- **Hetzner CX22** — ~5€/мес, честно списываются деньги, зато регистрация и создание
  сервера занимают 5 минут без сюрпризов. Для диплома, где важно, чтобы демонстрация
  на защите не сорвалась из-за "Oracle сегодня не даёт мощность", это спокойный
  вариант.

Решили не гнаться за бесплатным и взять недорогой — вот пошагово именно для Hetzner:

1. Зайди на `https://www.hetzner.com/cloud` → "Sign up" → подтверди почту.
2. При первом добавлении сервера попросят способ оплаты (карта или PayPal) — это
   нормально, спишется только за реально арендованное время (CX22 — это доли евро
   в день, можно удалить сервер в любой момент и платить перестанет).
3. В консоли (`console.hetzner.cloud`) → "New Project" → внутри проекта "Add Server".
4. Location — ближайший к тебе датацентр с разумным пингом (Helsinki/Falkenstein
   обычно ближе для СНГ, чем US-варианты).
5. Image — Ubuntu 24.04.
6. Type — вкладка "Shared vCPU", строка **CX22** (2 vCPU / 4 ГБ RAM — с запасом
   для relay+Postgres+Caddy).
7. SSH keys — нажми "Add SSH key", вставь публичный ключ со своего компьютера. Если
   ключа ещё нет, сгенерируй на своей Windows-машине в PowerShell:
   ```powershell
   ssh-keygen -t ed25519 -C "jarvis-deploy"
   ```
   (по умолчанию сохранится в `$HOME\.ssh\id_ed25519` / `id_ed25519.pub`) — содержимое
   `.pub`-файла и есть то, что нужно вставить в Hetzner. Без ключа тоже можно (тогда
   Hetzner пришлёт временный root-пароль на почту), но ключ удобнее и безопаснее.
8. "Create & Buy now" → через полминуты в списке серверов появится публичный IPv4 —
   это и есть адрес для DuckDNS (шаг А3) и для `ssh root@<IP>` (шаг А2).
9. В том же проекте, слева "Firewalls" → создай новый файрвол с правилами: разрешить
   входящие TCP 22 (SSH), 80 и 443 — и примени его к серверу. Это и есть "открой
   порты" из общего шага А1 выше, просто применительно к интерфейсу Hetzner.

Дальше — ровно шаги А2–А4 ниже, они уже не зависят от того, какого провайдера
выбрал (Oracle/Hetzner/Google) — просто заходишь по SSH на этот IP.

В любом случае при создании машины возьми:
- Ubuntu 22.04/24.04 LTS,
- минимум 1 vCPU / 2 ГБ RAM (relay + Postgres в контейнерах это потянут),
- запиши её публичный IPv4-адрес — он понадобится в следующем шаге.

**Другие "бесплатные" варианты, которые я проверил и почему они НЕ подходят сюда:**

- **Google Cloud Always Free (e2-micro)** — тоже честно бесплатно навсегда (не триал),
  но это резервный вариант, а не основной: доступен только в трёх регионах США
  (us-west1/us-central1/us-east1 — заметный пинг из СНГ), у e2-micro всего ~1 ГБ RAM
  (Postgres + Spring Boot туда влезут, но впритык — стоит следить за памятью), и
  бесплатный исходящий трафик ограничен ~1 ГБ/мес — если включишь фичу трансляции
  экрана и погоняешь её подольше на защите, можно вылезти за лимит и получить
  небольшой счёт. Держи в уме как план Б, если с Oracle совсем не получится.
- **AWS/Azure free tier (t2.micro / B1s)** — бесплатны только первые 12 месяцев с
  момента регистрации аккаунта, дальше начинают тихо списывать деньги. Для дипломного
  проекта, который живёт дольше года, это не "бесплатно навсегда", а отложенный счёт.
- **Render / Railway / Fly.io / Koyeb и подобные "free tier" PaaS** — специально
  проверил: это та же ловушка, что и с Vercel, просто менее очевидная. У них либо
  контейнер "засыпает" после 15–45 минут без запросов (это ломает WebSocket — телефон
  и агент держат соединение часами, а не дергают эндпоинт раз в несколько минут), либо
  бесплатный уровень — это не "бесплатно", а стартовые кредиты (обычно на первый
  месяц), которые заканчиваются и дальше идёт обычная оплата. Ни один из них не даёт
  то же самое, что честная VPS (Oracle/Google/Hetzner) — постоянно работающую машину,
  которую ты полностью контролируешь.

Открой на машине (в настройках облака — Security List / Firewall) порты
**22 (SSH), 80 и 443** на входящие соединения. 5432 и 8080 наружу открывать **не
нужно** — они уйдут только на Caddy (порты 80/443), Postgres и сам relay изнутри
Docker-сети наружу торчать не будут.

### А2. Docker на сервере

По SSH зайди на машину и поставь Docker + Compose plugin (для Ubuntu):

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
# перелогинься (exit и снова ssh), чтобы группа применилась
docker compose version   # проверка, что compose plugin на месте
```

### А3. Бесплатный домен через DuckDNS

1. Зайди на `https://www.duckdns.org`, войди через GitHub/Google.
2. Заведи поддомен, например `jarvis-<что-то-своё>.duckdns.org`, и привяжи его к
   публичному IP машины из шага А1.
3. DuckDNS отдаёт токен для автообновления IP — на сервере с постоянным (не
   динамическим) IP это не обязательно, но если IP всё же может смениться, поставь
   их cron-скрипт обновления (инструкция прямо на сайте DuckDNS, раздел "install").

DuckDNS иногда бывает недоступен на несколько десятков минут (у него было несколько
коротких падений в этом году) — на уже выданный сертификат и на уже установленное
соединение это не влияет, страшно только если IP сменится именно в момент такого
падения. Если хочешь совсем без сюрпризов на защите — купи вместо DuckDNS обычный
домен на Reg.ru/Namecheap (обычно 150–300 ₽/год за `.ru`/`.online` и т.п.) и укажи
у него A-запись на IP сервера; всё остальное в этом документе не меняется, просто
вместо `*.duckdns.org` подставляешь свой домен.

Другие бесплатные варианты, которые смотрел вместо DuckDNS:

- **No-IP (free)** — тоже бесплатные поддомены, но раз в 30 дней нужно вручную
  подтверждать хостнейм по ссылке в письме, иначе он отключается. Если про это
  забыть посреди учёбы, сервер "молча" перестанет резолвиться прямо перед защитой —
  неудобно именно тем, что тихо ломается сам по себе.
- **FreeDNS (afraid.org)** — тоже бесплатные поддомены, но под общими доменами,
  которыми пользуются много случайных людей; некоторые из этих доменов время от
  времени попадают в списки подозрительных у антивирусов/фильтров просто из-за
  чужой активности на соседних поддоменах — для демонстрации на защите риск того не
  стоит.
- **Cloudflare** — не выдаёт свои поддомены бесплатно, зато если всё же купишь
  дешёвый домен (см. выше), бесплатный план Cloudflare для него — самый надёжный
  вариант из всех: свой домен + Cloudflare DNS вместо DuckDNS полностью убирает и
  риск падения DuckDNS, и риск "забыл подтвердить" у No-IP.

Итог: для черновой разработки DuckDNS вполне достаточно и это честный $0; для самого
дня защиты, если хочется вообще исключить любой сюрприз с доменом — дешёвый домен
+ Cloudflare через несколько сотен рублей того стоит.

### А4. Перенос проекта на сервер

Проще всего — через git. На сервере:

```bash
git clone <адрес твоего репозитория jarvis-relay> jarvis-relay
cd jarvis-relay
cp .env.example .env
nano .env
```

В `.env` **обязательно** смени на настоящие случайные значения (сейчас там
дефолты для localhost — `dev-admin-change-me` и `jarvis`/`jarvis`, на публичном
IP с ними нельзя):

```
JARVIS_ADMIN_TOKEN=<длинная случайная строка, например: openssl rand -hex 32>
JARVIS_LLM_API_KEY=<твой ключ Gemini>
JARVIS_DB_USER=jarvis
JARVIS_DB_PASSWORD=<другая длинная случайная строка>
```

`openssl rand -hex 32` прямо на сервере сгенерирует такую строку.

Промпты ниже (Часть Б) нужно сначала применить **в своей копии репозитория** (там,
где работает твой ИИ-агент), закоммитить и запушить, и только потом делать
`git pull` на сервере — либо наоборот, применить их прямо на сервере вручную тем же
редактором, раз изменений немного. Дальше на сервере:

```bash
docker compose up -d --build
docker compose logs -f relay   # убедиться, что поднялся без ошибок, Ctrl+C чтобы выйти
```

---

## Часть Б. Промпты для локального ИИ-агента (правки в коде)

### Промпт 1 — Caddy вместо прямого доступа к relay/Postgres

Файл: `jarvis-relay/docker-compose.yml`. Убираем `ports:` у `postgres` и `relay`
(наружу теперь торчит только Caddy на 80/443, друг друга сервисы видят по имени
внутри docker-сети — это уже так и работает, `JARVIS_DB_URL` использует `postgres`
как хост), добавляем сервис `caddy`.

Было:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: jarvis-relay-db
    environment:
      POSTGRES_DB: jarvis
      POSTGRES_USER: ${JARVIS_DB_USER:-jarvis}
      POSTGRES_PASSWORD: ${JARVIS_DB_PASSWORD:-jarvis}
    ports:
      - "5432:5432"
    volumes:
      - jarvis-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U jarvis -d jarvis"]
      interval: 5s
      timeout: 3s
      retries: 5
    restart: unless-stopped

  relay:
    build: .
    container_name: jarvis-relay
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      JARVIS_DB_URL: jdbc:postgresql://postgres:5432/jarvis
      JARVIS_DB_USER: ${JARVIS_DB_USER:-jarvis}
      JARVIS_DB_PASSWORD: ${JARVIS_DB_PASSWORD:-jarvis}
      JARVIS_ADMIN_TOKEN: ${JARVIS_ADMIN_TOKEN:-dev-admin-change-me}
      JARVIS_LLM_API_KEY: ${JARVIS_LLM_API_KEY:-}
      JARVIS_LLM_MODEL: ${JARVIS_LLM_MODEL:-gemini-3.6-flash}
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'echo > /dev/tcp/127.0.0.1/8080' || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 20s
    restart: unless-stopped

volumes:
  jarvis-db-data:
```

Стало:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: jarvis-relay-db
    environment:
      POSTGRES_DB: jarvis
      POSTGRES_USER: ${JARVIS_DB_USER:-jarvis}
      POSTGRES_PASSWORD: ${JARVIS_DB_PASSWORD:-jarvis}
    # Порт наружу больше не публикуем — на публичном сервере 5432 не должен
    # торчать в интернет. Relay достаёт Postgres по имени сервиса ("postgres")
    # внутри docker-сети, это не менялось.
    volumes:
      - jarvis-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U jarvis -d jarvis"]
      interval: 5s
      timeout: 3s
      retries: 5
    restart: unless-stopped

  relay:
    build: .
    container_name: jarvis-relay
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      JARVIS_DB_URL: jdbc:postgresql://postgres:5432/jarvis
      JARVIS_DB_USER: ${JARVIS_DB_USER:-jarvis}
      JARVIS_DB_PASSWORD: ${JARVIS_DB_PASSWORD:-jarvis}
      JARVIS_ADMIN_TOKEN: ${JARVIS_ADMIN_TOKEN:-dev-admin-change-me}
      JARVIS_LLM_API_KEY: ${JARVIS_LLM_API_KEY:-}
      JARVIS_LLM_MODEL: ${JARVIS_LLM_MODEL:-gemini-3.6-flash}
    # Порт наружу тоже не публикуем — единственная публичная дверь теперь Caddy.
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'echo > /dev/tcp/127.0.0.1/8080' || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 20s
    restart: unless-stopped

  # Обратный прокси: сам получает и продлевает TLS-сертификат Let's Encrypt для
  # домена из Caddyfile и заворачивает и HTTP(S), и WebSocket-апгрейд на relay:8080.
  caddy:
    image: caddy:2-alpine
    container_name: jarvis-caddy
    depends_on:
      - relay
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - jarvis-caddy-data:/data
      - jarvis-caddy-config:/config
    restart: unless-stopped

volumes:
  jarvis-db-data:
  jarvis-caddy-data:
  jarvis-caddy-config:
```

Новый файл `jarvis-relay/Caddyfile` (рядом с `docker-compose.yml`, замени
`jarvis-example.duckdns.org` на свой реальный поддомен из шага А3):

```
jarvis-example.duckdns.org {
    reverse_proxy relay:8080
}
```

Это всё — Caddy сам поймёт, что `/ws` внутри этого блока нужно апгрейдить в
WebSocket (начиная с Caddy 2 `reverse_proxy` делает это без дополнительных
директив), и сам заказывает/продлевает сертификат Let's Encrypt для этого домена
при старте, если 80/443 снаружи доступны — поэтому важен шаг А1 про открытые порты.

### Промпт 2 — новый адрес по умолчанию в агенте и в приложении

Меняем дефолт с `localhost`/эмуляторского адреса на публичный `wss://` — тогда
свежая установка на чужом компьютере или телефоне сама находит твой сервер, даже
если пользователь ничего не настраивал руками. Замени `jarvis-example.duckdns.org`
на свой реальный поддомен.

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AgentConfig.java`.

Было:

```java
    private static final String KEY_RELAY_URL = "relayUrl";
    private static final String KEY_PHONE_RELAY_URL = "phoneRelayUrl";
    static final String DEFAULT_RELAY_URL = "ws://localhost:8080/ws";
```

Стало:

```java
    private static final String KEY_RELAY_URL = "relayUrl";
    private static final String KEY_PHONE_RELAY_URL = "phoneRelayUrl";
    // Публичный relay на постоянно включённом сервере — см. docs/DEPLOY_PUBLIC_RELAY_PROMPTS.md.
    // Для локальной разработки по-прежнему можно передать ws://localhost:8080/ws
    // аргументом командной строки или в agent.properties (см. resolve()).
    static final String DEFAULT_RELAY_URL = "wss://jarvis-example.duckdns.org/ws";
```

В том же файле, в самом низу класса, добавь метод — он понадобится Промпту 3, чтобы
диалог первого запуска мог передать введённый пользователем адрес дальше как часть
`AgentConfig`, не переписывая всю остальную логику `resolve()`:

Было (конец файла):

```java
    private static Properties read(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
    }
}
```

Стало:

```java
    private static Properties read(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
    }

    /** Копия с другим relayUrl (тот же phoneRelayUrl) — нужно диалогу первого запуска. */
    public AgentConfig withRelayUrl(String relayUrl) {
        return new AgentConfig(relayUrl, phoneRelayUrl);
    }
}
```

Файл: `jarvis/androidApp/src/main/java/.../SettingsRepository.kt` (тот же, где
`DEFAULT_URL`).

Было:

```kotlin
    companion object {
        const val DEFAULT_URL = "ws://10.0.2.2:8080/ws" // 10.0.2.2 = хост из эмулятора
    }
```

Стало:

```kotlin
    companion object {
        // Публичный relay на постоянно включённом сервере — см.
        // docs/DEPLOY_PUBLIC_RELAY_PROMPTS.md. Это только запасной вариант для
        // ручного ввода: обычно адрес приходит из QR-кода (поле relay=), см.
        // PairingQr.kt.
        const val DEFAULT_URL = "wss://jarvis-example.duckdns.org/ws"
    }
```

### Промпт 3 — окно первого запуска для ПК-агента (без аргументов командной строки)

Сейчас код привязки ПК передаётся **только** аргументом командной строки
(`./gradlew run --args="ws://... <код>"`) — для готового `.exe`, который человек
просто открывает двойным кликом, так не получится. Добавляем маленькое окно
(в том же стиле, что и `PairPhoneDialog`), которое просит адрес relay и код,
если агент ещё ни разу не привязывался.

Новый файл `jarvis-agent/src/main/java/com/jarvis/agent/AgentSetupDialog.java`:

```java
package com.jarvis.agent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.lang.reflect.InvocationTargetException;

/**
 * Окно первого запуска: если агент ещё не привязан (нет сохранённого токена),
 * а код привязки не передан аргументом командной строки, просим адрес relay
 * и одноразовый код прямо в GUI — иначе у человека без Gradle/консоли просто
 * нет способа привязать агента после установки готового .exe.
 *
 * Код привязки выписывает владелец сервера (см. Часть В в
 * docs/DEPLOY_PUBLIC_RELAY_PROMPTS.md) — сам себе через это окно ПК никто не
 * зарегистрирует, окно только собирает то, что уже выписано.
 */
public class AgentSetupDialog {

    /** Результат: адрес relay и код (code == null, если уже был привязан раньше). */
    public record Result(String relayUrl, String pairingCode) {}

    /**
     * Если агент уже привязан — просто возвращает текущий адрес без диалога.
     * Иначе показывает модальное окно и блокируется, пока человек не нажмёт
     * «Подключиться» или «Выход». Безопасно звать из main().
     */
    public static Result collectIfNeeded(AgentConfig config, TokenStore tokenStore) throws Exception {
        if (tokenStore.load() != null) {
            return new Result(config.relayUrl(), null);
        }
        Result[] out = new Result[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = show(config));
        } catch (InvocationTargetException e) {
            throw (e.getCause() instanceof Exception ex) ? ex : new RuntimeException(e.getCause());
        }
        return out[0];
    }

    private static Result show(AgentConfig config) {
        JTextField relayField = new JTextField(config.relayUrl(), 28);
        JTextField codeField = new JTextField(10);
        Result[] holder = new Result[1];

        JDialog dialog = new JDialog((Frame) null, "Jarvis — первый запуск", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JLabel intro = new JLabel("<html>Этот компьютер ещё не привязан.<br>"
                + "Введи адрес сервера и одноразовый код,<br>который тебе дали.</html>");
        root.add(intro, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(labelled("Адрес сервера (relay):", relayField));
        form.add(Box.createVerticalStrut(8));
        form.add(labelled("Код привязки:", codeField));
        root.add(form, BorderLayout.CENTER);

        JButton connect = new JButton("Подключиться");
        connect.addActionListener(e -> {
            String url = relayField.getText().trim();
            String code = codeField.getText().trim();
            if (url.isEmpty() || code.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Заполни оба поля.", "Jarvis",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            holder[0] = new Result(url, code);
            dialog.dispose();
        });
        JButton exit = new JButton("Выход");
        exit.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel();
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);
        buttons.add(connect);
        buttons.add(exit);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // блокирует до dispose() — этим и ждём ввода
        return holder[0];
    }

    private static JPanel labelled(String label, JTextField field) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(new JLabel(label), BorderLayout.NORTH);
        row.add(field, BorderLayout.CENTER);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        return row;
    }
}
```

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AgentMain.java` — подключаем
диалог перед созданием `RelayClient`, только если код не пришёл аргументом и
агент ещё не привязан.

Было:

```java
        AgentConfig config = AgentConfig.resolve(rest);
        String pairingCode = rest.length > 1 ? rest[1] : null;

        TokenStore tokenStore = new TokenStore();
        AgentTray tray = new AgentTray(AgentLog.filePath(), () -> PairPhoneDialog.open(config, tokenStore));
        tray.install();
```

Стало:

```java
        AgentConfig config = AgentConfig.resolve(rest);
        String pairingCode = rest.length > 1 ? rest[1] : null;

        TokenStore tokenStore = new TokenStore();

        // Готовый .exe без консоли: код может прийти только через это окно, а не
        // аргументом командной строки. Если агент уже привязан — окно не покажется.
        if (pairingCode == null) {
            AgentSetupDialog.Result setup = AgentSetupDialog.collectIfNeeded(config, tokenStore);
            if (setup == null) {
                System.out.println("Настройка отменена — выход.");
                return;
            }
            config = config.withRelayUrl(setup.relayUrl());
            pairingCode = setup.pairingCode();
        }

        AgentConfig finalConfig = config; // нужно для лямбды ниже (эффективно-финальная переменная)
        AgentTray tray = new AgentTray(AgentLog.filePath(),
                () -> PairPhoneDialog.open(finalConfig, tokenStore),
                () -> resetPairing(tokenStore));
        tray.install();
```

Дальше по файлу везде, где встречается `config.relayUrl()` (в частности при
создании `RelayClient`), переменная `config` уже указывает на актуальный объект
(с введённым в диалоге адресом) — отдельно ничего менять не нужно, если тип
переменной остался `AgentConfig config`.

В конец класса `AgentMain` (перед закрывающей скобкой) добавь маленький метод:

```java
    /** Сброс привязки из трея: стирает токен и предлагает перезапустить агент вручную. */
    private static void resetPairing(TokenStore tokenStore) {
        tokenStore.clear();
        JOptionPane.showMessageDialog(null,
                "Привязка сброшена. Закрой это окно и запусти Jarvis Agent заново — "
                        + "он снова спросит код.",
                "Jarvis", JOptionPane.INFORMATION_MESSAGE);
        System.exit(0);
    }
```

И добавь импорт `javax.swing.JOptionPane;` в начало `AgentMain.java`.

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/TokenStore.java` — добавляем
`clear()`, которым пользуется `resetPairing()` выше.

Было (конец файла):

```java
    private void write(String key, String value) {
        Properties props = readAll();          // сохраняем то, что уже было
        props.setProperty(key, value);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Данные агента jarvis. Не передавайте этот файл третьим лицам.");
            }
        } catch (IOException e) {
            System.err.println("Не удалось сохранить в " + file + ": " + e.getMessage());
        }
    }
}
```

Стало:

```java
    private void write(String key, String value) {
        Properties props = readAll();          // сохраняем то, что уже было
        props.setProperty(key, value);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Данные агента jarvis. Не передавайте этот файл третьим лицам.");
            }
        } catch (IOException e) {
            System.err.println("Не удалось сохранить в " + file + ": " + e.getMessage());
        }
    }

    /** Стирает токен и deviceId — используется при повторной привязке ПК с нуля. */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("Не удалось удалить " + file + ": " + e.getMessage());
        }
    }
}
```

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AgentTray.java` — конструктору
нужен второй колбэк (`onResetPairing`), плюс пункт меню и менее "хардкодный" текст
сообщения (сейчас там жёстко зашит `start-jarvis.bat`, которого не будет у
человека с готовым `.exe`).

Было:

```java
    private final Path logFile;
    private final Runnable onPairPhone;
    private TrayIcon trayIcon;
```

Стало:

```java
    private final Path logFile;
    private final Runnable onPairPhone;
    private final Runnable onResetPairing;
    private TrayIcon trayIcon;
```

Было:

```java
    public AgentTray(Path logFile, Runnable onPairPhone) {
        this.logFile = logFile;
        this.onPairPhone = onPairPhone;
    }
```

Стало:

```java
    public AgentTray(Path logFile, Runnable onPairPhone, Runnable onResetPairing) {
        this.logFile = logFile;
        this.onPairPhone = onPairPhone;
        this.onResetPairing = onResetPairing;
    }
```

Было:

```java
        MenuItem logItem = new MenuItem("Открыть лог");
        logItem.addActionListener(e -> openLog());
        menu.add(logItem);

        menu.addSeparator();
```

Стало:

```java
        MenuItem logItem = new MenuItem("Открыть лог");
        logItem.addActionListener(e -> openLog());
        menu.add(logItem);

        MenuItem resetItem = new MenuItem("Настроить заново");
        resetItem.addActionListener(e -> onResetPairing.run());
        menu.add(resetItem);

        menu.addSeparator();
```

Было:

```java
    @Override
    public void onAuthRejected(String reason) {
        applyStatus(Status.REJECTED, reason);
        if (trayIcon != null) {
            trayIcon.displayMessage(APP_NAME,
                    "ПК отвязан на сервере — запусти start-jarvis.bat для перепривязки",
                    TrayIcon.MessageType.WARNING);
        }
    }
```

Стало:

```java
    @Override
    public void onAuthRejected(String reason) {
        applyStatus(Status.REJECTED, reason);
        if (trayIcon != null) {
            trayIcon.displayMessage(APP_NAME,
                    "ПК отвязан на сервере — выбери «Настроить заново» в трее",
                    TrayIcon.MessageType.WARNING);
        }
    }
```

(Если у тебя уже есть где-то в `.claude`/README упоминание про два аргумента
конструктора `AgentTray` — просто игнорируй, это единственное место, где он
создаётся.)

### Промпт 4 — сборка обычного `.exe` через jpackage

`jarvis-agent/build.gradle.kts` уже использует плагин `application`, поэтому
`./gradlew installDist` и так собирает ровно то, что нужно `jpackage` (jar +
зависимости в `build/install/jarvis-agent/lib`). Дополнительно ставить нужно
только **JDK 21 (с `jpackage` внутри — он идёт в комплекте с JDK, начиная с 14)**
и **WiX Toolset** (нужен только на машине, где собираешь инсталлятор, — не на
машине, куда его потом ставят) — актуальная версия для JDK 21 это WiX v4 или v5,
скачивается с `https://wixtoolset.org/`.

Новый файл `jarvis-agent/scripts/build-installer.ps1`:

```powershell
# ============================================================================
#  build-installer.ps1 — собирает jarvis-agent-<версия>.msi: обычный Windows-
#  инсталлятор с JRE внутри. Человеку, который его ставит, Java не нужна.
#  Запускать на машине со установленными JDK 21 и WiX Toolset (v4/v5).
# ============================================================================

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent   # C:\diplom\jarvis-agent

Push-Location $Root
try {
    Write-Host '==> Собираю jar и зависимости (installDist)' -ForegroundColor Cyan
    .\gradlew.bat installDist --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'installDist упал' }

    $Version  = (Select-String -Path 'build.gradle.kts' -Pattern 'version = "(.+)"').Matches[0].Groups[1].Value
    $MainJar  = "jarvis-agent-$Version.jar"
    $LibDir   = Join-Path $Root 'build\install\jarvis-agent\lib'
    $OutDir   = Join-Path $Root 'build\installer'

    if (-not (Test-Path (Join-Path $LibDir $MainJar))) {
        throw "Не нашёл $MainJar в $LibDir — проверь версию в build.gradle.kts"
    }

    Write-Host '==> Собираю установщик (jpackage)' -ForegroundColor Cyan
    jpackage `
        --type msi `
        --name "Jarvis Agent" `
        --app-version $Version `
        --input $LibDir `
        --main-jar $MainJar `
        --main-class com.jarvis.agent.AgentMain `
        --dest $OutDir `
        --win-shortcut `
        --win-menu `
        --vendor "Jarvis diploma project"

    Write-Host "`nГотово: $OutDir\Jarvis Agent-$Version.msi" -ForegroundColor Green
}
finally {
    Pop-Location
}
```

Запуск: `./scripts/build-installer.ps1` из `jarvis-agent`. Готовый `.msi` из
`build/installer/` — это и есть файл, который "скачал и открыл" человек с любого
другого компьютера; ставить его нужно на Windows (jpackage под каждую ОС собирает
инсталлятор только для той же ОС, на которой запущен, — если понадобится версия
под macOS/Linux, `jpackage` для них гоняется отдельно на соответствующей машине,
это уже за рамками диплома на защите).

---

## Часть В. Как выдать код новому человеку

Привязка нового ПК всё ещё требует твоего `JARVIS_ADMIN_TOKEN` (это осознанный
компромисс — см. "Общий контекст"). Со своего компьютера (не с того, который
привязываешь) выполни:

```powershell
$AdminToken = "<тот самый JARVIS_ADMIN_TOKEN из .env на сервере>"
$Body = @{ label = "ноутбук тестировщика" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "https://jarvis-example.duckdns.org/api/pairing-codes" `
    -Headers @{ "X-Admin-Token" = $AdminToken } -ContentType "application/json" -Body $Body
```

Ответ содержит одноразовый `code` — его и вставляют в окно `AgentSetupDialog`
(Промпт 3) на компьютере тестировщика вместе с адресом `wss://jarvis-example.duckdns.org/ws`
(он там уже будет стоять по умолчанию после Промпта 2, менять не придётся).

Телефон дальше привязывается самостоятельно — тестировщик открывает в приложении
Jarvis экран сканирования QR, а на только что привязанном ПК через трей
"Привязать телефон" показывается QR с уже правильным публичным адресом.

---

## Проверка

- [ ] На сервере `docker compose ps` показывает `postgres`, `relay`, `caddy` в
      статусе `healthy`/`running`, а `docker compose logs caddy` содержит строки
      про успешно выданный сертификат (`certificate obtained successfully` или
      похожее), без ошибок ACME.
- [ ] С любого устройства (не с сервера) `https://jarvis-example.duckdns.org`
      в браузере не даёт ошибку сертификата (можно ожидать 404/пустой ответ от
      самого relay — это нормально, сертификат уже главное).
- [ ] На **чистом** Windows-компьютере без Java и без Docker: скачал
      `Jarvis Agent-*.msi` → установил → при первом запуске появилось окно
      `AgentSetupDialog` → ввёл адрес и код, выписанный через Часть В → трей
      показал статус "подключено".
- [ ] На телефоне с чистой установкой приложения (без ручной правки настроек):
      привязка через QR с только что запущенного ПК-агента прошла, команда
      (например `screenshot`) доходит и возвращается.
- [ ] Перезапуск сервера (`docker compose restart` или ребут самой VM) — relay
      и Postgres поднимаются сами (`restart: unless-stopped`), данные (устройства,
      токены) не потерялись — том `jarvis-db-data` не трогали.
- [ ] В трее агента пункт "Настроить заново" стирает токен и после ручного
      перезапуска `Jarvis Agent.exe` окно первого запуска появляется снова.
- [ ] `.env` на сервере содержит **не дефолтные** `JARVIS_ADMIN_TOKEN` и
      `JARVIS_DB_PASSWORD` (это стоит явно показать на защите как пример того,
      что вы думали о безопасности продакшен-конфигурации, — хорошо ляжет рядом
      с рассказом про whitelist команд).
