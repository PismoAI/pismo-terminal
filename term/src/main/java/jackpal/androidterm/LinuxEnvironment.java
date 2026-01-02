package jackpal.androidterm;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class LinuxEnvironment {
    private static final String TAG = "LinuxEnvironment";
    private static final String ALPINE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz";

    private final Context context;
    private final File baseDir;
    private final File rootfsDir;
    private final File binDir;
    private final File prootBinary;

    public interface SetupCallback {
        void onProgress(String message, int percent);
        void onComplete(boolean success, String error);
    }

    private final File launcherScript;
    private final File l2sDir;

    public LinuxEnvironment(Context context) {
        this.context = context;
        this.baseDir = new File(context.getFilesDir(), "linux");
        this.rootfsDir = new File(baseDir, "rootfs");
        this.binDir = new File(baseDir, "bin");
        this.prootBinary = new File(binDir, "proot");
        this.launcherScript = new File(binDir, "launch-proot.sh");
        this.l2sDir = new File(baseDir, ".proot_l2s");
    }

    /**
     * Returns the path to the launcher script that should be used as the shell
     */
    public String getLauncherScript() {
        return launcherScript.getAbsolutePath();
    }

    public boolean isSetupComplete() {
        return new File(baseDir, ".setup_complete").exists()
            && rootfsDir.exists()
            && prootBinary.exists()
            && launcherScript.exists();
    }

    /**
     * Validates that the Linux environment is properly set up.
     * @return null if valid, or an error message describing what's wrong.
     */
    public String validate() {
        Log.i(TAG, "Validating Linux environment...");
        Log.i(TAG, "Base dir: " + baseDir.getAbsolutePath() + " exists=" + baseDir.exists());
        Log.i(TAG, "Rootfs dir: " + rootfsDir.getAbsolutePath() + " exists=" + rootfsDir.exists());
        Log.i(TAG, "Proot binary: " + prootBinary.getAbsolutePath() + " exists=" + prootBinary.exists());

        if (!baseDir.exists()) {
            return "Base directory does not exist: " + baseDir.getAbsolutePath();
        }
        if (!rootfsDir.exists()) {
            return "Rootfs directory does not exist: " + rootfsDir.getAbsolutePath();
        }
        if (!prootBinary.exists()) {
            return "Proot binary does not exist: " + prootBinary.getAbsolutePath();
        }
        if (!prootBinary.canExecute()) {
            // Try to make it executable
            prootBinary.setExecutable(true, false);
            if (!prootBinary.canExecute()) {
                return "Proot binary is not executable: " + prootBinary.getAbsolutePath();
            }
        }

        // Check for launcher script
        Log.i(TAG, "Launcher script: " + launcherScript.getAbsolutePath() + " exists=" + launcherScript.exists());
        if (!launcherScript.exists()) {
            return "Launcher script does not exist: " + launcherScript.getAbsolutePath();
        }
        if (!launcherScript.canExecute()) {
            launcherScript.setExecutable(true, false);
            if (!launcherScript.canExecute()) {
                return "Launcher script is not executable: " + launcherScript.getAbsolutePath();
            }
        }

        // Check for shell inside rootfs
        File shell = new File(rootfsDir, "bin/sh");
        Log.i(TAG, "Shell: " + shell.getAbsolutePath() + " exists=" + shell.exists());
        if (!shell.exists()) {
            // Try busybox as fallback
            File busybox = new File(rootfsDir, "bin/busybox");
            if (!busybox.exists()) {
                return "No shell found in rootfs (neither /bin/sh nor /bin/busybox)";
            }
        }

        // Check tmp directory
        File tmpDir = new File(baseDir, "tmp");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }

        Log.i(TAG, "Linux environment validation passed");
        return null; // All good
    }

    public String[] getShellCommand() {
        if (!isSetupComplete()) return new String[]{"/system/bin/sh"};
        return new String[]{
            prootBinary.getAbsolutePath(), "--link2symlink", "-0",
            "-r", rootfsDir.getAbsolutePath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/sdcard:/sdcard",
            "-b", context.getFilesDir().getAbsolutePath() + ":/android",
            "-w", "/root", "/bin/sh", "-l"
        };
    }

    /**
     * Returns the proot command as a single string for use as shell parameter
     */
    public String getProotCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append(prootBinary.getAbsolutePath());
        sb.append(" --link2symlink -0");
        sb.append(" -r ").append(rootfsDir.getAbsolutePath());
        sb.append(" -b /dev -b /proc -b /sys");
        sb.append(" -b /sdcard:/sdcard");
        sb.append(" -b ").append(context.getFilesDir().getAbsolutePath()).append(":/android");
        sb.append(" -w /root");
        sb.append(" /bin/sh -l");
        return sb.toString();
    }

    public String[] getEnvironment() {
        return new String[]{
            "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR=" + new File(baseDir, "tmp").getAbsolutePath(),
            "PROOT_NO_SECCOMP=1"
        };
    }

    public void setup(SetupCallback cb) {
        try {
            cb.onProgress("Cleaning up...", 2);
            deleteRecursive(baseDir);
            cb.onProgress("Creating directories...", 5);
            baseDir.mkdirs(); rootfsDir.mkdirs(); binDir.mkdirs();
            new File(baseDir, "tmp").mkdirs();
            cb.onProgress("Extracting proot...", 10);
            extractProot();
            cb.onProgress("Downloading Alpine Linux...", 15);
            downloadAndExtractAlpine(cb);
            cb.onProgress("Configuring system...", 85);
            configureDns(); createProfile(); copySetupScript();
            cb.onProgress("Creating launcher...", 90);
            createLauncherScript();
            cb.onProgress("Finalizing...", 95);
            new File(baseDir, ".setup_complete").createNewFile();
            cb.onProgress("Complete!", 100);
            cb.onComplete(true, null);
        } catch (Exception e) {
            Log.e(TAG, "Setup failed", e);
            cb.onComplete(false, e.getMessage());
        }
    }

    private void extractProot() throws IOException {
        String arch = Build.SUPPORTED_ABIS[0];
        String assetName = (arch.contains("arm64") || arch.contains("aarch64")) ? "bin/proot-aarch64" : "bin/proot-arm";
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(prootBinary)) {
            byte[] buf = new byte[8192]; int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
        prootBinary.setExecutable(true, false);
    }

    private void downloadAndExtractAlpine(SetupCallback cb) throws IOException {
        File tarFile = new File(baseDir, "alpine.tar.gz");
        URL url = new URL(ALPINE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(60000); conn.setReadTimeout(120000);
        int fileSize = conn.getContentLength();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(tarFile)) {
            byte[] buf = new byte[32768]; int len; long downloaded = 0;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                downloaded += len;
                if (fileSize > 0) cb.onProgress("Downloading... " + (downloaded/1024) + "KB", 15 + (int)((downloaded * 60) / fileSize));
            }
        }
        conn.disconnect();
        cb.onProgress("Extracting...", 78);
        extractTarGz(tarFile, rootfsDir);
        tarFile.delete();
    }

    private void extractTarGz(File tarGzFile, File destDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(tarGzFile);
             GZIPInputStream gzIn = new GZIPInputStream(fis);
             TarInputStream tarIn = new TarInputStream(gzIn)) {
            TarEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty() || name.contains("..")) continue;
                File outFile = new File(destDir, name);
                if (entry.isDirectory()) { outFile.mkdirs(); }
                else if (!entry.isSymlink()) {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(outFile)) { tarIn.copyEntryContents(out); }
                    if (name.startsWith("bin/") || name.startsWith("sbin/") || name.startsWith("usr/bin/") || name.startsWith("usr/sbin/"))
                        outFile.setExecutable(true, false);
                }
            }
        }
        File busybox = new File(destDir, "bin/busybox");
        if (busybox.exists()) {
            busybox.setExecutable(true, false);
            File sh = new File(destDir, "bin/sh");
            if (!sh.exists()) { copyFile(busybox, sh); sh.setExecutable(true, false); }
        }
    }

    private void configureDns() throws IOException {
        new File(rootfsDir, "etc").mkdirs();
        try (FileWriter fw = new FileWriter(new File(rootfsDir, "etc/resolv.conf"))) {
            fw.write("nameserver 8.8.8.8\nnameserver 8.8.4.4\n");
        }
    }

    private void createProfile() throws IOException {
        new File(rootfsDir, "root").mkdirs();
        try (FileWriter fw = new FileWriter(new File(rootfsDir, "root/.profile"))) {
            fw.write("#!/bin/sh\nif [ ! -f /root/.setup_done ]; then\n");
            fw.write("    echo 'Running first-time setup...'\n");
            fw.write("    [ -f /root/setup.sh ] && /root/setup.sh && touch /root/.setup_done\nfi\n");
            fw.write("[ -x /bin/bash ] && exec /bin/bash --login\nexport PS1='pismo# '\n");
        }
    }

    private void copySetupScript() throws IOException {
        File setupScript = new File(rootfsDir, "root/setup.sh");
        try (InputStream in = context.getAssets().open("scripts/setup-alpine.sh");
             FileOutputStream out = new FileOutputStream(setupScript)) {
            byte[] buf = new byte[4096]; int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
        setupScript.setExecutable(true, false);
    }

    /**
     * Creates a launcher script that properly sets up the environment before running proot.
     * This is critical - proot fails if LD_PRELOAD is set or other environment issues exist.
     */
    private void createLauncherScript() throws IOException {
        // Create the l2s directory for proot's link2symlink feature
        l2sDir.mkdirs();

        File tmpDir = new File(baseDir, "tmp");
        tmpDir.mkdirs();

        StringBuilder script = new StringBuilder();
        script.append("#!/system/bin/sh\n");
        script.append("# Pismo Terminal - proot launcher script\n");
        script.append("# This script properly configures the environment before running proot\n\n");

        // Critical: unset LD_PRELOAD - this breaks proot if set
        script.append("unset LD_PRELOAD\n");
        script.append("unset LD_LIBRARY_PATH\n\n");

        // Set proot-specific environment variables
        script.append("export PROOT_L2S_DIR=\"").append(l2sDir.getAbsolutePath()).append("\"\n");
        script.append("export PROOT_TMP_DIR=\"").append(tmpDir.getAbsolutePath()).append("\"\n");
        script.append("export PROOT_NO_SECCOMP=1\n\n");

        // Build the proot command
        script.append("exec \"").append(prootBinary.getAbsolutePath()).append("\" \\\n");
        script.append("    --link2symlink \\\n");
        script.append("    -0 \\\n");
        script.append("    -r \"").append(rootfsDir.getAbsolutePath()).append("\" \\\n");
        script.append("    -b /dev \\\n");
        script.append("    -b /proc \\\n");
        script.append("    -b /sys \\\n");
        script.append("    -b /sdcard:/sdcard \\\n");
        script.append("    -b \"").append(context.getFilesDir().getAbsolutePath()).append(":/android\" \\\n");
        script.append("    -w /root \\\n");
        script.append("    /bin/sh -l\n");

        try (FileWriter fw = new FileWriter(launcherScript)) {
            fw.write(script.toString());
        }
        launcherScript.setExecutable(true, false);

        Log.i(TAG, "Created launcher script at: " + launcherScript.getAbsolutePath());
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private static class TarInputStream extends FilterInputStream {
        private TarEntry currentEntry; private long remaining;
        public TarInputStream(InputStream in) { super(in); }
        public TarEntry getNextEntry() throws IOException {
            while (remaining > 0) remaining -= skip(remaining);
            byte[] header = new byte[512]; int read = 0;
            while (read < 512) { int r = in.read(header, read, 512 - read); if (r < 0) return null; read += r; }
            boolean allZero = true; for (byte b : header) if (b != 0) { allZero = false; break; }
            if (allZero) return null;
            currentEntry = new TarEntry(header); remaining = currentEntry.size; return currentEntry;
        }
        public void copyEntryContents(OutputStream out) throws IOException {
            byte[] buf = new byte[8192];
            while (remaining > 0) { int toRead = (int)Math.min(buf.length, remaining); int r = in.read(buf, 0, toRead); if (r < 0) break; out.write(buf, 0, r); remaining -= r; }
            long padding = (512 - (currentEntry.size % 512)) % 512; while (padding > 0) padding -= in.skip(padding);
        }
    }

    private static class TarEntry {
        String name, linkName; long size; byte type;
        String getName() { return name; }
        boolean isDirectory() { return type == '5' || name.endsWith("/"); }
        boolean isSymlink() { return type == '2' || type == '1'; }
        TarEntry(byte[] h) {
            StringBuilder sb = new StringBuilder(); for (int i = 0; i < 100 && h[i] != 0; i++) sb.append((char)h[i]); name = sb.toString();
            sb = new StringBuilder(); for (int i = 157; i < 257 && h[i] != 0; i++) sb.append((char)h[i]); linkName = sb.toString();
            sb = new StringBuilder(); for (int i = 124; i < 136 && h[i] != 0 && h[i] != ' '; i++) sb.append((char)h[i]);
            try { size = Long.parseLong(sb.toString().trim(), 8); } catch (Exception e) { size = 0; }
            type = h[156];
        }
    }
}
