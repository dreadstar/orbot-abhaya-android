TestLogger
=========

Lightweight test logging helper for JVM unit tests.

Usage
-----

- Call `TestLogger.log("MyTag", "I", "message")` from JUnit tests. Messages are appended to `build/logs/test-ulog.log` and printed to stderr.
- Control truncation length with system property `-Dtestlogger.maxchars=2000` when running Gradle.

Truncation script
------------------

There is a helper script at `scripts/truncate-log.sh` that keeps head and tail of large logs and archives the middle.

CI Integration
--------------

Add `-Dtestlogger.maxchars=2000` to your Gradle test task and run `scripts/truncate-log.sh build/logs/test-ulog.log` after tests to keep logs a manageable size.
