"""
Дистилляция: своя модель учится отвечать так же, как Gemini, на ЖИВЫХ фразах.

Чем это отличается от «учителя» в build_trainset.py. Там Gemini только
перефразирует просьбу, смысл которой задали мы, — фразы выходят вокруг наших же
шаблонов и словарей. Здесь Gemini делает обе половины работы сам:

  pool   играет пользователя: придумывает, что люди говорят ассистенту в разных
         ситуациях и разным стилем (ни команд, ни слов мы ему не подсказываем);
  label  размечает эти фразы ТЕМ ЖЕ промптом, которым разбирает фразы в релее
         (GeminiIntentParser.SYSTEM_PROMPT), только пачками — так в десятки раз дешевле;
  local  на те же фразы отвечает наша текущая модель (Ollama, бесплатно);
  build  фразы делятся на две части:
           * обучение — data/distill.jsonl, его подхватывает build_trainset.py;
           * экзамен Б — jarvis-relay/eval/intent_test_b.jsonl: отложенные 15% фраз.
             В обучение они не попадают; на них измеряется, насколько наша модель
             согласна с Gemini на фразах, которые писали не мы.

Ответы Gemini кэшируются на диске (data/distill_cache.json): повторный запуск
денег не тратит и продолжает с места остановки. Число платных запросов за запуск
ограничено --max-requests.

Запуск (нужны релей с ключом Gemini и Ollama):
  python distill.py                    все шаги
  python distill.py --max-requests 0   без Gemini: только пересчитать по кэшу
  python distill.py --model faust-intent   с какой нашей моделью сравнивать
"""
import argparse
import hashlib
import json
import os
import re
import sys
import textwrap
import time

from common import DATA, HERE, RELAY_EVAL, norm, read_jsonl, relay_post, target, to_command, write_jsonl
from vocab import APPS, FOLDERS, PROCS, SITES

sys.path.insert(0, RELAY_EVAL)
from intent_eval import host_of, norm_app, norm_proc  # noqa: E402

CACHE = os.path.join(DATA, "distill_cache.json")
PARSER_JAVA = os.path.join(HERE, "..", "jarvis-relay", "src", "main", "java", "com", "jarvis", "relay", "llm",
                           "GeminiIntentParser.java")
HOLDOUT_PERCENT = 15      # доля фраз, уходящая в экзамен Б
BATCH = 40                # фраз в одном запросе на разметку

# --- кто и в какой обстановке говорит с ассистентом --------------------------
SCENES = [
    "студент делает учебные задания за компьютером",
    "программист работает над проектом",
    "человек лежит на диване, смотрит кино на компьютере и управляет им с телефона",
    "офисный работник весь день в документах, таблицах и почте",
    "геймер между катками",
    "человек только проснулся и начинает день за компьютером",
    "человек собирается уходить и оставляет компьютер",
    "человек готовит на кухне, руки заняты, компьютер в другой комнате",
    "пожилой человек, который плохо разбирается в компьютерах",
    "человек показывает что-то друзьям на большом экране",
]
STYLES = [
    "очень коротко, 1–4 слова, как бросают на ходу",
    "разговорно, со сленгом и словами-паразитами",
    "как после распознавания речи: без знаков препинания, с оговорками и случайно не так распознанными словами",
    "вежливо и многословно, с объяснением, зачем это нужно",
    "как печатают на телефоне в спешке: с опечатками и сокращениями",
    "по-английски или вперемешку русские и английские слова",
]
ABOUT = ("Ассистент «Фауст» управляет компьютером пользователя по фразам с телефона. Он умеет: менять громкость; "
         "открывать сайты; открывать программы и папки; закрывать программы; делать скриншот экрана; блокировать "
         "экран; показывать запущенные процессы; выполнять многошаговые задачи в браузере и в программах "
         "(найти, скачать, заполнить, отправить).")
MIX = ("Примерно треть фраз — простые просьбы в одно действие, треть — задачи из нескольких действий, остальное — "
       "то, что люди всё равно говорят ассистенту, хотя он этого не делает: болтовня, вопросы, бытовые просьбы, "
       "будильники и напоминания, просьбы выключить компьютер, что-то купить или оплатить.")


class Budget:
    """Сколько платных запросов к Gemini ещё можно сделать за этот запуск."""

    def __init__(self, limit):
        self.left, self.used, self.fails = limit, 0, 0

    def ask(self, prompt, temperature):
        """Ответ Gemini (разобранный JSON) или None, если лимит исчерпан либо Gemini не отвечает."""
        if self.left <= 0 or self.fails >= 3:
            return None
        for attempt in range(3):
            try:
                self.left -= 1
                self.used += 1
                out = json.loads(relay_post("/api/admin/llm/generate",
                                            {"prompt": prompt, "temperature": temperature, "json": True})["text"])
                self.fails = 0
                return out
            except Exception as e:
                print(f"  Gemini: попытка {attempt + 1} не удалась ({e})", flush=True)
                if self.left <= 0:
                    break
                time.sleep(10)
        self.fails += 1
        return None


def load_cache():
    cache = json.load(open(CACHE, encoding="utf-8")) if os.path.exists(CACHE) else {}
    for part in ("pool", "label", "local"):
        cache.setdefault(part, {})
    return cache


def save_cache(cache):
    os.makedirs(DATA, exist_ok=True)
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=0)


# =============================================================================
#  1. pool — Gemini в роли пользователя
# =============================================================================
def step_pool(cache, budget, per_request):
    for i, scene in enumerate(SCENES):
        # Каждой обстановке — три стиля из шести, со сдвигом: все сочетания брать незачем,
        # а так каждый стиль встречается в пяти разных обстановках.
        for j in (i, i + 2, i + 3):
            style = STYLES[j % len(STYLES)]
            key = f"{i}:{j % len(STYLES)}"
            if key in cache["pool"]:
                continue
            prompt = (f"{ABOUT}\n\nПридумай {per_request} разных фраз, которые говорит или пишет ассистенту такой "
                      f"пользователь: {scene}. {MIX}\nСтиль фраз: {style}.\nФразы должны быть разными по смыслу и "
                      "по построению, как у живых людей; не объясняй и не нумеруй. Ответ — только JSON-массив строк.")
            items = budget.ask(prompt, 1.0)
            if items is None:
                return
            cache["pool"][key] = [str(x).strip() for x in items if isinstance(x, str) and 2 <= len(x.strip()) <= 220]
            save_cache(cache)
            print(f"  pool {key}: {len(cache['pool'][key])} фраз ({scene[:40]}… / {style[:30]}…)", flush=True)


def pool_phrases(cache):
    """Все придуманные фразы без повторов (с точностью до регистра и знаков)."""
    seen, out = set(), []
    for key in sorted(cache["pool"]):
        for p in cache["pool"][key]:
            if norm(p) and norm(p) not in seen:
                seen.add(norm(p))
                out.append(p)
    return out


# =============================================================================
#  2. label — Gemini размечает своим рабочим промптом
# =============================================================================
def parser_prompt():
    """Промпт разбора фраз прямо из кода релея: размечаем ровно тем, чем Gemini разбирает фразы в работе."""
    src = open(PARSER_JAVA, encoding="utf-8").read()
    return textwrap.dedent(re.search(r'SYSTEM_PROMPT = """\n(.*?)"""', src, re.S).group(1)).strip()


def step_label(cache, budget, phrases):
    todo = [p for p in phrases if norm(p) not in cache["label"]]
    rules = parser_prompt()
    for start in range(0, len(todo), BATCH):
        chunk = todo[start:start + BATCH]
        prompt = (rules + "\n\nСейчас фраз несколько. Разбери КАЖДУЮ по правилам выше, независимо от остальных.\n"
                  "Ответ — JSON-массив той же длины; каждый элемент — объект {\"i\": <номер фразы>, \"command\": "
                  "{<команда>}}. Для agent_task пиши только type и scope (goal и maxSteps не нужны).\n\nФразы:\n"
                  + "\n".join(f"{i}. {p}" for i, p in enumerate(chunk, 1)))
        items = budget.ask(prompt, 0.0)
        if items is None:
            return
        got = 0
        for it in items if isinstance(items, list) else []:
            i, cmd = (it or {}).get("i"), (it or {}).get("command")
            if isinstance(i, int) and 1 <= i <= len(chunk) and isinstance(cmd, dict) and cmd.get("type"):
                cache["label"][norm(chunk[i - 1])] = cmd
                got += 1
        save_cache(cache)
        print(f"  label: {start + len(chunk)}/{len(todo)} (в этой пачке размечено {got} из {len(chunk)})", flush=True)


# =============================================================================
#  3. local — что отвечает наша модель
# =============================================================================
def step_local(cache, model, phrases):
    import http.client

    from eval_ollama import ask
    answers = cache["local"].setdefault(model, {})
    todo = [p for p in phrases if norm(p) not in answers]
    if not todo:
        return
    conn = http.client.HTTPConnection("127.0.0.1", 11434, timeout=120)
    ask(conn, model, "прогрев")
    for i, p in enumerate(todo, 1):
        raw, conf, _ = ask(conn, model, p)
        answers[norm(p)] = [raw, round(conf, 4)]
        if i % 200 == 0:
            print(f"  local: {i}/{len(todo)}", flush=True)
    save_cache(cache)


# =============================================================================
#  4. build — приведение ответов Gemini к нашим обозначениям и раскладка
# =============================================================================
# Gemini и наши словари называют одно и то же по-разному («calculator» и «calc»,
# «https://www.ozon.ru/» и «https://ozon.ru»). Чтобы не учить модель двум написаниям
# одного ответа, известные имена приводятся к нашему, а неизвестные принимаются как есть.
APP_ALIAS = {}
for _canon, _names in list(APPS) + list(FOLDERS):
    for _n in [_canon] + _names:
        APP_ALIAS[norm_app(_n)] = _canon
PROC_ALIAS = {}
for _exe, _names, _gen in PROCS:
    for _n in [_exe] + _names:
        PROC_ALIAS[norm_proc(_n)] = _exe


def site_key(url):
    """Сайт без схемы, www и хвостового слэша: «https://www.Ozon.ru/» -> «ozon.ru»."""
    rest = url.split("://", 1)[-1]
    path = rest.split("/", 1)[1].rstrip("/") if "/" in rest else ""
    return host_of(url) + ("/" + path if path else "")


SITE_CANON = {site_key(url): url for url, _ in SITES}
KNOWN_HOSTS = {host_of(url) for url, _ in SITES}
# После скриншота просят сделать с ним что-то ещё — см. train_target().
SECOND_ACTION = re.compile(r"\b(сохрани|сохранить|отправь|скинь|перешли|вставь|закинь|save|send|paste)\b")


def canon(cmd):
    """Команда Gemini -> та же команда в наших обозначениях (dict) или None, если она не годится."""
    t = cmd.get("type")
    if t == "unknown":
        return {"type": t}
    if t in ("screenshot", "lock_screen", "list_processes"):
        return {"type": t}
    if t == "agent_task":
        return {"type": t, "scope": cmd["scope"]} if cmd.get("scope") in ("browser", "screen") else None
    if t == "set_volume":
        lvl = cmd.get("level")
        return {"type": t, "level": lvl} if isinstance(lvl, int) and 0 <= lvl <= 100 else None
    if t == "open_url":
        url = (cmd.get("url") or "").strip()
        host = host_of(url)
        if not re.fullmatch(r"[a-z0-9.\-]+\.[a-z]{2,}", host or "") or " " in url:
            return None
        key = site_key(url)
        return {"type": t, "url": SITE_CANON.get(key, "https://" + key)}
    if t == "open_app":
        app = norm_app(cmd.get("appName"))
        if not app or len(app) > 40:
            return None
        return {"type": t, "appName": APP_ALIAS.get(app, app)}
    if t == "kill_process":
        proc = norm_proc(cmd.get("processName"))
        proc = PROC_ALIAS.get(proc, proc)
        return {"type": t, "processName": proc} if re.fullmatch(r"[a-z0-9_.\-]+\.exe", proc) else None
    return None


def local_command(phrase, raw):
    """Ответ нашей модели в том же виде, что canon() (без goal/maxSteps)."""
    cmd = to_command(phrase, raw)
    if cmd is None:
        return {"type": "unknown"}
    return {k: v for k, v in cmd.items() if k not in ("goal", "maxSteps")}


def same(a, b):
    """Одна и та же команда? Громкость — с допуском 10, адрес — по сайту, имена — без регистра и .exe."""
    if a["type"] != b["type"]:
        return False
    t = a["type"]
    if t == "set_volume":
        return abs(a["level"] - b["level"]) <= 10
    if t == "open_url":
        return host_of(a["url"]) == host_of(b["url"])
    if t == "open_app":
        return APP_ALIAS.get(norm_app(a["appName"]), norm_app(a["appName"])) == \
            APP_ALIAS.get(norm_app(b["appName"]), norm_app(b["appName"]))
    if t == "kill_process":
        return norm_proc(a["processName"]) == norm_proc(b["processName"])
    if t == "agent_task":
        return a["scope"] == b["scope"]
    return True


def train_target(phrase, g, loc):
    """
    Чему учить на этой фразе: (ответ модели, категория) или (None, причина отказа).

    Там, где ответ определяется одним типом (скриншот, блокировка, процессы, задача
    агенту, «не понял»), верим Gemini. Там, где есть параметр, осторожнее:
      * наша модель согласна — берём НАШЕ написание (оно то же, что в шаблонах);
      * не согласна — берём ответ Gemini, только если параметр из него «читается»:
        новый сайт или программа, которых нет в словарях, либо число прямо во фразе.
    """
    t = g["type"]
    low = phrase.lower()
    # Два места, где разметка Gemini расходится с правилами, по которым работает система.
    # Такие фразы не переучиваем ни в одну сторону — просто не берём в обучение.
    if t == "screenshot" and SECOND_ACTION.search(low):
        # «сделай скриншот и сохрани в документы»: команда screenshot только присылает снимок
        # на телефон, сохранить его куда-то может лишь агент. У нас это задача агенту.
        return None, "скриншот с продолжением (сохрани/отправь) — у нас это задача агенту"
    if t == "open_app" and norm_app(g["appName"]) not in APP_ALIAS and re.search(r"папк|folder|диск", low):
        # Произвольную папку («папка Учёба 2024») команда open_app открыть не умеет, а сам
        # Gemini размечает такие фразы то так, то как задачу агенту.
        return None, "произвольная папка — open_app таких не открывает"
    if t in ("unknown", "screenshot", "lock_screen", "list_processes"):
        return target(t), t
    if t == "agent_task":
        return target(t, scope=g["scope"]), "agent_" + g["scope"]
    if same(g, loc):
        params = {k: v for k, v in loc.items() if k != "type"}
        return target(t, **params), t
    if t == "set_volume":
        # Число названо прямо, либо это один из условных уровней из промпта Gemini
        # («тише» 30, «громче» 80, выключить 0, максимум 100, вернуть звук 50).
        if g["level"] in (0, 30, 50, 80, 100) or re.search(rf"(?<!\d){g['level']}(?!\d)", phrase):
            return target(t, level=g["level"]), t
        return None, "громкость: уровень не условный и во фразе не назван"
    if t == "open_url":
        if host_of(g["url"]) not in KNOWN_HOSTS or loc["type"] != "open_url":
            return target(t, url=g["url"]), t
        return None, "сайт из словаря, но адреса разные"
    if t == "open_app":
        return target(t, appName=g["appName"]), t
    if t == "kill_process":
        return target(t, processName=g["processName"]), t
    return None, "неизвестный тип"


def exam_case(i, phrase, g):
    """Строка экзамена Б в формате intent_test.jsonl (её понимает intent_eval.grade)."""
    case = {"id": f"b-{i}", "source": "gemini", "phrase": phrase, "type": g["type"]}
    t = g["type"]
    if t == "set_volume":
        case["level"] = [max(0, g["level"] - 10), min(100, g["level"] + 10)]
    elif t == "open_url":
        case["hosts"] = [host_of(g["url"])]
    elif t == "open_app":
        case["apps"] = sorted({g["appName"]} | {n for n, c in APP_ALIAS.items() if c == g["appName"]})
    elif t == "kill_process":
        case["procs"] = [g["processName"]]
    elif t == "agent_task":
        case["scope"] = g["scope"]
    return case


def step_build(cache, model, phrases):
    answers = cache["local"].get(model, {})
    exam = {norm(c["phrase"]) for c in read_jsonl(os.path.join(RELAY_EVAL, "intent_test.jsonl"))}
    train, test, report = [], [], []
    stat = {"всего": 0, "без разметки": 0, "разметка не годится": 0, "согласны": 0}
    for p in phrases:
        key = norm(p)
        if key in exam or key not in cache["label"] or key not in answers:
            stat["без разметки"] += key not in cache["label"]
            continue
        g = canon(cache["label"][key])
        if g is None:
            stat["разметка не годится"] += 1
            continue
        loc = local_command(p, answers[key][0])
        agree = same(g, loc)
        stat["всего"] += 1
        stat["согласны"] += agree
        held = int(hashlib.md5(key.encode("utf-8")).hexdigest(), 16) % 100 < HOLDOUT_PERCENT
        if not agree:
            report.append((g["type"], loc["type"], p, g, answers[key], "экзамен Б" if held else "обучение"))
        if held:
            test.append(exam_case(len(test) + 1, p, g))
            continue
        tgt, cat = train_target(p, g, loc)
        if tgt is None:
            report.append((g["type"], loc["type"], p, g, answers[key], "ВЫБРОШЕНО: " + cat))
        else:
            train.append({"phrase": p, "target": tgt, "cat": cat, "src": "distill"})

    write_jsonl(os.path.join(DATA, "distill.jsonl"), train)
    write_jsonl(os.path.join(RELAY_EVAL, "intent_test_b.jsonl"), test)
    n = max(1, stat["всего"])
    lines = [f"# Дистилляция: наша модель {model} и Gemini на живых фразах", "",
             f"Фраз с разметкой Gemini: {stat['всего']} (ещё без разметки: {stat['без разметки']}, "
             f"разметка не годится: {stat['разметка не годится']}).",
             f"Наша модель согласна с Gemini: {stat['согласны']} из {stat['всего']} ({100 * stat['согласны'] / n:.1f}%).",
             f"В обучение: {len(train)}, в экзамен Б: {len(test)}.", "",
             "## Расхождения (Gemini -> наша модель)", ""]
    pairs = {}
    for g_type, l_type, *_ in report:
        pairs[(g_type, l_type)] = pairs.get((g_type, l_type), 0) + 1
    for (g_type, l_type), k in sorted(pairs.items(), key=lambda x: -x[1]):
        lines.append(f"- {g_type} -> {l_type}: {k}")
    lines.append("")
    for g_type, l_type, p, g, (raw, conf), where in sorted(report, key=lambda x: (x[0], x[1])):
        lines.append(f"- [{where}] «{p}» — Gemini: `{json.dumps(g, ensure_ascii=False)}`, "
                     f"наша ({conf:.2f}): `{raw}`")
    path = os.path.join(HERE, "runs", "distill-report.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:6 + len(pairs)]))
    print("отчёт:", path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="faust-intent", help="наша модель в Ollama, с которой сравниваем")
    ap.add_argument("--max-requests", type=int, default=80, help="потолок платных запросов к Gemini за запуск")
    ap.add_argument("--per-request", type=int, default=50, help="сколько фраз просить за один запрос")
    args = ap.parse_args()

    cache, budget = load_cache(), Budget(args.max_requests)
    step_pool(cache, budget, args.per_request)
    phrases = pool_phrases(cache)
    print(f"фраз в пуле: {len(phrases)} (запросов к Gemini: {budget.used})", flush=True)
    step_label(cache, budget, phrases)
    print(f"размечено: {sum(norm(p) in cache['label'] for p in phrases)} (запросов к Gemini всего: {budget.used})",
          flush=True)
    step_local(cache, args.model, [p for p in phrases if norm(p) in cache["label"]])
    step_build(cache, args.model, phrases)
    print(f"платных запросов к Gemini за этот запуск: {budget.used}")


if __name__ == "__main__":
    main()
