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

    // Distribute canonical AIDL files to consumer modules in this repository.
    // This variant-aware task copies the AIDL files into each consumer's
    // build-generated directory (e.g. build/generated/meshrabiya-aidl/<variant>)
    // so consumers can add that dir to their `aidl.srcDirs` without polluting
    // VCS-tracked source trees.
    tasks.register("distributeAidlToConsumers") {
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
}
