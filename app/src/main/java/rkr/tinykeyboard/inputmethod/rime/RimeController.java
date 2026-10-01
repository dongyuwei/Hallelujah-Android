package rkr.tinykeyboard.inputmethod.rime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Android-side lifecycle for the rime wasm engine: installs the bundled
 * assets into device-protected storage (mirroring DictionaryDb), boots the
 * engine on the IME's single background thread, and delivers results back on
 * the main thread.
 */
public final class RimeController {

    private static final String TAG = "HallelujahRime";

    // Bump when the bundled rime assets change, so devices re-install them.
    private static final int RIME_ASSETS_VERSION = 1;

    public interface Listener {
        void onRimeResult(RimeWasmEngine.Result result, char passthroughChar);

        void onRimeReady();
    }

    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile RimeWasmEngine engine;
    private volatile boolean initFailed;
    private volatile Listener listener;

    private RimeController(ExecutorService executor) {
        this.executor = executor;
    }

    public static RimeController create(Context context, ExecutorService executor) {
        RimeLog.setLogger(new RimeLog.Logger() {
            @Override
            public void log(String tag, String message, Throwable error) {
                if (error == null) {
                    Log.d(TAG, tag + " " + message);
                } else {
                    Log.w(TAG, tag + " " + message, error);
                }
            }
        });
        final RimeController controller = new RimeController(executor);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                controller.init(context);
            }
        });
        return controller;
    }

    public boolean isReady() {
        RimeWasmEngine e = engine;
        return e != null && e.isStarted();
    }

    public boolean isFailed() {
        return initFailed;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Feeds one key; passthroughChar is committed verbatim if rime ignores the key. */
    public void processKey(final String key, final char passthroughChar) {
        final RimeWasmEngine e = engine;
        if (e == null) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                RimeWasmEngine.Result result;
                try {
                    result = e.processKey(key);
                } catch (RuntimeException ex) {
                    RimeLog.w(TAG, "processKey(" + key + ") failed", ex);
                    return;
                }
                deliver(result, passthroughChar);
            }
        });
    }

    public void selectCandidate(final int index) {
        final RimeWasmEngine e = engine;
        if (e == null) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(e.selectCandidateOnCurrentPage(index), (char) 0);
                } catch (RuntimeException ex) {
                    RimeLog.w(TAG, "selectCandidate failed", ex);
                }
            }
        });
    }

    private void deliver(final RimeWasmEngine.Result result, final char passthroughChar) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                l.onRimeResult(result, passthroughChar);
            }
        });
    }

    public void destroy() {
        final RimeWasmEngine e = engine;
        engine = null;
        if (e != null) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        e.close();
                    } catch (RuntimeException ex) {
                        RimeLog.w(TAG, "close failed", ex);
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // asset installation + engine boot (background thread)
    // ------------------------------------------------------------------

    private void init(Context context) {
        try {
            Context storage = context.createDeviceProtectedStorageContext();
            File root = new File(storage.getFilesDir(), "rime-root");
            installAssetsIfNeeded(storage, root);
            File userDir = new File(root, "rime");
            //noinspection ResultOfMethodCallIgnored
            userDir.mkdirs();

            byte[] wasm = readAsset(storage, "rime/rime.wasm");
            long t0 = System.currentTimeMillis();
            RimeWasmEngine e = RimeWasmEngine.create(root, wasm,
                    new RimeWasmEngine.DeployListener() {
                        @Override
                        public void onDeployStatus(String status) {
                            RimeLog.w(TAG, "deploy status: " + status);
                        }
                    });
            e.start("luna_pinyin_fluency", "朙月拼音·語句流", 10);
            engine = e;
            RimeLog.w(TAG, "engine ready in " + (System.currentTimeMillis() - t0) + " ms");
            final Listener l = listener;
            if (l != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        l.onRimeReady();
                    }
                });
            }
        } catch (RuntimeException | IOException e) {
            initFailed = true;
            RimeLog.w(TAG, "engine init failed", e);
        }
    }

    private static void installAssetsIfNeeded(Context storage, File root) throws IOException {
        SharedPreferences prefs = storage.getSharedPreferences("rime", Context.MODE_PRIVATE);
        if (prefs.getInt("rime_assets_version", -1) == RIME_ASSETS_VERSION
                && new File(root, "usr/share/rime-data/build/luna_pinyin.table.bin").isFile()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        deleteRecursively(new File(root, "usr"));

        String manifest = RimeDataPack.readUtf8(storage.getAssets().open("rime/rime-data-files.txt"));
        List<RimeDataPack.Entry> entries = RimeDataPack.parseManifest(manifest);
        InputStream pack = storage.getAssets().open("rime/rime.data");
        try {
            RimeDataPack.unpack(root, pack, entries);
        } finally {
            pack.close();
        }

        File buildDir = new File(root, "usr/share/rime-data/build");
        //noinspection ResultOfMethodCallIgnored
        buildDir.mkdirs();
        String[] schemaFiles = storage.getAssets().list("rime/luna-pinyin");
        if (schemaFiles != null) {
            for (String name : schemaFiles) {
                copyAssetToFile(storage, "rime/luna-pinyin/" + name, new File(buildDir, name));
            }
        }
        prefs.edit().putInt("rime_assets_version", RIME_ASSETS_VERSION).apply();
        RimeLog.w(TAG, "rime assets installed in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void copyAssetToFile(Context context, String asset, File target)
            throws IOException {
        InputStream in = context.getAssets().open(asset);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static byte[] readAsset(Context context, String asset) throws IOException {
        InputStream in = context.getAssets().open(asset);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
