package com.popotomodem.discover

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        PopotoCli().run(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

private class PopotoCli {
    fun run(rawArgs: List<String>) {
        if (rawArgs.isEmpty() || rawArgs.any { it == "-h" || it == "--help" }) {
            usage()
            return
        }

        val args = rawArgs.toMutableList()
        var secretFile = Protocol.DEFAULT_SECRET_FILE
        var noAuth = false

        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--secret-file" -> {
                    secretFile = args.removeOptionWithValue(index, "--secret-file")
                    continue
                }
                "--no-auth" -> {
                    args.removeAt(index)
                    noAuth = true
                    continue
                }
            }
            index++
        }

        val command = args.removeFirstOrNull() ?: "discover"
        when (command) {
            "discover" -> discover(args, secretFile, noAuth)
            else -> throw IllegalArgumentException("unknown command '$command'")
        }
    }

    private fun discover(args: MutableList<String>, secretFile: String, noAuth: Boolean) {
        var timeout = Protocol.DEFAULT_TIMEOUT_SECONDS
        var transport = TransportMode.AUTO
        val interfaces = mutableListOf<String>()
        var retries = 3

        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--timeout" -> {
                    timeout = args.removeOptionWithValue(index, "--timeout").toDouble()
                    continue
                }
                "--transport" -> {
                    transport = TransportMode.parse(args.removeOptionWithValue(index, "--transport"))
                    continue
                }
                "-i", "--interface" -> {
                    interfaces += args.removeOptionWithValue(index, args[index])
                    continue
                }
                "--retries" -> {
                    retries = args.removeOptionWithValue(index, "--retries").toInt()
                    continue
                }
                else -> throw IllegalArgumentException("unknown discover option '${args[index]}'")
            }
        }

        val secret = if (noAuth) {
            System.err.println("WARNING: running without authentication is insecure")
            null
        } else {
            loadSecret(secretFile)
        }

        val devices = Discoverer().discover(
            DiscoveryOptions(
                timeoutSeconds = timeout,
                secret = secret,
                transportMode = transport,
                interfaces = interfaces,
                retries = retries,
            ),
        )

        if (devices.isEmpty()) {
            println("No Popoto devices discovered.")
            return
        }

        println()
        println("Discovered ${devices.size} device(s):")
        for (device in devices) {
            printDevice(device)
        }
    }

    private fun printDevice(device: Device) {
        val ip = device.text("ip").orEmpty()
        val port = device.text("http_port")?.toIntOrNull() ?: 80
        val url = if (port == 80) "http://$ip/" else "http://$ip:$port/"

        println("----")
        println(" Name:            ${device.text("name")}")
        println(" Model:           ${device.text("model")}")
        println(" Serial:          ${device.text("serial")}")
        println(" Device ID:       ${device.text("device_id")}")
        println(" IP:              $ip")
        println(" MAC:             ${device.text("mac")}")
        println(" mDNS Hostname:   ${device.text("mdns_hostname")}")
        println(" Identity source: ${device.text("identity_source")}")
        println(" FW:              ${device.text("fw")}")
        println(" URL:             $url")
        if (device.paths.isNotEmpty()) {
            val pathText = device.paths.joinToString(", ") { path ->
                path.transport + (path.interfaceName?.let { "@$it" } ?: "")
            }
            println(" Discovered via:  $pathText")
        }
    }

    private fun loadSecret(secretFile: String): String {
        val path = Path.of(secretFile)
        if (!path.exists()) {
            throw IllegalArgumentException(
                "secret file not found: $secretFile; create it or pass --no-auth for development",
            )
        }
        val secret = Files.readString(path).trim()
        if (secret.length < 16) {
            throw IllegalArgumentException("secret must be at least 16 characters")
        }
        return secret
    }

    private fun MutableList<String>.removeOptionWithValue(index: Int, option: String): String {
        if (index + 1 >= size) {
            throw IllegalArgumentException("$option requires a value")
        }
        removeAt(index)
        return removeAt(index)
    }

    private fun usage() {
        println(
            """
            Popoto discovery Kotlin CLI

            Usage:
              popoto-discover [--secret-file PATH] [--no-auth] discover [options]

            Discover options:
              --timeout SECONDS       Discovery timeout, default ${Protocol.DEFAULT_TIMEOUT_SECONDS}
              --transport MODE        auto, udp, l2, or all; default auto
              -i, --interface NAME    Interface to probe; may be repeated
              --retries N             Probe bursts during timeout, default 3

            Raw Ethernet discovery uses libpcap/Npcap and usually needs elevated privileges.
            """.trimIndent(),
        )
    }
}
