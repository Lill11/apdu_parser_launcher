import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class JavaSourcePluginBuilder {

    private final JavaSourcePluginInspector sourceInspector = new JavaSourcePluginInspector();
    private final PluginJarInspector jarInspector = new PluginJarInspector();

    public SourcePluginBuildResult build(Path sourceFile, Set<String> existingParserIds, String allowedExistingParserId) {
        Instant compiledAt = Instant.now();
        CompilerResolution compiler = CompilerResolution.resolve();
        if (!compiler.resolved()) {
            return new SourcePluginBuildResult(false, ParserValidationStatus.MISSING_COMPILER, compiler.message(),
                    null, null, null, compiler, compiledAt, List.of(compiler.message()), compiler.message());
        }

        SourcePluginInspection inspection = sourceInspector.inspect(sourceFile);
        if (!inspection.success() || inspection.spec() == null) {
            return new SourcePluginBuildResult(false, inspection.status(), inspection.message(),
                    null, null, inspection.spec(), compiler, compiledAt, inspection.diagnostics(),
                    String.join(System.lineSeparator(), inspection.diagnostics()));
        }

        Path tempRoot = null;
        try {
            AppEnvironment.ensureBaseLayout();
            Files.createDirectories(AppEnvironment.tempDir());
            tempRoot = Files.createTempDirectory(AppEnvironment.tempDir(), "parser-source-build-");
            Path stagedSource = tempRoot.resolve(sourceFile.getFileName().toString());
            Files.copy(sourceFile, stagedSource, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            Path classesDir = tempRoot.resolve("classes");
            Files.createDirectories(classesDir);
            CompileExecution compile = compileSource(compiler.compilerPath(), stagedSource, classesDir);
            if (compile.exitCode() != 0) {
                return new SourcePluginBuildResult(false, ParserValidationStatus.COMPILATION_FAILED,
                        "Java source compilation failed.", null, null, inspection.spec(), compiler, compiledAt,
                        compile.diagnostics(), compile.log());
            }

            Path servicesFile = classesDir.resolve("META-INF").resolve("services").resolve("apdu.parser.plugin.api.ApduParserPlugin");
            Files.createDirectories(servicesFile.getParent());
            Files.writeString(servicesFile, inspection.spec().implementationClassName() + System.lineSeparator(), StandardCharsets.UTF_8);

            Path pluginMetadata = classesDir.resolve("META-INF").resolve("apdu-parser-plugin.json");
            Files.writeString(pluginMetadata, buildPluginMetadataJson(inspection.spec()), StandardCharsets.UTF_8);

            Path generatedJar = tempRoot.resolve("plugin.jar");
            createJar(generatedJar, classesDir);
            Path persistedJar = Files.createTempFile(AppEnvironment.tempDir(), "parser-source-plugin-", ".jar");
            Files.copy(generatedJar, persistedJar, StandardCopyOption.REPLACE_EXISTING);

            Set<String> ids = new java.util.LinkedHashSet<>(existingParserIds);
            if (allowedExistingParserId != null && !allowedExistingParserId.isBlank()) {
                ids.remove(allowedExistingParserId);
            }
            PluginValidationReport validation = jarInspector.inspect(persistedJar, ids, true, null);
            if (!validation.success()) {
                List<String> diagnostics = new ArrayList<>(compile.diagnostics());
                diagnostics.addAll(validation.diagnostics());
                return new SourcePluginBuildResult(false, validation.status(), validation.message(),
                        persistedJar, validation, inspection.spec(), compiler, compiledAt, diagnostics,
                        compile.log() + System.lineSeparator() + String.join(System.lineSeparator(), validation.diagnostics()));
            }

            return new SourcePluginBuildResult(true, ParserValidationStatus.COMPATIBLE, "Java source plugin compiled successfully.",
                    persistedJar, validation, inspection.spec(), compiler, compiledAt, compile.diagnostics(), compile.log());
        } catch (IOException ex) {
            return new SourcePluginBuildResult(false, ParserValidationStatus.COMPILATION_FAILED,
                    "Failed to build Java source plugin.", null, null, inspection.spec(), compiler, compiledAt,
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

    private static CompileExecution compileSource(Path javacPath, Path sourceFile, Path classesDir) throws IOException {
        String compileClassPath = runtimeCompileClassPath();
        List<String> command = List.of(
                javacPath.toString(),
                "-encoding", "UTF-8",
                "-cp", compileClassPath,
                "-d", classesDir.toAbsolutePath().toString(),
                sourceFile.toAbsolutePath().toString()
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            return new CompileExecution(-1, List.of(ex.getMessage() == null ? ex.toString() : ex.getMessage()), ex.toString());
        }

        String stdout;
        String stderr;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            List<String> diagnostics = new ArrayList<>();
            if (!stdout.isBlank()) {
                diagnostics.addAll(List.of(stdout.strip().split("\\R")));
            }
            if (!stderr.isBlank()) {
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

    private static String buildPluginMetadataJson(SourcePluginSpec spec) {
        return SimpleJsonWriter.write(java.util.Map.of(
                "pluginApiVersion", apdu.parser.plugin.api.PluginConstants.CURRENT_PLUGIN_API_VERSION,
                "implementationClass", spec.implementationClassName()
        ));
    }

    private static String runtimeCompileClassPath() {
        Path bundledPluginApi = AppEnvironment.pluginApiJarPath();
        if (Files.exists(bundledPluginApi)) {
            return bundledPluginApi.toAbsolutePath().toString();
        }
        return AppEnvironment.parserJarOrClassesPath().toAbsolutePath().toString();
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

    private record CompileExecution(int exitCode, List<String> diagnostics, String log) {
    }
}
