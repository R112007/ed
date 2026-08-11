package com.r112007.effecteditor;

import android.app.Application;
import android.util.Log;

import com.r112007.effecteditor.ui.CompletionEngine;

/**
 * Application entry. Loads Tree-sitter and warms up the JDT-style completion engine
 * on a background thread so completions are ready by the time the editor appears.
 */
public class EffectEditorApplication extends Application {

    private static final String TAG = "EffectEditorApp";

    @Override
    public void onCreate() {
        long appStart = System.nanoTime();
        super.onCreate();

        // Initialize the startup logger before anything else so every subsequent step is recorded.
        StartupLog.init(this);
        StartupLog.log("Application.onCreate started");

        // Capture any future uncaught exceptions (including startup crashes in Activity/threads).
        CrashHandler.install(this);
        StartupLog.log("CrashHandler installed");

        try {
            long tsStart = System.nanoTime();
            Class<?> loaderClass = null;
            for (String name : new String[]{
                    "com.itsaky.androidide.treesitter.AndroidTreeSitter",
                    "com.itsaky.androidide.treesitter.TreeSitter",
                    "com.itsaky.androidide.treesitter.TSLoader"}) {
                try {
                    loaderClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (loaderClass != null) {
                try {
                    loaderClass.getMethod("loadLibrary").invoke(null);
                } catch (NoSuchMethodException ignored) {
                    loaderClass.getMethod("initialize").invoke(null);
                }
                StartupLog.logTime("Tree-sitter native library loaded", tsStart);
            } else {
                System.loadLibrary("android-tree-sitter");
                StartupLog.logTime("Tree-sitter loaded via System.loadLibrary", tsStart);
            }
        } catch (Throwable t) {
            StartupLog.log("Failed to initialize Tree-sitter native library: " + t.getMessage());
            Log.w(TAG, "Failed to initialize Tree-sitter native library", t);
        }

        // Build the runtime class index in the background so the first completion
        // popup is instantaneous.
        long completionStart = System.nanoTime();
        CompletionEngine.init(this);
        StartupLog.logTime("CompletionEngine.init dispatched on background thread", completionStart);

        StartupLog.logTime("Application.onCreate finished", appStart);
    }
}
