import apdu.parser.plugin.api.PluginDetectionResult;
import apdu.parser.plugin.api.PluginParseResult;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LegacyJavaExtractorSupport {
    private static final List<Charset> INPUT_CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("GBK"),
            Charset.forName("Big5"),
            StandardCharsets.UTF_16,
            StandardCharsets.UTF_16LE,
            StandardCharsets.UTF_16BE,
            StandardCharsets.ISO_8859_1
    );

    private static final Pattern INLINE_TX_RX_RE = Pattern.compile(
            "Type\\s*=\\s*(TX|RX)\\s+Data\\s*=\\s*(?:\\{([^}]*)\\}|([0-9A-Fa-f][0-9A-Fa-f ]*))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COLON_TX_RX_RE = Pattern.compile(
            "\\b(TX|RX):\\s*([0-9A-Fa-f][0-9A-Fa-f ]*)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APDU_TX_RX_RE = Pattern.compile(
            "\\bAPDU_(tx|rx)\\b[^:]*:\\s*([0-9A-Fa-f][0-9A-Fa-f ]*)",
            Pattern.CASE_INSENSITIVE
    );

    private LegacyJavaExtractorSupport() {
    }

    public static PluginDetectionResult detect(
            Path inputFile,
            Class<?> pluginOwner,
            List<String> supportedExtensions,
            String mainClassName,
            String commandPattern,
            String outputFileName
    ) throws Exception {
        if (!matchesExtension(inputFile, supportedExtensions)) {
            return PluginDetectionResult.noMatch("File extension is not supported by this legacy extractor.");
        }
        LegacyExtractorExecutionResult result = execute(inputFile, resolvePluginJar(pluginOwner), mainClassName, commandPattern, outputFileName);
        if (!result.success()) {
            String reason = result.message();
            if (!result.stderr().isBlank()) {
                reason = reason + " STDERR: " + firstLine(result.stderr());
            }
            return PluginDetectionResult.noMatch(reason.strip());
        }
        if (result.apdus().isEmpty()) {
            return PluginDetectionResult.noMatch("Legacy extractor produced no APDUs.");
        }
        return PluginDetectionResult.matched(88, "Legacy extractor produced " + result.apdus().size() + " APDUs.");
    }

    public static PluginParseResult parse(
            Path inputFile,
            Class<?> pluginOwner,
            String mainClassName,
            String commandPattern,
            String outputFileName
    ) throws Exception {
        LegacyExtractorExecutionResult result = execute(inputFile, resolvePluginJar(pluginOwner), mainClassName, commandPattern, outputFileName);
        if (!result.success()) {
            StringBuilder message = new StringBuilder(result.message());
            if (!result.stderr().isBlank()) {
                message.append(System.lineSeparator()).append(result.stderr().strip());
            }
            throw new IOException(message.toString().strip());
        }
        if (result.apdus().isEmpty()) {
            throw new IOException("Legacy extractor completed but produced no parseable APDUs.");
        }
        return new PluginParseResult(result.apdus(), result.warnings());
    }

    public static LegacyExtractorExecutionResult executeWithJar(
            Path inputFile,
            Path pluginJar,
            String mainClassName,
            String commandPattern,
            String outputFileName
    ) throws IOException {
        return execute(inputFile, pluginJar, mainClassName, commandPattern, outputFileName);
    }

    private static LegacyExtractorExecutionResult execute(
            Path inputFile,
            Path pluginJar,
            String mainClassName,
            String commandPattern,
            String outputFileName
    ) throws IOException {
        Path tempRoot = Files.createTempDirectory(AppEnvironment.tempDir(), "legacy-extractor-run-");
        try {
            String extension = extensionOf(inputFile);
            Path stagedInput = tempRoot.resolve("input" + (extension.isBlank() ? ".log" : extension));
            stageInputFile(inputFile, stagedInput);
            Path outputPath = switch (LegacyCommandPattern.fromWireValue(commandPattern)) {
                case INPUT_FILE_OUTPUT_FILE -> tempRoot.resolve(safeOutputName(outputFileName));
                case INPUT_FILE -> tempRoot.resolve(safeOutputName(outputFileName));
            };

            List<String> command = new ArrayList<>();
            command.add(resolveJavaExecutable().toString());
            command.add("-Dfile.encoding=UTF-8");
            command.add("-cp");
            command.add(pluginJar.toString());
            command.add(mainClassName);
            command.add(stagedInput.toAbsolutePath().toString());
            if (LegacyCommandPattern.fromWireValue(commandPattern) == LegacyCommandPattern.INPUT_FILE_OUTPUT_FILE) {
                command.add(outputPath.toAbsolutePath().toString());
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(tempRoot.toFile());
            processBuilder.redirectErrorStream(false);
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException ex) {
                return new LegacyExtractorExecutionResult(false, -1,
                        "Legacy extractor process failed to start.", "", ex.toString(), outputPath, List.of(), List.of(ex.toString()));
            }

            String stdout;
            String stderr;
            int exitCode;
            try {
                stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                exitCode = process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new LegacyExtractorExecutionResult(false, -1,
                        "Legacy extractor execution was interrupted.", "", "Execution interrupted.", outputPath, List.of(), List.of("Execution interrupted."));
            }

            Path effectiveOutputPath = resolveEffectiveOutputPath(tempRoot, outputPath, outputFileName);
            Path persistedOutputPath = persistOutputCopy(effectiveOutputPath);
            if (exitCode != 0) {
                return new LegacyExtractorExecutionResult(false, exitCode,
                        "Legacy extractor exited with code " + exitCode + ".",
                        stdout,
                        stderr,
                        persistedOutputPath,
                        List.of(),
                        List.of("Legacy extractor exit code: " + exitCode));
            }
            if (effectiveOutputPath == null || !Files.exists(effectiveOutputPath)) {
                return new LegacyExtractorExecutionResult(false, exitCode,
                        "Legacy extractor did not create the expected APDU output file.",
                        stdout,
                        stderr,
                        persistedOutputPath,
                        List.of(),
                        List.of("No output file was created."));
            }

            ParsedLegacyOutput parsed = parseOutputFile(effectiveOutputPath);
            if (parsed.apdus().isEmpty()) {
                return new LegacyExtractorExecutionResult(false, exitCode,
                        "Legacy extractor output did not contain parseable APDUs.",
                        stdout,
                        stderr,
                        persistedOutputPath,
                        List.of(),
                        parsed.warnings());
            }

            return new LegacyExtractorExecutionResult(true, exitCode, "Legacy extractor completed.",
                    stdout, stderr, persistedOutputPath, parsed.apdus(), parsed.warnings());
        } finally {
            try {
                ApduParserProcessor.deleteDirectoryIfExists(tempRoot);
            } catch (IOException ignored) {
            }
        }
    }

    private static Path resolveJavaExecutable() {
        Path bundled = AppEnvironment.parserRuntimeJavaPath();
        if (Files.exists(bundled)) {
            return bundled;
        }
        Path javaHome = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        if (Files.exists(javaHome)) {
            return javaHome.toAbsolutePath().normalize();
        }
        return javaHome;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path resolvePluginJar(Class<?> ownerClass) {
        try {
            return Path.of(ownerClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (Exception ex) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }

    private static boolean matchesExtension(Path inputFile, List<String> supportedExtensions) {
        if (inputFile == null) {
            return false;
        }
        if (supportedExtensions == null || supportedExtensions.isEmpty()) {
            return true;
        }
        String name = inputFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        for (String candidate : supportedExtensions) {
            if (candidate != null && extension.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveEffectiveOutputPath(Path tempRoot, Path explicitOutputPath, String outputFileName) throws IOException {
        if (explicitOutputPath != null && Files.exists(explicitOutputPath)) {
            return explicitOutputPath;
        }
        if (outputFileName != null && !outputFileName.isBlank()) {
            Path candidate = tempRoot.resolve(outputFileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        try (var stream = Files.list(tempRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".txt") || name.endsWith(".log");
                    })
                    .sorted()
                    .findFirst()
                    .orElse(explicitOutputPath);
        }
    }

    private static Path persistOutputCopy(Path effectiveOutputPath) throws IOException {
        if (effectiveOutputPath == null || !Files.exists(effectiveOutputPath)) {
            return effectiveOutputPath;
        }
        String originalName = effectiveOutputPath.getFileName().toString();
        String suffix = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : ".txt";
        Path persisted = Files.createTempFile(AppEnvironment.tempDir(), "legacy-output-", suffix);
        Files.copy(effectiveOutputPath, persisted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return persisted;
    }

    private static ParsedLegacyOutput parseOutputFile(Path outputFile) throws IOException {
        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        List<String> apdus = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String extracted = extractTxApdu(line);
            if (!extracted.isBlank()) {
                apdus.add(extracted);
                continue;
            }
            String normalized = ApduOutputAnalyzer.normalizeHex(line);
            if (normalized.length() >= 8 && (normalized.length() % 2) == 0) {
                apdus.add(normalized);
            }
        }
        if (apdus.isEmpty()) {
            warnings.add("No APDU lines were recognized in " + outputFile.getFileName());
        }
        return new ParsedLegacyOutput(List.copyOf(apdus), List.copyOf(warnings));
    }

    private static String extractTxApdu(String line) {
        Matcher inline = INLINE_TX_RX_RE.matcher(line);
        if (inline.find()) {
            String direction = inline.group(1);
            if ("TX".equalsIgnoreCase(direction)) {
                String payload = inline.group(2) != null ? inline.group(2) : inline.group(3);
                return ApduOutputAnalyzer.normalizeHex(payload);
            }
            return "";
        }

        Matcher colon = COLON_TX_RX_RE.matcher(line);
        if (colon.find()) {
            if ("TX".equalsIgnoreCase(colon.group(1))) {
                return ApduOutputAnalyzer.normalizeHex(colon.group(2));
            }
            return "";
        }

        Matcher apdu = APDU_TX_RX_RE.matcher(line);
        if (apdu.find()) {
            if ("tx".equalsIgnoreCase(apdu.group(1))) {
                return ApduOutputAnalyzer.normalizeHex(apdu.group(2));
            }
            return "";
        }
        return "";
    }

    private static String safeOutputName(String outputFileName) {
        String value = outputFileName == null || outputFileName.isBlank() ? "apdus.txt" : outputFileName.trim();
        value = value.replace('\\', '_').replace('/', '_');
        return value.isBlank() ? "apdus.txt" : value;
    }

    private static void stageInputFile(Path source, Path target) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        IOException lastFailure = null;
        String bestText = null;
        int bestScore = Integer.MIN_VALUE;
        for (Charset charset : INPUT_CHARSETS) {
            try {
                CharsetDecoder decoder = charset.newDecoder();
                String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
                int score = scoreDecodedText(text);
                if (score > bestScore) {
                    bestScore = score;
                    bestText = text;
                }
            } catch (CharacterCodingException ex) {
                lastFailure = ex;
            }
        }
        if (bestText != null) {
            Files.writeString(target, bestText, StandardCharsets.UTF_8);
            return;
        }
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (lastFailure != null) {
            throw new IOException("Input file could not be decoded using supported legacy encodings.", lastFailure);
        }
    }

    private static int scoreDecodedText(String text) {
        if (text == null || text.isBlank()) {
            return Integer.MIN_VALUE / 2;
        }
        int score = 0;
        score += countOccurrences(text, "ME ---->") * 10;
        score += countOccurrences(text, "ME <----") * 10;
        score += countOccurrences(text, "I0_USIM") * 8;
        score += countOccurrences(text, "I1_USIM") * 8;
        score += countOccurrences(text, "APDU") * 4;
        score += countOccurrences(text, "LSI") * 3;
        score += countOccurrences(text, "\uFFFD") * -20;
        score += countOccurrences(text, "\u0000") * -50;
        return score;
    }

    private static int countOccurrences(String text, String token) {
        if (text == null || text.isEmpty() || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String extensionOf(Path file) {
        if (file == null) {
            return "";
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int lineBreak = value.indexOf('\n');
        return lineBreak >= 0 ? value.substring(0, lineBreak).trim() : value.trim();
    }

    private record ParsedLegacyOutput(List<String> apdus, List<String> warnings) {
    }
}
