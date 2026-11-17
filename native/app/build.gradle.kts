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
    // Android Lint 开严格模式：警告也算失败，CI 里和单元测试一起跑（./gradlew lintDebug）
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        // “有新版本可用”这类检查要联网、会随时间变化，放在 CI 里会让同一份代码今天过明天不过；版本升级交给 dependabot
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
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

// Kotlin 写法检查（ktlint，官方 Kotlin 风格加 Compose 的命名习惯，见 native/.editorconfig）
// ./gradlew ktlintCheck 检查，./gradlew ktlintFormat 自动改
val ktlint: Configuration by configurations.creating
dependencies { ktlint(libs.ktlint.cli) }
val ktlintSources = listOf("src/**/*.kt", "*.kts", "../*.kts")
tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "检查 Kotlin 写法"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = listOf("--relative") + ktlintSources
}
tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "按 ktlint 自动整理 Kotlin 写法"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    // ktlint 需要访问 JDK 内部 API 才能改文件
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args = listOf("-F") + ktlintSources
}
