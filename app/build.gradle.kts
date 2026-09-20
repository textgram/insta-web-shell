import java.io.RandomAccessFile
import java.util.Random

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.instaweb.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.instaweb.shell"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress.add("bin")
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("build/generated/padAssets")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.ktx)
}

val padSizeBytes = 64L * 1024 * 1024

val generatePadAsset by tasks.registering {
    val outputDir = File(projectDir, "build/generated/padAssets")
    val outputFile = File(outputDir, "pad.bin")
    outputs.file(outputFile)
    doLast {
        if (outputFile.length() != padSizeBytes) {
            outputDir.mkdirs()
            val random = Random(20260918)
            val buffer = ByteArray(1024 * 1024)
            RandomAccessFile(outputFile, "rw").use { file ->
                file.setLength(0)
                repeat((padSizeBytes / buffer.size).toInt()) {
                    random.nextBytes(buffer)
                    file.write(buffer)
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(generatePadAsset) }
