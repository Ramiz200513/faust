"""
Общее для обучения, проверки и выгрузки своей модели разбора фраз «Фауста».

Здесь то, что обязано совпадать во всех местах, иначе модель обучится одному,
а спрашивать её будут о другом:
  * SYSTEM      — системная подсказка (она же зашита в Modelfile для Ollama);
  * target()    — как выглядит правильный ответ модели на команду;
  * to_command() — как из ответа модели получается команда протокола
                   (то же самое делает LocalIntentParser.java в релее).

Формат ответа модели — тот же JSON, что в протоколе, но без лишнего:
для задачи агенту модель пишет только {"type":"agent_task","scope":"screen"},
а цель (саму фразу) и лимит шагов подставляет код. Модели не нужно
переписывать длинную фразу слово в слово: это быстрее и убирает целый класс
ошибок («потеряла слово при переписывании»).
"""
import json
import os
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
RELAY_EVAL = os.path.normpath(os.path.join(HERE, "..", "jarvis-relay", "eval"))

# Короткая подсказка: модель дообучена, формат ей объяснять не нужно, а каждое
# лишнее слово здесь — это время на КАЖДОЙ фразе.
SYSTEM = "Ты — разборщик команд ассистента «Фауст». Преврати фразу пользователя в одну команду JSON."

# 127.0.0.1, а не localhost: Windows сначала пробует localhost по IPv6 и теряет на этом 2 секунды.
RELAY = os.environ.get("JARVIS_RELAY_HTTP", "http://127.0.0.1:8080")
ADMIN_TOKEN = os.environ.get("JARVIS_ADMIN_TOKEN", "dev-admin-change-me")


def dumps(obj):
    """Компактный JSON без пробелов — так короче ответ модели."""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def target(type_, **params):
    """Правильный ответ модели: {"type":"set_volume","level":30}."""
    return dumps({"type": type_, **params})


def to_command(phrase, raw):
    """
    Ответ модели -> команда протокола (dict) или None («не понял» / мусор).
    Зеркало LocalIntentParser.interpret в релее — менять только вместе.
    """
    try:
        n = json.loads(raw)
    except Exception:
        return None
    if not isinstance(n, dict):
        return None
    t = n.get("type")
    if t == "set_volume":
        lvl = n.get("level")
        return {"type": t, "level": lvl} if isinstance(lvl, int) and 0 <= lvl <= 100 else None
    if t == "open_url":
        url = (n.get("url") or "").strip()
        if not url or " " in url:
            return None
        if not url.lower().startswith(("http://", "https://")):
            url = "https://" + url
        return {"type": t, "url": url}
    if t == "open_app":
        app = (n.get("appName") or "").strip()
        return {"type": t, "appName": app} if app else None
    if t == "kill_process":
        proc = (n.get("processName") or "").strip()
        return {"type": t, "processName": proc} if proc and " " not in proc else None
    if t in ("screenshot", "lock_screen", "list_processes"):
        return {"type": t}
    if t == "agent_task":
        scope = n.get("scope")
        if scope not in ("browser", "screen"):
            return None
        return {"type": t, "goal": phrase, "scope": scope, "maxSteps": 15 if scope == "browser" else 20}
    return None


def norm(phrase):
    """Вид фразы для сравнения: нижний регистр, ё→е, без знаков и лишних пробелов."""
    out = []
    for ch in phrase.lower().replace("ё", "е"):
        out.append(ch if ch.isalnum() or ch == "." else " ")
    return " ".join("".join(out).split())


def relay_post(path, body, timeout=180):
    """POST на служебный эндпоинт релея (админский секрет)."""
    req = urllib.request.Request(RELAY.rstrip("/") + path, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "X-Admin-Token": ADMIN_TOKEN})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def write_jsonl(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
