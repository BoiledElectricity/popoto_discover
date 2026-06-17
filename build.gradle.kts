import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.popotomodem"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    implementation("net.java.dev.jna:jna:5.14.0")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.popotomodem.discover.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("popoto-discover")
    archiveClassifier.set("")
}

val packagedJarName = "popoto-discover.jar"
val guiLauncherName = "Popoto Discover"
val cliLauncherName = "popoto-discover"
val packageVersion = providers.gradleProperty("packageVersion").orNull ?: "1.0.0"
val packageModules = "java.base,java.desktop,java.sql"

fun hostOsName(): String = System.getProperty("os.name").lowercase()

fun isWindowsHost(): Boolean = hostOsName().contains("windows")

fun hostPackageType(): String {
    providers.gradleProperty("jpackageType").orNull?.let { return it }
    return when {
        isWindowsHost() -> "msi"
        hostOsName().contains("mac") || hostOsName().contains("darwin") -> "dmg"
        else -> "deb"
    }
}

fun jpackageExecutable(): String {
    val executable = if (isWindowsHost()) "jpackage.exe" else "jpackage"
    return Paths.get(System.getProperty("java.home"), "bin", executable).toString()
}

val shadowJarTask = tasks.named<ShadowJar>("shadowJar")
val jpackageInputDir = layout.buildDirectory.dir("jpackage/input")
val jpackageLauncherDir = layout.buildDirectory.dir("jpackage/launchers")
val jpackageAppImageDir = layout.buildDirectory.dir("jpackage/app-image")
val jpackageInstallerDir = layout.buildDirectory.dir("jpackage/installer")
val jpackageArtifactsDir = layout.buildDirectory.dir("jpackage/artifacts")
val jpackageIconDir = layout.buildDirectory.dir("jpackage/icons")
val linuxAppDir = layout.buildDirectory.dir("appimage/AppDir")
val linuxAppImageFile = jpackageArtifactsDir.map {
    it.file("Popoto-Discover-$packageVersion-x86_64.AppImage")
}
val sourcePngIcon = layout.projectDirectory.file("packaging/icons/popoto-icon.png")
val packageIcon = jpackageIconDir.map {
    when {
        isWindowsHost() -> it.file("popoto-discover.ico")
        hostOsName().contains("mac") || hostOsName().contains("darwin") -> it.file("popoto-discover.icns")
        else -> it.file("popoto-discover.png")
    }
}
val appImageToolUrl = providers.gradleProperty("appImageToolUrl")
    .orElse("https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage")
val appImageToolFile = layout.buildDirectory.file("tools/appimagetool-x86_64.AppImage")

tasks.register<Copy>("prepareJpackageInput") {
    dependsOn(shadowJarTask)
    from(shadowJarTask.flatMap { it.archiveFile }) {
        rename { packagedJarName }
    }
    into(jpackageInputDir)
}

val writeJpackageLaunchers = tasks.register("writeJpackageLaunchers") {
    val cliProperties = jpackageLauncherDir.map { it.file("$cliLauncherName.properties") }
    dependsOn("preparePackageIcon")
    outputs.file(cliProperties)

    doLast {
        val propertiesFile = cliProperties.get().asFile
        propertiesFile.parentFile.mkdirs()
        propertiesFile.writeText(
            """
            main-jar=$packagedJarName
            main-class=com.popotomodem.discover.MainKt
            description=Popoto Discover CLI
            icon=${packageIcon.get().asFile.absolutePath}
            arguments=
            win-console=true
            linux-shortcut=false
            """.trimIndent() + "\n",
        )
    }
}

tasks.register("preparePackageIcon") {
    group = "distribution"
    description = "Prepares the platform-specific package icon."
    inputs.file(sourcePngIcon)
    outputs.file(packageIcon)

    doLast {
        val iconDir = jpackageIconDir.get().asFile
        iconDir.mkdirs()

        when {
            isWindowsHost() -> {
                exec {
                    commandLine(
                        "magick",
                        sourcePngIcon.asFile.absolutePath,
                        "-define",
                        "icon:auto-resize=256,128,64,48,32,16",
                        packageIcon.get().asFile.absolutePath,
                    )
                }
            }
            hostOsName().contains("mac") || hostOsName().contains("darwin") -> {
                val iconSet = iconDir.resolve("popoto-discover.iconset")
                delete(iconSet)
                iconSet.mkdirs()
                val sizes = listOf(16, 32, 128, 256, 512)
                for (size in sizes) {
                    exec {
                        commandLine(
                            "sips",
                            "-z",
                            size.toString(),
                            size.toString(),
                            sourcePngIcon.asFile.absolutePath,
                            "--out",
                            iconSet.resolve("icon_${size}x$size.png").absolutePath,
                        )
                    }
                    exec {
                        val retinaSize = size * 2
                        commandLine(
                            "sips",
                            "-z",
                            retinaSize.toString(),
                            retinaSize.toString(),
                            sourcePngIcon.asFile.absolutePath,
                            "--out",
                            iconSet.resolve("icon_${size}x${size}@2x.png").absolutePath,
                        )
                    }
                }
                exec {
                    commandLine(
                        "iconutil",
                        "-c",
                        "icns",
                        iconSet.absolutePath,
                        "-o",
                        packageIcon.get().asFile.absolutePath,
                    )
                }
            }
            else -> {
                copy {
                    from(sourcePngIcon)
                    into(iconDir)
                    rename { "popoto-discover.png" }
                }
            }
        }
    }
}

fun jpackageCommonArgs(outputDir: String, packageType: String): List<String> {
    val args = mutableListOf(
        "--type", packageType,
        "--name", guiLauncherName,
        "--description", "Popoto/PMM discovery and management host tool",
        "--vendor", "Popoto Modem",
        "--app-version", packageVersion,
        "--dest", outputDir,
        "--input", jpackageInputDir.get().asFile.absolutePath,
        "--icon", packageIcon.get().asFile.absolutePath,
        "--main-jar", packagedJarName,
        "--main-class", "com.popotomodem.discover.MainKt",
        "--arguments", "gui",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--add-modules", packageModules,
        "--add-launcher",
        "$cliLauncherName=${jpackageLauncherDir.get().file("$cliLauncherName.properties").asFile.absolutePath}",
    )

    val isAppImage = packageType == "app-image"
    when {
        isWindowsHost() && !isAppImage -> {
            args += listOf("--win-menu", "--win-shortcut")
        }
        hostOsName().contains("linux") && !isAppImage -> {
            args += listOf(
                "--linux-package-name", "popoto-discover",
                "--linux-menu-group", "Utility",
                "--linux-app-category", "utils",
                "--linux-shortcut",
                "--linux-deb-maintainer", "support@popotomodem.com",
                "--linux-package-deps", "libpcap0.8",
            )
        }
    }

    return args
}

tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "Builds a native application image with a bundled Java runtime."
    dependsOn("prepareJpackageInput", "preparePackageIcon", writeJpackageLaunchers)

    doFirst {
        delete(jpackageAppImageDir)
        jpackageAppImageDir.get().asFile.mkdirs()
    }

    commandLine(
        listOf(jpackageExecutable()) +
            jpackageCommonArgs(jpackageAppImageDir.get().asFile.absolutePath, "app-image"),
    )
}

tasks.register<Exec>("jpackageInstaller") {
    group = "distribution"
    description = "Builds the host OS native installer with a bundled Java runtime."
    dependsOn("prepareJpackageInput", "preparePackageIcon", writeJpackageLaunchers)

    doFirst {
        delete(jpackageInstallerDir)
        jpackageInstallerDir.get().asFile.mkdirs()
    }

    commandLine(
        listOf(jpackageExecutable()) +
            jpackageCommonArgs(jpackageInstallerDir.get().asFile.absolutePath, hostPackageType()),
    )
}

tasks.register("patchLinuxDebCliLink") {
    group = "distribution"
    description = "Adds the installed popoto-discover CLI link to the Linux deb package."
    dependsOn("jpackageInstaller")
    onlyIf { hostOsName().contains("linux") }

    doLast {
        val debs = jpackageInstallerDir.get().asFile.listFiles { file ->
            file.isFile && file.extension == "deb"
        }.orEmpty()
        require(debs.isNotEmpty()) { "No deb package found in ${jpackageInstallerDir.get().asFile}" }

        for (deb in debs) {
            val workDir = layout.buildDirectory.dir("jpackage/deb-patch/${deb.nameWithoutExtension}").get().asFile
            delete(workDir)
            workDir.mkdirs()

            exec {
                commandLine("dpkg-deb", "-R", deb.absolutePath, workDir.absolutePath)
            }

            val cliLink = workDir.resolve("usr/local/bin/popoto-discover")
            cliLink.parentFile.mkdirs()
            Files.deleteIfExists(cliLink.toPath())
            Files.createSymbolicLink(cliLink.toPath(), Paths.get("/opt/popoto-discover/bin/popoto-discover"))

            exec {
                commandLine("dpkg-deb", "--build", workDir.absolutePath, deb.absolutePath)
            }
        }
    }
}

tasks.register("downloadAppImageTool") {
    group = "distribution"
    description = "Downloads appimagetool for Linux AppImage packaging."
    onlyIf { hostOsName().contains("linux") }
    outputs.file(appImageToolFile)

    doLast {
        val tool = appImageToolFile.get().asFile
        tool.parentFile.mkdirs()
        URI(appImageToolUrl.get()).toURL().openStream().use { input ->
            tool.outputStream().use { output -> input.copyTo(output) }
        }
        tool.setExecutable(true)
    }
}

tasks.register("prepareLinuxAppDir") {
    group = "distribution"
    description = "Prepares the Linux AppDir used to build the AppImage."
    dependsOn("jpackageAppImage")
    onlyIf { hostOsName().contains("linux") }
    outputs.dir(linuxAppDir)

    doLast {
        val appDir = linuxAppDir.get().asFile
        delete(appDir)
        appDir.mkdirs()

        copy {
            from(jpackageAppImageDir.get().dir(guiLauncherName))
            into(appDir.resolve("opt/popoto-discover"))
        }

        appDir.resolve("AppRun").writeText(
            """
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/opt/popoto-discover/bin/Popoto Discover" "${'$'}@"
            """.trimIndent() + "\n",
        )
        appDir.resolve("AppRun").setExecutable(true)

        appDir.resolve("popoto-discover.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Popoto Discover
            Comment=Popoto/PMM discovery and management
            Exec=AppRun
            Icon=popoto-discover
            Categories=Utility;
            Terminal=false
            """.trimIndent() + "\n",
        )

        copy {
            from(sourcePngIcon)
            into(appDir)
            rename { "popoto-discover.png" }
        }
    }
}

tasks.register<Exec>("linuxAppImage") {
    group = "distribution"
    description = "Builds the Linux AppImage for double-click GUI use."
    dependsOn("prepareLinuxAppDir", "downloadAppImageTool")
    onlyIf { hostOsName().contains("linux") }
    outputs.file(linuxAppImageFile)

    doFirst {
        jpackageArtifactsDir.get().asFile.mkdirs()
        delete(linuxAppImageFile)
    }

    environment("ARCH", "x86_64")
    environment("VERSION", packageVersion)
    environment("APPIMAGE_EXTRACT_AND_RUN", "1")
    commandLine(
        appImageToolFile.get().asFile.absolutePath,
        linuxAppDir.get().asFile.absolutePath,
        linuxAppImageFile.get().asFile.absolutePath,
    )
}

tasks.register("packageHost") {
    group = "distribution"
    description = "Builds the host OS release artifacts."
    dependsOn("jpackageInstaller")
    if (hostOsName().contains("linux")) {
        dependsOn("linuxAppImage", "patchLinuxDebCliLink")
    }
}
