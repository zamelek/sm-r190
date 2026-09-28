package dev.pk.budspro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.pk.budspro.protocol.BudsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Guard: keeps the earbuds link open and restores the touch lock immediately. */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var holding = false
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground is mandatory after startForegroundService, even if we stop right away.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(Buds.link.value, Buds.state.value, Buds.prefs.lockTouch.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            Log.w("GuardService", "startForeground failed: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Buds.prefs.guard.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!holding) {
            holding = true
            Buds.acquire(Buds.HOLD_GUARD)
        }
        if (updates == null) {
            val nm = getSystemService(NotificationManager::class.java)
            updates = scope.launch {
                combine(Buds.link, Buds.state, Buds.prefs.lockTouch) { l, s, lock -> Triple(l, s, lock) }
                    .distinctUntilChanged { a, b ->
                        a.first == b.first && a.third == b.third &&
                            a.second.batteryL == b.second.batteryL && a.second.batteryR == b.second.batteryR &&
                            a.second.batteryCase == b.second.batteryCase && a.second.touchLocked == b.second.touchLocked
                    }
                    .collect { (l, s, lock) -> nm.notify(NOTIFICATION_ID, buildNotification(l, s, lock)) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (holding) Buds.release(Buds.HOLD_GUARD)
        holding = false
        super.onDestroy()
    }

    private fun buildNotification(link: Link, s: BudsState, lock: Boolean): Notification {
        val title = when {
            !lock -> "Touch lock is off"
            link == Link.CONNECTED -> "Touch controls locked"
            else -> "Guard is waiting for earbuds"
        }
        val text = if (!lock) {
            "Turn it on in the app or from the Quick Settings tile"
        } else if (link == Link.CONNECTED) {
            listOfNotNull(
                s.batteryL?.let { "L $it%" }, s.batteryR?.let { "R $it%" }, s.batteryCase?.let { "Case $it%" },
            ).joinToString(" · ").ifEmpty { "Connected" }
        } else {
            "The lock will be restored when the earbuds connect"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_touch_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 1

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Touch lock guard", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, GuardService::class.java))
            } catch (e: Exception) {
                // The system may forbid starting from the background; the one-shot re-send covers that case.
                Log.w("GuardService", "start failed: $e")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
