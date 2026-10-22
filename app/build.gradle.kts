plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.geek.umbra"
    // 写完整平台名而不是 compileSdk = 37：Android 17 的平台包目录叫 android-37.0（带小版本号），
    // Android Studio 2025.3 同步时按 37 去找 android-37，报「Could not find compile target android-37」。
    // 升回 AGP 9.4 + 新版 Studio 后可以改回 compileSdk = 37。
    compileSdkVersion("android-37.0")

    defaultConfig {
        // 与 iOS 主 App 的 Bundle ID 相同，服务端推送 / 统计按同一个名字认端。
        applicationId = "com.geek.umbra"
        // Android 9：BiometricPrompt、AutofillService、Keystore AES-GCM 都在 28 起完整可用，
        // 覆盖率 > 95%，省掉一大串 Build.VERSION 分支。
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

// 安装包文件名用对外英文名（Youwo-0.1.0-release.apk）：产品名只在 app/Brand.kt 一处，这里正则读它，不另写一份。
// 不用中文名：非 ASCII 文件名在上传、命令行、部分分发平台上会出问题。
val brandEn: String = Regex("""const val productNameEn = "([^"]+)"""")
    .find(file("src/main/java/com/geek/umbra/app/Brand.kt").readText())!!.groupValues[1]
base {
    archivesName.set("$brandEn-${android.defaultConfig.versionName}")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.webrtc)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
}
