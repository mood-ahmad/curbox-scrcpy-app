package neth.iecal.curbox.remotecontrol

sealed class RemoteControlState {
    object Idle : RemoteControlState()
    object Starting : RemoteControlState()

    data class Ready(
        val localUrl: String?,
        val publicUrl: String?
    ) : RemoteControlState()

    data class Error(
        val message: String
    ) : RemoteControlState()

    object Stopping : RemoteControlState()
}
