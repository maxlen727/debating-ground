plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // 和原来的网页套壳版（Capacitor）同一个包名：原生版直接替换了它（#104）
    namespace = "debating.ground.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "debating.ground.app"
        minSdk = 24
        targetSdk = 37
        // CI（android.yml）用 -PversionCode / -PversionName 传进来：versionCode 是构建序号，每次都比上一次大，手机才会当成更新
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
    }

    // 正式签名：用原来套壳版的同一把密钥（同样的环境变量）。密钥只在 CI 里从仓库 Secrets 解出来，
    // 不进仓库。签名一样，手机才会把原生版当成套壳版的更新，而不是另一个 App（#104）。本地没配就构建未签名的 release 包
    val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // debug 包换个包名后缀，能和正式版同时装着对比
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            // 不混淆：JS 引擎、kotlinx.serialization 都靠反射或固定名字，省下的体积不值得冒这个险
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

// 网页版界面打包进 assets/web/：手机当局域网房主时，没装 App 的同学用浏览器打开邀请链接就能加入（#151）。
// 来源是仓库根目录 npm run build 生成的 dist/；没构建过就是空的，房主手机上打开首页会看到“用 App 加入”的说明页。
// 发版工作流（android.yml）先构建网页版，再检查 APK 里确实带着 web/index.html
val webAssets = layout.buildDirectory.dir("generated/webAssets").get().asFile
val syncWeb = tasks.register<Sync>("syncWeb") {
    from(rootProject.file("../dist"))
    into(webAssets.resolve("web"))
}
android.sourceSets.getByName("main").assets.srcDir(webAssets)
tasks.named("preBuild") { dependsOn(syncWeb) }

// 单元测试要读网页版的样式和图标，确认原生版的令牌没有走样
tasks.withType<Test>().configureEach {
    // 登记成输入：网页版的这两个文件改了，测试要重跑，不能当成“已是最新”跳过
    inputs.file(rootProject.file("../src/styles.css"))
    inputs.file(rootProject.file("../src/components/ui.tsx"))
    inputs.file(rootProject.file("../shared/modes.ts"))
    systemProperty("web.styles", rootProject.file("../src/styles.css").absolutePath)
    systemProperty("web.icons", rootProject.file("../src/components/ui.tsx").absolutePath)
    systemProperty("web.modes", rootProject.file("../shared/modes.ts").absolutePath)
    // 跨实现的标准答案（scripts/native-fixtures.ts 生成）：网页版的存档逻辑或导出改了，测试要重跑
    inputs.dir(file("src/test/resources/fixtures"))
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.javascriptengine)
    implementation(libs.okhttp)
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
