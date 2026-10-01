package rkr.tinykeyboard.inputmethod.rime;

/**
 * Logging seam so the rime engine and its wasm host layer stay free of
 * android.util.Log and can run in plain JVM unit tests.
 */
public final class RimeLog {

    public interface Logger {
        void log(String tag, String message, Throwable error);
    }

    public static final Logger SILENT = new Logger() {
        @Override
        public void log(String tag, String message, Throwable error) {
        }
    };

    public static final Logger STDOUT = new Logger() {
        @Override
        public void log(String tag, String message, Throwable error) {
            System.out.println(tag + ": " + message + (error == null ? "" : " (" + error + ")"));
        }
    };

    private static volatile Logger logger = SILENT;

    private RimeLog() {
    }

    public static void setLogger(Logger logger) {
        RimeLog.logger = logger == null ? SILENT : logger;
    }

    public static void w(String tag, String message) {
        logger.log(tag, message, null);
    }

    public static void w(String tag, String message, Throwable error) {
        logger.log(tag, message, error);
    }
}
