import java.io.File;
import java.io.FileOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.internal.Compiler;
import run.endive.wasm.Parser;

/**
 * Build-time helper: compiles rime.wasm into Java bytecode classes packaged
 * as a jar (app/libs/rime-machine.jar), so the app runs the compiled machine
 * instead of endive's interpreter (the interpreter needs 1.5-7s per keypress
 * on Android).
 *
 * Usage: java -cp <endive jars> GenerateRimeMachine.java <rime.wasm> <out.jar> <className>
 * Run via scripts/build-rime-machine.sh; the generated jar is committed.
 */
public class GenerateRimeMachine {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: GenerateRimeMachine <rime.wasm> <out.jar> <className>");
        }
        long t0 = System.currentTimeMillis();
        var module = Parser.parse(new File(args[0]));
        var result = Compiler.builder(module)
                .withClassName(args[2])
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build()
                .compile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(args[1]))) {
            for (var e : result.classBytes().entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey().replace('.', '/') + ".class"));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        System.out.println("compiled " + result.classBytes().size() + " classes ("
                + result.interpretedFunctions().size() + " interpreted) in "
                + (System.currentTimeMillis() - t0) + " ms -> " + args[1]);
    }
}
