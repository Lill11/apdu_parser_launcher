import apdu.parser.plugin.api.ApduParserPlugin;
import apdu.parser.plugin.api.PluginConstants;
import apdu.parser.plugin.api.PluginDetectionResult;
import apdu.parser.plugin.api.PluginParseResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class LogParserRegistry {

    private static final int SAMPLE_BYTES = 64 * 1024;

    private final List<ParserRuntimeDescriptor> parsers;
    private final PluginStateStore stateStore;

    public LogParserRegistry() {
        this(new PluginStateStore());
    }

    LogParserRegistry(List<LogParser> legacyParsers) {
        this.parsers = adaptLegacyParsers(legacyParsers, Map.of());
        this.stateStore = new PluginStateStore();
    }

    public LogParserRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore == null ? new PluginStateStore() : stateStore;
        this.parsers = loadParsers();
    }

    public List<LogParser> getParsers() {
        return parsers.stream()
                .filter(ParserRuntimeDescriptor::canParticipateInDetection)
                .map(ParserBackedLogParser::new)
                .map(LogParser.class::cast)
                .toList();
    }

    public List<ParserRuntimeDescriptor> listParsers() {
        return List.copyOf(parsers);
    }

    public Optional<ParserRuntimeDescriptor> findById(String parserId) {
        return parsers.stream().filter(parser -> parser.parserId().equals(parserId)).findFirst();
    }

    public DetectionResult detect(Path file) throws IOException {
        String extension = extensionOf(file);
        byte[] sampleBytes = readSampleBytes(file);
        String sampleText = new String(sampleBytes, StandardCharsets.ISO_8859_1);

        List<DetectedMatch> matches = new ArrayList<>();
        for (ParserRuntimeDescriptor parser : parsers) {
            if (!parser.canParticipateInDetection()) {
                continue;
            }
            try {
                PluginDetectionResult result = parser.detect(file, sampleBytes);
                if (result != null && result.matched()) {
                    boolean extensionMatched = parser.supportedExtensions().contains(extension);
                    int adjustedConfidence = result.confidence() + (extensionMatched ? 5 : 0);
                    matches.add(new DetectedMatch(parser, extensionMatched, adjustedConfidence, result.reason()));
                }
            } catch (Exception ex) {
                // Broken plugin detection should not stop other parsers.
            }
        }

        if (matches.isEmpty()) {
            return new DetectionResult(null, extension, sampleText, false, false, List.of(), "", 0);
        }

        matches.sort(Comparator
                .comparingInt(DetectedMatch::confidence).reversed()
                .thenComparingInt(match -> match.parser.priority())
                .thenComparing(match -> match.parser.parserId()));

        DetectedMatch winner = matches.get(0);
        List<DetectedMatch> sameScore = matches.stream()
                .filter(match -> match.confidence() == winner.confidence() && match.parser.priority() == winner.parser.priority())
                .toList();

        if (sameScore.size() > 1) {
            List<String> conflicting = sameScore.stream().map(match -> match.parser.parserId()).toList();
            return new DetectionResult(null, extension, sampleText, false, true, conflicting,
                    "Multiple parsers matched with the same confidence.", winner.confidence());
        }

        return new DetectionResult(
                new ParserBackedLogParser(winner.parser),
                extension,
                sampleText,
                winner.extensionMatched,
                false,
                List.of(),
                winner.reason,
                winner.confidence
        );
    }

    static String extensionOf(Path file) {
        String name = file == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private List<ParserRuntimeDescriptor> loadParsers() {
        Map<String, Boolean> builtInStates;
        List<ParserInstallMetadata> installedMetadata;
        try {
            builtInStates = stateStore.loadBuiltInStates();
            installedMetadata = stateStore.loadInstalledMetadata();
        } catch (IOException ex) {
            builtInStates = Map.of();
            installedMetadata = List.of();
        }

        List<ParserRuntimeDescriptor> results = new ArrayList<>();
        Map<String, ParserRuntimeDescriptor> byId = new LinkedHashMap<>();

        for (ParserRuntimeDescriptor parser : adaptLegacyParsers(InternalLogParsers.createDefaultParsers(), builtInStates)) {
            byId.put(parser.parserId(), parser);
            results.add(parser);
        }

        PluginJarInspector inspector = new PluginJarInspector();
        for (ParserInstallMetadata metadata : installedMetadata) {
            Path jarPath = metadata.pluginJar();
            Set<String> existingIds = byId.keySet();
            PluginValidationReport report = inspector.inspect(jarPath, existingIds, metadata.enabled(), metadata.installDirectory());
            ParserRuntimeDescriptor descriptor;
            if (report.descriptor() != null) {
                descriptor = new ParserRuntimeDescriptor(
                        report.descriptor().parserId(),
                        report.descriptor().name(),
                        report.descriptor().version(),
                        report.descriptor().pluginApiVersion(),
                        report.descriptor().implementationClass(),
                        report.descriptor().supportedExtensions(),
                        metadata.sourceType(),
                        false,
                        metadata.enabled(),
                        metadata.enabled() ? report.status() : ParserValidationStatus.DISABLED,
                        metadata.enabled() ? report.message() : "Disabled",
                        metadata.installDirectory(),
                        jarPath,
                        metadata.preservedSourceFile(),
                        metadata.originalSourcePath(),
                        metadata.compileLogPath(),
                        metadata.legacyMainClass(),
                        metadata.legacyCommandPattern(),
                        metadata.legacyOutputFileName(),
                        metadata.lastCompiledAt(),
                        metadata.lastCompilationStatus(),
                        metadata.lastCompilationMessage(),
                        metadata.lastTestedAt(),
                        metadata.lastTestStatus(),
                        metadata.lastTestMessage(),
                        metadata.lastTestStderr(),
                        metadata.installedAt(),
                        report.validatedAt(),
                        200,
                        report.descriptor().plugin()
                );
            } else {
                descriptor = new ParserRuntimeDescriptor(
                        metadata.parserId(),
                        metadata.name(),
                        metadata.version(),
                        metadata.pluginApiVersion(),
                        metadata.implementationClass(),
                        metadata.supportedExtensions(),
                        metadata.sourceType(),
                        false,
                        metadata.enabled(),
                        metadata.enabled() ? report.status() : ParserValidationStatus.DISABLED,
                        report.message(),
                        metadata.installDirectory(),
                        jarPath,
                        metadata.preservedSourceFile(),
                        metadata.originalSourcePath(),
                        metadata.compileLogPath(),
                        metadata.legacyMainClass(),
                        metadata.legacyCommandPattern(),
                        metadata.legacyOutputFileName(),
                        metadata.lastCompiledAt(),
                        metadata.lastCompilationStatus(),
                        metadata.lastCompilationMessage(),
                        metadata.lastTestedAt(),
                        metadata.lastTestStatus(),
                        metadata.lastTestMessage(),
                        metadata.lastTestStderr(),
                        metadata.installedAt(),
                        report.validatedAt(),
                        200,
                        null
                );
            }
            results.add(descriptor);
            if (!descriptor.parserId().isBlank() && !byId.containsKey(descriptor.parserId())) {
                byId.put(descriptor.parserId(), descriptor);
            }
        }

        return List.copyOf(results);
    }

    private static List<ParserRuntimeDescriptor> adaptLegacyParsers(List<LogParser> legacyParsers, Map<String, Boolean> builtInStates) {
        List<ParserRuntimeDescriptor> adapted = new ArrayList<>();
        for (LogParser parser : legacyParsers) {
            boolean enabled = builtInStates.getOrDefault(parser.getId(), true);
            ParserValidationStatus status = enabled ? ParserValidationStatus.COMPATIBLE : ParserValidationStatus.DISABLED;
            adapted.add(new ParserRuntimeDescriptor(
                    parser.getId(),
                    parser.getDisplayName(),
                    "1.0.0",
                    PluginConstants.CURRENT_PLUGIN_API_VERSION,
                    parser.getClass().getName(),
                    parser.getSupportedExtensions(),
                    ParserSourceType.BUILT_IN,
                    true,
                    enabled,
                    status,
                    enabled ? "Compatible" : "Disabled",
                    null,
                    null,
                    null,
                    "",
                    null,
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
                    null,
                    Instant.now(),
                    100,
                    new LegacyParserPluginAdapter(parser)
            ));
        }
        return List.copyOf(adapted);
    }

    private static byte[] readSampleBytes(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return new byte[0];
        }
        byte[] buffer = new byte[SAMPLE_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            return Arrays.copyOf(buffer, read);
        }
    }

    public record DetectionResult(
            LogParser parser,
            String extension,
            String sampleContent,
            boolean extensionMatched,
            boolean ambiguous,
            List<String> conflictingParserIds,
            String reason,
            int confidence
    ) {
        public boolean supported() {
            return parser != null;
        }

        public String parserId() {
            return parser == null ? "" : parser.getId();
        }

        public String displayName() {
            return parser == null ? "Unsupported" : parser.getDisplayName();
        }
    }

    private record DetectedMatch(ParserRuntimeDescriptor parser, boolean extensionMatched, int confidence, String reason) {
    }

    private static final class LegacyParserPluginAdapter implements ApduParserPlugin {
        private final LogParser delegate;

        private LegacyParserPluginAdapter(LogParser delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public String getName() {
            return delegate.getDisplayName();
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public int getPluginApiVersion() {
            return PluginConstants.CURRENT_PLUGIN_API_VERSION;
        }

        @Override
        public List<String> getSupportedExtensions() {
            return delegate.getSupportedExtensions();
        }

        @Override
        public PluginDetectionResult detect(Path inputFile, byte[] sample) {
            String sampleContent = sample == null ? "" : new String(sample, StandardCharsets.ISO_8859_1);
            boolean matched = delegate.supports(inputFile, sampleContent);
            int confidence = delegate.getSupportedExtensions().contains(extensionOf(inputFile)) ? 95 : 70;
            return matched ? PluginDetectionResult.matched(confidence, "Matched built-in parser heuristic.")
                    : PluginDetectionResult.noMatch("No built-in parser match.");
        }

        @Override
        public PluginParseResult parse(Path inputFile) throws Exception {
            LogParser.ParseResult parsed = delegate.parse(inputFile);
            return new PluginParseResult(parsed.apdus(), parsed.warnings());
        }
    }

    private static final class ParserBackedLogParser implements LogParser {
        private final ParserRuntimeDescriptor descriptor;

        private ParserBackedLogParser(ParserRuntimeDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public String getId() {
            return descriptor.parserId();
        }

        @Override
        public String getDisplayName() {
            return descriptor.name();
        }

        @Override
        public List<String> getSupportedExtensions() {
            return descriptor.supportedExtensions();
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            try {
                PluginDetectionResult result = descriptor.detect(file,
                        sampleContent == null ? new byte[0] : sampleContent.getBytes(StandardCharsets.ISO_8859_1));
                return result != null && result.matched();
            } catch (Exception ex) {
                return false;
            }
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            try {
                PluginParseResult parsed = descriptor.parse(inputFile);
                return new ParseResult(parsed.apdus(), parsed.warnings());
            } catch (IOException ioException) {
                throw ioException;
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage() == null ? ex.toString() : ex.getMessage(), ex);
            }
        }
    }
}
