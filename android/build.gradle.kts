plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// OneDrive locks files under build/ while syncing, which breaks Gradle. When the project lives in a
// OneDrive folder, keep build output in %LOCALAPPDATA%\AirPointer-build instead.
val localAppData: String? = System.getenv("LOCALAPPDATA")
if (localAppData != null && rootDir.absolutePath.contains("OneDrive", ignoreCase = true)) {
    allprojects {
        layout.buildDirectory.set(File(localAppData, "AirPointer-build/${project.name}"))
    }
}
