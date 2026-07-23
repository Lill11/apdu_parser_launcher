import java.nio.file.Files;
import java.nio.file.Path;

public record CompilerResolution(
        boolean resolved,
        Path compilerPath,
        String source,
        String message
) {
    public CompilerResolution {
        source = source == null ? "" : source;
        message = message == null ? "" : message;
    }

    public static CompilerResolution resolve() {
        Path bundled = AppEnvironment.parserRuntimeJavacPath();
        if (Files.exists(bundled)) {
            return fromCandidate(bundled, "bundled-runtime");
        }

        String property = System.getProperty("apdu.parser.javac");
        if (property != null && !property.isBlank()) {
            return fromCandidate(Path.of(property), "system-property");
        }

        String env = System.getenv("APDU_PARSER_JAVAC");
        if (env != null && !env.isBlank()) {
            return fromCandidate(Path.of(env), "environment");
        }

        return new CompilerResolution(false, null, "", "No Java compiler is configured. Set APDU_PARSER_JAVAC or bundle runtime/bin/javac.exe.");
    }

    private static CompilerResolution fromCandidate(Path path, String source) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || Files.isDirectory(normalized)) {
            return new CompilerResolution(false, normalized, source, "Configured Java compiler was not found: " + normalized);
        }
        return new CompilerResolution(true, normalized, source, "Compiler resolved from " + source + ".");
    }
}
