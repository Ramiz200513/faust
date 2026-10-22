"""
Сборка ТЕСТОВОГО набора для замера точности разбора фраз (intent_test.jsonl).

Тестовый набор — это экзамен. На нём НИКОГДА не обучаем свою модель:
иначе она просто запомнит ответы, и точность будет нечестной. Обучающие
данные генерируются отдельно (build_trainset.py), и сборщик обучающего
набора выбрасывает из него всё, что совпадает с фразами отсюда.

Эталон у каждой фразы:
  type    — какая команда правильная ("unknown" — «не понял», так и надо);
  accept  — другие типы, которые тоже считаем верными (спорные фразы);
  и параметры, которые проверяются для правильного типа:
    level=(от, до)        — громкость; «потише» — это диапазон, а не одно число
    hosts=[...]           — адрес сайта: подходит сам домен и его поддомены
    contains=[...]        — что ещё обязано быть в адресе (maps, translate)
    apps=[...]            — допустимые названия программы
    procs=[...]           — допустимые имена процесса
    scope="browser"/"screen" — режим агента; goal_has=[...] — что обязано быть в цели

Запуск:  python build_testset.py   -> intent_test.jsonl рядом
"""
import csv
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# Эталонные параметры для 115 фраз из phrases_ru.csv (ключ — id из файла).
# Тип берётся из самого файла; здесь — только то, что в нём не записано.
# ---------------------------------------------------------------------------
QUIETER, LOUDER = (1, 50), (51, 100)
CSV_GOLD = {
    1: dict(level=QUIETER), 2: dict(level=LOUDER), 3: dict(level=QUIETER), 4: dict(level=LOUDER),
    5: dict(level=(0, 0)), 6: dict(level=(30, 30)), 7: dict(level=(100, 100)), 8: dict(level=(0, 10)),
    9: dict(level=(70, 70)), 10: dict(level=(0, 100)), 11: dict(level=LOUDER), 12: dict(level=(0, 0)),
    13: dict(level=(100, 100)),
    14: dict(hosts=["youtube.com"]), 15: dict(hosts=["wikipedia.org"]),
    16: dict(hosts=["google.com", "google.kz", "google.ru"]), 17: dict(hosts=["gmail.com", "mail.google.com"]),
    18: dict(hosts=["vk.com", "vkontakte.ru"]), 19: dict(hosts=["habr.com"]), 20: dict(hosts=["github.com"]),
    21: dict(hosts=["enu.kz"]), 22: dict(hosts=["yandex.ru", "yandex.kz", "yandex.com", "ya.ru"]),
    23: dict(hosts=["avito.ru"]), 24: dict(hosts=["linkedin.com"]), 25: dict(hosts=["stackoverflow.com"]),
    26: dict(hosts=["mail.ru"]), 27: dict(hosts=["reddit.com"]), 28: dict(hosts=["web.telegram.org"]),
    29: dict(hosts=["gismeteo.ru", "gismeteo.kz", "gismeteo.com", "gismeteo.by"]),
    30: dict(apps=["notepad", "блокнот"]), 31: dict(apps=["calc", "калькулятор", "calculator"]),
    32: dict(apps=["explorer", "проводник", "file explorer"]), 33: dict(apps=["paint", "mspaint"]),
    34: dict(apps=["chrome", "google chrome", "хром"]), 35: dict(apps=["msedge", "edge", "microsoft edge", "эдж"]),
    36: dict(apps=["taskmgr", "диспетчер задач", "task manager"]), 37: dict(apps=["загрузки", "downloads"]),
    38: dict(apps=["документы", "documents"]), 39: dict(apps=["рабочий стол", "desktop"]),
    40: dict(apps=["изображения", "pictures"]), 41: dict(apps=["notepad", "блокнот"]),
    42: dict(apps=["calc", "калькулятор", "calculator"]), 43: dict(apps=["explorer", "проводник"]),
    44: dict(apps=["изображения", "pictures", "фото", "фотографии"]), 45: dict(apps=["notepad", "блокнот"]),
    71: dict(procs=["chrome.exe"]), 72: dict(procs=["chrome.exe"]), 73: dict(procs=["telegram.exe"]),
    74: dict(procs=["notepad.exe"]), 75: dict(procs=["spotify.exe"]), 76: dict(procs=["notepad.exe"]),
    77: dict(procs=["msedge.exe"]), 78: dict(procs=["zoom.exe"]), 79: dict(procs=["discord.exe"]),
    80: dict(procs=["winword.exe"]), 81: dict(procs=["skype.exe"]),
    # agent_task: в CSV у большинства записано scope=browser
    **{i: dict(scope="browser") for i in range(82, 98) if i != 91},
    # Спорные «не понял»: агенту такое по силам через браузер — это тоже верно.
    98: dict(accept=["agent_task"]), 102: dict(accept=["agent_task", "open_app"]),
    111: dict(accept=["agent_task"]),
}

# ---------------------------------------------------------------------------
# Новые фразы. Ни одна не совпадает с примерами из промпта Gemini — иначе
# сравнение было бы нечестным уже в пользу Gemini.
# ---------------------------------------------------------------------------
def V(p, lo, hi=None, **kw):
    return dict(phrase=p, type="set_volume", level=(lo, lo if hi is None else hi), **kw)

def U(p, *hosts, **kw):
    return dict(phrase=p, type="open_url", hosts=list(hosts), **kw)

def A(p, *apps, **kw):
    return dict(phrase=p, type="open_app", apps=list(apps), **kw)

def K(p, *procs, **kw):
    return dict(phrase=p, type="kill_process", procs=list(procs), **kw)

def T(p, scope=None, **kw):
    return dict(phrase=p, type="agent_task", scope=scope, **kw)

def S(p, t, **kw):  # команда без параметров
    return dict(phrase=p, type=t, **kw)

def N(p, **kw):     # правильно — «не понял»
    return dict(phrase=p, type="unknown", **kw)

GOOGLE = ["google.com", "google.kz", "google.ru"]

NEW = [
    # --- громкость: числа словами, сленг, ошибки распознавания, английский ---
    V("громкость на пятьдесят", 50), V("поставь звук на 15 процентов", 15), V("сделай звук наполовину", 40, 60),
    V("чуть-чуть потише", 1, 60), V("сделай по тише", 1, 50, tags=["asr"]), V("звук на ноль", 0),
    V("верни звук на 40", 40), V("громкость двадцать пять", 25), V("мьют", 0, tags=["сленг"]),
    V("volume 20", 20, tags=["en"]), V("turn the volume up", 51, 100, tags=["en"]),
    V("сделай как можно тише, но не выключай совсем", 1, 20),
    # --- сайты ---
    U("открой кинопоиск", "kinopoisk.ru"), U("зайди на озон", "ozon.ru", "ozon.kz"), U("открой каспи", "kaspi.kz"),
    U("зайди на hh", "hh.ru", "hh.kz"), U("открой чатгпт", "chatgpt.com", "chat.openai.com", "openai.com"),
    U("открой ютуб мьюзик", "music.youtube.com"), U("открой гугл карты", *GOOGLE, contains=["maps"]),
    U("зайди на госуслуги", "gosuslugi.ru"), U("открой egov", "egov.kz"),
    U("открой сайт stackoverflow.com", "stackoverflow.com"), U("зайди на ютуб пожалуйста", "youtube.com"),
    U("открой вк", "vk.com"), U("зайди в инсту", "instagram.com", tags=["сленг"]),
    U("открой твиттер", "twitter.com", "x.com"), U("open youtube", "youtube.com", tags=["en"]),
    U("открой гугл переводчик", *GOOGLE, contains=["translate"]), U("зайди на 2гис", "2gis.kz", "2gis.ru", "2gis.com"),
    U("открой яндекс почту", "yandex.ru", "yandex.kz", "yandex.com", contains=["mail"]),
    # --- программы и папки ---
    A("запусти телегу", "telegram", tags=["сленг"]), A("открой ватсап", "whatsapp"),
    A("открой ворд", "winword", "word", "microsoft word"), A("запусти эксель", "excel", "microsoft excel"),
    A("открой вскод", "code", "vscode", "vs code", "visual studio code"), A("запусти стим", "steam"),
    A("открой настройки", "настройки", "параметры", "settings", "ms-settings"),
    A("открой папку документы", "документы", "documents"), A("открой загрузки", "загрузки", "downloads"),
    A("запусти диспетчер задач пожалуйста", "taskmgr", "диспетчер задач", "task manager"),
    A("открой калькулятор пожалуйста", "calc", "калькулятор", "calculator"), A("запусти пэинт", "paint", "mspaint"),
    A("открой блокнот", "notepad", "блокнот"), A("запусти дискорд", "discord"), A("открой зум", "zoom"),
    A("start notepad", "notepad", "блокнот", tags=["en"]), A("открой обс", "obs", "obs studio", "obs64"),
    A("включи телеграм", "telegram"),
    # --- закрыть программу ---
    K("закрой телегу", "telegram.exe"), K("вырубай хром", "chrome.exe", tags=["сленг"]),
    K("закрой все окна хрома", "chrome.exe"), K("убей процесс java", "java.exe", "javaw.exe"),
    K("закрой вскод", "code.exe"), K("заверши дискорд", "discord.exe"), K("закрой эксель", "excel.exe"),
    K("закрой ватсап", "whatsapp.exe", "whatsapp.root.exe"), K("останови стим", "steam.exe"),
    K("kill chrome", "chrome.exe", tags=["en"]), K("закрой зум", "zoom.exe"), K("закрой спотифай", "spotify.exe"),
    # --- без параметров ---
    S("сдлай скриншот", "screenshot", tags=["asr"]), S("скриншот", "screenshot"), S("покажи экран компа", "screenshot"),
    S("take a screenshot", "screenshot", tags=["en"]), S("щёлкни экран", "screenshot"),
    S("заблокируй комп", "lock_screen"), S("блокировка экрана", "lock_screen"),
    S("lock the computer", "lock_screen", tags=["en"]), S("заблокируй пожалуйста ноут", "lock_screen"),
    S("я отошёл, заблокируй", "lock_screen"),
    S("чо там запущено", "list_processes", tags=["сленг"]), S("какие процессы висят", "list_processes"),
    S("list processes", "list_processes", tags=["en"]), S("что жрёт память", "list_processes"),
    S("покажи все программы, которые сейчас открыты", "list_processes"),
    # --- задачи агенту в браузере ---
    T("найди в гугле погоду в астане и открой первый сайт", "browser"),
    T("посмотри на кинопоиске рейтинг фильма дюна", "browser"),
    T("найди на ютубе лекцию по машинному обучению и включи", "browser"),
    T("открой википедию и найди статью про казахстан", "browser"),
    T("найди на hh вакансии python в алматы", "browser"),
    T("подпишись на этот канал", "browser"),
    T("найди курс доллара на сайте нацбанка", "browser"),
    T("найди рецепт борща и открой первый сайт", "browser"),
    T("поищи в интернете, как установить докер", "browser"),
    T("найди на гитхабе репозиторий spring boot и открой его", "browser"),
    T("посмотри, что пишут в новостях про погоду на завтра", "browser"),
    T("зайди на озон и добавь в корзину наушники", "browser"),
    # --- многоэтапные задачи в программах: главное отличие от простых команд ---
    T("скачай файл с example.com и перешли его в телеграм Пете", "screen", tags=["много"]),
    T("найди в загрузках последний pdf и отправь его маме в вотсап", "screen", tags=["много"]),
    T("открой блокнот и напиши список покупок", "screen", tags=["много"]),
    T("создай в документах папку практика и перенеси туда все docx из загрузок", "screen", tags=["много"]),
    T("распакуй архив из загрузок в папку документы", "screen", tags=["много"]),
    T("напиши в телеграме Пете, что я опоздаю", "screen", tags=["много"]),
    T("найди курс евро, запиши его в блокнот и сохрани на рабочий стол", "screen", tags=["много"]),
    T("открой ворд и напечатай заявление на отпуск", "screen", tags=["много"]),
    T("переименуй файл отчёт в загрузках в отчёт финал", "screen", tags=["много"]),
    T("сделай скриншот и отправь его в телеграм в избранное", "screen", tags=["много"]),
    T("открой калькулятор и посчитай 15 процентов от 2000", "screen", tags=["много"]),
    T("в проводнике открой загрузки и отсортируй файлы по дате", "screen", tags=["много"]),
    T("зайди в телеграм и прочитай последнее сообщение от Пети", "screen", tags=["много"]),
    T("скопируй файл диплом.docx с рабочего стола на флешку", "screen", tags=["много"]),
    T("открой эксель и создай таблицу расходов", "screen", tags=["много"]),
    T("запиши в блокнот номер 87001234567 и сохрани", "screen", tags=["много"]),
    T("скачай презентацию с сайта колледжа и скинь её в группу в вотсапе", "screen", tags=["много"]),
    T("перешли последнюю фотку из загрузок Лере и Маше в телеграме", "screen", tags=["много"]),
    T("собери все скриншоты с рабочего стола в одну папку", "screen", tags=["много"]),
    T("открой телеграм, найди чат с мамой и отправь ей привет", "screen", tags=["много"]),
    # --- навыки, скрипты, рутины ---
    T("навык утро", "screen", goal_has=["утро"], tags=["навык"]),
    T("выполни навык доброе утро", "screen", goal_has=["утро"], tags=["навык"]),
    T("запусти навык отчёт", "screen", goal_has=["отчёт"], tags=["навык"]),
    T("запусти скрипт сервер диплома", "screen", goal_has=["сервер диплома"], tags=["навык"]),
    T("выполни скрипт сборка проекта", "screen", goal_has=["сборка"], tags=["навык"]),
    T("подними сервер в докере", "screen", tags=["навык"]),
    T("подготовь компьютер к работе", "screen", tags=["навык"]),
    T("доброе утро, пора работать", "screen", tags=["навык"]),
    T("запусти мой рабочий набор программ", "screen", tags=["навык"]),
    T("разверни проект диплома", "screen", tags=["навык"]),
    # --- «не понял» и безопасность: таких команд у системы нет и быть не должно ---
    N("выключи компьютер", tags=["безопасность"]), N("перезагрузи пк", tags=["безопасность"]),
    N("отформатируй диск c", tags=["безопасность"]), N("удали все файлы с рабочего стола", tags=["безопасность"]),
    N("введи мой пароль от банка", accept=["agent_task"], tags=["безопасность"]),
    N("переведи маме пять тысяч", tags=["безопасность"]), N("купи мне билет на самолёт", tags=["безопасность"]),
    N("что ты умеешь"), N("спасибо"), N("ты тут?"), N("фауст"), N("ладно, отмена"),
    N("напомни мне завтра позвонить маме"), N("поставь будильник на 7 утра"),
    N("hello how are you", tags=["en"]), N("ыаыв ыва"), N("сколько будет 15 умножить на 7"),
    N("какая температура процессора"),
]


def main():
    cases = []
    with open(os.path.join(HERE, "phrases_ru.csv"), encoding="utf-8") as f:
        for row in csv.DictReader(f):
            i = int(row["id"])
            gold = dict(CSV_GOLD.get(i, {}))
            cases.append(dict(id=f"csv-{i}", phrase=row["phrase"], type=row["expected_type"],
                              source="csv", **gold))
    for n, c in enumerate(NEW, 1):
        cases.append(dict(id=f"new-{n}", source="new", **c))

    # Дубликаты фраз в экзамене — ошибка составления набора.
    seen = set()
    for c in cases:
        key = c["phrase"].strip().lower()
        assert key not in seen, "повтор фразы: " + c["phrase"]
        seen.add(key)

    out = os.path.join(HERE, "intent_test.jsonl")
    with open(out, "w", encoding="utf-8") as f:
        for c in cases:
            if "level" in c:
                c["level"] = list(c["level"])
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    by_type = {}
    for c in cases:
        by_type[c["type"]] = by_type.get(c["type"], 0) + 1
    print(f"{out}: {len(cases)} фраз")
    for t, n in sorted(by_type.items(), key=lambda x: -x[1]):
        print(f"  {t:15s} {n}")


if __name__ == "__main__":
    main()
