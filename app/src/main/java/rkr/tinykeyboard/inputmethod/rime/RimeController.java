package rkr.tinykeyboard.inputmethod.rime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Android-side lifecycle for the rime wasm engine: installs the bundled
 * assets into device-protected storage (mirroring DictionaryDb), boots the
 * engine on its own background thread, and delivers results back on the main
 * thread.
 */
public class RimeController {

    private static final String TAG = "HallelujahRime";

    /**
     * Bump to force a clean rime root (prebuilt data + user directory) on
     * devices: version 2 wiped stale deployed build files and user dbs from
     * the previous schema, which broke set_ime after an in-place upgrade.
     */
    private static final int RIME_ASSETS_VERSION = 2;

    public interface Listener {
        void onRimeResult(RimeWasmEngine.Result result, char passthroughChar);

        void onRimeReady();
    }

    private final ExecutorService engineExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile RimeWasmEngine engine;
    private volatile boolean initFailed;
    private volatile Listener listener;

    protected RimeController(ExecutorService engineExecutor) {
        this.engineExecutor = engineExecutor;
    }

    public static RimeController create(final Context context) {
        RimeLog.setLogger(new RimeLog.Logger() {
            @Override
            public void log(String tag, String message, Throwable error) {
                // System.out shows up in logcat as "System.out" and is visible
                // in Robolectric output too, which Log.d is not
                System.out.println(TAG + "/" + tag + ": " + message
                        + (error == null ? "" : " " + error));
            }
        });
        // dedicated single thread: the engine is internally single-threaded
        // and must not queue behind the (slow) dictionary load
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final RimeController controller = new RimeController(executor);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                controller.init(context.getApplicationContext());
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
        engineExecutor.execute(new Runnable() {
            @Override
            public void run() {
                RimeWasmEngine.Result result;
                final Thread worker = Thread.currentThread();
                final java.util.concurrent.atomic.AtomicBoolean finished =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                // dumps the engine thread's stack if a key takes suspiciously
                // long - an on-device hang is otherwise invisible
                Thread watchdog = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long start = System.currentTimeMillis();
                        while (!finished.get()) {
                            try {
                                Thread.sleep(10000);
                            } catch (InterruptedException e) {
                                return;
                            }
                            if (finished.get()) {
                                return;
                            }
                            long secs = (System.currentTimeMillis() - start) / 1000;
                            StringBuilder sb = new StringBuilder(
                                    "WATCHDOG key=" + key + " stuck " + secs + "s\n");
                            StackTraceElement[] st = worker.getStackTrace();
                            for (int i = 0; i < Math.min(50, st.length); i++) {
                                sb.append("  at ").append(st[i]).append('\n');
                            }
                            RimeLog.w(TAG, sb.toString());
                        }
                    }
                });
                watchdog.setDaemon(true);
                watchdog.start();
                try {
                    long t0 = System.currentTimeMillis();
                    result = e.processKey(key);
                    RimeLog.w(TAG, "processKey(" + key + ") state=" + result.state
                            + " in " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable ex) {
                    // never leave a keypress dead: surface the failure and let
                    // the raw character through so typing still works
                    RimeLog.w(TAG, "processKey(" + key + ") failed", ex);
                    result = new RimeWasmEngine.Result();
                    result.state = RimeWasmEngine.STATE_UNHANDLED;
                } finally {
                    finished.set(true);
                    watchdog.interrupt();
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
        engineExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(e.selectCandidateOnCurrentPage(index), (char) 0);
                } catch (Throwable ex) {
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
            engineExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        e.close();
                    } catch (Throwable ex) {
                        RimeLog.w(TAG, "close failed", ex);
                    }
                }
            });
        }
        engineExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // asset installation + engine boot (background thread)
    // ------------------------------------------------------------------

    private void init(final Context context) {
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
            // the luna family outputs traditional hanzi by default; the app
            // targets simplified-Chinese users (opencc t2s ships in rime.data)
            e.setOption("simplification", true);
            // ART JIT warmup: run a throwaway key so the hot engine paths
            // are compiled before the user types (first real key otherwise
            // costs seconds)
            long w0 = System.currentTimeMillis();
            e.processKey("n");
            e.processKey("{Escape}");
            RimeLog.w(TAG, "jit warmup in " + (System.currentTimeMillis() - w0) + " ms");
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
        } catch (final Throwable t) {
            // includes Errors (e.g. NoSuchMethodError on old devices): without
            // this catch the worker thread dies silently and keys go dead
            initFailed = true;
            RimeLog.w(TAG, "engine init failed", t);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context,
                            "拼音引擎启动失败，已回退到内置词库", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private static void installAssetsIfNeeded(Context storage, File root) throws IOException {
        SharedPreferences prefs = storage.getSharedPreferences("rime", Context.MODE_PRIVATE);
        if (prefs.getInt("rime_assets_version", -1) == RIME_ASSETS_VERSION
                && new File(root, "usr/share/rime-data/build/luna_pinyin.table.bin").isFile()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        // wipe the whole rime root: stale deployed build files and user dbs
        // from a previous schema/version break set_ime on in-place upgrades
        deleteRecursively(root);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();

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
