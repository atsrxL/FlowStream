import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** PARSE ONLY, not Android compilation or API/type verification. Requires a full JDK. */
public class JavaSyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required");
        List<Path> sources = new ArrayList<>();
        for (String root : args) try (Stream<Path> stream = Files.walk(Path.of(root))) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "17"), null, files.getJavaFileObjectsFromPaths(sources));
            task.parse();
            boolean failed = false;
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) if (d.getKind() == Diagnostic.Kind.ERROR) { System.err.println(d); failed = true; }
            if (failed) System.exit(1);
        }
        System.out.println("PASS Java syntax parse: " + sources.size() + " source files.");
        System.out.println("NOT an Android build: external types, APIs, resources and device behavior were not checked here.");
    }
}
