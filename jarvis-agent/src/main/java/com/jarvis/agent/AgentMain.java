package com.jarvis.agent;

import com.jarvis.agent.protocol.JarvisMessage;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Entry point of the PC agent.
 *
 * Первый запуск (привязка по одноразовому коду, выданному сервером):
 *   ./gradlew run --args="ws://localhost:8080/ws <код>"
 * Все последующие запуски (используется постоянный токен, сохранённый на диске):
 *   ./gradlew run
 * (адрес релея при этом берётся из $HOME/.jarvis-agent/agent.properties,
 *  см. AgentConfig; если аргумент [0] всё же передан — он важнее файла).
 *
 * Args: [0] relay WebSocket URL (опц.)   [1] одноразовый код привязки (нужен только при первом запуске)
 *       --pair-phone (в любой позиции) — сразу открыть окно привязки телефона
 */
public class AgentMain {

    private static final String FLAG_PAIR_PHONE = "--pair-phone";

    public static void main(String[] args) throws Exception {
        AgentLog.install();
        // Общая с мобильным приложением палитра — до создания первого окна,
        // иначе JOptionPane успеет взять стандартные цвета Windows.
        JarvisSwingTheme.applyGlobals();

        // Флаг вынимаем до разбора позиционных аргументов, чтобы не сдвинуть url/код.
        List<String> positional = new ArrayList<>(Arrays.asList(args));
        boolean openPairWindow = positional.remove(FLAG_PAIR_PHONE);
        String[] rest = positional.toArray(new String[0]);

        AgentConfig config = AgentConfig.resolve(rest);
        String pairingCode = rest.length > 1 ? rest[1] : null;

        TokenStore tokenStore = new TokenStore();
        AgentTray tray = new AgentTray(AgentLog.filePath(),
                () -> PairPhoneDialog.open(config, tokenStore),
                () -> resetPairing(tokenStore));
        tray.install();

        // Зависимость кольцевая: клиенту нужен исполнитель команд, а исполнителю —
        // send() клиента, чтобы слать кадры живого экрана самому. Разрываем её
        // ссылкой, которую заполняем сразу после создания клиента.
        AtomicReference<RelayClient> clientRef = new AtomicReference<>();
        Consumer<JarvisMessage> sender = msg -> {
            RelayClient c = clientRef.get();
            if (c != null) c.send(msg);
        };

        CommandExecutor executor = new CommandExecutor(config, tokenStore, tray, sender);

        // Обучение показом: пункты трея. Пока агент занят ИИ-задачей, учить нельзя.
        // После каждого изменения навыков — новые фразы релею (см. SkillSync).
        SkillTeacher teacher = new SkillTeacher(executor::isBusy,
                () -> SkillSync.push(config.relayUrl(), tokenStore));
        tray.setSkillActions(teacher::startInteractive, teacher::manageInteractive);
        // Папка доверенных скриптов (с пояснением внутри) — чтобы было куда их класть.
        ScriptCatalog.ensureFolder();
        RepoCatalog.ensureFile();
        RepoCommands.ensureFile();
        RecipientLists.ensureFile();

        // Прогреваем каталог установленных программ в фоне (Get-StartApps занимает
        // секунду-другую) — чтобы САМАЯ ПЕРВАЯ команда «открой телеграм» за сессию
        // не ждала PowerShell. Демон-поток: не мешает JVM завершиться, если что-то
        // пойдёт не так — AppCatalog.warmUp() сама ловит все исключения.
        Thread appCatalogWarmup = new Thread(AppCatalog::warmUp, "app-catalog-warmup");
        appCatalogWarmup.setDaemon(true);
        appCatalogWarmup.start();

        // Первый запуск без сохранённого токена и без кода аргументом — раньше
        // это значило "напечатать в консоль просьбу передать код и остановиться"
        // (см. RelayClient.Listener.onOpen). Теперь вместо этого показываем окно
        // приветствия — оно само создаст RelayClient с введённым там кодом.
        if (tokenStore.load() == null && pairingCode == null) {
            FirstRunWizard.show(config, tokenStore, executor, tray, clientRef);
        } else {
            RelayClient client = new RelayClient(URI.create(config.relayUrl()), pairingCode, executor, tokenStore);
            clientRef.set(client);
            client.setStatusListener(tray);
            client.start();
        }

        if (openPairWindow) {
            PairPhoneDialog.open(config, tokenStore);
        }

        System.out.println("Agent running. Press Ctrl+C to stop.");
        Thread.currentThread().join(); // keep the process alive
    }

    /** Пункт трея «Настроить заново»: подтверждение, сброс токена, просьба перезапустить агент. */
    private static void resetPairing(TokenStore tokenStore) {
        int choice = javax.swing.JOptionPane.showConfirmDialog(null,
                "Сбросить привязку этого ПК? После перезапуска агента нажмите «Подключить» — "
                        + "ПК зарегистрируется заново, а телефон нужно будет привязать по QR ещё раз.",
                "Faust", javax.swing.JOptionPane.YES_NO_OPTION);
        if (choice != javax.swing.JOptionPane.YES_OPTION) return;
        tokenStore.clear();
        javax.swing.JOptionPane.showMessageDialog(null,
                "Токен сброшен. Закройте и снова откройте Faust Agent, чтобы привязать заново.",
                "Faust", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }
}
