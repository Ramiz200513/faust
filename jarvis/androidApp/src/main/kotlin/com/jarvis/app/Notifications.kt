package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

// Два канала: тихий «держим связь» (пока сервис жив) и обычный «задача готова»
// (разовое, можно смахнуть). Разделены, чтобы пользователь мог заглушить первый
// в настройках Android, не потеряв уведомления о готовности задач.
private const val CHANNEL_ONGOING = "jarvis_ongoing"
private const val CHANNEL_TASK_DONE = "jarvis_task_done"
private const val NOTIFICATION_TASK_DONE_ID = 2001
// Третий канал — вопросы агента. Отдельный и с высокой важностью: в отличие от
// «задача готова», тут агент стоит и ждёт человека, и уведомление должно
// всплыть поверх, а не тихо лечь в шторку.
private const val CHANNEL_QUESTION = "jarvis_agent_question"
private const val NOTIFICATION_QUESTION_ID = 2002

/** Создание каналов идемпотентно — Android сам не пересоздаёт существующий. */
private fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ONGOING, "Соединение с ПК", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Держит связь с ПК, пока приложение свёрнуто" }
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_TASK_DONE, "Задача выполнена", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Агент на ПК закончил работу" }
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_QUESTION, "Вопрос от агента", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "ИИ-агент на ПК ждёт вашего ответа, чтобы продолжить задачу" }
    )
}

/**
 * Обязательное постоянное уведомление foreground-сервиса (без него
 * startForeground упадёт). Текст теперь параметр, потому что один и тот же
 * сервис показывает разные состояния: «держим связь», «слушаю Фауст»,
 * «готовлю модель».
 */
fun buildOngoingNotification(
    context: Context,
    text: String = "Держим связь с ПК…",
): Notification {
    ensureChannels(context)
    return NotificationCompat.Builder(context, CHANNEL_ONGOING)
        .setContentTitle("Faust")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

/**
 * Обновить текст уже показанного постоянного уведомления, не перезапуская
 * сервис: notify() с тем же id просто заменяет содержимое.
 */
fun updateOngoingNotification(context: Context, notificationId: Int, text: String) {
    context.getSystemService(NotificationManager::class.java)
        ?.notify(notificationId, buildOngoingNotification(context, text))
}

/** Тап по уведомлению открывает приложение — там карточка с вопросом или итог задачи. */
private fun openAppIntent(context: Context): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    return launchIntent?.let {
        PendingIntent.getActivity(
            context, 0, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Агент задал вопрос, пока приложение свёрнуто. Ответ — в самом приложении
 * (там кнопки и поле ввода), уведомление только зовёт туда.
 */
fun notifyAgentQuestion(context: Context, question: String) {
    ensureChannels(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_QUESTION)
        .setContentTitle("Faust спрашивает")
        .setContentText(question)
        // Длинный вопрос («Нашёл два чата «Лера»: …») целиком, а не обрезанным.
        .setStyle(NotificationCompat.BigTextStyle().bigText(question))
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openAppIntent(context))
        .build()
    context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_QUESTION_ID, notification)
}

/** Отдельное уведомление: agent_task завершился, пока приложение было свёрнуто. */
fun notifyAgentTaskFinished(context: Context, text: String) {
    ensureChannels(context)
    val pendingIntent = openAppIntent(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_TASK_DONE)
        .setContentTitle("Faust: задача выполнена")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()
    context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_TASK_DONE_ID, notification)
}
