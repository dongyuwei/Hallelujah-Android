package rkr.tinykeyboard.inputmethod.rime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

/**
 * The rime wasm engine on endive: one wasm instance + the emscripten host
 * environment, driven through the same exported API the my_rime web worker
 * uses (init, set_schema_name, set_ime, process, select_candidate_on_current_page,
 * change_page, set_page_size).
 *
 * <p>All methods must be called from a single thread (the IME background
 * executor on Android, the test thread on the JVM).
 */
public final class RimeWasmEngine {

    private static final String TAG = "RimeEngine";

    public static final int STATE_COMMITTED = 0;
    public static final int STATE_ACCEPTED = 1;
    public static final int STATE_REJECTED = 2;
    public static final int STATE_UNHANDLED = 3;

    public static final class Candidate {
        public final String text;
        public final String comment;

        Candidate(String text, String comment) {
            this.text = text;
            this.comment = comment;
        }
    }

    public static final class Result {
        public int state;
        public String committed;
        public String head = "";
        public String body = "";
        public String tail = "";
        public int page;
        public boolean isLastPage;
        public int highlighted;
        public List<Candidate> candidates = Collections.emptyList();
        public List<String> selectLabels;
        /** preedit as the my_rime editor renders it */
        public String preedit() {
            return head + body + tail;
        }
    }

    public interface DeployListener {
        void onDeployStatus(String status);
    }

    private final WasmVfs vfs;
    private final EmscriptenHost host;
    private final EmscriptenEh eh;
    private final Instance instance;
    private boolean started;

    private RimeWasmEngine(WasmVfs vfs, EmscriptenHost host, EmscriptenEh eh, Instance instance) {
        this.vfs = vfs;
        this.host = host;
        this.eh = eh;
        this.instance = instance;
    }

    /**
     * Instantiates the engine over a prepared root directory that already
     * contains usr/share/rime-data (prebuilt build/ included) and an empty
     * rime/ user directory.
     */
    public static RimeWasmEngine create(File rootDir, byte[] wasmBytes,
            final DeployListener deployListener) {
        WasmVfs vfs = new WasmVfs(rootDir, null);
        EmscriptenHost host = new EmscriptenHost(vfs, new EmscriptenHost.DeployStatusListener() {
            @Override
            public void onDeployStatus(String status, String schemasJson) {
                if (deployListener != null) {
                    deployListener.onDeployStatus(status);
                }
            }
        });
        List<run.endive.runtime.ImportFunction> fns = host.hostFunctions();
        ImportValues imports = ImportValues.builder().withFunctions(fns).build();
        Instance instance = Instance.builder(Parser.parse(wasmBytes))
                .withImportValues(imports)
                .build();
        EmscriptenEh eh = new EmscriptenEh(instance);
        host.attach(instance, eh);
        RimeWasmEngine engine = new RimeWasmEngine(vfs, host, eh, instance);
        // rime.js runs module ctors before onRuntimeInitialized
        instance.export("__wasm_call_ctors").apply();
        return engine;
    }

    /**
     * Runs the my_rime worker startup sequence: init, page size, schema name
     * fallback, then set_ime which initializes librime and creates the session.
     */
    public void start(String schemaId, String schemaName, int pageSize) {
        if (started) {
            return;
        }
        instance.export("init").apply();
        instance.export("set_page_size").apply(pageSize);
        callTwoStrings("set_schema_name", schemaId, schemaName);
        callWithString("set_ime", schemaId);
        started = true;
    }

    public boolean isStarted() {
        return started;
    }

    /** Feeds one key ("n", " ", "BackSpace", "Return", ...) to rime. */
    public Result processKey(String key) {
        return parseResult(callWithString("process", key));
    }

    public Result selectCandidateOnCurrentPage(int index) {
        return parseResult(instance.export("select_candidate_on_current_page").apply(index)[0]);
    }

    public Result changePage(boolean backward) {
        return parseResult(instance.export("change_page").apply(backward ? 1 : 0)[0]);
    }

    public void close() {
        if (started) {
            try {
                instance.export("reset").apply();
            } catch (RuntimeException e) {
                RimeLog.w(TAG, "reset failed", e);
            }
        }
        vfs.closeAll();
        instance.close();
        started = false;
    }

    // ------------------------------------------------------------------
    // marshaling
    // ------------------------------------------------------------------

    /**
     * ccall("name", ..., ["string"], [s]) equivalent: stack-allocates the
     * NUL-terminated UTF-8 string and returns the raw i32 result.
     */
    private long callWithString(String name, String arg) {
        byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
        long stackPtr = instance.export("_emscripten_stack_alloc").apply(bytes.length + 1)[0];
        instance.memory().write((int) stackPtr, bytes, 0, bytes.length);
        instance.memory().writeByte((int) stackPtr + bytes.length, (byte) 0);
        long[] result = instance.export(name).apply(stackPtr);
        return result == null ? 0 : result[0];
    }

    private void callTwoStrings(String name, String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        long ptrB = instance.export("_emscripten_stack_alloc").apply(bb.length + 1)[0];
        instance.memory().write((int) ptrB, bb, 0, bb.length);
        instance.memory().writeByte((int) ptrB + bb.length, (byte) 0);
        long ptrA = instance.export("_emscripten_stack_alloc").apply(ba.length + 1)[0];
        instance.memory().write((int) ptrA, ba, 0, ba.length);
        instance.memory().writeByte((int) ptrA + ba.length, (byte) 0);
        instance.export(name).apply(ptrA, ptrB);
    }

    /**
     * All the string-returning rime exports return a char* that stays valid
     * until the next call (a std::string member reused by the wrapper).
     */
    private String deref(long charPtr) {
        if (charPtr == 0) {
            return null;
        }
        return instance.memory().readCString((int) charPtr);
    }

    private Result parseResult(long charPtr) {
        String json = deref(charPtr);
        Result r = new Result();
        if (json == null || json.isEmpty()) {
            r.state = STATE_UNHANDLED;
            return r;
        }
        JsonObject obj;
        try {
            obj = new JsonParser().parse(json).getAsJsonObject();
        } catch (RuntimeException e) {
            RimeLog.w(TAG, "bad process() json: " + json);
            r.state = STATE_UNHANDLED;
            return r;
        }
        r.state = obj.has("state") ? obj.get("state").getAsInt() : STATE_UNHANDLED;
        r.committed = optString(obj, "committed");
        r.head = orEmpty(optString(obj, "head"));
        r.body = orEmpty(optString(obj, "body"));
        r.tail = orEmpty(optString(obj, "tail"));
        r.page = obj.has("page") ? obj.get("page").getAsInt() : 0;
        r.isLastPage = obj.has("isLastPage") && obj.get("isLastPage").getAsBoolean();
        r.highlighted = obj.has("highlighted") ? obj.get("highlighted").getAsInt() : 0;
        if (obj.has("candidates")) {
            JsonArray arr = obj.getAsJsonArray("candidates");
            List<Candidate> candidates = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject c = el.getAsJsonObject();
                candidates.add(new Candidate(orEmpty(optString(c, "text")),
                        optString(c, "comment")));
            }
            r.candidates = candidates;
        }
        if (obj.has("selectLabels")) {
            JsonArray arr = obj.getAsJsonArray("selectLabels");
            List<String> labels = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                labels.add(el.getAsString());
            }
            r.selectLabels = labels;
        }
        return r;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
