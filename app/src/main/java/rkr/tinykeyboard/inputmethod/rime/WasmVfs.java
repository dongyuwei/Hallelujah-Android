package rkr.tinykeyboard.inputmethod.rime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import run.endive.runtime.Instance;
import run.endive.runtime.Memory;

/**
 * A Linux-flavoured virtual filesystem for the rime wasm module, backed by a
 * real directory tree (Android filesDir, or a temp dir in JVM tests).
 *
 * <p>It serves both the musl {@code __syscall_*} imports (negative errno
 * returns, WASI errno numbering as used by emscripten) and the
 * {@code wasi_snapshot_preview1} imports (positive errno returns). Struct
 * layouts (stat64, dirent64, fdstat, iovec) are byte-for-byte ports of the
 * emscripten glue shipped with my_rime's rime.js.
 */
public final class WasmVfs {

    private static final String TAG = "RimeVfs";

    /** musl/emscripten open(2) flags. */
    private static final int O_RDONLY = 0, O_WRONLY = 1, O_RDWR = 2;
    private static final int O_CREAT = 0x40, O_EXCL = 0x80, O_TRUNC = 0x200,
            O_APPEND = 0x400, O_DIRECTORY = 0x10000, O_NOFOLLOW = 0x20000;

    private static final int AT_FDCWD = -100;
    private static final int DIRENT_SIZE = 280;
    private static final long WASI_FILETYPE_CHARACTER_DEVICE = 2;
    private static final long WASI_FILETYPE_DIRECTORY = 3;
    private static final long WASI_FILETYPE_REGULAR_FILE = 4;

    /** fd -> open file. */
    private static final class Fd {
        File file; // null for the stdio pseudo fds
        boolean stdio;
        boolean append;
        boolean readable;
        boolean writable;
        RandomAccessFile raf; // null for directories/stdio
        String[] dirEntries; // directory listing for getdents64
        int dirIndex;
        long position;
        final StringBuilder stdioBuffer = new StringBuilder();
    }

    // host-activity counters (performance diagnostics)
    public volatile long stdioBytes;
    public volatile long stdioLines;
    public volatile long syscallCount;

    private final File root;
    private final Map<Integer, Fd> fds = new HashMap<>();
    private String cwd = "/";
    private final RimeLog.Logger log;
    /** Set to trace every syscall (debugging). */
    public static volatile boolean TRACE = false;

    public WasmVfs(File root, RimeLog.Logger log) {
        this.root = root;
        this.log = log == null ? new RimeLog.Logger() {
            @Override
            public void log(String tag, String message, Throwable error) {
                RimeLog.w(tag, message, error);
            }
        } : log;
        // stdio fds exist from the start; rime logs diagnostics on stderr
        Fd stdin = new Fd();
        stdin.stdio = true;
        stdin.readable = true;
        fds.put(0, stdin);
        Fd stdout = new Fd();
        stdout.stdio = true;
        stdout.writable = true;
        fds.put(1, stdout);
        Fd stderr = new Fd();
        stderr.stdio = true;
        stderr.writable = true;
        fds.put(2, stderr);
    }

    // ------------------------------------------------------------------
    // path helpers
    // ------------------------------------------------------------------

    private String readCString(Instance inst, long ptr) {
        return inst.memory().readCString((int) ptr);
    }

    private void trace(String op, String detail, long result) {
        if (TRACE) {
            log.log(TAG, op + " " + detail + " -> " + result, null);
        }
    }

    /** Resolves a wasm path against the root dir; null if it escapes. */
    private File resolve(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String normalized = normalize(path);
        if (normalized == null) {
            return null;
        }
        return new File(root, normalized);
    }

    private static String normalize(String path) {
        if (!path.startsWith("/")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append('/').append(p);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private String calculateAt(long dirfd, String path, boolean allowEmpty) {
        if (path.startsWith("/")) {
            return path;
        }
        String base;
        if ((int) dirfd == AT_FDCWD) {
            base = cwd;
        } else {
            Fd fd = fds.get((int) dirfd);
            if (fd == null || fd.file == null) {
                return null;
            }
            base = absolute(fd.file.getPath());
        }
        if (path.isEmpty()) {
            return allowEmpty ? base : null;
        }
        return base.equals("/") ? "/" + path : base + "/" + path;
    }

    /** Path of {@code f} relative to the root, as a "/..." wasm path. */
    private String absolute(String realPath) {
        String rootPath = root.getPath();
        String p = realPath.startsWith(rootPath) ? realPath.substring(rootPath.length()) : realPath;
        if (p.isEmpty()) {
            return "/";
        }
        return p.startsWith("/") ? p : "/" + p;
    }

    // ------------------------------------------------------------------
    // fd helpers
    // ------------------------------------------------------------------

    private Fd getFd(long fd) throws VfsError {
        Fd f = fds.get((int) fd);
        if (f == null) {
            throw new VfsError(Errno.EBADF);
        }
        return f;
    }

    private int allocFd() {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!fds.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    private static final class VfsError extends RuntimeException {
        final int errno;

        VfsError(int errno) {
            super("errno " + errno);
            this.errno = errno;
        }
    }

    // ------------------------------------------------------------------
    // syscalls: open / close / dup
    // ------------------------------------------------------------------

    public long syscallOpenat(Instance inst, long dirfd, long pathPtr, long flags, long varargs) {
        try {
            String path = calculateAt(dirfd, readCString(inst, pathPtr), false);
            if (path == null) {
                return Errno.ret(Errno.ENOENT);
            }
            int mode = 0;
            if (varargs != 0) {
                mode = inst.memory().readInt((int) varargs);
            }
            File file = resolve(path);
            if (file == null) {
                return Errno.ret(Errno.EPERM);
            }
            boolean exists = file.exists();
            boolean isDir = exists && file.isDirectory();
            if ((flags & O_DIRECTORY) != 0 && !isDir) {
                return Errno.ret(Errno.ENOTDIR);
            }
            if (!exists) {
                if ((flags & O_CREAT) == 0) {
                    return Errno.ret(Errno.ENOENT);
                }
                if (isDir) {
                    return Errno.ret(Errno.EISDIR);
                }
                File parent = file.getParentFile();
                if (parent == null || !parent.isDirectory()) {
                    return Errno.ret(Errno.ENOENT);
                }
                try {
                    if (!file.createNewFile()) {
                        return Errno.ret(Errno.EIO);
                    }
                } catch (IOException e) {
                    return Errno.ret(Errno.EIO);
                }
            } else if ((flags & O_CREAT) != 0 && (flags & O_EXCL) != 0) {
                return Errno.ret(Errno.EEXIST);
            }
            int access = (int) (flags & (O_RDWR | O_WRONLY));
            boolean readable = access != O_WRONLY;
            boolean writable = access != O_RDONLY;
            if (!file.canWrite() && writable && !isDir) {
                return Errno.ret(Errno.EACCES);
            }
            Fd fd = new Fd();
            fd.file = file;
            fd.readable = readable;
            fd.writable = writable;
            fd.append = (flags & O_APPEND) != 0;
            if (isDir) {
                fd.dirEntries = file.list();
                if (fd.dirEntries == null) {
                    return Errno.ret(Errno.ENOTDIR);
                }
            } else {
                try {
                    fd.raf = new RandomAccessFile(file, writable ? "rw" : "r");
                } catch (IOException e) {
                    return Errno.ret(Errno.EIO);
                }
                if ((flags & O_TRUNC) != 0 && writable) {
                    try {
                        fd.raf.setLength(0);
                    } catch (IOException e) {
                        return Errno.ret(Errno.EIO);
                    }
                }
            }
            int fdNum = allocFd();
            fds.put(fdNum, fd);
            trace("openat", path + " flags=0x" + Long.toHexString(flags), fdNum);
            return fdNum;
        } catch (VfsError e) {
            trace("openat", "errno", -e.errno);
            return Errno.ret(e.errno);
        }
    }

    public long wasiFdClose(long fd) {
        try {
            Fd f = getFd(fd);
            if (f.stdio) {
                return Errno.SUCCESS; // keep stdio open
            }
            if (f.raf != null) {
                try {
                    f.raf.close();
                } catch (IOException e) {
                    return Errno.EIO;
                }
            }
            fds.remove((int) fd);
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return e.errno;
        }
    }

    public long syscallDup3(long oldFd, long newFd, long flags) {
        try {
            Fd old = getFd(oldFd);
            if (old.stdio || (int) newFd < 0) {
                return Errno.ret(Errno.EBADF);
            }
            Fd target = fds.get((int) newFd);
            if (target != null && !target.stdio && target.raf != null) {
                try {
                    target.raf.close();
                } catch (IOException ignored) {
                }
            }
            fds.put((int) newFd, old);
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    // ------------------------------------------------------------------
    // read / write / seek
    // ------------------------------------------------------------------

    public long wasiFdRead(Instance inst, long fd, long iov, long iovcnt, long pnum) {
        try {
            Fd f = getFd(fd);
            if (f.stdio) {
                inst.memory().writeI32((int) pnum, 0);
                return Errno.SUCCESS;
            }
            if (f.raf == null) {
                return Errno.EISDIR;
            }
            long total = readv(inst, f, iov, iovcnt, -1);
            inst.memory().writeI32((int) pnum, (int) total);
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return e.errno;
        } catch (IOException e) {
            return Errno.EIO;
        }
    }

    public long wasiFdPread(Instance inst, long fd, long iov, long iovcnt, long offsetLow,
            long offsetHigh, long pnum) {
        try {
            Fd f = getFd(fd);
            if (f.stdio || f.raf == null) {
                return Errno.EBADF;
            }
            long total = readv(inst, f, iov, iovcnt, i53(offsetLow, offsetHigh));
            inst.memory().writeI32((int) pnum, (int) total);
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return e.errno;
        } catch (IOException e) {
            return Errno.EIO;
        }
    }

    private long readv(Instance inst, Fd f, long iov, long iovcnt, long position) throws IOException {
        Memory mem = inst.memory();
        long cursor = position >= 0 ? position : f.position;
        long total = 0;
        for (long i = 0; i < iovcnt; i++) {
            long ptr = Integer.toUnsignedLong(mem.readInt((int) (iov + i * 8)));
            int len = mem.readInt((int) (iov + i * 8 + 4));
            if (len == 0) {
                continue;
            }
            f.raf.seek(cursor + total);
            byte[] buf = new byte[len];
            int n = f.raf.read(buf);
            if (n <= 0) {
                break;
            }
            mem.write((int) ptr, buf, 0, n);
            total += n;
            if (n < len) {
                break;
            }
        }
        if (position < 0) {
            f.position = cursor + total;
        }
        return total;
    }

    public long wasiFdWrite(Instance inst, long fd, long iov, long iovcnt, long pnum) {
        try {
            Fd f = getFd(fd);
            if (f.stdio) {
                long total = writeStdio(inst, f, iov, iovcnt);
                inst.memory().writeI32((int) pnum, (int) total);
                return Errno.SUCCESS;
            }
            if (f.raf == null) {
                return Errno.EISDIR;
            }
            Memory mem = inst.memory();
            long total = 0;
            for (long i = 0; i < iovcnt; i++) {
                long ptr = Integer.toUnsignedLong(mem.readInt((int) (iov + i * 8)));
                int len = mem.readInt((int) (iov + i * 8 + 4));
                if (len == 0) {
                    continue;
                }
                byte[] buf = mem.readBytes((int) ptr, len);
                long pos = f.append ? f.raf.length() : f.position;
                f.raf.seek(pos);
                f.raf.write(buf);
                total += len;
                if (!f.append) {
                    f.position = pos + len;
                }
            }
            inst.memory().writeI32((int) pnum, (int) total);
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return e.errno;
        } catch (IOException e) {
            return Errno.EIO;
        }
    }

    private long writeStdio(Instance inst, Fd f, long iov, long iovcnt) {
        Memory mem = inst.memory();
        long total = 0;
        for (long i = 0; i < iovcnt; i++) {
            long ptr = Integer.toUnsignedLong(mem.readInt((int) (iov + i * 8)));
            int len = mem.readInt((int) (iov + i * 8 + 4));
            total += len;
            stdioBytes += len;
            for (int j = 0; j < len; j++) {
                int b = mem.read((int) (ptr + j)) & 0xff;
                if (b == '\n') {
                    stdioLines++;
                    log.log(TAG + ".stdio", f.stdioBuffer.toString(), null);
                    f.stdioBuffer.setLength(0);
                } else {
                    f.stdioBuffer.append((char) b);
                }
            }
        }
        return total;
    }

    public long wasiFdSeek(Instance inst, long fd, long offsetLow, long offsetHigh, long whence,
            long newOffsetPtr) {
        try {
            Fd f = getFd(fd);
            if (f.stdio) {
                return Errno.ESPIPE;
            }
            long offset = i53(offsetLow, offsetHigh);
            long base;
            if (whence == 0) {
                base = 0;
            } else if (whence == 1) {
                base = f.position;
            } else if (whence == 2) {
                base = f.raf != null ? fileLength(f) : 0;
            } else {
                return Errno.EINVAL;
            }
            f.position = base + offset;
            if (f.dirEntries != null && offset == 0 && whence == 0) {
                // rewind also resets the dirent cursor, as emscripten does
                f.dirIndex = 0;
            }
            if (newOffsetPtr != 0) {
                inst.memory().writeLong((int) newOffsetPtr, f.position);
            }
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return e.errno;
        } catch (IOException e) {
            return Errno.EIO;
        }
    }

    private long fileLength(Fd f) throws IOException {
        return f.raf != null ? f.raf.length() : 0;
    }

    /** Reads a 64-bit value split into two i32 halves, as emscripten does. */
    private static long i53(long low, long high) {
        return Integer.toUnsignedLong((int) low) + (high << 32);
    }

    // ------------------------------------------------------------------
    // stat family
    // ------------------------------------------------------------------

    private long writeStat(Instance inst, long buf, File file) {
        Memory mem = inst.memory();
        boolean isDir = file.isDirectory();
        long size = isDir ? 4096 : file.length();
        long mtimeMs = file.lastModified();
        mem.writeI32((int) buf + 0, 1); // dev
        mem.writeI32((int) buf + 4, isDir ? 0x41ED : 0x81FF); // mode
        mem.writeI32((int) buf + 8, 1); // nlink
        mem.writeI32((int) buf + 12, 0); // uid
        mem.writeI32((int) buf + 16, 0); // gid
        mem.writeI32((int) buf + 20, 0); // rdev
        mem.writeLong((int) buf + 24, size);
        mem.writeI32((int) buf + 32, 4096); // blksize
        mem.writeI32((int) buf + 36, (int) ((size + 4095) / 4096)); // blocks
        writeTimePair(mem, (int) buf + 40, mtimeMs); // atime
        writeTimePair(mem, (int) buf + 52, mtimeMs); // mtime
        writeTimePair(mem, (int) buf + 64, mtimeMs); // ctime
        return 0;
    }

    private static void writeTimePair(Memory mem, int offset, long ms) {
        long sec = Math.floorDiv(ms, 1000L);
        long nsec = Math.floorMod(ms, 1000L) * 1000000L;
        mem.writeLong(offset, sec);
        mem.writeI32(offset + 8, (int) nsec);
    }

    public long syscallStat64(Instance inst, long pathPtr, long buf) {
        File file = resolve(readCString(inst, pathPtr));
        if (file == null || !file.exists()) {
            return Errno.ret(Errno.ENOENT);
        }
        return writeStat(inst, buf, file);
    }

    public long syscallLstat64(Instance inst, long pathPtr, long buf) {
        // no symlinks in this fs, lstat == stat
        return syscallStat64(inst, pathPtr, buf);
    }

    public long syscallFstat64(Instance inst, long fd, long buf) {
        try {
            Fd f = getFd(fd);
            if (f.stdio) {
                Memory mem = inst.memory();
                mem.writeI32((int) buf + 0, 1);
                mem.writeI32((int) buf + 4, 0x2190); // char device
                mem.writeI32((int) buf + 8, 1);
                mem.writeLong((int) buf + 24, 0);
                mem.writeI32((int) buf + 32, 4096);
                mem.writeI32((int) buf + 36, 0);
                writeTimePair(mem, (int) buf + 40, 0);
                writeTimePair(mem, (int) buf + 52, 0);
                writeTimePair(mem, (int) buf + 64, 0);
                return 0;
            }
            return writeStat(inst, buf, f.file);
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    public long syscallNewfstatat(Instance inst, long dirfd, long pathPtr, long buf, long flags) {
        String path = calculateAt(dirfd, readCString(inst, pathPtr), true);
        if (path == null) {
            return Errno.ret(Errno.ENOENT);
        }
        File file = resolve(path);
        if (file == null || !file.exists()) {
            return Errno.ret(Errno.ENOENT);
        }
        return writeStat(inst, buf, file);
    }

    public long syscallFaccessat(Instance inst, long dirfd, long pathPtr, long amode, long flags) {
        String path = calculateAt(dirfd, readCString(inst, pathPtr), false);
        File file = path == null ? null : resolve(path);
        if (file == null || !file.exists()) {
            return Errno.ret(Errno.ENOENT);
        }
        if (amode != 0 && !file.canRead()) {
            return Errno.ret(Errno.EACCES);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // directory operations
    // ------------------------------------------------------------------

    public long syscallGetdents64(Instance inst, long fd, long dirp, long count) {
        try {
            Fd f = getFd(fd);
            if (f.dirEntries == null) {
                return Errno.ret(Errno.ENOTDIR);
            }
            Memory mem = inst.memory();
            long pos = 0;
            while (f.dirIndex < f.dirEntries.length && pos + DIRENT_SIZE <= count) {
                String name = f.dirEntries[f.dirIndex];
                long type = new File(f.file, name).isDirectory() ? 4 : 8;
                int at = (int) (dirp + pos);
                mem.writeLong(at + 0, f.dirIndex + 1L); // ino
                mem.writeLong(at + 8, (f.dirIndex + 1L) * DIRENT_SIZE); // off
                mem.writeShort(at + 16, (short) DIRENT_SIZE); // reclen
                mem.writeByte(at + 18, (byte) type);
                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                mem.write(at + 19, nameBytes, 0, Math.min(nameBytes.length, 255));
                mem.writeByte(at + 19 + Math.min(nameBytes.length, 255), (byte) 0);
                pos += DIRENT_SIZE;
                f.dirIndex++;
            }
            // the fd position doubles as the dirent cursor (see emscripten)
            f.position = f.dirIndex * (long) DIRENT_SIZE;
            return pos;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    public long syscallMkdirat(Instance inst, long dirfd, long pathPtr, long mode) {
        String path = calculateAt(dirfd, readCString(inst, pathPtr), false);
        File file = path == null ? null : resolve(path);
        if (file == null) {
            trace("mkdirat", "path=" + path, -Errno.ENOENT);
            return Errno.ret(Errno.ENOENT);
        }
        if (file.exists()) {
            trace("mkdirat", path, -Errno.EEXIST);
            return Errno.ret(Errno.EEXIST);
        }
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            trace("mkdirat", path + " (no parent)", -Errno.ENOENT);
            return Errno.ret(Errno.ENOENT);
        }
        long r = file.mkdir() ? 0 : -Errno.EIO;
        trace("mkdirat", path, r);
        return r;
    }

    public long syscallUnlinkat(Instance inst, long dirfd, long pathPtr, long flags) {
        String path = calculateAt(dirfd, readCString(inst, pathPtr), false);
        File file = path == null ? null : resolve(path);
        if (file == null || !file.exists()) {
            return Errno.ret(Errno.ENOENT);
        }
        if ((flags & 0x200) != 0) { // AT_REMOVEDIR
            if (!file.isDirectory()) {
                return Errno.ret(Errno.ENOTDIR);
            }
            return file.delete() ? 0 : Errno.ret(Errno.ENOTEMPTY);
        }
        if (file.isDirectory()) {
            return Errno.ret(Errno.EISDIR);
        }
        return file.delete() ? 0 : Errno.ret(Errno.EIO);
    }

    public long syscallRmdir(Instance inst, long pathPtr) {
        return syscallUnlinkat(inst, AT_FDCWD, pathPtr, 0x200);
    }

    public long syscallRenameat(Instance inst, long oldDirfd, long oldPathPtr, long newDirfd,
            long newPathPtr) {
        String oldPath = calculateAt(oldDirfd, readCString(inst, oldPathPtr), false);
        String newPath = calculateAt(newDirfd, readCString(inst, newPathPtr), false);
        File from = oldPath == null ? null : resolve(oldPath);
        File to = newPath == null ? null : resolve(newPath);
        if (from == null || !from.exists() || to == null) {
            return Errno.ret(Errno.ENOENT);
        }
        if (from.equals(to)) {
            return 0;
        }
        File parent = to.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return Errno.ret(Errno.ENOENT);
        }
        return from.renameTo(to) ? 0 : Errno.ret(Errno.EIO);
    }

    public long syscallGetcwd(Instance inst, long buf, long size) {
        byte[] cwdBytes = cwd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int needed = cwdBytes.length + 1;
        if (size < needed) {
            return Errno.ret(Errno.ERANGE);
        }
        Memory mem = inst.memory();
        mem.write((int) buf, cwdBytes, 0, cwdBytes.length);
        mem.writeByte((int) buf + cwdBytes.length, (byte) 0);
        return cwdBytes.length;
    }

    // ------------------------------------------------------------------
    // truncate / chmod / misc
    // ------------------------------------------------------------------

    public long syscallFtruncate64(long fd, long lenLow, long lenHigh) {
        try {
            Fd f = getFd(fd);
            if (f.raf == null || !f.writable) {
                return Errno.ret(Errno.EINVAL);
            }
            f.raf.setLength(i53(lenLow, lenHigh));
            return 0;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        } catch (IOException e) {
            return Errno.ret(Errno.EIO);
        }
    }

    public long syscallTruncate64(Instance inst, long pathPtr, long lenLow, long lenHigh) {
        String path = readCString(inst, pathPtr);
        File file = path == null ? null : resolve(path);
        if (file == null || !file.exists()) {
            return Errno.ret(Errno.ENOENT);
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            try {
                raf.setLength(i53(lenLow, lenHigh));
            } finally {
                raf.close();
            }
            return 0;
        } catch (IOException e) {
            return Errno.ret(Errno.EIO);
        }
    }

    public long syscallChmod(Instance inst, long pathPtr, long mode) {
        String path = readCString(inst, pathPtr);
        File file = path == null ? null : resolve(path);
        return file != null && file.exists() ? 0 : Errno.ret(Errno.ENOENT);
    }

    public long syscallFchmod(long fd, long mode) {
        try {
            getFd(fd);
            return 0;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    public long syscallFchmodat2(Instance inst, long dirfd, long pathPtr, long mode, long flags) {
        return syscallChmod(inst, pathPtr, mode);
    }

    public long syscallSymlink(Instance inst, long targetPtr, long linkPtr) {
        return Errno.ret(Errno.EPERM);
    }

    public long syscallReadlinkat(Instance inst, long dirfd, long pathPtr, long buf, long bufsize) {
        if (bufsize <= 0) {
            return Errno.ret(Errno.EINVAL);
        }
        return Errno.ret(Errno.EINVAL); // no symlinks in this fs
    }

    public long syscallFcntl64(long fd, long cmd, long varargs) {
        try {
            Fd f = getFd(fd);
            if (cmd == 3) { // F_GETFL
                return (f.writable ? 1 : 0) | (f.append ? 0x400 : 0);
            }
            return 0; // locks (F_SETLK & co): pretend success, like a browser
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    public long syscallIoctl(long fd, long op, long varargs) {
        try {
            getFd(fd);
            return Errno.ret(Errno.ENOTTY);
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    public long syscallFdatasync(long fd) {
        // no-op, exactly like emscripten's MEMFS in the browser build: file
        // data stays in the kernel page cache (survives process death); a
        // real fsync here costs seconds of flash latency on every commit
        try {
            getFd(fd);
            return 0;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        }
    }

    // ------------------------------------------------------------------
    // WASI leftovers
    // ------------------------------------------------------------------

    public long wasiFdFdstatGet(Instance inst, long fd, long pbuf) {
        try {
            Fd f = getFd(fd);
            long type = f.stdio ? WASI_FILETYPE_CHARACTER_DEVICE
                    : f.file != null && f.file.isDirectory() ? WASI_FILETYPE_DIRECTORY
                            : WASI_FILETYPE_REGULAR_FILE;
            Memory mem = inst.memory();
            mem.writeByte((int) pbuf, (byte) type);
            mem.writeShort((int) pbuf + 2, (short) 0);
            mem.writeLong((int) pbuf + 8, 0);
            mem.writeLong((int) pbuf + 16, 0);
            return Errno.SUCCESS;
        } catch (VfsError e) {
            return e.errno;
        }
    }

    public long wasiEnvironSizesGet(Instance inst, long pcount, long psize) {
        if (pcount != 0) {
            inst.memory().writeI32((int) pcount, 0);
        }
        if (psize != 0) {
            inst.memory().writeI32((int) psize, 0);
        }
        return Errno.SUCCESS;
    }

    public long wasiEnvironGet(Instance inst, long environ, long environBuf) {
        return Errno.SUCCESS;
    }

    /** msync-like write-back for munmap of writable mappings. */
    public long msyncToFile(long fd, long addr, long len, Instance inst, long fileOffset) {
        try {
            Fd f = getFd(fd);
            if (f.raf == null) {
                return Errno.ret(Errno.EBADF);
            }
            byte[] data = inst.memory().readBytes((int) addr, (int) len);
            f.raf.seek(fileOffset);
            f.raf.write(data);
            return 0;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        } catch (IOException e) {
            return Errno.ret(Errno.EIO);
        }
    }

    public long readFileSlice(long fd, long fileOffset, long len, Instance inst, long addr) {
        try {
            Fd f = getFd(fd);
            if (f.raf == null) {
                return Errno.ret(Errno.EBADF);
            }
            byte[] data = new byte[(int) len];
            f.raf.seek(fileOffset);
            int n = f.raf.read(data);
            if (n < 0) {
                n = 0;
            }
            inst.memory().write((int) addr, data, 0, n);
            return 0;
        } catch (VfsError e) {
            return Errno.ret(e.errno);
        } catch (IOException e) {
            return Errno.ret(Errno.EIO);
        }
    }

    /** Opens a file read-only and returns its fd, for mmap bookkeeping. -1 on error. */
    public long openReadonlyForMmap(String path) {
        File file = resolve(path);
        if (file == null || !file.isFile()) {
            return -1;
        }
        try {
            Fd fd = new Fd();
            fd.file = file;
            fd.readable = true;
            fd.raf = new RandomAccessFile(file, "r");
            int fdNum = allocFd();
            fds.put(fdNum, fd);
            return fdNum;
        } catch (IOException e) {
            return -1;
        }
    }

    /** True if the fd refers to an open regular file (for mmap/munmap). */
    public boolean isOpenFileFd(long fd) {
        Fd f = fds.get((int) fd);
        return f != null && !f.stdio && f.raf != null;
    }

    public void closeAll() {
        for (Fd f : fds.values()) {
            if (f.raf != null) {
                try {
                    f.raf.close();
                } catch (IOException ignored) {
                }
            }
        }
        fds.clear();
    }
}
