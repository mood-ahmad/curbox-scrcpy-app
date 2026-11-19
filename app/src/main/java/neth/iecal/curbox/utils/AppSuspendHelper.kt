package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import neth.iecal.curbox.utils.privileged.PrivilegedRunner

object AppSuspendHelper {

    // Todo: Sometimes user starts focus mode, apps get suspended but in the midst
    // of that the privileged backend becomes unavailable. This can leave apps
    // suspended until privileged access is restored.
    fun suspendApps(packages: List<String>) {
        // executePmCommand(packages, "suspend")
    }

    fun unsuspendApps(packages: List<String>) {
        executePmCommand(packages, "unsuspend")
    }

    fun unsuspendAllApps(context: Context) {
        if (!isPrivilegedAccessAvailable()) {
            return
        }

        Thread {
            try {
                val allPackages = getInstalledPackagesSafe(context)
                executePmCommand(
                    packages = allPackages,
                    commandType = "unsuspend"
                )
            } catch (e: Exception) {
                Log.e(
                    "AppSuspendHelper",
                    "Failed to unsuspend all apps",
                    e
                )
            }
        }.start()
    }

    fun getPackagesToSuspend(
        context: Context,
        blockMode: FocusBlockMode,
        groupPackages: Set<String>,
        essentialPackages: Set<String>
    ): List<String> {
        return if (
            blockMode == FocusBlockMode.BLOCK_SELECTED
        ) {
            groupPackages.toList()
        } else {
            val allPackages =
                getInstalledPackagesSafe(context)

            allPackages.filter {
                it !in groupPackages &&
                    it !in essentialPackages
            }
        }
    }

    private fun getInstalledPackagesSafe(
        context: Context
    ): List<String> {
        return try {
            context.packageManager
                .getInstalledPackages(0)
                .map { it.packageName }
        } catch (e: Exception) {
            Log.w(
                "AppSuspendHelper",
                "getInstalledPackages failed, " +
                    "falling back to queryIntentActivities",
                e
            )

            val intent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(
                        Intent.CATEGORY_LAUNCHER
                    )
                }

            context.packageManager
                .queryIntentActivities(
                    intent,
                    0
                )
                .map {
                    it.activityInfo.packageName
                }
                .distinct()
        }
    }

    private fun executePmCommand(
        packages: List<String>,
        commandType: String
    ) {
        if (
            !isPrivilegedAccessAvailable() ||
            packages.isEmpty()
        ) {
            return
        }

        Thread {
            packages
                .chunked(40)
                .forEach { chunk ->

                    val command =
                        "pm $commandType " +
                            chunk.joinToString(" ")

                    PrivilegedRunner.executeCommand(
                        command = command,
                        listener = object :
                            PrivilegedBackend.CommandResultListener {

                            override fun onCommandError(
                                error: String
                            ) {
                                Log.e(
                                    "AppSuspendHelper",
                                    "pm $commandType failed: $error"
                                )
                            }
                        }
                    )
                }
        }.start()
    }

    /**
     * Returns true when any privileged command backend is ready.
     *
     * During the foundation phase this still means Shizuku. Later,
     * Direct ADB can satisfy the same check without changing callers.
     */
    fun isPrivilegedAccessAvailable(): Boolean {
        return PrivilegedRunner.isAvailable()
    }

    /**
     * Kept temporarily for compatibility with existing callers while
     * the project migrates from Shizuku-specific checks to the unified
     * privileged-access layer.
     */
    fun isShizukuAvailable(): Boolean {
        return isPrivilegedAccessAvailable()
    }
}
