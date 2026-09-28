package dev.pk.budspro

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ACTION_ACL_CONNECTED is exempt from implicit broadcast limits, so the app receives it
 * even when not running and re-sends the lock right away.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Buds.init(context)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (!Buds.isTarget(device)) return
                Buds.wake()
                if (Buds.prefs.guard.value) GuardService.start(context)
                if (!Buds.prefs.lockTouch.value) return
                val pending = goAsync()
                scope.launch {
                    try { Buds.syncLockOnce() } finally { pending.finish() }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (Buds.prefs.guard.value) GuardService.start(context)
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
