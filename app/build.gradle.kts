import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.FileInputStream
import java.util.*

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
}

kotlin { jvmToolchain(21) }

val orbotBaseVersionCode = 1750300200
fun getVersionName(): String {
    // Gets the version name from the latest Git tag
    return providers.exec {
        commandLine("git", "describe", "--tags", "--always")
    }.standardOutput.asText.get().trim()
}

android {
    namespace = "org.torproject.android"
    compileSdk = 36

    defaultConfig {
        applicationId = namespace
        versionCode = orbotBaseVersionCode
        versionName = getVersionName()
        minSdk = 24
        targetSdk = 36
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        flavorDimensions += "free"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "armeabi-v7a", "x86_64", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions { 
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                it.testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = true
                }
            }
        }
    }
    
    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.canRead()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }
            if (!keystoreProperties.stringPropertyNames().isEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.txt")
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    productFlavors {
        create("fullperm") { dimension = "free" }
        create("nightly") {
            dimension = "free"
            // overwrites defaults from defaultConfig
            applicationId = "org.torproject.android.nightly"
            versionCode = (Date().time / 1000).toInt()
        }
    }

    // Exclude .bak files from Android resource processing
    androidResources {
        ignoreAssetsPattern = "!*.bak:!**/*.bak"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/androidx.localbroadcastmanager_localbroadcastmanager.version",
                "**/*.bak"  // Exclude .bak files from APK packaging
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "InvalidPackage"
        htmlReport = true
        lintConfig = file("../lint.xml")
        textReport = false
        xmlReport = false
    }
}

// Increments versionCode by ABI type
android.applicationVariants.all {
    outputs.configureEach {
        if (versionCode == orbotBaseVersionCode) {
            val incrementMap =
                mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 4, "x86_64" to 5)
            val increment = incrementMap[filters.find { it.filterType == "ABI" }?.identifier] ?: 0
            (this as ApkVariantOutputImpl).versionCodeOverride = orbotBaseVersionCode + increment
        }
    }
}

dependencies {
    implementation(project(":OrbotLib"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":orbotservice"))
    implementation(project(":Meshrabiya:lib-meshrabiya"))
    implementation(libs.android.material)
    implementation(libs.android.volley)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.localbroadcast)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.retrofit.converter)
    implementation(libs.retrofit.lib)
    implementation(libs.rootbeer.lib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.appiconnamechanger)

    // Camera and QR code dependencies (Hybrid approach: QRCode-kotlin + ML Kit)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    
    // QR Code generation and scanning (Pure hybrid: QRCode-kotlin + ML Kit)
    implementation("io.github.g0dkar:qrcode-kotlin:4.5.0")  // Generation only
    implementation("com.google.mlkit:barcode-scanning:17.2.0")  // Scanning + computer vision

    testImplementation(libs.junit.jupiter)
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.screengrab)
    androidTestUtil(libs.androidx.orchestrator)
}

// Configure JaCoCo test coverage for app module
tasks.register<JacocoReport>("jacocoTestReport") {
    description = "Generates code coverage report for app module unit tests"
    group = "verification"
    
    dependsOn("test")
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    
    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/*$\$serializer.*",
        "**/*.bak"  // Exclude .bak files from coverage reports
    )
    
    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    
    val mainSrc = "${project.projectDir}/src/main/java"
    val kotlinSrc = "${project.projectDir}/src/main/kotlin"
    
    sourceDirectories.setFrom(files(listOf(mainSrc, kotlinSrc)))
    classDirectories.setFrom(files(listOf(debugTree)))
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/*/test*.exec")
    })
    
    doLast {
        val htmlReport = reports.html.outputLocation.get().asFile
        if (htmlReport.exists()) {
            println("📊 App module coverage report: file://${htmlReport.absolutePath}/index.html")
        }
    }
}

// .bak file management tasks to prevent Android Resource Manager conflicts
tasks.register<Exec>("moveBakFiles") {
    group = "build setup"
    description = "Move .bak files to temporary storage before resource processing"
    
    commandLine("./pre_build_bak_manager.sh")
    workingDir = project.rootDir
    
    doFirst {
        println("🔧 Moving .bak files to prevent resource conflicts...")
    }
}

tasks.register<Exec>("restoreBakFiles") {
    group = "build cleanup"
    description = "Restore .bak files after resource processing is complete"
    
    commandLine("./post_build_bak_manager.sh")
    workingDir = project.rootDir
    
    doFirst {
        println("🔧 Restoring .bak files to original locations...")
    }
    
    // Always run, even if build fails
    mustRunAfter("moveBakFiles")
}

// Hook into the build lifecycle
tasks.named("preBuild") { 
    dependsOn("copyLicenseToAssets", "moveBakFiles") 
}

// Ensure .bak files are restored after resource processing tasks complete
tasks.matching { it.name.contains("mergeDebugResources") || it.name.contains("mergeReleaseResources") }.configureEach {
    finalizedBy("restoreBakFiles")
}

// Also ensure restoration after APK building tasks
tasks.matching { it.name.contains("assembleDebug") || it.name.contains("assembleRelease") }.configureEach {
    finalizedBy("restoreBakFiles")
}

// Ensure restoration after compilation tasks complete
tasks.matching { it.name.contains("compileDebugKotlin") || it.name.contains("compileReleaseKotlin") }.configureEach {
    finalizedBy("restoreBakFiles")
}

// Comprehensive approach: restore .bak files after any major Android build task
tasks.matching { 
    it.name.startsWith("compile") || 
    it.name.startsWith("merge") || 
    it.name.startsWith("assemble") ||
    it.name.startsWith("bundle") ||
    it.name.contains("Resources") ||
    it.name.contains("Assets")
}.configureEach {
    finalizedBy("restoreBakFiles")
}

tasks.register<Copy>("copyLicenseToAssets") {
    from(layout.projectDirectory.file("LICENSE"))
    into(layout.projectDirectory.dir("src/main/assets"))
    // Copy tasks automatically handle file filtering - no manual exclude needed here
}

// Global exclusion for all copy tasks - this prevents .bak files from being processed
tasks.withType<Copy>().configureEach {
    exclude("**/*.bak")
}

// Workaround for missing R class in Kotlin compilation (fixed deprecated buildDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("processFullpermDebugResources")
    val rJar = file("${layout.buildDirectory.get()}/intermediates/compile_and_runtime_not_namespaced_r_class_jar/fullpermDebug/processFullpermDebugResources/R.jar")
    compilerOptions {
        if (rJar.exists()) {
            freeCompilerArgs.add("-Xplugin=${rJar.absolutePath}")
        }
    }
}