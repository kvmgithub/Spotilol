package app.myspoti.power

import android.app.Application
import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned preferences (the app's saver is independent of Android's); no polling or playback commands. */
object EnergySettings {
    private val mutableState = MutableStateFlow(EnergyState())
    val state = mutableState.asStateFlow()
    var oledBlack by mutableStateOf(false)
        private set
    private var app: Application? = null
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    fun init(context: Context) {
        if (app != null) return
        val application = context.applicationContext as Application
        app = application
        preferences = application.getSharedPreferences("energy_settings", Context.MODE_PRIVATE)
        preferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh()
    }

    fun setSaver(enabled: Boolean) {
        preferences?.edit { putBoolean("manual_saver", enabled) }
        refresh()
    }

    fun setBlackBackground(enabled: Boolean) {
        preferences?.edit { putBoolean("black_background", enabled) }
        refresh()
    }

    fun setCanvasEnabled(enabled: Boolean) {
        preferences?.edit { putBoolean("canvas", enabled) }
        refresh()
    }

    private fun refresh() {
        if (app == null) return
        val next = EnergyState(
            manualSaver = preferences?.getBoolean("manual_saver", false) == true,
            blackPreference = preferences?.getBoolean("black_background", false) == true,
            canvasEnabled = preferences?.getBoolean("canvas", true) != false,
        )
        mutableState.value = next
        oledBlack = next.policy.oledBlack
    }
}
