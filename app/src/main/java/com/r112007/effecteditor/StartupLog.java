package com.r112007.effecteditor;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records startup progress to both logcat and a file in the app's external files directory.
 * <p>
 * The file ({@code Android/data/<package>/files/startup.log}) can be read by the user
 * when diagnosing hangs or slow launches.
 */
public class StartupLog {

    private static final String TAG = "StartupLog";
    private static final String LOG_NAME = "startup.log";

    private static File logFile;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private static final Object lock = new Object();

    public static void init(Context context) {
        synchronized (lock) {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                logFile = new File(dir, LOG_NAME);
            }
            log("=== Effect Editor startup log ===");
            log("log file: " + (logFile != null ? logFile.getAbsolutePath() : "null"));
        }
    }

    /**
     * Logs a message with the current timestamp.
     */
    public static void log(String message) {
        String line = TIME_FMT.format(new Date()) + "  " + message;
        Log.i(TAG, line);
        synchronized (lock) {
            if (logFile != null) {
                try (FileWriter writer = new FileWriter(logFile, true)) {
                    writer.write(line + "\n");
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write startup log", e);
                }
            }
        }
    }

    /**
     * Logs a message and records the elapsed time since {@code startNanos}.
     */
    public static void logTime(String message, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        log(message + " (" + ms + " ms)");
    }

    public static File getLogFile() {
        return logFile;
    }
}
