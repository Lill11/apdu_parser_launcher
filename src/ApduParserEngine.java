import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApduParserEngine {

    private static final int PREVIEW_LIMIT_FALLBACK = 24_000;

    private final Path dataRoot;
    private final Path configPath;
    private final Config config;
    private final LogParserRegistry registry;
    private final ApduParserProcessor processor;
    private final Path inputDir;
    private final Path outputDir;
    private final Path tempDir;
    private final Path logsDir;
    private final List<ImportedLog> importedLogs = new ArrayList<>();

    public ApduParserEngine() {
        this((String) null);
    }

    public ApduParserEngine(String configPath) {
        try {
            this.configPath = resolveConfigPath(configPath);
            this.dataRoot = resolveDataRoot(this.configPath);
            if (this.configPath.equals(AppEnvironment.configPath())) {
                AppEnvironment.ensureBaseLayout();
            }
            this.config = Config.load(this.configPath);
            this.registry = new LogParserRegistry();
            this.processor = new ApduParserProcessor(this.registry);
            this.inputDir = dataRoot.resolve(config.inputDir()).normalize();
            this.outputDir = dataRoot.resolve(config.outputDir()).normalize();
            this.tempDir = dataRoot.resolve(config.tempDir()).normalize();
            this.logsDir = dataRoot.resolve(config.logsDir()).normalize();
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);
            Files.createDirectories(tempDir);
            Files.createDirectories(logsDir);
            refreshImportedLogs();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to initialize engine", ex);
        }
    }

    public synchronized List<ImportedLog> getImportedLogs() {
        return List.copyOf(importedLogs);
    }

    public synchronized List<ImportedLog> refreshImportedLogs() throws IOException {
        importedLogs.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, path ->
                Files.isRegularFile(path) && !path.getFileName().toString().startsWith("."))) {
            for (Path file : stream) {
                importedLogs.add(loadImportedLog(file));
            }
        }
        importedLogs.sort(Comparator.comparing(log -> log.fileName().toLowerCase(Locale.ROOT)));
        return List.copyOf(importedLogs);
    }

    public synchronized List<ImportedLog> importFiles(List<Path> sourceFiles) throws IOException {
        List<ImportedLog> imported = new ArrayList<>();
        if (sourceFiles == null) {
            return imported;
        }

        for (Path source : sourceFiles) {
            if (source == null || !Files.isRegularFile(source)) {
                continue;
            }
            String fileName = source.getFileName().toString();
            Path destination = uniquePath(inputDir, fileName);
            Files.copy(source, destination);
            ImportedLog log = new ImportedLog(destination, computeResultDir(destination));
            importedLogs.add(log);
            imported.add(log);
        }
        importedLogs.sort(Comparator.comparing(log -> log.fileName().toLowerCase(Locale.ROOT)));
        return imported;
    }

    public synchronized boolean deleteImportedFile(Path importedFile) throws IOException {
        if (importedFile == null) {
            return false;
        }
        ImportedLog target = findImportedLog(importedFile);
        if (target == null) {
            return false;
        }
        Files.deleteIfExists(target.filePath());
        ApduParserProcessor.deleteDirectoryIfExists(target.resultDir());
        importedLogs.remove(target);
        return true;
    }

    public synchronized int clearImportedFiles() throws IOException {
        int deleted = 0;
        for (ImportedLog log : List.copyOf(importedLogs)) {
            if (deleteImportedFile(log.filePath())) {
                deleted++;
            }
        }
        return deleted;
    }

    public synchronized RunSummary analyzeAll(boolean detectOnly, CancellationToken token, ProgressListener progressListener, LogSink sink) {
        List<ImportedLog> snapshot = new ArrayList<>(importedLogs);
        RunSummary summary = new RunSummary();
        for (ImportedLog log : snapshot) {
            if (token != null && token.isCancellationRequested()) {
                markCancelled(log);
                summary.cancelled++;
                publish(log, progressListener);
                continue;
            }
            analyzeOne(log, detectOnly, progressListener, sink, summary);
        }
        return summary;
    }

    public RunSummary processAll(boolean detectOnly, LogSink sink) throws IOException {
        refreshImportedLogs();
        return analyzeAll(detectOnly, () -> false, null, sink);
    }

    public Path getInputDir() {
        return inputDir;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public Path getTempDir() {
        return tempDir;
    }

    public Path getLogsDir() {
        return logsDir;
    }

    public Path getDataRoot() {
        return dataRoot;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public Config getConfig() {
        return config;
    }

    public void saveConfig(Config updatedConfig) throws IOException {
        updatedConfig.save(configPath);
    }

    public void openDirectory(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        Files.createDirectories(directory);
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(directory.toFile());
        }
    }

    public String readFilePreview(Path file, int maxChars) throws IOException {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int limit = maxChars > 0 ? maxChars : PREVIEW_LIMIT_FALLBACK;
        return content.length() <= limit ? content : content.substring(0, limit) + System.lineSeparator() + "...";
    }

    public synchronized ImportedLog findLog(Path importedFile) {
        ImportedLog log = findImportedLog(importedFile);
        return log == null ? null : log.copy();
    }

    private void analyzeOne(ImportedLog log, boolean detectOnly, ProgressListener progressListener, LogSink sink, RunSummary summary) {
        log.status = Status.ANALYZING;
        log.message = "Analyzing";
        log.errorText = "";
        publish(log, progressListener);
        logTo(sink, "Analyzing " + log.fileName());

        try {
            ApduParserProcessor.ProcessingResult result = processor.process(log.filePath(), detectOnly, log.resultDir());
            applyResult(log, result);
            switch (result.exitCode()) {
                case SUCCESS -> summary.completed++;
                case UNSUPPORTED_FORMAT -> summary.unsupported++;
                default -> summary.failed++;
            }
            publish(log, progressListener);
        } catch (Exception ex) {
            log.status = Status.FAILED;
            log.message = "Failed";
            log.errorText = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            try {
                processor.writeArtifacts(toFailureResult(log), log.resultDir());
            } catch (IOException ioException) {
                logTo(sink, "Failed to write error artifacts: " + ioException.getMessage());
            }
            summary.failed++;
            publish(log, progressListener);
            logTo(sink, "Failed for " + log.fileName() + ": " + log.errorText);
        }
    }

    private ApduParserProcessor.ProcessingResult toFailureResult(ImportedLog log) {
        return ApduParserProcessor.ProcessingResult.parserFailure(
                log.filePath(),
                new LogParserRegistry.DetectionResult(null, LogParserRegistry.extensionOf(log.filePath()), "", false, false, List.of(), "", 0),
                new RuntimeException(log.errorText == null ? "Failed" : log.errorText)
        );
    }

    private void applyResult(ImportedLog log, ApduParserProcessor.ProcessingResult result) {
        log.parserId = result.parserId();
        log.detectedFormat = result.detectedFormat();
        log.message = result.message();
        log.errorText = result.errorText();
        log.apduCount = result.rawApdus().size();
        log.warningCount = result.warnings().size();
        log.appletStatus = result.appletResult().status();
        log.appletMessage = result.appletResult().message();
        log.status = switch (result.exitCode()) {
            case SUCCESS -> Status.COMPLETED;
            case UNSUPPORTED_FORMAT -> Status.UNSUPPORTED;
            case MALFORMED_INPUT, PARSER_FAILURE, OUTPUT_WRITE_FAILURE, INVALID_ARGUMENTS -> Status.FAILED;
        };
    }

    private void publish(ImportedLog log, ProgressListener progressListener) {
        if (progressListener != null) {
            progressListener.onProgress(log.copy());
        }
    }

    private void logTo(LogSink sink, String message) {
        if (sink != null && message != null) {
            sink.log(message);
        }
    }

    private void markCancelled(ImportedLog log) {
        log.status = Status.CANCELLED;
        log.message = "Cancelled";
    }

    private ImportedLog loadImportedLog(Path file) throws IOException {
        ImportedLog log = new ImportedLog(file, computeResultDir(file));
        Path resultJson = log.resultJsonPath();
        if (Files.exists(resultJson)) {
            String json = Files.readString(resultJson, StandardCharsets.UTF_8);
            log.detectedFormat = readJsonString(json, "detectedFormat", "Pending");
            log.parserId = readJsonString(json, "parserId", "");
            log.message = readJsonString(json, "message", "");
            log.errorText = Files.exists(log.errorsOutputPath())
                    ? Files.readString(log.errorsOutputPath(), StandardCharsets.UTF_8)
                    : "";
            log.apduCount = readJsonInt(json, "apduCount", Files.exists(log.rawOutputPath()) ? countLines(log.rawOutputPath()) : 0);
            log.warningCount = readJsonInt(json, "warningCount", 0);
            log.status = Status.valueOf(readJsonString(json, "status", Status.PENDING.name()));
            String appletStatus = readJsonString(json, "appletStatus", AppletExtractor.ExtractionResult.Status.NOT_APPLICABLE.name());
            log.appletStatus = AppletExtractor.ExtractionResult.Status.valueOf(appletStatus);
            log.appletMessage = readJsonString(json, "appletMessage", "");
        }
        return log;
    }

    private static String readJsonString(String json, String field, String fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\") : fallback;
    }

    private static int readJsonInt(String json, String field, int fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static int countLines(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            int lines = 0;
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    lines++;
                }
            }
            return lines;
        }
    }

    private ImportedLog findImportedLog(Path importedFile) {
        for (ImportedLog log : importedLogs) {
            if (Objects.equals(log.filePath(), importedFile)) {
                return log;
            }
        }
        return null;
    }

    private Path computeResultDir(Path importedFile) {
        String folderName = importedFile.getFileName().toString();
        if (folderName.startsWith(".")) {
            folderName = "_" + folderName.substring(1);
        }
        Path candidate = outputDir.resolve(folderName);
        if (Files.exists(candidate) && !Files.isDirectory(candidate)) {
            candidate = outputDir.resolve(folderName + "__result");
        }
        return candidate;
    }

    private static Path uniquePath(Path directory, String fileName) throws IOException {
        Files.createDirectories(directory);
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        Path candidate = directory.resolve(fileName);
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(base + " (" + index + ")" + extension);
            index++;
        }
        return candidate;
    }

    private static Path resolveConfigPath(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return AppEnvironment.configPath();
        }
        Path path = Path.of(configPath);
        return path.toAbsolutePath().normalize();
    }

    private static Path resolveDataRoot(Path configPath) {
        Path parent = configPath.getParent();
        if (parent != null && "config".equalsIgnoreCase(parent.getFileName().toString())) {
            Path root = parent.getParent();
            if (root != null) {
                return root.toAbsolutePath().normalize();
            }
        }
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent.toAbsolutePath().normalize();
    }

    public interface CancellationToken {
        boolean isCancellationRequested();
    }

    public interface ProgressListener {
        void onProgress(ImportedLog updatedLog);
    }

    public interface LogSink {
        void log(String line);
    }

    public static final class RunSummary {
        private int completed;
        private int unsupported;
        private int failed;
        private int cancelled;

        public int completed() {
            return completed;
        }

        public int unsupported() {
            return unsupported;
        }

        public int failed() {
            return failed;
        }

        public int cancelled() {
            return cancelled;
        }
    }

    public enum Status {
        PENDING,
        ANALYZING,
        COMPLETED,
        UNSUPPORTED,
        FAILED,
        CANCELLED
    }

    public static final class ImportedLog {
        private final Path filePath;
        private final Path resultDir;
        private String parserId = "";
        private String detectedFormat = "Pending";
        private Status status = Status.PENDING;
        private String message = "Pending";
        private String errorText = "";
        private int apduCount = 0;
        private int warningCount = 0;
        private AppletExtractor.ExtractionResult.Status appletStatus = AppletExtractor.ExtractionResult.Status.NOT_APPLICABLE;
        private String appletMessage = "";

        private ImportedLog(Path filePath, Path resultDir) {
            this.filePath = filePath;
            this.resultDir = resultDir;
        }

        public ImportedLog copy() {
            ImportedLog copy = new ImportedLog(filePath, resultDir);
            copy.parserId = parserId;
            copy.detectedFormat = detectedFormat;
            copy.status = status;
            copy.message = message;
            copy.errorText = errorText;
            copy.apduCount = apduCount;
            copy.warningCount = warningCount;
            copy.appletStatus = appletStatus;
            copy.appletMessage = appletMessage;
            return copy;
        }

        public Path filePath() {
            return filePath;
        }

        public String fileName() {
            return filePath.getFileName().toString();
        }

        public Path resultDir() {
            return resultDir;
        }

        public Path rawOutputPath() {
            return resultDir.resolve("apdus.txt");
        }

        public Path analysisOutputPath() {
            return resultDir.resolve("analysis.txt");
        }

        public Path errorsOutputPath() {
            return resultDir.resolve("errors.txt");
        }

        public Path resultJsonPath() {
            return resultDir.resolve("result.json");
        }

        public Path appletsDir() {
            return resultDir.resolve("applets");
        }

        public String parserId() {
            return parserId;
        }

        public String detectedFormat() {
            return detectedFormat;
        }

        public Status status() {
            return status;
        }

        public String message() {
            return message;
        }

        public String errorText() {
            return errorText;
        }

        public int apduCount() {
            return apduCount;
        }

        public int warningCount() {
            return warningCount;
        }

        public AppletExtractor.ExtractionResult.Status appletStatus() {
            return appletStatus;
        }

        public String appletMessage() {
            return appletMessage;
        }
    }

    public record Config(
            String inputDir,
            String outputDir,
            String tempDir,
            String logsDir,
            boolean autoAnalyzeOnImport,
            boolean retainDebugArtifacts,
            boolean detectOnlyDefault,
            boolean showDiagnosticsOnLaunch,
            int windowWidth,
            int windowHeight
    ) {
        public static Config load(Path configPath) throws IOException {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Config config = defaults();
                config.save(configPath);
                return config;
            }
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            return new Config(
                    readJsonString(json, "inputDir", "logs/imported"),
                    readJsonString(json, "outputDir", "output"),
                    readJsonString(json, "tempDir", "temp"),
                    readJsonString(json, "logsDir", "logs"),
                    readJsonBoolean(json, "autoAnalyzeOnImport", false),
                    readJsonBoolean(json, "retainDebugArtifacts", false),
                    readJsonBoolean(json, "detectOnlyDefault", false),
                    readJsonBoolean(json, "showDiagnosticsOnLaunch", false),
                    readJsonInt(json, "windowWidth", 1320),
                    readJsonInt(json, "windowHeight", 860)
            );
        }

        public static Config defaults() {
            return new Config("logs/imported", "output", "temp", "logs", false, false, false, false, 1320, 860);
        }

        public void save(Path configPath) throws IOException {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            String json = "{\n"
                    + "  \"inputDir\": \"" + ApduParserProcessor.escapeJson(inputDir) + "\",\n"
                    + "  \"outputDir\": \"" + ApduParserProcessor.escapeJson(outputDir) + "\",\n"
                    + "  \"tempDir\": \"" + ApduParserProcessor.escapeJson(tempDir) + "\",\n"
                    + "  \"logsDir\": \"" + ApduParserProcessor.escapeJson(logsDir) + "\",\n"
                    + "  \"autoAnalyzeOnImport\": " + autoAnalyzeOnImport + ",\n"
                    + "  \"retainDebugArtifacts\": " + retainDebugArtifacts + ",\n"
                    + "  \"detectOnlyDefault\": " + detectOnlyDefault + ",\n"
                    + "  \"showDiagnosticsOnLaunch\": " + showDiagnosticsOnLaunch + ",\n"
                    + "  \"windowWidth\": " + windowWidth + ",\n"
                    + "  \"windowHeight\": " + windowHeight + "\n"
                    + "}\n";
            Files.writeString(configPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static boolean readJsonBoolean(String json, String field, boolean fallback) {
        Pattern bare = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher bareMatcher = bare.matcher(json == null ? "" : json);
        if (bareMatcher.find()) {
            return Boolean.parseBoolean(bareMatcher.group(1));
        }
        return Boolean.parseBoolean(readJsonString(json, field, String.valueOf(fallback)));
    }
}
