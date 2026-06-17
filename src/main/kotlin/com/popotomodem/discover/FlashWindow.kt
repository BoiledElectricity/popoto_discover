package com.popotomodem.discover

import java.awt.BorderLayout
import java.awt.Dimension
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.WindowConstants

class FlashWindow(
    private val request: FlashRequest,
) : JFrame("Flash PMM eMMC") {
    private val statusLabel = JLabel("Ready")
    private val progress = JProgressBar(0, 100)
    private val logArea = JTextArea()
    private val closeButton = JButton("Close")

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(760, 520)
        layout = BorderLayout(8, 8)

        statusLabel.border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
        add(statusLabel, BorderLayout.NORTH)

        logArea.isEditable = false
        add(JScrollPane(logArea), BorderLayout.CENTER)

        progress.isStringPainted = true
        progress.value = 0
        progress.string = "0%"
        val bottom = javax.swing.JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            add(progress, BorderLayout.CENTER)
            add(closeButton, BorderLayout.EAST)
        }
        add(bottom, BorderLayout.SOUTH)

        closeButton.isEnabled = false
        closeButton.addActionListener { dispose() }

        pack()
        setLocationRelativeTo(null)
        start()
    }

    private fun start() {
        log("Image: ${request.image.absolutePath}")
        log("Mode: ${if (request.mode == FlashMode.BMAP) "bmap payload" else "full image"}")
        request.bmap?.let { log("Bmap: ${it.absolutePath}") }
        log("Interface: ${request.interfaceName}")
        log("Target: ${request.target.label}")

        object : SwingWorker<Device, FlashEvent>() {
            override fun doInBackground(): Device {
                return FlashWorkflow(request) { event -> publish(event) }.run()
            }

            override fun process(chunks: MutableList<FlashEvent>) {
                for (event in chunks) {
                    handleEvent(event)
                }
            }

            override fun done() {
                closeButton.isEnabled = true
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                try {
                    val device = get()
                    statusLabel.text = "Flash complete: ${device.text("name") ?: device.text("serial") ?: request.target.label}"
                    this@FlashWindow.progress.value = 100
                    this@FlashWindow.progress.string = "100%"
                    log("OK: flash workflow complete")
                    JOptionPane.showMessageDialog(this@FlashWindow, "Flash complete and PMM rediscovered.", "Flash Complete", JOptionPane.INFORMATION_MESSAGE)
                } catch (e: Exception) {
                    val message = e.cause?.message ?: e.message ?: "Unknown error"
                    statusLabel.text = "Flash failed"
                    log("ERROR: $message")
                    JOptionPane.showMessageDialog(this@FlashWindow, message, "Flash Failed", JOptionPane.ERROR_MESSAGE)
                }
            }
        }.execute()
    }

    private fun handleEvent(event: FlashEvent) {
        statusLabel.text = event.message.lineSequence().firstOrNull()?.take(140) ?: event.phase
        if (event.totalBytes > 0) {
            val percent = FlashWorkflow.progressPercent(event)
            progress.value = percent
            progress.string = "$percent%"
        }
        log(event.message)
    }

    private fun log(message: String) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logArea.append("[$stamp] $message\n")
        logArea.caretPosition = logArea.document.length
    }

    companion object {
        fun launch(request: FlashRequest) {
            SwingUtilities.invokeLater {
                FlashWindow(request).isVisible = true
            }
        }
    }
}
