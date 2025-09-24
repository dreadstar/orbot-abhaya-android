package android.util;

/**
 * Minimal stub for android.util.Log used in JVM unit tests.
 * Methods return 0 and perform no real logging.
 */
public final class Log {

    private Log() {}

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

}
