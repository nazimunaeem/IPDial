package com.ipdial.util

import android.os.Build
import java.io.File

object DeviceUtil {

    /**
     * Comprehensive emulator detection including BlueStacks, Nox, and generic QEMU.
     */
    fun isEmulator(): Boolean {
        val all = buildString {
            append(Build.PRODUCT).append('|')
            append(Build.MODEL).append('|')
            append(Build.MANUFACTURER).append('|')
            append(Build.HARDWARE).append('|')
            append(Build.BRAND).append('|')
            append(Build.DEVICE).append('|')
            append(Build.FINGERPRINT)
        }.lowercase()

        val isGenericEmulator = all.contains("sdk") ||
                all.contains("emulator") ||
                all.contains("genymotion") ||
                all.contains("goldfish") ||
                all.contains("ranchu") ||
                all.contains("qemu") ||
                all.contains("mumu") ||
                all.contains("vmos") ||
                all.contains("google_sdk") ||
                all.contains("x86_64") ||
                all.contains("kvm") ||
                all.contains("nox") ||
                all.contains("bluestacks") ||
                all.contains("ldplayer")

        if (isGenericEmulator) return true

        // ro.kernel.qemu=1 is the definitive QEMU/emulator marker.
        if (runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java)
                get.invoke(null, "ro.kernel.qemu") == "1"
            }.getOrDefault(false)
        ) return true

        // QEMU guest device nodes / marker files.
        val markerFiles = listOf(
            "/dev/goldfish_pipe",
            "/dev/qemu_pipe",
            "/dev/qemu_trace",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/system/bin/qemu-props"
        )
        return markerFiles.any { File(it).exists() }
    }
}
