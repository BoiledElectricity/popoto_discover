package com.popotomodem.discover

object CaptureDiagnostics {
    private val osName: String = System.getProperty("os.name").lowercase()

    fun rawEthernetFailure(interfaceName: String, detail: String?): String {
        val base = buildString {
            append("raw Ethernet unavailable on ")
            append(interfaceName)
            if (!detail.isNullOrBlank()) {
                append(": ")
                append(detail)
            }
        }
        return when {
            osName.contains("win") -> "$base. Use Install Npcap in Popoto Discover to install the bundled capture driver. If this build does not include bundled Npcap, rebuild the Windows installer with the Npcap OEM installer resource."
            osName.contains("linux") -> "$base. Install libpcap and grant packet-capture permission. The Linux deb installer applies capture capabilities automatically; AppImage or jar users should run with sudo for L2 discovery and flashing."
            osName.contains("mac") || osName.contains("darwin") -> "$base. Use Enable L2 in Popoto Discover to install one-time BPF access."
            else -> base
        }
    }
}
