"""
Экзамен модели, уже установленной в Ollama, — напрямую, без релея.

Чем отличается от eval_local.py: там проверяется модель «в мастерской» (PyTorch),
здесь — ровно тот файл, который будет отвечать телефону (GGUF в Ollama), тем же
запросом, что шлёт релей (LocalIntentParser.java). Отсюда берётся и настоящая
скорость ответа.

Ответ модели превращается в команду той же функцией, что при обучении
(common.to_command), и проверяется той же функцией, что у Gemini (intent_eval.grade).

Запуск (Python любой, сторонних библиотек не нужно):
  python eval_ollama.py                      модель faust-intent, экзамен
  python eval_ollama.py --model faust-intent --set dev
"""
import argparse
import http.client
import json
import math
import os
import statistics
import sys
import time
from datetime import datetime

from common import DATA, RELAY_EVAL, read_jsonl, to_command

sys.path.insert(0, RELAY_EVAL)
from intent_eval import grade, write_summary  # noqa: E402


def ask(conn, model, phrase):
    body = json.dumps({"model": model, "stream": False, "logprobs": True, "keep_alive": "60m",
                       "messages": [{"role": "user", "content": phrase}],
                       "options": {"temperature": 0, "num_predict": 96}})
    t0 = time.perf_counter()
    conn.request("POST", "/api/chat", body=body.encode("utf-8"), headers={"Content-Type": "application/json"})
    out = json.loads(conn.getresponse().read().decode("utf-8"))
    ms = int((time.perf_counter() - t0) * 1000)
    raw = out.get("message", {}).get("content", "").strip()
    # Уверенность — произведение вероятностей всех слов ответа (как в релее).
    conf = math.exp(sum(t.get("logprob", 0.0) for t in out.get("logprobs") or [])) if out.get("logprobs") else 0.0
    return raw, conf, ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="faust-intent")
    ap.add_argument("--set", default="test", choices=["test", "dev", "b"],
                    help="test — экзамен; b — экзамен Б (живые фразы с разметкой Gemini, см. distill.py); dev — отложенные")
    args = ap.parse_args()

    if args.set == "dev":
        cases = [{"id": f"dev-{i}", "phrase": r["phrase"], "gold": to_command(r["phrase"], r["target"]), "source": "dev"}
                 for i, r in enumerate(read_jsonl(os.path.join(DATA, "dev.jsonl")))]
    else:
        cases = read_jsonl(os.path.join(RELAY_EVAL, "intent_test_b.jsonl" if args.set == "b" else "intent_test.jsonl"))

    # 127.0.0.1, а не localhost (IPv6 на Windows стоит 2 секунды на соединение); одно
    # соединение на весь прогон — как у релея, который держит его открытым.
    conn = http.client.HTTPConnection("127.0.0.1", 11434, timeout=120)
    ask(conn, args.model, "прогрев")   # первая фраза грузит модель в видеопамять — не в счёт

    rows = []
    for i, case in enumerate(cases, 1):
        raw, conf, ms = ask(conn, args.model, case["phrase"])
        cmd = to_command(case["phrase"], raw)
        if args.set == "dev":
            full_ok = cmd == case["gold"]
            got = (cmd or {}).get("type") or "unknown"
            expected = (case["gold"] or {}).get("type") or "unknown"
            type_ok, why = got == expected, "" if full_ok else f"ответ {raw}"
        else:
            expected = case["type"]
            got, type_ok, full_ok, why = grade(case, cmd)
        rows.append(dict(id=case["id"], phrase=case["phrase"], expected=expected, got=got, command=cmd, raw=raw,
                         confidence=round(conf, 4), type_ok=type_ok, full_ok=full_ok, why=why, ms=ms, error=None,
                         tags=case.get("tags", []), source=case.get("source", "")))
        mark = "OK " if full_ok else ("ТИП" if type_ok else "ОШ ")
        print(f"[{i:3d}/{len(cases)}] {mark} {conf:.2f} {ms:4d}мс {case['phrase'][:50]:50s} -> {raw[:55]} {why}", flush=True)

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    os.makedirs(os.path.join(RELAY_EVAL, "results"), exist_ok=True)
    base = os.path.join(RELAY_EVAL, "results", f"ollama-{args.model}-{args.set}-{stamp}")
    with open(base + ".jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    write_summary(base + ".md", f"своя модель {args.model} в Ollama ({args.set})", rows)

    n = len(rows)
    right = [r["confidence"] for r in rows if r["full_ok"]]
    wrong = [r["confidence"] for r in rows if not r["full_ok"]]
    lat = sorted(r["ms"] for r in rows)
    print(f"\nточность команды: {100 * len(right) / n:.1f}% ({len(right)} из {n}); "
          f"задержка: медиана {statistics.median(lat):.0f} мс, 90% фраз быстрее {lat[int(0.9 * (n - 1))]} мс")
    print("порог | оставляем себе | из них верно | уходит в Gemini | ошибок остаётся")
    for th in (0.0, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98):
        kr, kw = sum(c >= th for c in right), sum(c >= th for c in wrong)
        print(f" {th:4.2f} | {kr + kw:4d} ({100 * (kr + kw) / n:5.1f}%) | {100 * kr / max(1, kr + kw):5.1f}% "
              f"| {n - kr - kw:4d} ({100 * (n - kr - kw) / n:5.1f}%) | {kw}")
    print("отчёт:", base + ".md")


if __name__ == "__main__":
    main()
