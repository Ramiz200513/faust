"""
Проверка обученной модели на экзамене — напрямую, без релея и Ollama.

Нужна сразу после обучения, чтобы понять, стоит ли модель выгружать: тот же
экзамен (jarvis-relay/eval/intent_test.jsonl) и та же проверка ответов, что
у Gemini и регулярок (функция grade из intent_eval.py).

Запуск:
  python eval_local.py --name qwen05-v1
  python eval_local.py --name qwen05-v1 --set dev     проверочный набор «учителя» — для выбора порога уверенности
Итог: jarvis-relay/eval/results/local-<name>-<время>.md и .jsonl
"""
import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

from common import DATA, HERE, RELAY_EVAL, read_jsonl, to_command
from train import generate

sys.path.insert(0, RELAY_EVAL)
from intent_eval import grade, write_summary  # noqa: E402  — та же проверка, что у Gemini


def load(name, device):
    run = os.path.join(HERE, "runs", name)
    base = json.load(open(os.path.join(run, "train_log.json"), encoding="utf-8"))["base"]
    tok = AutoTokenizer.from_pretrained(os.path.join(run, "adapter"))
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.bfloat16 if device == "cuda" else torch.float32)
    # Заплатки LoRA сразу вливаем в модель: так считается быстрее, и проверяется ровно
    # то, что потом уйдёт в Ollama.
    model = PeftModel.from_pretrained(model, os.path.join(run, "adapter")).merge_and_unload()
    model.to(device).eval()
    return model, tok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--set", default="test", choices=["test", "test_b", "dev"])
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model, tok = load(args.name, device)

    if args.set == "dev":
        # Проверочный набор учителя: эталон — строка-ответ; превращаем в тот же вид, что у экзамена.
        cases = []
        for i, r in enumerate(read_jsonl(os.path.join(DATA, "dev.jsonl"))):
            cases.append({"id": f"dev-{i}", "phrase": r["phrase"], "gold": to_command(r["phrase"], r["target"]),
                          "source": "dev", "cat": r["cat"]})
    else:
        fname = "intent_test.jsonl" if args.set == "test" else "intent_test_b.jsonl"
        cases = read_jsonl(os.path.join(RELAY_EVAL, fname))

    generate(model, tok, "прогрев")   # первый вызов всегда медленный — не в счёт
    rows = []
    for i, case in enumerate(cases, 1):
        t0 = time.perf_counter()
        raw, conf = generate(model, tok, case["phrase"])
        ms = int((time.perf_counter() - t0) * 1000)
        cmd = to_command(case["phrase"], raw)
        if args.set == "dev":
            ok = cmd == case["gold"]
            got = (cmd or {}).get("type") or "unknown"
            expected = (case["gold"] or {}).get("type") or "unknown"
            type_ok, full_ok, why = got == expected, ok, "" if ok else f"ответ {raw}"
        else:
            expected = case["type"]
            got, type_ok, full_ok, why = grade(case, cmd)
        rows.append(dict(id=case["id"], phrase=case["phrase"], expected=expected, got=got, command=cmd, raw=raw,
                         confidence=round(conf, 4), type_ok=type_ok, full_ok=full_ok, why=why, ms=ms, error=None,
                         tags=case.get("tags", []), source=case.get("source", "")))
        mark = "OK " if full_ok else ("ТИП" if type_ok else "ОШ ")
        print(f"[{i:3d}/{len(cases)}] {mark} {conf:.2f} {case['phrase'][:55]:55s} -> {raw[:60]} {why}", flush=True)

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    os.makedirs(os.path.join(RELAY_EVAL, "results"), exist_ok=True)
    base = os.path.join(RELAY_EVAL, "results", f"local-{args.name}-{args.set}-{stamp}")
    with open(base + ".jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    write_summary(base + ".md", f"своя модель {args.name} ({args.set}, напрямую, {device})", rows)

    n = len(rows)
    right = [r["confidence"] for r in rows if r["full_ok"]]
    wrong = [r["confidence"] for r in rows if not r["full_ok"]]
    print(f"\nточность команды: {100 * len(right) / n:.1f}% ({len(right)} из {n}), "
          f"медиана задержки {statistics.median(r['ms'] for r in rows):.0f} мс")
    # Что даст порог уверенности: сколько фраз уйдёт в Gemini и сколько ошибок при этом останется у нас.
    print("порог | оставляем себе | из них верно | уходит в Gemini | ошибок остаётся")
    for th in (0.0, 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98):
        kept_right = sum(c >= th for c in right)
        kept_wrong = sum(c >= th for c in wrong)
        kept = kept_right + kept_wrong
        print(f" {th:4.2f} | {kept:4d} ({100 * kept / n:5.1f}%) | {100 * kept_right / max(1, kept):5.1f}% "
              f"| {n - kept:4d} ({100 * (n - kept) / n:5.1f}%) | {kept_wrong}")
    print("отчёт:", base + ".md")


if __name__ == "__main__":
    main()
