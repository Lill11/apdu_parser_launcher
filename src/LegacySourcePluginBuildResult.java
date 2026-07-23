import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record LegacySourcePluginBuildResult(
        boolean success,
        ParserValidationStatus status,
        String message,
        Path generatedJar,
        PluginValidationReport validationReport,
        LegacyJavaSourceSpec sourceSpec,
        CompilerResolution compilerResolution,
        Instant compiledAt,
        List<String> diagnostics,
        String compileLog
) {
    public LegacySourcePluginBuildResult {
        status = status == null ? ParserValidationStatus.COMPILATION_FAILED : status;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        compileLog = compileLog == null ? "" : compileLog;
    }
}
