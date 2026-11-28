package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.io.File

/**
 * Fallback for devices where the shell refuses to activate the device admin:
 * the main process opens Android's own "Activate device admin" screen and
 * leaves a short-lived request file. The accessibility service, which runs in
 * another process, taps Activate on that exact screen.
 */
object AdminActivationRequest {
    private const val FILE_NAME = "admin_activation_request"
    private const val MAX_AGE_MS = 90_000L

    val activateLabels = listOf(
        "activate this device admin app", "activate", "تفعيل تطبيق مشرف الجهاز هذا", "تفعيل",
        "activer cette application d\u2019administration de l\u2019appareil", "activer",
        "activar", "aktivieren", "ativar", "активировать", "etkinleştir", "attiva"
    )

    fun request(context: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).writeText(nowMs.toString())
        } catch (_: Throwable) {
        }
    }

    fun pending(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (!f.exists()) false else {
            val at = f.readText().trim().toLongOrNull() ?: 0L
            if (nowMs - at in 0..MAX_AGE_MS) true else { f.delete(); false }
        }
    } catch (_: Throwable) {
        false
    }

    fun finish(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
