package rkr.tinykeyboard.inputmethod.rime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmFunctionHandle;
import run.endive.wasm.types.ValType;

/**
 * Builds the complete host environment for my_rime's rime.wasm on top of
 * endive: the {@code env} module (Emscripten glue), the
 * {@code wasi_snapshot_preview1} module, plus the mmap bookkeeping and
 * time/mem helpers. Every function is a port of the corresponding piece of
 * the rime.js glue shipped with the exact wasm build we execute.
 */
public final class EmscriptenHost {

    private static final String TAG = "RimeHost";

    private static final long HEAP_MAX = 4294901760L; // 65535 pages, per build flags
    private static final int ASM_CONST_DEPLOY_STATUS = 253904;

    /** Notification from the wasm EM_ASM hook in my_rime's api.cpp handler. */
    public interface DeployStatusListener {
        void onDeployStatus(String status, String schemasJson);
    }

    /** Thrown by {@code exit(code)} to unwind out of the machine. */
    public static final class WasmExitException extends RuntimeException {
        public final int code;

        WasmExitException(int code) {
            super("wasm exit(" + code + ")", null, false, false);
            this.code = code;
        }
    }

    private final WasmVfs vfs;
    private final DeployStatusListener deployStatusListener;
    private Instance instance;
    private EmscriptenEh eh;

    /** addr -> mapping, for munmap write-back. */
    private static final class Mapping {
        final long fd;
        final long fileOffset;
        final long len;

        Mapping(long fd, long fileOffset, long len) {
            this.fd = fd;
            this.fileOffset = fileOffset;
            this.len = len;
        }
    }

    private final Map<Long, Mapping> mappings = new HashMap<>();

    public EmscriptenHost(WasmVfs vfs, DeployStatusListener deployStatusListener) {
        this.vfs = vfs;
        this.deployStatusListener = deployStatusListener;
    }

    /** Must be called right after instantiation so imports can reach the instance. */
    public void attach(Instance instance, EmscriptenEh eh) {
        this.instance = instance;
        this.eh = eh;
    }

    // ------------------------------------------------------------------
    // host function registry
    // ------------------------------------------------------------------

    private Memory mem() {
        return instance.memory();
    }

    private static ValType valType(char c) {
        switch (c) {
            case 'i':
                return ValType.I32;
            case 'j':
                return ValType.I64;
            case 'f':
                return ValType.F32;
            case 'd':
                return ValType.F64;
            default:
                throw new IllegalArgumentException("bad val type " + c);
        }
    }

    private static List<ValType> types(String sig) {
        List<ValType> out = new ArrayList<>(sig.length());
        for (int i = 0; i < sig.length(); i++) {
            out.add(valType(sig.charAt(i)));
        }
        return out;
    }

    private static HostFunction fn(String module, String name, String params, String results,
            final WasmFunctionHandle handle) {
        return new HostFunction(module, name, types(params), types(results), handle);
    }

    public List<run.endive.runtime.ImportFunction> hostFunctions() {
        List<run.endive.runtime.ImportFunction> fns = new ArrayList<>(120);
        final String env = "env";
        final String wasi = "wasi_snapshot_preview1";

        // ---- invoke_* (flattened signatures exactly as imported) ----
        addInvokes(fns, env);

        // ---- C++ exception emulation ----
        fns.add(fn(env, "__cxa_find_matching_catch_2", "", "i",
                (inst, a) -> new long[]{eh.findMatchingCatch0()}));
        fns.add(fn(env, "__cxa_find_matching_catch_3", "i", "i",
                (inst, a) -> new long[]{eh.findMatchingCatch1(a[0])}));
        fns.add(fn(env, "__cxa_find_matching_catch_7", "iiiii", "i",
                (inst, a) -> new long[]{eh.findMatchingCatch5(a[0], a[1], a[2], a[3], a[4])}));
        fns.add(fn(env, "__resumeException", "i", "",
                (inst, a) -> eh.resumeException(a[0])));
        fns.add(fn(env, "__cxa_begin_catch", "i", "i",
                (inst, a) -> new long[]{eh.cxaBeginCatch(a[0])}));
        fns.add(fn(env, "__cxa_end_catch", "", "",
                (inst, a) -> eh.cxaEndCatch()));
        fns.add(fn(env, "__cxa_throw", "iii", "",
                (inst, a) -> eh.cxaThrow(a[0], a[1], a[2])));
        fns.add(fn(env, "llvm_eh_typeid_for", "i", "i",
                (inst, a) -> new long[]{eh.llvmEhTypeidFor(a[0])}));
        fns.add(fn(env, "__cxa_rethrow", "", "",
                (inst, a) -> eh.cxaRethrow()));
        fns.add(fn(env, "__cxa_uncaught_exceptions", "", "i",
                (inst, a) -> new long[]{eh.cxaUncaughtExceptions()}));
        fns.add(fn(env, "__cxa_rethrow_primary_exception", "i", "",
                (inst, a) -> eh.cxaRethrowPrimaryException(a[0])));
        fns.add(fn(env, "_emscripten_throw_longjmp", "", "",
                (inst, a) -> eh.throwLongjmp()));

        // ---- syscalls over the VFS (negative errno on failure) ----
        fns.add(fn(env, "__syscall_fcntl64", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallFcntl64(a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_ioctl", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallIoctl(a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_faccessat", "iiii", "i",
                (inst, a) -> new long[]{vfs.syscallFaccessat(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(env, "__syscall_fdatasync", "i", "i",
                (inst, a) -> new long[]{vfs.syscallFdatasync(a[0])}));
        fns.add(fn(env, "__syscall_openat", "iiii", "i",
                (inst, a) -> new long[]{vfs.syscallOpenat(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(env, "__syscall_dup3", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallDup3(a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_fstat64", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallFstat64(inst, a[0], a[1])}));
        fns.add(fn(env, "__syscall_stat64", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallStat64(inst, a[0], a[1])}));
        fns.add(fn(env, "__syscall_newfstatat", "iiii", "i",
                (inst, a) -> new long[]{vfs.syscallNewfstatat(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(env, "__syscall_lstat64", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallLstat64(inst, a[0], a[1])}));
        fns.add(fn(env, "__syscall_mkdirat", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallMkdirat(inst, a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_getdents64", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallGetdents64(inst, a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_unlinkat", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallUnlinkat(inst, a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_rmdir", "i", "i",
                (inst, a) -> new long[]{vfs.syscallRmdir(inst, a[0])}));
        fns.add(fn(env, "__syscall_renameat", "iiii", "i",
                (inst, a) -> new long[]{vfs.syscallRenameat(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(env, "__syscall_symlink", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallSymlink(inst, a[0], a[1])}));
        fns.add(fn(env, "__syscall_getcwd", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallGetcwd(inst, a[0], a[1])}));
        fns.add(fn(env, "__syscall_readlinkat", "iiii", "i",
                (inst, a) -> new long[]{vfs.syscallReadlinkat(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(env, "__syscall_fchmod", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallFchmod(a[0], a[1])}));
        fns.add(fn(env, "__syscall_chmod", "ii", "i",
                (inst, a) -> new long[]{vfs.syscallChmod(inst, a[0], a[1])}));
        fns.add(fn(env, "__syscall_fchmodat2", "iiii", "i",
                (inst, a) -> new long[]{vfs.syscallFchmodat2(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(env, "__syscall_ftruncate64", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallFtruncate64(a[0], a[1], a[2])}));
        fns.add(fn(env, "__syscall_truncate64", "iii", "i",
                (inst, a) -> new long[]{vfs.syscallTruncate64(inst, a[0], a[1], a[2])}));

        // ---- misc emscripten runtime ----
        fns.add(fn(env, "_abort_js", "", "",
                (inst, a) -> {
                    throw new IllegalStateException("wasm abort()");
                }));
        fns.add(fn(env, "exit", "i", "",
                (inst, a) -> {
                    throw new WasmExitException((int) a[0]);
                }));
        fns.add(fn(env, "_emscripten_memcpy_js", "iii", "",
                (inst, a) -> {
                    int dest = (int) a[0], src = (int) a[1], num = (int) a[2];
                    byte[] buf = inst.memory().readBytes(src, num);
                    inst.memory().write(dest, buf, 0, buf.length);
                    return null;
                }));
        fns.add(fn(env, "emscripten_date_now", "", "d",
                (inst, a) -> new long[]{Double.doubleToRawLongBits(System.currentTimeMillis())}));
        fns.add(fn(env, "emscripten_get_now", "", "d",
                (inst, a) -> new long[]{Double.doubleToRawLongBits(
                        System.nanoTime() / 1_000_000.0)}));
        fns.add(fn(env, "_emscripten_get_now_is_monotonic", "", "i",
                (inst, a) -> new long[]{1}));
        fns.add(fn(env, "emscripten_get_heap_max", "", "i",
                (inst, a) -> new long[]{HEAP_MAX}));
        fns.add(fn(env, "emscripten_resize_heap", "i", "i",
                (inst, a) -> new long[]{resizeHeap(a[0])}));
        fns.add(fn(env, "_emscripten_system", "i", "i",
                (inst, a) -> new long[]{a[0] == 0 ? 0 : -Errno.ENOSYS}));
        fns.add(fn(env, "emscripten_asm_const_int", "iii", "i",
                (inst, a) -> new long[]{asmConstInt(a[0], a[1], a[2])}));

        // ---- time ----
        fns.add(fn(env, "_tzset_js", "iiii", "",
                (inst, a) -> {
                    tzsetJs(inst, a[0], a[1], a[2], a[3]);
                    return null;
                }));
        fns.add(fn(env, "_localtime_js", "iii", "",
                (inst, a) -> {
                    timeToTm(inst, i53(a[0], a[1]), a[2], false);
                    return null;
                }));
        fns.add(fn(env, "_gmtime_js", "iii", "",
                (inst, a) -> {
                    timeToTm(inst, i53(a[0], a[1]), a[2], true);
                    return null;
                }));
        fns.add(fn(env, "_mktime_js", "i", "i",
                (inst, a) -> new long[]{mktimeJs(inst, a[0])}));

        // ---- mmap ----
        fns.add(fn(env, "_mmap_js", "iiiiiiii", "i",
                (inst, a) -> new long[]{mmapJs(inst, a[0], a[1], a[2], a[3], a[4], a[5], a[6],
                        a[7])}));
        fns.add(fn(env, "_munmap_js", "iiiiiii", "i",
                (inst, a) -> new long[]{munmapJs(inst, a[0], a[1], a[2], a[3], a[4], a[5],
                        a[6])}));

        // ---- wasi_snapshot_preview1 (positive errno on failure) ----
        fns.add(fn(wasi, "fd_close", "i", "i",
                (inst, a) -> new long[]{vfs.wasiFdClose(a[0])}));
        fns.add(fn(wasi, "fd_read", "iiii", "i",
                (inst, a) -> new long[]{vfs.wasiFdRead(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(wasi, "fd_write", "iiii", "i",
                (inst, a) -> new long[]{vfs.wasiFdWrite(inst, a[0], a[1], a[2], a[3])}));
        fns.add(fn(wasi, "fd_seek", "iiiii", "i",
                (inst, a) -> new long[]{vfs.wasiFdSeek(inst, a[0], a[1], a[2], a[3], a[4])}));
        fns.add(fn(wasi, "fd_pread", "iiiiii", "i",
                (inst, a) -> new long[]{vfs.wasiFdPread(inst, a[0], a[1], a[2], a[3], a[4],
                        a[5])}));
        fns.add(fn(wasi, "fd_fdstat_get", "ii", "i",
                (inst, a) -> new long[]{vfs.wasiFdFdstatGet(inst, a[0], a[1])}));
        fns.add(fn(wasi, "environ_sizes_get", "ii", "i",
                (inst, a) -> new long[]{vfs.wasiEnvironSizesGet(inst, a[0], a[1])}));
        fns.add(fn(wasi, "environ_get", "ii", "i",
                (inst, a) -> new long[]{vfs.wasiEnvironGet(inst, a[0], a[1])}));

        return fns;
    }

    // ------------------------------------------------------------------
    // invoke registration table
    // ------------------------------------------------------------------

    private static final String[][] INVOKES = {
            {"iii", "iii", "i"}, {"vii", "iii", ""}, {"viiii", "iiiii", ""},
            {"iiii", "iiii", "i"}, {"viii", "iiii", ""}, {"v", "i", ""},
            {"iiiii", "iiiii", "i"}, {"viiiii", "iiiiii", ""}, {"ii", "ii", "i"},
            {"iiiiii", "iiiiii", "i"}, {"vi", "ii", ""}, {"viiiiii", "iiiiiii", ""},
            {"iiiiiii", "iiiiiii", "i"}, {"i", "i", "i"}, {"viiiiiii", "iiiiiiii", ""},
            {"iiiiiiii", "iiiiiiii", "i"}, {"iiid", "iiid", "i"}, {"iid", "iid", "i"},
            {"vid", "iid", ""}, {"dii", "iii", "d"}, {"di", "ii", "d"},
            {"viidi", "iiidi", ""}, {"viiid", "iiiid", ""}, {"diiii", "iiiii", "d"},
            {"viiiiiiii", "iiiiiiiii", ""}, {"viiiiid", "iiiiiid", ""},
            {"viiiiiiiii", "iiiiiiiiii", ""}, {"iiiiiiiiii", "iiiiiiiiii", "i"},
            {"viiiiiid", "iiiiiiid", ""}, {"d", "i", "d"}, {"iiiiid", "iiiiid", "i"},
            {"fiii", "iiii", "f"}, {"diii", "iiii", "d"},
            {"iiiiiiiiiiii", "iiiiiiiiiiii", "i"}, {"viiiiiiiiii", "iiiiiiiiiii", ""},
            {"viiiiiiiiiiiiiii", "iiiiiiiiiiiiiiii", ""}, {"viid", "iiid", ""},
            {"viji", "iiiii", ""}, {"viij", "iiiii", ""}, {"jii", "iii", "i"},
            {"jiii", "iiii", "i"}, {"vij", "iiii", ""}, {"jiiii", "iiiii", "i"},
            {"ji", "ii", "i"}, {"viijii", "iiiiiii", ""}, {"iiiijiii", "iiiiiiiii", "i"},
            {"iij", "iiii", "i"}, {"viiijdi", "iiiiiidi", ""}, {"iiiij", "iiiiii", "i"},
            {"iiiiij", "iiiiiii", "i"}, {"j", "i", "i"}, {"jj", "iii", "i"},
            {"djj", "iiiii", "d"}, {"iiji", "iiiii", "i"},
    };

    private void addInvokes(List<run.endive.runtime.ImportFunction> fns, String module) {
        for (String[] row : INVOKES) {
            final String letters = row[0];
            final String params = row[1];
            final String results = row[2];
            fns.add(fn(module, "invoke_" + letters, params, results,
                    (inst, a) -> eh.invoke(letters, results.length(), a)));
        }
    }

    // ------------------------------------------------------------------
    // memory
    // ------------------------------------------------------------------

    private long resizeHeap(long requestedSize) {
        if (requestedSize > HEAP_MAX) {
            return 0;
        }
        Memory memory = instance.memory();
        long oldSize = (long) memory.pages() * 65536L;
        long desired = Math.min(HEAP_MAX,
                alignUp(Math.max(requestedSize, oldSize + oldSize / 5), 65536));
        long pagesNeeded = (desired - oldSize + 65535) / 65536;
        if (pagesNeeded > 0) {
            if (memory.pages() + pagesNeeded > Math.min(HEAP_MAX / 65536,
                    memory.maximumPages() > 0 ? memory.maximumPages()
                            : Integer.MAX_VALUE)) {
                return 0;
            }
            int grown = memory.grow((int) pagesNeeded);
            if (grown < 0 && memory.pages() * 65536L < desired) {
                return 0;
            }
        }
        return 1;
    }

    private static long alignUp(long x, long multiple) {
        return x + (multiple - x % multiple) % multiple;
    }

    // ------------------------------------------------------------------
    // mmap / munmap
    // ------------------------------------------------------------------

    private long mmapJs(Instance inst, long len, long prot, long flags, long fd, long offsetLow,
            long offsetHigh, long allocatedPtr, long addrPtr) {
        long offset = i53(offsetLow, offsetHigh);
        if (!vfs.isOpenFileFd(fd)) {
            return -Errno.EBADF;
        }
        long alignedLen = alignUp(len, 65536);
        long ptr = inst.export("emscripten_builtin_memalign").apply(65536, alignedLen)[0];
        if (ptr == 0) {
            return -Errno.ENOMEM;
        }
        long read = vfs.readFileSlice(fd, offset, len, inst, ptr);
        if (read != 0) {
            return read;
        }
        // readFileSlice wrote the file bytes; space beyond EOF stays zeroed
        inst.memory().writeI32((int) allocatedPtr, 1);
        inst.memory().writeI32((int) addrPtr, (int) ptr);
        mappings.put(ptr, new Mapping(fd, offset, len));
        return 0;
    }

    private long munmapJs(Instance inst, long addr, long len, long prot, long flags, long fd,
            long offsetLow, long offsetHigh) {
        Mapping mapping = mappings.remove(addr);
        if ((prot & 2) != 0 && mapping != null) {
            return vfs.msyncToFile(mapping.fd, addr, mapping.len, inst, mapping.fileOffset);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // time (ports of rime.js helpers; struct tm: i32 fields)
    // ------------------------------------------------------------------

    private static long i53(long low, long high) {
        return Integer.toUnsignedLong((int) low) + (high << 32);
    }

    private void tzsetJs(Instance inst, long timezonePtr, long daylightPtr, long stdNamePtr,
            long dstNamePtr) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        int rawOffsetSec = zone.getRules().getOffset(now.toInstant()).getTotalSeconds();
        boolean dst = zone.getRules().isDaylightSavings(now.toInstant());
        inst.memory().writeI32((int) timezonePtr, rawOffsetSec);
        inst.memory().writeI32((int) daylightPtr, dst ? 1 : 0);
        writeTzName(inst, stdNamePtr, zone.getId());
        writeTzName(inst, dstNamePtr, zone.getId());
    }

    private static void writeTzName(Instance inst, long ptr, String name) {
        if (ptr == 0) {
            return;
        }
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, 31);
        inst.memory().write((int) ptr, bytes, 0, n);
        inst.memory().writeByte((int) ptr + n, (byte) 0);
    }

    private void timeToTm(Instance inst, long epochSec, long tmPtr, boolean utc) {
        Instant instant = Instant.ofEpochSecond(epochSec);
        ZoneId zone = utc ? ZoneId.of("UTC") : ZoneId.systemDefault();
        ZonedDateTime t = ZonedDateTime.ofInstant(instant, zone);
        Memory m = inst.memory();
        int p = (int) tmPtr;
        m.writeI32(p + 0, t.getSecond());
        m.writeI32(p + 4, t.getMinute());
        m.writeI32(p + 8, t.getHour());
        m.writeI32(p + 12, t.getDayOfMonth());
        m.writeI32(p + 16, t.getMonthValue() - 1);
        m.writeI32(p + 20, t.getYear() - 1900);
        m.writeI32(p + 24, t.getDayOfWeek().getValue() % 7);
        m.writeI32(p + 28, t.getDayOfYear() - 1);
        boolean dst = !utc && zone.getRules().isDaylightSavings(instant);
        m.writeI32(p + 32, dst ? 1 : 0);
        m.writeI32(p + 36, utc ? 0 : t.getOffset().getTotalSeconds());
    }

    private long mktimeJs(Instance inst, long tmPtr) {
        Memory m = inst.memory();
        int p = (int) tmPtr;
        int year = m.readInt(p + 20) + 1900;
        int month = m.readInt(p + 16) + 1;
        int day = m.readInt(p + 12);
        int hour = m.readInt(p + 8);
        int minute = m.readInt(p + 4);
        int second = m.readInt(p + 0);
        int isdst = m.readInt(p + 32);
        if (year < 1 || month < 1 || month > 12) {
            return -1;
        }
        try {
            LocalDate date = LocalDate.of(year, month, 1)
                    .withDayOfMonth(Math.min(day, dateLen(year, month)));
            ZonedDateTime z = ZonedDateTime.of(date.getYear(), date.getMonthValue(),
                    date.getDayOfMonth(), hour, minute, 0, 0, ZoneId.systemDefault());
            if (isdst < 0) {
                // caller wants us to guess; just use the standard offset result
            }
            long sec = z.toEpochSecond() + second;
            // write back resolved tm like JS does
            timeToTm(inst, sec, tmPtr, false);
            return sec;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int dateLen(int year, int month) {
        switch (month) {
            case 2:
                return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    // ------------------------------------------------------------------
    // EM_ASM dispatch (single const in this build: _deployStatus)
    // ------------------------------------------------------------------

    private long asmConstInt(long code, long sigPtr, long argbuf) {
        if (code != ASM_CONST_DEPLOY_STATUS) {
            RimeLog.w(TAG, "unknown asm const " + code);
            return 0;
        }
        // args read per the sig string, like readEmAsmArgs
        List<Long> args = new ArrayList<>(2);
        String sig = instance.memory().readCString((int) sigPtr);
        long buf = argbuf;
        for (int i = 0; i < sig.length(); i++) {
            char c = sig.charAt(i);
            if (c == 'i' || c == 'f') {
                args.add(Integer.toUnsignedLong(instance.memory().readInt((int) buf)));
                buf += 4;
            } else if (c == 'j' || c == 'd') {
                args.add(instance.memory().readLong((int) buf));
                buf += 8;
            }
        }
        if (args.size() >= 2) {
            String status = instance.memory().readCString(args.get(0).intValue());
            String schemas = instance.memory().readCString(args.get(1).intValue());
            if (deployStatusListener != null) {
                deployStatusListener.onDeployStatus(status, schemas);
            }
        }
        return 0;
    }
}
