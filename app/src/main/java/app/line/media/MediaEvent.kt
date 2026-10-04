package app.line.media

sealed class MediaEvent {
    object Connected : MediaEvent()
    data class Participants(val numbers: List<String>) : MediaEvent()
    data class Disconnected(val reason: String) : MediaEvent()
    data class Failed(val message: String) : MediaEvent()
}
