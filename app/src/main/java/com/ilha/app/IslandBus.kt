package com.ilha.app

import android.app.PendingIntent
import android.graphics.drawable.Drawable

/** Uma notificação pronta para aparecer na ilha. */
data class IslandEvent(
    val title: String,
    val text: String,
    val icon: Drawable?,
    val intent: PendingIntent?,
)

/**
 * Ponte entre o leitor de notificações e o serviço que desenha a ilha.
 * Os dois rodam no mesmo processo, então basta um objeto compartilhado.
 */
object IslandBus {
    @Volatile
    var listener: ((IslandEvent) -> Unit)? = null

    fun post(event: IslandEvent): Boolean {
        val l = listener ?: return false
        l(event)
        return true
    }
}
