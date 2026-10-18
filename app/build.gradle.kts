import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 动态生成版本号：YYYY.MM.DD.HHmm
val buildTimestamp = SimpleDateFormat("yyyy.MM.dd.HHmm", Locale.getDefault()).format(Date())

android {
    namespace = "com.zhiji.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuejian.reader"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = buildTimestamp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // 仅保留真机架构，减小 APK 体积（sherpa-onnx AAR 内含多套 .so）
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    aaptOptions {
        // .onnx 模型文件不能被压缩，否则 native 侧无法通过 AssetManager 随机读取
        noCompress += "onnx"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 修复：AGP 在 debug 构建中自动注入 android:testOnly="true"
// 导致 Android 13 设备安装失败（INSTALL_FAILED_TEST_ONLY）
tasks.register("fixDebugTestOnly") {
    dependsOn("processDebugManifest")
    doLast {
        val manifestDir = file("$buildDir/intermediates/merged_manifests/debug")
        manifestDir.walkTopDown().filter { it.name == "AndroidManifest.xml" }.forEach { manifestFile ->
            var content = manifestFile.readText()
            val original = content
            content = content.replace("android:testOnly=\"true\"", "android:testOnly=\"false\"")
            if (content != original) {
                manifestFile.writeText(content)
                logger.lifecycle("✓ Fixed: Changed android:testOnly to false in ${manifestFile.name}")
            }
        }
    }
}

tasks.matching {
    it.name.startsWith("package") && it.name.contains("Debug")
}.configureEach {
    dependsOn("fixDebugTestOnly")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // JSON 序列化（用于标签历史持久化）
    implementation("com.google.code.gson:gson:2.10.1")

    // HTTP 客户端（用于 AI 大模型 API 调用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // sherpa-onnx 离线语音合成（本地 AAR，含 Kotlin 绑定 + native 库）
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))

    // Navigation (for future deep links; primary nav is state-based)
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // =============== 智能阅读器 Phase 1 ===============
    // KMP Markdown v1.5：当前阿里云镜像元数据不完整，暂时跳过。
    // 阅读页 MVP 使用自实现的按段落 LazyColumn 渲染。待后续升级镜像后恢复。
    // implementation("io.github.huarangmeng:markdown-parser:1.5.0")
    // implementation("io.github.huarangmeng:markdown-runtime:1.5.0")
    // implementation("io.github.huarangmeng:markdown-renderer:1.5.0")

    // DocumentFile（SAF 选择文件后读取内容）
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
