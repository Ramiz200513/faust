"""
Голос «Фауста» на нейросети Silero (локально, без интернета).

Зачем отдельный процесс на Python
---------------------------------
Silero — модель PyTorch, из Java её не запустить. Поэтому PcVoice (jarvis-agent) держит
этот скрипт фоновым процессом и пишет ему в stdin по фразе на строку — так же, как раньше
писал PowerShell-скрипту System.Speech. Протокол тот же:
  * при старте скрипт печатает «VOICE silero <голос>» (или «NOVOICE» — тогда PcVoice
    берёт старый голос Windows);
  * каждая строка stdin — фраза; новая фраза ОБРЫВАЕТ предыдущую (человек уже на шаге дальше);
  * пустая строка — замолчать; закрытый stdin — выйти;
  * «#FILE <id> <фраза>» — НЕ говорить, а сохранить WAV (24 кГц) и ответить
    «WAV <id> <путь>» или «WAVERR <id> <причина>». Это голос для ТЕЛЕФОНА (с 23.11):
    телефон просит ПК озвучить фразу, чтобы и он говорил тем же голосом «Фауста».
    24 кГц вместо 48 — вдвое меньше данных по сети, для динамика телефона разницы нет.

Почему так
----------
* Модель грузится ~1 с, а первые фразы считаются по 2–9 с («прогрев» PyTorch); дальше —
  ~0,15 с на фразу. Поэтому PcVoice запускает процесс при старте агента, а скрипт сразу
  после «VOICE» сам прогревается на типовых фразах урока.
* Silero читает только кириллицу: «Win», «Ctrl», «Telegram», «+» и цифры она пропустит или
  исковеркает. to_speech() переводит их в произносимый русский текст.
* Одинаковые фразы («Готово! Вы сделали это сами.») не синтезируются заново — кэш WAV.
* Звук — winsound (входит в Python на Windows): без лишних библиотек.

Запуск (его делает PcVoice): python -I silero_voice.py <путь к v4_ru.pt> [голос]
Лицензия модели Silero — CC BY-NC (некоммерческая): для диплома подходит.
"""
import hashlib
import io
import os
import re
import sys
import tempfile
import wave
import winsound

SAMPLE_RATE = 48000
PHONE_RATE = 24000       # для телефона: вдвое меньше данных, на слух в динамике телефона то же
MAX_CHARS = 800          # у Silero есть предел длины текста; подсказки урока короче

# Как произносить служебные слова. Ключи — в нижнем регистре.
WORDS = {
    "win": "вин", "windows": "виндоус", "ctrl": "контрол", "control": "контрол", "alt": "альт",
    "shift": "шифт", "enter": "энтер", "esc": "эскейп", "escape": "эскейп", "tab": "таб",
    "delete": "делит", "del": "делит", "backspace": "бэкспейс", "space": "пробел",
    "pageup": "пейдж ап", "pagedown": "пейдж даун", "home": "хоум", "end": "энд",
    "telegram": "телеграм", "youtube": "ютуб", "google": "гугл", "chrome": "хром",
    "word": "ворд", "excel": "эксель", "explorer": "эксплорер", "notepad": "нотпад",
    "faust": "фауст", "ok": "окей", "wi-fi": "вай фай", "wifi": "вай фай", "bluetooth": "блютус",
    "pdf": "пэ дэ эф", "usb": "ю эс би", "ai": "эй ай",
}
# Буквы латиницы для слов, которых нет в WORDS (грубо, но лучше, чем тишина).
DIGRAPHS = [("sh", "ш"), ("ch", "ч"), ("th", "т"), ("ph", "ф"), ("oo", "у"), ("ee", "и"), ("ck", "к")]
LETTERS = dict(zip("abcdefghijklmnopqrstuvwxyz",
                   ["а", "б", "к", "д", "е", "ф", "г", "х", "и", "дж", "к", "л", "м",
                    "н", "о", "п", "к", "р", "с", "т", "у", "в", "в", "кс", "й", "з"]))

UNITS = ["ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
         "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
         "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"]
TENS = ["", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят",
        "восемьдесят", "девяносто"]
HUNDREDS = ["", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот",
            "восемьсот", "девятьсот"]


def number_words(n):
    """0–999 словами; больше — по цифрам (в подсказках урока длинных чисел почти не бывает)."""
    if n < 20:
        return UNITS[n]
    if n < 100:
        return (TENS[n // 10] + ("" if n % 10 == 0 else " " + UNITS[n % 10])).strip()
    if n < 1000:
        rest = n % 100
        return (HUNDREDS[n // 100] + ("" if rest == 0 else " " + number_words(rest))).strip()
    return " ".join(UNITS[int(d)] for d in str(n))


# Одиночная буква — по названию, как её говорят про клавиши: «Ctrl+S» — «контрол и эс».
LETTER_NAMES = dict(zip("abcdefghijklmnopqrstuvwxyz",
                        ["эй", "би", "си", "ди", "и", "эф", "джи", "эйч", "ай", "джей", "кей", "эл", "эм",
                         "эн", "оу", "пи", "кью", "ар", "эс", "ти", "ю", "ви", "дабл ю", "экс", "уай", "зет"]))


def latin_word(word):
    low = word.lower()
    if low in WORDS:
        return WORDS[low]
    m = re.fullmatch(r"f(\d{1,2})", low)            # F1–F12
    if m:
        return "эф " + number_words(int(m.group(1)))
    if len(low) == 1 and low in LETTER_NAMES:
        return LETTER_NAMES[low]
    for a, b in DIGRAPHS:
        low = low.replace(a, b)
    out = "".join(LETTERS.get(ch, ch) for ch in low)
    return re.sub(r"(\d+)", r" \1 ", out)            # «mp3» -> «мп 3» (число прочтётся словом)


def to_speech(text):
    """Подсказка урока -> текст, который Silero прочтёт: только кириллица, цифры словами."""
    t = text.replace("«", "").replace("»", "").replace('"', "").replace("…", ".")
    t = re.sub(r"\s*\+\s*", " и ", t)                # «Win+плюс» -> «Win и плюс»
    t = re.sub(r"[A-Za-z][A-Za-z0-9\-]*", lambda m: latin_word(m.group(0)), t)
    t = re.sub(r"\d+", lambda m: number_words(int(m.group(0))), t)
    t = re.sub(r"[^А-Яа-яЁё0-9 .,!?:;\-—()]", " ", t)  # прочие значки Silero не нужны
    t = re.sub(r"\s+", " ", t).strip()
    return t[:MAX_CHARS]


class Voice:
    def __init__(self, model_path, speaker):
        import torch                                  # импорт здесь: to_speech тестируется без PyTorch
        torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
        self.torch = torch
        self.model = torch.package.PackageImporter(model_path).load_pickle("tts_models", "model")
        self.model.to(torch.device("cpu"))
        self.speaker = speaker
        self.dir = tempfile.mkdtemp(prefix="faust-voice-")
        self.cache = {}

    def wav_for(self, text, rate=SAMPLE_RATE):
        """Путь к WAV фразы (из кэша или новый синтез); None — произносить нечего."""
        spoken = to_speech(text)
        if not re.search(r"[А-Яа-яЁё]", spoken):
            return None
        key = (spoken, rate)
        if key in self.cache:
            return self.cache[key]
        audio = self.model.apply_tts(text=spoken, speaker=self.speaker, sample_rate=rate)
        pcm = (audio.clamp(-1, 1) * 32767).to(self.torch.int16).numpy().tobytes()
        name = hashlib.sha1(("%d:%s" % (rate, spoken)).encode("utf-8")).hexdigest()[:16] + ".wav"
        path = os.path.join(self.dir, name)
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(pcm)
        self.cache[key] = path
        return path


def main():
    model_path = sys.argv[1]
    speaker = sys.argv[2] if len(sys.argv) > 2 else "eugene"
    stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")
    try:
        voice = Voice(model_path, speaker)
        if speaker not in voice.model.speakers:
            raise ValueError("нет голоса " + speaker)
    except Exception as e:                              # нет модели, PyTorch, голоса — PcVoice возьмёт старый
        print("NOVOICE", flush=True)
        print("silero: " + str(e), file=sys.stderr, flush=True)
        return
    print("VOICE silero " + speaker, flush=True)

    # Прогрев на типовых фразах урока: первая настоящая подсказка прозвучит без задержки.
    for warm in ("Нажмите на подсвеченное.", "Готово! Вы сделали это сами.",
                 "Не сюда. Нажмите на подсвеченное.", "Нажмите вин и плюс."):
        try:
            voice.wav_for(warm)
        except Exception as e:
            print("silero прогрев: " + str(e), file=sys.stderr, flush=True)

    for line in stdin:
        text = line.strip()
        if text.startswith("#FILE "):
            # Голос для телефона: сохранить, не играть. Ответ — одной строкой в stdout.
            parts = text.split(" ", 2)
            req = parts[1] if len(parts) > 1 else "?"
            try:
                path = voice.wav_for(parts[2] if len(parts) > 2 else "", PHONE_RATE)
                print(("WAV %s %s" % (req, path)) if path else ("WAVERR %s нечего произносить" % req), flush=True)
            except Exception as e:
                print("WAVERR %s %s" % (req, str(e).replace("\n", " ")[:200]), flush=True)
            continue
        if not text:
            winsound.PlaySound(None, 0)                 # пустая строка — замолчать
            continue
        try:
            path = voice.wav_for(text)
            if path:
                # SND_ASYNC: не ждём конца; новый PlaySound обрывает прежний звук.
                winsound.PlaySound(path, winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_NODEFAULT)
        except Exception as e:
            print("silero: " + str(e), file=sys.stderr, flush=True)
    winsound.PlaySound(None, 0)


if __name__ == "__main__":
    main()
