package com.jarvis.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Именованные списки получателей: «одногруппники = Петя, Маша, Лера».
 *
 * <h2>Зачем, если можно сказать «отправь всем в чатах»</h2>
 * Буквально «во все чаты» агент не делает: одна ошибка распознавания — и
 * сообщение уходит всем, отозвать его нельзя. Рассылка идёт только по списку,
 * который ЧЕЛОВЕК составил сам, заранее, в файле {@code ~/.jarvis-agent/lists.txt}.
 * Модель называет только список; кто в нём — знает код, и шаги «для каждого»
 * размножает тоже код (см. GeminiTaskPlanner). Подтверждение перед рассылкой
 * перечисляет всех получателей поимённо.
 *
 * <p>В списке не больше {@link #MAX} человек: это потолок любой рассылки.
 */
public final class RecipientLists {

    /** Больше людей в одном списке не бывает — потолок рассылки. */
    public static final int MAX = 10;

    private RecipientLists() {}

    /** Файл со списками. Свойство faust.listsFile — для тестов. */
    public static Path file() {
        String override = System.getProperty("faust.listsFile");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "lists.txt");
    }

    /** Все списки: название -> люди, в порядке файла. Список длиннее MAX пропускается целиком. */
    public static Map<String, List<String>> all() {
        Path f = file();
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось прочитать " + f + ": " + e.getMessage());
            return out;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("﻿")) line = line.substring(1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).strip();
            List<String> people = new ArrayList<>();
            for (String p : line.substring(eq + 1).split("[,;]")) {
                if (!p.isBlank() && !people.contains(p.strip())) people.add(p.strip());
            }
            if (name.isEmpty() || people.isEmpty()) continue;
            if (people.size() > MAX) {
                // Обрезать молча нельзя (кто-то не получит сообщение, а человек не узнает),
                // а выполнить целиком — превысить потолок. Список просто не работает.
                System.err.println("lists.txt: в списке «" + name + "» " + people.size() + " человек, больше " + MAX
                                   + " нельзя — список пропущен");
                continue;
            }
            out.put(name, List.copyOf(people));
        }
        return out;
    }

    /** Список по названию: точно или по вхождению («одногруппникам» -> «одногруппники»), если вариант один. */
    public static Optional<Map.Entry<String, List<String>>> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Map<String, List<String>> all = all();
        String want = AppCatalog.norm(name);
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            if (AppCatalog.norm(e.getKey()).equals(want)) return Optional.of(e);
        }
        // Падежи: сравниваем основы — общие первые символы без последних двух букв.
        List<Map.Entry<String, List<String>>> partial = all.entrySet().stream().filter(e -> {
            String n = AppCatalog.norm(e.getKey());
            String stem = n.length() > 4 ? n.substring(0, n.length() - 2) : n;
            return want.startsWith(stem) || n.startsWith(want.length() > 4 ? want.substring(0, want.length() - 2) : want);
        }).toList();
        return partial.size() == 1 ? Optional.of(partial.get(0)) : Optional.empty();
    }

    /** Создать файл-образец с пояснением, если его ещё нет. */
    public static void ensureFile() {
        Path f = file();
        if (Files.exists(f)) return;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, """
                    # Списки получателей для рассылок «Фауста».
                    # Одна строка — один список:  название = имя, имя, имя   (не больше 10 человек)
                    # Имена — как контакты называются в мессенджере.
                    # «Фауст, отправь одногруппникам файл отчёт в Телеграм» — отправит каждому из списка,
                    # но сначала спросит подтверждение и перечислит всех поимённо.
                    #
                    # пример:
                    # одногруппники = Петя Иванов, Маша, Лера
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось создать " + f + ": " + e.getMessage());
        }
    }
}
