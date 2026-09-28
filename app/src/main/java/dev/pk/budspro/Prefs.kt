package dev.pk.budspro

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _lockTouch = MutableStateFlow(sp.getBoolean(KEY_LOCK, true))
    val lockTouch: StateFlow<Boolean> = _lockTouch

    private val _guard = MutableStateFlow(sp.getBoolean(KEY_GUARD, false))
    val guard: StateFlow<Boolean> = _guard

    private val _address = MutableStateFlow(sp.getString(KEY_ADDRESS, null))
    val address: StateFlow<String?> = _address

    fun setLockTouch(v: Boolean) { sp.edit { putBoolean(KEY_LOCK, v) }; _lockTouch.value = v }
    fun setGuard(v: Boolean) { sp.edit { putBoolean(KEY_GUARD, v) }; _guard.value = v }
    fun setAddress(v: String?) { sp.edit { putString(KEY_ADDRESS, v) }; _address.value = v }

    private companion object {
        const val KEY_LOCK = "lock_touch"
        const val KEY_GUARD = "guard"
        const val KEY_ADDRESS = "address"
    }
}
