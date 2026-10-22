# jarvis-ml — своя модель разбора фраз «Фауста»

Здесь всё, что нужно, чтобы обучить, проверить и включить собственную языковую
модель, которая превращает фразу с телефона («сделай потише», «скачай отчёт и
отправь Пете») в команду протокола.

Модель — открытая Qwen2.5 (0,5 или 1,5 млрд параметров), дообученная методом
LoRA на собственном датасете. Работает локально через Ollama: без интернета,
без ключа, без оплаты. Если она не уверена в ответе, фраза уходит в Gemini
(см. `HybridIntentParser.java` в релее).

## Файлы

| Файл | Что делает |
|---|---|
| `common.py` | Системная подсказка и формат ответа модели — общее для всех скриптов |
| `vocab.py` | Словари: сайты, программы, папки, процессы и как их называют люди |
| `build_trainset.py` | Собирает обучающий набор: шаблоны + трудные пары + перефразировки Gemini + живые фразы + уроки |
| `distill.py` | Дистилляция: Gemini придумывает живые фразы и сам размечает; 85% — в обучение, 15% — в экзамен Б |
| `train.py` | Дообучение (LoRA) |
| `eval_local.py` | Экзамен обученной модели напрямую, без релея и Ollama |
| `export_ollama.py` | Вливает LoRA в модель, переводит в GGUF и ставит в Ollama под именем `faust-intent` |
| `eval_ollama.py` | Экзамен модели, уже стоящей в Ollama: настоящая точность и скорость |
| `llama.cpp/` | Только конвертер в GGUF и его библиотека (не сама программа) |
| `retrain.py` | Самообучение: уроки из релея → новая версия → экзамен → включить, если не хуже |
| `data/` | `train.jsonl`, `val.jsonl`, `dev.jsonl`, `distill.jsonl`, `lessons.jsonl`, кэши ответов Gemini; `v1/` — набор первой версии |
| `models/` | Скачанные базовые модели Qwen2.5 |
| `runs/<имя>/` | Результат обучения: заплатки LoRA, кривая обучения, слитая модель, Modelfile |

Экзаменов два, оба лежат отдельно — в `jarvis-relay/eval/`:
`intent_test.jsonl` (А, 250 фраз, писал автор) и `intent_test_b.jsonl` (Б, 201 живая
фраза, писал и размечал Gemini). В обучение они не попадают: `build_trainset.py`
выбрасывает любую фразу, совпадающую с экзаменационной с точностью до вежливых слов.

## Полный цикл

Все команды — из этой папки, Python из окружения `.venv`:

```
.venv\Scripts\python.exe distill.py                  живые фразы от Gemini (платные запросы; ответы кэшируются)
.venv\Scripts\python.exe build_trainset.py
.venv\Scripts\python.exe train.py --base models\Qwen2.5-0.5B-Instruct --name qwen05-v2 --cool-ms 250
.venv\Scripts\python.exe export_ollama.py --name qwen05-v2 --tag faust-intent-v2
python eval_ollama.py --model faust-intent-v2             экзамен А
python eval_ollama.py --model faust-intent-v2 --set b     экзамен Б
python eval_ollama.py --model faust-intent-v2 --set dev   отложенные фразы — по ним подбирается порог
ollama cp faust-intent-v2 faust-intent                    включить новую версию в работу
```

Обучение 0.5B на 17 тысячах примеров — 65 минут на RTX 3050 Ti при `--cool-ms 250`
(пауза после каждого шага), видеокарта около 80 °C; без пауз — быстрее, но до 86 °C.
Результат сохраняется после каждого прохода, так что обучение можно остановить.

`distill.py` и `build_trainset.py` ходят в Gemini через релей. Сколько платных запросов
можно сделать за запуск, задаёт `distill.py --max-requests` (0 — только пересчёт по кэшу);
`build_trainset.py` спрашивает учителя только о том, чего нет в кэше.

## Текущий результат (19.10.2026, qwen15-v1 = faust-intent-v3)

| | Экзамен А | Экзамен Б | Медиана задержки |
|---|---|---|---|
| Регулярки | 39,6% | — | 0 мс |
| Gemini, новый промпт | 100% | эталон | 2013 мс |
| Своя модель, версия 1 (06.10) | 97,6% | 77,1% | 131 мс |
| Своя модель, версия 2 | 97,2% | 89,1% | 133 мс |
| Вся связка: версия 2, при сомнении Gemini | 100% | 96,5% (расчёт) | 140 мс |
| Своя модель, версия 3 (1.5B, тот же набор, 19.10) | 98,0% | 93,0% (без учёта scope 97,0%) | 171–184 мс |

В работе `faust-intent` = `faust-intent-v3`. Прежние версии лежат в Ollama как `faust-intent-v2` и `faust-intent-v1`;
откат на v2 — `ollama cp faust-intent-v2 faust-intent` (порог 0.9 тот же), на v1 — ещё и порог 0.7 в `JARVIS_LOCAL_LLM_MIN_CONFIDENCE`.

Обучение 1.5B (~1 ч 50 мин, 3,5 ГБ видеопамяти, карта до 87 °C):
`train.py --base models\Qwen2.5-1.5B-Instruct --name qwen15-v1 --grad-ckpt --epochs 2 --cool-ms 800 --resume`
(готовый запуск — `train-qwen15.cmd`). Каждые 100 шагов пишется контрольная точка; оборвалось — тот же запуск продолжит.
Выгрузка: `export_ollama.py --name qwen15-v1 --tag faust-intent-v3 --outtype q8_0`.

Подробности и оговорки — `jarvis/docs/объяснение/5-Своя-модель.md`.

Замер всей связки через релей — из `jarvis-relay/eval`:

```
python intent_eval.py --engine local     только своя модель
python intent_eval.py --engine system    связка: своя модель → Gemini, плюс запрет опасных целей
python intent_eval.py --engine gemini    только Gemini
python intent_eval.py --engine stub      только регулярные выражения
```

## Настройки релея

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `JARVIS_INTENT_ENGINE` | `auto` | `auto` — своя модель, потом Gemini; `local` — без интернета; `gemini` — как раньше |
| `JARVIS_LOCAL_LLM_URL` | `http://host.docker.internal:11434` | Где Ollama; пусто — своя модель выключена |
| `JARVIS_LOCAL_LLM_MODEL` | `faust-intent` | Имя модели в Ollama |
| `JARVIS_LOCAL_LLM_MIN_CONFIDENCE` | `0.9` | Ниже этой уверенности ответ перепроверяет Gemini |
| `JARVIS_FORBIDDEN_GOALS` | `true` | Запрет опасных целей для агента |

## Окружение

Python 3.11, PyTorch 2.6 (CUDA 12.4), transformers, peft. Создаётся так:

```
py -3.11 -m venv .venv
.venv\Scripts\python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cu124
.venv\Scripts\python.exe -m pip install "transformers>=4.46,<5" "peft>=0.13" "datasets>=3" "accelerate>=1.0" sentencepiece protobuf gguf huggingface_hub
```

## Что не хранится в git и как вернуть

В репозиторий не попадают веса (`models/`, `runs/<имя>/`) и конвертер `llama.cpp/` —
это гигабайты, которые можно получить заново. Логи и отчёты обучения (`runs/*.log`,
`runs/*.md`, `runs/current.json`) и все наборы `data/` — в git: это цифры для диплома.

```
.venv\Scripts\python.exe -c "from huggingface_hub import snapshot_download as d; [d(f'Qwen/{m}', local_dir=f'models/{m}') for m in ('Qwen2.5-0.5B-Instruct','Qwen2.5-1.5B-Instruct')]"
git clone --filter=blob:none --sparse https://github.com/ggml-org/llama.cpp
git -C llama.cpp sparse-checkout set conversion gguf-py
```
Использованная версия llama.cpp — коммит `5e03bdd`. Готовые модели живут в Ollama
(`faust-intent`, `faust-intent-v1/v2/v3`): `ollama list`.
