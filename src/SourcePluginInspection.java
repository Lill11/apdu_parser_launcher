import java.util.List;

public record SourcePluginInspection(
        boolean success,
        ParserValidationStatus status,
        String message,
        SourcePluginSpec spec,
        List<String> diagnostics
) {
    public SourcePluginInspection {
        status = status == null ? ParserValidationStatus.INVALID_SOURCE : status;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
