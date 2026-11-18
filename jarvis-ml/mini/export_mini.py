"""
Выгрузка «Фауст-мини» для релея (Java, без PyTorch и без сторонних библиотек).

  python mini/export_mini.py --name mini-v1

Пишет:
  jarvis-relay/src/main/resources/mini/faust-mini.bin   — все веса подряд, float32, little-endian
  jarvis-relay/src/main/resources/mini/faust-mini.json  — словари, размеры, где какой массив в .bin
  jarvis-relay/src/test/resources/mini/parity.jsonl     — эталонные ответы PyTorch для сверки Java

Почему свой простой формат, а не ONNX: модель крошечная (≈0,5 млн чисел), а слоёв в ней
пять видов (вложения, свёртка, GRU, внимание, линейный). Их проще и прозрачнее
написать на Java самим (MiniIntentModel), чем тянуть в релей движок ONNX на десятки
мегабайт. Правильность Java-версии проверяется не «на глаз», а сверкой с PyTorch
на всех экзаменационных фразах (тест MiniIntentModelTest).
"""
import argparse
import json
import os
import struct
import sys

import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from common import DATA, RELAY_EVAL, read_jsonl  # noqa: E402
import faust_mini as fm  # noqa: E402

ROOT = os.path.dirname(HERE)
RELAY = os.path.join(os.path.dirname(ROOT), "jarvis-relay")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="mini-v1")
    ap.add_argument("--parity", type=int, default=400, help="сколько фраз обучения добавить к сверке (кроме экзаменов)")
    args = ap.parse_args()

    torch.set_num_threads(1)
    model, vocab, meta = fm.load(os.path.join(ROOT, "runs", args.name, "model.pt"))
    state = model.state_dict()

    out_dir = os.path.join(RELAY, "src", "main", "resources", "mini")
    os.makedirs(out_dir, exist_ok=True)
    index, offset = {}, 0
    with open(os.path.join(out_dir, "faust-mini.bin"), "wb") as f:
        for name, t in state.items():
            arr = t.detach().cpu().float().contiguous().view(-1).tolist()
            f.write(struct.pack(f"<{len(arr)}f", *arr))
            index[name] = {"shape": list(t.shape), "offset": offset}
            offset += len(arr)
    desc = {
        "format": "faust-mini/1",
        "name": args.name,
        "epoch": meta.get("epoch"),
        "val_exact": meta.get("val_exact"),
        "dims": meta.get("dims"),
        "max_len": fm.MAX_LEN,
        "intents": fm.INTENTS,
        "slot_of": fm.SLOT_OF,
        "slot_field": fm.SLOT_FIELD,
        "chars": vocab.chars,
        "values": vocab.values,
        "tensors": index,
        "floats": offset,
    }
    with open(os.path.join(out_dir, "faust-mini.json"), "w", encoding="utf-8") as f:
        json.dump(desc, f, ensure_ascii=False)

    # --- эталон для сверки: по одной фразе (как в релее), а не порцией ---
    # В порции фразы дополняются заполнителями до одной длины, и обратный проход GRU
    # их «видит» — ответы чуть другие. Релей разбирает по одной фразе, так и сверяем.
    phrases = []
    for name in ("intent_test.jsonl", "intent_test_b.jsonl", "intent_test_new.jsonl"):
        phrases += [c["phrase"] for c in read_jsonl(os.path.join(RELAY_EVAL, name))]
    phrases += [r["phrase"] for r in read_jsonl(os.path.join(DATA, "dev.jsonl"))]
    phrases += [r["phrase"] for r in read_jsonl(os.path.join(DATA, "train.jsonl"))[:args.parity]]
    phrases += ["", "   ", "ё", "Фауст!!!", "x" * 400]          # края: пусто, одна буква, слишком длинно
    par_dir = os.path.join(RELAY, "src", "test", "resources", "mini")
    os.makedirs(par_dir, exist_ok=True)
    with open(os.path.join(par_dir, "parity.jsonl"), "w", encoding="utf-8") as f:
        for p in phrases:
            raw, conf = fm.predict(model, vocab, [p])[0]
            f.write(json.dumps({"phrase": p, "raw": raw, "confidence": conf}, ensure_ascii=False) + "\n")

    size = os.path.getsize(os.path.join(out_dir, "faust-mini.bin")) / 2 ** 20
    print(f"веса: {offset} чисел, {size:.2f} МБ -> {out_dir}")
    print(f"сверка: {len(phrases)} фраз -> {par_dir}\\parity.jsonl")


if __name__ == "__main__":
    main()
