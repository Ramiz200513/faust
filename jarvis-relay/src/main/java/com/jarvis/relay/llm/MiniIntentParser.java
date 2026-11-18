package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Звено «мини» в цепочке разбора фраз: наша нейросеть, обученная с нуля
 * ({@link MiniIntentModel}), — до Qwen в Ollama.
 *
 * <h2>Зачем ещё одна модель перед Qwen</h2>
 * Qwen 1.5B точнее на непривычных формулировках, но ей нужна Ollama и ~230 мс на
 * видеокарте (на сервере без видеокарты — секунды). «Фауст-мини» считает прямо
 * в релее за единицы миллисекунд. Отвечает она только тогда, когда уверена не меньше
 * порога ({@code jarvis.intent.mini.min-confidence}, 0,993); иначе фраза идёт к Qwen
 * дальше по цепочке, как раньше. Порог подобран так, что точность всей цепочки не
 * хуже одной Qwen (замер 16.11: jarvis-ml/runs/stats-v4.md).
 *
 * <h2>Что считается ответом</h2>
 * Ответ сети — тот же JSON, что у Qwen, и проверяется той же функцией
 * {@link LocalIntentParser#interpret}: тип из списка, громкость 0..100, действие быстрой
 * команды из закрытого списка. «Не понял» мини-модели цепочка не принимает —
 * такую фразу всё равно решает Qwen или Gemini.
 *
 * <p>Нет файлов весов в resources/mini или {@code jarvis.intent.mini.enabled=false} —
 * звено просто выключено, цепочка работает как до него.
 */
@Component
public class MiniIntentParser {

    private static final Logger log = LoggerFactory.getLogger(MiniIntentParser.class);
    /** Как у Qwen: длинные тексты — не для модели, учившейся на коротких фразах. */
    private static final int MAX_PHRASE_CHARS = 300;

    private final LocalIntentParser checker;
    private final MiniIntentModel model;
    private final double minConfidence;

    // @Autowired обязателен: конструкторов два (второй — для тестов), и без пометки Spring не
    // знает, каким создавать компонент, — релей не запускался (16.11, «No default constructor found»).
    @org.springframework.beans.factory.annotation.Autowired
    public MiniIntentParser(LocalIntentParser checker, ObjectMapper mapper,
                            @Value("${jarvis.intent.mini.enabled:true}") boolean enabled,
                            @Value("${jarvis.intent.mini.min-confidence:0.993}") double minConfidence) {
        this.checker = checker;
        this.minConfidence = minConfidence;
        this.model = enabled ? loadModel(mapper) : null;
        log.info("«Фауст-мини»: {}", model == null ? "выключена" : "модель " + model.name() + ", порог " + minConfidence);
    }

    /** Для тестов: готовая модель (или null — звено выключено). */
    MiniIntentParser(LocalIntentParser checker, MiniIntentModel model, double minConfidence) {
        this.checker = checker;
        this.model = model;
        this.minConfidence = minConfidence;
    }

    private static MiniIntentModel loadModel(ObjectMapper mapper) {
        try (InputStream json = MiniIntentParser.class.getResourceAsStream("/mini/faust-mini.json");
             InputStream bin = MiniIntentParser.class.getResourceAsStream("/mini/faust-mini.bin")) {
            if (json == null || bin == null) {
                log.warn("«Фауст-мини»: нет весов в resources/mini — звено выключено");
                return null;
            }
            return MiniIntentModel.load(json, bin, mapper);
        } catch (Exception e) {
            log.warn("«Фауст-мини» не загрузилась ({}) — звено выключено", e.getMessage());
            return null;
        }
    }

    public boolean available() {
        return model != null;
    }

    public double minConfidence() {
        return minConfidence;
    }

    /** Разобрать фразу; UNAVAILABLE — звено выключено или фраза слишком длинная. */
    public LocalIntentParser.Result parse(String phrase) {
        if (model == null || phrase == null || phrase.isBlank() || phrase.length() > MAX_PHRASE_CHARS) {
            return LocalIntentParser.Result.unavailable();
        }
        long t0 = System.nanoTime();
        MiniIntentModel.Prediction p = model.predict(phrase);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return checker.interpret(phrase, p.raw(), p.confidence(), ms);
    }

    /**
     * Ответ, которому цепочка верит без Qwen: команда, прошедшая проверку формата,
     * с уверенностью не ниже порога. Иначе null.
     */
    public LocalIntentParser.Result sure(String phrase) {
        LocalIntentParser.Result r = parse(phrase);
        return r.kind() == LocalIntentParser.Kind.COMMAND && r.confidence() >= minConfidence ? r : null;
    }
}
