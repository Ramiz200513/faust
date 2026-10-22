package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.persistence.IntentLesson;
import com.jarvis.relay.persistence.IntentLessonRepository;
import com.jarvis.relay.protocol.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Самообучение разбора фраз: «не понял сам → спросил Gemini → запомнил».
 *
 * <pre>
 *   наша модель не справилась ──► Gemini назвал команду ──► propose()   урок «предложен»
 *   агент выполнил её успешно ───────────────────────────► confirm()   урок «подтверждён»
 *   та же фраза ещё раз ──────────────────────────────────► recall()    ответ сразу, без моделей
 *   накопилось N подтверждённых ──► дообучение (jarvis-ml/retrain.py) ──► модель понимает и похожие фразы
 * </pre>
 *
 * <h2>Почему урок подтверждается выполнением</h2>
 * Gemini тоже ошибается. Если команда не выполнилась или человек её отменил,
 * учить на ней нашу модель нельзя — она выучила бы ошибку учителя. Успешное
 * выполнение — самый дешёвый признак «понято верно», для которого не надо
 * ничего спрашивать у человека.
 *
 * <h2>Чему не учимся никогда</h2>
 * Фразам с паролями и платёжными данными (их не должно быть в базе) и
 * слишком длинным текстам.
 */
@Service
public class IntentLessons {

    private static final Logger log = LoggerFactory.getLogger(IntentLessons.class);

    private static final int MAX_PHRASE_CHARS = 300;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(парол|password|\\bпин\\b|\\bpin\\b|cvv|cvc|паспорт|\\bиин\\b|номер карты|card number)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private final IntentLessonRepository repo;
    private final ObjectMapper mapper;

    public IntentLessons(IntentLessonRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Фраза уже была понята и подтверждена — вернуть ту же команду. */
    public Optional<Command> recall(String phrase) {
        try {
            Optional<IntentLesson> lesson =
                    repo.findFirstByPhraseNormAndConfirmedAtIsNotNullOrderByConfirmedAtDesc(norm(phrase));
            if (lesson.isEmpty()) return Optional.empty();
            Command cmd = mapper.readValue(lesson.get().getCommandJson(), Command.class);
            // Цель задачи агенту — всегда то, что человек сказал сейчас.
            if (cmd instanceof Command.AgentTask t) cmd = new Command.AgentTask(phrase.strip(), t.scope(), t.maxSteps());
            return Optional.of(cmd);
        } catch (Exception e) {
            // База недоступна или урок испорчен — не повод не выполнить команду.
            log.warn("Уроки: не удалось вспомнить ({}: {})", e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Gemini понял то, чего не поняла наша модель: записать урок, пока неподтверждённый. */
    public void propose(String requestId, String phrase, Command command, String localAnswer) {
        if (requestId == null || phrase == null || command == null) return;
        if (phrase.length() > MAX_PHRASE_CHARS || SENSITIVE.matcher(phrase).find()) return;
        try {
            repo.save(new IntentLesson(norm(phrase), phrase.strip(), mapper.writeValueAsString(command),
                    localAnswer == null ? null : cut(localAnswer, 500), requestId));
        } catch (Exception e) {
            log.warn("Уроки: не удалось записать ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Агент выполнил команду этого запроса успешно — урок можно использовать. */
    public void confirm(String requestId) {
        if (requestId == null) return;
        try {
            for (IntentLesson lesson : repo.findByRequestId(requestId)) {
                if (lesson.getConfirmedAt() == null) {
                    lesson.confirm();
                    repo.save(lesson);
                    log.info("Урок подтверждён выполнением: «{}»", lesson.getPhrase());
                }
            }
        } catch (Exception e) {
            log.warn("Уроки: не удалось подтвердить ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Команда не выполнилась или отменена — такой урок не нужен. */
    public void reject(String requestId) {
        if (requestId == null) return;
        try {
            List<IntentLesson> pending = repo.findByRequestId(requestId).stream()
                    .filter(l -> l.getConfirmedAt() == null).toList();
            repo.deleteAll(pending);
        } catch (Exception e) {
            log.warn("Уроки: не удалось удалить ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Подтверждённые уроки — для дообучения. */
    public List<IntentLesson> confirmed() {
        return repo.findByConfirmedAtIsNotNullOrderByConfirmedAtAsc();
    }

    /** Нижний регистр, ё→е, без знаков препинания и лишних пробелов. */
    static String norm(String phrase) {
        return phrase == null ? "" : phrase.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}.]+", " ").replaceAll("\\s+", " ").strip();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
