import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class LegacySourcePluginBuilder {

    private final LegacyJavaSourceInspector sourceInspector = new LegacyJavaSourceInspector();
    private final PluginJarInspector jarInspector = new PluginJarInspector();

    public LegacySourcePluginBuildResult build(
            LegacyJavaExtractorInstallRequest request,
            Set<String> existingParserIds,
            String allowedExistingParserId
    ) {
        Instant compiledAt = Instant.now();
        CompilerResolution compiler = CompilerResolution.resolve();
        if (!compiler.resolved()) {
            return new LegacySourcePluginBuildResult(false, ParserValidationStatus.MISSING_COMPILER, compiler.message(),
                    null, null, null, compiler, compiledAt, List.of(compiler.message()), compiler.message());
        }

        if (request == null) {
            return new LegacySourcePluginBuildResult(false, ParserValidationStatus.INVALID_SOURCE, "Legacy extractor request is missing.",
                    null, null, null, compiler, compiledAt, List.of("Missing install request."), "Missing install request.");
        }
        if (request.parserName().isBlank() || request.parserId().isBlank() || request.supportedExtensions().isEmpty()) {
            return new LegacySourcePluginBuildResult(false, ParserValidationStatus.INVALID_SOURCE,
                    "Parser name, parser ID, and supported extensions are required.",
                    null, null, null, compiler, compiledAt, List.of("Missing parser metadata."), "Missing parser metadata.");
        }
        if (request.sampleLog() == null || !Files.isRegularFile(request.sampleLog())) {
            return new LegacySourcePluginBuildResult(false, ParserValidationStatus.INVALID_SOURCE,
                    "A sample log is required to validate a legacy extractor.",
                    null, null, null, compiler, compiledAt, List.of("Sample log is missing."), "Sample log is missing.");
        }

        LegacyJavaSourceInspection inspection = sourceInspector.inspect(request.sourceFile());
        if (!inspection.success() || inspection.spec() == null) {
            return new LegacySourcePluginBuildResult(false, inspection.status(), inspection.message(),
                    null, null, inspection.spec(), compiler, compiledAt, inspection.diagnostics(),
                    String.join(System.lineSeparator(), inspection.diagnostics()));
        }

        Path tempRoot = null;
        try {
            AppEnvironment.ensureBaseLayout();
            tempRoot = Files.createTempDirectory(AppEnvironment.tempDir(), "legacy-source-build-");
            Path stagedSource = tempRoot.resolve(request.sourceFile().getFileName().toString());
            Files.copy(request.sourceFile(), stagedSource, StandardCopyOption.REPLACE_EXISTING);

            Path generatedSourceDir = tempRoot.resolve("generated");
            Files.createDirectories(generatedSourceDir);
            String wrapperClassName = "LegacyWrapper_" + sanitizeClassSuffix(request.parserId());
            String wrapperSource = buildWrapperSource(wrapperClassName, inspection.spec(), request);
            Path wrapperFile = generatedSourceDir.resolve(wrapperClassName + ".java");
            Files.writeString(wrapperFile, wrapperSource, StandardCharsets.UTF_8);

            Path classesDir = tempRoot.resolve("classes");
            Files.createDirectories(classesDir);
            CompileExecution compile = compileSources(compiler.compilerPath(), classesDir, stagedSource, wrapperFile);
            if (compile.exitCode() != 0) {
                return new LegacySourcePluginBuildResult(false, ParserValidationStatus.COMPILATION_FAILED,
                        "Legacy Java extractor compilation failed.", null, null, inspection.spec(), compiler, compiledAt,
                        compile.diagnostics(), compile.log());
            }

            String wrapperImplementation = wrapperClassName;
            Path servicesFile = classesDir.resolve("META-INF").resolve("services").resolve("apdu.parser.plugin.api.ApduParserPlugin");
            Files.createDirectories(servicesFile.getParent());
            Files.writeString(servicesFile, wrapperImplementation + System.lineSeparator(), StandardCharsets.UTF_8);

            Path pluginMetadata = classesDir.resolve("META-INF").resolve("apdu-parser-plugin.json");
            Files.writeString(pluginMetadata, SimpleJsonWriter.write(java.util.Map.of(
                    "pluginApiVersion", apdu.parser.plugin.api.PluginConstants.CURRENT_PLUGIN_API_VERSION,
                    "implementationClass", wrapperImplementation,
                    "legacyMainClass", inspection.spec().mainClassName(),
                    "generatedAt", compiledAt.toString()
            )), StandardCharsets.UTF_8);

            Path generatedJar = tempRoot.resolve("plugin.jar");
            createJar(generatedJar, classesDir);
            Path persistedJar = Files.createTempFile(AppEnvironment.tempDir(), "legacy-source-plugin-", ".jar");
            Files.copy(generatedJar, persistedJar, StandardCopyOption.REPLACE_EXISTING);

            Set<String> ids = new java.util.LinkedHashSet<>(existingParserIds);
            if (allowedExistingParserId != null && !allowedExistingParserId.isBlank()) {
                ids.remove(allowedExistingParserId);
            }
            PluginValidationReport validation = jarInspector.inspect(persistedJar, ids, true, null);
            if (!validation.success()) {
                List<String> diagnostics = new ArrayList<>(compile.diagnostics());
                diagnostics.addAll(validation.diagnostics());
                return new LegacySourcePluginBuildResult(false, validation.status(), validation.message(),
                        persistedJar, validation, inspection.spec(), compiler, compiledAt, diagnostics,
                        compile.log() + System.lineSeparator() + String.join(System.lineSeparator(), validation.diagnostics()));
            }
            return new LegacySourcePluginBuildResult(true, ParserValidationStatus.COMPATIBLE,
                    "Legacy Java extractor compiled successfully.", persistedJar, validation, inspection.spec(),
                    compiler, compiledAt, compile.diagnostics(), compile.log());
        } catch (IOException ex) {
            return new LegacySourcePluginBuildResult(false, ParserValidationStatus.COMPILATION_FAILED,
                    "Failed to build legacy Java extractor.", null, null, inspection.spec(), compiler, compiledAt,
                    List.of(ex.toString()), ex.toString());
        } finally {
            if (tempRoot != null) {
                try {
                    ApduParserProcessor.deleteDirectoryIfExists(tempRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static CompileExecution compileSources(Path javacPath, Path classesDir, Path... sourceFiles) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javacPath.toString());
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-cp");
        command.add(AppEnvironment.parserJarOrClassesPath().toAbsolutePath().toString());
        command.add("-d");
        command.add(classesDir.toAbsolutePath().toString());
        for (Path sourceFile : sourceFiles) {
            command.add(sourceFile.toAbsolutePath().toString());
        }
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            return new CompileExecution(-1, List.of(ex.toString()), ex.toString());
        }
        try {
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            List<String> diagnostics = new ArrayList<>();
            if (exitCode != 0 && !stdout.isBlank()) {
                diagnostics.addAll(List.of(stdout.strip().split("\\R")));
            }
            if (exitCode != 0 && !stderr.isBlank()) {
                diagnostics.addAll(List.of(stderr.strip().split("\\R")));
            }
            String log = "Command: " + String.join(" ", command) + System.lineSeparator()
                    + "Exit code: " + exitCode;
            if (exitCode != 0 && !stdout.isBlank()) {
                log += System.lineSeparator() + "STDOUT:" + System.lineSeparator() + stdout;
            }
            if (exitCode != 0 && !stderr.isBlank()) {
                log += System.lineSeparator() + "STDERR:" + System.lineSeparator() + stderr;
            }
            return new CompileExecution(exitCode, exitCode == 0 ? List.of() : diagnostics, log.strip());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CompileExecution(-1, List.of("Compilation interrupted."), "Compilation interrupted.");
        }
    }

    private static void createJar(Path jarPath, Path classesDir) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out)) {
            Files.walk(classesDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                            jar.putNextEntry(new JarEntry(entryName));
                            jar.write(Files.readAllBytes(file));
                            jar.closeEntry();
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }

    private static String buildWrapperSource(
            String wrapperClassName,
            LegacyJavaSourceSpec spec,
            LegacyJavaExtractorInstallRequest request
    ) {
        String extensions = request.supportedExtensions().stream()
                .map(value -> "\"" + ApduParserProcessor.escapeJson(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        return """
                import apdu.parser.plugin.api.ApduParserPlugin;
                import apdu.parser.plugin.api.PluginConstants;
                import apdu.parser.plugin.api.PluginDetectionResult;
                import apdu.parser.plugin.api.PluginParseResult;

                import java.nio.file.Path;
                import java.util.List;

                public final class %s implements ApduParserPlugin {
                    @Override
                    public String getId() { return "%s"; }

                    @Override
                    public String getName() { return "%s"; }

                    @Override
                    public String getVersion() { return "%s"; }

                    @Override
                    public int getPluginApiVersion() { return PluginConstants.CURRENT_PLUGIN_API_VERSION; }

                    @Override
                    public List<String> getSupportedExtensions() { return List.of(%s); }

                    @Override
                    public PluginDetectionResult detect(Path inputFile, byte[] sample) throws Exception {
                        return LegacyJavaExtractorSupport.detect(
                                inputFile,
                                %s.class,
                                getSupportedExtensions(),
                                "%s",
                                "%s",
                                "%s"
                        );
                    }

                    @Override
                    public PluginParseResult parse(Path inputFile) throws Exception {
                        return LegacyJavaExtractorSupport.parse(
                                inputFile,
                                %s.class,
                                "%s",
                                "%s",
                                "%s"
                        );
                    }
                }
                """.formatted(
                wrapperClassName,
                ApduParserProcessor.escapeJson(request.parserId()),
                ApduParserProcessor.escapeJson(request.parserName()),
                ApduParserProcessor.escapeJson(request.version()),
                extensions,
                wrapperClassName,
                ApduParserProcessor.escapeJson(spec.mainClassName()),
                request.commandPattern().name(),
                ApduParserProcessor.escapeJson(request.outputFileName()),
                wrapperClassName,
                ApduParserProcessor.escapeJson(spec.mainClassName()),
                request.commandPattern().name(),
                ApduParserProcessor.escapeJson(request.outputFileName())
        );
    }

    private static String sanitizeClassSuffix(String parserId) {
        StringBuilder sb = new StringBuilder();
        for (char value : parserId.toCharArray()) {
            if (Character.isLetterOrDigit(value)) {
                sb.append(value);
            } else {
                sb.append('_');
            }
        }
        String sanitized = sb.toString();
        if (sanitized.isBlank()) {
            return "Parser";
        }
        if (!Character.isLetter(sanitized.charAt(0)) && sanitized.charAt(0) != '_') {
            return "_" + sanitized;
        }
        return sanitized;
    }

    private record CompileExecution(int exitCode, List<String> diagnostics, String log) {
    }
}
