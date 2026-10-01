package rkr.tinykeyboard.inputmethod.rime;

import java.util.ArrayDeque;
import java.util.Deque;

import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;

/**
 * Emulation of the emscripten C++ exception runtime ("JS exception handling"),
 * ported 1:1 from the rime.js glue of the exact rime.wasm build we run.
 *
 * <p>Background: {@code __cxa_throw} throws a {@link CppException} carrying
 * the exception pointer; the generated {@code invoke_*} trampolines catch it,
 * restore the wasm stack pointer and flip {@code setThrew(1, 0)} so the wasm
 * landing pad can call {@code __cxa_find_matching_catch_*}, which inspects the
 * {@link ExceptionInfo} header the wasm wrote at {@code ptr - 24}.
 */
public final class EmscriptenEh {

    /** Sentinel thrown by {@code _emscripten_throw_longjmp} (JS throws Infinity). */
    public static final class LongjmpException extends RuntimeException {
        public LongjmpException() {
            super("longjmp", null, false, false);
        }
    }

    /** A propagating C++ exception; the JS glue throws the bare pointer value. */
    public static final class CppException extends RuntimeException {
        public final long excPtr;

        public CppException(long excPtr) {
            super("c++ exception at " + excPtr, null, false, false);
            this.excPtr = excPtr;
        }
    }

    /** Rust-style "unwind" stop marker (JS throws 'unwind'); unused but kept. */
    private static final class UnwindException extends RuntimeException {
        UnwindException() {
            super("unwind", null, false, false);
        }
    }

    private static final class ExceptionInfo {
        final long excPtr;
        final long ptr; // excPtr - 24

        ExceptionInfo(long excPtr) {
            this.excPtr = excPtr;
            this.ptr = excPtr - 24;
        }
    }

    private final Instance instance;
    private final Deque<Long> exceptionCaught = new ArrayDeque<>();
    private long exceptionLast;
    private int uncaughtExceptionCount;

    public EmscriptenEh(Instance instance) {
        this.instance = instance;
    }

    private Memory mem() {
        return instance.memory();
    }

    private ExportFunction ex(String name) {
        return instance.export(name);
    }

    // ------------------------------------------------------------------
    // ExceptionInfo accessors (layout from rime.js)
    // ------------------------------------------------------------------

    private void setAdjustedPtr(ExceptionInfo info, long v) {
        mem().writeI32((int) (info.ptr + 16), (int) v);
    }

    private long getAdjustedPtr(ExceptionInfo info) {
        return Integer.toUnsignedLong(mem().readInt((int) (info.ptr + 16)));
    }

    private long getType(ExceptionInfo info) {
        return Integer.toUnsignedLong(mem().readInt((int) (info.ptr + 4)));
    }

    private boolean getCaught(ExceptionInfo info) {
        return mem().read((int) (info.ptr + 12)) != 0;
    }

    private void setCaught(ExceptionInfo info, boolean v) {
        mem().writeByte((int) (info.ptr + 12), (byte) (v ? 1 : 0));
    }

    private boolean getRethrown(ExceptionInfo info) {
        return mem().read((int) (info.ptr + 13)) != 0;
    }

    private void setRethrown(ExceptionInfo info, boolean v) {
        mem().writeByte((int) (info.ptr + 13), (byte) (v ? 1 : 0));
    }

    private long getExceptionPtr(ExceptionInfo info) {
        long isPointer = ex("__cxa_is_pointer_type").apply(getType(info))[0];
        if (isPointer != 0) {
            return Integer.toUnsignedLong(mem().readInt((int) info.excPtr));
        }
        long adjusted = getAdjustedPtr(info);
        return adjusted != 0 ? adjusted : info.excPtr;
    }

    // ------------------------------------------------------------------
    // __cxa_* imports
    // ------------------------------------------------------------------

    /** {@code __cxa_throw(ptr, type, destructor)}: throws a CppException. */
    public long[] cxaThrow(long ptr, long type, long destructor) {
        ExceptionInfo info = new ExceptionInfo(ptr);
        setAdjustedPtr(info, 0);
        mem().writeI32((int) (info.ptr + 4), (int) type);
        mem().writeI32((int) (info.ptr + 8), (int) destructor);
        exceptionLast = ptr;
        uncaughtExceptionCount++;
        throw new CppException(ptr);
    }

    /** {@code __cxa_begin_catch(ptr)}: returns the adjusted exception pointer. */
    public long cxaBeginCatch(long ptr) {
        ExceptionInfo info = new ExceptionInfo(ptr);
        if (!getCaught(info)) {
            setCaught(info, true);
            uncaughtExceptionCount--;
        }
        setRethrown(info, false);
        exceptionCaught.push(ptr);
        ex("__cxa_increment_exception_refcount").apply(info.excPtr);
        return getExceptionPtr(info);
    }

    /** {@code __cxa_end_catch()}. */
    public long[] cxaEndCatch() {
        ex("setThrew").apply(0, 0);
        long ptr = exceptionCaught.pop();
        ex("__cxa_decrement_exception_refcount").apply(ptr);
        exceptionLast = 0;
        return null;
    }

    /** {@code __cxa_rethrow()}: rethrows the innermost caught exception. */
    public long[] cxaRethrow() {
        Long ptr = exceptionCaught.poll();
        if (ptr == null) {
            throw new IllegalStateException("no exception to rethrow");
        }
        ExceptionInfo info = new ExceptionInfo(ptr);
        if (!getRethrown(info)) {
            exceptionCaught.push(ptr);
            setRethrown(info, true);
            setCaught(info, false);
            uncaughtExceptionCount++;
        }
        exceptionLast = ptr;
        throw new CppException(ptr);
    }

    /** {@code __cxa_rethrow_primary_exception(ptr)}. */
    public long[] cxaRethrowPrimaryException(long ptr) {
        if (ptr == 0) {
            return null;
        }
        ExceptionInfo info = new ExceptionInfo(ptr);
        exceptionCaught.push(ptr);
        setRethrown(info, true);
        return cxaRethrow();
    }

    /** {@code __cxa_uncaught_exceptions()}. */
    public long cxaUncaughtExceptions() {
        return uncaughtExceptionCount;
    }

    /** {@code llvm_eh_typeid_for(type)}: identity in this emscripten build. */
    public long llvmEhTypeidFor(long type) {
        return type;
    }

    /** {@code __resumeException(ptr)}. */
    public long[] resumeException(long ptr) {
        if (exceptionLast == 0) {
            exceptionLast = ptr;
        }
        throw new CppException(exceptionLast);
    }

    private long findMatchingCatch(long[] args) {
        long thrown = exceptionLast;
        if (thrown == 0) {
            setTempRet0(0);
            return 0;
        }
        ExceptionInfo info = new ExceptionInfo(thrown);
        setAdjustedPtr(info, thrown);
        long thrownType = getType(info);
        if (thrownType == 0) {
            setTempRet0(0);
            return thrown;
        }
        for (long caughtType : args) {
            if (caughtType == 0 || caughtType == thrownType) {
                break;
            }
            // __cxa_can_catch(caughtType, thrownType, &adjusted_ptr)
            long canCatch = ex("__cxa_can_catch").apply(caughtType, thrownType,
                    info.ptr + 16)[0];
            if (canCatch != 0) {
                setTempRet0(caughtType);
                return thrown;
            }
        }
        setTempRet0(thrownType);
        return thrown;
    }

    private void setTempRet0(long value) {
        ex("_emscripten_tempret_set").apply(value);
    }

    public long findMatchingCatch0() {
        return findMatchingCatch(new long[0]);
    }

    public long findMatchingCatch1(long a0) {
        return findMatchingCatch(new long[]{a0});
    }

    public long findMatchingCatch5(long a0, long a1, long a2, long a3, long a4) {
        return findMatchingCatch(new long[]{a0, a1, a2, a3, a4});
    }

    // ------------------------------------------------------------------
    // invoke_* trampolines
    // ------------------------------------------------------------------

    private static final long[] EMPTY_ARGS = new long[0];
    private final java.util.Map<String, ExportFunction> dynCalls = new java.util.HashMap<>();
    private final java.util.Set<String> noDynCall = new java.util.HashSet<>();

    /**
     * Implements every {@code invoke_<sig>} import, mirroring the JS glue:
     * {@code function invoke_xx(index, ...) { var sp = stackSave(); try { return
     * getWasmTableEntry(index)(...) } catch (e) { stackRestore(sp); if (e !== e+0)
     * throw e; _setThrew(1, 0) } }}.
     *
     * <p>This build predates wasm-bigint at the JS boundary, so i64 values are
     * flattened: each i64 param arrives as (lo, hi) i32 pairs and i64 results
     * leave via the low 32 bits plus tempRet0. Conveniently every invoke whose
     * signature contains an i64 also has a {@code dynCall_<sig>} export whose
     * import signature is identical to the invoke's flattened signature — the
     * wasm-side dynCall adapter reassembles the i64s itself, so we forward to
     * it verbatim. Pure i32 / f64 invokes call the table entry directly.
     *
     * @param letters      the invoke suffix, e.g. "viji"
     * @param resultSlots  width of the flattened return value in longs
     * @param args         flattened (target, params...) as received from wasm
     */
    public long[] invoke(String letters, int resultSlots, long[] args) {
        long sp = ex("emscripten_stack_get_current").apply()[0];
        try {
            ExportFunction dynCall = dynCallFor(letters);
            if (dynCall != null) {
                return dynCall.apply(args);
            }
            int target = (int) args[0];
            long[] call = args.length == 1 ? EMPTY_ARGS
                    : java.util.Arrays.copyOfRange(args, 1, args.length);
            TableInstance table = instance.table(0);
            return instance.getMachine().call(table.requiredRef(target), call);
        } catch (Throwable e) {
            // the runtime may wrap host exceptions; the JS glue rethrows
            // anything that is not a C++ exception pointer or a longjmp
            if (e instanceof CppException || e instanceof LongjmpException
                    || containsCppException(e)) {
                ex("_emscripten_stack_restore").apply(sp);
                ex("setThrew").apply(1, 0);
                return resultSlots == 0 ? null : new long[resultSlots];
            }
            throw e;
        }
    }

    private ExportFunction dynCallFor(String letters) {
        ExportFunction cached = dynCalls.get(letters);
        if (cached != null) {
            return cached;
        }
        if (noDynCall.contains(letters)) {
            return null;
        }
        try {
            ExportFunction fn = ex("dynCall_" + letters);
            dynCalls.put(letters, fn);
            return fn;
        } catch (RuntimeException e) {
            noDynCall.add(letters);
            return null;
        }
    }

    private static boolean containsCppException(Throwable e) {
        for (Throwable c = e.getCause(); c != null && c != e; c = c.getCause()) {
            if (c instanceof CppException || c instanceof LongjmpException) {
                return true;
            }
        }
        return false;
    }

    private static long[] zeroResult(int count) {
        return count == 0 ? null : new long[count];
    }

    /** {@code _emscripten_throw_longjmp()}: JS throws Infinity. */
    public long[] throwLongjmp() {
        throw new LongjmpException();
    }
}
