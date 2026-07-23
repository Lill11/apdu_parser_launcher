import java.nio.file.Path;
import java.util.List;

public record LegacyExtractorExecutionResult(
        boolean success,
        int exitCode,
        String message,
        String stdout,
        String stderr,
        Path generatedOutputPath,
        List<String> apdus,
        List<String> warnings
) {
    public LegacyExtractorExecutionResult {
        message = message == null ? "" : message;
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        apdus = apdus == null ? List.of() : List.copyOf(apdus);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

