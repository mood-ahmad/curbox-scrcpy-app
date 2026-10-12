package neth.iecal.curbox

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import neth.iecal.curbox.data.sync.SyncGateway

class Curbox: Application() {
  override fun onCreate() {
    DynamicColors.applyToActivitiesIfAvailable(this)
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
    super.onCreate()
    registerActivityLifecycleCallbacks(WatermarkCallbacks())
    neth.iecal.curbox.remotecontrol.RemoteControlManager.init(this)
    if (isMainProcess()) {
      try {
        SyncGateway.init(this)
        if (SyncGateway.provider.isAvailable) SyncGateway.provider.start()
      } catch (e: Exception) {
        e.printStackTrace()
      }
      neth.iecal.curbox.utils.UsageStatsCleaner.cleanNowIfDue(this)
      neth.iecal.curbox.utils.SettingsChangeDelayUtils.applyDuePendingChangesAsync(this)
    }
  }

  private fun isMainProcess(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      return getProcessName() == packageName
    }
    val procName = run {
      try {
        java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '' }
      } catch (e: Exception) {
        packageName
      }
    }
    return procName == packageName
  }
}
