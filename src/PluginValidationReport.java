import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record PluginValidationReport(
        boolean success,
        ParserValidationStatus status,
        String message,
        ParserRuntimeDescriptor descriptor,
        Path inspectedJar,
        Instant validatedAt,
        List<String> diagnostics
) {
    public PluginValidationReport {
        status = status == null ? ParserValidationStatus.INVALID_PLUGIN : status;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
