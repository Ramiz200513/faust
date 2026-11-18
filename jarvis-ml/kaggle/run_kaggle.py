"""
Обучение на Kaggle без браузера — через официальный Kaggle API.

  python kaggle/run_kaggle.py whoami     проверить вход (печатает только имя пользователя)
  python kaggle/run_kaggle.py push       собрать пакет, загрузить датасет, запустить блокнот на видеокарте
  python kaggle/run_kaggle.py status     идёт / готово / ошибка
  python kaggle/run_kaggle.py log        хвост журнала блокнота
  python kaggle/run_kaggle.py pull       скачать результат и разложить: runs/qwen15-v4, runs/mini-v1-kaggle,
                                         отчёты экзаменов — в jarvis-relay/eval/results

Вход: ключ API, который человек сам создал на kaggle.com (Settings -> API) и сам
положил в %USERPROFILE%\\.kaggle\\ (kaggle.json или access_token). Скрипт ключ не
читает и не печатает — его читает сама библиотека Kaggle.

Что уходит на Kaggle: только архив из make_package.py (код, обучающий набор,
экзамены) — приватным датасетом, и блокнот — приватным. Ни ключей, ни настроек
ПК там нет.
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ML = os.path.dirname(HERE)
REPO = os.path.dirname(ML)
KAGGLE = os.path.join(os.path.dirname(sys.executable), "kaggle.exe" if os.name == "nt" else "kaggle")
DATASET_SLUG = "faust-ml-v4"
KERNEL_SLUG = "faust-train-v4"


def run(*args, check=True):
    p = subprocess.run([KAGGLE, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (p.stdout + p.stderr).strip()
    if check and p.returncode != 0:
        sys.exit(f"kaggle {' '.join(args)}: {out}")
    return out


def username():
    """Имя пользователя из настроек библиотеки Kaggle (ключ при этом не печатается)."""
    from kaggle.api.kaggle_api_extended import KaggleApi
    api = KaggleApi()
    api.authenticate()
    name = api.config_values.get("username") or api.get_config_value("username")
    if not name:
        sys.exit("Kaggle не знает имя пользователя: ключ создан, но имени в настройках нет")
    return name


def push():
    user = username()
    subprocess.run([sys.executable, os.path.join(HERE, "make_package.py")], check=True)

    # --- датасет: архив + описание ---
    ds = os.path.join(HERE, "upload", "dataset")
    shutil.rmtree(ds, ignore_errors=True)
    os.makedirs(ds)
    shutil.copy(os.path.join(HERE, "faust-ml-v4.zip"), ds)
    with open(os.path.join(ds, "dataset-metadata.json"), "w", encoding="utf-8") as f:
        json.dump({"title": DATASET_SLUG, "id": f"{user}/{DATASET_SLUG}", "licenses": [{"name": "other"}]}, f)
    exists = DATASET_SLUG in run("datasets", "list", "--mine", "--search", DATASET_SLUG, check=False)
    if exists:
        print(run("datasets", "version", "-p", ds, "-m", "новые данные", "--dir-mode", "zip"))
    else:
        print(run("datasets", "create", "-p", ds, "--dir-mode", "zip"))   # приватный по умолчанию

    # --- блокнот: на видеокарте, с интернетом (скачать Qwen), приватный ---
    kd = os.path.join(HERE, "upload", "kernel")
    shutil.rmtree(kd, ignore_errors=True)
    os.makedirs(kd)
    shutil.copy(os.path.join(HERE, "faust-train-v4.ipynb"), kd)
    with open(os.path.join(kd, "kernel-metadata.json"), "w", encoding="utf-8") as f:
        json.dump({"id": f"{user}/{KERNEL_SLUG}", "title": KERNEL_SLUG, "code_file": "faust-train-v4.ipynb",
                   "language": "python", "kernel_type": "notebook", "is_private": True,
                   "enable_gpu": True, "enable_internet": True,
                   "dataset_sources": [f"{user}/{DATASET_SLUG}"], "competition_sources": [], "kernel_sources": []},
                  f, ensure_ascii=False)
    print(run("kernels", "push", "-p", kd))
    print(f"запущено: https://www.kaggle.com/code/{user}/{KERNEL_SLUG}")


def status():
    print(run("kernels", "status", f"{username()}/{KERNEL_SLUG}"))


def pull(log_only=False, skip_download=False):
    out = os.path.join(HERE, "output")
    if not skip_download:
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        # Только архив с результатом и журнал. Без фильтра Kaggle отдаёт ВСЮ рабочую
        # папку блокнота — в первом запуске 13.11 там лежала и базовая Qwen (~3 ГБ).
        print(run("kernels", "output", f"{username()}/{KERNEL_SLUG}", "-p", out,
                  "--file-pattern", r"(faust-v4-results\.zip|.*\.log)$"))
    logs = [f for f in os.listdir(out) if f.endswith(".log")]
    if log_only:
        for f in logs:
            text = open(os.path.join(out, f), encoding="utf-8", errors="replace").read()
            print(text[-4000:])
        return
    res = os.path.join(out, "faust-v4-results.zip")
    if not os.path.exists(res):
        sys.exit("архива с результатом нет — блокнот ещё идёт или упал (см. log)")
    # В архиве пути вида jarvis-ml/runs/... и jarvis-relay/eval/results/... — от корня репозитория.
    with zipfile.ZipFile(res) as z:
        for name in z.namelist():
            if not name.startswith(("jarvis-ml/runs/", "jarvis-relay/eval/results/")) or ".." in name:
                continue                                   # чужие пути не распаковываем
            z.extract(name, REPO)
    print("разложено: jarvis-ml/runs/qwen15-v4, jarvis-ml/runs/mini-v1-kaggle, jarvis-relay/eval/results")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "whoami":
        print("вход в Kaggle есть, пользователь:", username())
    elif cmd == "push":
        push()
    elif cmd == "status":
        status()
    elif cmd == "log":
        pull(log_only=True)
    elif cmd == "pull":
        pull(skip_download="--no-download" in sys.argv)   # --no-download: архив уже скачан, только разложить
    else:
        print(__doc__)
