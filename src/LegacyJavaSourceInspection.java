import java.util.List;

public record LegacyJavaSourceInspection(
        boolean success,
        ParserValidationStatus status,
        String message,
        LegacyJavaSourceSpec spec,
        List<String> diagnostics
) {
    public LegacyJavaSourceInspection {
        status = status == null ? ParserValidationStatus.INVALID_SOURCE : status;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}

