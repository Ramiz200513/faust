"""
Пакет для обучения на Kaggle: архив с кодом и данными + блокнот.

  python kaggle/make_package.py

Получается:
  kaggle/faust-ml-v4.zip       — загрузить на Kaggle как датасет (Datasets -> New Dataset, приватный)
  kaggle/faust-train-v4.ipynb  — импортировать как блокнот (Create -> Notebook -> File -> Import)

В архиве только то, что нужно для обучения и экзамена: код, обучающий набор и
экзамены. Ключей, токенов и настроек ПК в нём нет — их там и не бывает
(обучение не ходит ни в релей, ни в Gemini). Структура папок та же, что в
репозитории (jarvis-ml/ и jarvis-relay/eval/): скрипты находят экзамены по
относительному пути ../jarvis-relay/eval.
"""
import json
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ML = os.path.dirname(HERE)
REPO = os.path.dirname(ML)

FILES = {
    "jarvis-ml": ["common.py", "train.py", "eval_local.py",
                  "mini/faust_mini.py", "mini/train_mini.py", "mini/eval_mini.py",
                  "data/train.jsonl", "data/val.jsonl", "data/dev.jsonl"],
    "jarvis-relay/eval": ["intent_eval.py", "intent_test.jsonl", "intent_test_b.jsonl", "intent_test_new.jsonl"],
}


def build_zip(path):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for folder, files in FILES.items():
            for f in files:
                src = os.path.join(REPO, folder, f)
                z.write(src, arcname=f"faust/{folder}/{f}")
    return path


def md(text):
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)}


def code(text):
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip("\n").splitlines(keepends=True)}


CELLS = [
    md("""
# «Фауст»: обучение своих моделей разбора фраз (v4)

Две модели на одних и тех же данных (~19 тысяч фраз «фраза → команда»):

1. **Qwen2.5-1.5B + LoRA (v4)** — дообучение готовой модели: учатся только «заплатки» (~1% чисел).
2. **«Фауст-мини»** — своя маленькая нейросеть (≈0,5 млн чисел), обучается **с нуля**.

**Перед запуском** (справа, панель *Settings*):
- *Accelerator* — **GPU T4 x2** (или P100);
- *Internet* — **On** (скачать Qwen с Hugging Face и библиотеки);
- *Add Input* — датасет с архивом `faust-ml-v4.zip`.

Лучше запускать через **Save Version → Save & Run All**: тогда блокнот доработает в фоне,
даже если закрыть вкладку, а результат появится во вкладке *Output* (`faust-v4-results.zip`).
Обучение Qwen — порядка часа-полутора, мини-модель — минуты.
"""),
    code("""
# 1. Какая видеокарта досталась
!nvidia-smi --query-gpu=name,memory.total --format=csv
"""),
    code("""
# 2. Код и данные из датасета — в рабочую папку (там можно писать)
import glob, os, shutil
src = glob.glob('/kaggle/input/**/faust/jarvis-ml/common.py', recursive=True)
if not src:   # архив не распакован Kaggle'ом — распакуем сами
    import zipfile
    z = glob.glob('/kaggle/input/**/faust-ml-v4.zip', recursive=True)[0]
    zipfile.ZipFile(z).extractall('/kaggle/working')
else:
    shutil.copytree(os.path.dirname(os.path.dirname(src[0])), '/kaggle/working/faust', dirs_exist_ok=True)
os.chdir('/kaggle/working/faust/jarvis-ml')
print(os.getcwd()); print(os.listdir('.')); print(os.listdir('data'))
"""),
    code("""
# 3. Библиотеки (те же основные версии, что на ПК: transformers 4.x, peft)
!pip install -q "transformers>=4.46,<5" "peft>=0.13" "accelerate>=1.0"
# В образе Kaggle стоит torchao 0.10, а peft отказывается работать с torchao старше 0.16
# (первый запуск 13.11 упал на get_peft_model). Нам torchao не нужна — убираем.
!pip uninstall -y -q torchao
import torch, transformers, peft
print(torch.__version__, transformers.__version__, peft.__version__, torch.cuda.get_device_name(0))
"""),
    code("""
# 4. Базовая модель Qwen2.5-1.5B-Instruct (открытая, ~3 ГБ) — с Hugging Face
# Во временную папку /tmp, а не в рабочую: всё из /kaggle/working Kaggle выкладывает в Output,
# и модель (~3 ГБ) скачивалась бы вместе с результатом. Ссылка models/... — чтобы путь был как на ПК.
from huggingface_hub import snapshot_download
snapshot_download('Qwen/Qwen2.5-1.5B-Instruct', local_dir='/tmp/models/Qwen2.5-1.5B-Instruct')
os.makedirs('models', exist_ok=True)
if not os.path.exists('models/Qwen2.5-1.5B-Instruct'):
    os.symlink('/tmp/models/Qwen2.5-1.5B-Instruct', 'models/Qwen2.5-1.5B-Instruct')
"""),
    code("""
# 5. Дообучение Qwen (LoRA). Точность выбирается сама: на T4/P100 — fp16 с масштабированием.
#    Контрольная точка каждые 100 шагов; если сессия оборвётся — тот же запуск с --resume продолжит.
!python -u train.py --base models/Qwen2.5-1.5B-Instruct --name qwen15-v4 --epochs 2 --batch 8 --accum 2 --val-sample 200 --checkpoint-every 100 --precision auto 2>&1 | grep -v Warning
# Команды через «!» не останавливают блокнот при ошибке — проверяем результат сами,
# иначе он пойдёт дальше впустую (так и было в первом запуске).
assert os.path.exists('runs/qwen15-v4/adapter/adapter_model.safetensors'), 'обучение Qwen не удалось — см. вывод выше'
"""),
    code("""
# 6. Экзамены Qwen v4 сразу здесь (напрямую, без Ollama): А, Б, «Новое» и dev (для порога уверенности)
for s in ['test', 'test_b', 'new', 'dev']:
    !python eval_local.py --name qwen15-v4 --set {s} 2>&1 | tail -14
"""),
    code("""
# 7. «Фауст-мини» с нуля — на видеокарте это минуты
!python -u mini/train_mini.py --name mini-v1-kaggle --gpu --epochs 40
!python mini/eval_mini.py --name mini-v1-kaggle 2>&1 | grep -E '^==|^ 0.9|^ 0.0'
"""),
    code("""
# 8. Всё нужное — в один архив: заплатки LoRA, журналы обучения, мини-модель, отчёты экзаменов.
#    Базовую модель не кладём (её скачаем на ПК сами), контрольные точки тоже.
import zipfile
out = '/kaggle/working/faust-v4-results.zip'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root in ['runs/qwen15-v4', 'runs/mini-v1-kaggle', '../jarvis-relay/eval/results']:
        for dirpath, _, files in os.walk(root):
            if 'checkpoint' in dirpath:
                continue
            for f in files:
                p = os.path.join(dirpath, f)
                z.write(p, arcname=os.path.relpath(p, '/kaggle/working/faust'))
print(out, round(os.path.getsize(out) / 2**20, 1), 'МБ')
"""),
]


def build_notebook(path):
    nb = {"cells": CELLS, "nbformat": 4, "nbformat_minor": 5,
          "metadata": {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
                       "language_info": {"name": "python"}}}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(nb, f, ensure_ascii=False, indent=1)
    return path


if __name__ == "__main__":
    z = build_zip(os.path.join(HERE, "faust-ml-v4.zip"))
    n = build_notebook(os.path.join(HERE, "faust-train-v4.ipynb"))
    print(z, round(os.path.getsize(z) / 2 ** 20, 2), "МБ")
    print(n)
