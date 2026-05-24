import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class ApduParserEngine {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final Path launcherRoot;
    private final Path workspaceRoot;
    private final Path configPath;
    private final Config config;
    private final Path inputDir;
    private final Path outputDir;
    private final Path unknownDir;
    private final Path workDir;

    public ApduParserEngine() throws IOException {
        this("config.json");
    }

    public ApduParserEngine(String configPath) throws IOException {
        this.launcherRoot = detectLauncherRoot();
        this.workspaceRoot = Objects.requireNonNull(launcherRoot.getParent(), "Workspace root not found");
        Path resolvedConfigPath = ensureInsideWorkspace(workspaceRoot, launcherRoot.resolve(configPath).normalize());
        this.configPath = resolvedConfigPath;
        this.config = Config.load(resolvedConfigPath, workspaceRoot);
        this.inputDir = ensureInsideWorkspace(workspaceRoot, launcherRoot.resolve(config.inputDir).normalize());
        this.outputDir = ensureInsideWorkspace(workspaceRoot, launcherRoot.resolve(config.outputDir).normalize());
        this.unknownDir = ensureInsideWorkspace(workspaceRoot, outputDir.resolve("unknown").normalize());
        this.workDir = ensureInsideWorkspace(workspaceRoot, launcherRoot.resolve(config.workDir).normalize());

        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(workDir);
    }

    public Path getLauncherRoot() {
        return launcherRoot;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path getInputDir() {
        return inputDir;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public Path getUnknownDir() {
        return unknownDir;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public List<ParserDefinition> listParserDefinitions() {
        List<ParserDefinition> definitions = new ArrayList<>();
        for (ParserConfig parser : config.parsers) {
            definitions.add(parser.toDefinition(launcherRoot));
        }
        return definitions;
    }

    public void addParserDefinition(ParserDefinition definition) throws IOException {
        Objects.requireNonNull(definition, "Parser definition is required");

        ParserConfig parser = ParserConfig.fromDefinition(definition, launcherRoot, workspaceRoot);
        for (ParserConfig existing : config.parsers) {
            if (existing.name.equalsIgnoreCase(parser.name)) {
                throw new IllegalArgumentException("A parser with this name already exists: " + parser.name);
            }
        }

        config.parsers.add(parser);
        config.save(configPath, launcherRoot);
    }

    public List<Path> listInputFiles() throws IOException {
        return listVisibleFiles(inputDir);
    }

    public DetectionResult detectParser(Path inputFile) throws IOException {
        Path normalizedInput = inputFile.toAbsolutePath().normalize();
        String content = readForDetection(normalizedInput);
        ParserConfig parser = config.findMatch(normalizedInput, content);
        return new DetectionResult(normalizedInput, parser == null ? null : parser.name);
    }

    public List<RunResult> processAll(boolean dryRun, LogSink logSink) throws Exception {
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(workDir);

        List<Path> inputFiles = listInputFiles();
        if (inputFiles.isEmpty()) {
            log(logSink, "No input files found in " + inputDir);
            return List.of();
        }

        List<RunResult> results = new ArrayList<>();
        for (Path inputFile : inputFiles) {
            results.add(processFile(inputFile, dryRun, logSink));
        }
        return results;
    }

    public RunResult processFile(Path inputFile, boolean dryRun, LogSink logSink) throws Exception {
        Path normalizedInput = inputFile.toAbsolutePath().normalize();
        String content = readForDetection(normalizedInput);
        ParserConfig parser = config.findMatch(normalizedInput, content);

        log(logSink, "--------------------------------------------------");
        log(logSink, "Input file name: " + normalizedInput.getFileName());

        if (parser == null) {
            Path unknownTarget = ensureInsideWorkspace(
                    workspaceRoot,
                    unknownDir.resolve(normalizedInput.getFileName()).normalize()
            );
            log(logSink, "Detected parser: none");
            log(logSink, "Command executed: n/a");
            log(logSink, "Output file path: " + unknownTarget);
            if (!dryRun) {
                Files.createDirectories(unknownTarget.getParent());
                Files.copy(normalizedInput, unknownTarget, StandardCopyOption.REPLACE_EXISTING);
            } else {
                log(logSink, "Dry-run mode enabled, unmatched file was not copied.");
            }
            log(logSink, "No matching parser found");
            return RunResult.unmatched(normalizedInput, unknownTarget, null, dryRun);
        }

        Path parserOutputDir = ensureInsideWorkspace(workspaceRoot, outputDir.resolve(parser.name).normalize());
        Files.createDirectories(parserOutputDir);

        String outputFileName = buildOutputFileName(normalizedInput, parser.outputExtension);
        Path finalOutput = ensureInsideWorkspace(workspaceRoot, parserOutputDir.resolve(outputFileName).normalize());
        Path enhancedOutput = ensureInsideWorkspace(
                workspaceRoot,
                ApduOutputAnalyzer.buildEnhancedOutputPath(finalOutput).normalize()
        );
        Path jobDir = ensureInsideWorkspace(
                workspaceRoot,
                workDir.resolve(buildJobFolderName(normalizedInput, parser.name)).normalize()
        );

        List<String> processCommand = parser.buildProcessCommand(parser.stagedInputFileName, parser.stagedOutputFileName);

        log(logSink, "Detected parser: " + parser.name);
        log(logSink, "Command executed: " + String.join(" ", processCommand));
        log(logSink, "Raw output file path: " + finalOutput);
        log(logSink, "Enhanced analysis file path: " + enhancedOutput);

        if (dryRun) {
            log(logSink, "Dry-run mode enabled, extractor was not executed.");
            return RunResult.dryRun(normalizedInput, parser.name, finalOutput, enhancedOutput, processCommand);
        }

        Files.createDirectories(jobDir);

        Path sourceScript = ensureInsideWorkspace(
                workspaceRoot,
                parser.extractorFolder.resolve(parser.scriptFile).normalize()
        );
        Path stagedScript = ensureInsideWorkspace(
                workspaceRoot,
                jobDir.resolve(parser.stagedScriptFileName).normalize()
        );
        Path stagedInput = ensureInsideWorkspace(
                workspaceRoot,
                jobDir.resolve(parser.stagedInputFileName).normalize()
        );
        Path stagedOutput = ensureInsideWorkspace(
                workspaceRoot,
                jobDir.resolve(parser.stagedOutputFileName).normalize()
        );

        Files.copy(sourceScript, stagedScript, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(normalizedInput, stagedInput, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(stagedOutput);

        ProcessBuilder pb = new ProcessBuilder(processCommand);
        pb.directory(jobDir.toFile());
        pb.redirectErrorStream(true);

        int exitCode = runAndLogProcess(pb, logSink);
        if (exitCode != 0) {
            throw new IllegalStateException("Extractor exited with code " + exitCode + " for " + normalizedInput.getFileName());
        }

        if (!Files.exists(stagedOutput)) {
            throw new IllegalStateException("Expected output not created: " + stagedOutput);
        }

        Files.copy(stagedOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING);
        ApduOutputAnalyzer.analyze(normalizedInput, finalOutput, enhancedOutput);
        return RunResult.completed(normalizedInput, parser.name, finalOutput, enhancedOutput, processCommand);
    }

    public AppletExtractionResult extractApplets(Path inputFile, LogSink logSink) throws Exception {
        Path normalizedInput = ensureInsideWorkspace(workspaceRoot, inputFile.toAbsolutePath().normalize());
        String content = readForDetection(normalizedInput);
        ParserConfig parser = config.findMatch(normalizedInput, content);

        if (parser == null) {
            throw new IllegalStateException("No APDUs available for applet extraction.");
        }

        Path rawOutput = resolveRawOutputPath(normalizedInput, parser.name, parser.outputExtension);
        if (!Files.exists(rawOutput) || Files.size(rawOutput) == 0) {
            throw new IllegalStateException("No APDUs available for applet extraction.");
        }

        Path appletOutputDir = resolveAppletOutputDir(normalizedInput, parser.name);
        Path extractAppletsRoot = ensureInsideWorkspace(
                workspaceRoot,
                workspaceRoot.resolve("ExtractAppletsFromTxt").normalize()
        );
        Path sourceScript = ensureInsideWorkspace(
                workspaceRoot,
                extractAppletsRoot.resolve("ExtractAppletsFromTxt.java").normalize()
        );

        if (!Files.exists(sourceScript)) {
            throw new IllegalStateException("ExtractAppletsFromTxt.java was not found in the workspace.");
        }

        clearDirectory(appletOutputDir);
        Files.createDirectories(appletOutputDir);

        List<String> processCommand = List.of(
                resolveJavaExecutable().toString(),
                sourceScript.getFileName().toString(),
                rawOutput.toString(),
                appletOutputDir.toString()
        );

        log(logSink, "--------------------------------------------------");
        log(logSink, "Applet extraction input: " + rawOutput);
        log(logSink, "Applet extraction command: " + String.join(" ", processCommand));
        log(logSink, "Applet output folder: " + appletOutputDir);

        ProcessBuilder pb = new ProcessBuilder(processCommand);
        pb.directory(extractAppletsRoot.toFile());
        pb.redirectErrorStream(true);

        int exitCode = runAndLogProcess(pb, logSink);
        if (exitCode != 0) {
            throw new IllegalStateException("Applet extractor exited with code " + exitCode + ".");
        }

        List<Path> appletFiles = listAppletFiles(appletOutputDir);
        Path allCleanFile = appletOutputDir.resolve("all_clean.lop");
        if (appletFiles.isEmpty()) {
            log(logSink, "No applet data found.");
        } else {
            log(logSink, "Applet files created: " + appletFiles.size());
        }

        return new AppletExtractionResult(
                normalizedInput,
                parser.name,
                rawOutput,
                appletOutputDir,
                allCleanFile,
                appletFiles,
                processCommand
        );
    }

    public void importFiles(List<Path> files) throws IOException {
        Files.createDirectories(inputDir);
        for (Path file : files) {
            if (file == null) {
                continue;
            }
            Path normalized = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                continue;
            }
            Path target = ensureInsideWorkspace(
                    workspaceRoot,
                    inputDir.resolve(normalized.getFileName()).normalize()
            );
            Files.copy(normalized, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public boolean deleteImportedFile(Path file) throws IOException {
        if (file == null) {
            return false;
        }
        Path normalized = ensureInsideWorkspace(workspaceRoot, file.toAbsolutePath().normalize());
        if (!normalized.startsWith(inputDir)) {
            throw new IllegalArgumentException("Imported file is outside input directory: " + normalized);
        }
        return Files.deleteIfExists(normalized);
    }

    public int clearImportedFiles() throws IOException {
        int deleted = 0;
        for (Path file : listInputFiles()) {
            if (deleteImportedFile(file)) {
                deleted++;
            }
        }
        return deleted;
    }

    public String readFilePreview(Path file, int maxChars) throws IOException {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + System.lineSeparator() + "...";
    }

    public Path resolveAppletOutputDir(Path inputFile, String parserName) {
        Path normalizedInput = inputFile.toAbsolutePath().normalize();
        return ensureInsideWorkspace(
                workspaceRoot,
                outputDir.resolve(parserName)
                        .resolve("applets")
                        .resolve(sanitizeFilePart(stripExtension(normalizedInput.getFileName().toString())))
                        .normalize()
        );
    }

    public List<Path> listAppletFiles(Path appletOutputDir) throws IOException {
        if (appletOutputDir == null || !Files.isDirectory(appletOutputDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(appletOutputDir, "applet_*.lop")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path.toAbsolutePath().normalize());
                }
            }
        }
        Collections.sort(files);
        return files;
    }

    public String readAppletPreview(Path appletOutputDir, int maxChars) throws IOException {
        if (appletOutputDir == null || !Files.isDirectory(appletOutputDir)) {
            return "No APDUs available for applet extraction.";
        }

        List<Path> appletFiles = listAppletFiles(appletOutputDir);
        Path allClean = appletOutputDir.resolve("all_clean.lop");
        if (appletFiles.isEmpty()) {
            return "No applet data found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Applet files found: ").append(appletFiles.size()).append(System.lineSeparator());
        for (Path file : appletFiles) {
            sb.append("- ").append(file.getFileName()).append(System.lineSeparator());
        }

        if (Files.exists(allClean)) {
            sb.append(System.lineSeparator()).append("all_clean.lop").append(System.lineSeparator());
            sb.append("----------------------------------------").append(System.lineSeparator());
            sb.append(readFilePreview(allClean, maxChars));
        } else {
            sb.append(System.lineSeparator()).append("No combined all_clean.lop file found.");
        }
        return sb.toString();
    }

    private static void log(LogSink sink, String message) {
        if (sink != null) {
            sink.log(message);
        }
    }

    private static int runAndLogProcess(ProcessBuilder pb, LogSink sink) throws IOException, InterruptedException {
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log(sink, "[extractor] " + line);
            }
        }
        return process.waitFor();
    }

    private static List<Path> listVisibleFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (Files.isRegularFile(path) && !fileName.startsWith(".")) {
                    files.add(path.toAbsolutePath().normalize());
                }
            }
        }
        Collections.sort(files);
        return files;
    }

    private static String readForDetection(Path inputFile) throws IOException {
        byte[] bytes = Files.readAllBytes(inputFile);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String buildOutputFileName(Path inputFile, String outputExtension) {
        String name = inputFile.getFileName().toString();
        String base = stripExtension(name);
        return sanitizeFilePart(base) + outputExtension;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String buildJobFolderName(Path inputFile, String parserName) {
        return sanitizeFilePart(parserName)
                + "__"
                + sanitizeFilePart(inputFile.getFileName().toString())
                + "__"
                + TS.format(LocalDateTime.now());
    }

    private static String sanitizeFilePart(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        String cleaned = normalized
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replace('\u0000', '_');
        String trimmed = cleaned.trim();
        while (!trimmed.isEmpty() && (trimmed.endsWith(".") || trimmed.endsWith(" "))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? "item" : trimmed;
    }

    public static Path ensureInsideWorkspace(Path workspaceRoot, Path candidate) {
        Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException("Path escapes workspace: " + normalizedCandidate);
        }
        return normalizedCandidate;
    }

    public static Path detectLauncherRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(cwd);
        candidates.add(cwd.resolve("apdu_parser_launcher").normalize());
        Path parent = cwd.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("apdu_parser_launcher").normalize());
        }

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)
                    && Files.exists(candidate.resolve("config.json"))
                    && Files.isDirectory(candidate.resolve("src"))) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "Could not locate apdu_parser_launcher. Run from the launcher folder or the workspace root."
        );
    }

    public static Path resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return Paths.get("java");
        }
        Path javaBin = Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");
        return Files.exists(javaBin) ? javaBin : Paths.get("java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path resolveRawOutputPath(Path inputFile, String parserName, String outputExtension) {
        String outputFileName = buildOutputFileName(inputFile, outputExtension);
        return ensureInsideWorkspace(
                workspaceRoot,
                outputDir.resolve(parserName).resolve(outputFileName).normalize()
        );
    }

    private void clearDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Path normalizedDir = ensureInsideWorkspace(workspaceRoot, dir.toAbsolutePath().normalize());
        Files.walkFileTree(normalizedDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (!directory.equals(normalizedDir)) {
                    Files.deleteIfExists(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public interface LogSink {
        void log(String message);
    }

    public static final class DetectionResult {
        private final Path inputFile;
        private final String parserName;

        private DetectionResult(Path inputFile, String parserName) {
            this.inputFile = inputFile;
            this.parserName = parserName;
        }

        public Path getInputFile() {
            return inputFile;
        }

        public String getParserName() {
            return parserName;
        }

        public boolean matched() {
            return parserName != null;
        }
    }

    public static final class RunResult {
        private final Path inputFile;
        private final String parserName;
        private final Path outputFile;
        private final Path enhancedOutputFile;
        private final List<String> command;
        private final String status;
        private final boolean dryRun;

        private RunResult(
                Path inputFile,
                String parserName,
                Path outputFile,
                Path enhancedOutputFile,
                List<String> command,
                String status,
                boolean dryRun
        ) {
            this.inputFile = inputFile;
            this.parserName = parserName;
            this.outputFile = outputFile;
            this.enhancedOutputFile = enhancedOutputFile;
            this.command = command;
            this.status = status;
            this.dryRun = dryRun;
        }

        public static RunResult completed(Path inputFile, String parserName, Path outputFile, Path enhancedOutputFile, List<String> command) {
            return new RunResult(inputFile, parserName, outputFile, enhancedOutputFile, List.copyOf(command), "completed", false);
        }

        public static RunResult dryRun(Path inputFile, String parserName, Path outputFile, Path enhancedOutputFile, List<String> command) {
            return new RunResult(inputFile, parserName, outputFile, enhancedOutputFile, List.copyOf(command), "dry-run", true);
        }

        public static RunResult unmatched(Path inputFile, Path outputFile, Path enhancedOutputFile, boolean dryRun) {
            return new RunResult(inputFile, null, outputFile, enhancedOutputFile, List.of(), dryRun ? "unmatched-dry-run" : "unmatched", dryRun);
        }

        public Path getInputFile() {
            return inputFile;
        }

        public String getParserName() {
            return parserName;
        }

        public Path getOutputFile() {
            return outputFile;
        }

        public Path getEnhancedOutputFile() {
            return enhancedOutputFile;
        }

        public List<String> getCommand() {
            return command;
        }

        public String getStatus() {
            return status;
        }

        public boolean isDryRun() {
            return dryRun;
        }
    }

    public static final class AppletExtractionResult {
        private final Path inputFile;
        private final String parserName;
        private final Path apduOutputFile;
        private final Path appletOutputDir;
        private final Path allCleanFile;
        private final List<Path> appletFiles;
        private final List<String> command;

        private AppletExtractionResult(
                Path inputFile,
                String parserName,
                Path apduOutputFile,
                Path appletOutputDir,
                Path allCleanFile,
                List<Path> appletFiles,
                List<String> command
        ) {
            this.inputFile = inputFile;
            this.parserName = parserName;
            this.apduOutputFile = apduOutputFile;
            this.appletOutputDir = appletOutputDir;
            this.allCleanFile = allCleanFile;
            this.appletFiles = List.copyOf(appletFiles);
            this.command = List.copyOf(command);
        }

        public Path getInputFile() {
            return inputFile;
        }

        public String getParserName() {
            return parserName;
        }

        public Path getApduOutputFile() {
            return apduOutputFile;
        }

        public Path getAppletOutputDir() {
            return appletOutputDir;
        }

        public Path getAllCleanFile() {
            return allCleanFile;
        }

        public List<Path> getAppletFiles() {
            return appletFiles;
        }

        public List<String> getCommand() {
            return command;
        }

        public boolean hasApplets() {
            return !appletFiles.isEmpty();
        }
    }

    public static final class ParserDefinition {
        private final String name;
        private final String extractorFolder;
        private final String scriptFile;
        private final String stagedScriptFileName;
        private final String stagedInputFileName;
        private final String stagedOutputFileName;
        private final String outputExtension;
        private final String detectionMode;
        private final List<String> patterns;
        private final List<String> extensions;
        private final String fileNameRegex;
        private final List<String> commandArgs;

        public ParserDefinition(
                String name,
                String extractorFolder,
                String scriptFile,
                String stagedScriptFileName,
                String stagedInputFileName,
                String stagedOutputFileName,
                String outputExtension,
                String detectionMode,
                List<String> patterns,
                List<String> extensions,
                String fileNameRegex,
                List<String> commandArgs
        ) {
            this.name = name;
            this.extractorFolder = extractorFolder;
            this.scriptFile = scriptFile;
            this.stagedScriptFileName = stagedScriptFileName;
            this.stagedInputFileName = stagedInputFileName;
            this.stagedOutputFileName = stagedOutputFileName;
            this.outputExtension = outputExtension;
            this.detectionMode = detectionMode;
            this.patterns = patterns == null ? List.of() : List.copyOf(patterns);
            this.extensions = extensions == null ? List.of() : List.copyOf(extensions);
            this.fileNameRegex = fileNameRegex == null ? "" : fileNameRegex;
            this.commandArgs = commandArgs == null ? List.of() : List.copyOf(commandArgs);
        }

        public String getName() {
            return name;
        }

        public String getExtractorFolder() {
            return extractorFolder;
        }

        public String getScriptFile() {
            return scriptFile;
        }

        public String getStagedScriptFileName() {
            return stagedScriptFileName;
        }

        public String getStagedInputFileName() {
            return stagedInputFileName;
        }

        public String getStagedOutputFileName() {
            return stagedOutputFileName;
        }

        public String getOutputExtension() {
            return outputExtension;
        }

        public String getDetectionMode() {
            return detectionMode;
        }

        public List<String> getPatterns() {
            return patterns;
        }

        public List<String> getExtensions() {
            return extensions;
        }

        public String getFileNameRegex() {
            return fileNameRegex;
        }

        public List<String> getCommandArgs() {
            return commandArgs;
        }
    }

    private static final class Config {
        private final String inputDir;
        private final String outputDir;
        private final String workDir;
        private final List<ParserConfig> parsers;

        private Config(String inputDir, String outputDir, String workDir, List<ParserConfig> parsers) {
            this.inputDir = inputDir;
            this.outputDir = outputDir;
            this.workDir = workDir;
            this.parsers = parsers;
        }

        private static Config load(Path configPath, Path workspaceRoot) throws IOException {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            Object parsed = new JsonParser(json).parse();
            if (!(parsed instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("config.json root must be an object");
            }
            Map<?, ?> rawRoot = (Map<?, ?>) parsed;

            String inputDir = requireString(rawRoot, "inputDir");
            String outputDir = requireString(rawRoot, "outputDir");
            String workDir = requireString(rawRoot, "workDir");

            Object parserList = rawRoot.get("parsers");
            if (!(parserList instanceof List<?>)) {
                throw new IllegalArgumentException("config.json must define a non-empty parsers array");
            }
            List<?> rawParsers = (List<?>) parserList;
            if (rawParsers.isEmpty()) {
                throw new IllegalArgumentException("config.json must define a non-empty parsers array");
            }

            List<ParserConfig> parsers = new ArrayList<>();
            for (Object item : rawParsers) {
                if (!(item instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("Each parser entry must be an object");
                }
                Map<?, ?> rawParser = (Map<?, ?>) item;
                parsers.add(ParserConfig.from(rawParser, configPath.getParent(), workspaceRoot));
            }

            return new Config(inputDir, outputDir, workDir, parsers);
        }

        private ParserConfig findMatch(Path inputFile, String content) {
            for (ParserConfig parser : parsers) {
                if (parser.matches(inputFile, content)) {
                    return parser;
                }
            }
            return null;
        }

        private void save(Path configPath, Path launcherRoot) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            appendJsonProperty(sb, "inputDir", inputDir, 1, true);
            appendJsonProperty(sb, "outputDir", outputDir, 1, true);
            appendJsonProperty(sb, "workDir", workDir, 1, true);
            sb.append(indent(1)).append("\"parsers\": [\n");
            for (int i = 0; i < parsers.size(); i++) {
                ParserConfig parser = parsers.get(i);
                sb.append(parser.toJson(launcherRoot, 2));
                if (i < parsers.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(indent(1)).append("]\n");
            sb.append("}\n");
            Files.writeString(configPath, sb.toString(), StandardCharsets.UTF_8);
        }
    }

    private static final class ParserConfig {
        private final String name;
        private final Path extractorFolder;
        private final String scriptFile;
        private final String stagedScriptFileName;
        private final String stagedInputFileName;
        private final String stagedOutputFileName;
        private final String outputExtension;
        private final String detectionMode;
        private final List<String> patterns;
        private final List<String> extensions;
        private final String fileNameRegex;
        private final List<String> commandArgs;

        private ParserConfig(
                String name,
                Path extractorFolder,
                String scriptFile,
                String stagedScriptFileName,
                String stagedInputFileName,
                String stagedOutputFileName,
                String outputExtension,
                String detectionMode,
                List<String> patterns,
                List<String> extensions,
                String fileNameRegex,
                List<String> commandArgs
        ) {
            this.name = name;
            this.extractorFolder = extractorFolder;
            this.scriptFile = scriptFile;
            this.stagedScriptFileName = stagedScriptFileName;
            this.stagedInputFileName = stagedInputFileName;
            this.stagedOutputFileName = stagedOutputFileName;
            this.outputExtension = outputExtension;
            this.detectionMode = detectionMode;
            this.patterns = patterns;
            this.extensions = extensions;
            this.fileNameRegex = fileNameRegex;
            this.commandArgs = commandArgs;
        }

        private static ParserConfig from(Map<?, ?> raw, Path configDir, Path workspaceRoot) {
            String name = requireString(raw, "name");
            String folderValue = requireString(raw, "extractorFolder");
            String scriptFile = requireString(raw, "scriptFile");
            String stagedScriptFileName = requireString(raw, "stagedScriptFileName");
            String stagedInputFileName = requireString(raw, "stagedInputFileName");
            String stagedOutputFileName = requireString(raw, "stagedOutputFileName");
            String outputExtension = optionalString(raw, "outputExtension", ".txt");
            String detectionMode = optionalString(raw, "detectionMode", "all").toLowerCase(Locale.ROOT);
            List<String> patterns = optionalStringList(raw, "patterns");
            List<String> extensions = optionalStringList(raw, "extensions");
            String fileNameRegex = optionalString(raw, "fileNameRegex", "");
            List<String> commandArgs = optionalStringList(raw, "commandArgs");

            if (!"all".equals(detectionMode) && !"any".equals(detectionMode)) {
                throw new IllegalArgumentException("Unsupported detectionMode for parser " + name + ": " + detectionMode);
            }
            if (patterns.isEmpty() && extensions.isEmpty() && fileNameRegex.isBlank()) {
                throw new IllegalArgumentException(
                        "Parser " + name + " must define at least one detection hint: patterns, extensions, or fileNameRegex"
                );
            }

            Path folder = ensureInsideWorkspace(
                    workspaceRoot,
                    Objects.requireNonNull(configDir, "Config directory is required").resolve(folderValue).normalize()
            );

            return new ParserConfig(
                    name,
                    folder,
                    scriptFile,
                    stagedScriptFileName,
                    stagedInputFileName,
                    stagedOutputFileName,
                    outputExtension,
                    detectionMode,
                    patterns,
                    extensions,
                    fileNameRegex,
                    commandArgs
            );
        }

        private static ParserConfig fromDefinition(ParserDefinition definition, Path launcherRoot, Path workspaceRoot) {
            String name = requireNonBlank(definition.getName(), "Parser name");
            String extractorFolder = requireNonBlank(definition.getExtractorFolder(), "Extractor folder");
            String scriptFile = requireNonBlank(definition.getScriptFile(), "Script file");
            String stagedScriptFileName = requireNonBlank(definition.getStagedScriptFileName(), "Staged script file name");
            String stagedInputFileName = requireNonBlank(definition.getStagedInputFileName(), "Staged input file name");
            String stagedOutputFileName = requireNonBlank(definition.getStagedOutputFileName(), "Staged output file name");
            String outputExtension = definition.getOutputExtension() == null || definition.getOutputExtension().isBlank()
                    ? ".txt"
                    : definition.getOutputExtension();
            String detectionMode = definition.getDetectionMode() == null || definition.getDetectionMode().isBlank()
                    ? "all"
                    : definition.getDetectionMode().toLowerCase(Locale.ROOT);

            if (!"all".equals(detectionMode) && !"any".equals(detectionMode)) {
                throw new IllegalArgumentException("Detection mode must be 'all' or 'any'");
            }

            List<String> patterns = new ArrayList<>(definition.getPatterns());
            List<String> extensions = new ArrayList<>(definition.getExtensions());
            String fileNameRegex = definition.getFileNameRegex() == null ? "" : definition.getFileNameRegex();
            if (patterns.isEmpty() && extensions.isEmpty() && fileNameRegex.isBlank()) {
                throw new IllegalArgumentException("Provide at least one of: supported extensions, file regex, or detection patterns");
            }

            Path folder = ensureInsideWorkspace(workspaceRoot, launcherRoot.resolve(extractorFolder).normalize());

            return new ParserConfig(
                    name,
                    folder,
                    scriptFile,
                    stagedScriptFileName,
                    stagedInputFileName,
                    stagedOutputFileName,
                    outputExtension,
                    detectionMode,
                    patterns,
                    extensions,
                    fileNameRegex,
                    new ArrayList<>(definition.getCommandArgs())
            );
        }

        private List<String> buildProcessCommand(String stagedInput, String stagedOutput) {
            List<String> command = new ArrayList<>();
            command.add(resolveJavaExecutable().toString());
            command.add(stagedScriptFileName);
            for (String arg : commandArgs) {
                command.add(
                        arg.replace("{input}", stagedInput)
                                .replace("{output}", stagedOutput)
                );
            }
            return command;
        }

        private boolean matches(Path inputFile, String content) {
            String lowerContent = content.toLowerCase(Locale.ROOT);
            String fileName = inputFile.getFileName().toString();
            String lowerFileName = fileName.toLowerCase(Locale.ROOT);

            if (!extensions.isEmpty()) {
                boolean extensionMatch = false;
                for (String ext : extensions) {
                    if (lowerFileName.endsWith(ext.toLowerCase(Locale.ROOT))) {
                        extensionMatch = true;
                        break;
                    }
                }
                if (!extensionMatch) {
                    return false;
                }
            }

            if (!fileNameRegex.isBlank() && !fileName.matches(fileNameRegex)) {
                return false;
            }

            if (patterns.isEmpty()) {
                return true;
            }

            if ("any".equals(detectionMode)) {
                for (String pattern : patterns) {
                    if (lowerContent.contains(pattern.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
                return false;
            }

            for (String pattern : patterns) {
                if (!lowerContent.contains(pattern.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        }

        private ParserDefinition toDefinition(Path launcherRoot) {
            Path relativeFolder = launcherRoot.relativize(extractorFolder);
            return new ParserDefinition(
                    name,
                    relativeFolder.toString().replace('\\', '/'),
                    scriptFile,
                    stagedScriptFileName,
                    stagedInputFileName,
                    stagedOutputFileName,
                    outputExtension,
                    detectionMode,
                    patterns,
                    extensions,
                    fileNameRegex,
                    commandArgs
            );
        }

        private String toJson(Path launcherRoot, int level) {
            StringBuilder sb = new StringBuilder();
            ParserDefinition definition = toDefinition(launcherRoot);
            sb.append(indent(level)).append("{\n");
            appendJsonProperty(sb, "name", definition.getName(), level + 1, true);
            appendJsonProperty(sb, "extractorFolder", definition.getExtractorFolder(), level + 1, true);
            appendJsonProperty(sb, "scriptFile", definition.getScriptFile(), level + 1, true);
            appendJsonProperty(sb, "stagedScriptFileName", definition.getStagedScriptFileName(), level + 1, true);
            appendJsonProperty(sb, "stagedInputFileName", definition.getStagedInputFileName(), level + 1, true);
            appendJsonProperty(sb, "stagedOutputFileName", definition.getStagedOutputFileName(), level + 1, true);
            appendJsonProperty(sb, "outputExtension", definition.getOutputExtension(), level + 1, true);
            appendJsonProperty(sb, "detectionMode", definition.getDetectionMode(), level + 1, true);
            if (!definition.getCommandArgs().isEmpty()) {
                appendJsonArrayProperty(sb, "commandArgs", definition.getCommandArgs(), level + 1, true);
            }
            if (!definition.getPatterns().isEmpty()) {
                appendJsonArrayProperty(sb, "patterns", definition.getPatterns(), level + 1, true);
            }
            if (!definition.getExtensions().isEmpty()) {
                appendJsonArrayProperty(sb, "extensions", definition.getExtensions(), level + 1, true);
            }
            if (!definition.getFileNameRegex().isBlank()) {
                appendJsonProperty(sb, "fileNameRegex", definition.getFileNameRegex(), level + 1, true);
            }
            trimTrailingComma(sb);
            sb.append(indent(level)).append("}");
            return sb.toString();
        }
    }

    private static void appendJsonProperty(StringBuilder sb, String key, String value, int level, boolean comma) {
        sb.append(indent(level))
                .append("\"").append(escapeJson(key)).append("\": ")
                .append("\"").append(escapeJson(value)).append("\"");
        if (comma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendJsonArrayProperty(StringBuilder sb, String key, List<String> values, int level, boolean comma) {
        sb.append(indent(level))
                .append("\"").append(escapeJson(key)).append("\": [\n");
        for (int i = 0; i < values.size(); i++) {
            sb.append(indent(level + 1))
                    .append("\"").append(escapeJson(values.get(i))).append("\"");
            if (i < values.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(indent(level)).append("]");
        if (comma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void trimTrailingComma(StringBuilder sb) {
        int index = sb.length() - 1;
        while (index >= 0 && Character.isWhitespace(sb.charAt(index))) {
            index--;
        }
        if (index >= 0 && sb.charAt(index) == ',') {
            sb.deleteCharAt(index);
        }
    }

    private static String indent(int level) {
        return "  ".repeat(Math.max(0, level));
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Missing or blank string property: " + key);
        }
        String s = (String) value;
        if (s.isBlank()) {
            throw new IllegalArgumentException("Missing or blank string property: " + key);
        }
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Property must be a string: " + key);
        }
        return (String) value;
    }

    private static List<String> requireStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Missing or empty string list property: " + key);
        }
        List<?> rawList = (List<?>) value;
        if (rawList.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty string list property: " + key);
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("Property " + key + " must contain only non-blank strings");
            }
            String s = (String) item;
            if (s.isBlank()) {
                throw new IllegalArgumentException("Property " + key + " must contain only non-blank strings");
            }
            result.add(s);
        }
        return result;
    }

    private static List<String> optionalStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Property must be a list: " + key);
        }
        List<?> rawList = (List<?>) value;
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("Property " + key + " must contain only non-blank strings");
            }
            String s = (String) item;
            if (s.isBlank()) {
                throw new IllegalArgumentException("Property " + key + " must contain only non-blank strings");
            }
            result.add(s);
        }
        return result;
    }

    private static final class JsonParser {
        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw error("Unexpected end of input");
            }
            char c = text.charAt(index);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expectLiteral("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expectLiteral("null");
                return null;
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw error("Unexpected character: " + c);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw error("Unexpected end of escape sequence");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append(parseUnicodeEscape());
                            break;
                        default:
                            throw error("Unsupported escape sequence: \\" + escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = text.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error("Invalid unicode escape: " + hex);
            }
        }

        private Number parseNumber() {
            int start = index;
            if (text.charAt(index) == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String number = text.substring(start, index);
            try {
                if (number.contains(".")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("Invalid number: " + number);
            }
        }

        private void expectLiteral(String literal) {
            if (!text.startsWith(literal, index)) {
                throw error("Expected " + literal);
            }
            index += literal.length();
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            skipWhitespace();
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }
    }
}
