plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// This module only contains AIDL files. Android projects will compile them when
// depending on this module as an AIDL source dependency. Keep this module as a
// plain Java library so Gradle still exposes sources to IDEs.
