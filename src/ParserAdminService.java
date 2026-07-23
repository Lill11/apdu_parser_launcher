import apdu.parser.plugin.api.PluginDetectionResult;
import apdu.parser.plugin.api.PluginParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ParserAdminService {

    private final PluginStateStore stateStore;
    private final PluginJarInspector inspector;
    private final JavaSourcePluginBuilder sourceBuilder;
    private final LegacySourcePluginBuilder legacySourceBuilder;

    public ParserAdminService() {
        this(new PluginStateStore(), new PluginJarInspector(), new JavaSourcePluginBuilder(), new LegacySourcePluginBuilder());
    }

    ParserAdminService(PluginStateStore stateStore, PluginJarInspector inspector) {
        this(stateStore, inspector, new JavaSourcePluginBuilder(), new LegacySourcePluginBuilder());
    }

    ParserAdminService(PluginStateStore stateStore, PluginJarInspector inspector, JavaSourcePluginBuilder sourceBuilder) {
        this(stateStore, inspector, sourceBuilder, new LegacySourcePluginBuilder());
    }

    ParserAdminService(PluginStateStore stateStore, PluginJarInspector inspector, JavaSourcePluginBuilder sourceBuilder, LegacySourcePluginBuilder legacySourceBuilder) {
        this.stateStore = stateStore;
        this.inspector = inspector;
        this.sourceBuilder = sourceBuilder;
        this.legacySourceBuilder = legacySourceBuilder;
    }

    public List<ParserRuntimeDescriptor> listParsers() {
        return new LogParserRegistry(stateStore).listParsers();
    }

    public PluginValidationReport validatePlugin(Path jarPath) {
        return inspector.inspect(jarPath, existingParserIds(), true, null);
    }

    public PluginValidationReport inspectPlugin(Path jarPath) {
        return validatePlugin(jarPath);
    }

    public LegacyJavaSourceInspection inspectLegacySource(Path sourceFile) {
        return new LegacyJavaSourceInspector().inspect(sourceFile);
    }

    public ParserRuntimeDescriptor installPlugin(Path jarPath) throws IOException {
        PluginValidationReport report = inspector.inspect(jarPath, existingParserIds(), true, null);
        if (!report.success() || report.descriptor() == null) {
            throw new IOException(report.message());
        }

        ParserRuntimeDescriptor descriptor = report.descriptor();
        Path installDirectory = stateStore.installDirectory(descriptor.parserId());
        Files.createDirectories(installDirectory);
        Path targetJar = installDirectory.resolve("plugin.jar");
        Files.copy(jarPath, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        ParserInstallMetadata metadata = new ParserInstallMetadata(
                descriptor.parserId(),
                descriptor.name(),
                descriptor.version(),
                descriptor.pluginApiVersion(),
                descriptor.implementationClass(),
                descriptor.supportedExtensions(),
                ParserSourceType.PLUGIN_JAR,
                false,
                true,
                ParserValidationStatus.COMPATIBLE,
                "Compatible",
                installDirectory,
                targetJar,
                null,
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
                report.validatedAt()
        );
        stateStore.saveMetadata(metadata);
        return metadataToDescriptor(metadata);
    }

    public SourcePluginOperationResult installSource(Path sourceFile) throws IOException {
        SourcePluginBuildResult build = sourceBuilder.build(sourceFile, existingParserIds(), null);
        if (!build.success() || build.validationReport() == null || build.validationReport().descriptor() == null) {
            return new SourcePluginOperationResult(
                    false,
                    build.message(),
                    null,
                    build.status(),
                    build.diagnostics(),
                    build.compileLog(),
                    null,
                    build.compilerResolution(),
                    "",
                    "",
                    null,
                    0,
                    List.of()
            );
        }

        ParserRuntimeDescriptor descriptor = build.validationReport().descriptor();
        Path installDirectory = stateStore.installDirectory(descriptor.parserId());
        Files.createDirectories(installDirectory);
        Path targetJar = installDirectory.resolve("plugin.jar");
        Path sourceDirectory = installDirectory.resolve("source");
        Files.createDirectories(sourceDirectory);
        Path preservedSource = sourceDirectory.resolve(sourceFile.getFileName().toString());
        Path compileLogPath = installDirectory.resolve("compile.log");

        Files.copy(build.generatedJar(), targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sourceFile, preservedSource, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        writeCompileLog(compileLogPath, build.compileLog());

        ParserInstallMetadata metadata = new ParserInstallMetadata(
                descriptor.parserId(),
                descriptor.name(),
                descriptor.version(),
                descriptor.pluginApiVersion(),
                descriptor.implementationClass(),
                descriptor.supportedExtensions(),
                ParserSourceType.JAVA_SOURCE,
                false,
                true,
                ParserValidationStatus.COMPATIBLE,
                "Compatible",
                installDirectory,
                targetJar,
                preservedSource,
                sourceFile.toAbsolutePath().toString(),
                compileLogPath,
                "",
                "",
                "",
                build.compiledAt(),
                "SUCCESS",
                "Compilation succeeded.",
                null,
                "",
                "",
                "",
                Instant.now(),
                build.validationReport().validatedAt()
        );
        stateStore.saveMetadata(metadata);
        return new SourcePluginOperationResult(
                true,
                "Java source parser installed.",
                metadataToDescriptor(metadata),
                ParserValidationStatus.COMPATIBLE,
                build.diagnostics(),
                build.compileLog(),
                compileLogPath,
                build.compilerResolution(),
                "",
                "",
                null,
                0,
                List.of()
            );
    }

    public SourcePluginOperationResult installLegacySource(LegacyJavaExtractorInstallRequest request) throws IOException {
        LegacySourcePluginBuildResult build = legacySourceBuilder.build(request, existingParserIds(), null);
        if (!build.success() || build.validationReport() == null || build.validationReport().descriptor() == null || build.sourceSpec() == null) {
            return new SourcePluginOperationResult(
                    false,
                    build.message(),
                    null,
                    build.status(),
                    build.diagnostics(),
                    build.compileLog(),
                    null,
                    build.compilerResolution(),
                    "",
                    "",
                    null,
                    0,
                    List.of()
            );
        }

        LegacyExtractorExecutionResult testResult = LegacyJavaExtractorSupport.executeWithJar(
                request.sampleLog(),
                build.generatedJar(),
                build.sourceSpec().mainClassName(),
                request.commandPattern().name(),
                request.outputFileName()
        );
        if (!testResult.success()) {
            return new SourcePluginOperationResult(
                    false,
                    testResult.message(),
                    null,
                    ParserValidationStatus.INVALID_SOURCE,
                    testResult.warnings(),
                    build.compileLog(),
                    null,
                    build.compilerResolution(),
                    testResult.stdout(),
                    testResult.stderr(),
                    testResult.generatedOutputPath(),
                    testResult.apdus().size(),
                    testResult.warnings()
            );
        }

        ParserRuntimeDescriptor descriptor = build.validationReport().descriptor();
        Path installDirectory = stateStore.installDirectory(descriptor.parserId());
        Files.createDirectories(installDirectory);
        Path targetJar = installDirectory.resolve("plugin.jar");
        Path sourceDirectory = installDirectory.resolve("source");
        Files.createDirectories(sourceDirectory);
        Path preservedSource = sourceDirectory.resolve(request.sourceFile().getFileName().toString());
        Path compileLogPath = installDirectory.resolve("compile.log");

        Files.copy(build.generatedJar(), targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(request.sourceFile(), preservedSource, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        writeCompileLog(compileLogPath, build.compileLog());

        ParserInstallMetadata metadata = new ParserInstallMetadata(
                descriptor.parserId(),
                descriptor.name(),
                descriptor.version(),
                descriptor.pluginApiVersion(),
                descriptor.implementationClass(),
                descriptor.supportedExtensions(),
                ParserSourceType.LEGACY_JAVA_EXTRACTOR,
                false,
                true,
                ParserValidationStatus.COMPATIBLE,
                "Compatible",
                installDirectory,
                targetJar,
                preservedSource,
                request.sourceFile().toAbsolutePath().toString(),
                compileLogPath,
                build.sourceSpec().mainClassName(),
                request.commandPattern().name(),
                request.outputFileName(),
                build.compiledAt(),
                "SUCCESS",
                "Compilation succeeded.",
                Instant.now(),
                "SUCCESS",
                testResult.message(),
                testResult.stderr(),
                Instant.now(),
                build.validationReport().validatedAt()
        );
        stateStore.saveMetadata(metadata);
        return new SourcePluginOperationResult(
                true,
                "Legacy Java extractor installed.",
                metadataToDescriptor(metadata),
                ParserValidationStatus.COMPATIBLE,
                build.diagnostics(),
                build.compileLog(),
                compileLogPath,
                build.compilerResolution(),
                testResult.stdout(),
                testResult.stderr(),
                testResult.generatedOutputPath(),
                testResult.apdus().size(),
                testResult.warnings()
        );
    }

    public SourcePluginOperationResult recompileSource(String parserId) throws IOException {
        ParserRuntimeDescriptor current = listParsers().stream()
                .filter(parser -> parser.parserId().equals(parserId))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown parser ID: " + parserId));
        if (current.builtIn()) {
            throw new IOException("Built-in parsers cannot be recompiled.");
        }
        if (current.sourceType() != ParserSourceType.JAVA_SOURCE && current.sourceType() != ParserSourceType.LEGACY_JAVA_EXTRACTOR) {
            throw new IOException("Only Java source parsers can be recompiled.");
        }
        if (current.preservedSourceFile() == null || !Files.isRegularFile(current.preservedSourceFile())) {
            throw new IOException("Preserved source file is missing for parser '" + parserId + "'.");
        }

        ParserInstallMetadata existing = stateStore.loadMetadata(current.installDirectory());
        Path compileLogPath = existing.compileLogPath() == null ? existing.installDirectory().resolve("compile.log") : existing.compileLogPath();
        if (current.sourceType() == ParserSourceType.LEGACY_JAVA_EXTRACTOR) {
            LegacyJavaExtractorInstallRequest request = new LegacyJavaExtractorInstallRequest(
                    existing.preservedSourceFile(),
                    existing.name(),
                    existing.parserId(),
                    existing.version(),
                    existing.supportedExtensions(),
                    LegacyCommandPattern.fromWireValue(existing.legacyCommandPattern()),
                    existing.legacyOutputFileName(),
                    existing.preservedSourceFile()
            );
            LegacySourcePluginBuildResult build = legacySourceBuilder.build(request, existingParserIds(), parserId);
            writeCompileLog(compileLogPath, build.compileLog());
            if (!build.success() || build.validationReport() == null || build.validationReport().descriptor() == null || build.sourceSpec() == null) {
                ParserInstallMetadata updated = existing.withCompilationResult(
                        existing.pluginJar(),
                        existing.preservedSourceFile(),
                        existing.originalSourcePath(),
                        compileLogPath,
                        build.compiledAt(),
                        "FAILED",
                        build.message(),
                        existing.lastTestedAt(),
                        existing.lastTestStatus(),
                        existing.lastTestMessage(),
                        existing.lastTestStderr(),
                        existing.lastValidatedAt(),
                        existing.validationStatus(),
                        existing.validationMessage(),
                        existing.sourceType(),
                        existing.implementationClass(),
                        existing.supportedExtensions(),
                        existing.pluginApiVersion(),
                        existing.name(),
                        existing.version(),
                        existing.legacyMainClass(),
                        existing.legacyCommandPattern(),
                        existing.legacyOutputFileName()
                );
                stateStore.saveMetadata(updated);
                return new SourcePluginOperationResult(
                        false,
                        build.message(),
                        metadataToDescriptor(updated),
                        build.status(),
                        build.diagnostics(),
                        build.compileLog(),
                        compileLogPath,
                        build.compilerResolution(),
                        "",
                        "",
                        null,
                        0,
                        List.of()
                );
            }

            ParserRuntimeDescriptor descriptor = build.validationReport().descriptor();
            Files.copy(build.generatedJar(), existing.pluginJar(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ParserInstallMetadata updated = existing.withCompilationResult(
                    existing.pluginJar(),
                    existing.preservedSourceFile(),
                    existing.originalSourcePath(),
                    compileLogPath,
                    build.compiledAt(),
                    "SUCCESS",
                    "Compilation succeeded.",
                    existing.lastTestedAt(),
                    existing.lastTestStatus(),
                    existing.lastTestMessage(),
                    existing.lastTestStderr(),
                    build.validationReport().validatedAt(),
                    ParserValidationStatus.COMPATIBLE,
                    "Compatible",
                    ParserSourceType.LEGACY_JAVA_EXTRACTOR,
                    descriptor.implementationClass(),
                    descriptor.supportedExtensions(),
                    descriptor.pluginApiVersion(),
                    descriptor.name(),
                    descriptor.version(),
                    build.sourceSpec().mainClassName(),
                    existing.legacyCommandPattern(),
                    existing.legacyOutputFileName()
            );
            stateStore.saveMetadata(updated);
            return new SourcePluginOperationResult(
                    true,
                    "Legacy Java extractor recompiled.",
                    metadataToDescriptor(updated),
                    ParserValidationStatus.COMPATIBLE,
                    build.diagnostics(),
                    build.compileLog(),
                    compileLogPath,
                    build.compilerResolution(),
                    "",
                    "",
                    null,
                    0,
                    List.of()
            );
        }

        SourcePluginBuildResult build = sourceBuilder.build(existing.preservedSourceFile(), existingParserIds(), parserId);
        writeCompileLog(compileLogPath, build.compileLog());
        if (!build.success() || build.validationReport() == null || build.validationReport().descriptor() == null) {
            ParserInstallMetadata updated = existing.withCompilationResult(
                    existing.pluginJar(),
                    existing.preservedSourceFile(),
                    existing.originalSourcePath(),
                    compileLogPath,
                    build.compiledAt(),
                    "FAILED",
                    build.message(),
                    existing.lastTestedAt(),
                    existing.lastTestStatus(),
                    existing.lastTestMessage(),
                    existing.lastTestStderr(),
                    existing.lastValidatedAt(),
                    existing.validationStatus(),
                    existing.validationMessage(),
                    existing.sourceType(),
                    existing.implementationClass(),
                    existing.supportedExtensions(),
                    existing.pluginApiVersion(),
                    existing.name(),
                    existing.version(),
                    existing.legacyMainClass(),
                    existing.legacyCommandPattern(),
                    existing.legacyOutputFileName()
            );
            stateStore.saveMetadata(updated);
            return new SourcePluginOperationResult(
                    false,
                    build.message(),
                    metadataToDescriptor(updated),
                    build.status(),
                    build.diagnostics(),
                    build.compileLog(),
                    compileLogPath,
                    build.compilerResolution(),
                    "",
                    "",
                    null,
                    0,
                    List.of()
            );
        }

        ParserRuntimeDescriptor descriptor = build.validationReport().descriptor();
        Files.copy(build.generatedJar(), existing.pluginJar(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ParserInstallMetadata updated = existing.withCompilationResult(
                existing.pluginJar(),
                existing.preservedSourceFile(),
                existing.originalSourcePath(),
                compileLogPath,
                build.compiledAt(),
                "SUCCESS",
                "Compilation succeeded.",
                existing.lastTestedAt(),
                existing.lastTestStatus(),
                existing.lastTestMessage(),
                existing.lastTestStderr(),
                build.validationReport().validatedAt(),
                ParserValidationStatus.COMPATIBLE,
                "Compatible",
                ParserSourceType.JAVA_SOURCE,
                descriptor.implementationClass(),
                descriptor.supportedExtensions(),
                descriptor.pluginApiVersion(),
                descriptor.name(),
                descriptor.version(),
                existing.legacyMainClass(),
                existing.legacyCommandPattern(),
                existing.legacyOutputFileName()
        );
        stateStore.saveMetadata(updated);
        return new SourcePluginOperationResult(
                true,
                "Java source parser recompiled.",
                metadataToDescriptor(updated),
                ParserValidationStatus.COMPATIBLE,
                build.diagnostics(),
                build.compileLog(),
                compileLogPath,
                build.compilerResolution(),
                "",
                "",
                null,
                0,
                List.of()
        );
    }

    public ParserRuntimeDescriptor setEnabled(String parserId, boolean enabled) throws IOException {
        List<ParserRuntimeDescriptor> current = listParsers();
        ParserRuntimeDescriptor target = current.stream()
                .filter(parser -> parser.parserId().equals(parserId))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown parser ID: " + parserId));

        if (target.builtIn()) {
            Map<String, Boolean> states = stateStore.loadBuiltInStates();
            states.put(parserId, enabled);
            stateStore.saveBuiltInStates(states);
            return new LogParserRegistry(stateStore).findById(parserId)
                    .orElseThrow(() -> new IOException("Failed to reload parser after updating state."));
        }

        ParserInstallMetadata metadata = stateStore.loadMetadata(target.installDirectory());
        ParserInstallMetadata updated = metadata.withEnabled(enabled);
        stateStore.saveMetadata(updated);
        return new LogParserRegistry(stateStore).findById(parserId)
                .orElseThrow(() -> new IOException("Failed to reload parser after updating state."));
    }

    public void removePlugin(String parserId) throws IOException {
        ParserRuntimeDescriptor target = listParsers().stream()
                .filter(parser -> parser.parserId().equals(parserId))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown parser ID: " + parserId));
        if (target.builtIn()) {
            throw new IOException("Built-in parsers cannot be removed.");
        }
        stateStore.removeInstalledPlugin(parserId);
    }

    public ParserTestResult testParser(String parserId, Path inputFile) throws IOException {
        if (inputFile == null || !Files.isRegularFile(inputFile)) {
            throw new IOException("Input file does not exist or is not a regular file.");
        }
        LogParserRegistry registry = new LogParserRegistry(stateStore);
        ParserRuntimeDescriptor descriptor = registry.findById(parserId)
                .orElseThrow(() -> new IOException("Unknown parser ID: " + parserId));
        long started = System.nanoTime();
        try {
            if (descriptor.sourceType() == ParserSourceType.LEGACY_JAVA_EXTRACTOR) {
                ParserInstallMetadata metadata = stateStore.loadMetadata(descriptor.installDirectory());
                LegacyExtractorExecutionResult execution = LegacyJavaExtractorSupport.executeWithJar(
                        inputFile,
                        descriptor.pluginJar(),
                        metadata.legacyMainClass(),
                        metadata.legacyCommandPattern(),
                        metadata.legacyOutputFileName()
                );
                ParserInstallMetadata updated = existingWithLatestTest(metadata, execution);
                stateStore.saveMetadata(updated);
                return new ParserTestResult(
                        metadataToDescriptor(updated),
                        execution.success(),
                        execution.success() ? 88 : 0,
                        execution.message(),
                        execution.apdus().size(),
                        execution.warnings(),
                        execution.success() ? List.of() : List.of(execution.message()),
                        elapsedMs(started),
                        execution.success() ? "completed" : "failed",
                        execution.exitCode(),
                        execution.stdout(),
                        execution.stderr(),
                        execution.generatedOutputPath()
                );
            }
            byte[] sample = Files.readAllBytes(inputFile);
            PluginDetectionResult detection = descriptor.canParticipateInDetection()
                    ? descriptor.detect(inputFile, sample)
                    : apdu.parser.plugin.api.PluginDetectionResult.noMatch("Parser is disabled or invalid.");
            if (!descriptor.canParticipateInDetection()) {
                return new ParserTestResult(descriptor, false, 0, detection.reason(), 0, List.of(), List.of(),
                        elapsedMs(started), "disabled", 0, "", "", null);
            }
            PluginParseResult parsed = descriptor.parse(inputFile);
            List<ApduOutputAnalyzer.AnalysisItem> analysis = ApduOutputAnalyzer.analyzeEntries(inputFile, parsed.apdus());
            List<String> warnings = parsed.warnings();
            List<String> errors = new ArrayList<>();
            return new ParserTestResult(
                    descriptor,
                    detection.matched(),
                    detection.confidence(),
                    detection.reason(),
                    parsed.apdus().size(),
                    warnings,
                    errors,
                    elapsedMs(started),
                    "completed",
                    0,
                    "",
                    "",
                    null
            );
        } catch (Exception ex) {
            return new ParserTestResult(
                    descriptor,
                    false,
                    0,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                    0,
                    List.of(),
                    List.of(ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                    elapsedMs(started),
                    "failed",
                    -1,
                    "",
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                    null
            );
        }
    }

    private Set<String> existingParserIds() {
        return listParsers().stream()
                .map(ParserRuntimeDescriptor::parserId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static void writeCompileLog(Path compileLogPath, String text) throws IOException {
        if (compileLogPath == null) {
            return;
        }
        Files.createDirectories(compileLogPath.getParent());
        Files.writeString(compileLogPath, text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static ParserRuntimeDescriptor metadataToDescriptor(ParserInstallMetadata metadata) {
        return new ParserRuntimeDescriptor(
                metadata.parserId(),
                metadata.name(),
                metadata.version(),
                metadata.pluginApiVersion(),
                metadata.implementationClass(),
                metadata.supportedExtensions(),
                metadata.sourceType(),
                metadata.builtIn(),
                metadata.enabled(),
                metadata.validationStatus(),
                metadata.validationMessage(),
                metadata.installDirectory(),
                metadata.pluginJar(),
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
                metadata.lastValidatedAt(),
                metadata.builtIn() ? 100 : 200,
                null
        );
    }

    private static ParserInstallMetadata existingWithLatestTest(ParserInstallMetadata metadata, LegacyExtractorExecutionResult execution) {
        return new ParserInstallMetadata(
                metadata.parserId(),
                metadata.name(),
                metadata.version(),
                metadata.pluginApiVersion(),
                metadata.implementationClass(),
                metadata.supportedExtensions(),
                metadata.sourceType(),
                metadata.builtIn(),
                metadata.enabled(),
                metadata.validationStatus(),
                metadata.validationMessage(),
                metadata.installDirectory(),
                metadata.pluginJar(),
                metadata.preservedSourceFile(),
                metadata.originalSourcePath(),
                metadata.compileLogPath(),
                metadata.legacyMainClass(),
                metadata.legacyCommandPattern(),
                metadata.legacyOutputFileName(),
                metadata.lastCompiledAt(),
                metadata.lastCompilationStatus(),
                metadata.lastCompilationMessage(),
                Instant.now(),
                execution.success() ? "SUCCESS" : "FAILED",
                execution.message(),
                execution.stderr(),
                metadata.installedAt(),
                metadata.lastValidatedAt()
        );
    }

    public record ParserTestResult(
            ParserRuntimeDescriptor parser,
            boolean matched,
            int confidence,
            String reason,
            int apduCount,
            List<String> warnings,
            List<String> errors,
            long elapsedMs,
            String status,
            int exitCode,
            String stdout,
            String stderr,
            Path outputPath
    ) {
        public ParserTestResult {
            reason = reason == null ? "" : reason;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            errors = errors == null ? List.of() : List.copyOf(errors);
            status = status == null ? "" : status.toLowerCase(Locale.ROOT);
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }

    public record SourcePluginOperationResult(
            boolean success,
            String message,
            ParserRuntimeDescriptor parser,
            ParserValidationStatus status,
            List<String> diagnostics,
            String compileLog,
            Path compileLogPath,
            CompilerResolution compilerResolution,
            String stdout,
            String stderr,
            Path generatedOutputPath,
            int apduCount,
            List<String> warnings
    ) {
        public SourcePluginOperationResult {
            message = message == null ? "" : message;
            status = status == null ? ParserValidationStatus.COMPILATION_FAILED : status;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            compileLog = compileLog == null ? "" : compileLog;
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
