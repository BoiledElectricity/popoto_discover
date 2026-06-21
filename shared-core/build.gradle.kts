import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

group = "com.popoto"
version = "0.1.0"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val isMacHost = System.getProperty("os.name").contains("Mac", ignoreCase = true)
    if (isMacHost) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()

        targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.framework {
                baseName = "SharedCore"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
