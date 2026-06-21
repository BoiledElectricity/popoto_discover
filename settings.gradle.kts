pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "popoto-discover"

include(":shared-core")

val hasAndroidSdk = System.getenv("ANDROID_HOME")?.isNotBlank() == true ||
    System.getenv("ANDROID_SDK_ROOT")?.isNotBlank() == true ||
    file("local.properties").takeIf { it.isFile }
        ?.readText()
        ?.lineSequence()
        ?.any { it.trimStart().startsWith("sdk.dir=") } == true

if (hasAndroidSdk) {
    include(":androidApp")
}
