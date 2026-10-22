"""
Замер точности разбора фраз на тестовом наборе (intent_test.jsonl).

Каждая фраза отправляется в релей (POST /api/admin/intent/parse) с нужным
режимом, ответ сравнивается с эталоном. Команды никуда не исполняются.

Режимы:
  stub    — только регулярные выражения
  gemini  — только модель Gemini, без запасного пути (видны её собственные ошибки)
  system  — вся система так, как её сейчас видит телефон

Две метрики:
  точность типа    — правильно ли понято, ЧТО делать (громкость, сайт, задача агенту...)
  точность команды — тип И параметры: тот уровень громкости, тот сайт, тот процесс

Запуск:
  python intent_eval.py --engine gemini
  python intent_eval.py --engine stub --limit 20
Итог: eval/results/<engine>-<время>.jsonl (по фразам) и .md (сводка и все ошибки).
"""
import argparse
import json
import os
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------------------
# Вызов релея
# ---------------------------------------------------------------------------
def call_relay(relay, token, engine, phrase):
    body = json.dumps({"phrase": phrase, "engine": engine}).encode("utf-8")
    req = urllib.request.Request(relay.rstrip("/") + "/api/admin/intent/parse", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "X-Admin-Token": token})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_with_retry(relay, token, engine, phrase):
    """Квота и сеть — не ошибка модели: ждём и повторяем, а в итог пишем только настоящий ответ."""
    delays = [5, 15, 30, 60]
    for attempt in range(len(delays) + 1):
        try:
            r = call_relay(relay, token, engine, phrase)
        except (urllib.error.URLError, TimeoutError) as e:
            r = {"command": None, "ms": None, "error": f"relay: {e}"}
        err = r.get("error") or ""
        transient = any(s in err for s in ("429", "503", "500", "timed out", "Timeout", "relay:", "Connect"))
        if not transient or attempt == len(delays):
            return r
        time.sleep(delays[attempt])


# ---------------------------------------------------------------------------
# Сравнение с эталоном
# ---------------------------------------------------------------------------
def norm_app(s):
    s = (s or "").strip().strip('"\'«»').lower().replace("ё", "е")
    return s[:-4] if s.endswith(".exe") else s


def norm_proc(s):
    s = (s or "").strip().strip('"\'«»').lower()
    return s if s.endswith(".exe") else s + ".exe"


def host_of(url):
    u = (url or "").strip()
    if "://" not in u:
        u = "https://" + u
    h = (urllib.parse.urlparse(u).hostname or "").lower()
    for prefix in ("www.", "m."):
        if h.startswith(prefix):
            h = h[len(prefix):]
    return h


def params_ok(case, cmd):
    """Параметры команды правильного типа. Возвращает (верно?, почему нет)."""
    t = case["type"]
    if t == "set_volume":
        lo, hi = case.get("level", [0, 100])
        lvl = cmd.get("level")
        return (isinstance(lvl, int) and lo <= lvl <= hi), f"level={lvl}, ждали {lo}..{hi}"
    if t == "open_url":
        url = cmd.get("url") or ""
        h = host_of(url)
        ok = any(h == x or h.endswith("." + x) for x in case.get("hosts", []))
        ok = ok and all(c in url.lower() for c in case.get("contains", []))
        return ok, f"url={url}"
    if t == "open_app":
        return norm_app(cmd.get("appName")) in [norm_app(a) for a in case.get("apps", [])], f"appName={cmd.get('appName')}"
    if t == "kill_process":
        return norm_proc(cmd.get("processName")) in [norm_proc(p) for p in case.get("procs", [])], \
            f"processName={cmd.get('processName')}"
    if t == "agent_task":
        goal = (cmd.get("goal") or "").lower().replace("ё", "е")
        if not goal.strip():
            return False, "пустая цель"
        scope = case.get("scope")
        if scope and cmd.get("scope") != scope:
            return False, f"scope={cmd.get('scope')}, ждали {scope}"
        for part in case.get("goal_has", []):
            if part.lower().replace("ё", "е") not in goal:
                return False, f"в цели нет «{part}»"
        return True, ""
    return True, ""  # screenshot, lock_screen, list_processes — параметров нет


def grade(case, cmd):
    got = (cmd or {}).get("type") or "unknown"
    allowed = [case["type"]] + case.get("accept", [])
    type_ok = got in allowed
    if not type_ok:
        return got, False, False, f"тип {got}, ждали {case['type']}"
    if got != case["type"]:           # спорная фраза, допустимый другой тип
        return got, True, True, ""
    ok, why = params_ok(case, cmd or {})
    return got, True, ok, "" if ok else why


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", default="system", choices=["stub", "gemini", "local", "system"])
    ap.add_argument("--set", default="intent_test.jsonl", help="файл с тестовым набором рядом со скриптом")
    ap.add_argument("--label", default="", help="пометка в имени отчёта, например v2prompt")
    ap.add_argument("--relay", default=os.environ.get("JARVIS_RELAY_HTTP", "http://localhost:8080"))
    ap.add_argument("--token", default=os.environ.get("JARVIS_ADMIN_TOKEN", "dev-admin-change-me"))
    ap.add_argument("--limit", type=int, default=0, help="только первые N фраз (проверка скрипта)")
    ap.add_argument("--pause", type=float, default=0.0, help="пауза между фразами, с (бережём квоту)")
    ap.add_argument("--resume", default="", help="прежний отчёт (.jsonl в results): фразы, на которые в нём есть "
                                                 "ответ, заново не спрашиваем — доделываем оборванный замер")
    args = ap.parse_args()

    # Оборванный замер (кончилась квота): готовые ответы берём из прежнего отчёта,
    # платные запросы уходят только на фразы, где тогда был сбой.
    done = {}
    if args.resume:
        with open(os.path.join(HERE, "results", os.path.basename(args.resume)), encoding="utf-8") as f:
            done = {r["id"]: r for r in map(json.loads, f) if not r.get("error")}

    with open(os.path.join(HERE, args.set), encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]
    if args.limit:
        cases = cases[:args.limit]

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    name = args.engine + ("-" + args.label if args.label else "")
    base = os.path.join(HERE, "results", f"{name}-{stamp}")

    rows = []
    with open(base + ".jsonl", "w", encoding="utf-8") as out:
        for i, case in enumerate(cases, 1):
            old = done.get(case["id"])
            if old and old["phrase"] == case["phrase"]:
                r = {"command": old["command"], "ms": old["ms"], "error": None, "source": old.get("answered_by"),
                     "confidence": old.get("confidence"), "denied": old.get("denied")}
            else:
                r = parse_with_retry(args.relay, args.token, args.engine, case["phrase"])
            cmd = r.get("command")
            got, type_ok, full_ok, why = grade(case, cmd)
            row = dict(id=case["id"], phrase=case["phrase"], expected=case["type"], got=got,
                       command=cmd, type_ok=type_ok, full_ok=full_ok, why=why,
                       ms=r.get("ms"), error=r.get("error"), tags=case.get("tags", []), source=case["source"],
                       # кто ответил в связке (local / gemini / memory / shortcut / stub) и насколько уверенно
                       answered_by=r.get("source"), confidence=r.get("confidence"), denied=r.get("denied"),
                       local_raw=r.get("localRaw"), local_ms=r.get("localMs"), gemini_ms=r.get("geminiMs"))
            rows.append(row)
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            mark = "OK " if full_ok else ("ТИП" if type_ok else "ОШ ")
            who = f" [{row['answered_by']}]" if row["answered_by"] else ""
            print(f"[{i:3d}/{len(cases)}] {mark} {case['phrase'][:60]:60s} -> {got}{who} {why}", flush=True)
            if args.pause:
                time.sleep(args.pause)

    write_summary(base + ".md", name, rows)
    print("\n" + open(base + ".md", encoding="utf-8").read().split("## Ошибки")[0])


def pct(a, b):
    return f"{100.0 * a / b:.1f}%" if b else "—"


def write_summary(path, engine, rows):
    n = len(rows)
    errors = [r for r in rows if r["error"]]
    ms = [r["ms"] for r in rows if isinstance(r["ms"], int)]
    lines = [f"# Разбор фраз: {engine}, {datetime.now():%Y-%m-%d %H:%M}", "",
             f"Фраз: {n}. Сбоев связи/квоты (после повторов): {len(errors)}.", "",
             "| Метрика | Значение |", "|---|---|",
             f"| Точность типа | {pct(sum(r['type_ok'] for r in rows), n)} |",
             f"| Точность команды (тип + параметры) | {pct(sum(r['full_ok'] for r in rows), n)} |"]
    if ms:
        q = sorted(ms)
        lines += [f"| Задержка, медиана | {statistics.median(q):.0f} мс |",
                  f"| Задержка, 90-й перцентиль | {q[int(0.9 * (len(q) - 1))]:.0f} мс |"]
    lines += ["", "## По типам команд", "", "| Тип | Фраз | Тип верно | Команда верно |", "|---|---|---|---|"]
    for t in sorted({r["expected"] for r in rows}):
        sub = [r for r in rows if r["expected"] == t]
        lines.append(f"| {t} | {len(sub)} | {pct(sum(r['type_ok'] for r in sub), len(sub))} "
                     f"| {pct(sum(r['full_ok'] for r in sub), len(sub))} |")
    # Связка: кто из разборщиков ответил и как часто пришлось идти в Gemini.
    who = [r.get("answered_by") for r in rows if r.get("answered_by")]
    if who:
        lines += ["", "## Кто ответил (связка)", "", "| Разборщик | Фраз | Доля | Команда верно | Медиана, мс |",
                  "|---|---|---|---|---|"]
        for w in sorted(set(who)):
            sub = [r for r in rows if r.get("answered_by") == w]
            sub_ms = [r["ms"] for r in sub if isinstance(r["ms"], int)]
            lines.append(f"| {w} | {len(sub)} | {pct(len(sub), n)} | {pct(sum(r['full_ok'] for r in sub), len(sub))} "
                         f"| {statistics.median(sub_ms):.0f} |" if sub_ms else f"| {w} | {len(sub)} | {pct(len(sub), n)} | — | — |")
        denied = [r for r in rows if r.get("denied")]
        lines += ["", f"Запрещено белым списком (опасные цели): {len(denied)}."]
    tags = sorted({t for r in rows for t in r["tags"]})
    if tags:
        lines += ["", "## По особенностям фраз", "", "| Метка | Фраз | Команда верно |", "|---|---|---|"]
        for tag in tags:
            sub = [r for r in rows if tag in r["tags"]]
            lines.append(f"| {tag} | {len(sub)} | {pct(sum(r['full_ok'] for r in sub), len(sub))} |")
    lines += ["", "## Ошибки", ""]
    for r in rows:
        if not r["full_ok"]:
            lines.append(f"- `{r['id']}` «{r['phrase']}» — {r['why'] or 'тип ' + r['got']}"
                         + (f" (сбой: {r['error']})" if r["error"] else ""))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
