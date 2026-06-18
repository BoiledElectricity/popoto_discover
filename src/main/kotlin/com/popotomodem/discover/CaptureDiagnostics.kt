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
            osName.contains("win") -> "$base. Install the PMM NDIS raw Ethernet driver from the Windows installer, or use the legacy bundled pcap fallback if this build includes it."
            osName.contains("linux") -> "$base. Install libpcap and grant packet-capture permission. The Linux deb installer applies capture capabilities automatically; AppImage or jar users should run with sudo for L2 discovery and flashing."
            osName.contains("mac") || osName.contains("darwin") -> "$base. Popoto Discover automatically requests one-time BPF access on first launch."
            else -> base
        }
    }
}
