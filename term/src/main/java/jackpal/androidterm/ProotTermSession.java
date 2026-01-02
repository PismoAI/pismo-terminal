package jackpal.androidterm;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import jackpal.androidterm.util.TermSettings;
import java.io.*;

public class ProotTermSession extends GenericTermSession {
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
        initializeSession();
        setTermOut(new ParcelFileDescriptor.AutoCloseOutputStream(mTermFd));
        setTermIn(new ParcelFileDescriptor.AutoCloseInputStream(mTermFd));
        mWatcherThread = new Thread() {
            @Override
            public void run() {
                Log.i(TermDebug.LOG_TAG, "Waiting for proot: " + mProcId);
                int result = TermExec.waitFor(mProcId);
                Log.i(TermDebug.LOG_TAG, "Proot exited: " + result);
                mMsgHandler.sendMessage(mMsgHandler.obtainMessage(PROCESS_EXITED, result));
            }
        };
        mWatcherThread.setName("Proot watcher");
    }

    private void initializeSession() throws IOException {
        LinuxEnvironment linuxEnv = new LinuxEnvironment(mContext);
        String[] shellCmd = linuxEnv.getShellCommand();
        String[] envVars = linuxEnv.getEnvironment();
        mProcId = createSubprocess(shellCmd, envVars);
    }

    private int createSubprocess(String[] args, String[] env) throws IOException {
        if (args == null || args.length == 0) throw new IOException("No shell command");
        Log.i(TermDebug.LOG_TAG, "Starting proot: " + args[0]);
        return TermExec.createSubprocess(mTermFd, args[0], args, env);
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        super.initializeEmulator(columns, rows);
        mWatcherThread.start();
    }

    private void onProcessExit(int result) { onProcessExit(); }

    @Override
    public void finish() { hangupProcessGroup(); super.finish(); }

    void hangupProcessGroup() { TermExec.sendSignal(-mProcId, 1); }
}
