"""
Честное сравнение моделей разбора фраз по уже сохранённым ответам — без единого
запроса к ИИ. Отчёт: runs/stats-v4.md.

  python stats_compare.py

Что считается
-------------
1. Все ответы ПЕРЕПРОВЕРЯЮТСЯ одной и той же функцией intent_eval.grade по текущим
   экзаменам (поправки вроде b-45/b-1/b-117 учитываются для всех моделей одинаково).
2. Тест Макнемара (точный, биномиальный): значима ли разница двух моделей на ОДНИХ
   И ТЕХ ЖЕ фразах. Считаются только фразы, где модели разошлись (одна права, другая
   нет); если разница случайна, такие расхождения делятся примерно пополам.
3. Точность по видам команд с 95%-интервалом Уилсона: на 20 фразах «95%» может
   оказаться и 75%, и 99% — интервал показывает, насколько цифре можно верить.
4. Цепочка «мини → Qwen → Gemini»: порог мини-модели подбирается на dev (не на
   экзамене — иначе это подгонка), потом смотрим, сколько фраз решает каждое звено.
"""
import glob
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from common import DATA, RELAY_EVAL, read_jsonl, to_command  # noqa: E402

sys.path.insert(0, RELAY_EVAL)
from intent_eval import grade  # noqa: E402

RESULTS = os.path.join(RELAY_EVAL, "results")
SETS = {"А": ("intent_test.jsonl", "test", "test"), "Б": ("intent_test_b.jsonl", "b", "test_b"),
        "Новое": ("intent_test_new.jsonl", "new", "new"), "dev": (None, "dev", "dev")}
MODELS = {"v3": "ollama-faust-intent-v3-{s}-*.jsonl", "v4": "ollama-faust-intent-v4-{s}-*.jsonl",
          "мини": "mini-mini-v1-{s}-*.jsonl"}


def latest(pattern):
    files = sorted(glob.glob(os.path.join(RESULTS, pattern)))
    return files[-1] if files else None


def cases(set_name):
    fname = SETS[set_name][0]
    if fname is None:
        return {f"dev-{i}": {"id": f"dev-{i}", "phrase": r["phrase"], "gold": to_command(r["phrase"], r["target"]),
                             "cat": r["cat"]}
                for i, r in enumerate(read_jsonl(os.path.join(DATA, "dev.jsonl")))}
    return {c["id"]: c for c in read_jsonl(os.path.join(RELAY_EVAL, fname))}


def regrade(set_name, model):
    """id -> (верно?, уверенность, мс, класс) — по сохранённой команде, текущей проверкой."""
    s_ollama, s_mini = SETS[set_name][1], SETS[set_name][2]
    path = latest(MODELS[model].format(s=s_mini if model == "мини" else s_ollama))
    if not path:
        return None, None
    cs = cases(set_name)
    out = {}
    for r in read_jsonl(path):
        c = cs.get(r["id"])
        if c is None:
            continue
        cmd = r.get("command")
        if set_name == "dev":
            ok = cmd == c["gold"]
            cls = (c["gold"] or {}).get("type", "unknown")
            if cls == "agent_task":
                cls += "/" + c["gold"]["scope"]
        else:
            _, _, ok, _ = grade(c, cmd)
            cls = c["type"] + ("/" + c["scope"] if c.get("scope") else "")
        out[r["id"]] = (bool(ok), float(r.get("confidence") or 0), float(r.get("ms") or 0), cls)
    return out, os.path.basename(path)


def mcnemar(a, b):
    """Точный двусторонний тест: b_only — права только первая, c_only — только вторая."""
    ids = sorted(set(a) & set(b))
    b_only = sum(a[i][0] and not b[i][0] for i in ids)
    c_only = sum(b[i][0] and not a[i][0] for i in ids)
    n = b_only + c_only
    if n == 0:
        return b_only, c_only, 1.0
    k = min(b_only, c_only)
    p = sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n
    return b_only, c_only, min(1.0, 2 * p)


def wilson(k, n, z=1.96):
    if n == 0:
        return 0.0, 0.0
    p = k / n
    d = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, centre - half), min(1.0, centre + half)


def pct(x):
    return f"{100 * x:.1f}%"


def main():
    data, files = {}, []
    for s in SETS:
        for m in MODELS:
            r, f = regrade(s, m)
            if r:
                data[(s, m)] = r
                files.append(f)
    L = ["# Сравнение моделей разбора фраз (v3, v4, «Фауст-мини»)", "",
         "Пересчитано одной проверкой (`intent_eval.grade`) по сохранённым ответам:",
         "", *[f"- `{f}`" for f in files], ""]

    L += ["## 1. Точность целиком (команда и параметры)", "",
          "| Набор | v3 | v4 | мини |", "|---|---|---|---|"]
    for s in SETS:
        row = [s]
        for m in MODELS:
            r = data.get((s, m))
            if r:
                k, n = sum(v[0] for v in r.values()), len(r)
                lo, hi = wilson(k, n)
                row.append(f"{pct(k / n)} ({k}/{n}; 95%: {pct(lo)}–{pct(hi)})")
            else:
                row.append("—")
        L.append("| " + " | ".join(row) + " |")
    L += ["", "dev для v3 завышен: dev пересобран для v4, и v3 могла видеть часть этих фраз в обучении.", ""]

    L += ["## 2. Тест Макнемара: значима ли разница", "",
          "p < 0,05 — разница, скорее всего, не случайна; иначе на этих фразах модели не различимы.", "",
          "| Сравнение | Набор | права только первая | только вторая | p |", "|---|---|---|---|---|"]
    for a, b in (("v3", "v4"), ("v4", "мини")):
        for s in ("А", "Б", "Новое"):
            if (s, a) in data and (s, b) in data:
                x, y, p = mcnemar(data[(s, a)], data[(s, b)])
                L.append(f"| {a} vs {b} | {s} | {x} | {y} | {p:.3f} |")
        if all((s, m) in data for s in ("А", "Б") for m in (a, b)):
            ab_a = {**{"A" + k: v for k, v in data[("А", a)].items()}, **{"B" + k: v for k, v in data[("Б", a)].items()}}
            ab_b = {**{"A" + k: v for k, v in data[("А", b)].items()}, **{"B" + k: v for k, v in data[("Б", b)].items()}}
            x, y, p = mcnemar(ab_a, ab_b)
            L.append(f"| {a} vs {b} | А+Б | {x} | {y} | {p:.3f} |")
    L.append("")

    L += ["## 3. По видам команд (А+Б+«Новое» вместе), 95%-интервал Уилсона", "",
          "| Вид | фраз | v3 | v4 | мини |", "|---|---|---|---|---|"]
    classes = {}
    for s in ("А", "Б", "Новое"):
        for m in MODELS:
            for i, v in (data.get((s, m)) or {}).items():
                classes.setdefault(v[3], {}).setdefault(m, []).append(v[0])
    for cls in sorted(classes, key=lambda c: -len(classes[c].get("v4", []))):
        row = [cls, str(len(classes[cls].get("v4", [])))]
        for m in MODELS:
            vals = classes[cls].get(m, [])
            if vals:
                lo, hi = wilson(sum(vals), len(vals))
                row.append(f"{pct(sum(vals) / len(vals))} ({pct(lo)}–{pct(hi)})")
            else:
                row.append("—")
        L.append("| " + " | ".join(row) + " |")
    L.append("")

    # --- цепочка: мини (порог t) -> Qwen (0.9) -> Gemini ---
    L += ["## 4. Цепочка «мини → Qwen → Gemini»", "",
          "Порог мини-модели выбран на dev: самый низкий, при котором среди оставленных ею фраз",
          "не меньше 99% верных. Qwen — с прежним порогом 0,9. Остальное — Gemini (его точность",
          "здесь не считается: на «Новом» его не гоняли; на А он 100%, Б размечен им самим).", ""]
    dev_mini = data.get(("dev", "мини"))
    t_best = None
    if dev_mini:
        for t in [x / 100 for x in range(50, 100)]:
            kept = [v for v in dev_mini.values() if v[1] >= t]
            if kept and sum(v[0] for v in kept) / len(kept) >= 0.99:
                t_best = t
                break
    if t_best is None:
        t_best = 0.95
    L.append(f"Порог мини-модели по dev: **{t_best:.2f}**.")
    L += ["", "| Набор | Qwen | решает мини | из них верно | решает Qwen | из них верно | уходит в Gemini | "
          "ошибок своих моделей | медиана задержки |", "|---|---|---|---|---|---|---|---|---|"]
    for s in ("А", "Б", "Новое"):
        mini = data.get((s, "мини"))
        for q in ("v3", "v4"):
            qw = data.get((s, q))
            if not mini or not qw:
                continue
            n = len(qw)
            by_mini = [i for i in qw if i in mini and mini[i][1] >= t_best]
            rest = [i for i in qw if i not in by_mini]
            by_qwen = [i for i in rest if qw[i][1] >= 0.9]
            to_gem = len(rest) - len(by_qwen)
            ok_m = sum(mini[i][0] for i in by_mini)
            ok_q = sum(qw[i][0] for i in by_qwen)
            errs = (len(by_mini) - ok_m) + (len(by_qwen) - ok_q)
            lat = sorted([mini[i][2] for i in by_mini]
                         + [mini.get(i, (0, 0, 5, ""))[2] + qw[i][2] for i in by_qwen]
                         + [mini.get(i, (0, 0, 5, ""))[2] + qw[i][2] + 2000 for i in rest if i not in by_qwen])
            med = lat[len(lat) // 2] if lat else 0
            L.append(f"| {s} | {q} | {len(by_mini)} ({pct(len(by_mini) / n)}) | {pct(ok_m / max(1, len(by_mini)))} "
                     f"| {len(by_qwen)} ({pct(len(by_qwen) / n)}) | {pct(ok_q / max(1, len(by_qwen)))} "
                     f"| {to_gem} ({pct(to_gem / n)}) | {errs} | ~{med:.0f} мс |")
    L += ["", "Задержка Gemini принята за 2000 мс (замер 06.10); мини и Qwen — свои замеры на этом ПК.", ""]

    # --- честная калибровка: половина Б для порога, другая половина — для проверки ---
    # dev — перефразировки учителя, похожие на обучение; на них мини-модель уверена
    # «по делу», а на живых фразах — самоуверенна. Порог, выбранный только на dev, на Б
    # не держится. Подбирать порог на Б целиком нельзя (подгонка под экзамен), поэтому
    # Б делится пополам по номеру фразы: нечётные — калибровка, чётные — проверка.
    L += ["## 5. Порог мини-модели: калибровка на половине Б (нечётные), проверка на другой (чётные)", ""]
    mini_b, v4_b = data.get(("Б", "мини")), data.get(("Б", "v4"))
    mini_a, v4_a = data.get(("А", "мини")), data.get(("А", "v4"))
    mini_n, v4_n = data.get(("Новое", "мини")), data.get(("Новое", "v4"))
    if mini_b and v4_b and dev_mini:
        num = lambda i: int("".join(ch for ch in i if ch.isdigit()) or 0)
        calib = {i: v for i, v in mini_b.items() if num(i) % 2 == 1}
        check = {i for i in mini_b if num(i) % 2 == 0}
        pool = list(calib.values()) + list(dev_mini.values())
        t_cal = None
        for t in [x / 1000 for x in range(900, 1000)]:
            kept = [v for v in pool if v[1] >= t]
            if kept and sum(v[0] for v in kept) / len(kept) >= 0.99:
                t_cal = t
                break
        t_cal = t_cal or 0.999
        L.append(f"Порог по dev + нечётным фразам Б (≥ 99% верных среди оставленных): **{t_cal:.3f}**.")
        L += ["", "| Проверка | решает мини | из них верно | решает v4 (≥0,9) | из них верно | Gemini | "
              "ошибок своих моделей | для сравнения: одна v4 (≥0,9), ошибок |", "|---|---|---|---|---|---|---|---|"]
        for name, mini, qw, ids in (("Б, чётные", mini_b, v4_b, check), ("А", mini_a, v4_a, None),
                                    ("Новое", mini_n, v4_n, None)):
            if not mini or not qw:
                continue
            ids = [i for i in qw if ids is None or i in ids]
            n = len(ids)
            bm = [i for i in ids if i in mini and mini[i][1] >= t_cal]
            rest = [i for i in ids if i not in bm]
            bq = [i for i in rest if qw[i][1] >= 0.9]
            okm, okq = sum(mini[i][0] for i in bm), sum(qw[i][0] for i in bq)
            alone = [i for i in ids if qw[i][1] >= 0.9]
            alone_err = len(alone) - sum(qw[i][0] for i in alone)
            L.append(f"| {name} ({n}) | {len(bm)} ({pct(len(bm) / n)}) | {pct(okm / max(1, len(bm)))} | "
                     f"{len(bq)} ({pct(len(bq) / n)}) | {pct(okq / max(1, len(bq)))} | {len(rest) - len(bq)} | "
                     f"{len(bm) - okm + len(bq) - okq} | {alone_err} |")
        L.append("")

    out = os.path.join(HERE, "runs", "stats-v4.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print("\n".join(L))
    print("\nотчёт:", out)


if __name__ == "__main__":
    main()
