package com.jarvis.agent;

/**
 * Уведомления о состоянии связи с релеем — нужны трею, чтобы показывать
 * актуальный статус (значок/подсказка/всплывающее уведомление), не влезая
 * в саму логику подключения в RelayClient.
 */
public interface RelayStatusListener {

    /** Пытаемся подключиться / переподключиться. */
    default void onConnecting() {}

    /** Подключены и авторизованы — можно выполнять команды. */
    default void onConnected() {}

    /** Соединение разорвано, будет автоматический реконнект. */
    default void onDisconnected(String reason) {}

    /** Сервер отверг токен/код — реконнект остановлен, нужны действия пользователя. */
    default void onAuthRejected(String reason) {}
}
