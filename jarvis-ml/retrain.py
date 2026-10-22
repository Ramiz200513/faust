"""
Самообучение: дообучить свою модель на том, чего она не поняла в работе.

Откуда берутся уроки. Когда наша модель не справилась с фразой, релей спросил
Gemini; если Gemini назвал команду и агент её УСПЕШНО выполнил, релей сохранил
пару «фраза -> команда» как подтверждённый урок (IntentLessons.java). Этот
скрипт забирает уроки и учит на них следующую версию модели.

Шаги:
  1. Забрать уроки из релея (GET /api/admin/intent/lessons) -> data/lessons.jsonl
  2. Пересобрать обучающий набор: прежние примеры + уроки (каждый урок — трижды)
  3. Обучить новую версию (train.py)
  4. ЭКЗАМЕН: та же проверка на 250 фразах, что и всегда (eval_local.py)
  5. Новая версия включается (export_ollama.py), ТОЛЬКО если сдала экзамен не хуже
     текущей. Иначе остаётся прежняя — так плохие уроки не могут испортить модель.

Запуск:
  python retrain.py                    обычный цикл
  python retrain.py --min-lessons 20   не начинать, пока новых уроков меньше 20
  python retrain.py --dry-run          только показать уроки
"""
import argparse
import glob
import json
import os
import subprocess
import sys
import urllib.request

from common import ADMIN_TOKEN, DATA, HERE, RELAY, RELAY_EVAL, norm, read_jsonl, target, write_jsonl

STATE = os.path.join(HERE, "runs", "current.json")   # какая версия сейчас в работе и как она сдала экзамен


def lesson_target(cmd):
    """Команда протокола (как её назвал Gemini) -> ответ, которому учим модель. None — такому не учим."""
    t = cmd.get("type")
    if t == "set_volume" and isinstance(cmd.get("level"), int):
        return target(t, level=cmd["level"]), t
    if t == "open_url" and cmd.get("url"):
        return target(t, url=cmd["url"]), t
    if t == "open_app" and cmd.get("appName"):
        return target(t, appName=cmd["appName"]), t
    if t == "kill_process" and cmd.get("processName"):
        return target(t, processName=cmd["processName"]), t
    if t in ("screenshot", "lock_screen", "list_processes"):
        return target(t), t
    if t == "agent_task" and cmd.get("scope") in ("browser", "screen"):
        return target(t, scope=cmd["scope"]), "agent_" + cmd["scope"]
    return None, None


def fetch_lessons():
    req = urllib.request.Request(RELAY.rstrip("/") + "/api/admin/intent/lessons",
                                 headers={"X-Admin-Token": ADMIN_TOKEN})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run(*cmd):
    print(">", " ".join(cmd), flush=True)
    subprocess.run([sys.executable, *cmd], cwd=HERE, check=True)


def exam_score(name):
    """Точность команды на экзамене из самого свежего отчёта eval_local.py для этой версии."""
    files = sorted(glob.glob(os.path.join(RELAY_EVAL, "results", f"local-{name}-test-*.jsonl")))
    rows = read_jsonl(files[-1])
    return sum(r["full_ok"] for r in rows) / len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="")
    ap.add_argument("--min-lessons", type=int, default=1)
    ap.add_argument("--quantize", default="")
    ap.add_argument("--cool-ms", type=int, default=300, help="пауза между шагами обучения, мс (см. train.py)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    state = json.load(open(STATE, encoding="utf-8")) if os.path.exists(STATE) else {}
    exam = {norm(c["phrase"]) for c in read_jsonl(os.path.join(RELAY_EVAL, "intent_test.jsonl"))}

    # --- 1. уроки ---
    path = os.path.join(DATA, "lessons.jsonl")
    known = {norm(r["phrase"]): r for r in (read_jsonl(path) if os.path.exists(path) else [])}
    fresh = 0
    for lesson in fetch_lessons():
        tgt, cat = lesson_target(lesson.get("command") or {})
        key = norm(lesson["phrase"])
        # Фраза из экзамена уроком стать не может — иначе экзамен перестал бы быть экзаменом.
        if tgt is None or key in exam or key in known:
            continue
        known[key] = {"phrase": lesson["phrase"], "target": tgt, "cat": cat, "localAnswer": lesson.get("localAnswer")}
        fresh += 1
    print(f"уроков всего: {len(known)}, новых: {fresh}")
    for r in list(known.values())[-fresh:] if fresh else []:
        print(f"   «{r['phrase']}» -> {r['target']}   (модель отвечала: {r.get('localAnswer')})")
    if args.dry_run or fresh < args.min_lessons:
        print("дообучение не запускаю" + ("" if args.dry_run else f": новых уроков меньше {args.min_lessons}"))
        return
    write_jsonl(path, list(known.values()))

    # --- 2–4. набор, обучение, экзамен ---
    base = args.base or state.get("base", "Qwen/Qwen2.5-0.5B-Instruct")
    version = state.get("version", 1) + 1
    name = f"{state.get('family', 'faust')}-v{version}"
    run("build_trainset.py")
    run("train.py", "--base", base, "--name", name, "--cool-ms", str(args.cool_ms))
    run("eval_local.py", "--name", name, "--set", "test")
    new_score, old_score = exam_score(name), state.get("exam", 0.0)

    # --- 5. ворота: хуже прежней — не пускаем ---
    print(f"экзамен: новая версия {100 * new_score:.1f}%, текущая {100 * old_score:.1f}%")
    if new_score + 1e-9 < old_score:
        print("новая версия хуже — остаётся прежняя (уроки сохранены, попробуем в следующий раз)")
        return
    export = ["export_ollama.py", "--name", name, "--tag", state.get("tag", "faust-intent")]
    if args.quantize or state.get("quantize"):
        export += ["--quantize", args.quantize or state["quantize"]]
    run(*export)
    state.update(version=version, name=name, base=base, exam=new_score, lessons=len(known))
    state.setdefault("history", []).append({"name": name, "exam": new_score, "lessons": len(known)})
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    print(f"включена версия {name}")


if __name__ == "__main__":
    main()
