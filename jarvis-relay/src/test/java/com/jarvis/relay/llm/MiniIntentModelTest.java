package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java-версия «Фауст-мини» считает так же, как PyTorch.
 *
 * <p>Эталон — {@code parity.jsonl}: ответы обученной модели в PyTorch на 1 218 фразах
 * (все три экзамена, dev, часть обучения, края — пустая фраза, одна буква, 400
 * символов), выгруженные {@code jarvis-ml/mini/export_mini.py} вместе с весами.
 * Ответ должен совпасть ДОСЛОВНО, уверенность — до тысячной (порядок сложения
 * в float32 у PyTorch и в double у Java разный, отсюда крошечная разница).
 */
class MiniIntentModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static MiniIntentModel model;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream json = MiniIntentModelTest.class.getResourceAsStream("/mini/faust-mini.json");
             InputStream bin = MiniIntentModelTest.class.getResourceAsStream("/mini/faust-mini.bin")) {
            assertNotNull(json, "веса мини-модели должны лежать в resources/mini");
            model = MiniIntentModel.load(json, bin, MAPPER);
        }
    }

    @Test
    void sameAnswersAsPyTorch_onAllExamPhrases() throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                MiniIntentModelTest.class.getResourceAsStream("/mini/parity.jsonl"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.isBlank()) rows.add(MAPPER.readTree(line));
        }
        assertTrue(rows.size() > 1000, "эталон неполный: " + rows.size());
        List<String> diffs = new ArrayList<>();
        double worst = 0;
        long t0 = System.nanoTime();
        for (JsonNode row : rows) {
            String phrase = row.path("phrase").asText();
            MiniIntentModel.Prediction p = model.predict(phrase);
            double d = Math.abs(p.confidence() - row.path("confidence").asDouble());
            worst = Math.max(worst, d);
            if (!p.raw().equals(row.path("raw").asText()) || d > 1e-3) {
                diffs.add("«" + phrase + "»: Java " + p.raw() + " " + p.confidence()
                          + " / PyTorch " + row.path("raw").asText() + " " + row.path("confidence").asDouble());
            }
        }
        double msPerPhrase = (System.nanoTime() - t0) / 1e6 / rows.size();
        System.out.printf("мини на Java: %d фраз, расхождений %d, наибольшая разница уверенности %.2e, %.2f мс на фразу%n",
                rows.size(), diffs.size(), worst, msPerPhrase);
        assertTrue(diffs.isEmpty(), diffs.size() + " расхождений, первые:\n" + String.join("\n", diffs.subList(0, Math.min(10, diffs.size()))));
    }

    @Test
    void normalization_likePython() {
        assertArrayEquals(model.encode("ЁЛКА   палка"), model.encode("елка палка"), "регистр, ё→е, пробелы");
        assertEquals(1, model.encode("   ").length, "пустая фраза — одна «незнакомая буква»");
        assertEquals(160, model.encode("x".repeat(400)).length, "длиннее 160 символов — обрезается");
    }
}
