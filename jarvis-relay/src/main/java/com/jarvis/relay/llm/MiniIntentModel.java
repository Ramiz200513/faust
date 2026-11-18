package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * «Фауст-мини» — наша нейросеть разбора фраз, обученная С НУЛЯ (jarvis-ml/mini), —
 * здесь она только СЧИТАЕТ: прямой проход по готовым весам, на чистой Java.
 *
 * <h2>Почему своя реализация, а не ONNX/PyTorch</h2>
 * Сеть крошечная (≈0,5 млн чисел) и состоит из пяти видов слоёв: вложения букв,
 * свёртка, двунаправленная GRU, «внимание» и линейный слой. Написать их — около
 * двухсот строк по формулам из документации PyTorch; движок ONNX добавил бы в
 * релей десятки мегабайт ради того же. Правильность проверяется не на глаз:
 * {@code MiniIntentModelTest} сверяет ответы с PyTorch на 1 218 фразах
 * (все экзамены, dev, часть обучения, края — пустая фраза, 400 символов).
 *
 * <h2>Что на входе и на выходе</h2>
 * Фраза -> буквы -> номера по словарю модели. Ответ — тот же JSON, что пишет
 * Qwen ({"type":"set_volume","level":30}), и уверенность 0..1 — произведение
 * вероятностей выбранного типа команды и выбранного значения. Поэтому дальше
 * ответ проверяется той же функцией, что и ответ Qwen
 * ({@link LocalIntentParser#interpret}).
 *
 * <p>Объект неизменяемый после загрузки — безопасен для одновременных запросов.
 */
public final class MiniIntentModel {

    /** Ответ сети: JSON-строка и уверенность. */
    public record Prediction(String raw, double confidence) {}

    private final int maxLen;
    private final Map<Integer, Integer> charId = new HashMap<>();   // код символа Unicode -> номер
    private final List<String> intents;
    private final Map<String, String> slotOf;
    private final Map<String, String> slotField;
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final String name;

    // Веса (имена — как в PyTorch: emb.weight, conv1.weight, rnn.weight_ih_l0, …)
    private final float[][] emb;                 // [букв][E]
    private final float[][][] conv1W, conv2W;    // [выход][вход][ядро]
    private final float[] conv1B, conv2B;
    private final Gru fwd, bwd;
    private final float[] attW;                  // [2H]
    private final float attB;
    private final Linear intent, level;
    private final Map<String, Linear> slots = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    /** GRU одного направления: веса в порядке PyTorch — r, z, n (сброс, обновление, кандидат). */
    private record Gru(float[][] wIh, float[][] wHh, float[] bIh, float[] bHh, int hidden) {}

    private record Linear(float[][] w, float[] b) {
        double[] apply(double[] x) {
            double[] y = new double[b.length];
            for (int o = 0; o < y.length; o++) {
                double s = b[o];
                float[] row = w[o];
                for (int i = 0; i < x.length; i++) s += row[i] * x[i];
                y[o] = s;
            }
            return y;
        }
    }

    private MiniIntentModel(JsonNode d, float[] all, ObjectMapper mapper) {
        this.mapper = mapper;
        this.name = d.path("name").asText("mini");
        this.maxLen = d.path("max_len").asInt(160);
        JsonNode chars = d.path("chars");
        for (int i = 0; i < chars.size(); i++) {
            String c = chars.get(i).asText();
            // Номера 0 и 1 — служебные «<pad>» и «<unk>», настоящие буквы — одиночные символы.
            if (c.codePointCount(0, c.length()) == 1) charId.put(c.codePointAt(0), i);
        }
        this.intents = new ArrayList<>();
        d.path("intents").forEach(n -> intents.add(n.asText()));
        this.slotOf = new HashMap<>();
        d.path("slot_of").fields().forEachRemaining(e -> slotOf.put(e.getKey(), e.getValue().asText()));
        this.slotField = new HashMap<>();
        d.path("slot_field").fields().forEachRemaining(e -> slotField.put(e.getKey(), e.getValue().asText()));
        d.path("values").fields().forEachRemaining(e -> {
            List<String> vs = new ArrayList<>();
            e.getValue().forEach(v -> vs.add(v.asText()));
            values.put(e.getKey(), vs);
        });

        JsonNode t = d.path("tensors");
        this.emb = m2(all, t.path("emb.weight"));
        this.conv1W = m3(all, t.path("conv1.weight"));
        this.conv1B = v1(all, t.path("conv1.bias"));
        this.conv2W = m3(all, t.path("conv2.weight"));
        this.conv2B = v1(all, t.path("conv2.bias"));
        int hidden = t.path("rnn.weight_hh_l0").path("shape").get(1).asInt();
        this.fwd = new Gru(m2(all, t.path("rnn.weight_ih_l0")), m2(all, t.path("rnn.weight_hh_l0")),
                v1(all, t.path("rnn.bias_ih_l0")), v1(all, t.path("rnn.bias_hh_l0")), hidden);
        this.bwd = new Gru(m2(all, t.path("rnn.weight_ih_l0_reverse")), m2(all, t.path("rnn.weight_hh_l0_reverse")),
                v1(all, t.path("rnn.bias_ih_l0_reverse")), v1(all, t.path("rnn.bias_hh_l0_reverse")), hidden);
        this.attW = m2(all, t.path("att.weight"))[0];
        this.attB = v1(all, t.path("att.bias"))[0];
        this.intent = new Linear(m2(all, t.path("intent.weight")), v1(all, t.path("intent.bias")));
        this.level = new Linear(m2(all, t.path("level.weight")), v1(all, t.path("level.bias")));
        for (String slot : values.keySet()) {
            slots.put(slot, new Linear(m2(all, t.path("slots." + slot + ".weight")), v1(all, t.path("slots." + slot + ".bias"))));
        }
    }

    /** Загрузить из описания (faust-mini.json) и весов (faust-mini.bin). */
    public static MiniIntentModel load(InputStream json, InputStream bin, ObjectMapper mapper) throws IOException {
        JsonNode d = mapper.readTree(json);
        if (!"faust-mini/1".equals(d.path("format").asText())) {
            throw new IOException("неизвестный формат весов: " + d.path("format").asText());
        }
        byte[] bytes = bin.readAllBytes();
        int n = d.path("floats").asInt();
        if (bytes.length != n * 4) throw new IOException("весов " + bytes.length / 4 + ", а в описании " + n);
        float[] all = new float[n];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(all);
        return new MiniIntentModel(d, all, mapper);
    }

    public String name() {
        return name;
    }

    // --- разбор фразы ---------------------------------------------------------------

    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");

    /**
     * Вид фразы для сети — ровно как {@code norm_text} в Python: нижний регистр,
     * ё→е, одиночные пробелы, не длиннее maxLen СИМВОЛОВ (кодовых точек Unicode, как
     * в Python, а не половинок UTF-16, как у String.substring в Java).
     */
    int[] encode(String phrase) {
        String s = SPACES.matcher((phrase == null ? "" : phrase).toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .replaceAll(" ").strip();
        int[] cps = s.codePoints().limit(maxLen).toArray();
        if (cps.length == 0) return new int[]{1};                  // пусто -> одна «незнакомая буква»
        int[] ids = new int[cps.length];
        for (int i = 0; i < cps.length; i++) ids[i] = charId.getOrDefault(cps[i], 1);
        return ids;
    }

    public Prediction predict(String phrase) {
        int[] ids = encode(phrase);
        int len = ids.length;

        // 1. Вложения букв.
        double[][] x = new double[len][];
        for (int t = 0; t < len; t++) {
            float[] e = emb[ids[t]];
            x[t] = new double[e.length];
            for (int i = 0; i < e.length; i++) x[t][i] = e[i];
        }
        // 2. Две свёртки по времени; вторая — с «остаточной» связью: x1 + relu(conv2(x1)).
        double[][] x1 = relu(conv(x, conv1W, conv1B));
        double[][] c2 = relu(conv(x1, conv2W, conv2B));
        for (int t = 0; t < len; t++) for (int i = 0; i < x1[t].length; i++) x1[t][i] += c2[t][i];
        // 3. Двунаправленная GRU: вперёд и назад, выходы склеиваются (2H на каждую букву).
        int h = fwd.hidden();
        double[][] out = new double[len][2 * h];
        gru(fwd, x1, out, 0, false);
        gru(bwd, x1, out, h, true);
        // 4. «Внимание» (взвешенная сумма по буквам) + максимум по буквам.
        double[] score = new double[len];
        double maxScore = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < len; t++) {
            double s = attB;
            for (int i = 0; i < 2 * h; i++) s += attW[i] * out[t][i];
            score[t] = s;
            maxScore = Math.max(maxScore, s);
        }
        double z = 0;
        for (int t = 0; t < len; t++) z += (score[t] = Math.exp(score[t] - maxScore));
        double[] f = new double[4 * h];
        for (int i = 0; i < 2 * h; i++) {
            double att = 0, mx = Double.NEGATIVE_INFINITY;
            for (int t = 0; t < len; t++) {
                att += score[t] / z * out[t][i];
                mx = Math.max(mx, out[t][i]);
            }
            f[i] = att;
            f[2 * h + i] = mx;
        }
        // 5. Головы и сборка ответа — как predict() в faust_mini.py.
        double[] pIntent = softmax(intent.apply(f));
        int k = argmax(pIntent);
        double conf = pIntent[k];
        String intentName = intents.get(k);
        String slot = slotOf.get(intentName);
        int lvl = 0;
        String value = null;
        if ("level".equals(slot)) {
            double[] pl = softmax(level.apply(f));
            if (!"set_volume".equals(intentName)) pl[0] = 0;   // сдвиг громкости на 0 не бывает
            double sum = 0;
            for (double p : pl) sum += p;
            lvl = argmax(pl);
            conf *= pl[lvl] / sum;
        } else if (slot != null) {
            double[] ps = softmax(slots.get(slot).apply(f));
            int j = argmax(ps);
            conf *= ps[j];
            value = values.get(slot).get(j);
        }
        return new Prediction(answer(intentName, slot, lvl, value), conf);
    }

    /** JSON ответа в точности как у Python (порядок полей, без пробелов). */
    private String answer(String intentName, String slot, int lvl, String value) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (intentName.startsWith("agent_")) {
            raw.put("type", "agent_task");
            raw.put("scope", intentName.substring(6));
        } else if (intentName.equals("qa_volume_up") || intentName.equals("qa_volume_down")) {
            raw.put("type", "quick_action");
            raw.put("action", "volume_delta");
            raw.put("amount", intentName.endsWith("up") ? lvl : -lvl);
        } else if (intentName.startsWith("qa_")) {
            raw.put("type", "quick_action");
            raw.put("action", intentName.substring(3));
        } else if (intentName.equals("set_volume")) {
            raw.put("type", "set_volume");
            raw.put("level", lvl);
        } else if (slot != null && slotField.containsKey(slot)) {
            raw.put("type", intentName);
            raw.put(slotField.get(slot), value);
        } else {
            raw.put("type", intentName);
        }
        try {
            return mapper.writeValueAsString(raw);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- слои -------------------------------------------------------------------------

    /** Свёртка по времени с дополнением нулями по краям (padding = ядро/2), как Conv1d в PyTorch. */
    private static double[][] conv(double[][] x, float[][][] w, float[] b) {
        int len = x.length, outC = w.length, inC = w[0].length, k = w[0][0].length, pad = k / 2;
        double[][] y = new double[len][outC];
        for (int t = 0; t < len; t++) {
            for (int o = 0; o < outC; o++) {
                double s = b[o];
                float[][] wo = w[o];
                for (int j = 0; j < k; j++) {
                    int src = t + j - pad;
                    if (src < 0 || src >= len) continue;
                    double[] xs = x[src];
                    for (int i = 0; i < inC; i++) s += wo[i][j] * xs[i];
                }
                y[t][o] = s;
            }
        }
        return y;
    }

    /**
     * GRU одного направления (формулы PyTorch):
     * r = σ(W_ir·x + b_ir + W_hr·h + b_hr), z = σ(W_iz·x + b_iz + W_hz·h + b_hz),
     * n = tanh(W_in·x + b_in + r ⊙ (W_hn·h + b_hn)), h' = (1 − z) ⊙ n + z ⊙ h.
     * Выход пишется в out[t][shift .. shift+H).
     */
    private static void gru(Gru g, double[][] x, double[][] out, int shift, boolean reverse) {
        int hd = g.hidden(), len = x.length;
        double[] hPrev = new double[hd];
        for (int step = 0; step < len; step++) {
            int t = reverse ? len - 1 - step : step;
            double[] gi = matVec(g.wIh(), g.bIh(), x[t]);
            double[] gh = matVec(g.wHh(), g.bHh(), hPrev);
            double[] hNew = new double[hd];
            for (int i = 0; i < hd; i++) {
                double r = sigmoid(gi[i] + gh[i]);
                double zz = sigmoid(gi[hd + i] + gh[hd + i]);
                double n = Math.tanh(gi[2 * hd + i] + r * gh[2 * hd + i]);
                hNew[i] = (1 - zz) * n + zz * hPrev[i];
            }
            hPrev = hNew;
            System.arraycopy(hNew, 0, out[t], shift, hd);
        }
    }

    private static double[] matVec(float[][] w, float[] b, double[] x) {
        double[] y = new double[w.length];
        for (int o = 0; o < w.length; o++) {
            double s = b[o];
            float[] row = w[o];
            for (int i = 0; i < x.length; i++) s += row[i] * x[i];
            y[o] = s;
        }
        return y;
    }

    private static double[][] relu(double[][] x) {
        for (double[] row : x) for (int i = 0; i < row.length; i++) if (row[i] < 0) row[i] = 0;
        return x;
    }

    private static double sigmoid(double v) {
        return 1 / (1 + Math.exp(-v));
    }

    private static double[] softmax(double[] v) {
        double max = Double.NEGATIVE_INFINITY;
        for (double x : v) max = Math.max(max, x);
        double s = 0;
        double[] p = new double[v.length];
        for (int i = 0; i < v.length; i++) s += (p[i] = Math.exp(v[i] - max));
        for (int i = 0; i < v.length; i++) p[i] /= s;
        return p;
    }

    /** Первый максимальный — как argmax в PyTorch. */
    private static int argmax(double[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    // --- чтение массивов из общего буфера ------------------------------------------------

    private static float[] v1(float[] all, JsonNode t) {
        int n = t.path("shape").get(0).asInt(), off = t.path("offset").asInt();
        float[] v = new float[n];
        System.arraycopy(all, off, v, 0, n);
        return v;
    }

    private static float[][] m2(float[] all, JsonNode t) {
        int a = t.path("shape").get(0).asInt(), b = t.path("shape").get(1).asInt(), off = t.path("offset").asInt();
        float[][] m = new float[a][b];
        for (int i = 0; i < a; i++) System.arraycopy(all, off + i * b, m[i], 0, b);
        return m;
    }

    private static float[][][] m3(float[] all, JsonNode t) {
        int a = t.path("shape").get(0).asInt(), b = t.path("shape").get(1).asInt(), c = t.path("shape").get(2).asInt();
        int off = t.path("offset").asInt();
        float[][][] m = new float[a][b][c];
        for (int i = 0; i < a; i++) for (int j = 0; j < b; j++) System.arraycopy(all, off + (i * b + j) * c, m[i][j], 0, c);
        return m;
    }
}
