"""
Обучение «Фауст-мини» с нуля на data/train.jsonl, выбор лучшей эпохи по data/val.jsonl.

  python mini/train_mini.py                      на процессоре (минуты; видеокарта не трогается)
  python mini/train_mini.py --gpu                на видеокарте (Kaggle)
  python mini/train_mini.py --name mini-v1 --epochs 40

Результат — runs/<name>/model.pt (веса + словари) и train_log.json (кривая обучения).
"""
import argparse
import json
import os
import random
import sys
import time

import torch
import torch.nn.functional as F

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from common import DATA, read_jsonl, to_command  # noqa: E402
import faust_mini as fm  # noqa: E402

ROOT = os.path.dirname(HERE)


def typo(text, rng):
    """
    Одна случайная «опечатка распознавания»: пропуск, повтор, перестановка соседних
    букв или замена на соседнюю по алфавиту. Так модель учится не рассыпаться от
    «сдеай» вместо «сделай» — у неё нет знания языка, которое прощало бы такое.
    """
    if len(text) < 4:
        return text
    i = rng.randrange(1, len(text) - 1)
    kind = rng.randrange(4)
    if kind == 0:
        return text[:i] + text[i + 1:]
    if kind == 1:
        return text[:i] + text[i] + text[i:]
    if kind == 2:
        return text[:i] + text[i + 1] + text[i] + text[i + 2:]
    c = text[i]
    return text[:i] + (chr(ord(c) + rng.choice((-1, 1))) if c.isalpha() else c) + text[i + 1:]


def encode_rows(rows, vocab):
    out = []
    for r in rows:
        intent, level, value = fm.target_to_labels(r["target"])
        slot = fm.SLOT_OF.get(fm.INTENTS[intent])
        slot_ids = {k: -1 for k in vocab.values}
        if slot in vocab.values:
            slot_ids[slot] = vocab.value_id[slot].get(value, -1)
        out.append({"phrase": fm.norm_text(r["phrase"]), "intent": intent, "level": level, "slots": slot_ids})
    return out


def batches(items, size, rng, shuffle, typo_p, vocab):
    order = list(range(len(items)))
    if shuffle:
        rng.shuffle(order)
    for s in range(0, len(order), size):
        part = [items[i] for i in order[s:s + size]]
        texts = [typo(x["phrase"], rng) if typo_p and rng.random() < typo_p else x["phrase"] for x in part]
        ids = fm.pad_batch([vocab.encode(t) for t in texts])
        y = {"intent": torch.tensor([x["intent"] for x in part]),
             "level": torch.tensor([x["level"] for x in part])}
        for k in vocab.values:
            y[k] = torch.tensor([x["slots"][k] for x in part])
        yield ids, y


def loss_fn(out, y):
    """Ошибка типа + ошибки слотов там, где слот у примера есть (-1 — нет, не считается)."""
    loss = F.cross_entropy(out["intent"], y["intent"])
    for k in out:
        if k == "intent":
            continue
        if (y[k] >= 0).any():
            loss = loss + F.cross_entropy(out[k], y[k], ignore_index=-1)
    return loss


def exact(model, vocab, rows, batch=256):
    """Доля фраз, где команда совпала с эталоном целиком — как accuracy() в train.py для Qwen."""
    ok = 0
    for s in range(0, len(rows), batch):
        part = rows[s:s + batch]
        for r, (raw, _) in zip(part, fm.predict(model, vocab, [x["phrase"] for x in part])):
            ok += to_command(r["phrase"], raw) == to_command(r["phrase"], r["target"])
    return ok / max(1, len(rows))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="mini-v1")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--patience", type=int, default=6, help="стоп, если точность на проверке не росла N эпох")
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--typo", type=float, default=0.3, help="доля примеров с опечаткой на лету")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--train-file", default=os.path.join(DATA, "train.jsonl"))
    ap.add_argument("--gpu", action="store_true", help="считать на видеокарте (по умолчанию — процессор)")
    ap.add_argument("--threads", type=int, default=0, help="потоков процессора (0 — как решит PyTorch)")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    torch.manual_seed(args.seed)
    if args.threads:
        torch.set_num_threads(args.threads)
    device = "cuda" if args.gpu and torch.cuda.is_available() else "cpu"
    out_dir = os.path.join(ROOT, "runs", args.name)
    os.makedirs(out_dir, exist_ok=True)

    train_rows = read_jsonl(args.train_file)
    val_rows = read_jsonl(os.path.join(DATA, "val.jsonl"))
    vocab = fm.Vocab.build(train_rows)
    train = encode_rows(train_rows, vocab)
    dims = {"emb": 48, "conv": 128, "rnn": 96, "dropout": 0.3}
    model = fm.FaustMini(len(vocab.chars), {k: len(v) for k, v in vocab.values.items()}, **dims).to(device)
    n_params = fm.count_params(model)
    print(f"«Фауст-мини»: {n_params / 1e6:.2f} млн чисел (все обучаются с нуля), букв в словаре {len(vocab.chars)}, "
          f"значений: " + ", ".join(f"{k} {len(v)}" for k, v in vocab.values.items()), flush=True)
    print(f"примеров: обучение {len(train)}, проверка {len(val_rows)}, устройство {device}", flush=True)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    steps_per_epoch = (len(train) + args.batch - 1) // args.batch
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps_per_epoch * args.epochs)
    log = {"args": vars(args), "params": n_params, "dims": dims, "epochs": []}
    best, best_epoch, t0 = -1.0, 0, time.time()
    for epoch in range(1, args.epochs + 1):
        model.train()
        total, n = 0.0, 0
        for ids, y in batches(train, args.batch, rng, True, args.typo, vocab):
            ids = ids.to(device)
            y = {k: v.to(device) for k, v in y.items()}
            loss = loss_fn(model(ids), y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            total += loss.item()
            n += 1
        model.cpu()
        acc = exact(model, vocab, val_rows)
        model.to(device)
        row = {"epoch": epoch, "train_loss": round(total / n, 4), "val_exact": round(acc, 4),
               "seconds": round(time.time() - t0)}
        log["epochs"].append(row)
        mark = ""
        if acc > best:
            best, best_epoch = acc, epoch
            model.cpu()
            fm.save(os.path.join(out_dir, "model.pt"), model, vocab, {"dims": dims, "epoch": epoch, "val_exact": acc})
            model.to(device)
            mark = "  ← лучшая, сохранена"
        print(f"эпоха {epoch}: ошибка {row['train_loss']:.4f}, точность на проверке {100 * acc:.1f}%, "
              f"{row['seconds']} с{mark}", flush=True)
        if epoch - best_epoch >= args.patience:
            print(f"точность не росла {args.patience} эпох — стоп", flush=True)
            break
    log.update(best_epoch=best_epoch, best_val_exact=best, total_seconds=round(time.time() - t0))
    with open(os.path.join(out_dir, "train_log.json"), "w", encoding="utf-8") as f:
        json.dump(log, f, ensure_ascii=False, indent=2)
    size_mb = os.path.getsize(os.path.join(out_dir, "model.pt")) / 2 ** 20
    print(f"готово: лучшая эпоха {best_epoch}, точность на проверке {100 * best:.1f}%, файл {size_mb:.1f} МБ, "
          f"{log['total_seconds']} с", flush=True)


if __name__ == "__main__":
    main()
