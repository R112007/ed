package com.r112007.effecteditor;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captures uncaught exceptions and writes them to a file in the app's external files dir.
 * The log can be retrieved via adb pull or shared by the user for debugging launch crashes.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private final Application application;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Application application) {
        this.application = application;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Application application) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(application));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            String crashReport = buildCrashReport(thread, throwable);
            File file = writeReport(crashReport);
            Log.e(TAG, "Crash logged to " + file.getAbsolutePath(), throwable);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to write crash report", t);
        }

        // Let the system handler deal with the termination so the app still exits.
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }

    private String buildCrashReport(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("========== Effect Editor Crash Report ==========");
        pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        pw.println("Thread: " + (thread != null ? thread.getName() : "unknown"));
        pw.println("SDK: " + Build.VERSION.SDK_INT);
        pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("Package: " + application.getPackageName());
        pw.println("Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        pw.println("------------------------------------------------");
        throwable.printStackTrace(pw);
        pw.println("================================================");
        pw.close();
        return sw.toString();
    }

    private File writeReport(String report) throws Exception {
        Context context = application.getApplicationContext();
        File dir = new File(context.getExternalFilesDir(null), "crashes");
        if (!dir.exists() && !dir.mkdirs()) {
            // Fallback to internal files dir if external is unavailable.
            dir = new File(context.getFilesDir(), "crashes");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new RuntimeException("Cannot create crash log directory");
            }
        }
        String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        File file = new File(dir, name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(report);
        }
        return file;
    }
}
