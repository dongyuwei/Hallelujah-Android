package rkr.tinykeyboard.inputmethod;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Stack;

/**
 * Java-side lifecycle of the librime engine: copies the bundled Rime data
 * (assets/rime) into device-protected storage, then starts the native engine.
 * Deployment (schema/dict compilation) runs inside librime; the IME polls
 * {@link #isMaintenancing()} until it finishes.
 */
public class RimeEngine {
    // Bump when the bundled assets/rime data changes, so devices re-copy and redeploy.
    private static final int RIME_ASSETS_VERSION = 1;
    private static final String SHARED_DATA_DIR = "rime_shared";
    private static final String USER_DATA_DIR = "rime_user";

    private static volatile boolean started;
    private static volatile boolean libraryLoaded;

    public static synchronized void init(Context context) {
        if (started) {
            return;
        }
        if (!libraryLoaded) {
            try {
                System.loadLibrary("rime_jni");
                libraryLoaded = true;
            } catch (Throwable t) {
                System.out.println("Hallelujah failed to load rime_jni: " + t);
                return;
            }
        }
        Context storageContext = context.createDeviceProtectedStorageContext();
        SharedPreferences prefs = storageContext.getSharedPreferences("rime", Context.MODE_PRIVATE);
        boolean fullCheck = prefs.getInt("rime_assets_version", -1) != RIME_ASSETS_VERSION;
        try {
            if (fullCheck) {
                copyAssets(storageContext, "rime",
                        new File(storageContext.getFilesDir(), SHARED_DATA_DIR));
                prefs.edit().putInt("rime_assets_version", RIME_ASSETS_VERSION).apply();
            }
            File sharedDataDir = new File(storageContext.getFilesDir(), SHARED_DATA_DIR);
            File userDataDir = new File(storageContext.getFilesDir(), USER_DATA_DIR);
            if (!userDataDir.exists()) {
                userDataDir.mkdirs();
            }
            started = nativeStartup(sharedDataDir.getAbsolutePath(),
                    userDataDir.getAbsolutePath(), fullCheck);
            if (!started) {
                System.out.println("Hallelujah failed to start librime");
            }
        } catch (Throwable t) {
            System.out.println("Hallelujah failed to start librime: " + t);
        }
    }

    public static boolean isStarted() {
        return started;
    }

    /** True while librime is compiling schemas; keys should be deferred. */
    public static boolean isMaintenancing() {
        return started && nativeIsMaintenancing();
    }

    /**
     * X11 keysym for a keyboard primary code: letters are lowercased (pinyin is
     * typed lowercase), DONE/DELETE map to Return/BackSpace, anything else is
     * used as its ASCII code; unsupported keys return 0.
     */
    public static int keysymFor(int primaryCode) {
        switch (primaryCode) {
            case -4: // Keyboard.KEYCODE_DONE
                return 0xff0d; // XK_Return
            case -5: // Keyboard.KEYCODE_DELETE
                return 0xff08; // XK_BackSpace
            default:
                if (primaryCode >= 'A' && primaryCode <= 'Z') {
                    return primaryCode + 32;
                }
                return primaryCode > 0 ? primaryCode : 0;
        }
    }

    /** Recursively copies assets/<assetDir> to the target directory. */
    private static void copyAssets(Context context, String assetDir, File targetDir)
            throws IOException {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        String prefix = assetDir + "/";
        Stack<String> dirs = new Stack<>();
        dirs.push(assetDir);
        while (!dirs.isEmpty()) {
            String dir = dirs.pop();
            String relative = dir.length() > assetDir.length() ? dir.substring(prefix.length()) : "";
            File targetSubDir = new File(targetDir, relative);
            if (!targetSubDir.exists()) {
                targetSubDir.mkdirs();
            }
            for (String child : context.getAssets().list(dir)) {
                String path = dir + "/" + child;
                if (context.getAssets().list(path).length > 0) {
                    dirs.push(path);
                } else {
                    copyFile(context, path, new File(targetSubDir, child));
                }
            }
        }
    }

    private static void copyFile(Context context, String assetPath, File target) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    // Native methods (implemented in jni/rime_jni/RimeJni.cpp)

    private static native boolean nativeStartup(String sharedDataDir, String userDataDir,
                                                boolean fullCheck);

    static native boolean nativeProcessKey(int keysym, int mask);

    static native RimeResponse nativeGetResponse();

    static native void nativeSelectCandidate(int index);

    static native void nativeClearComposition();

    static native boolean nativeIsMaintenancing();

    static native void nativeFinalize();
}
