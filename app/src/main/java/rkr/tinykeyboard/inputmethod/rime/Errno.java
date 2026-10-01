package rkr.tinykeyboard.inputmethod.rime;

/**
 * Errno numbers as expected by the emscripten-compiled musl inside rime.wasm.
 * Emscripten uses the WASI preview 1 errno numbering, NOT the Linux one:
 * the {@code __syscall_*} imports return the negated value, the WASI imports
 * return it directly.
 */
public final class Errno {
    public static final int SUCCESS = 0;
    public static final int EPERM = 63;
    public static final int ENOENT = 44;
    public static final int EBADF = 8;
    public static final int EACCES = 2;
    public static final int EEXIST = 20;
    public static final int EISDIR = 31;
    public static final int EINVAL = 28;
    public static final int EMFILE = 33;
    public static final int ENOMEM = 48;
    public static final int ENOSYS = 52;
    public static final int EOPNOTSUPP = 58;
    public static final int ENOTEMPTY = 55;
    public static final int ENOTDIR = 54;
    public static final int ENOTTY = 59;
    public static final int EOVERFLOW = 61;
    public static final int ERANGE = 68;
    public static final int ESPIPE = 70;
    public static final int EIO = 29;

    private Errno() {
    }

    /** Syscall convention: negative errno on failure. */
    public static long ret(int errno) {
        return errno == SUCCESS ? 0 : -errno;
    }
}
