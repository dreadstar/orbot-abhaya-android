plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

android {
    namespace = "org.torproject.android.service"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testOptions.targetSdk = 36
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = true
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Include AIDL from the meshrabiya-api module so generated binder stubs are
    // available at compile time.
    sourceSets {
        getByName("main") {
            aidl.srcDir(project(":meshrabiya-api").file("src/main/aidl"))
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/androidx.localbroadcastmanager_localbroadcastmanager.version")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable + "InvalidPackage"
        htmlReport = true
        lintConfig = file("../lint.xml")
        textReport = false
        xmlReport = false
    }
}




dependencies {
    api(libs.tor.android)
    // local tor-android:
    // api(files("../../tor-android/tor-android-binary/build/outputs/aar/tor-android-binary-debug.aar"))

    api(project(":OrbotLib")) // Use locally built ipt_proxy+go_tun2socks
    api(libs.guardian.jtorctl)
    implementation(libs.android.shell)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.localbroadcast)
    implementation(libs.androidx.work)
    implementation(libs.androidx.work.kotlin)
    implementation(libs.pcap.core)
    implementation(libs.pcap.factory)
    implementation(files("../libs/geoip.jar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.converter)
    implementation(libs.retrofit.lib)
    
    // Critical missing dependencies for D8 desugaring
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.75")
    implementation("org.bouncycastle:bcutil-jdk18on:1.75")
    // Meshrabiya AIDL API module (provides AIDL interfaces under com.ustadmobile.meshrabiya.api)
    api(project(":meshrabiya-api"))
    
    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.6.0")
    testImplementation("androidx.test:core:1.5.0")
}

// Configure JaCoCo test coverage for orbotservice module
tasks.register<JacocoReport>("jacocoTestReport") {
    description = "Generates code coverage report for orbotservice module unit tests"
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
        "**/*$\$serializer.*"
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
            println("📊 OrbotService module coverage report: file://${htmlReport.absolutePath}/index.html")
        }
    }
}
