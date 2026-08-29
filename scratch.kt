import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

val hasHardwareAec = AcousticEchoCanceler.isAvailable()
val hasHardwareNs = NoiseSuppressor.isAvailable()
