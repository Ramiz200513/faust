package com.jarvis.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jarvis.agent.vision.TrajectoryCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Показ с переменными: «показал один раз — дальше с другими данными».
 *
 * <h2>Идея</h2>
 * Человек показал навык (DemoRecorder) и сказал, какой фразой будет его
 * просить, — с теми же данными, что в показе: «отправь Пете отчёт». Агент
 * сам находит, какие слова этой фразы встречаются в показе: в поиске
 * вводили «Петя», в списке нажимали «Петя Иванов», выбирали «отчёт.pdf».
 * Эти места становятся переменными, а фраза — шаблоном «отправь {1} {2}».
 * Потом «отправь Маше расписание» выполняется тем же показом, но с Машей и
 * расписанием — без обращений к ИИ.
 *
 * <h2>Почему кодом, а не моделью</h2>
 * Сопоставление «слово фразы ↔ значение в показе» — это сравнение строк с
 * поправкой на русские окончания (Пете ↔ Петя, отчёт ↔ отчёт.pdf). Модель
 * здесь не нужна, а код предсказуем, бесплатен и проверяется тестами.
 * Ошибка в навыке дорогая (он нажимает кнопки сам), поэтому правила
 * осторожные: слово короче трёх букв переменной не станет.
 *
 * <h2>Окончания</h2>
 * Человек говорит «Пете», а в поиск Telegram вводил «Петя». Если в показе
 * значение было не в той форме, что во фразе (переменная «склоняемая»), то
 * при повторе в поле вводится основа слова («Маш» вместо «Маше»): поиск по
 * началу имени найдёт «Машу». Совпавшее дословно («расписание») вводится
 * как сказано.
 */
public final class SkillTemplate {

    private SkillTemplate() {}

    /** Метка переменной в шаблоне фразы и в шагах: {1}, {2}… */
    private static final Pattern SLOT = Pattern.compile("\\{(\\d+)}");

    /** Сколько слов может занять одна переменная во фразе («Маше Ивановой»). */
    private static final int MAX_SLOT_WORDS = 4;

    /**
     * Переменная навыка.
     *
     * @param said      как значение прозвучало в примере фразы («Пете»)
     * @param shown     как оно было в показе («Петя»)
     * @param inflected сказано не в той форме, что в показе, — в поле вводить основу
     * @param words     сколько слов во фразе оно заняло (подсказка для разбора новой фразы)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slot(int index, String said, String shown, boolean inflected, int words) {}

    /** Итог обобщения показа: шаблон фразы, переменные, шаги и описания с метками {n}. */
    public record Generalized(List<String> template, List<Slot> slots,
                              List<TrajectoryCache.Step> steps, List<String> descriptions) {
        public boolean hasSlots() {
            return !slots.isEmpty();
        }
    }

    // =========================================================================
    //  Слова: нормализация и сравнение с поправкой на окончания
    // =========================================================================

    /** Строчные, ё→е, без знаков препинания по краям слова. */
    static String norm(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        int a = 0, b = w.length();
        while (a < b && !Character.isLetterOrDigit(w.charAt(a))) a++;
        while (b > a && !Character.isLetterOrDigit(w.charAt(b - 1))) b--;
        return w.substring(a, b);
    }

    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String t : text.strip().split("\\s+")) if (!norm(t).isEmpty()) out.add(t);
        return out;
    }

    /** «отчёт.pdf» -> «отчёт»: расширение файла во фразе не говорят. */
    static String withoutExtension(String value) {
        return value.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
    }

    /**
     * Одно и то же ли слово с точностью до окончания: «Пете» и «Петя»,
     * «отчёт» и «отчёта». Общее начало — минимум 3 буквы и всё слово, кроме
     * двух последних букв. «Маша» и «мама» (общее «ма») — разные слова.
     */
    static boolean sameWord(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        if (x.length() < 3 || y.length() < 3) return false;
        int p = 0;
        while (p < x.length() && p < y.length() && x.charAt(p) == y.charAt(p)) p++;
        return p >= 3 && p >= Math.max(x.length(), y.length()) - 2;
    }

    /**
     * Неизменная часть шаблона («отправь»), с которой должно начинаться слово
     * новой фразы. Та же граница, что в {@link #sameWord}; одно правило и у
     * агента, и в регулярном выражении для релея.
     */
    static String literalPrefix(String literal) {
        String w = norm(literal);
        return w.length() <= 3 ? w : w.substring(0, Math.max(3, w.length() - 2));
    }

    /**
     * Основа для ввода в поиск: «Маше» -> «Маш». Снимаем две последние буквы у
     * слов длиннее четырёх и одну — у слов из четырёх; короче — как есть.
     */
    static String searchStem(String value) {
        List<String> out = new ArrayList<>();
        for (String w : words(value)) {
            String n = norm(w);
            boolean cyrillic = n.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
            if (!cyrillic || n.length() <= 3) out.add(w);
            else out.add(w.substring(0, w.length() - (n.length() > 4 ? 2 : 1)));
        }
        return String.join(" ", out);
    }

    // =========================================================================
    //  Обобщение показа
    // =========================================================================

    /**
     * Найти в показе данные из примера фразы и превратить их в переменные.
     *
     * @param examplePhrase как человек будет просить, с данными из показа
     * @param steps         записанные шаги
     * @param descriptions  те же шаги словами (1:1)
     */
    public static Generalized generalize(String examplePhrase, List<TrajectoryCache.Step> steps,
                                         List<String> descriptions) {
        List<String> phrase = words(examplePhrase);
        // Какой участок фразы (начало, длина) -> номер переменной.
        Map<Long, Integer> spanToSlot = new HashMap<>();
        boolean[] taken = new boolean[phrase.size()];
        List<Slot> slots = new ArrayList<>();
        List<TrajectoryCache.Step> outSteps = new ArrayList<>();
        List<String> outDescriptions = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            TrajectoryCache.Step s = steps.get(i);
            String description = i < descriptions.size() ? descriptions.get(i) : "";
            String value = switch (s.action()) {
                case "type" -> s.text();
                case "click" -> s.label();
                default -> null;   // открыть программу, клавиши — не данные
            };
            int[] hit = value == null ? null : findSpan(phrase, taken, spanToSlot, withoutExtension(value));
            if (hit == null) {
                outSteps.add(s);
                outDescriptions.add(description);
                continue;
            }
            int start = hit[0], len = hit[1], valueFrom = hit[2];
            long key = ((long) start << 8) | len;
            Integer slotNo = spanToSlot.get(key);
            List<String> valueWords = words(withoutExtension(value));
            String shownPart = String.join(" ", valueWords.subList(valueFrom, valueFrom + len));
            String saidPart = String.join(" ", phrase.subList(start, start + len)).replaceAll("[,.!?;:]+$", "");
            if (slotNo == null) {
                slotNo = slots.size() + 1;
                spanToSlot.put(key, slotNo);
                for (int k = start; k < start + len; k++) taken[k] = true;
                slots.add(new Slot(slotNo, saidPart, shownPart, !norm(saidPart).equals(norm(shownPart)), len));
            } else if ("type".equals(s.action())) {
                // Переменную уже нашли по клику, а теперь видим, как её вводили, —
                // форма ввода важнее: по ней решаем, вводить ли основу.
                Slot old = slots.get(slotNo - 1);
                slots.set(slotNo - 1, new Slot(old.index(), old.said(), shownPart,
                        !norm(old.said()).equals(norm(shownPart)), old.words()));
            }
            String mark = "{" + slotNo + "}";
            if ("type".equals(s.action())) {
                // В поле вводили, возможно, больше, чем данные («Привет, Петя»):
                // заменяем только совпавшие слова, остальное оставляем.
                List<String> rebuilt = new ArrayList<>(valueWords.subList(0, valueFrom));
                rebuilt.add(mark);
                rebuilt.addAll(valueWords.subList(valueFrom + len, valueWords.size()));
                outSteps.add(new TrajectoryCache.Step("type", s.tag(), s.role(), s.label(), s.ordinal(),
                        String.join(" ", rebuilt), s.amount(), s.url(), false));
            } else {
                // Клик по элементу «Петя Иванов»: при повторе ищем элемент, в
                // подписи которого есть новое значение, — «Маша Петрова».
                outSteps.add(new TrajectoryCache.Step("click", s.tag(), s.role(), mark, s.ordinal(),
                        s.text(), s.amount(), s.url(), false));
            }
            outDescriptions.add(description.replace(value, mark).replace(shownPart, mark));
        }

        List<String> template = new ArrayList<>();
        for (int k = 0; k < phrase.size(); ) {
            Integer slotNo = null;
            int len = 0;
            for (Map.Entry<Long, Integer> e : spanToSlot.entrySet()) {
                if ((int) (e.getKey() >> 8) == k) {
                    slotNo = e.getValue();
                    len = (int) (e.getKey() & 0xFF);
                }
            }
            if (slotNo != null) {
                template.add("{" + slotNo + "}");
                k += len;
            } else {
                template.add(norm(phrase.get(k)));
                k++;
            }
        }
        template.removeIf(String::isEmpty);
        return new Generalized(template, slots, outSteps, outDescriptions);
    }

    /**
     * Где во фразе встречается значение из показа. Возвращает {начало во
     * фразе, число слов, с какого слова значения совпало} или null.
     * Берётся самый длинный участок; первое слово фразы (глагол просьбы)
     * переменной не бывает.
     */
    private static int[] findSpan(List<String> phrase, boolean[] taken, Map<Long, Integer> known, String value) {
        List<String> v = words(value);
        if (v.isEmpty()) return null;
        int[] best = null;
        for (int start = 1; start < phrase.size(); start++) {
            // Переменная не начинается с предлога или союза («в», «и», «на»).
            if (norm(phrase.get(start)).length() < 3) continue;
            for (int from = 0; from < v.size(); from++) {
                int len = 0;
                while (start + len < phrase.size() && from + len < v.size() && len < MAX_SLOT_WORDS
                        && sameWord(phrase.get(start + len), v.get(from + len))) len++;
                if (len == 0) continue;
                long key = ((long) start << 8) | len;
                boolean free = true;
                for (int k = start; k < start + len; k++) if (taken[k]) free = false;
                // Свободный участок или ровно тот же, что уже стал переменной.
                if (!free && !known.containsKey(key)) continue;
                if (best == null || len > best[1]) best = new int[]{start, len, from};
            }
        }
        return best;
    }

    // =========================================================================
    //  Новая фраза -> значения переменных
    // =========================================================================

    /**
     * Подходит ли фраза под шаблон, и если да — значения переменных.
     * «отправь {1} {2}» + «отправь Маше расписание» -> {1=Маше, 2=расписание}.
     */
    public static Optional<Map<Integer, String>> match(List<String> template, List<Slot> slots, String phrase) {
        if (template == null || template.isEmpty()) return Optional.empty();
        List<String> tokens = words(phrase);
        Map<Integer, Integer> sizeHint = new HashMap<>();
        if (slots != null) for (Slot s : slots) sizeHint.put(s.index(), Math.max(1, s.words()));
        Map<Integer, String> values = new HashMap<>();
        return matchFrom(template, 0, tokens, 0, sizeHint, values) ? Optional.of(values) : Optional.empty();
    }

    private static boolean matchFrom(List<String> template, int ti, List<String> tokens, int pi,
                                     Map<Integer, Integer> sizeHint, Map<Integer, String> values) {
        if (ti == template.size()) return pi == tokens.size();
        if (pi >= tokens.size()) return false;
        String part = template.get(ti);
        Matcher m = SLOT.matcher(part);
        if (!m.matches()) {
            if (!norm(tokens.get(pi)).startsWith(literalPrefix(part))) return false;
            return matchFrom(template, ti + 1, tokens, pi + 1, sizeHint, values);
        }
        int slot = Integer.parseInt(m.group(1));
        int max = Math.min(MAX_SLOT_WORDS, tokens.size() - pi);
        // Сначала столько слов, сколько было в примере («Пете» — одно слово),
        // потом остальные варианты: «Маше Ивановой» тоже подойдёт.
        List<Integer> sizes = new ArrayList<>();
        int hint = Math.min(sizeHint.getOrDefault(slot, 1), max);
        sizes.add(hint);
        for (int n = 1; n <= max; n++) if (n != hint) sizes.add(n);
        for (int n : sizes) {
            String value = String.join(" ", tokens.subList(pi, pi + n)).replaceAll("[,.!?;:]+$", "");
            values.put(slot, value);
            if (matchFrom(template, ti + 1, tokens, pi + n, sizeHint, values)) return true;
        }
        values.remove(slot);
        return false;
    }

    /**
     * Шаблон -> регулярное выражение для релея: по нему релей узнаёт фразу
     * навыка без модели и отдаёт её агенту целиком. Фразу релей перед
     * проверкой нормализует так же: строчные, ё→е, без знаков препинания.
     */
    public static String toRelayRegex(List<String> template) {
        StringBuilder re = new StringBuilder();
        for (String part : template) {
            if (!re.isEmpty()) re.append("\\s+");
            if (SLOT.matcher(part).matches()) re.append("\\S+(?:\\s+\\S+){0,").append(MAX_SLOT_WORDS - 1).append('}');
            else re.append(Pattern.quote(literalPrefix(part))).append("\\S*");
        }
        return re.toString();
    }

    // =========================================================================
    //  Подстановка при повторе
    // =========================================================================

    /**
     * Значения переменных для одного запуска навыка.
     *
     * @param values номер переменной -> как сказано в новой фразе («Маше»)
     */
    public record Binding(List<Slot> slots, Map<Integer, String> values) {

        /** Есть ли в строке шага метки переменных. */
        public static boolean hasSlot(String s) {
            return s != null && SLOT.matcher(s).find();
        }

        /** Текст для ввода в поле: «{1}» -> «Маш» (склоняемое) или «расписание» (как сказано). */
        public String typed(String template) {
            return replace(template, true);
        }

        /** То же для человека (панель плана, описание шага): значение как сказано. */
        public String shown(String template) {
            return replace(template, false);
        }

        private String replace(String template, boolean forInput) {
            if (template == null) return null;
            Matcher m = SLOT.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                String v = values.getOrDefault(n, m.group());
                if (forInput && isInflected(n)) v = searchStem(v);
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private boolean isInflected(int n) {
            for (Slot s : slots) if (s.index() == n) return s.inflected();
            return false;
        }

        /**
         * Подходит ли подпись элемента к клику «{1}»: в ней есть все слова
         * значения с точностью до окончания («Маше» -> «Маша Петрова»).
         */
        public boolean labelMatches(String labelTemplate, String elementName) {
            if (elementName == null) return false;
            String value = shown(labelTemplate);
            List<String> name = words(elementName);
            for (String w : words(value)) {
                if (name.stream().noneMatch(n -> sameWord(n, w))) return false;
            }
            return true;
        }
    }
}
