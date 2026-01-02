package jackpal.androidterm;

import android.content.Context;
import android.os.Environment;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple crash logger that writes to a file in Downloads folder
 */
public class CrashLogger {
    private static File logFile;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static void init(Context context) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            logFile = new File(downloads, "pismo-crash.log");

            // Clear old log on fresh start
            if (logFile.exists()) {
                logFile.delete();
            }

            log("=== Pismo Terminal Started ===");
            log("Time: " + new Date().toString());
            log("Device: " + android.os.Build.MODEL);
            log("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            log("App data dir: " + context.getFilesDir().getAbsolutePath());
            log("");

            // Set global exception handler
            final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                log("!!! UNCAUGHT EXCEPTION !!!");
                log("Thread: " + thread.getName());
                logException(throwable);

                // Call default handler
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            });

            log("Crash logger initialized");
        } catch (Exception e) {
            // Can't log if logger fails
        }
    }

    public static void log(String message) {
        if (logFile == null) return;
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println("[" + sdf.format(new Date()) + "] " + message);
        } catch (Exception e) {
            // Ignore
        }
    }

    public static void logException(Throwable t) {
        if (logFile == null || t == null) return;
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println("[" + sdf.format(new Date()) + "] EXCEPTION: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(pw);
            pw.println();
        } catch (Exception e) {
            // Ignore
        }
    }
}
