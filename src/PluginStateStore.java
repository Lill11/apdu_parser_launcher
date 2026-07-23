import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginStateStore {

    private final Path pluginsRoot;

    public PluginStateStore() {
        this(AppEnvironment.pluginsDir());
    }

    public PluginStateStore(Path pluginsRoot) {
        this.pluginsRoot = pluginsRoot.toAbsolutePath().normalize();
    }

    public void ensureLayout() throws IOException {
        Files.createDirectories(pluginsInstalledDir());
        Files.createDirectories(pluginsRoot);
    }

    public Map<String, Boolean> loadBuiltInStates() throws IOException {
        ensureLayout();
        Path path = builtInParserStatePath();
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Map<String, Boolean> states = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(json);
        while (matcher.find()) {
            states.put(matcher.group(1), Boolean.parseBoolean(matcher.group(2)));
        }
        return states;
    }

    public void saveBuiltInStates(Map<String, Boolean> states) throws IOException {
        ensureLayout();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int index = 0;
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            sb.append("  \"").append(ApduParserProcessor.escapeJson(entry.getKey())).append("\": ").append(entry.getValue());
            sb.append(index++ == states.size() - 1 ? "\n" : ",\n");
        }
        sb.append("}\n");
        Files.writeString(builtInParserStatePath(), sb.toString(), StandardCharsets.UTF_8);
    }

    public List<ParserInstallMetadata> loadInstalledMetadata() throws IOException {
        ensureLayout();
        List<ParserInstallMetadata> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsInstalledDir())) {
            for (Path directory : stream) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                items.add(loadMetadata(directory));
            }
        }
        items.sort((left, right) -> left.parserId().compareToIgnoreCase(right.parserId()));
        return items;
    }

    public ParserInstallMetadata loadMetadata(Path installDirectory) throws IOException {
        ensureLayout();
        Path metadataPath = installDirectory.resolve("metadata.json");
        Path pluginJar = installDirectory.resolve("plugin.jar");
        if (!Files.exists(metadataPath)) {
            String parserId = installDirectory.getFileName().toString();
            return new ParserInstallMetadata(
                    parserId,
                    parserId,
                    "",
                    0,
                    "",
                    List.of(),
                    ParserSourceType.PLUGIN_JAR,
                    false,
                    true,
                    ParserValidationStatus.INVALID_METADATA,
                    "metadata.json is missing.",
                    installDirectory,
                    pluginJar,
                    installDirectory.resolve("source").resolve(parserId + ".java"),
                    "",
                    installDirectory.resolve("compile.log"),
                    "",
                    "",
                    "",
                    null,
                    "",
                    "",
                    null,
                    "",
                    "",
                    "",
                    Instant.now(),
                    Instant.now()
            );
        }

        String json = Files.readString(metadataPath, StandardCharsets.UTF_8);
        return new ParserInstallMetadata(
                readJsonString(json, "parserId", installDirectory.getFileName().toString()),
                readJsonString(json, "name", installDirectory.getFileName().toString()),
                readJsonString(json, "version", ""),
                readJsonInt(json, "pluginApiVersion", 0),
                readJsonString(json, "implementationClass", ""),
                readJsonArray(json, "supportedExtensions"),
                ParserSourceType.valueOf(readJsonString(json, "sourceType", ParserSourceType.PLUGIN_JAR.name()).toUpperCase(Locale.ROOT)),
                readJsonBoolean(json, "builtIn", false),
                readJsonBoolean(json, "enabled", true),
                ParserValidationStatus.valueOf(readJsonString(json, "validationStatus", ParserValidationStatus.INVALID_PLUGIN.name()).toUpperCase(Locale.ROOT)),
                readJsonString(json, "validationMessage", ""),
                installDirectory,
                pluginJar,
                readOptionalPath(json, "preservedSourceFile"),
                readJsonString(json, "originalSourcePath", ""),
                readOptionalPath(json, "compileLogPath"),
                readJsonString(json, "legacyMainClass", ""),
                readJsonString(json, "legacyCommandPattern", ""),
                readJsonString(json, "legacyOutputFileName", ""),
                parseInstant(readJsonString(json, "lastCompiledAt", "")),
                readJsonString(json, "lastCompilationStatus", ""),
                readJsonString(json, "lastCompilationMessage", ""),
                parseInstant(readJsonString(json, "lastTestedAt", "")),
                readJsonString(json, "lastTestStatus", ""),
                readJsonString(json, "lastTestMessage", ""),
                readJsonString(json, "lastTestStderr", ""),
                parseInstant(readJsonString(json, "installedAt", "")),
                parseInstant(readJsonString(json, "lastValidatedAt", ""))
        );
    }

    public void saveMetadata(ParserInstallMetadata metadata) throws IOException {
        ensureLayout();
        Path directory = metadata.installDirectory();
        Files.createDirectories(directory);
        Path metadataPath = directory.resolve("metadata.json");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"parserId\": \"").append(ApduParserProcessor.escapeJson(metadata.parserId())).append("\",\n");
        sb.append("  \"name\": \"").append(ApduParserProcessor.escapeJson(metadata.name())).append("\",\n");
        sb.append("  \"version\": \"").append(ApduParserProcessor.escapeJson(metadata.version())).append("\",\n");
        sb.append("  \"pluginApiVersion\": ").append(metadata.pluginApiVersion()).append(",\n");
        sb.append("  \"implementationClass\": \"").append(ApduParserProcessor.escapeJson(metadata.implementationClass())).append("\",\n");
        sb.append("  \"sourceType\": \"").append(metadata.sourceType().name()).append("\",\n");
        sb.append("  \"builtIn\": ").append(metadata.builtIn()).append(",\n");
        sb.append("  \"enabled\": ").append(metadata.enabled()).append(",\n");
        sb.append("  \"validationStatus\": \"").append(metadata.validationStatus().name()).append("\",\n");
        sb.append("  \"validationMessage\": \"").append(ApduParserProcessor.escapeJson(metadata.validationMessage())).append("\",\n");
        sb.append("  \"preservedSourceFile\": \"").append(metadata.preservedSourceFile() == null ? "" : ApduParserProcessor.escapeJson(metadata.preservedSourceFile().toAbsolutePath().toString())).append("\",\n");
        sb.append("  \"originalSourcePath\": \"").append(ApduParserProcessor.escapeJson(metadata.originalSourcePath())).append("\",\n");
        sb.append("  \"compileLogPath\": \"").append(metadata.compileLogPath() == null ? "" : ApduParserProcessor.escapeJson(metadata.compileLogPath().toAbsolutePath().toString())).append("\",\n");
        sb.append("  \"legacyMainClass\": \"").append(ApduParserProcessor.escapeJson(metadata.legacyMainClass())).append("\",\n");
        sb.append("  \"legacyCommandPattern\": \"").append(ApduParserProcessor.escapeJson(metadata.legacyCommandPattern())).append("\",\n");
        sb.append("  \"legacyOutputFileName\": \"").append(ApduParserProcessor.escapeJson(metadata.legacyOutputFileName())).append("\",\n");
        sb.append("  \"lastCompiledAt\": \"").append(metadata.lastCompiledAt() == null ? "" : metadata.lastCompiledAt().toString()).append("\",\n");
        sb.append("  \"lastCompilationStatus\": \"").append(ApduParserProcessor.escapeJson(metadata.lastCompilationStatus())).append("\",\n");
        sb.append("  \"lastCompilationMessage\": \"").append(ApduParserProcessor.escapeJson(metadata.lastCompilationMessage())).append("\",\n");
        sb.append("  \"lastTestedAt\": \"").append(metadata.lastTestedAt() == null ? "" : metadata.lastTestedAt().toString()).append("\",\n");
        sb.append("  \"lastTestStatus\": \"").append(ApduParserProcessor.escapeJson(metadata.lastTestStatus())).append("\",\n");
        sb.append("  \"lastTestMessage\": \"").append(ApduParserProcessor.escapeJson(metadata.lastTestMessage())).append("\",\n");
        sb.append("  \"lastTestStderr\": \"").append(ApduParserProcessor.escapeJson(metadata.lastTestStderr())).append("\",\n");
        sb.append("  \"installedAt\": \"").append(metadata.installedAt() == null ? "" : metadata.installedAt().toString()).append("\",\n");
        sb.append("  \"lastValidatedAt\": \"").append(metadata.lastValidatedAt() == null ? "" : metadata.lastValidatedAt().toString()).append("\",\n");
        sb.append("  \"supportedExtensions\": [");
        for (int i = 0; i < metadata.supportedExtensions().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(ApduParserProcessor.escapeJson(metadata.supportedExtensions().get(i))).append("\"");
        }
        sb.append("]\n");
        sb.append("}\n");
        Files.writeString(metadataPath, sb.toString(), StandardCharsets.UTF_8);
    }

    public Path installDirectory(String parserId) {
        return pluginsInstalledDir().resolve(parserId);
    }

    public void removeInstalledPlugin(String parserId) throws IOException {
        Path directory = installDirectory(parserId);
        ApduParserProcessor.deleteDirectoryIfExists(directory);
    }

    public Path pluginsInstalledDir() {
        return pluginsRoot.resolve("installed");
    }

    public Path builtInParserStatePath() {
        return pluginsRoot.resolve("builtins.json");
    }

    static String readJsonString(String json, String field, String fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find()
                ? matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                : fallback;
    }

    private static Path readOptionalPath(String json, String field) {
        String value = readJsonString(json, field, "");
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static int readJsonInt(String json, String field, int fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static boolean readJsonBoolean(String json, String field, boolean fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }

    private static List<String> readJsonArray(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return List.of();
        }
        Matcher itemMatcher = Pattern.compile("\"([^\"]*)\"").matcher(matcher.group(1));
        List<String> items = new ArrayList<>();
        while (itemMatcher.find()) {
            items.add(itemMatcher.group(1));
        }
        return items;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignore) {
            return null;
        }
    }
}
