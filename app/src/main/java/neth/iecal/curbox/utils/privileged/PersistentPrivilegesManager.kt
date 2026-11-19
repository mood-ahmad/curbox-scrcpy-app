package neth.iecal.curbox.utils.privileged

import android.content.Context
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.services.AppBlockerService

/**
 * Applies CurBox privileges that can persist after the privileged shell
 * operation itself has finished.
 *
 * Runtime/user-facing permissions such as POST_NOTIFICATIONS deliberately do
 * not belong here. They continue to use Android's normal permission flow.
 */
object PersistentPrivilegesManager {

    data class Result(
        val success: Boolean,
        val output: String = "",
        val error: String? = null
    )

    interface Listener {
        fun onComplete(
            result: Result
        )
    }

    /**
     * Applies the persistent privileges CurBox can configure through an
     * available privileged backend.
     *
     * The command is idempotent:
     * - SYSTEM_ALERT_WINDOW is set to allow.
     * - DND policy access is allowed for CurBox.
     * - WRITE_SECURE_SETTINGS is granted only on builds that support it.
     * - AppBlockerService is appended to the existing enabled accessibility
     *   services list without removing other services.
     */
    fun apply(
        context: Context,
        listener: Listener
    ) {
        val appContext =
            context.applicationContext

        val packageName =
            appContext.packageName

        val accessibilityService =
            "$packageName/${AppBlockerService::class.java.name}"

        val secureSettingsGrant =
            if (
                BuildConfig
                    .SUPPORTS_WRITE_SECURE_SETTINGS
            ) {
                """
                pm grant $packageName android.permission.WRITE_SECURE_SETTINGS
                """.trimIndent()
            } else {
                ""
            }

        val command =
            """
            appops set $packageName SYSTEM_ALERT_WINDOW allow
            $secureSettingsGrant
            cmd notification allow_dnd $packageName

            CURRENT_ACC_SVCS=${'$'}(settings get secure enabled_accessibility_services)

            if [ "${'$'}CURRENT_ACC_SVCS" = "null" ] || [ -z "${'$'}CURRENT_ACC_SVCS" ]; then
                NEW_ACC_SVCS="$accessibilityService"
            else
                NEW_ACC_SVCS="${'$'}CURRENT_ACC_SVCS"

                case ":${'$'}CURRENT_ACC_SVCS:" in
                    *":$accessibilityService:"*)
                        ;;
                    *)
                        NEW_ACC_SVCS="${'$'}NEW_ACC_SVCS:$accessibilityService"
                        ;;
                esac
            fi

            settings put secure enabled_accessibility_services "${'$'}NEW_ACC_SVCS"
            settings put secure accessibility_enabled 1
            """.trimIndent()

        PrivilegedRunner.executeCommand(
            command = command,
            listener =
                object :
                    PrivilegedBackend.CommandResultListener {

                    private val output =
                        StringBuilder()

                    override fun onCommandResult(
                        output: String,
                        done: Boolean
                    ) {
                        this.output.append(
                            output
                        )

                        if (!done) {
                            return
                        }

                        listener.onComplete(
                            Result(
                                success = true,
                                output =
                                    this.output
                                        .toString()
                            )
                        )
                    }

                    override fun onCommandError(
                        error: String
                    ) {
                        listener.onComplete(
                            Result(
                                success = false,
                                output =
                                    output.toString(),
                                error =
                                    error
                            )
                        )
                    }
                }
        )
    }
}
