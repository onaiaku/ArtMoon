package io.github.onaiaku.artmoon;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final Logger LOGGER = Logger.getLogger(LimeLog.class.getName());

    /**
     * ⚠️ java.util.logging on Android keeps its output to itself unless a handler is added, so
     * everything the app logged this way went nowhere that adb could see and every fault had to be
     * chased from behaviour alone. The lines below mirror to logcat (tag LimeLog) so the same
     * message is visible over adb; the JUL logger is kept for the in-app file handler.
     */
    private static final String TAG = "LimeLog";

    public static void info(String msg) {
        LOGGER.info(msg);
        android.util.Log.i(TAG, msg);
    }
    
    public static void warning(String msg) {
        LOGGER.warning(msg);
        android.util.Log.w(TAG, msg);
    }
    
    public static void severe(String msg) {
        LOGGER.severe(msg);
        android.util.Log.e(TAG, msg);
    }
    
    public static void setFileHandler(String fileName) throws IOException {
        LOGGER.addHandler(new FileHandler(fileName));
    }
}
