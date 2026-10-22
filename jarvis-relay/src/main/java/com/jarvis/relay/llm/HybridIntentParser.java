package com.jarvis.relay.llm;

import com.jarvis.relay.protocol.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Связка разборщиков — то, что на самом деле отвечает телефону.
 *
 * <pre>
 *   фраза
 *     │ 1. «навык …» / «запусти скрипт …» / git  ── код, без моделей
 *     │ 1б. быстрые команды (пауза, трек, «громче на 20%», свернуть окна) ── правила
 *     │ 1в. составная фраза «потише и открой ютуб» ── по частям, только своей моделью
 *     │ 2. уже выученный урок (та же фраза)  ── память, без моделей
 *     │ 3. НАША модель (локально, ~0.1–0.3 с)
 *     │       уверена и ответ годный ──────────► команда
 *     │       не уверена / «не понял» / мусор
 *     ▼
 *     4. Gemini (интернет, 2–3 с) ── понял ───► команда + УРОК для нашей модели
 *     │       недоступен
 *     ▼
 *     5. ответ нашей модели, если он был годным, иначе регулярные выражения
 * </pre>
 *
 * <p>Режим задаётся {@code jarvis.intent.engine}:
 * <ul>
 *   <li>{@code auto} (по умолчанию) — вся цепочка;</li>
 *   <li>{@code local} — только своя модель и регулярки, Gemini не спрашиваем (офлайн);</li>
 *   <li>{@code gemini} — как было до своей модели: Gemini и регулярки.</li>
 * </ul>
 * Пока своя модель не установлена в Ollama, {@code auto} ведёт себя как
 * {@code gemini} — релей с этим кодом можно ставить заранее.
 *
 * <p>Белый список и запрет опасных целей проверяются ПОСЛЕ разбора, в
 * обработчике сокета, — одинаково для любого пути отсюда.
 */
@Primary
@Component
public class HybridIntentParser implements IntentParser {

    private static final Logger log = LoggerFactory.getLogger(HybridIntentParser.class);

    /**
     * Кто и как разобрал фразу — для журнала, замеров и графиков диплома.
     *
     * @param source     shortcut | memory | local | gemini | local-offline | stub | none
     * @param confidence уверенность нашей модели (null — её не спрашивали)
     * @param localRaw   дословный ответ нашей модели (null — не спрашивали или недоступна)
     */
    public record Decision(Command command, String source, Double confidence, String localRaw,
                           long localMs, long geminiMs) {}

    private final LocalIntentParser local;
    private final GeminiIntentParser gemini;
    private final StubIntentParser stub;
    private final IntentLessons lessons;
    private final String engine;
    private final double minConfidence;
    private final double trustUnknownAbove;
    /** Делить ли составные фразы на простые команды (jarvis.intent.composite, по умолчанию да). */
    private final boolean composite;
    /** До какого момента Gemini не спрашиваем после его отказа; 0 — можно спрашивать. */
    private volatile long geminiDownUntil;
    /** Сколько не трогать Gemini после отказа. Не final — тесты ставят 0. */
    long geminiRetryMs = 60_000;

    public HybridIntentParser(LocalIntentParser local, GeminiIntentParser gemini, StubIntentParser stub,
                              IntentLessons lessons,
                              @Value("${jarvis.intent.engine:auto}") String engine,
                              @Value("${jarvis.local-llm.min-confidence:0.9}") double minConfidence,
                              @Value("${jarvis.local-llm.trust-unknown-above:2.0}") double trustUnknownAbove) {
        this(local, gemini, stub, lessons, engine, minConfidence, trustUnknownAbove, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HybridIntentParser(LocalIntentParser local, GeminiIntentParser gemini, StubIntentParser stub,
                              IntentLessons lessons,
                              @Value("${jarvis.intent.engine:auto}") String engine,
                              @Value("${jarvis.local-llm.min-confidence:0.9}") double minConfidence,
                              @Value("${jarvis.local-llm.trust-unknown-above:2.0}") double trustUnknownAbove,
                              @Value("${jarvis.intent.composite:true}") boolean composite) {
        this.local = local;
        this.gemini = gemini;
        this.stub = stub;
        this.lessons = lessons;
        this.engine = engine == null ? "auto" : engine.trim().toLowerCase();
        this.minConfidence = minConfidence;
        this.trustUnknownAbove = trustUnknownAbove;
        this.composite = composite;
        log.info("Разбор фраз: режим {}, порог уверенности своей модели {}", this.engine, minConfidence);
    }

    @Override
    public Command parse(String phrase) {
        return decide(phrase, null).command();
    }

    @Override
    public Command parse(String phrase, String requestId) {
        return decide(phrase, requestId).command();
    }

    /**
     * @param requestId запрос телефона — к нему привяжется урок, если фразу понял только Gemini;
     *                  null — урок не записывается (замеры)
     */
    public Decision decide(String phrase, String requestId) {
        // 1. Навыки и скрипты — всегда кодом: быстро и без шанса спутать с «запусти блокнот».
        Command skill = StubIntentParser.skillOrScript(phrase);
        if (skill != null) return done(phrase, new Decision(skill, "shortcut", null, null, 0, 0));

        // 1б. Быстрые команды — правилами: модель их пока не знает.
        Command quick = QuickPhrases.parse(phrase);
        if (quick != null) return done(phrase, new Decision(quick, "quick", null, null, 0, 0));

        boolean useLocal = !"gemini".equals(engine);
        boolean useGemini = !"local".equals(engine);

        // 1в. Составная фраза из простых команд — списком, без ИИ-агента.
        if (composite && useLocal) {
            Decision seq = composite(phrase);
            if (seq != null) return done(phrase, seq);
        }

        // 2. Та же фраза уже была понята Gemini и подтверждена выполнением.
        if (useLocal) {
            Command remembered = lessons.recall(phrase).orElse(null);
            if (remembered != null) return done(phrase, new Decision(remembered, "memory", null, null, 0, 0));
        }

        // 3. Наша модель.
        LocalIntentParser.Result mine = useLocal ? local.parse(phrase) : LocalIntentParser.Result.unavailable();
        Double conf = mine.kind() == LocalIntentParser.Kind.UNAVAILABLE ? null : mine.confidence();
        if (mine.kind() == LocalIntentParser.Kind.COMMAND && mine.confidence() >= minConfidence) {
            return done(phrase, new Decision(mine.command(), "local", conf, mine.raw(), mine.ms(), 0));
        }
        // «Не понял» с очень высокой уверенностью можно не перепроверять (порог > 1 — всегда перепроверять).
        if (mine.kind() == LocalIntentParser.Kind.UNKNOWN && mine.confidence() >= trustUnknownAbove) {
            return done(phrase, new Decision(null, "local", conf, mine.raw(), mine.ms(), 0));
        }

        // 4. Gemini — когда своя модель не справилась (или её нет).
        //    Если он только что не ответил (нет интернета, кончилась квота), минуту
        //    его не трогаем: иначе каждая непонятная фраза ждала бы заведомо
        //    неудачного запроса (замер 06.10 — около полсекунды на фразу).
        long geminiMs = 0;
        if (useGemini && System.currentTimeMillis() >= geminiDownUntil) {
            long t0 = System.nanoTime();
            try {
                Command cmd = gemini.parseModelOnly(phrase);
                geminiMs = (System.nanoTime() - t0) / 1_000_000;
                // Урок записываем, только если своя модель БЫЛА и не справилась:
                // иначе это не «чего она не знает», а просто «её не спросили».
                if (cmd != null && mine.kind() != LocalIntentParser.Kind.UNAVAILABLE) {
                    lessons.propose(requestId, phrase, cmd, mine.raw());
                }
                return done(phrase, new Decision(cmd, "gemini", conf, mine.raw(), mine.ms(), geminiMs));
            } catch (Exception e) {
                geminiMs = (System.nanoTime() - t0) / 1_000_000;
                geminiDownUntil = System.currentTimeMillis() + geminiRetryMs;
                log.warn("Gemini недоступен ({}: {}), следующая попытка через {} с",
                        e.getClass().getSimpleName(), e.getMessage(), geminiRetryMs / 1000);
            }
        }

        // 5. Интернета нет. Годный ответ своей модели лучше, чем ничего: он прошёл
        //    проверку формата, а дальше его ждёт белый список.
        if (mine.kind() == LocalIntentParser.Kind.COMMAND) {
            return done(phrase, new Decision(mine.command(), "local-offline", conf, mine.raw(), mine.ms(), geminiMs));
        }
        if (mine.kind() == LocalIntentParser.Kind.UNKNOWN) {
            return done(phrase, new Decision(null, "local-offline", conf, mine.raw(), mine.ms(), geminiMs));
        }
        Command byRegex = stub.parse(phrase);
        return done(phrase, new Decision(byRegex, byRegex == null ? "none" : "stub", conf, mine.raw(), mine.ms(), geminiMs));
    }

    // --- составные фразы ----------------------------------------------------------

    /**
     * Где фразу можно разрезать: «и», «а потом», «потом», «затем», запятая.
     * Резать можно смело: результат принимается, только если КАЖДАЯ часть
     * оказалась простой командой — иначе фраза разбирается целиком, как раньше.
     */
    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile(
            // Одна запятая — не граница: «покажи программы, которые открыты» — одна просьба
            // (проверка на экзаменах 19.10 резала её на две одинаковые команды).
            "\\s*,\\s*(?:и|а\\s+потом|а\\s+затем|потом|затем)\\s+(?:потом\\s+|затем\\s+|еще\\s+|сразу\\s+)?"
            + "|\\s+и\\s+(?:потом\\s+|затем\\s+|еще\\s+|сразу\\s+)?"
            + "|\\s+а\\s+(?:потом|затем)\\s+|\\s+(?:потом|затем)\\s+",
            java.util.regex.Pattern.UNICODE_CASE | java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Что можно выполнить списком — то же, что пускает в sequence белый список. */
    private static final java.util.Set<String> SIMPLE = java.util.Set.of(
            "set_volume", "quick_action", "open_url", "open_app", "screenshot", "lock_screen",
            "list_processes", "kill_process");

    /**
     * «Сделай потише и открой ютуб» -> sequence(set_volume 30, open_url youtube).
     *
     * <p>Части разбирают только правила и СВОЯ модель — без Gemini. Иначе любая
     * длинная просьба агенту («скачай отчёт и отправь Лере») платила бы лишний
     * запрос за попытку её разрезать. Своя модель на части — 0,2 с каждая, и
     * при первой же части, которая не простая команда, попытка прекращается.
     *
     * @return список команд или null — фраза не составная, разбираем как обычно
     */
    Decision composite(String phrase) {
        String p = QuickPhrases.clean(phrase);
        String[] parts = SPLIT.split(p);
        if (parts.length < 2 || parts.length > Command.Sequence.MAX) return null;
        java.util.List<Command> cmds = new java.util.ArrayList<>();
        long localMs = 0;
        double minConf = 1.0;
        StringBuilder raws = new StringBuilder();
        for (String part : parts) {
            String text = part.strip();
            if (text.length() < 3) return null;
            Command cmd = QuickPhrases.parse(text);
            if (cmd == null) {
                LocalIntentParser.Result r = local.parse(text);
                localMs += r.ms();
                if (r.kind() != LocalIntentParser.Kind.COMMAND || r.confidence() < minConfidence) return null;
                cmd = r.command();
                minConf = Math.min(minConf, r.confidence());
                raws.append(raws.isEmpty() ? "" : " | ").append(r.raw());
            }
            if (!SIMPLE.contains(cmd.name())) return null;
            cmds.add(cmd);
        }
        // Все части дали одно и то же — значит, резали одну просьбу, а не несколько.
        if (cmds.stream().distinct().count() < cmds.size()) return null;
        return new Decision(new Command.Sequence(java.util.List.copyOf(cmds)), "composite", minConf,
                raws.toString(), localMs, 0);
    }

    private Decision done(String phrase, Decision d) {
        log.info("Разбор «{}» -> {} [{}{}]", phrase.length() > 80 ? phrase.substring(0, 80) + "…" : phrase,
                d.command() == null ? "не понял" : d.command().name(), d.source(),
                d.confidence() == null ? "" : ", уверенность " + String.format("%.2f", d.confidence()));
        return d;
    }
}
