package dev.pk.budspro

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.pk.budspro.protocol.BudsState
import dev.pk.budspro.protocol.Frame
import dev.pk.budspro.protocol.Msg
import dev.pk.budspro.protocol.u
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

enum class Link { NO_PERMISSION, BT_OFF, NO_DEVICE, CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Single entry point for talking to the earbuds. The link stays open while at least one
 * holder exists: the visible screen, the guard service, or a one-shot lock re-send.
 *
 * Firmware R190XXU0AVF1 keeps reporting "touch locked" after both earbuds return to the
 * case, but stops enforcing it until 0x90 01 is sent again. So the lock is re-sent on
 * every connection and placement change.
 */
@SuppressLint("MissingPermission")
object Buds {
    private const val TAG = "Buds"
    const val HOLD_UI = "ui"
    const val HOLD_GUARD = "guard"
    private const val HOLD_ONESHOT = "oneshot"

    private lateinit var app: Context
    lateinit var prefs: Prefs
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _link = MutableStateFlow(Link.DISCONNECTED)
    val link: StateFlow<Link> = _link

    private val _state = MutableStateFlow(BudsState())
    val state: StateFlow<BudsState> = _state

    /** Consecutive failed connection attempts, used for a UI hint. */
    private val _failures = MutableStateFlow(0)
    val failures: StateFlow<Int> = _failures

    @Volatile private var session: BudsSession? = null
    private val holders = HashMap<String, Int>()
    private var loopRunning = false
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val lockAcks = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    @Volatile private var lastRelock = 0L

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
    }

    // ---- link holders ----

    fun acquire(tag: String) {
        synchronized(this) {
            holders[tag] = (holders[tag] ?: 0) + 1
            if (loopRunning) { wake(); return }
            loopRunning = true
        }
        scope.launch { runLoop() }
    }

    fun release(tag: String) {
        val toClose = synchronized(this) {
            val n = (holders[tag] ?: 0) - 1
            if (n > 0) holders[tag] = n else holders.remove(tag)
            if (holders.isEmpty()) session else null
        }
        toClose?.close()
        wake()
    }

    fun wake() { wakeup.trySend(Unit) }

    private fun isHeldBy(tag: String) = synchronized(this) { holders.containsKey(tag) }

    // ---- device ----

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermission() =
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .sortedWith(compareByDescending<BluetoothDevice> { looksLikeBudsPro(it) }.thenBy { it.name ?: "" })
    }

    fun deviceName(d: BluetoothDevice): String = (if (hasPermission()) d.name else null) ?: d.address

    private fun looksLikeBudsPro(d: BluetoothDevice) = d.name?.contains("Buds Pro", ignoreCase = true) == true

    fun targetDevice(): BluetoothDevice? {
        if (!hasPermission()) return null
        val bonded = adapter?.bondedDevices.orEmpty()
        val address = prefs.address.value
        bonded.firstOrNull { it.address == address }?.let { return it }
        return bonded.firstOrNull { looksLikeBudsPro(it) }?.also { prefs.setAddress(it.address) }
    }

    fun isTarget(d: BluetoothDevice?): Boolean {
        if (d == null) return false
        val address = prefs.address.value
        return if (address != null) d.address == address else hasPermission() && looksLikeBudsPro(d)
    }

    fun selectDevice(address: String) {
        if (address == prefs.address.value) return
        prefs.setAddress(address)
        _state.value = BudsState()
        session?.close()
        wake()
    }

    private fun audioConnected(): Boolean {
        val a = adapter ?: return false
        return a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED ||
            a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
    }

    // ---- connection loop ----

    private suspend fun runLoop() {
        while (true) {
            synchronized(this) {
                if (holders.isEmpty()) { loopRunning = false; _link.value = Link.DISCONNECTED; return }
            }
            val problem = when {
                !hasPermission() -> Link.NO_PERMISSION
                adapter?.isEnabled != true -> Link.BT_OFF
                targetDevice() == null -> Link.NO_DEVICE
                else -> null
            }
            if (problem != null) { _link.value = problem; waitWake(10_000); continue }

            val interactive = isHeldBy(HOLD_UI) || isHeldBy(HOLD_ONESHOT)
            if (!interactive && !audioConnected()) {
                // Guard only and no audio link: wait for ACL_CONNECTED instead of paging and draining battery.
                _link.value = Link.DISCONNECTED
                waitWake(120_000)
                continue
            }

            _link.value = Link.CONNECTING
            val s = try {
                BudsSession.open(targetDevice() ?: continue)
            } catch (e: IOException) {
                Log.i(TAG, "connect failed: $e"); null
            } catch (e: SecurityException) {
                Log.w(TAG, "connect denied: $e"); null
            }
            if (s == null) {
                _failures.update { it + 1 }
                _link.value = Link.DISCONNECTED
                waitWake(if (interactive) 3_000 else 30_000)
                continue
            }
            _failures.value = 0
            val keep = synchronized(this) { holders.isNotEmpty().also { if (it) session = s } }
            if (!keep) { s.close(); continue }
            _link.value = Link.CONNECTED
            onConnected()
            s.readLoop(::handle)
            session = null
            _state.update { it.copy(findingEarbuds = false) }
            _link.value = Link.DISCONNECTED
            waitWake(1_500)
        }
    }

    private suspend fun waitWake(ms: Long) {
        withTimeoutOrNull(ms) { wakeup.receive() }
    }

    private fun onConnected() {
        if (prefs.lockTouch.value) {
            relock(force = true)
            // The second earbud may join a bit later, so send again.
            scope.launch { delay(2_500); relock(force = true) }
        }
        send(Msg.BUILD_INFO)
    }

    private fun handle(f: Frame) {
        val p = f.payload
        when (f.id) {
            Msg.STATUS_UPDATED -> {
                val old = _state.value
                val new = old.withStatus(p)
                _state.value = new
                if (old.placementL != new.placementL || old.placementR != new.placementR || old.coupled != new.coupled) {
                    relock(force = true)
                    scope.launch { delay(2_000); relock(force = true) }
                }
            }
            Msg.EXTENDED_STATUS -> {
                val new = _state.value.withExtended(p)
                _state.value = new
                if (new.touchLocked == false) relock()
            }
            Msg.NOISE_CONTROLS_UPDATE -> if (p.isNotEmpty()) _state.update { it.copy(noiseMode = p[0].u) }
            Msg.TOUCH_UPDATED -> if (p.isNotEmpty()) {
                _state.update { it.copy(touchLocked = p[0].u == 1) }
                if (p[0].u != 1) relock()
            }
            // A touch reached the earbuds while locked, so the firmware "forgot" the lock.
            Msg.TOUCH_ON_BUDS -> relock()
            Msg.ACK -> if (p.size >= 2 && p[0].u == Msg.LOCK_TOUCHPAD) {
                val locked = p[1].u == 1
                _state.update { it.copy(touchLocked = locked) }
                lockAcks.tryEmit(locked)
            }
            Msg.BUILD_INFO -> if (p.size > 2 && _state.value.build == null) {
                val text = String(p, 2, p.size - 2, Charsets.US_ASCII).trim { it <= ' ' }
                _state.update { it.copy(build = text) }
            }
        }
    }

    /** Re-send the lock if it is enabled in the app. */
    private fun relock(force: Boolean = false) {
        if (!prefs.lockTouch.value) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRelock < 700) return
        lastRelock = now
        send(Msg.LOCK_TOUCHPAD, 1)
    }

    private fun send(id: Int, vararg bytes: Int) {
        val s = session ?: return
        try {
            s.send(id, ByteArray(bytes.size) { bytes[it].toByte() })
        } catch (e: IOException) {
            Log.i(TAG, "send failed: $e")
            s.close()
        }
    }

    private fun command(id: Int, vararg bytes: Int, update: BudsState.() -> BudsState) {
        _state.update(update)
        scope.launch { send(id, *bytes) }
    }

    // ---- touch lock ----

    fun setLockTouch(enabled: Boolean) {
        prefs.setLockTouch(enabled)
        if (session != null) {
            scope.launch { send(Msg.LOCK_TOUCHPAD, if (enabled) 1 else 0) }
        } else {
            scope.launch { syncLockOnce() }
        }
    }

    /**
     * Connect if needed, send the current lock state and wait for the ACK.
     * Used by the BroadcastReceiver and the Quick Settings tile.
     */
    suspend fun syncLockOnce(timeoutMs: Long = 25_000): Boolean {
        acquire(HOLD_ONESHOT)
        try {
            val want = prefs.lockTouch.value
            return withTimeoutOrNull(timeoutMs) {
                _link.first { it == Link.CONNECTED }
                var acked = false
                repeat(3) {
                    if (acked) return@repeat
                    send(Msg.LOCK_TOUCHPAD, if (want) 1 else 0)
                    acked = withTimeoutOrNull(2_000) { lockAcks.first { it == want } } != null
                }
                // Hold the link a bit longer for the re-send after the second earbud joins.
                delay(3_500)
                acked
            } ?: false
        } finally {
            release(HOLD_ONESHOT)
        }
    }

    // ---- settings ----

    fun setNoiseMode(mode: Int) = command(Msg.NOISE_CONTROLS, mode) { copy(noiseMode = mode) }
    fun setAncLevel(level: Int) = command(Msg.NOISE_REDUCTION_LEVEL, level) { copy(ancLevel = level) }
    fun setAmbientVolume(v: Int) = command(Msg.AMBIENT_VOLUME, v) { copy(ambientVolume = v) }
    fun setEqualizer(preset: Int) = command(Msg.EQUALIZER, preset) { copy(equalizer = preset) }
    fun setGamingMode(on: Boolean) = command(Msg.GAMING_MODE, on.bit) { copy(gamingMode = on) }
    fun setDetectConversations(on: Boolean) =
        command(Msg.SET_DETECT_CONVERSATIONS, on.bit) { copy(detectConversations = on) }
    fun setDetectConversationsDuration(d: Int) =
        command(Msg.SET_DETECT_CONVERSATIONS_DURATION, d) { copy(detectConversationsDuration = d) }
    fun setOutsideDoubleTap(on: Boolean) = command(Msg.OUTSIDE_DOUBLE_TAP, on.bit) { copy(outsideDoubleTap = on) }
    fun setTouchOptions(left: Int, right: Int) =
        command(Msg.SET_TOUCHPAD_OPTION, left, right) { copy(touchOptionL = left, touchOptionR = right) }

    fun findEarbuds(start: Boolean) =
        command(if (start) Msg.FIND_MY_EARBUDS_START else Msg.FIND_MY_EARBUDS_STOP) { copy(findingEarbuds = start) }

    private val Boolean.bit get() = if (this) 1 else 0
}
