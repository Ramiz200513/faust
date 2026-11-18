"""
Экзамен «Фауст-мини» — те же наборы и та же проверка ответов (intent_eval.grade),
что у Qwen и Gemini, поэтому цифры сравнимы напрямую.

  python mini/eval_mini.py --name mini-v1                  все наборы: А, Б, «Новое», dev
  python mini/eval_mini.py --name mini-v1 --set test_b     один набор

Скорость меряется честно для работы в релее: одна фраза за раз, один поток
процессора, после прогрева. Отчёты — jarvis-relay/eval/results/mini-<name>-<набор>-<время>.md/.jsonl
"""
import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime

import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from common import DATA, RELAY_EVAL, read_jsonl, to_command  # noqa: E402
import faust_mini as fm  # noqa: E402

sys.path.insert(0, RELAY_EVAL)
from intent_eval import grade, write_summary  # noqa: E402

ROOT = os.path.dirname(HERE)
SETS = {"test": "intent_test.jsonl", "test_b": "intent_test_b.jsonl", "new": "intent_test_new.jsonl", "dev": None}


def cases_of(name):
    if name == "dev":
        return [{"id": f"dev-{i}", "phrase": r["phrase"], "gold": to_command(r["phrase"], r["target"]),
                 "source": "dev"} for i, r in enumerate(read_jsonl(os.path.join(DATA, "dev.jsonl")))]
    return read_jsonl(os.path.join(RELAY_EVAL, SETS[name]))


def run_set(model, vocab, set_name, run_name):
    cases = cases_of(set_name)
    rows = []
    for case in cases:
        t0 = time.perf_counter()
        raw, conf = fm.predict(model, vocab, [case["phrase"]])[0]
        ms = (time.perf_counter() - t0) * 1000
        cmd = to_command(case["phrase"], raw)
        if set_name == "dev":
            ok = cmd == case["gold"]
            got = (cmd or {}).get("type") or "unknown"
            expected = (case["gold"] or {}).get("type") or "unknown"
            type_ok, full_ok, why = got == expected, ok, "" if ok else f"ответ {raw}"
        else:
            expected = case["type"]
            got, type_ok, full_ok, why = grade(case, cmd)
        rows.append(dict(id=case["id"], phrase=case["phrase"], expected=expected, got=got, command=cmd, raw=raw,
                         confidence=round(conf, 4), type_ok=type_ok, full_ok=full_ok, why=why, ms=round(ms, 2),
                         error=None, tags=case.get("tags", []), source=case.get("source", "")))
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    os.makedirs(os.path.join(RELAY_EVAL, "results"), exist_ok=True)
    base = os.path.join(RELAY_EVAL, "results", f"mini-{run_name}-{set_name}-{stamp}")
    with open(base + ".jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    write_summary(base + ".md", f"«Фауст-мини» {run_name} ({set_name}, процессор, 1 поток)", rows)
    return rows, base


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="mini-v1")
    ap.add_argument("--set", default="all", choices=["all"] + list(SETS))
    args = ap.parse_args()

    torch.set_num_threads(1)          # как в релее: одна фраза — один поток
    model, vocab, meta = fm.load(os.path.join(ROOT, "runs", args.name, "model.pt"))
    fm.predict(model, vocab, ["прогрев"] * 3)
    print(f"«Фауст-мини» {args.name}: {fm.count_params(model) / 1e6:.2f} млн чисел, эпоха {meta.get('epoch')}")

    for set_name in (SETS if args.set == "all" else [args.set]):
        rows, base = run_set(model, vocab, set_name, args.name)
        n = len(rows)
        right = [r["confidence"] for r in rows if r["full_ok"]]
        wrong = [r["confidence"] for r in rows if not r["full_ok"]]
        print(f"\n== {set_name}: точность команды {100 * len(right) / n:.1f}% ({len(right)} из {n}), "
              f"типа {100 * sum(r['type_ok'] for r in rows) / n:.1f}%, "
              f"медиана {statistics.median(r['ms'] for r in rows):.2f} мс, отчёт {os.path.basename(base)}.md")
        print("порог | оставляем себе | из них верно | уходит дальше | ошибок остаётся")
        for th in (0.0, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98):
            kr = sum(c >= th for c in right)
            kw = sum(c >= th for c in wrong)
            kept = kr + kw
            print(f" {th:4.2f} | {kept:4d} ({100 * kept / n:5.1f}%) | {100 * kr / max(1, kept):5.1f}% "
                  f"| {n - kept:4d} ({100 * (n - kept) / n:5.1f}%) | {kw}")
        bad = [r for r in rows if not r["full_ok"]][:8]
        for r in bad:
            print(f"   ✗ {r['phrase'][:60]:60s} -> {r['raw']} ({r['confidence']:.2f}) {r['why']}")


if __name__ == "__main__":
    main()
