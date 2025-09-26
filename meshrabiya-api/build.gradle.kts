plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

// Ensure Kotlin uses the Java 21 toolchain so generated AIDL stubs and
// Kotlin compilation share the same JVM target.
kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.ustadmobile.meshrabiya.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    // No code here — this module only exposes AIDL sources to consumers. The
    // Android library plugin ensures AIDL files under src/main/aidl are
    // processed and the generated binder stubs are packaged in the AAR.
}

// Configure Java compatibility and Kotlin jvmTarget explicitly to avoid
// inconsistent-target errors when consumers compile against different JVMs.
android.compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Set Kotlin jvmTarget for Kotlin compilation tasks in this module.
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    // Use the newer compilerOptions DSL to set Kotlin's JVM target.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Post-process the produced AAR(s) to include the raw AIDL sources under an
// `aidl/` directory in the AAR. This allows consumers to generate binder
// stubs from the AAR's aidl sources.
afterEvaluate {
    // Simpler: create a debug-only task to attach AIDL into the debug AAR.
    tasks.register("bundleAarWithAidl") {
        dependsOn("bundleDebugAar")
        doLast {
            val aarDir = file("${buildDir}/outputs/aar")
            val variantName = "debug"
            val aarFile = aarDir.listFiles()?.firstOrNull { it.name.endsWith("-$variantName.aar") }
                ?: aarDir.resolve("${project.name}-$variantName.aar")

            if (!aarFile.exists()) {
                logger.warn("AAR not found at expected path: ${aarFile.absolutePath}")
                return@doLast
            }

            val tmp = file("${buildDir}/tmp/aar-mod-$variantName")
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()

            copy {
                from(zipTree(aarFile))
                into(tmp)
            }

            val aidlSrc = file("src/main/aidl")
            if (aidlSrc.exists()) {
                copy {
                    from(aidlSrc)
                    into(File(tmp, "aidl"))
                }
            }

            ant.invokeMethod("jar", mapOf("destfile" to aarFile.absolutePath, "basedir" to tmp.absolutePath))
            logger.lifecycle("Attached AIDL sources into AAR: ${aarFile.absolutePath}")
        }
    }

    val distributeAidlToConsumers = tasks.register("distributeAidlToConsumers") {
        val consumers = listOf(":orbotservice", ":abhaya-sensor-android:app")
        val variants = listOf("debug", "release")
        dependsOn("bundleAarWithAidl")

        doLast {
            val aidlSrc = file("src/main/aidl")
            if (!aidlSrc.exists()) {
                logger.warn("No AIDL sources found in meshrabiya-api/src/main/aidl; nothing to distribute.")
                return@doLast
            }

            consumers.forEach { projPath ->
                val consumer = rootProject.findProject(projPath)
                if (consumer == null) {
                    logger.warn("Consumer project not found: $projPath; skipping")
                    return@forEach
                }

                variants.forEach { variant ->
                    val dest = File(consumer.projectDir, "build/generated/meshrabiya-aidl/${variant}")
                    if (!dest.exists()) dest.mkdirs()
                    copy {
                        from(aidlSrc)
                        into(dest)
                    }
                    logger.lifecycle("Copied AIDL to consumer $projPath -> ${dest.absolutePath}")
                }
            }
        }
    }

    // Wire the distribution task to consumer assemble tasks and ensure consumer
    // clean tasks run after distribution completes. Use afterEvaluate so consumer
    // tasks are available.
    afterEvaluate {
        val consumers = listOf(":orbotservice", ":abhaya-sensor-android:app")
        val variants = listOf("debug", "release")

        consumers.forEach { projPath ->
            val consumer = rootProject.findProject(projPath) ?: return@forEach

            // Ensure consumers are cleaned before distribution runs so the generated
            // AIDL folder is created in a clean state. Match any clean* task (clean,
            // cleanDebug, etc.) to be robust to variant-specific clean tasks.
            distributeAidlToConsumers.configure {
                dependsOn(consumer.tasks.matching { it.name.startsWith("clean", ignoreCase = true) })
            }

            // Make each consumer assemble<Variant> depend on distribution so distribution
            // runs once before any assemble task that needs it.
            variants.forEach { variant ->
                val assembleName = "assemble${variant.replaceFirstChar { it.uppercase() }}"
                consumer.tasks.matching { it.name == assembleName }.configureEach {
                    dependsOn(distributeAidlToConsumers)
                }
            }
        }
    }
}
