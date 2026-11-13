package dev.apkcli.core.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android's battery optimisation leaves the app alone, and asking it to.
 *
 * Exempt, the app keeps its wake lock and its network through Doze and App
 * Standby, and most vendors' battery managers stop putting it to sleep in the
 * background: what [AgentService] holds is then honoured whatever the phone
 * decides about apps the user is not looking at. Termux asks for the same.
 * The app is not meant for the Play Store, whose policy limits the request.
 */
object BatteryExemption {

    fun granted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Ask with the system's dialog, "Let the app always run in the
     * background?". False when the system has no such screen; then
     * [openSettings] still leads to the list.
     */
    // The request is the point: an agent that works while the user is in
    // another app is what the exemption exists for.
    @SuppressLint("BatteryLife")
    fun request(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )

    /** The system's list of apps and their battery optimisation, where an exemption is taken back too. */
    fun openSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** The app's own page in the system settings, where vendors put their background limits. */
    fun openAppSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
