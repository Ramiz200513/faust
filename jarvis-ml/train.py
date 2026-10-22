"""
Дообучение своей модели разбора фраз: Qwen2.5 + LoRA на нашем датасете.

Что такое LoRA и почему она. Базовая модель (0.5–1.5 млрд чисел) уже знает
русский язык. Переучивать все её числа нам незачем и не на чем (4 ГБ
видеопамяти). LoRA добавляет к слоям модели маленькие «заплатки» — около 1%
от её размера — и учит только их, а саму модель не трогает. Потом заплатки
вливаются в модель (export_ollama.py), и получается обычная модель, которая
умеет наше.

Чему учим: по системной подсказке и фразе пользователя написать команду JSON.
Ошибка считается ТОЛЬКО по ответу — подсказку и фразу модель «читает», а не
учится их писать.

Запуск:
  python train.py --base Qwen/Qwen2.5-0.5B-Instruct --name qwen05-v1
  python train.py --base Qwen/Qwen2.5-1.5B-Instruct --name qwen15-v1 --batch 8
Итог: runs/<name>/adapter (заплатки LoRA) и runs/<name>/train_log.json (кривая обучения).
"""
import argparse
import json
import math
import os
import random
import time

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer

from common import DATA, HERE, SYSTEM, read_jsonl, to_command


def chat_prompt(tok, phrase):
    """Текст до ответа: системная подсказка + фраза + «слово за ассистентом»."""
    return tok.apply_chat_template(
        [{"role": "system", "content": SYSTEM}, {"role": "user", "content": phrase}],
        tokenize=False, add_generation_prompt=True)


def encode(tok, row, max_len):
    prompt_ids = tok(chat_prompt(tok, row["phrase"]), add_special_tokens=False)["input_ids"]
    answer_ids = tok(row["target"] + "<|im_end|>", add_special_tokens=False)["input_ids"]
    ids = (prompt_ids + answer_ids)[:max_len]
    # -100 — «здесь ошибку не считать»: учим писать только ответ.
    labels = ([-100] * len(prompt_ids) + answer_ids)[:max_len]
    return ids, labels


def batches(examples, batch_size, pad_id, shuffle, rng):
    order = list(range(len(examples)))
    groups = [order[i:i + batch_size] for i in range(0, len(order), batch_size)]
    if shuffle:
        # В порцию подбираем примеры близкой длины. Порция дополняется пустышками до
        # самого длинного примера, и когда рядом «громкость 30» и фраза в три строки,
        # большая часть вычислений уходит на пустышки. Перемешиваем всё, режем на куски
        # по 50 порций, внутри куска сортируем по длине, а сами порции снова
        # перемешиваем — случайность остаётся, а шаг обучения становится короче.
        rng.shuffle(order)
        groups = []
        for i in range(0, len(order), batch_size * 50):
            part = sorted(order[i:i + batch_size * 50], key=lambda j: len(examples[j][0]))
            groups += [part[k:k + batch_size] for k in range(0, len(part), batch_size)]
        rng.shuffle(groups)
    for group in groups:
        chunk = [examples[j] for j in group]
        width = max(len(ids) for ids, _ in chunk)
        ids = torch.full((len(chunk), width), pad_id, dtype=torch.long)
        labels = torch.full((len(chunk), width), -100, dtype=torch.long)
        mask = torch.zeros((len(chunk), width), dtype=torch.long)
        for r, (x, y) in enumerate(chunk):
            ids[r, :len(x)] = torch.tensor(x)
            labels[r, :len(y)] = torch.tensor(y)
            mask[r, :len(x)] = 1
        yield ids, mask, labels


def answer_loss(model, ids, mask, labels):
    """
    Ошибка только по словам ОТВЕТА.

    Обычный путь (model(..., labels=...)) считает вероятности всех 150 тысяч слов
    словаря для КАЖДОЙ позиции — включая подсказку и фразу, где ошибка всё равно
    не учитывается. На порции из 16 примеров это около гигабайта видеопамяти
    впустую, и на карте с 4 ГБ обучение начинает «свопиться» в обычную память
    и замедляется в десятки раз. Здесь словарь применяется только там, где
    модель должна написать слово ответа.
    """
    core = model.get_base_model()                      # сама Qwen, заплатки LoRA уже внутри её слоёв
    hidden = core.model(input_ids=ids, attention_mask=mask).last_hidden_state
    h, y = hidden[:, :-1, :], labels[:, 1:]            # по позиции t предсказываем слово t+1
    keep = y != -100
    logits = core.lm_head(h[keep])
    return torch.nn.functional.cross_entropy(logits.float(), y[keep])


@torch.no_grad()
def generate(model, tok, phrase, max_new_tokens=64):
    """Ответ модели на фразу и её уверенность (произведение вероятностей слов ответа)."""
    enc = tok(chat_prompt(tok, phrase), return_tensors="pt", add_special_tokens=False).to(model.device)
    on_gpu = model.device.type == "cuda"
    with torch.autocast(device_type=model.device.type, dtype=torch.bfloat16, enabled=on_gpu):
        out = model.generate(**enc, max_new_tokens=max_new_tokens, do_sample=False, output_scores=True,
                             return_dict_in_generate=True, pad_token_id=tok.pad_token_id,
                             eos_token_id=tok.convert_tokens_to_ids("<|im_end|>"))
    new = out.sequences[0, enc["input_ids"].shape[1]:]
    logp = 0.0
    for step, token in enumerate(new):
        logp += torch.log_softmax(out.scores[step][0].float(), dim=-1)[token].item()
    text = tok.decode(new, skip_special_tokens=True).strip()
    return text, math.exp(logp)


def accuracy(model, tok, rows):
    """Доля фраз, где команда совпала с эталоном целиком (после той же проверки, что в релее)."""
    model.eval()
    ok = 0
    for r in rows:
        text, _ = generate(model, tok, r["phrase"])
        ok += to_command(r["phrase"], text) == to_command(r["phrase"], r["target"])
    model.train()
    return ok / max(1, len(rows))


def save(model, tok, out_dir, log, device, t0):
    """Заплатки LoRA и журнал обучения — на диск."""
    model.save_pretrained(os.path.join(out_dir, "adapter"))
    tok.save_pretrained(os.path.join(out_dir, "adapter"))
    if device == "cuda":
        log["max_vram_gb"] = round(torch.cuda.max_memory_allocated() / 2 ** 30, 2)
    log["total_seconds"] = round(time.time() - t0)
    with open(os.path.join(out_dir, "train_log.json"), "w", encoding="utf-8") as f:
        json.dump(log, f, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-0.5B-Instruct")
    ap.add_argument("--cool-ms", type=int, default=0,
                    help="пауза после каждого шага, мс: видеокарта греется меньше, обучение идёт дольше "
                         "(250 — примерно на четверть дольше и заметно прохладнее)")
    ap.add_argument("--name", default="qwen05-v1")
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--accum", type=int, default=2, help="накопление градиента: batch*accum примеров на шаг")
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=16, help="размер заплаток LoRA")
    ap.add_argument("--max-len", type=int, default=160)
    ap.add_argument("--val-sample", type=int, default=300, help="сколько фраз проверочного набора проверять генерацией")
    ap.add_argument("--train-file", default=os.path.join(DATA, "train.jsonl"))
    ap.add_argument("--grad-ckpt", action="store_true", help="экономить видеопамять (для модели 1.5B)")
    ap.add_argument("--checkpoint-every", type=int, default=100,
                    help="каждые N шагов сохранять контрольную точку (0 — не сохранять)")
    ap.add_argument("--resume", action="store_true",
                    help="продолжить с контрольной точки runs/<name>/checkpoint, если она есть")
    args = ap.parse_args()

    rng = random.Random(7)
    torch.manual_seed(7)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    out_dir = os.path.join(HERE, "runs", args.name)
    os.makedirs(out_dir, exist_ok=True)

    tok = AutoTokenizer.from_pretrained(args.base)
    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token
    # bfloat16 — половинная точность: модель занимает вдвое меньше видеопамяти.
    model = AutoModelForCausalLM.from_pretrained(args.base, dtype=torch.bfloat16 if device == "cuda" else torch.float32)
    model.to(device)
    model.config.use_cache = False

    lora = LoraConfig(r=args.rank, lora_alpha=2 * args.rank, lora_dropout=0.05, task_type="CAUSAL_LM",
                      target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"])
    model = get_peft_model(model, lora)
    if args.grad_ckpt:
        # Экономия видеопамяти ценой времени: промежуточные вычисления не хранятся, а
        # пересчитываются. Нужна только модели 1.5B на 4 ГБ; маленькой 0.5B она лишь мешает.
        # enable_input_require_grads обязателен: без него при такой экономии до заплаток
        # LoRA не доходит сигнал ошибки, и модель не учится вовсе.
        model.gradient_checkpointing_enable()
        model.enable_input_require_grads()
    # Сами заплатки учим в полной точности: в половинной мелкие шаги обучения теряются.
    for p in model.parameters():
        if p.requires_grad:
            p.data = p.data.float()
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total = sum(p.numel() for p in model.parameters())
    print(f"модель {args.base}: {total / 1e6:.0f} млн чисел, учим {trainable / 1e6:.1f} млн ({100 * trainable / total:.2f}%)")

    train_rows = read_jsonl(args.train_file)
    val_rows = read_jsonl(os.path.join(DATA, "val.jsonl"))
    train = [encode(tok, r, args.max_len) for r in train_rows]
    val = [encode(tok, r, args.max_len) for r in val_rows]
    val_check = rng.sample(val_rows, min(args.val_sample, len(val_rows)))
    print(f"примеров: обучение {len(train)}, проверка {len(val)}")

    steps_per_epoch = math.ceil(len(train) / (args.batch * args.accum))
    total_steps = int(steps_per_epoch * args.epochs)
    warmup = max(10, total_steps // 20)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr, weight_decay=0.0)

    def lr_at(step):   # разгон, потом плавное снижение до нуля
        if step < warmup:
            return args.lr * (step + 1) / warmup
        progress = (step - warmup) / max(1, total_steps - warmup)
        return args.lr * 0.5 * (1 + math.cos(math.pi * min(1.0, progress)))

    log = {"base": args.base, "args": vars(args), "trainable_params": trainable, "epochs": []}
    model.train()
    step, t0 = 0, time.time()
    epoch = 0

    # --- продолжение оборванного обучения ---
    # Обучение 1.5B идёт полтора-два часа на ноутбуке. Если его прервут (закрылось
    # окно, перегрев, выключение), без контрольной точки пропадает всё. В точке
    # лежат заплатки LoRA, состояние оптимизатора (его «память» о прошлых шагах —
    # без неё продолжение пошло бы как с нуля) и состояние генератора случайных
    # чисел на начало прохода: по нему тот же самый порядок порций строится
    # заново, и уже пройденные порции пропускаются.
    ckpt_dir = os.path.join(out_dir, "checkpoint")
    resume = None
    if args.resume and os.path.exists(os.path.join(ckpt_dir, "state.pt")):
        resume = torch.load(os.path.join(ckpt_dir, "state.pt"), weights_only=False)
        from peft import set_peft_model_state_dict
        from safetensors.torch import load_file
        set_peft_model_state_dict(model, load_file(os.path.join(ckpt_dir, "adapter_model.safetensors")))
        for p in model.parameters():          # загруженные заплатки — снова в полной точности
            if p.requires_grad:
                p.data = p.data.float()
        opt.load_state_dict(resume["opt"])
        step, epoch, log = resume["step"], resume["epoch"] - 1, resume["log"]
        t0 = time.time() - resume["seconds"]
        print(f"продолжаю с шага {step} (проход {resume['epoch']}, порция {resume['micro']})", flush=True)

    def checkpoint(epoch_rng_state, micro, running, seen):
        model.save_pretrained(ckpt_dir)
        torch.save({"opt": opt.state_dict(), "step": step, "epoch": epoch, "micro": micro,
                    "rng": epoch_rng_state, "running": running, "seen": seen,
                    "log": log, "seconds": time.time() - t0}, os.path.join(ckpt_dir, "state.pt.tmp"))
        # Сначала во временный файл, потом подмена: оборвись запись посередине —
        # прежняя точка останется целой.
        os.replace(os.path.join(ckpt_dir, "state.pt.tmp"), os.path.join(ckpt_dir, "state.pt"))

    while step < total_steps:
        epoch += 1
        running, seen = 0.0, 0
        skip = 0
        if resume is not None:
            rng.setstate(resume["rng"])
            skip, running, seen = resume["micro"], resume["running"], resume["seen"]
            resume = None
        epoch_rng_state = rng.getstate()
        opt.zero_grad(set_to_none=True)
        for i, (ids, mask, labels) in enumerate(batches(train, args.batch, tok.pad_token_id, True, rng)):
            if i < skip:
                continue                       # эти порции уже пройдены до обрыва
            ids, mask, labels = ids.to(device), mask.to(device), labels.to(device)
            with torch.autocast(device_type=device, dtype=torch.bfloat16, enabled=device == "cuda"):
                loss = answer_loss(model, ids, mask, labels)
            (loss / args.accum).backward()
            running += loss.item()
            seen += 1
            if (i + 1) % args.accum == 0:
                for g in opt.param_groups:
                    g["lr"] = lr_at(step)
                torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 1.0)
                opt.step()
                opt.zero_grad(set_to_none=True)
                step += 1
                if args.cool_ms:
                    time.sleep(args.cool_ms / 1000)   # «прохладный режим» — см. --cool-ms
                if step % 50 == 0:
                    print(f"  шаг {step}/{total_steps}, ошибка {running / seen:.4f}, {time.time() - t0:.0f} с", flush=True)
                if args.checkpoint_every and step % args.checkpoint_every == 0:
                    checkpoint(epoch_rng_state, i + 1, running, seen)
                if step >= total_steps:
                    break

        # --- конец эпохи: ошибка и точность на проверочном наборе ---
        model.eval()
        vloss, vn = 0.0, 0
        with torch.no_grad():
            for ids, mask, labels in batches(val, args.batch, tok.pad_token_id, False, rng):
                ids, mask, labels = ids.to(device), mask.to(device), labels.to(device)
                with torch.autocast(device_type=device, dtype=torch.bfloat16, enabled=device == "cuda"):
                    vloss += answer_loss(model, ids, mask, labels).item()
                vn += 1
        model.config.use_cache = True
        acc = accuracy(model, tok, val_check)
        model.config.use_cache = False
        model.train()
        row = {"epoch": epoch, "step": step, "train_loss": running / max(1, seen), "val_loss": vloss / max(1, vn),
               "val_exact": acc, "seconds": round(time.time() - t0)}
        log["epochs"].append(row)
        print(f"эпоха {epoch}: ошибка обучения {row['train_loss']:.4f}, проверки {row['val_loss']:.4f}, "
              f"точность на проверке {100 * acc:.1f}%, прошло {row['seconds']} с", flush=True)
        # Сохраняем после КАЖДОГО прохода: если обучение остановят (перегрев, села
        # батарея), останется модель после последнего законченного прохода, а не ничего.
        save(model, tok, out_dir, log, device, t0)

    save(model, tok, out_dir, log, device, t0)
    print(f"готово: {out_dir}, {log['total_seconds']} с, видеопамяти до {log.get('max_vram_gb', '—')} ГБ")


if __name__ == "__main__":
    main()
