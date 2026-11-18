"""
«Фауст-мини» — своя нейросеть разбора фраз, обученная С НУЛЯ.

Чем отличается от модели на Qwen
--------------------------------
Qwen2.5 + LoRA — это чужая большая модель (1,5 млрд чисел), которая уже знает
язык; мы лишь дообучаем к ней «заплатки». Здесь всё своё: архитектура, словарь,
веса. Модель начинает со случайных чисел и учится только на наших ~19 тысячах
фраз. Поэтому она маленькая (около полумиллиона чисел, ~2 МБ) и быстрая, но
языка «вообще» не знает: на непривычных формулировках ошибается чаще — тогда
её неуверенность отправляет фразу дальше по цепочке (Qwen, Gemini).

Задача — та же, что у Qwen: фраза -> одна команда. Но не текстом, слово за
словом, а как в классической постановке «распознавание намерения + заполнение
слотов» (intent detection + slot filling):
  * голова intent  — какой это тип команды (16 вариантов, см. INTENTS);
  * голова level   — уровень громкости 0..100 (для set_volume) или величина
                     сдвига 1..100 (для «громче/тише на N»);
  * головы url / app / proc — какой сайт, программа или процесс, из закрытого
                     списка значений, встреченных в обучении.
Ответ собирается в тот же JSON, что пишет Qwen ({"type":"set_volume","level":30}),
поэтому релей и экзамены принимают его без переделок.

Устройство сети (всё — стандартные слои PyTorch, обучаются с нуля)
-----------------------------------------------------------------
  буквы фразы -> вложения букв (48) -> две свёртки (видят 3–5 букв подряд:
  кусочки слов, опечатки не мешают) -> двунаправленная GRU (видит фразу целиком,
  в обе стороны) -> «внимание» + максимум по позициям (вектор всей фразы) -> головы.

Почему по буквам, а не по словам: фразы приходят из распознавания речи с
опечатками («сдеай», «телегу», «тормозни»). Словарь слов на 19 тысячах фраз
был бы дырявым; буквы покрывают любое слово.
"""
import json

import torch
import torch.nn as nn

MAX_LEN = 160

# Типы ответа. quick_action разложен на действия (и громкость — на «вверх/вниз»),
# чтобы знак сдвига громкости решала голова intent, а величину — голова level.
INTENTS = [
    "unknown", "agent_browser", "agent_screen",
    "set_volume", "open_url", "open_app", "kill_process",
    "list_processes", "lock_screen", "screenshot",
    "qa_play_pause", "qa_next_track", "qa_prev_track", "qa_minimize_all", "qa_volume_up", "qa_volume_down",
]
INTENT_ID = {n: i for i, n in enumerate(INTENTS)}
# Какая голова-слот нужна каждому типу (None — слотов нет).
SLOT_OF = {"set_volume": "level", "qa_volume_up": "level", "qa_volume_down": "level",
           "open_url": "url", "open_app": "app", "kill_process": "proc"}
SLOT_FIELD = {"url": "url", "app": "appName", "proc": "processName"}


def norm_text(s):
    """Вид фразы для сети: нижний регистр, ё->е, одиночные пробелы, не длиннее MAX_LEN."""
    s = " ".join((s or "").lower().replace("ё", "е").split())
    return s[:MAX_LEN]


def target_to_labels(target_json):
    """
    Ответ модели из обучающего набора -> метки голов:
    (intent, level или -1, слот-значение или None).
    """
    t = json.loads(target_json)
    typ = t["type"]
    if typ == "agent_task":
        return INTENT_ID["agent_" + t["scope"]], -1, None
    if typ == "quick_action":
        a = t["action"]
        if a == "volume_delta":
            n = t["amount"]
            return INTENT_ID["qa_volume_up" if n > 0 else "qa_volume_down"], abs(n), None
        return INTENT_ID["qa_" + a], -1, None
    if typ == "set_volume":
        return INTENT_ID["set_volume"], t["level"], None
    if typ == "open_url":
        return INTENT_ID["open_url"], -1, t["url"]
    if typ == "open_app":
        return INTENT_ID["open_app"], -1, t["appName"]
    if typ == "kill_process":
        return INTENT_ID["kill_process"], -1, t["processName"]
    return INTENT_ID[typ], -1, None


class Vocab:
    """Словари: буквы фразы и значения слотов (что было в обучении)."""

    def __init__(self, chars, values):
        self.chars = chars                           # список букв; 0 — заполнитель, 1 — незнакомая
        self.char_id = {c: i for i, c in enumerate(chars)}
        self.values = values                         # {"url": [...], "app": [...], "proc": [...]}
        self.value_id = {k: {v: i for i, v in enumerate(vs)} for k, vs in values.items()}

    @staticmethod
    def build(rows):
        chars = {}
        values = {"url": set(), "app": set(), "proc": set()}
        for r in rows:
            for c in norm_text(r["phrase"]):
                chars[c] = chars.get(c, 0) + 1
            t = json.loads(r["target"])
            for slot, field in SLOT_FIELD.items():
                if field in t:
                    values[slot].add(t[field])
        # Редкие буквы (встретились 1–2 раза) — как «незнакомая»: иначе их вложения не обучатся.
        kept = sorted(c for c, n in chars.items() if n >= 3)
        return Vocab(["<pad>", "<unk>"] + kept, {k: sorted(v) for k, v in values.items()})

    def encode(self, text):
        return [self.char_id.get(c, 1) for c in norm_text(text)] or [1]

    def to_json(self):
        return {"chars": self.chars, "values": self.values}

    @staticmethod
    def from_json(d):
        return Vocab(d["chars"], d["values"])


class FaustMini(nn.Module):
    def __init__(self, n_chars, n_values, emb=48, conv=128, rnn=96, dropout=0.3):
        super().__init__()
        self.emb = nn.Embedding(n_chars, emb, padding_idx=0)
        self.conv1 = nn.Conv1d(emb, conv, kernel_size=3, padding=1)
        self.conv2 = nn.Conv1d(conv, conv, kernel_size=5, padding=2)
        self.rnn = nn.GRU(conv, rnn, batch_first=True, bidirectional=True)
        self.att = nn.Linear(2 * rnn, 1)
        self.drop = nn.Dropout(dropout)
        feat = 4 * rnn                                   # «внимание» + максимум
        self.intent = nn.Linear(feat, len(INTENTS))
        self.level = nn.Linear(feat, 101)
        self.slots = nn.ModuleDict({k: nn.Linear(feat, max(1, n)) for k, n in n_values.items()})

    def forward(self, ids):
        mask = ids != 0                                   # где настоящие буквы, а где заполнитель
        x = self.emb(ids).transpose(1, 2)                 # (порция, вложение, длина) — так ждут свёртки
        x = torch.relu(self.conv1(x))
        x = x + torch.relu(self.conv2(x))                 # «остаточная» связь: учится легче
        x = self.drop(x.transpose(1, 2))
        h, _ = self.rnn(x)                                # (порция, длина, 2*rnn)
        score = self.att(h).squeeze(-1).masked_fill(~mask, -1e4)
        w = torch.softmax(score, dim=1).unsqueeze(-1)
        pooled_att = (w * h).sum(dim=1)
        pooled_max = h.masked_fill(~mask.unsqueeze(-1), -1e4).max(dim=1).values
        f = self.drop(torch.cat([pooled_att, pooled_max], dim=-1))
        return {"intent": self.intent(f), "level": self.level(f), **{k: m(f) for k, m in self.slots.items()}}


def pad_batch(seqs):
    n = max(len(s) for s in seqs)
    return torch.tensor([s + [0] * (n - len(s)) for s in seqs], dtype=torch.long)


@torch.no_grad()
def predict(model, vocab, phrases):
    """
    Фразы -> [(ответ JSON-строкой как у Qwen, уверенность 0..1)].
    Уверенность — произведение вероятностей выбранного типа и выбранного слота:
    тот же смысл, что у Qwen (вероятность ответа целиком), и тот же порог в релее.
    """
    model.eval()
    out = model(pad_batch([vocab.encode(p) for p in phrases]))
    probs = {k: torch.softmax(v, dim=-1) for k, v in out.items()}
    res = []
    for i in range(len(phrases)):
        pi = probs["intent"][i]
        k = int(pi.argmax())
        conf = float(pi[k])
        name = INTENTS[k]
        slot = SLOT_OF.get(name)
        if slot == "level":
            pl = probs["level"][i]
            if name != "set_volume":
                pl = pl.clone()
                pl[0] = 0                                 # сдвиг на 0 не бывает
            lvl = int(pl.argmax())
            conf *= float(pl[lvl] / pl.sum())
        elif slot:
            ps = probs[slot][i]
            j = int(ps.argmax())
            conf *= float(ps[j])
            value = vocab.values[slot][j]
        if name.startswith("agent_"):
            raw = {"type": "agent_task", "scope": name[6:]}
        elif name == "qa_volume_up":
            raw = {"type": "quick_action", "action": "volume_delta", "amount": lvl}
        elif name == "qa_volume_down":
            raw = {"type": "quick_action", "action": "volume_delta", "amount": -lvl}
        elif name.startswith("qa_"):
            raw = {"type": "quick_action", "action": name[3:]}
        elif name == "set_volume":
            raw = {"type": "set_volume", "level": lvl}
        elif slot in SLOT_FIELD:
            raw = {"type": name, SLOT_FIELD[slot]: value}
        else:
            raw = {"type": name}
        res.append((json.dumps(raw, ensure_ascii=False, separators=(",", ":")), conf))
    return res


def save(path, model, vocab, meta):
    torch.save({"state": model.state_dict(), "vocab": vocab.to_json(), "meta": meta}, path)


def load(path):
    d = torch.load(path, map_location="cpu", weights_only=False)
    vocab = Vocab.from_json(d["vocab"])
    model = FaustMini(len(vocab.chars), {k: len(v) for k, v in vocab.values.items()}, **d["meta"].get("dims", {}))
    model.load_state_dict(d["state"])
    model.eval()
    return model, vocab, d["meta"]


def count_params(model):
    return sum(p.numel() for p in model.parameters())
