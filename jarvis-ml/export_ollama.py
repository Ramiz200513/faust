"""
Выгрузка обученной модели в Ollama — чтобы релей мог с ней разговаривать.

Шаги:
  1. Влить заплатки LoRA в базовую модель: получается обычная модель, которая
     умеет наше (папка runs/<name>/merged).
  2. Написать Modelfile — «паспорт» модели для Ollama: где веса, какая системная
     подсказка (та же, что при обучении — common.SYSTEM), как оформлять диалог.
  3. Перевести веса в формат GGUF (конвертер llama.cpp) и выполнить
     ollama create <тег>; если просили — со сжатием (квантованием) весов.
  4. Проверка: несколько фраз через тот же HTTP-адрес, которым пользуется релей.

Запуск:
  python export_ollama.py --name qwen05-v1 --tag faust-intent
  python export_ollama.py --name qwen15-v1 --tag faust-intent --outtype q8_0
  (--quantize в Ollama 0.40 для GGUF не работает: сжимает сам конвертер, --outtype)
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

from common import HERE, SYSTEM

# Оформление диалога Qwen (ChatML) — ровно то, что строит tokenizer.apply_chat_template при обучении.
TEMPLATE = """<|im_start|>system
{{ .System }}<|im_end|>
<|im_start|>user
{{ .Prompt }}<|im_end|>
<|im_start|>assistant
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--tag", default="faust-intent", help="имя модели в Ollama; его же ждёт релей")
    ap.add_argument("--quantize", default="", help="сжатие весов: q8_0 (почти без потерь) или q4_K_M (вдвое меньше)")
    ap.add_argument("--skip-merge", action="store_true")
    ap.add_argument("--outtype", default="f16", help="точность весов в GGUF: f16 (без потерь) или q8_0 (вдвое меньше)")
    ap.add_argument("--keep-files", action="store_true", help="не удалять слитую модель и GGUF после выгрузки")
    args = ap.parse_args()

    run = os.path.join(HERE, "runs", args.name)
    merged = os.path.join(run, "merged")
    if not args.skip_merge:
        base = json.load(open(os.path.join(run, "train_log.json"), encoding="utf-8"))["base"]
        print("вливаю заплатки LoRA в", base)
        # На процессоре и в половинной точности: видеопамять не нужна, а файл выходит вдвое меньше.
        model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.float16)
        model = PeftModel.from_pretrained(model, os.path.join(run, "adapter")).merge_and_unload()
        model.save_pretrained(merged, safe_serialization=True)
        AutoTokenizer.from_pretrained(os.path.join(run, "adapter")).save_pretrained(merged)

    # --- GGUF: формат, который понимает Ollama ---
    # Ollama 0.34 не умеет сама читать Qwen в исходном виде («unsupported architecture»),
    # поэтому переводим официальным конвертером llama.cpp (папка llama.cpp рядом —
    # в ней только скрипт и его библиотека, без самой программы).
    gguf = os.path.join(run, "model.gguf")
    converter = os.path.join(HERE, "llama.cpp", "convert_hf_to_gguf.py")
    env = dict(os.environ, PYTHONPATH=os.path.join(HERE, "llama.cpp", "gguf-py"), PYTHONIOENCODING="utf-8")
    print("перевожу в GGUF…")
    subprocess.run([sys.executable, converter, merged, "--outfile", gguf, "--outtype", args.outtype],
                   check=True, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not args.keep_files:
        # Слитая модель больше не нужна (её всегда можно получить заново из заплаток LoRA),
        # а места занимает столько же, сколько сама модель. На маленьком диске это решает.
        shutil.rmtree(merged, ignore_errors=True)

    modelfile = os.path.join(run, "Modelfile")
    with open(modelfile, "w", encoding="utf-8") as f:
        f.write("# Своя модель разбора фраз «Фауста». Создан export_ollama.py — руками не править.\n")
        f.write("FROM ./model.gguf\n")
        f.write(f'TEMPLATE """{TEMPLATE}"""\n')
        f.write(f'SYSTEM """{SYSTEM}"""\n')
        f.write("PARAMETER temperature 0\n")
        f.write('PARAMETER stop "<|im_end|>"\n')
        f.write("PARAMETER num_predict 96\n")

    cmd = ["ollama", "create", args.tag, "-f", "Modelfile"]
    if args.quantize:
        cmd += ["--quantize", args.quantize]
    print(">", " ".join(cmd))
    subprocess.run(cmd, cwd=run, check=True)
    if not args.keep_files:
        os.remove(gguf)   # Ollama уже скопировала веса в своё хранилище

    # --- проверка тем же путём, что у релея ---
    for phrase in ("сделай потише", "открой ютуб", "скачай отчёт и отправь Пете в телеграм"):
        body = json.dumps({"model": args.tag, "stream": False, "logprobs": True, "keep_alive": "60m",
                           "messages": [{"role": "user", "content": phrase}],
                           "options": {"temperature": 0, "num_predict": 96}}).encode("utf-8")
        t0 = time.perf_counter()
        # Именно 127.0.0.1, а не localhost: Windows сначала пробует localhost по IPv6, где
        # Ollama не слушает, и каждое новое соединение теряет на этом около двух секунд.
        req = urllib.request.Request("http://127.0.0.1:11434/api/chat", data=body,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as resp:
            out = json.loads(resp.read().decode("utf-8"))
        print(f"  «{phrase}» -> {out['message']['content'].strip()}   ({(time.perf_counter() - t0) * 1000:.0f} мс)")


if __name__ == "__main__":
    main()
