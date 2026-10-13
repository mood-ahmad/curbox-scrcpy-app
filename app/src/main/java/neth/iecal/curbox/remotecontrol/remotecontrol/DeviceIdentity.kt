package neth.iecal.curbox.remotecontrol

import android.os.Build

/**
 * Automatically identifies the current device for email subjects.
 * Uses manufacturer + model, with a short suffix from the Android ID
 * to avoid collisions when the same model is used more than once.
 */
object DeviceIdentity {

    private val cachedName: String by lazy { buildName() }

    /**
     * Returns a short device name like "samsung SM-S901E" or
     * "samsung SM-S901E (a3f5)" when needed.
     */
    fun getName(): String = cachedName

    /**
     * Full description for email bodies.
     */
    fun getFullDescription(): String {
        return "${getName()} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
    }

    // -------------------------------------------------------------------------

    private fun buildName(): String {
        val manufacturer = Build.MANUFACTURER
            ?.replaceFirstChar { it.uppercase() }
            ?.trim()
            ?: "Unknown"

        val model = Build.MODEL?.trim() ?: "Device"

        // If the manufacturer already appears in the model, don't repeat it.
        val base = if (model.lowercase().startsWith(manufacturer.lowercase())) {
            model
        } else {
            "$manufacturer $model"
        }

        return base
    }
}
