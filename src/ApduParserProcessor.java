import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ApduParserProcessor {

    public static final int SCHEMA_VERSION = 1;
    public static final String PARSER_VERSION = "1.0.0";

    private final LogParserRegistry registry;

    public ApduParserProcessor() {
        this(new LogParserRegistry());
    }

    ApduParserProcessor(LogParserRegistry registry) {
        this.registry = registry;
    }

    public ProcessingResult process(Path inputFile, boolean detectOnly, Path artifactsDir) throws IOException {
        if (inputFile == null || !Files.isRegularFile(inputFile)) {
            return ProcessingResult.invalidArguments(inputFile, "Input file does not exist or is not a regular file.");
        }
        if (Files.size(inputFile) == 0) {
            return ProcessingResult.malformedInput(inputFile, "Input file is empty.");
        }

        LogParserRegistry.DetectionResult detection = registry.detect(inputFile);
        if (detection.ambiguous()) {
            ProcessingResult result = ProcessingResult.unsupported(inputFile, detection, "Parser conflict: " + detection.reason());
            if (artifactsDir != null) {
                writeArtifacts(result, artifactsDir);
            }
            return result;
        }
        if (!detection.supported()) {
            ProcessingResult result = ProcessingResult.unsupported(inputFile, detection, "No internal parser matched this file.");
            if (artifactsDir != null) {
                writeArtifacts(result, artifactsDir);
            }
            return result;
        }

        try {
            if (detectOnly) {
                ProcessingResult result = ProcessingResult.detectOnly(inputFile, detection);
                if (artifactsDir != null) {
                    writeArtifacts(result, artifactsDir);
                }
                return result;
            }

            LogParser.ParseResult parseResult = detection.parser().parse(inputFile);
            List<String> rawApdus = parseResult.apdus();
            List<ApduOutputAnalyzer.AnalysisItem> analysisItems = ApduOutputAnalyzer.analyzeEntries(
                    inputFile,
                    rawApdus,
                    parseResult.events()
            );
            String analysisText = ApduOutputAnalyzer.renderEnhancedOutput(analysisItems, ApduOutputAnalyzer.FilterMode.ALL);
            AppletExtractor.ExtractionResult appletResult = AppletExtractor.extract(rawApdus);

            ProcessingResult result = ProcessingResult.completed(
                    inputFile,
                    detection,
                    rawApdus,
                    parseResult.warnings(),
                    analysisItems,
                    analysisText,
                    appletResult
            );
            if (artifactsDir != null) {
                writeArtifacts(result, artifactsDir);
            }
            return result;
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception ex) {
            return ProcessingResult.parserFailure(inputFile, detection, ex);
        }
    }

    void writeArtifacts(ProcessingResult result, Path artifactsDir) throws IOException {
        Files.createDirectories(artifactsDir);
        cleanupResultFolder(artifactsDir);

        if (!result.analysisItems().isEmpty()) {
            Files.write(result.rawOutputPath(artifactsDir), result.normalizedOutputLines(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        if (!result.analysisText().isBlank()) {
            Files.writeString(result.analysisOutputPath(artifactsDir), result.analysisText(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        if (!result.errorText().isBlank()) {
            Files.writeString(result.errorsOutputPath(artifactsDir), result.errorText().strip() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        deleteDirectoryIfExists(result.appletsDir(artifactsDir));
        if (result.appletResult().status() == AppletExtractor.ExtractionResult.Status.EXTRACTED) {
            Files.createDirectories(result.appletsDir(artifactsDir));
            Files.write(result.appletsDir(artifactsDir).resolve("all_clean.lop"), result.appletResult().allClean(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            for (Map.Entry<String, List<String>> entry : result.appletResult().applets().entrySet()) {
                Files.write(result.appletsDir(artifactsDir).resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }

        Files.writeString(result.legacyResultJsonPath(artifactsDir), result.toLegacyResultJson(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void cleanupResultFolder(Path resultDir) throws IOException {
        if (!Files.exists(resultDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(resultDir)) {
            for (Path child : stream) {
                deleteDirectoryIfExists(child);
                Files.deleteIfExists(child);
            }
        }
    }

    static void deleteDirectoryIfExists(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    deleteDirectoryIfExists(child);
                    Files.deleteIfExists(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    public record ProcessingResult(
            Path inputFile,
            ExitCode exitCode,
            boolean success,
            String status,
            String message,
            String parserId,
            String detectedFormat,
            List<String> rawApdus,
            List<String> warnings,
            List<ApduOutputAnalyzer.AnalysisItem> analysisItems,
            String analysisText,
            AppletExtractor.ExtractionResult appletResult,
            String errorText,
            Instant generatedAt
    ) {
        static ProcessingResult completed(
                Path inputFile,
                LogParserRegistry.DetectionResult detection,
                List<String> rawApdus,
                List<String> warnings,
                List<ApduOutputAnalyzer.AnalysisItem> analysisItems,
                String analysisText,
                AppletExtractor.ExtractionResult appletResult
        ) {
            return new ProcessingResult(
                    inputFile,
                    ExitCode.SUCCESS,
                    true,
                    "completed",
                    rawApdus.isEmpty() ? "No APDUs extracted" : "Completed",
                    detection.parserId(),
                    detection.displayName(),
                    List.copyOf(rawApdus),
                    List.copyOf(warnings),
                    List.copyOf(analysisItems),
                    analysisText == null ? "" : analysisText,
                    appletResult,
                    warnings.isEmpty() ? "" : String.join(System.lineSeparator(), warnings),
                    Instant.now()
            );
        }

        static ProcessingResult detectOnly(Path inputFile, LogParserRegistry.DetectionResult detection) {
            return new ProcessingResult(
                    inputFile,
                    ExitCode.SUCCESS,
                    true,
                    "detected",
                    "Detected " + detection.displayName() + " (detect-only)",
                    detection.parserId(),
                    detection.displayName(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    AppletExtractor.ExtractionResult.notApplicable("Detect-only mode."),
                    "",
                    Instant.now()
            );
        }

        static ProcessingResult unsupported(Path inputFile, LogParserRegistry.DetectionResult detection, String details) {
            return new ProcessingResult(
                    inputFile,
                    ExitCode.UNSUPPORTED_FORMAT,
                    false,
                    detection != null && detection.ambiguous() ? "parser_conflict" : "unsupported",
                    detection != null && detection.ambiguous() ? "Parser detection conflict" : "Unsupported log format",
                    "",
                    detection.displayName(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    AppletExtractor.ExtractionResult.notApplicable(details),
                    details,
                    Instant.now()
            );
        }

        static ProcessingResult malformedInput(Path inputFile, String message) {
            return new ProcessingResult(
                    inputFile,
                    ExitCode.MALFORMED_INPUT,
                    false,
                    "malformed_input",
                    message,
                    "",
                    "Unsupported",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    AppletExtractor.ExtractionResult.notApplicable(message),
                    message,
                    Instant.now()
            );
        }

        static ProcessingResult invalidArguments(Path inputFile, String message) {
            return new ProcessingResult(
                    inputFile,
                    ExitCode.INVALID_ARGUMENTS,
                    false,
                    "invalid_arguments",
                    message,
                    "",
                    "Unsupported",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    AppletExtractor.ExtractionResult.notApplicable(message),
                    message,
                    Instant.now()
            );
        }

        static ProcessingResult parserFailure(Path inputFile, LogParserRegistry.DetectionResult detection, Exception ex) {
            String details = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return new ProcessingResult(
                    inputFile,
                    ExitCode.PARSER_FAILURE,
                    false,
                    "parser_failure",
                    "Parser execution failed",
                    detection == null ? "" : detection.parserId(),
                    detection == null ? "Unsupported" : detection.displayName(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    AppletExtractor.ExtractionResult.notApplicable("Parser failed."),
                    details,
                    Instant.now()
            );
        }

        int completedApduCount() {
            int count = 0;
            for (ApduOutputAnalyzer.AnalysisItem item : analysisItems) {
                if (!item.isResetMarker()) {
                    count++;
                }
            }
            return count;
        }

        int analysisEventCount() {
            return analysisItems.size();
        }

        int appletCount() {
            return appletResult.applets().size();
        }

        List<String> normalizedOutputLines() {
            List<String> lines = new ArrayList<>();
            for (ApduOutputAnalyzer.AnalysisItem item : analysisItems) {
                lines.add(item.isResetMarker() ? "RESET" : item.commandApdu);
            }
            return List.copyOf(lines);
        }

        Path rawOutputPath(Path artifactsDir) {
            return artifactsDir.resolve("apdus.txt");
        }

        Path analysisOutputPath(Path artifactsDir) {
            return artifactsDir.resolve("analysis.txt");
        }

        Path errorsOutputPath(Path artifactsDir) {
            return artifactsDir.resolve("errors.txt");
        }

        Path legacyResultJsonPath(Path artifactsDir) {
            return artifactsDir.resolve("result.json");
        }

        Path appletsDir(Path artifactsDir) {
            return artifactsDir.resolve("applets");
        }

        String toLegacyResultJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            appendJsonField(sb, "fileName", inputFile == null ? "" : inputFile.getFileName().toString(), true);
            appendJsonField(sb, "parserId", parserId, true);
            appendJsonField(sb, "detectedFormat", detectedFormat, true);
            appendJsonField(sb, "status", legacyStatus(), true);
            appendJsonField(sb, "message", message, true);
            appendJsonField(sb, "appletStatus", appletResult.status().name(), true);
            appendJsonField(sb, "appletMessage", appletResult.message(), true);
            appendJsonField(sb, "generatedAt", generatedAt.toString(), true);
            appendJsonNumberField(sb, "apduCount", rawApdus.size(), true);
            appendJsonNumberField(sb, "warningCount", warnings.size(), false);
            sb.append("}\n");
            return sb.toString();
        }

        String legacyStatus() {
            return switch (exitCode) {
                case SUCCESS -> "COMPLETED";
                case UNSUPPORTED_FORMAT -> "UNSUPPORTED";
                case MALFORMED_INPUT, PARSER_FAILURE, OUTPUT_WRITE_FAILURE -> "FAILED";
                case INVALID_ARGUMENTS -> "FAILED";
            };
        }
    }

    public enum ExitCode {
        SUCCESS(0),
        UNSUPPORTED_FORMAT(1),
        MALFORMED_INPUT(2),
        PARSER_FAILURE(3),
        INVALID_ARGUMENTS(4),
        OUTPUT_WRITE_FAILURE(5);

        private final int code;

        ExitCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    static String toStructuredJson(ProcessingResult result, Path jsonOutputPath, Path artifactsDir, String stderrText) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJsonNumberField(sb, "schemaVersion", SCHEMA_VERSION, true);
        appendJsonField(sb, "parserVersion", PARSER_VERSION, true);
        appendJsonBooleanField(sb, "success", result.success(), true);
        appendJsonField(sb, "status", result.status(), true);
        appendJsonField(sb, "message", result.message(), true);
        appendJsonField(sb, "generatedAt", result.generatedAt().toString(), true);
        appendJsonField(sb, "sourceFile", result.inputFile() == null ? "" : result.inputFile().toAbsolutePath().toString(), true);
        appendJsonField(sb, "sourceFileName", result.inputFile() == null ? "" : result.inputFile().getFileName().toString(), true);

        sb.append("  \"detectedParser\": {\n");
        appendJsonField(sb, "id", result.parserId(), true, 4);
        appendJsonField(sb, "displayName", result.detectedFormat(), true, 4);
        appendJsonBooleanField(sb, "supported", !result.parserId().isBlank(), false, 4);
        sb.append("  },\n");

        sb.append("  \"summary\": {\n");
        appendJsonNumberField(sb, "apduCount", result.rawApdus().size(), true, 4);
        appendJsonNumberField(sb, "analysisEventCount", result.analysisEventCount(), true, 4);
        appendJsonNumberField(sb, "appletCount", result.appletCount(), true, 4);
        appendJsonNumberField(sb, "warningCount", result.warnings().size(), true, 4);
        appendJsonNumberField(sb, "exitCode", result.exitCode().code(), false, 4);
        sb.append("  },\n");

        sb.append("  \"apdus\": [\n");
        List<ApduOutputAnalyzer.AnalysisItem> commandItems = new ArrayList<>();
        for (ApduOutputAnalyzer.AnalysisItem item : result.analysisItems()) {
            if (!item.isResetMarker()) {
                commandItems.add(item);
            }
        }
        for (int i = 0; i < commandItems.size(); i++) {
            ApduOutputAnalyzer.AnalysisItem item = commandItems.get(i);
            sb.append("    {\n");
            appendJsonNumberField(sb, "index", item.sequenceIndex, true, 6);
            appendJsonNumberField(sb, "eventSequence", item.eventSequence, true, 6);
            appendJsonField(sb, "command", item.commandApdu, true, 6);
            appendJsonField(sb, "response", item.responseApdu, true, 6);
            appendJsonField(sb, "commandName", item.commandName, true, 6);
            appendJsonField(sb, "headline", item.headline, true, 6);
            appendJsonField(sb, "statusWord", item.statusWord, true, 6);
            appendJsonField(sb, "severity", item.severity, true, 6);
            appendJsonField(sb, "tag", item.tagLabel, true, 6);
            appendJsonNumberField(sb, "sourceLine", item.sourceLine, true, 6);
            sb.append("      \"filters\": [");
            List<String> filters = new ArrayList<>();
            if (item.es10) {
                filters.add("ES10");
            }
            if (item.fetchOrTerminalResponse) {
                filters.add("FETCH/TR");
            }
            if (item.isConfigureLsi() || "Manage LSI".equals(item.commandName)) {
                filters.add("LSI");
            }
            for (int f = 0; f < filters.size(); f++) {
                if (f > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(escapeJson(filters.get(f))).append("\"");
            }
            sb.append("],\n");
            appendJsonField(sb, "note", item.note, false, 6);
            sb.append(i == commandItems.size() - 1 ? "    }\n" : "    },\n");
        }
        sb.append("  ],\n");

        sb.append("  \"events\": [\n");
        for (int i = 0; i < result.analysisItems().size(); i++) {
            ApduOutputAnalyzer.AnalysisItem item = result.analysisItems().get(i);
            sb.append("    {\n");
            appendJsonNumberField(sb, "sequence", item.eventSequence, true, 6);
            appendJsonField(sb, "type", item.isResetMarker() ? "RESET" : "APDU", true, 6);
            if (item.isResetMarker()) {
                appendJsonField(sb, "resetType", item.resetType, true, 6);
                appendJsonField(sb, "label", "RESET", true, 6);
                appendJsonField(sb, "atr", item.atr, true, 6);
                appendJsonNumberField(sb, "sourceLine", item.sourceLine, false, 6);
            } else {
                appendJsonNumberField(sb, "apduIndex", item.sequenceIndex, true, 6);
                appendJsonField(sb, "command", item.commandApdu, true, 6);
                appendJsonField(sb, "response", item.responseApdu, true, 6);
                appendJsonField(sb, "commandName", item.commandName, true, 6);
                appendJsonField(sb, "headline", item.headline, true, 6);
                appendJsonField(sb, "statusWord", item.statusWord, true, 6);
                appendJsonField(sb, "severity", item.severity, true, 6);
                appendJsonField(sb, "tag", item.tagLabel, true, 6);
                sb.append("      \"filters\": [");
                List<String> filters = new ArrayList<>();
                if (item.es10) {
                    filters.add("ES10");
                }
                if (item.fetchOrTerminalResponse) {
                    filters.add("FETCH/TR");
                }
                if (item.isConfigureLsi() || "Manage LSI".equals(item.commandName)) {
                    filters.add("LSI");
                }
                for (int f = 0; f < filters.size(); f++) {
                    if (f > 0) {
                        sb.append(", ");
                    }
                    sb.append("\"").append(escapeJson(filters.get(f))).append("\"");
                }
                sb.append("],\n");
                appendJsonField(sb, "note", item.note, true, 6);
                appendJsonNumberField(sb, "sourceLine", item.sourceLine, false, 6);
            }
            sb.append(i == result.analysisItems().size() - 1 ? "    }\n" : "    },\n");
        }
        sb.append("  ],\n");

        sb.append("  \"analysis\": [\n");
        for (int i = 0; i < result.analysisItems().size(); i++) {
            ApduOutputAnalyzer.AnalysisItem item = result.analysisItems().get(i);
            sb.append("    {\n");
            appendJsonNumberField(sb, "index", item.sequenceIndex, true, 6);
            appendJsonNumberField(sb, "eventSequence", item.eventSequence, true, 6);
            appendJsonField(sb, "type", item.isResetMarker() ? "reset" : "apdu", true, 6);
            appendJsonField(sb, "title", item.headline, true, 6);
            appendJsonField(sb, "message", item.note, true, 6);
            appendJsonField(sb, "severity", item.severity, true, 6);
            appendJsonField(sb, "statusWord", item.statusWord, true, 6);
            appendJsonField(sb, "tag", item.tagLabel, true, 6);
            appendJsonField(sb, "resetType", item.resetType, true, 6);
            appendJsonField(sb, "atr", item.atr, true, 6);
            appendJsonNumberField(sb, "sourceLine", item.sourceLine, false, 6);
            sb.append(i == result.analysisItems().size() - 1 ? "    }\n" : "    },\n");
        }
        sb.append("  ],\n");

        sb.append("  \"applets\": {\n");
        appendJsonField(sb, "status", result.appletResult().status().name().toLowerCase(Locale.ROOT), true, 4);
        appendJsonField(sb, "message", result.appletResult().message(), true, 4);
        sb.append("    \"allClean\": [");
        for (int i = 0; i < result.appletResult().allClean().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(escapeJson(result.appletResult().allClean().get(i))).append("\"");
        }
        sb.append("],\n");
        sb.append("    \"files\": [\n");
        List<Map.Entry<String, List<String>>> appletEntries = new ArrayList<>(result.appletResult().applets().entrySet());
        for (int i = 0; i < appletEntries.size(); i++) {
            Map.Entry<String, List<String>> entry = appletEntries.get(i);
            sb.append("      {\n");
            appendJsonField(sb, "name", entry.getKey(), true, 8);
            sb.append("        \"lines\": [");
            List<String> lines = entry.getValue();
            for (int j = 0; j < lines.size(); j++) {
                if (j > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(escapeJson(lines.get(j))).append("\"");
            }
            sb.append("]\n");
            sb.append(i == appletEntries.size() - 1 ? "      }\n" : "      },\n");
        }
        sb.append("    ]\n");
        sb.append("  },\n");

        sb.append("  \"warnings\": [");
        for (int i = 0; i < result.warnings().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(escapeJson(result.warnings().get(i))).append("\"");
        }
        sb.append("],\n");

        sb.append("  \"errors\": [\n");
        if (!result.errorText().isBlank()) {
            sb.append("    {\n");
            appendJsonField(sb, "code", result.exitCode().name(), true, 6);
            appendJsonField(sb, "message", result.message(), true, 6);
            appendJsonField(sb, "details", result.errorText(), false, 6);
            sb.append("    }\n");
        }
        sb.append("  ],\n");

        sb.append("  \"outputFiles\": {\n");
        appendJsonField(sb, "json", jsonOutputPath == null ? "" : jsonOutputPath.toAbsolutePath().toString(), true, 4);
        appendJsonField(sb, "artifactsDir", artifactsDir == null ? "" : artifactsDir.toAbsolutePath().toString(), true, 4);
        appendJsonField(sb, "apduText", artifactsDir == null ? "" : result.rawOutputPath(artifactsDir).toAbsolutePath().toString(), true, 4);
        appendJsonField(sb, "analysisText", artifactsDir == null ? "" : result.analysisOutputPath(artifactsDir).toAbsolutePath().toString(), true, 4);
        appendJsonField(sb, "errorsText", artifactsDir == null ? "" : result.errorsOutputPath(artifactsDir).toAbsolutePath().toString(), true, 4);
        appendJsonField(sb, "legacyResultJson", artifactsDir == null ? "" : result.legacyResultJsonPath(artifactsDir).toAbsolutePath().toString(), true, 4);
        appendJsonField(sb, "stderrLog", stderrText == null ? "" : stderrText, false, 4);
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma) {
        appendJsonField(sb, key, value, trailingComma, 2);
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma, int indent) {
        sb.append(" ".repeat(indent))
                .append("\"").append(escapeJson(key)).append("\": \"").append(escapeJson(value)).append("\"")
                .append(trailingComma ? ",\n" : "\n");
    }

    private static void appendJsonNumberField(StringBuilder sb, String key, int value, boolean trailingComma) {
        appendJsonNumberField(sb, key, value, trailingComma, 2);
    }

    private static void appendJsonNumberField(StringBuilder sb, String key, int value, boolean trailingComma, int indent) {
        sb.append(" ".repeat(indent))
                .append("\"").append(escapeJson(key)).append("\": ").append(value)
                .append(trailingComma ? ",\n" : "\n");
    }

    private static void appendJsonBooleanField(StringBuilder sb, String key, boolean value, boolean trailingComma) {
        appendJsonBooleanField(sb, key, value, trailingComma, 2);
    }

    private static void appendJsonBooleanField(StringBuilder sb, String key, boolean value, boolean trailingComma, int indent) {
        sb.append(" ".repeat(indent))
                .append("\"").append(escapeJson(key)).append("\": ").append(value)
                .append(trailingComma ? ",\n" : "\n");
    }
}
