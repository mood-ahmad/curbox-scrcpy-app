package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import neth.iecal.curbox.remotecontrol.EventLogger

/** Writes DIRECT_ADB_NAV lines to the Event Log from any process. */
object NavLog {
    fun log(context: Context?, message: String) {
        try {
            if (context != null) EventLogger.init(context)
            EventLogger.log("DIRECT_ADB_NAV", message)
        } catch (_: Throwable) {
        }
    }

    /** Short "who called me" chain, skipping the logger frames. */
    fun caller(skip: Int = 2, depth: Int = 4): String = try {
        Throwable().stackTrace.drop(skip).take(depth).joinToString("<") {
            it.className.substringAfterLast('.') + "." + it.methodName
        }
    } catch (_: Throwable) {
        "?"
    }
}
