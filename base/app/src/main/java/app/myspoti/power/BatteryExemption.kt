package app.myspoti.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimization off for Myspoti: without it, Android may put the app to sleep in
 * the background (music stopping, a Connect device no longer followed with the screen off).
 */
object BatteryExemption {
    private const val PREFS = "myspoti_prefs"
    private const val KEY_ASKED = "BatteryExemptionAsked"

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system "Allow unrestricted battery?" dialog, or the settings list if it is unavailable. */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Asks once (onboarding, or the first launch after an update); later from Settings. */
    fun askOnce(context: Context) {
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
        if (!asked && !isExempt(context)) request(context)
    }
}
