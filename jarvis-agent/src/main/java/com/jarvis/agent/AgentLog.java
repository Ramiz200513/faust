package com.jarvis.agent;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Дублирует System.out/System.err в файл $HOME/.jarvis-agent/agent.log
 * (UTF-8, дозапись), не трогая существующий код — весь проект как писал
 * System.out.println, так и продолжает, просто вывод теперь идёт в двух
 * места сразу.
 */
public class AgentLog {

    public static Path filePath() {
        return Paths.get(System.getProperty("user.home"), ".jarvis-agent", "agent.log");
    }

    public static void install() {
        install(filePath());
    }

    static void install(Path logFile) {
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            OutputStream file = new FileOutputStream(logFile.toFile(), true);
            System.setOut(tee(System.out, file));
            System.setErr(tee(System.err, file));
        } catch (IOException e) {
            System.err.println("Не удалось открыть лог " + logFile + ": " + e.getMessage());
        }
    }

    /** Пишет каждый байт и в исходный поток (консоль), и в файл лога. */
    private static PrintStream tee(PrintStream console, OutputStream file) {
        OutputStream combined = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                console.write(b);
                file.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                console.write(b, off, len);
                file.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                console.flush();
                file.flush();
            }
        };
        return new PrintStream(combined, true, StandardCharsets.UTF_8);
    }
}
