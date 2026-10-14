package neth.iecal.curbox

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
    neth.iecal.curbox.remotecontrol.RemoteControlManager.autoStartIfEnabled(this)
    neth.iecal.curbox.remotecontrol.RemoteControlGuardian.start(this)
    registerNetworkMonitor()
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

  private fun registerNetworkMonitor() {
    try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          neth.iecal.curbox.remotecontrol.EventLogger.log("NETWORK", "Network changed, restarting service")
          neth.iecal.curbox.remotecontrol.RemoteControlManager.restart(this@Curbox)
        }
      })
    } catch (t: Throwable) {
      neth.iecal.curbox.remotecontrol.EventLogger.log("NETWORK", "Failed to register monitor: ${t.message}")
    }
  }

  private fun isMainProcess(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      return getProcessName() == packageName
    }
    val procName = run {
      try {
        java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
      } catch (e: Exception) {
        packageName
      }
    }
    return procName == packageName
  }
}
