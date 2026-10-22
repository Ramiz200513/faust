package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import com.jarvis.relay.security.CommandWhitelist;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Быстрые команды правилами и составные фразы списком — без сети: «своя
 * модель» отвечает по словарю фраз. Проверяется, когда фраза становится
 * списком команд, а когда — и это не менее важно — остаётся одной задачей.
 */
class QuickAndCompositeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** «Своя модель», которая знает ответы на конкретные фразы; на остальные — задача агенту. */
    private final class DictLocal extends LocalIntentParser {
        final Map<String, String> answers = new HashMap<>();
        double confidence = 0.97;
        int calls;

        DictLocal() {
            super(mapper, "http://fake", "faust-intent");
        }

        DictLocal with(String phrase, String raw) {
            answers.put(phrase, raw);
            return this;
        }

        @Override
        public Result parse(String phrase) {
            calls++;
            String raw = answers.getOrDefault(phrase, "{\"type\":\"agent_task\",\"scope\":\"screen\"}");
            return interpret(phrase, raw, confidence, 5);
        }
    }

    /** Gemini, которого нельзя спрашивать: на части составной фразы деньги не тратим. */
    private final class CountingGemini extends GeminiIntentParser {
        int calls;

        CountingGemini() {
            super(new StubIntentParser(), mapper, "fake-key", "gemini-test");
        }

        @Override
        public Command parseModelOnly(String phrase) {
            calls++;
            return new Command.AgentTask(phrase, AgentScope.SCREEN, 20);
        }
    }

    private static final class NoLessons extends IntentLessons {
        NoLessons() {
            super(null, null);
        }

        @Override
        public Optional<Command> recall(String phrase) {
            return Optional.empty();
        }

        @Override
        public void propose(String requestId, String phrase, Command command, String localAnswer) { }
    }

    private HybridIntentParser hybrid(DictLocal local, CountingGemini gemini) {
        return new HybridIntentParser(local, gemini, new StubIntentParser(), new NoLessons(), "auto", 0.9, 2.0, true);
    }

    // --- быстрые команды ---------------------------------------------------------

    @Test
    void quickPhrases() {
        assertEquals(new Command.QuickAction("play_pause", 0), QuickPhrases.parse("Фауст, поставь на паузу"));
        assertEquals(new Command.QuickAction("play_pause", 0), QuickPhrases.parse("пауза, пожалуйста!"));
        assertEquals(new Command.QuickAction("next_track", 0), QuickPhrases.parse("следующий трек"));
        assertEquals(new Command.QuickAction("next_track", 0), QuickPhrases.parse("включи следующую песню"));
        assertEquals(new Command.QuickAction("prev_track", 0), QuickPhrases.parse("верни предыдущий трек"));
        assertEquals(new Command.QuickAction("minimize_all", 0), QuickPhrases.parse("сверни все окна"));
        assertEquals(new Command.QuickAction("volume_delta", 20), QuickPhrases.parse("сделай громче на 20%"));
        assertEquals(new Command.QuickAction("volume_delta", -10), QuickPhrases.parse("убавь звук на десять процентов"));
        assertEquals(new Command.QuickAction("volume_delta", 15), QuickPhrases.parse("прибавь на 15 процентов"));
        assertEquals(new Command.QuickAction("volume_delta", -5), QuickPhrases.parse("потише на чуть-чуть"));
    }

    @Test
    void quickPhrases_doNotSwallowOtherRequests() {
        assertNull(QuickPhrases.parse("сделай потише"), "без числа — абсолютная громкость, как раньше");
        assertNull(QuickPhrases.parse("громкость на 20"), "это «выставь 20», а не «на 20 громче»");
        assertNull(QuickPhrases.parse("поставь на паузу и открой почту"), "составная — не целиком быстрая");
        assertNull(QuickPhrases.parse("найди песню пауза"));
        assertNull(QuickPhrases.parse("сделай громче на 500"));
    }

    @Test
    void quickPhrase_beatsTheModel() {
        DictLocal local = new DictLocal().with("сделай громче на 20%", "{\"type\":\"set_volume\",\"level\":20}");
        HybridIntentParser.Decision d = hybrid(local, new CountingGemini()).decide("сделай громче на 20%", null);
        assertEquals(new Command.QuickAction("volume_delta", 20), d.command(),
                "раньше модель отвечала «громкость 20», то есть делала ТИШЕ");
        assertEquals("quick", d.source());
        assertEquals(0, local.calls);
    }

    // --- составные фразы ---------------------------------------------------------

    @Test
    void simpleParts_becomeSequence() {
        DictLocal local = new DictLocal()
                .with("сделай потише", "{\"type\":\"set_volume\",\"level\":30}")
                .with("открой ютуб", "{\"type\":\"open_url\",\"url\":\"https://youtube.com\"}");
        CountingGemini gemini = new CountingGemini();

        HybridIntentParser.Decision d = hybrid(local, gemini).decide("Фауст, сделай потише и открой ютуб", null);

        assertEquals(new Command.Sequence(List.of(new Command.SetVolume(30), new Command.OpenUrl("https://youtube.com"))),
                d.command());
        assertEquals("composite", d.source());
        assertEquals(0, gemini.calls);
        assertNull(new CommandWhitelist().denyReason(d.command()), "список из простых команд пропускается");
    }

    @Test
    void quickAndModelParts_mix() {
        DictLocal local = new DictLocal().with("открой спотифай", "{\"type\":\"open_app\",\"appName\":\"Spotify\"}");
        HybridIntentParser.Decision d = hybrid(local, new CountingGemini())
                .decide("открой спотифай, а потом следующий трек", null);
        assertEquals(new Command.Sequence(List.of(new Command.OpenApp("Spotify"),
                new Command.QuickAction("next_track", 0))), d.command());
    }

    @Test
    void anyAgentPart_keepsWholePhraseAsOneTask_withoutPayingGemini() {
        DictLocal local = new DictLocal().with("открой телеграм", "{\"type\":\"open_app\",\"appName\":\"Telegram\"}");
        CountingGemini gemini = new CountingGemini();

        HybridIntentParser.Decision d = hybrid(local, gemini).decide("открой телеграм и отправь Лере отчёт", null);

        Command.AgentTask task = assertInstanceOf(Command.AgentTask.class, d.command());
        assertEquals("открой телеграм и отправь Лере отчёт", task.goal(), "задача агенту — фраза целиком");
        assertNotEquals("composite", d.source());
    }

    @Test
    void unsureModel_onAPart_meansNoSequence() {
        DictLocal local = new DictLocal()
                .with("сделай потише", "{\"type\":\"set_volume\",\"level\":30}")
                .with("открой ютуб", "{\"type\":\"open_url\",\"url\":\"https://youtube.com\"}");
        local.confidence = 0.6;
        HybridIntentParser h = hybrid(local, new CountingGemini());
        assertNull(h.composite("сделай потише и открой ютуб"), "сомнение в любой части — разбираем целиком");
    }

    @Test
    void compositeCanBeSwitchedOff() {
        DictLocal local = new DictLocal()
                .with("сделай потише", "{\"type\":\"set_volume\",\"level\":30}")
                .with("открой ютуб", "{\"type\":\"open_url\",\"url\":\"https://youtube.com\"}");
        HybridIntentParser off = new HybridIntentParser(local, new CountingGemini(), new StubIntentParser(),
                new NoLessons(), "auto", 0.9, 2.0, false);
        assertFalse(off.decide("сделай потише и открой ютуб", null).command() instanceof Command.Sequence);
    }

    @Test
    void resumePhrases_goToAgent_butResumeMusicIsAQuickAction() {
        assertInstanceOf(Command.AgentTask.class, StubIntentParser.skillOrScript("Фауст, продолжи"));
        assertInstanceOf(Command.AgentTask.class, StubIntentParser.skillOrScript("продолжи с места сбоя"));
        assertInstanceOf(Command.AgentTask.class, StubIntentParser.skillOrScript("доделай задачу"));
        assertNull(StubIntentParser.skillOrScript("продолжи музыку"));
        assertEquals(new Command.QuickAction("play_pause", 0), QuickPhrases.parse("продолжи музыку"));
    }

    // --- белый список --------------------------------------------------------------

    @Test
    void whitelist_checksEveryCommandInsideSequence() {
        CommandWhitelist wl = new CommandWhitelist();
        assertNotNull(wl.denyReason(new Command.Sequence(List.of(new Command.SetVolume(10), new Command.Shutdown(0)))),
                "выключение не пройдёт и внутри списка");
        assertNotNull(wl.denyReason(new Command.Sequence(List.of(new Command.SetVolume(10),
                new Command.AgentTask("сделай что-нибудь", AgentScope.SCREEN, 20)))), "задачи агенту в списке нельзя");
        assertNotNull(wl.denyReason(new Command.Sequence(List.of(
                new Command.Sequence(List.of(new Command.SetVolume(1))), new Command.SetVolume(2)))), "вложенные списки нельзя");
        assertNotNull(wl.denyReason(new Command.Sequence(List.of())));
        assertNotNull(wl.denyReason(new Command.QuickAction("format_c", 0)), "быстрые действия — из закрытого списка");
        assertNull(wl.denyReason(new Command.QuickAction("next_track", 0)));
    }

    @Test
    void sequence_jsonHasTypeOnEveryInnerCommand() throws Exception {
        String json = mapper.writeValueAsString(new Command.Sequence(List.of(new Command.QuickAction("play_pause", 0))));
        assertEquals("{\"type\":\"sequence\",\"commands\":[{\"type\":\"quick_action\",\"action\":\"play_pause\",\"amount\":0}]}",
                json);
        assertEquals(new Command.Sequence(List.of(new Command.QuickAction("play_pause", 0))),
                mapper.readValue("{\"type\":\"sequence\",\"commands\":[{\"type\":\"quick_action\",\"action\":\"play_pause\"}]}",
                        Command.class), "amount можно не писать — как в Kotlin по умолчанию 0");
    }
}
