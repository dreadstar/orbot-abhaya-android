# Contributing to orbot-abhaya-android

Thank you for your interest in contributing! This project is licensed under
AGPLv3, and we welcome contributions that align with our goals of privacy,
security, and open-source values.

## License Agreement

By contributing to this project, you agree that:
- Your contributions will be licensed under AGPLv3
- You have the right to submit your contributions
- Your contributions don't violate any third-party licenses

## Types of Contributions

We welcome:
- 🐛 Bug reports and fixes
- ✨ New features (discuss first via issue)
- 📝 Documentation improvements
- 🧪 Tests and test coverage improvements
- 🌐 Translations and localization
- ♿ Accessibility improvements

## Before You Start

1. **Check existing issues**: See if someone is already working on it
2. **Open an issue**: Discuss major changes before coding
3. **Review license requirements**: Understand AGPLv3 and LGPL implications

## Development Setup

See [README.md](README.md#development-setup) for detailed setup instructions.

### Quick Start

```bash
# Clone the repository
git clone --recurse-submodules https://github.com/dreadstar/orbot-abhaya-android.git
cd orbot-abhaya-android

# Set Java 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Build
./gradlew assembleFullpermDebug

# Run tests
./gradlew test
./gradlew connectedAndroidTest
```

## Code Guidelines

- Follow Android/Kotlin best practices
- Write tests for new features
- Update documentation
- Follow existing code style
- Keep commits focused and atomic

### Code Style

- **Language**: Kotlin (Java only for legacy code)
- **Indentation**: 4 spaces
- **Line Length**: 120 characters max
- **Naming**: camelCase for functions, PascalCase for classes
- **Comments**: Use KDoc for public APIs

Example:
```kotlin
/**
 * Captures camera frames and processes them for streaming.
 *
 * @param context The application context
 * @param lifecycleOwner The lifecycle owner for CameraX
 * @param ingestor The stream ingestor for processing frames
 */
class CameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val ingestor: StreamIngestor
) {
    // Implementation...
}
```

## Testing

All new code must include tests.

### Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Specific module tests
./gradlew :abhaya-sensor-android:app:test
./gradlew :abhaya-sensor-android:app:connectedAndroidTest
```

### Test Coverage Goals

- Unit test coverage: 70%+
- Integration test coverage: 60%+
- Critical paths (security, networking): 90%+

### Writing Tests

```kotlin
@Test
fun `test camera initialization succeeds`() {
    // Given
    val context = ApplicationProvider.getApplicationContext<Context>()
    
    // When
    val camera = CameraCapture(context, lifecycleOwner, mockIngestor)
    camera.start(mockPreviewView)
    
    // Then
    assertTrue(camera.isInitialized)
}
```

## License Compliance

### When Adding Dependencies

Before adding new dependencies, check:
- ✅ License compatibility with AGPLv3
- ✅ For LGPL libraries: Follow compliance requirements
- ❌ Avoid GPL-2.0-only (incompatible with Apache 2.0)
- ❌ Avoid proprietary licenses

**Compatible licenses:**
- Apache-2.0 ✅
- MIT ✅
- BSD-3-Clause ✅
- LGPL-3.0 ✅
- GPL-3.0 ✅

**Incompatible licenses:**
- AGPL (except this project itself)
- GPL-2.0-only
- Proprietary/Commercial
- Non-commercial licenses

**Document new dependencies:**

1. Add to `build.gradle.kts` with comments:
```kotlin
dependencies {
    // Camera support (Apache-2.0)
    implementation("androidx.camera:camera-core:1.2.2")
}
```

2. Update `THIRD_PARTY_LICENSES.md` with license info

3. If LGPL, update `NOTICE` with LGPL compliance notice

### Modifying Meshrabiya

If you modify Meshrabiya code:
- Those changes MUST be LGPL-3.0
- Document what you changed
- Consider contributing back to upstream: https://github.com/UstadMobile/Meshrabiya

## Pull Request Process

1. **Fork** the repository
2. **Create** a feature branch: `git checkout -b feature/your-feature`
3. **Make** your changes
4. **Add/update** tests
5. **Update** documentation
6. **Run** tests: `./gradlew test`
7. **Commit** with clear messages
8. **Push** to your fork
9. **Open** a pull request

### PR Checklist

- [ ] Code follows project style
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] License compliance verified
- [ ] No new warnings introduced
- [ ] Commits are clean and focused
- [ ] PR description explains changes

### Commit Message Format

```
<type>: <subject>

<body>

<footer>
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style (formatting, no logic change)
- `refactor`: Code refactoring
- `test`: Adding/updating tests
- `chore`: Maintenance tasks

**Example:**
```
feat: Add 3-state flash mode to camera capture

Implements OFF/ON/AUTO flash modes with visual indicators.
- OFF: Grey icon
- ON: Yellow icon, torch enabled
- AUTO: Blue icon, flash mode set to auto

Fixes #123
```

## Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Assume good intentions
- Help create a welcoming community
- No harassment, discrimination, or hate speech

## Security Issues

**Do NOT open public issues for security vulnerabilities.**

Instead:
1. Email: [your-security-email@example.com]
2. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We will respond within 48 hours and coordinate disclosure.

## Feature Requests

1. Search existing issues first
2. Open a new issue with:
   - Clear description
   - Use cases
   - Expected behavior
   - Mockups/examples (if applicable)
3. Wait for discussion before implementing

## Questions?

- Open an issue for questions
- Join discussion: https://github.com/dreadstar/orbot-abhaya-android/discussions
- Read the FAQ: [FAQ.md](FAQ.md) (coming soon)

## Recognition

Contributors will be recognized in:
- GitHub contributors page
- Release notes
- `CONTRIBUTORS.md` (if we create one)
- In-app about screen (for significant contributions)

Thank you for contributing! 🎉

---

**Copyright © 2025 Tyrone Thomas/BreakThrough Technologies**

Licensed under AGPLv3. See [LICENSE](LICENSE) for details.
