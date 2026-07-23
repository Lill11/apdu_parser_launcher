import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record LegacyJavaExtractorInstallRequest(
        Path sourceFile,
        String parserName,
        String parserId,
        String version,
        List<String> supportedExtensions,
        LegacyCommandPattern commandPattern,
        String outputFileName,
        Path sampleLog
) {
    public LegacyJavaExtractorInstallRequest {
        parserName = parserName == null ? "" : parserName.trim();
        parserId = parserId == null ? "" : parserId.trim();
        version = version == null || version.isBlank() ? "1.0.0" : version.trim();
        supportedExtensions = normalizeExtensions(supportedExtensions);
        commandPattern = commandPattern == null ? LegacyCommandPattern.INPUT_FILE_OUTPUT_FILE : commandPattern;
        outputFileName = outputFileName == null || outputFileName.isBlank() ? "apdus.txt" : outputFileName.trim();
    }

    private static List<String> normalizeExtensions(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String extension = value.trim().toLowerCase(Locale.ROOT);
            if (!extension.startsWith(".")) {
                extension = "." + extension;
            }
            normalized.add(extension);
        }
        return List.copyOf(normalized);
    }
}

