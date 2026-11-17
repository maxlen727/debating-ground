plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 和现在的 Capacitor App 同一个包名：最终直接替换它（#70）
    namespace = "debating.ground.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "debating.ground.app"
        minSdk = 24
        targetSdk = 37
        // CI 用 -PversionCode / -PversionName 传进来，规则和 android/ 一样
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
    }

    buildTypes {
        // 开发期的 debug 包换个包名后缀，能和现在的 App 同时装着对比；切换发布时（M6）再定正式包
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric 渲染界面要用到资源
        unitTests.isIncludeAndroidResources = true
    }
}

// 单元测试要读网页版的样式和图标，确认原生版的令牌没有走样
tasks.withType<Test>().configureEach {
    systemProperty("web.styles", rootProject.file("../src/styles.css").absolutePath)
    systemProperty("web.icons", rootProject.file("../src/components/ui.tsx").absolutePath)
    // 截图存这里，CI 作为构建产物上传，审查时可以直接看
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
