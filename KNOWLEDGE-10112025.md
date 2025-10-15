# KNOWLEDGE — October 11, 2025

## Agent Workflow Rules

### Command Execution with Logging
**RULE**: When running commands with output logging (using `2>&1 | tee logfile.log`), do NOT monitor the terminal or wait for completion. Return prompt immediately to user. The logs are being captured and can be reviewed after completion.

### Knowledge Documentation Updates
**RULE**: ALWAYS update TODAY's KNOWLEDGE doc with new rules, findings, and important information. If today's KNOWLEDGE doc doesn't exist, create it using the format: `KNOWLEDGE-MMDDYYYY.md` where MM=month, DD=day, YYYY=year.

**RULE**: It is exhausting to repeatedly remind the agent of existing rules. Follow the rules consistently without requiring reminders.

### Test Execution Memory
**RULE**: Memorize working test execution commands and their parameters. Do not re-discover them each time:
- Package: `org.torproject.android.debug.test`
- Runner: `androidx.test.runner.AndroidJUnitRunner`
- Device: `30870044490006E`
- Command format: `adb -s 30870044490006E shell am instrument -w -r -e debug false org.torproject.android.debug.test/androidx.test.runner.AndroidJUnitRunner`

### Build Commands Memory
**RULE**: All Gradle build commands MUST use the format:
```bash
: > /path/to/logfile.log && export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && cd /path/to/project && ./gradlew [tasks] 2>&1 | tee /path/to/logfile.log
```

## Test Execution Results - October 11, 2025

### Build Status
- **Final build**: SUCCESS in 8m 2s, 221 actionable tasks
- All APKs generated successfully
- All test files compiled without errors

### Test Execution Results
- **Tests run**: 43/46 passed before crash
- **Crash location**: Test #44 - `testRapidNavigationSwitching` in `OrbotNavigationTest`
- **Crash cause**: `ClassNotFoundException` for `com.ustadmobile.orbotmeshrabiyaintegration.routing.OrbotStateReceiver`

### Issue Analysis
**Problem**: OrbotStateReceiver.kt exists in correct location with correct package name but was NOT included in the compiled APK.

**File location**: `/Users/dreadstar/workspace/orbot-android/app/src/main/java/com/ustadmobile/orbotmeshrabiyaintegration/routing/OrbotStateReceiver.kt`

**File created**: October 11, 18:51
**Last successful build**: October 11, 20:55

**Verification**: 
- File exists with correct package declaration
- Manifest references it correctly at line 283
- `unzip -l` on APK shows file is NOT present in compiled APK
- No compilation errors in build log

**Status**: Rebuild in progress to include OrbotStateReceiver in APK

## File Structure Fixes Completed

### OrbotActivityUITest.kt Resolution
**Problem**: File had severe structural corruption:
1. Class prematurely closed at line 233
2. Duplicate method definitions outside class scope (lines 234-351)  
3. Two conflicting `testBasicInteraction()` methods (lines 216 and 241)

**Solution**: 
1. Removed premature class closure and all duplicate methods
2. Removed incomplete first `testBasicInteraction` method
3. Kept properly structured second method with ActivityScenario

**Verification**: 
- kotlinc linter confirmed no syntax errors
- Gradle compilation confirmed no conflicting overloads
- Build succeeded with all fixes

## Verification Tools Usage

### Primary Verification Tool
**kotlinc linter** is the definitive tool for syntax structure validation:
```bash
kotlinc -no-stdlib <file.kt> 2>&1 | grep -E "(syntax error|Expecting|Conflicting overloads)"
```

### Verification Hierarchy
1. **kotlinc linter**: File syntax and structure - STRONGEST confirmation
2. **Gradle compiler**: Full compilation with dependencies and conflicts
3. **Manual inspection**: WEAKEST - prone to missing subtle issues

**RULE**: Use the strongest available verification tool. Don't waste time with manual inspection when linter provides definitive confirmation.
