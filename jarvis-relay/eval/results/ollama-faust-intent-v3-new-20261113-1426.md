# Разбор фраз: своя модель faust-intent-v3 в Ollama (new), 2026-11-13 14:26

Фраз: 53. Сбоев связи/квоты (после повторов): 0.

| Метрика | Значение |
|---|---|
| Точность типа | 32.1% |
| Точность команды (тип + параметры) | 30.2% |
| Задержка, медиана | 184 мс |
| Задержка, 90-й перцентиль | 200 мс |

## По типам команд

| Тип | Фраз | Тип верно | Команда верно |
|---|---|---|---|
| agent_task | 11 | 100.0% | 90.9% |
| open_app | 1 | 100.0% | 100.0% |
| quick_action | 34 | 0.0% | 0.0% |
| set_volume | 4 | 100.0% | 100.0% |
| unknown | 3 | 33.3% | 33.3% |

## Ошибки

- `n-01` «поставь видос на паузу» — тип agent_task, ждали quick_action
- `n-02` «тормозни музыку» — тип agent_task, ждали quick_action
- `n-03` «стопни трек на минутку» — тип agent_task, ждали quick_action
- `n-04` «Фауст, пауза в плеере» — тип agent_task, ждали quick_action
- `n-05` «останови воспроизведение, мне звонят» — тип agent_task, ждали quick_action
- `n-06` «верни музыку, сними с паузы» — тип agent_task, ждали quick_action
- `n-07` «pause it» — тип agent_task, ждали quick_action
- `n-08` «нажми паузу» — тип agent_task, ждали quick_action
- `n-09` «давай дальше смотреть, включи воспроизведение» — тип agent_task, ждали quick_action
- `n-10` «следующую песню давай» — тип agent_task, ждали quick_action
- `n-11` «эта песня надоела, переключи» — тип agent_task, ждали quick_action
- `n-12` «скипни это» — тип unknown, ждали quick_action
- `n-13` «дальше трек» — тип agent_task, ждали quick_action
- `n-14` «включи следующую композицию в плейлисте» — тип agent_task, ждали quick_action
- `n-15` «skip this song» — тип agent_task, ждали quick_action
- `n-16` «верни прошлую песню» — тип agent_task, ждали quick_action
- `n-17` «назад на предыдущий трек» — тип agent_task, ждали quick_action
- `n-18` «включи ещё раз ту песню, что была до этой» — тип agent_task, ждали quick_action
- `n-19` «previous song please» — тип agent_task, ждали quick_action
- `n-20` «сверни всё» — тип agent_task, ждали quick_action
- `n-21` «убери все окна с экрана» — тип agent_task, ждали quick_action
- `n-22` «покажи мне рабочий стол» — тип open_app, ждали quick_action
- `n-23` «спрячь все программы, кто-то идёт» — тип agent_task, ждали quick_action
- `n-24` «минимизируй окна» — тип agent_task, ждали quick_action
- `n-25` «прибавь звук на 10» — тип set_volume, ждали quick_action
- `n-26` «сделай громче на пятнадцать процентов» — тип set_volume, ждали quick_action
- `n-27` «чуть громче, процентов на пять» — тип set_volume, ждали quick_action
- `n-28` «добавь громкости на 20» — тип set_volume, ждали quick_action
- `n-29` «подкрути звук вверх на 30» — тип set_volume, ждали quick_action
- `n-30` «убавь звук на 10» — тип set_volume, ждали quick_action
- `n-31` «сделай тише на двадцать» — тип set_volume, ждали quick_action
- `n-32` «приглуши на 15 процентов» — тип set_volume, ждали quick_action
- `n-33` «понизь громкость на 5» — тип set_volume, ждали quick_action
- `n-34` «минус десять к громкости» — тип set_volume, ждали quick_action
- `n-43` «перемотай видео на минуту назад» — scope=browser, ждали screen
- `n-52` «переключи канал на телевизоре» — тип agent_task, ждали unknown
- `n-53` «сделай погромче радио в машине» — тип set_volume, ждали unknown
