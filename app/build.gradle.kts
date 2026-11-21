import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cmzc.xj_file.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cmzc.xj_file.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 17128076
        versionName = "5.9.9.17128076"
    }

    // release 签名。密码只从 local.properties 或环境变量读取，
    // 不要把密码写进 build.gradle；local.properties 和密钥库都不要提交。
    signingConfigs {
        create("release") {
            // `java` 在 Kotlin DSL 里会被 Gradle 的 java 扩展遮住，所以用顶部 import 的 Properties
            val props = Properties()
            val propFile = rootProject.file("local.properties")
            if (propFile.exists()) propFile.inputStream().use { props.load(it) }
            val storePasswordValue = props.getProperty("XJ_STORE_PASSWORD")
                ?: System.getenv("XJ_STORE_PASSWORD")
                ?: ""
            val keyPasswordValue = props.getProperty("XJ_KEY_PASSWORD")
                ?: System.getenv("XJ_KEY_PASSWORD")
                ?: storePasswordValue
            storeFile = rootProject.file(props.getProperty("XJ_STORE_FILE") ?: "xj.jks")
            storePassword = storePasswordValue
            keyAlias = props.getProperty("XJ_KEY_ALIAS")
                ?: System.getenv("XJ_KEY_ALIAS")
                ?: "key0"
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

// ★ 关键：统一 BouncyCastle 到 bcprov-jdk18on 一个分支，避免 Duplicate class
configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:1.77")
    }
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.32.0")
    implementation("com.github.mwiede:jsch:0.2.20")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // ★ 统一用 bcprov-jdk18on（与 jsch / smbj / jcifs-ng 一致）
    //    删掉了 bcprov-jdk15to18，改用它
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // SVG 解码（平台不支持 SVG，图片查看器要能看）
    implementation("com.caverock:androidsvg-aar:1.4")

    // 阶段五：编码探测。Android 平台没有公开 CharsetDetector，ICU4J 覆盖最全；
    // 只用于 core/text，编辑器 UI 不直接依赖它。
    implementation("com.ibm.icu:icu4j:75.1")

    // 压缩格式：ZIP 用 JDK；TAR/GZIP/BZIP2/7Z 用 Apache Commons Compress。
    // RAR 的创建格式是专有的，Commons Compress 只能读不能写，UI 里仍置灰。
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("org.tukaani:xz:1.9")

    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // ★ 阶段四：Media3 边下边播。
    //    版本必须 <= 1.3.x —— media3 1.4.0 起 AAR 元数据要求 compileSdk 35，
    //    而本工程锁死 compileSdk 34（避免牵连 vivo 适配），升版本会直接编译失败。
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // datasource 是 exoplayer 的传递依赖，这里显式声明只是因为 RemoteDataSource 要直接继承它的接口
    implementation("androidx.media3:media3-datasource:1.3.1")
}