package com.ilha.app

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    // Evita repetir a mesma notificação quando o app só a atualiza
    private val lastShown = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return // downloads, música, chamadas em andamento etc.
        // Notificação de player: a música já fica na ilha, não precisa de cartão a cada faixa
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        // Ignora notificações silenciosas/de baixa prioridade
        val ranking = Ranking()
        if (currentRanking.getRanking(sbn.key, ranking) &&
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
        ) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        val signature = "$title|$text"
        if (lastShown[sbn.key] == signature) return
        lastShown[sbn.key] = signature
        if (lastShown.size > 200) lastShown.clear()

        val icon = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (e: Exception) {
            null
        }

        IslandBus.post(IslandEvent(title, text, icon, n.contentIntent))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastShown.remove(sbn.key)
    }
}
