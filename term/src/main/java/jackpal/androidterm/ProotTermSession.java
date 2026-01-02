package jackpal.androidterm;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import jackpal.androidterm.compat.FileCompat;
import jackpal.androidterm.util.TermSettings;
import java.io.*;

public class ProotTermSession extends GenericTermSession {
    private static final String TAG = "ProotTermSession";
    private int mProcId;
    private Thread mWatcherThread;
    private Context mContext;
    private static final int PROCESS_EXITED = 1;

    private Handler mMsgHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (!isRunning()) return;
            if (msg.what == PROCESS_EXITED) onProcessExit((Integer) msg.obj);
        }
    };

    public ProotTermSession(Context context, TermSettings settings) throws IOException {
        super(ParcelFileDescriptor.open(new File("/dev/ptmx"), ParcelFileDescriptor.MODE_READ_WRITE), settings, false);
        mContext = context;

        Log.i(TAG, "ProotTermSession constructor started");

        // Validate environment before proceeding
        LinuxEnvironment linuxEnv = new LinuxEnvironment(mContext);
        String validationError = linuxEnv.validate();
        if (validationError != null) {
            Log.e(TAG, "Linux environment validation failed: " + validationError);
            throw new IOException("Linux environment invalid: " + validationError);
        }

        initializeSession();
        setTermOut(new ParcelFileDescriptor.AutoCloseOutputStream(mTermFd));
        setTermIn(new ParcelFileDescriptor.AutoCloseInputStream(mTermFd));

        mWatcherThread = new Thread() {
            @Override
            public void run() {
                Log.i(TAG, "Waiting for proot process: " + mProcId);
                int result = TermExec.waitFor(mProcId);
                Log.i(TAG, "Proot process exited with code: " + result);
                mMsgHandler.sendMessage(mMsgHandler.obtainMessage(PROCESS_EXITED, result));
            }
        };
        mWatcherThread.setName("Proot watcher");
        Log.i(TAG, "ProotTermSession constructor completed successfully");
    }

    private void initializeSession() throws IOException {
        LinuxEnvironment linuxEnv = new LinuxEnvironment(mContext);
        String[] shellCmd = linuxEnv.getShellCommand();
        String[] envVars = linuxEnv.getEnvironment();

        Log.i(TAG, "Shell command: " + java.util.Arrays.toString(shellCmd));
        Log.i(TAG, "Environment: " + java.util.Arrays.toString(envVars));

        mProcId = createSubprocess(shellCmd, envVars);
        Log.i(TAG, "Created subprocess with PID: " + mProcId);

        if (mProcId <= 0) {
            throw new IOException("Failed to create proot subprocess, PID=" + mProcId);
        }
    }

    private int createSubprocess(String[] args, String[] env) throws IOException {
        if (args == null || args.length == 0) {
            throw new IOException("No shell command provided");
        }

        String executable = args[0];
        Log.i(TAG, "Starting proot executable: " + executable);

        // Verify executable exists
        File execFile = new File(executable);
        if (!execFile.exists()) {
            throw new IOException("Proot binary not found: " + executable);
        }
        if (!FileCompat.canExecute(execFile)) {
            throw new IOException("Proot binary not executable: " + executable);
        }

        try {
            return TermExec.createSubprocess(mTermFd, args[0], args, env);
        } catch (Throwable e) {
            Log.e(TAG, "TermExec.createSubprocess failed", e);
            throw new IOException("Failed to create subprocess: " + e.getMessage(), e);
        }
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        super.initializeEmulator(columns, rows);
        mWatcherThread.start();
    }

    private void onProcessExit(int result) {
        Log.i(TAG, "Process exited with result: " + result);
        onProcessExit();
    }

    @Override
    public void finish() {
        Log.i(TAG, "Finishing ProotTermSession");
        hangupProcessGroup();
        super.finish();
    }

    void hangupProcessGroup() {
        if (mProcId > 0) {
            Log.i(TAG, "Sending SIGHUP to process group: " + (-mProcId));
            TermExec.sendSignal(-mProcId, 1);
        }
    }
}
