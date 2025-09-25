// Top-level build file where you can add configuration options common to all sub-projects/modules.

// TODO: Consider enabling configuration cache to speed up builds
// See: https://docs.gradle.org/9.0.0/userguide/configuration_cache_enabling.html
// Add 'org.gradle.configuration-cache=true' to gradle.properties after testing compatibility

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("jacoco")
}

// Apply JaCoCo to all subprojects for comprehensive code coverage
subprojects {
    apply(plugin = "jacoco")
    
    configure<JacocoPluginExtension> {
        toolVersion = "0.8.10"
    }
    
    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }
    
    // Skip test tasks for modules without test files
    tasks.withType<Test> {
        onlyIf { 
            // Only run if there are actual test source files
            project.fileTree("src/test").files.isNotEmpty() || 
            project.fileTree("src/androidTest").files.isNotEmpty()
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

// Auto-discovering runAllTests task that finds and runs all tests dynamically
tasks.register("runAllTests") {
    description = "Automatically discovers and runs all tests across all submodules and generates code coverage reports"
    group = "verification"
    
    // Clean build outputs first to ensure fresh runs
    dependsOn("clean")
    
    // Automatically discover and depend on all test tasks (include both debug and release)
    dependsOn(provider {
        project.allprojects.flatMap { proj ->
            proj.tasks.withType<Test>().map { task ->
                "${proj.path}:${task.name}"
            }.filter { taskPath ->
                // Include all unit tests, both debug and release
                taskPath.contains("UnitTest", ignoreCase = true) || 
                taskPath.matches(Regex(".*:test$"))
            }
        }
    })
    
    // Generate coverage reports after tests complete  
    finalizedBy(
        ":app:jacocoTestReport",
        ":orbotservice:jacocoTestReport",
        ":Meshrabiya:lib-meshrabiya:jacocoTestReport",
        "aggregatedCoverageReport"
    )

    doLast {
        println("===============================================")
        println("🎯 ORBOT COMPREHENSIVE TEST EXECUTION COMPLETE")
        println("===============================================")
        println()
        
        // Function to parse test results from XML
        fun parseTestResults(xmlFile: File): Triple<Int, Int, Int> {
            if (!xmlFile.exists()) return Triple(0, 0, 0)
            
            return try {
                val content = xmlFile.readText()
                val testsRegex = """tests="(\d+)"""".toRegex()
                val failuresRegex = """failures="(\d+)"""".toRegex()
                val errorsRegex = """errors="(\d+)"""".toRegex()
                
                val tests = testsRegex.find(content)?.groupValues?.get(1)?.toInt() ?: 0
                val failures = failuresRegex.find(content)?.groupValues?.get(1)?.toInt() ?: 0
                val errors = errorsRegex.find(content)?.groupValues?.get(1)?.toInt() ?: 0
                
                Triple(tests, failures, errors)
            } catch (e: Exception) {
                Triple(0, 0, 0)
            }
        }
        
        // Function to get all test results from a build directory
        fun getAllTestResults(buildDir: File): Triple<Int, Int, Int> {
            val testResultsDir = File(buildDir, "test-results")
            if (!testResultsDir.exists()) return Triple(0, 0, 0)
            
            var totalTests = 0
            var totalFailures = 0
            var totalErrors = 0
            
            testResultsDir.walkTopDown()
                .filter { it.name.startsWith("TEST-") && it.extension == "xml" }
                .forEach { xmlFile ->
                    val (tests, failures, errors) = parseTestResults(xmlFile)
                    totalTests += tests
                    totalFailures += failures
                    totalErrors += errors
                }
            
            return Triple(totalTests, totalFailures, totalErrors)
        }
        
        // Collect and report test results
        var totalTests = 0
        var successfulTests = 0
        var failedTests = 0
        
        println("📋 Test Results Summary:")
        
        // App module results
        val (appTests, appFailures, appErrors) = getAllTestResults(file("app/build"))
        val appFailed = appFailures + appErrors
        val appPassed = appTests - appFailed
        totalTests += appTests
        successfulTests += appPassed
        failedTests += appFailed
        
        if (appTests > 0) {
            if (appFailed == 0) {
                println("   ✅ App Module: $appTests tests passed")
            } else {
                println("   ⚠️  App Module: $appPassed passed, $appFailed failed ($appTests total)")
            }
        } else {
            println("   ℹ️  App Module: No tests found")
        }
        
        // OrbotService module results
        val (serviceTests, serviceFailures, serviceErrors) = getAllTestResults(file("orbotservice/build"))
        val serviceFailed = serviceFailures + serviceErrors
        val servicePassed = serviceTests - serviceFailed
        totalTests += serviceTests
        successfulTests += servicePassed
        failedTests += serviceFailed
        
        if (serviceTests > 0) {
            if (serviceFailed == 0) {
                println("   ✅ OrbotService Module: $serviceTests tests passed")
            } else {
                println("   ⚠️  OrbotService Module: $servicePassed passed, $serviceFailed failed ($serviceTests total)")
            }
        } else {
            println("   ℹ️  OrbotService Module: No tests found")
        }
        
        // Meshrabiya module results
        val (meshTests, meshFailures, meshErrors) = getAllTestResults(file("Meshrabiya/lib-meshrabiya/build"))
        val meshFailed = meshFailures + meshErrors
        val meshPassed = meshTests - meshFailed
        totalTests += meshTests
        successfulTests += meshPassed
        failedTests += meshFailed
        
        if (meshTests > 0) {
            if (meshFailed == 0) {
                println("   ✅ Meshrabiya Module: $meshTests tests passed")
            } else {
                println("   ⚠️  Meshrabiya Module: $meshPassed passed, $meshFailed failed ($meshTests total)")
            }
        } else {
            println("   ℹ️  Meshrabiya Module: No tests found")
        }
        
        println()
        println("📊 Coverage Reports Generated:")
        if (totalTests > 0) {
            println("   • App: app/build/reports/jacoco/testFullpermDebugUnitTest/html/index.html")
            println("   • OrbotService: orbotservice/build/reports/jacoco/testDebugUnitTest/html/index.html") 
            println("   • Meshrabiya: Meshrabiya/lib-meshrabiya/build/reports/jacoco/testDebugUnitTest/html/index.html")
        }
        
        val successRate = if (totalTests > 0) (successfulTests * 100) / totalTests else 0
        println()
        println("📋 Overall Status:")
        println("   • Success Rate: $successRate% ($successfulTests/$totalTests)")
        if (successRate >= 90) {
            println("   • Assessment: ✅ Excellent test coverage with high success rate")
        } else if (successRate >= 75) {
            println("   • Assessment: ✅ Good test coverage with most tests passing")
        } else if (successRate >= 50) {
            println("   • Assessment: ⚠️  Moderate success rate - some test fixes needed")
        } else if (totalTests > 0) {
            println("   • Assessment: ❌ Many tests failing - significant fixes required")
        } else {
            println("   • Assessment: ❌ No tests found or major compilation issues")
        }
        
        println("===============================================")
    }
}

// Aggregate coverage report combining all subprojects
tasks.register<JacocoReport>("aggregatedCoverageReport") {
    description = "Generates aggregated code coverage report for all submodules"
    group = "verification"
    
    // Depend on all individual module test reports
    dependsOn(":app:jacocoTestReport", ":orbotservice:jacocoTestReport", ":Meshrabiya:lib-meshrabiya:jacocoTestReport")
    
    val fileFilter = listOf(
        "**/R.class",
        "**/R\$*.class", 
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*"
    )
    
    // Collect sources and classes from all modules
    val allSources = mutableListOf<File>()
    val allClasses = mutableListOf<ConfigurableFileTree>()
    val allExecutionFiles = mutableListOf<File>()
    
    // App module
    val appProject = project(":app")
    allSources.addAll(listOf(
        appProject.file("src/main/java"),
        appProject.file("src/main/kotlin")
    ).filter { it.exists() })
    
    allClasses.add(fileTree(appProject.file("build/tmp/kotlin-classes/debug")) {
        exclude(fileFilter)
    })
    
    val appExecFile = appProject.file("build/outputs/unit_test_code_coverage/fullpermDebugUnitTest/testFullpermDebugUnitTest.exec")
    if (appExecFile.exists()) allExecutionFiles.add(appExecFile)
    
    // OrbotService module  
    val orbotServiceProject = project(":orbotservice")
    allSources.addAll(listOf(
        orbotServiceProject.file("src/main/java"),
        orbotServiceProject.file("src/main/kotlin")
    ).filter { it.exists() })
    
    allClasses.add(fileTree(orbotServiceProject.file("build/tmp/kotlin-classes/debug")) {
        exclude(fileFilter)
    })
    
    val orbotServiceExecFile = orbotServiceProject.file("build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    if (orbotServiceExecFile.exists()) allExecutionFiles.add(orbotServiceExecFile)
    
    // Meshrabiya module
    val meshrabiyaProject = project(":Meshrabiya:lib-meshrabiya")
    allSources.addAll(listOf(
        meshrabiyaProject.file("src/main/java"),
        meshrabiyaProject.file("src/main/kotlin")
    ).filter { it.exists() })
    
    allClasses.add(fileTree(meshrabiyaProject.file("build/tmp/kotlin-classes/debug")) {
        exclude(fileFilter)
    })
    
    val meshrabiyaExecFile = meshrabiyaProject.file("build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    if (meshrabiyaExecFile.exists()) allExecutionFiles.add(meshrabiyaExecFile)
    
    // Configure report sources
    sourceDirectories.setFrom(allSources)
    classDirectories.setFrom(allClasses)
    executionData.setFrom(allExecutionFiles)
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
        
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregated/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated/jacoco.xml"))
        csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated/jacoco.csv"))
    }
    
    doFirst {
        // Clear previous aggregated coverage reports
        delete(layout.buildDirectory.dir("reports/jacoco/aggregated"))
        
        // Debug information
        println("🔍 AGGREGATED COVERAGE DEBUG:")
        println("   Source directories: ${sourceDirectories.files}")
        println("   Class directories: ${classDirectories.files}")
        println("   Execution files: ${executionData.files}")
        println("   Files that exist: ${executionData.files.filter { it.exists() }}")
    }
    
    doLast {
        val htmlReport = reports.html.outputLocation.get().asFile
        val xmlReport = reports.xml.outputLocation.get().asFile
        val csvReport = reports.csv.outputLocation.get().asFile
        
        println("\n🎯 AGGREGATED COVERAGE REPORT GENERATED")
        println("📊 Combined coverage analysis available at:")
        if (htmlReport.exists()) {
            println("   • HTML: file://${htmlReport.absolutePath}/index.html")
        }
        if (xmlReport.exists()) {
            println("   • XML: file://${xmlReport.absolutePath}")
        }
        if (csvReport.exists()) {
            println("   • CSV: file://${csvReport.absolutePath}")
        }
        
        // Also show individual module reports
        println("\n📋 Individual Module Coverage Reports:")
        println("   • App: file://${appProject.file("build/reports/jacoco/jacocoTestReport/html/index.html").absolutePath}")
        println("   • OrbotService: file://${orbotServiceProject.file("build/reports/jacoco/jacocoTestReport/html/index.html").absolutePath}")
        println("   • Meshrabiya: file://${meshrabiyaProject.file("build/reports/jacoco/jacocoTestReport/html/index.html").absolutePath}")
    }
}

// Task to calculate and display coverage percentages
tasks.register<Exec>("calculateCoveragePercentages") {
    description = "Calculates and displays coverage percentages from Jacoco CSV report"
    group = "verification"
    
    dependsOn("aggregatedCoverageReport")
    
    workingDir = projectDir
    commandLine("bash", "calculate_coverage.sh")
    
    // Ensure the CSV report exists before running
    doFirst {
        val csvReport = file("build/reports/jacoco/aggregated/jacoco.csv")
        if (!csvReport.exists()) {
            throw GradleException("Jacoco CSV report not found at ${csvReport.absolutePath}. Run aggregatedCoverageReport first.")
        }
    }
    
    doLast {
        println("\n📊 Coverage percentages have been calculated and saved to coverage_summary.log")
    }
}

// Make runAllTests automatically generate the aggregated coverage report and calculate percentages
tasks.named("runAllTests") {
    finalizedBy("aggregatedCoverageReport")
}

// Make aggregatedCoverageReport automatically calculate percentages when it completes
tasks.named("aggregatedCoverageReport") {
    finalizedBy("calculateCoveragePercentages")
}

// Cross-platform task: assemble a chosen APK variant and list produced APK files
tasks.register("assembleAndListApk") {
    description = "Assembles the selected APK variant and lists produced APK files (portable)"
    group = "distribution"

    // Allow overriding the variant via -PapkVariant=fullpermDebug
    val variantProp = findProperty("apkVariant") as String?
    val apkVariant = variantProp ?: "fullpermDebug"

    // Map a simple variant name to the assemble task path for :app
    val assembleTaskPath = ":app:assemble${apkVariant.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
    dependsOn(assembleTaskPath)

    doLast {
        // Convert a camelCase variant name like `fullpermDebug` into the output subpath `fullperm/debug`
        fun variantToOutputDir(variant: String): String {
            val parts = variant.split(Regex("(?=[A-Z])")).map { it.lowercase() }
            return parts.joinToString("/")
        }

        val outDir = project(":app").layout.buildDirectory.dir("outputs/apk/${variantToOutputDir(apkVariant)}").get().asFile
        val reportFile = layout.buildDirectory.file("artifacts/apks.txt").get().asFile
        reportFile.parentFile.mkdirs()

        if (!outDir.exists() || !outDir.isDirectory) {
            logger.lifecycle("No APK output directory found at: ${outDir.absolutePath}")
            reportFile.writeText("No APK output directory found at: ${outDir.absolutePath}\n")
            return@doLast
        }

        val files = outDir.listFiles()?.filter { it.isFile && (it.extension == "apk" || it.extension == "aab") }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            logger.lifecycle("No APK/AAB files found in ${outDir.absolutePath}")
            reportFile.writeText("No APK/AAB files found in ${outDir.absolutePath}\n")
            return@doLast
        }

        fun humanReadable(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B","K","M","G","T")
            var size = bytes.toDouble()
            var idx = 0
            while (size >= 1024 && idx < units.size - 1) {
                size /= 1024
                idx++
            }
            return String.format("%.1f%s", size, units[idx])
        }

        val sb = StringBuilder()
        sb.append("APK files in ${outDir.absolutePath}:\n")
        files.forEach { f ->
            sb.append("  ${humanReadable(f.length())}\t${f.name}\n")
        }

        logger.lifecycle(sb.toString())
        reportFile.writeText(sb.toString())
    }
}
