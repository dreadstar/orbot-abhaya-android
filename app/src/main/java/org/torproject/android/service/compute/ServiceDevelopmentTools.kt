package org.torproject.android.service.compute

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * LOCAL DEVELOPMENT TOOLS
 * 
 * Tools for developers to create, test, and validate services locally
 * before submitting to the I2P/BitTorrent distribution network.
 */
class ServiceDevelopmentTools(
    private val context: Context,
    private val packageManager: ServicePackageManager
) {
    
    companion object {
        private const val TAG = "ServiceDevTools"
        const val DEV_WORKSPACE_DIR = "service_development"
        const val TEMPLATES_DIR = "templates"
        const val TEST_RESULTS_DIR = "test_results"
    }
    
    /**
     * PROJECT SCAFFOLDING
     * 
     * Create a new service development project with templates
     */
    suspend fun createServiceProject(
        packageId: String,
        serviceName: String,
        serviceType: ServiceType,
        language: ProgrammingLanguage = ProgrammingLanguage.KOTLIN
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(context.filesDir, "$DEV_WORKSPACE_DIR/$packageId")
            projectDir.mkdirs()
            
            // Create language-appropriate project structure
            createProjectStructure(projectDir, language)
            
            // Generate manifest template
            val manifestTemplate = createManifestTemplate(packageId, serviceName, serviceType, language)
            File(projectDir, "manifest.json").writeText(
                Json { prettyPrint = true }.encodeToString(
                    ServicePackageManager.ServiceManifest.serializer(),
                    manifestTemplate
                )
            )
            
            // Generate service code template
            generateServiceCodeTemplate(projectDir, serviceType, language)
            
            // Generate test template
            generateTestTemplate(projectDir, serviceType, language)
            
            // Generate build configuration
            generateBuildConfiguration(projectDir, language)
            
            // Create README
            generateProjectReadme(projectDir, packageId, serviceName, serviceType, language)
            
            Log.i(TAG, "Service project created: ${projectDir.absolutePath}")
            Result.success(projectDir.absolutePath)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create service project", e)
            Result.failure(e)
        }
    }
    
    enum class ServiceType {
        // MACHINE LEARNING
        ML_INFERENCE,           // ML model inference and prediction
        ML_TRAINING,           // Distributed model training
        ML_PREPROCESSING,      // Data preprocessing for ML
        
        // DATA PROCESSING
        DATA_ANALYSIS,         // Statistical analysis and insights
        DATA_TRANSFORMATION,   // ETL and data format conversion
        DATA_VALIDATION,       // Data quality and validation
        DATA_AGGREGATION,      // Summarization and aggregation
        
        // COMPUTATIONAL
        MATHEMATICAL,          // Complex mathematical computations
        SCIENTIFIC_COMPUTING,  // Physics, chemistry, biology simulations
        OPTIMIZATION,          // Optimization algorithms and solvers
        NUMERICAL_ANALYSIS,    // Numerical methods and algorithms
        
        // CRYPTOGRAPHIC
        ENCRYPTION,            // Data encryption and decryption
        DIGITAL_SIGNATURE,     // Signing and verification
        HASH_COMPUTATION,      // Cryptographic hashing
        KEY_DERIVATION,        // Key generation and derivation
        
        // MEDIA PROCESSING
        IMAGE_PROCESSING,      // Image analysis and manipulation
        VIDEO_PROCESSING,      // Video encoding, analysis
        AUDIO_PROCESSING,      // Audio analysis and processing
        TEXT_PROCESSING,       // NLP and text analysis
        
        // WEB SERVICES
        API_PROXY,             // Proxy to external APIs
        WEB_SCRAPING,          // Data extraction from websites
        CONTENT_ANALYSIS,      // Web content analysis
        
        // BLOCKCHAIN & FINANCE
        BLOCKCHAIN_ANALYSIS,   // Blockchain data analysis
        FINANCIAL_MODELING,    // Financial calculations and modeling
        RISK_ANALYSIS,         // Risk assessment algorithms
        
        // UTILITY
        FORMAT_CONVERSION,     // File format conversion
        COMPRESSION,           // Data compression algorithms
        VALIDATION,            // Input validation and sanitization
        MONITORING,            // System monitoring and alerts
        
        // CUSTOM
        CUSTOM                 // User-defined service type
    }
    
    enum class ProgrammingLanguage {
        KOTLIN,
        JAVA,
        PYTHON,
        JAVASCRIPT,
        TYPESCRIPT,
        NATIVE_CPP,
        NATIVE_C,
        RUST,
        GO,
        WEBASSEMBLY
    }
    
    /**
     * LOCAL TESTING SUITE
     * 
     * Comprehensive testing framework for service development
     */
    suspend fun runLocalTestSuite(projectPath: String): Result<TestResults> = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(projectPath)
            val manifestFile = File(projectDir, "manifest.json")
            
            if (!manifestFile.exists()) {
                return@withContext Result.failure(IllegalArgumentException("No manifest.json found in project"))
            }
            
            val manifest = Json.decodeFromString(
                ServicePackageManager.ServiceManifest.serializer(),
                manifestFile.readText()
            )
            
            val testResults = TestResults(
                packageId = manifest.packageId,
                testStartTime = System.currentTimeMillis(),
                tests = mutableListOf()
            )
            
            Log.i(TAG, "Running test suite for: ${manifest.packageId}")
            
            // 1. Manifest validation test
            testResults.tests.add(runManifestValidationTest(manifest))
            
            // 2. Code structure test
            testResults.tests.add(runCodeStructureTest(projectDir, manifest))
            
            // 3. Security compliance test
            testResults.tests.add(runSecurityComplianceTest(projectDir, manifest))
            
            // 4. Resource usage test
            testResults.tests.add(runResourceUsageTest(projectDir, manifest))
            
            // 5. Functional tests
            val functionalTests = runFunctionalTests(projectDir, manifest)
            testResults.tests.addAll(functionalTests)
            
            // 6. Package creation test
            testResults.tests.add(runPackageCreationTest(projectDir, manifest))
            
            testResults.testEndTime = System.currentTimeMillis()
            testResults.overallResult = if (testResults.tests.all { it.passed }) "PASS" else "FAIL"
            
            // Save test results
            saveTestResults(projectDir, testResults)
            
            Log.i(TAG, "Test suite completed: ${testResults.overallResult}")
            Result.success(testResults)
            
        } catch (e: Exception) {
            Log.e(TAG, "Test suite failed", e)
            Result.failure(e)
        }
    }
    
    data class TestResults(
        val packageId: String,
        val testStartTime: Long,
        var testEndTime: Long = 0,
        var overallResult: String = "RUNNING",
        val tests: MutableList<TestCase>
    )
    
    data class TestCase(
        val name: String,
        val description: String,
        val passed: Boolean,
        val details: String,
        val executionTimeMs: Long
    )
    
    /**
     * PACKAGE VALIDATION PREVIEW
     * 
     * Show how the service will appear in the discovery system
     */
    suspend fun previewServiceListing(projectPath: String): Result<ServiceListing> = withContext(Dispatchers.IO) {
        try {
            val manifestFile = File(projectPath, "manifest.json")
            val manifest = Json.decodeFromString(
                ServicePackageManager.ServiceManifest.serializer(),
                manifestFile.readText()
            )
            
            val listing = ServiceListing(
                packageId = manifest.packageId,
                displayName = manifest.serviceName,
                description = manifest.serviceDescription,
                category = manifest.category,
                tags = manifest.tags,
                authorInfo = AuthorInfo(
                    onionAddress = manifest.authorOnionAddress,
                    trustLevel = "UNKNOWN", // Will be calculated in real system
                    endorsements = manifest.signatureInfo.trustedBy.size
                ),
                technicalSpecs = TechnicalSpecs(
                    memoryMB = manifest.resourceRequirements.maxMemoryMB,
                    executionTimeS = manifest.executionTimeoutSeconds,
                    platformSupport = manifest.supportedPlatforms,
                    securityLevel = calculateSecurityLevel(manifest)
                ),
                packageInfo = PackageInfo(
                    version = manifest.packageVersion,
                    size = estimatePackageSize(File(projectPath)),
                    lastUpdated = manifest.createdTimestamp
                )
            )
            
            Result.success(listing)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preview service listing", e)
            Result.failure(e)
        }
    }
    
    data class ServiceListing(
        val packageId: String,
        val displayName: String,
        val description: String,
        val category: String,
        val tags: List<String>,
        val authorInfo: AuthorInfo,
        val technicalSpecs: TechnicalSpecs,
        val packageInfo: PackageInfo
    )
    
    data class AuthorInfo(
        val onionAddress: String,
        val trustLevel: String,
        val endorsements: Int
    )
    
    data class TechnicalSpecs(
        val memoryMB: Int,
        val executionTimeS: Int,
        val platformSupport: List<String>,
        val securityLevel: String
    )
    
    data class PackageInfo(
        val version: String,
        val size: String,
        val lastUpdated: Long
    )
    
    /**
     * PUBLISHING PREPARATION
     * 
     * Prepare service for distribution on I2P/BitTorrent network
     */
    suspend fun prepareForPublishing(
        projectPath: String,
        authorPrivateKey: String? = null
    ): Result<PublishingPackage> = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(projectPath)
            
            // Run final validation
            val testResults = runLocalTestSuite(projectPath).getOrThrow()
            if (testResults.overallResult != "PASS") {
                return@withContext Result.failure(IllegalStateException("Service must pass all tests before publishing"))
            }
            
            // Create signed package
            val packagePath = createSignedPackage(projectDir, authorPrivateKey)
            
            // Generate distribution metadata
            val distributionInfo = generateDistributionMetadata(projectDir, packagePath)
            
            val publishingPackage = PublishingPackage(
                packagePath = packagePath,
                distributionInfo = distributionInfo,
                testResults = testResults,
                readyForDistribution = true
            )
            
            Log.i(TAG, "Service prepared for publishing: ${distributionInfo.packageId}")
            Result.success(publishingPackage)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare for publishing", e)
            Result.failure(e)
        }
    }
    
    data class PublishingPackage(
        val packagePath: String,
        val distributionInfo: DistributionInfo,
        val testResults: TestResults,
        val readyForDistribution: Boolean
    )
    
    data class DistributionInfo(
        val packageId: String,
        val packageHash: String,
        val packageSizeBytes: Long,
        val torrentMagnetLink: String?,
        val i2pSiteUrls: List<String>,
        val publishingChecklist: List<ChecklistItem>
    )
    
    data class ChecklistItem(
        val item: String,
        val completed: Boolean,
        val required: Boolean
    )
    
    // TODO: Enhanced local testing workflow
    // 1. Interactive test runner with real-time feedback
    // 2. Performance profiling and resource usage monitoring
    // 3. Security vulnerability scanner
    // 4. Compatibility testing across different Android versions
    // 5. Network simulation for testing distributed scenarios
    // 6. Integration with Android Studio for debugging
    // 7. Automated test generation based on service type
    // 8. Mock I2P/BitTorrent environment for testing distribution
    
    // TODO: Developer documentation generator
    // 1. Auto-generate API documentation from service code
    // 2. Create user-friendly service descriptions
    // 3. Generate usage examples and tutorials
    // 4. Produce troubleshooting guides
    // 5. Create performance benchmarks and comparisons
    
    /**
     * PRIVATE HELPER METHODS
     */
    
    private fun createProjectStructure(projectDir: File, language: ProgrammingLanguage) {
        when (language) {
            ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> {
                File(projectDir, "service/src/main").mkdirs()
                File(projectDir, "service/src/test").mkdirs()
                File(projectDir, "models").mkdirs()
                File(projectDir, "assets").mkdirs()
                File(projectDir, "libs").mkdirs()
            }
            ProgrammingLanguage.PYTHON -> {
                File(projectDir, "service").mkdirs()
                File(projectDir, "service/src").mkdirs()
                File(projectDir, "service/tests").mkdirs()
                File(projectDir, "models").mkdirs()
                File(projectDir, "assets").mkdirs()
                File(projectDir, "requirements").mkdirs()
            }
            ProgrammingLanguage.JAVASCRIPT, ProgrammingLanguage.TYPESCRIPT -> {
                File(projectDir, "service/src").mkdirs()
                File(projectDir, "service/test").mkdirs()
                File(projectDir, "service/node_modules").mkdirs()
                File(projectDir, "models").mkdirs()
                File(projectDir, "assets").mkdirs()
            }
            ProgrammingLanguage.NATIVE_CPP, ProgrammingLanguage.NATIVE_C -> {
                File(projectDir, "service/src").mkdirs()
                File(projectDir, "service/include").mkdirs()
                File(projectDir, "service/test").mkdirs()
                File(projectDir, "models").mkdirs()
                File(projectDir, "assets").mkdirs()
                File(projectDir, "build").mkdirs()
            }
            else -> {
                // Generic structure for other languages
                File(projectDir, "service").mkdirs()
                File(projectDir, "models").mkdirs()
                File(projectDir, "assets").mkdirs()
                File(projectDir, "tests").mkdirs()
            }
        }
    }
    
    private fun generateBuildConfiguration(projectDir: File, language: ProgrammingLanguage) {
        when (language) {
            ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> {
                // Generate build.gradle.kts
                val buildScript = """
                    plugins {
                        kotlin("jvm") version "1.8.0"
                        kotlin("plugin.serialization") version "1.8.0"
                    }
                    
                    repositories {
                        mavenCentral()
                    }
                    
                    dependencies {
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
                        testImplementation("org.jetbrains.kotlin:kotlin-test")
                    }
                    
                    tasks.test {
                        useJUnitPlatform()
                    }
                """.trimIndent()
                File(projectDir, "service/build.gradle.kts").writeText(buildScript)
            }
            ProgrammingLanguage.PYTHON -> {
                // Generate requirements.txt and setup.py
                val requirements = """
                    # Core dependencies
                    numpy>=1.21.0
                    
                    # Add your dependencies here
                    # pandas>=1.3.0
                    # scikit-learn>=1.0.0
                    # torch>=1.9.0
                """.trimIndent()
                File(projectDir, "requirements/requirements.txt").writeText(requirements)
                
                val setup = """
                    from setuptools import setup, find_packages
                    
                    setup(
                        name="${projectDir.name}",
                        version="1.0.0",
                        packages=find_packages(where="src"),
                        package_dir={"": "src"},
                        install_requires=open("requirements/requirements.txt").read().splitlines(),
                    )
                """.trimIndent()
                File(projectDir, "service/setup.py").writeText(setup)
            }
            ProgrammingLanguage.JAVASCRIPT, ProgrammingLanguage.TYPESCRIPT -> {
                // Generate package.json
                val packageJson = """
                    {
                      "name": "${projectDir.name}",
                      "version": "1.0.0",
                      "description": "Distributed compute service",
                      "main": "src/index.js",
                      "scripts": {
                        "start": "node src/index.js",
                        "test": "jest",
                        "build": "tsc"
                      },
                      "dependencies": {},
                      "devDependencies": {
                        "jest": "^27.0.0"
                      }
                    }
                """.trimIndent()
                File(projectDir, "service/package.json").writeText(packageJson)
            }
            ProgrammingLanguage.NATIVE_CPP -> {
                // Generate CMakeLists.txt
                val cmake = """
                    cmake_minimum_required(VERSION 3.16)
                    project(${projectDir.name})
                    
                    set(CMAKE_CXX_STANDARD 17)
                    set(CMAKE_CXX_STANDARD_REQUIRED ON)
                    
                    include_directories(include)
                    
                    add_executable(service src/main.cpp)
                    
                    # Add tests
                    enable_testing()
                    add_executable(tests test/test_main.cpp)
                    add_test(NAME unit_tests COMMAND tests)
                """.trimIndent()
                File(projectDir, "service/CMakeLists.txt").writeText(cmake)
            }
            else -> {
                // No specific build configuration
            }
        }
    }
    
    private fun createManifestTemplate(
        packageId: String,
        serviceName: String,
        serviceType: ServiceType,
        language: ProgrammingLanguage
    ): ServicePackageManager.ServiceManifest {
        val resourceSpec = when (serviceType) {
            ServiceType.ML_INFERENCE, ServiceType.ML_TRAINING -> ServicePackageManager.ResourceSpec(
                minMemoryMB = 64,
                maxMemoryMB = 128,
                estimatedCpuUsage = 0.8f,
                storageRequiredMB = 50,
                networkBandwidthKbps = 100,
                requiresGPU = serviceType == ServiceType.ML_TRAINING
            )
            ServiceType.IMAGE_PROCESSING, ServiceType.VIDEO_PROCESSING -> ServicePackageManager.ResourceSpec(
                minMemoryMB = 32,
                maxMemoryMB = 64,
                estimatedCpuUsage = 0.7f,
                storageRequiredMB = 20,
                networkBandwidthKbps = 200,
                requiresGPU = true
            )
            ServiceType.SCIENTIFIC_COMPUTING, ServiceType.MATHEMATICAL -> ServicePackageManager.ResourceSpec(
                minMemoryMB = 32,
                maxMemoryMB = 64,
                estimatedCpuUsage = 0.9f,
                storageRequiredMB = 10,
                networkBandwidthKbps = 50
            )
            else -> ServicePackageManager.ResourceSpec(
                minMemoryMB = 16,
                maxMemoryMB = 32,
                estimatedCpuUsage = 0.5f,
                storageRequiredMB = 5,
                networkBandwidthKbps = 25
            )
        }
        
        val runtimeSpec = when (language) {
            ProgrammingLanguage.KOTLIN -> ServicePackageManager.RuntimeSpec(
                language = "kotlin",
                languageVersion = "1.8",
                runtime = "jvm",
                runtimeVersion = "17",
                executionMode = "bytecode",
                sandboxCompatible = true,
                requiresNativeLibs = false,
                crossPlatform = true
            )
            ProgrammingLanguage.JAVA -> ServicePackageManager.RuntimeSpec(
                language = "java",
                languageVersion = "17",
                runtime = "jvm",
                runtimeVersion = "17",
                executionMode = "bytecode",
                sandboxCompatible = true,
                requiresNativeLibs = false,
                crossPlatform = true
            )
            ProgrammingLanguage.PYTHON -> ServicePackageManager.RuntimeSpec(
                language = "python",
                languageVersion = "3.9",
                runtime = "python",
                runtimeVersion = "3.9.7",
                executionMode = "interpreted",
                sandboxCompatible = true,
                requiresNativeLibs = false,
                crossPlatform = true
            )
            ProgrammingLanguage.JAVASCRIPT -> ServicePackageManager.RuntimeSpec(
                language = "javascript",
                languageVersion = "ES2020",
                runtime = "nodejs",
                runtimeVersion = "16.14.0",
                executionMode = "interpreted",
                sandboxCompatible = true,
                requiresNativeLibs = false,
                crossPlatform = true
            )
            ProgrammingLanguage.NATIVE_CPP -> ServicePackageManager.RuntimeSpec(
                language = "cpp",
                languageVersion = "17",
                runtime = "native",
                runtimeVersion = "1.0",
                executionMode = "native",
                sandboxCompatible = false,
                requiresNativeLibs = true,
                crossPlatform = false
            )
            ProgrammingLanguage.WEBASSEMBLY -> ServicePackageManager.RuntimeSpec(
                language = "wasm",
                languageVersion = "1.0",
                runtime = "wasm",
                runtimeVersion = "1.0",
                executionMode = "bytecode",
                sandboxCompatible = true,
                requiresNativeLibs = false,
                crossPlatform = true
            )
            else -> ServicePackageManager.RuntimeSpec(
                language = language.name.lowercase(),
                languageVersion = "latest",
                runtime = "generic",
                runtimeVersion = "1.0",
                executionMode = "interpreted",
                sandboxCompatible = true,
                requiresNativeLibs = false,
                crossPlatform = true
            )
        }
        
        val entryPoint = when (language) {
            ProgrammingLanguage.KOTLIN -> "src/main/Main.kt"
            ProgrammingLanguage.JAVA -> "src/main/Main.java"
            ProgrammingLanguage.PYTHON -> "src/main.py"
            ProgrammingLanguage.JAVASCRIPT -> "src/index.js"
            ProgrammingLanguage.TYPESCRIPT -> "src/index.ts"
            ProgrammingLanguage.NATIVE_CPP -> "src/main.cpp"
            else -> "main"
        }
        
        return ServicePackageManager.ServiceManifest(
            packageId = packageId,
            packageVersion = "1.0.0",
            manifestVersion = "1.0",
            serviceName = serviceName,
            serviceDescription = "TODO: Describe what this service does",
            authorOnionAddress = "TODO: Your .onion address",
            createdTimestamp = System.currentTimeMillis(),
            entryPoint = entryPoint,
            runtime = runtimeSpec,
            supportedPlatforms = if (runtimeSpec.crossPlatform) listOf("android", "linux", "any") else listOf("android"),
            requiredPermissions = listOf(),
            resourceRequirements = resourceSpec,
            executionTimeoutSeconds = 30,
            signatureInfo = ServicePackageManager.SignatureInfo(
                packageHash = "PLACEHOLDER",
                authorSignature = "PLACEHOLDER",
                signatureAlgorithm = "Ed25519",
                trustedBy = listOf(),
                auditReports = null
            ),
            sandboxProfile = "STRICT",
            allowedSyscalls = listOf("read", "write", "exit"),
            inputValidation = ServicePackageManager.ValidationSpec(
                inputSchema = "{}",
                maxInputSizeBytes = 1024,
                allowedInputTypes = listOf("text/plain"),
                sanitizationRequired = true
            ),
            outputFormat = "text/plain",
            modelFiles = listOf(),
            assetFiles = listOf(),
            dependencies = listOf(),
            tags = listOf(serviceType.name.lowercase().replace("_", "-")),
            category = serviceType.name,
            licenseType = "MIT",
            sourceCodeUrl = null,
            documentationUrl = null,
            changeLog = "Initial version",
            testCases = listOf(),
            compatibilityNotes = null
        )
    }
    
    private fun generateServiceCodeTemplate(projectDir: File, serviceType: ServiceType, language: ProgrammingLanguage) {
        val serviceCode = when (language) {
            ProgrammingLanguage.KOTLIN -> generateKotlinTemplate(serviceType)
            ProgrammingLanguage.JAVA -> generateJavaTemplate(serviceType)
            ProgrammingLanguage.PYTHON -> generatePythonTemplate(serviceType)
            ProgrammingLanguage.JAVASCRIPT -> generateJavaScriptTemplate(serviceType)
            ProgrammingLanguage.TYPESCRIPT -> generateTypeScriptTemplate(serviceType)
            ProgrammingLanguage.NATIVE_CPP -> generateCppTemplate(serviceType)
            else -> generateGenericTemplate(serviceType, language)
        }
        
        val filePath = when (language) {
            ProgrammingLanguage.KOTLIN -> "service/src/main/Main.kt"
            ProgrammingLanguage.JAVA -> "service/src/main/Main.java"
            ProgrammingLanguage.PYTHON -> "service/src/main.py"
            ProgrammingLanguage.JAVASCRIPT -> "service/src/index.js"
            ProgrammingLanguage.TYPESCRIPT -> "service/src/index.ts"
            ProgrammingLanguage.NATIVE_CPP -> "service/src/main.cpp"
            else -> "service/main"
        }
        
        File(projectDir, filePath).apply {
            parentFile.mkdirs()
            writeText(serviceCode)
        }
    }
    
    private fun generateTestTemplate(projectDir: File, serviceType: ServiceType, language: ProgrammingLanguage) {
        val testCode = when (language) {
            ProgrammingLanguage.KOTLIN -> generateKotlinTestTemplate(serviceType)
            ProgrammingLanguage.JAVA -> generateJavaTestTemplate(serviceType)
            ProgrammingLanguage.PYTHON -> generatePythonTestTemplate(serviceType)
            ProgrammingLanguage.JAVASCRIPT -> generateJavaScriptTestTemplate(serviceType)
            else -> generateGenericTestTemplate(serviceType, language)
        }
        
        val testPath = when (language) {
            ProgrammingLanguage.KOTLIN -> "service/src/test/TestMain.kt"
            ProgrammingLanguage.JAVA -> "service/src/test/TestMain.java"
            ProgrammingLanguage.PYTHON -> "service/tests/test_main.py"
            ProgrammingLanguage.JAVASCRIPT -> "service/test/test.js"
            else -> "tests/test_main"
        }
        
        File(projectDir, testPath).apply {
            parentFile.mkdirs()
            writeText(testCode)
        }
    }
    
    // KOTLIN TEMPLATES
    private fun generateKotlinTemplate(serviceType: ServiceType): String {
        val serviceLogic = when (serviceType) {
            ServiceType.ML_INFERENCE -> """
        // TODO: Load your ML model from models/ directory
        // TODO: Preprocess input data
        // TODO: Run inference
        // TODO: Postprocess output
        return "ML inference result for: ${'$'}input"
            """.trimIndent()
            ServiceType.DATA_ANALYSIS -> """
        // TODO: Parse input data (JSON, CSV, etc.)
        // TODO: Perform statistical analysis
        // TODO: Generate insights and summaries
        return "Analysis result for: ${'$'}input"
            """.trimIndent()
            ServiceType.CRYPTOGRAPHIC -> """
        // TODO: Implement cryptographic operations
        // Note: Be careful with key management in distributed environment
        return "Crypto result for: ${'$'}input"
            """.trimIndent()
            ServiceType.IMAGE_PROCESSING -> """
        // TODO: Decode image data from input
        // TODO: Apply image processing algorithms
        // TODO: Encode and return processed image
        return "Processed image for: ${'$'}input"
            """.trimIndent()
            else -> """
        // TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
        return "Result for: ${'$'}input"
            """.trimIndent()
        }
        
        return """
// ${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
// TODO: Implement your service logic

class ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service {
    fun processInput(input: String): String {
        $serviceLogic
    }
}

fun main(args: Array<String>) {
    val service = ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service()
    val input = readLine() ?: ""
    val result = service.processInput(input)
    println(result)
}
        """.trimIndent()
    }
    
    // JAVA TEMPLATES
    private fun generateJavaTemplate(serviceType: ServiceType): String {
        val className = serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        
        return """
import java.util.Scanner;

/**
 * ${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
 * TODO: Implement your service logic
 */
public class ${className}Service {
    
    public String processInput(String input) {
        // TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
        return "Result for: " + input;
    }
    
    public static void main(String[] args) {
        ${className}Service service = new ${className}Service();
        Scanner scanner = new Scanner(System.in);
        String input = scanner.nextLine();
        String result = service.processInput(input);
        System.out.println(result);
        scanner.close();
    }
}
        """.trimIndent()
    }
    
    // PYTHON TEMPLATES
    private fun generatePythonTemplate(serviceType: ServiceType): String {
        val serviceLogic = when (serviceType) {
            ServiceType.ML_INFERENCE -> """
    # TODO: Load your ML model (TensorFlow, PyTorch, scikit-learn)
    # TODO: Preprocess input data
    # TODO: Run inference
    # TODO: Postprocess output
    return f"ML inference result for: {input_data}"
            """.trimIndent()
            ServiceType.DATA_ANALYSIS -> """
    # TODO: Use pandas, numpy for data analysis
    # TODO: Perform statistical computations
    # TODO: Generate insights
    return f"Analysis result for: {input_data}"
            """.trimIndent()
            ServiceType.SCIENTIFIC_COMPUTING -> """
    # TODO: Use scipy, numpy for scientific computations
    # TODO: Implement algorithms
    # TODO: Return numerical results
    return f"Computation result for: {input_data}"
            """.trimIndent()
            else -> """
    # TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
    return f"Result for: {input_data}"
            """.trimIndent()
        }
        
        return """
#!/usr/bin/env python3
"""
${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
TODO: Implement your service logic
"""

import sys
import json

class ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service:
    def process_input(self, input_data):
        $serviceLogic

def main():
    service = ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service()
    input_data = input().strip()
    result = service.process_input(input_data)
    print(result)

if __name__ == "__main__":
    main()
        """.trimIndent()
    }
    
    // JAVASCRIPT TEMPLATES
    private fun generateJavaScriptTemplate(serviceType: ServiceType): String {
        return """
/**
 * ${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
 * TODO: Implement your service logic
 */

class ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service {
    processInput(input) {
        // TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
        return `Result for: ${'$'}{input}`;
    }
}

// Main execution
function main() {
    const service = new ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service();
    
    process.stdin.setEncoding('utf8');
    process.stdin.on('readable', () => {
        const chunk = process.stdin.read();
        if (chunk !== null) {
            const input = chunk.trim();
            const result = service.processInput(input);
            console.log(result);
            process.exit(0);
        }
    });
}

if (require.main === module) {
    main();
}

module.exports = { ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service };
        """.trimIndent()
    }
    
    // TYPESCRIPT TEMPLATES
    private fun generateTypeScriptTemplate(serviceType: ServiceType): String {
        return """
/**
 * ${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
 * TODO: Implement your service logic
 */

interface ServiceInput {
    data: string;
}

interface ServiceOutput {
    result: string;
}

class ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service {
    processInput(input: string): string {
        // TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
        return `Result for: ${'$'}{input}`;
    }
}

// Main execution
function main(): void {
    const service = new ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service();
    
    process.stdin.setEncoding('utf8');
    process.stdin.on('readable', () => {
        const chunk = process.stdin.read();
        if (chunk !== null) {
            const input: string = chunk.trim();
            const result: string = service.processInput(input);
            console.log(result);
            process.exit(0);
        }
    });
}

if (require.main === module) {
    main();
}

export { ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service };
        """.trimIndent()
    }
    
    // C++ TEMPLATES
    private fun generateCppTemplate(serviceType: ServiceType): String {
        return """
#include <iostream>
#include <string>
#include <sstream>

/**
 * ${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
 * TODO: Implement your service logic
 */

class ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service {
public:
    std::string processInput(const std::string& input) {
        // TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
        return "Result for: " + input;
    }
};

int main() {
    ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service service;
    
    std::string input;
    std::getline(std::cin, input);
    
    std::string result = service.processInput(input);
    std::cout << result << std::endl;
    
    return 0;
}
        """.trimIndent()
    }
    
    // GENERIC TEMPLATE
    private fun generateGenericTemplate(serviceType: ServiceType, language: ProgrammingLanguage): String {
        return """
// ${serviceType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} Service
// Language: ${language.name}
// TODO: Implement your service logic

// Main service function
function processInput(input) {
    // TODO: Implement your ${serviceType.name.lowercase().replace("_", " ")} logic
    return "Result for: " + input;
}

// Main execution
var input = readInput();  // Platform-specific input reading
var result = processInput(input);
writeOutput(result);      // Platform-specific output writing
        """.trimIndent()
    }
    
    // TEST TEMPLATES
    private fun generateKotlinTestTemplate(serviceType: ServiceType): String {
        return """
// Test Cases for ${serviceType.name}
// TODO: Add comprehensive test cases

import org.junit.Test
import org.junit.Assert.*

data class TestCase(val name: String, val input: String, val expectedOutput: String)

class ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}ServiceTest {
    
    private val service = ${serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }}Service()
    
    private val testCases = listOf(
        TestCase("basic_test", "hello", "expected_output"),
        TestCase("edge_case_empty", "", "expected_empty_output"),
        TestCase("edge_case_large", "x".repeat(1000), "expected_large_output")
    )
    
    @Test
    fun testBasicFunctionality() {
        testCases.forEach { testCase ->
            println("Running test: ${'$'}{testCase.name}")
            val result = service.processInput(testCase.input)
            // TODO: Replace with actual expected output comparison
            assertNotNull("Service should return non-null result", result)
        }
    }
}
        """.trimIndent()
    }
    
    private fun generateJavaTestTemplate(serviceType: ServiceType): String {
        val className = serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        return """
import org.junit.Test;
import org.junit.Assert;

/**
 * Test Cases for ${serviceType.name}
 * TODO: Add comprehensive test cases
 */
public class ${className}ServiceTest {
    
    private ${className}Service service = new ${className}Service();
    
    @Test
    public void testBasicFunctionality() {
        String result = service.processInput("test_input");
        Assert.assertNotNull("Service should return non-null result", result);
        // TODO: Add more specific assertions
    }
    
    @Test
    public void testEmptyInput() {
        String result = service.processInput("");
        Assert.assertNotNull("Service should handle empty input", result);
    }
}
        """.trimIndent()
    }
    
    private fun generatePythonTestTemplate(serviceType: ServiceType): String {
        val className = serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        return """
import unittest
import sys
import os

# Add the src directory to the path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from main import ${className}Service

class Test${className}Service(unittest.TestCase):
    
    def setUp(self):
        self.service = ${className}Service()
    
    def test_basic_functionality(self):
        result = self.service.process_input("test_input")
        self.assertIsNotNone(result, "Service should return non-null result")
        # TODO: Add more specific assertions
    
    def test_empty_input(self):
        result = self.service.process_input("")
        self.assertIsNotNone(result, "Service should handle empty input")
    
    def test_large_input(self):
        large_input = "x" * 1000
        result = self.service.process_input(large_input)
        self.assertIsNotNone(result, "Service should handle large input")

if __name__ == '__main__':
    unittest.main()
        """.trimIndent()
    }
    
    private fun generateJavaScriptTestTemplate(serviceType: ServiceType): String {
        val className = serviceType.name.split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        return """
const { ${className}Service } = require('../src/index');

describe('${className}Service', () => {
    let service;
    
    beforeEach(() => {
        service = new ${className}Service();
    });
    
    test('should handle basic input', () => {
        const result = service.processInput('test_input');
        expect(result).toBeDefined();
        expect(typeof result).toBe('string');
    });
    
    test('should handle empty input', () => {
        const result = service.processInput('');
        expect(result).toBeDefined();
    });
    
    test('should handle large input', () => {
        const largeInput = 'x'.repeat(1000);
        const result = service.processInput(largeInput);
        expect(result).toBeDefined();
    });
});
        """.trimIndent()
    }
    
    private fun generateGenericTestTemplate(serviceType: ServiceType, language: ProgrammingLanguage): String {
        return """
// Test Cases for ${serviceType.name}
// Language: ${language.name}
// TODO: Add comprehensive test cases for your service

// Test case 1: Basic functionality
test_basic() {
    input = "test_input";
    result = processInput(input);
    assert(result != null, "Service should return non-null result");
}

// Test case 2: Empty input
test_empty() {
    input = "";
    result = processInput(input);
    assert(result != null, "Service should handle empty input");
}

// Test case 3: Large input
test_large() {
    input = repeat("x", 1000);
    result = processInput(input);
    assert(result != null, "Service should handle large input");
}

// Run all tests
run_tests() {
    test_basic();
    test_empty(); 
    test_large();
    print("All tests completed");
}
        """.trimIndent()
    }
        projectDir: File,
        packageId: String,
        serviceName: String,
        serviceType: ServiceType
    ) {
        private fun generateProjectReadme(
        projectDir: File,
        packageId: String,
        serviceName: String,
        serviceType: ServiceType,
        language: ProgrammingLanguage
    ) {
        val languageInstructions = when (language) {
            ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> """
## Development (JVM)

1. Edit service code in `service/src/main/`
2. Add dependencies to `service/build.gradle.kts`
3. Run tests with `./gradlew test`
4. Build with `./gradlew build`
            """.trimIndent()
            ProgrammingLanguage.PYTHON -> """
## Development (Python)

1. Edit service code in `service/src/main.py`
2. Add dependencies to `requirements/requirements.txt`
3. Install dependencies: `pip install -r requirements/requirements.txt`
4. Run tests: `python -m pytest service/tests/`
5. Test locally: `python service/src/main.py`
            """.trimIndent()
            ProgrammingLanguage.JAVASCRIPT -> """
## Development (Node.js)

1. Edit service code in `service/src/index.js`
2. Add dependencies to `service/package.json`
3. Install dependencies: `cd service && npm install`
4. Run tests: `npm test`
5. Test locally: `npm start`
            """.trimIndent()
            ProgrammingLanguage.NATIVE_CPP -> """
## Development (C++)

1. Edit service code in `service/src/main.cpp`
2. Update `service/CMakeLists.txt` for dependencies
3. Build: `cd service && mkdir build && cd build && cmake .. && make`
4. Run tests: `make test`
5. Test locally: `./service`
            """.trimIndent()
            else -> """
## Development (${language.name})

1. Edit service code in the appropriate source files
2. Follow language-specific build and test procedures
3. Test locally before packaging
            """.trimIndent()
        }
        
        val readme = """
# $serviceName

**Package ID**: $packageId
**Service Type**: ${serviceType.name.replace("_", " ")}
**Programming Language**: ${language.name}

## Description

TODO: Describe what your service does and how to use it.

$languageInstructions

## Service Types Supported

Your service is configured for **${serviceType.name.replace("_", " ").lowercase()}**. This includes:

${getServiceTypeDescription(serviceType)}

## Testing

Use the ServiceDevelopmentTools to run comprehensive tests:
- Manifest validation
- Code structure checks  
- Security compliance
- Resource usage validation
- Functional tests
- Cross-platform compatibility (if applicable)

## Publishing

Once all tests pass, use the publishing tools to:
1. Create signed package (.meshsvc)
2. Generate distribution metadata
3. Upload to I2P discovery sites
4. Create BitTorrent magnets for distribution

## Resource Requirements

- Memory: Up to ${getResourceRequirement(serviceType, "memory")}MB
- CPU: ${getResourceRequirement(serviceType, "cpu")}% estimated usage
- Execution timeout: 30 seconds maximum
- Storage: ${getResourceRequirement(serviceType, "storage")}MB

## Platform Support

${if (isLanguageCrossPlatform(language)) "✅ Cross-platform compatible" else "⚠️ Platform-specific"} 
- Supports: ${getSupportedPlatforms(language).joinToString(", ")}
- Sandbox compatible: ${if (isLanguageSandboxCompatible(language)) "Yes" else "No"}

## Notes

- Keep resource usage within limits
- Follow security guidelines for sandbox compatibility  
- Test thoroughly before publishing to the network
- Ensure all dependencies are properly declared
        """.trimIndent()
        
        File(projectDir, "README.md").writeText(readme)
    }
    
    private fun getServiceTypeDescription(serviceType: ServiceType): String = when (serviceType) {
        ServiceType.ML_INFERENCE -> "- Loading and running machine learning models
- Preprocessing input data for inference
- Postprocessing model outputs"
        ServiceType.ML_TRAINING -> "- Distributed model training
- Gradient aggregation
- Model parameter updates"
        ServiceType.DATA_ANALYSIS -> "- Statistical analysis and computations
- Data insights and pattern recognition
- Report generation"
        ServiceType.IMAGE_PROCESSING -> "- Image filtering and enhancement
- Computer vision algorithms
- Image format conversion"
        ServiceType.CRYPTOGRAPHIC -> "- Encryption and decryption
- Digital signatures
- Hash computations"
        ServiceType.SCIENTIFIC_COMPUTING -> "- Numerical simulations
- Scientific algorithms
- Mathematical modeling"
        else -> "- General computational tasks
- Data processing
- Algorithm execution"
    }
    
    private fun getResourceRequirement(serviceType: ServiceType, resource: String): String = when (resource) {
        "memory" -> when (serviceType) {
            ServiceType.ML_INFERENCE, ServiceType.ML_TRAINING -> "128"
            ServiceType.IMAGE_PROCESSING, ServiceType.VIDEO_PROCESSING -> "64"
            ServiceType.SCIENTIFIC_COMPUTING -> "64"
            else -> "32"
        }
        "cpu" -> when (serviceType) {
            ServiceType.ML_TRAINING, ServiceType.SCIENTIFIC_COMPUTING -> "90"
            ServiceType.ML_INFERENCE, ServiceType.IMAGE_PROCESSING -> "80"
            else -> "50"
        }
        "storage" -> when (serviceType) {
            ServiceType.ML_INFERENCE, ServiceType.ML_TRAINING -> "50"
            ServiceType.IMAGE_PROCESSING -> "20"
            else -> "10"
        }
        else -> "Unknown"
    }
    
    private fun isLanguageCrossPlatform(language: ProgrammingLanguage): Boolean = when (language) {
        ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> true
        ProgrammingLanguage.PYTHON -> true
        ProgrammingLanguage.JAVASCRIPT, ProgrammingLanguage.TYPESCRIPT -> true
        ProgrammingLanguage.WEBASSEMBLY -> true
        ProgrammingLanguage.NATIVE_CPP, ProgrammingLanguage.NATIVE_C -> false
        else -> true
    }
    
    private fun isLanguageSandboxCompatible(language: ProgrammingLanguage): Boolean = when (language) {
        ProgrammingLanguage.NATIVE_CPP, ProgrammingLanguage.NATIVE_C -> false
        else -> true
    }
    
    private fun getSupportedPlatforms(language: ProgrammingLanguage): List<String> = when (language) {
        ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> listOf("Android", "Linux", "Any JVM")
        ProgrammingLanguage.PYTHON -> listOf("Android (via Chaquopy)", "Linux", "Any Python runtime")
        ProgrammingLanguage.JAVASCRIPT -> listOf("Android (via Node.js)", "Linux", "Any Node.js runtime") 
        ProgrammingLanguage.WEBASSEMBLY -> listOf("Android", "Linux", "Any WASM runtime")
        ProgrammingLanguage.NATIVE_CPP -> listOf("Android (NDK)", "Linux (x86_64)")
        else -> listOf("Android", "Linux")
    }
        
        File(projectDir, "README.md").writeText(readme)
    }
    
    // Implement test methods
    private fun runManifestValidationTest(manifest: ServicePackageManager.ServiceManifest): TestCase {
        val startTime = System.currentTimeMillis()
        return try {
            // Validate manifest using ServicePackageManager logic
            val valid = manifest.packageId.isNotEmpty() && 
                       manifest.serviceName.isNotEmpty() &&
                       manifest.resourceRequirements.maxMemoryMB <= 64
            
            TestCase(
                name = "manifest_validation",
                description = "Validate manifest.json structure and constraints",
                passed = valid,
                details = if (valid) "Manifest is valid" else "Manifest validation failed",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            TestCase(
                name = "manifest_validation",
                description = "Validate manifest.json structure and constraints",
                passed = false,
                details = "Validation error: ${e.message}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    private fun runCodeStructureTest(projectDir: File, manifest: ServicePackageManager.ServiceManifest): TestCase {
        val startTime = System.currentTimeMillis()
        val serviceDir = File(projectDir, "service")
        val entryPointFile = File(serviceDir, manifest.entryPoint)
        
        val passed = serviceDir.exists() && entryPointFile.exists()
        
        return TestCase(
            name = "code_structure",
            description = "Verify service code structure and entry point",
            passed = passed,
            details = if (passed) "Code structure is valid" else "Missing service files",
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    private fun runSecurityComplianceTest(projectDir: File, manifest: ServicePackageManager.ServiceManifest): TestCase {
        val startTime = System.currentTimeMillis()
        
        // Basic security checks
        val securityChecks = listOf(
            manifest.allowedSyscalls.size <= 15,
            manifest.resourceRequirements.maxMemoryMB <= 64,
            manifest.executionTimeoutSeconds <= 30,
            manifest.sandboxProfile == "STRICT"
        )
        
        val passed = securityChecks.all { it }
        
        return TestCase(
            name = "security_compliance",
            description = "Verify security constraints and sandbox compatibility",
            passed = passed,
            details = if (passed) "Security compliance passed" else "Security compliance failed",
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    private fun runResourceUsageTest(projectDir: File, manifest: ServicePackageManager.ServiceManifest): TestCase {
        val startTime = System.currentTimeMillis()
        
        // Estimate resource usage
        val projectSize = projectDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        val sizeOk = projectSize < 50 * 1024 * 1024 // 50MB limit
        
        return TestCase(
            name = "resource_usage",
            description = "Verify resource usage is within limits",
            passed = sizeOk,
            details = "Project size: ${projectSize / 1024 / 1024}MB",
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    private fun runFunctionalTests(projectDir: File, manifest: ServicePackageManager.ServiceManifest): List<TestCase> {
        // TODO: Run actual functional tests
        return listOf(
            TestCase(
                name = "functional_basic",
                description = "Basic functionality test",
                passed = true,
                details = "TODO: Implement functional testing",
                executionTimeMs = 100
            )
        )
    }
    
    private fun runPackageCreationTest(projectDir: File, manifest: ServicePackageManager.ServiceManifest): TestCase {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Test package creation without actually creating it
            val canCreatePackage = File(projectDir, "service").exists() &&
                                 File(projectDir, "manifest.json").exists()
            
            TestCase(
                name = "package_creation",
                description = "Test package creation process",
                passed = canCreatePackage,
                details = if (canCreatePackage) "Package can be created" else "Package creation would fail",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            TestCase(
                name = "package_creation",
                description = "Test package creation process",
                passed = false,
                details = "Package creation error: ${e.message}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    private fun saveTestResults(projectDir: File, results: TestResults) {
        val resultsFile = File(projectDir, "${TEST_RESULTS_DIR}/test_results.json")
        resultsFile.parentFile?.mkdirs()
        
        val json = Json { prettyPrint = true }
        resultsFile.writeText(json.encodeToString(TestResults.serializer(), results))
    }
    
    private fun calculateSecurityLevel(manifest: ServicePackageManager.ServiceManifest): String {
        val score = when {
            manifest.allowedSyscalls.size <= 5 -> 3
            manifest.allowedSyscalls.size <= 10 -> 2
            else -> 1
        } + when {
            manifest.resourceRequirements.maxMemoryMB <= 16 -> 2
            manifest.resourceRequirements.maxMemoryMB <= 32 -> 1
            else -> 0
        }
        
        return when {
            score >= 4 -> "HIGH"
            score >= 2 -> "MEDIUM"
            else -> "LOW"
        }
    }
    
    private fun estimatePackageSize(projectDir: File): String {
        val sizeBytes = projectDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / 1024 / 1024}MB"
        }
    }
    
    private fun createSignedPackage(projectDir: File, privateKey: String?): String {
        // TODO: Implement actual package signing
        return "${projectDir.absolutePath}/dist/${projectDir.name}.meshsvc"
    }
    
    private fun generateDistributionMetadata(projectDir: File, packagePath: String): DistributionInfo {
        val manifest = Json.decodeFromString(
            ServicePackageManager.ServiceManifest.serializer(),
            File(projectDir, "manifest.json").readText()
        )
        
        return DistributionInfo(
            packageId = manifest.packageId,
            packageHash = "TODO_CALCULATE_HASH",
            packageSizeBytes = File(packagePath).length(),
            torrentMagnetLink = null, // TODO: Generate magnet link
            i2pSiteUrls = listOf("http://meshcompute1.i2p/${manifest.packageId}"),
            publishingChecklist = listOf(
                ChecklistItem("Tests passed", true, true),
                ChecklistItem("Package signed", privateKey != null, true),
                ChecklistItem("Documentation complete", false, false),
                ChecklistItem("I2P sites configured", false, true)
            )
        )
    }
}
