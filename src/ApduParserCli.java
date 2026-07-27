import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApduParserCli {

    public static void main(String[] args) {
        int exitCode = run(args, System.err::println);
        System.exit(exitCode);
    }

    static int run(String[] args, LogSink stderr) {
        return run(args, stderr, new ApduParserProcessor(), new ParserAdminService());
    }

    static int run(String[] args, LogSink stderr, ApduParserProcessor processor) {
        return run(args, stderr, processor, new ParserAdminService());
    }

    static int run(String[] args, LogSink stderr, ApduParserProcessor processor, ParserAdminService adminService) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException ex) {
            if (stderr != null) {
                stderr.log("Invalid arguments: " + ex.getMessage());
                stderr.log(CliOptions.helpText());
            }
            return ApduParserProcessor.ExitCode.INVALID_ARGUMENTS.code();
        }

        if (options.help()) {
            if (stderr != null) {
                stderr.log(CliOptions.helpText());
            }
            return ApduParserProcessor.ExitCode.SUCCESS.code();
        }

        try {
            return switch (options.mode()) {
                case PARSE -> runParse(options, stderr, processor);
                case LIST_PARSERS -> writeJsonResponse(options.jsonOut(), parserListResponse(adminService.listParsers()));
                case VALIDATE_PLUGIN -> writeJsonResponse(options.jsonOut(), pluginValidationResponse("validatePlugin",
                        adminService.validatePlugin(options.pluginJar())));
                case INSPECT_PLUGIN -> writeJsonResponse(options.jsonOut(), pluginValidationResponse("inspectPlugin",
                        adminService.inspectPlugin(options.pluginJar())));
                case INSPECT_LEGACY_SOURCE -> writeJsonResponse(options.jsonOut(), legacyInspectionResponse(
                        adminService.inspectLegacySource(options.sourceFile())));
                case INSTALL_PLUGIN -> writeJsonResponse(options.jsonOut(), installResponse(adminService.installPlugin(options.pluginJar())));
                case INSTALL_SOURCE -> writeJsonResponse(options.jsonOut(), sourceOperationResponse("installSource",
                        adminService.installSource(options.sourceFile())));
                case INSTALL_LEGACY_SOURCE -> writeJsonResponse(options.jsonOut(), sourceOperationResponse("installLegacySource",
                        adminService.installLegacySource(new LegacyJavaExtractorInstallRequest(
                                options.sourceFile(),
                                options.parserName(),
                                options.parserId(),
                                options.parserVersion(),
                                parseExtensionsCsv(options.supportedExtensionsCsv()),
                                LegacyCommandPattern.fromWireValue(options.legacyCommandPattern()),
                                options.legacyOutputFileName(),
                                options.sampleInput()
                        ))));
                case ENABLE_PARSER -> writeJsonResponse(options.jsonOut(), parserActionResponse("enableParser",
                        adminService.setEnabled(options.parserId(), true)));
                case DISABLE_PARSER -> writeJsonResponse(options.jsonOut(), parserActionResponse("disableParser",
                        adminService.setEnabled(options.parserId(), false)));
                case REMOVE_PLUGIN -> {
                    adminService.removePlugin(options.parserId());
                    yield writeJsonResponse(options.jsonOut(), basicResponse("removePlugin", true, "Plugin removed."));
                }
                case RECOMPILE_PARSER -> writeJsonResponse(options.jsonOut(), sourceOperationResponse("recompileParser",
                        adminService.recompileSource(options.parserId())));
                case TEST_PARSER -> writeJsonResponse(options.jsonOut(), parserTestResponse(adminService.testParser(options.parserId(), options.input())));
            };
        } catch (OutputWriteException ioException) {
            if (stderr != null) {
                stderr.log("Output write failure: " + ioException.getMessage());
            }
            try {
                if (options.jsonOut() != null) {
                    writeJsonFile(options.jsonOut(), SimpleJsonWriter.write(errorResponse(options.mode().wireName(), ioException.getMessage())));
                }
            } catch (IOException ignored) {
            }
            return ApduParserProcessor.ExitCode.OUTPUT_WRITE_FAILURE.code();
        } catch (Exception ex) {
            if (stderr != null) {
                stderr.log("Parser failure: " + ex.getMessage());
            }
            try {
                if (options.jsonOut() != null) {
                    writeJsonFile(options.jsonOut(), SimpleJsonWriter.write(errorResponse(options.mode().wireName(),
                            ex.getMessage() == null ? ex.toString() : ex.getMessage())));
                }
            } catch (IOException ignored) {
            }
            return ApduParserProcessor.ExitCode.PARSER_FAILURE.code();
        }
    }

    private static int runParse(CliOptions options, LogSink stderr, ApduParserProcessor processor) throws Exception {
        StringBuilder diagnostics = new StringBuilder();
        ApduParserProcessor.ProcessingResult result = processor.process(options.input(), options.detectOnly(), options.artifactsDir());
        if (options.artifactsDir() != null && !Files.isDirectory(options.artifactsDir())) {
            return ApduParserProcessor.ExitCode.OUTPUT_WRITE_FAILURE.code();
        }

        if (!result.errorText().isBlank()) {
            diagnostics.append(result.errorText());
            if (stderr != null) {
                stderr.log(result.errorText());
            }
        }

        String json = ApduParserProcessor.toStructuredJson(
                result,
                options.jsonOut(),
                options.artifactsDir(),
                diagnostics.toString().strip()
        );
        writeJsonFile(options.jsonOut(), json);
        return result.exitCode().code();
    }

    private static int writeJsonResponse(Path jsonOut, Map<String, Object> payload) throws IOException {
        writeJsonFile(jsonOut, SimpleJsonWriter.write(payload));
        return ApduParserProcessor.ExitCode.SUCCESS.code();
    }

    private static void writeJsonFile(Path jsonOut, String json) throws OutputWriteException {
        try {
            if (jsonOut.getParent() != null) {
                Files.createDirectories(jsonOut.getParent());
            }
            Files.writeString(jsonOut, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            throw new OutputWriteException(ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> parserListResponse(List<ParserRuntimeDescriptor> parsers) {
        Map<String, Object> payload = baseResponse("listParsers", true, "Loaded parsers.");
        payload.put("parsers", parsers.stream().map(ApduParserCli::descriptorToMap).toList());
        return payload;
    }

    private static Map<String, Object> pluginValidationResponse(String responseType, PluginValidationReport report) {
        Map<String, Object> payload = baseResponse(responseType, report.success(), report.message());
        payload.put("status", report.status().name());
        payload.put("inspectedJar", report.inspectedJar() == null ? "" : report.inspectedJar().toAbsolutePath().toString());
        payload.put("validatedAt", report.validatedAt() == null ? "" : report.validatedAt().toString());
        payload.put("diagnostics", report.diagnostics());
        if (report.descriptor() != null) {
            payload.put("parser", descriptorToMap(report.descriptor()));
        }
        return payload;
    }

    private static Map<String, Object> legacyInspectionResponse(LegacyJavaSourceInspection inspection) {
        Map<String, Object> payload = baseResponse("inspectLegacySource", inspection.success(), inspection.message());
        payload.put("status", inspection.status().name());
        payload.put("diagnostics", inspection.diagnostics());
        if (inspection.spec() != null) {
            payload.put("source", Map.of(
                    "packageName", inspection.spec().packageName(),
                    "publicClassName", inspection.spec().publicClassName(),
                    "mainClassName", inspection.spec().mainClassName()
            ));
        }
        return payload;
    }

    private static Map<String, Object> installResponse(ParserRuntimeDescriptor descriptor) {
        Map<String, Object> payload = baseResponse("installPlugin", true, "Plugin installed.");
        payload.put("parser", descriptorToMap(descriptor));
        return payload;
    }

    private static Map<String, Object> parserActionResponse(String responseType, ParserRuntimeDescriptor descriptor) {
        Map<String, Object> payload = baseResponse(responseType, true, "Parser state updated.");
        payload.put("parser", descriptorToMap(descriptor));
        return payload;
    }

    private static Map<String, Object> sourceOperationResponse(String responseType, ParserAdminService.SourcePluginOperationResult result) {
        Map<String, Object> payload = baseResponse(responseType, result.success(), result.message());
        payload.put("status", result.status().name());
        payload.put("diagnostics", result.diagnostics());
        payload.put("compileLog", result.compileLog());
        payload.put("compileLogPath", result.compileLogPath() == null ? "" : result.compileLogPath().toAbsolutePath().toString());
        payload.put("stdout", result.stdout());
        payload.put("stderr", result.stderr());
        payload.put("generatedOutputPath", result.generatedOutputPath() == null ? "" : result.generatedOutputPath().toAbsolutePath().toString());
        payload.put("apduCount", result.apduCount());
        payload.put("warnings", result.warnings());
        if (result.compilerResolution() != null) {
            Map<String, Object> compiler = new LinkedHashMap<>();
            compiler.put("resolved", result.compilerResolution().resolved());
            compiler.put("path", result.compilerResolution().compilerPath() == null ? "" : result.compilerResolution().compilerPath().toAbsolutePath().toString());
            compiler.put("source", result.compilerResolution().source());
            compiler.put("message", result.compilerResolution().message());
            payload.put("compiler", compiler);
        }
        if (result.parser() != null) {
            payload.put("parser", descriptorToMap(result.parser()));
        }
        return payload;
    }

    private static Map<String, Object> parserTestResponse(ParserAdminService.ParserTestResult result) {
        Map<String, Object> payload = baseResponse("testParser", "completed".equals(result.status()), result.reason());
        payload.put("parser", descriptorToMap(result.parser()));
        payload.put("detection", Map.of(
                "matched", result.matched(),
                "confidence", result.confidence(),
                "reason", result.reason()
        ));
        payload.put("summary", Map.of(
                "status", result.status(),
                "apduCount", result.apduCount(),
                "warningCount", result.warnings().size(),
                "errorCount", result.errors().size(),
                "elapsedMs", result.elapsedMs(),
                "exitCode", result.exitCode()
        ));
        payload.put("warnings", result.warnings());
        payload.put("errors", result.errors());
        payload.put("stdout", result.stdout());
        payload.put("stderr", result.stderr());
        payload.put("outputPath", result.outputPath() == null ? "" : result.outputPath().toAbsolutePath().toString());
        return payload;
    }

    private static Map<String, Object> basicResponse(String responseType, boolean success, String message) {
        return baseResponse(responseType, success, message);
    }

    private static Map<String, Object> errorResponse(String responseType, String message) {
        return baseResponse(responseType, false, message);
    }

    private static Map<String, Object> baseResponse(String responseType, boolean success, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("responseType", responseType);
        payload.put("success", success);
        payload.put("message", message == null ? "" : message);
        payload.put("generatedAt", Instant.now().toString());
        return payload;
    }

    private static Map<String, Object> descriptorToMap(ParserRuntimeDescriptor descriptor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", descriptor.name());
        payload.put("id", descriptor.parserId());
        payload.put("version", descriptor.version());
        payload.put("pluginApiVersion", descriptor.pluginApiVersion());
        payload.put("supportedExtensions", descriptor.supportedExtensions());
        payload.put("sourceType", descriptor.sourceType().name());
        payload.put("enabled", descriptor.enabled());
        payload.put("validationStatus", descriptor.validationStatus().name());
        payload.put("validationMessage", descriptor.validationMessage());
        payload.put("installDirectory", descriptor.installDirectory() == null ? "" : descriptor.installDirectory().toAbsolutePath().toString());
        payload.put("pluginJar", descriptor.pluginJar() == null ? "" : descriptor.pluginJar().toAbsolutePath().toString());
        payload.put("implementationClass", descriptor.implementationClass());
        payload.put("builtIn", descriptor.builtIn());
        payload.put("preservedSourceFile", descriptor.preservedSourceFile() == null ? "" : descriptor.preservedSourceFile().toAbsolutePath().toString());
        payload.put("originalSourcePath", descriptor.originalSourcePath());
        payload.put("compileLogPath", descriptor.compileLogPath() == null ? "" : descriptor.compileLogPath().toAbsolutePath().toString());
        payload.put("legacyMainClass", descriptor.legacyMainClass());
        payload.put("legacyCommandPattern", descriptor.legacyCommandPattern());
        payload.put("legacyOutputFileName", descriptor.legacyOutputFileName());
        payload.put("lastCompiledAt", descriptor.lastCompiledAt() == null ? "" : descriptor.lastCompiledAt().toString());
        payload.put("lastCompilationStatus", descriptor.lastCompilationStatus());
        payload.put("lastCompilationMessage", descriptor.lastCompilationMessage());
        payload.put("lastTestedAt", descriptor.lastTestedAt() == null ? "" : descriptor.lastTestedAt().toString());
        payload.put("lastTestStatus", descriptor.lastTestStatus());
        payload.put("lastTestMessage", descriptor.lastTestMessage());
        payload.put("lastTestStderr", descriptor.lastTestStderr());
        payload.put("lastValidatedAt", descriptor.lastValidatedAt() == null ? "" : descriptor.lastValidatedAt().toString());
        payload.put("installedAt", descriptor.installedAt() == null ? "" : descriptor.installedAt().toString());
        payload.put("priority", descriptor.priority());
        return payload;
    }

    private static List<String> parseExtensionsCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    interface LogSink {
        void log(String line);
    }

    private static final class OutputWriteException extends IOException {
        private OutputWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    enum Mode {
        PARSE("parse"),
        LIST_PARSERS("listParsers"),
        VALIDATE_PLUGIN("validatePlugin"),
        INSPECT_PLUGIN("inspectPlugin"),
        INSPECT_LEGACY_SOURCE("inspectLegacySource"),
        INSTALL_PLUGIN("installPlugin"),
        INSTALL_SOURCE("installSource"),
        INSTALL_LEGACY_SOURCE("installLegacySource"),
        ENABLE_PARSER("enableParser"),
        DISABLE_PARSER("disableParser"),
        REMOVE_PLUGIN("removePlugin"),
        RECOMPILE_PARSER("recompileParser"),
        TEST_PARSER("testParser");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        static Mode fromWireName(String wireName) {
            for (Mode value : values()) {
                if (value.wireName.equalsIgnoreCase(wireName == null ? "" : wireName)) {
                    return value;
                }
            }
            return PARSE;
        }
    }

    record CliOptions(
            Mode mode,
            Path input,
            Path jsonOut,
            Path artifactsDir,
            boolean detectOnly,
            boolean help,
            Path pluginJar,
            Path sourceFile,
            String parserId,
            String parserName,
            String parserVersion,
            String supportedExtensionsCsv,
            String legacyCommandPattern,
            String legacyOutputFileName,
            Path sampleInput
    ) {
        static CliOptions parse(String[] args) {
            Path input = null;
            Path jsonOut = null;
            Path artifactsDir = null;
            Path requestFile = null;
            Path pluginJar = null;
            Path sourceFile = null;
            String parserId = null;
            String parserName = "";
            String parserVersion = "";
            String supportedExtensionsCsv = "";
            String legacyCommandPattern = "";
            String legacyOutputFileName = "";
            Path sampleInput = null;
            boolean detectOnly = false;
            boolean help = false;
            Mode mode = Mode.PARSE;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--input" -> input = requirePath(args, ++i, "--input");
                    case "--json-out" -> jsonOut = requirePath(args, ++i, "--json-out");
                    case "--artifacts-dir" -> artifactsDir = requirePath(args, ++i, "--artifacts-dir");
                    case "--request-file" -> requestFile = requirePath(args, ++i, "--request-file");
                    case "--plugin-jar" -> pluginJar = requirePath(args, ++i, "--plugin-jar");
                    case "--source-file" -> sourceFile = requirePath(args, ++i, "--source-file");
                    case "--parser-id" -> parserId = requireString(args, ++i, "--parser-id");
                    case "--parser-name" -> parserName = requireString(args, ++i, "--parser-name");
                    case "--parser-version" -> parserVersion = requireString(args, ++i, "--parser-version");
                    case "--supported-extensions" -> supportedExtensionsCsv = requireString(args, ++i, "--supported-extensions");
                    case "--legacy-command-pattern" -> legacyCommandPattern = requireString(args, ++i, "--legacy-command-pattern");
                    case "--legacy-output-file-name" -> legacyOutputFileName = requireString(args, ++i, "--legacy-output-file-name");
                    case "--sample-input" -> sampleInput = requirePath(args, ++i, "--sample-input");
                    case "--detect-only" -> detectOnly = true;
                    case "--list-parsers" -> mode = Mode.LIST_PARSERS;
                    case "--validate-plugin" -> {
                        mode = Mode.VALIDATE_PLUGIN;
                        pluginJar = requirePath(args, ++i, "--validate-plugin");
                    }
                    case "--inspect-plugin" -> {
                        mode = Mode.INSPECT_PLUGIN;
                        pluginJar = requirePath(args, ++i, "--inspect-plugin");
                    }
                    case "--inspect-legacy-source" -> {
                        mode = Mode.INSPECT_LEGACY_SOURCE;
                        sourceFile = requirePath(args, ++i, "--inspect-legacy-source");
                    }
                    case "--install-plugin" -> {
                        mode = Mode.INSTALL_PLUGIN;
                        pluginJar = requirePath(args, ++i, "--install-plugin");
                    }
                    case "--install-source" -> {
                        mode = Mode.INSTALL_SOURCE;
                        sourceFile = requirePath(args, ++i, "--install-source");
                    }
                    case "--install-legacy-source" -> {
                        mode = Mode.INSTALL_LEGACY_SOURCE;
                        sourceFile = requirePath(args, ++i, "--install-legacy-source");
                    }
                    case "--enable-parser" -> {
                        mode = Mode.ENABLE_PARSER;
                        parserId = requireString(args, ++i, "--enable-parser");
                    }
                    case "--disable-parser" -> {
                        mode = Mode.DISABLE_PARSER;
                        parserId = requireString(args, ++i, "--disable-parser");
                    }
                    case "--remove-plugin" -> {
                        mode = Mode.REMOVE_PLUGIN;
                        parserId = requireString(args, ++i, "--remove-plugin");
                    }
                    case "--recompile-parser" -> {
                        mode = Mode.RECOMPILE_PARSER;
                        parserId = requireString(args, ++i, "--recompile-parser");
                    }
                    case "--test-parser" -> {
                        mode = Mode.TEST_PARSER;
                        parserId = requireString(args, ++i, "--test-parser");
                    }
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (requestFile != null) {
                RequestPayload request = RequestPayload.load(requestFile);
                mode = request.mode();
                input = request.input();
                jsonOut = request.jsonOut();
                artifactsDir = request.artifactsDir();
                detectOnly = request.detectOnly();
                pluginJar = request.pluginJar();
                sourceFile = request.sourceFile();
                parserId = request.parserId();
                parserName = request.parserName();
                parserVersion = request.parserVersion();
                supportedExtensionsCsv = request.supportedExtensionsCsv();
                legacyCommandPattern = request.legacyCommandPattern();
                legacyOutputFileName = request.legacyOutputFileName();
                sampleInput = request.sampleInput();
            }

            if (help) {
                return new CliOptions(mode, input, jsonOut, artifactsDir, detectOnly, true, pluginJar, sourceFile, parserId,
                        parserName, parserVersion, supportedExtensionsCsv, legacyCommandPattern, legacyOutputFileName, sampleInput);
            }
            if (jsonOut == null) {
                throw new IllegalArgumentException("--json-out is required");
            }
            switch (mode) {
                case PARSE -> {
                    if (input == null) {
                        throw new IllegalArgumentException("--input is required");
                    }
                }
                case VALIDATE_PLUGIN, INSPECT_PLUGIN, INSTALL_PLUGIN -> {
                    if (pluginJar == null) {
                        throw new IllegalArgumentException("Plugin JAR path is required.");
                    }
                }
                case INSTALL_SOURCE, INSPECT_LEGACY_SOURCE -> {
                    if (sourceFile == null) {
                        throw new IllegalArgumentException("Java source path is required.");
                    }
                }
                case INSTALL_LEGACY_SOURCE -> {
                    if (sourceFile == null) {
                        throw new IllegalArgumentException("Legacy Java source path is required.");
                    }
                    if (parserId == null || parserId.isBlank()) {
                        throw new IllegalArgumentException("Parser ID is required.");
                    }
                    if (parserName == null || parserName.isBlank()) {
                        throw new IllegalArgumentException("Parser name is required.");
                    }
                    if (supportedExtensionsCsv == null || supportedExtensionsCsv.isBlank()) {
                        throw new IllegalArgumentException("Supported extensions are required.");
                    }
                    if (sampleInput == null) {
                        throw new IllegalArgumentException("Sample input is required for legacy extractor install.");
                    }
                }
                case ENABLE_PARSER, DISABLE_PARSER, REMOVE_PLUGIN, RECOMPILE_PARSER -> {
                    if (parserId == null || parserId.isBlank()) {
                        throw new IllegalArgumentException("Parser ID is required.");
                    }
                }
                case TEST_PARSER -> {
                    if (parserId == null || parserId.isBlank()) {
                        throw new IllegalArgumentException("Parser ID is required.");
                    }
                    if (input == null) {
                        throw new IllegalArgumentException("--input is required for --test-parser");
                    }
                }
                case LIST_PARSERS -> {
                }
            }
            return new CliOptions(mode, input, jsonOut, artifactsDir, detectOnly, false, pluginJar, sourceFile, parserId,
                    parserName, parserVersion, supportedExtensionsCsv, legacyCommandPattern, legacyOutputFileName, sampleInput);
        }

        private static Path requirePath(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return Path.of(args[index]).toAbsolutePath().normalize();
        }

        private static String requireString(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        static String helpText() {
            return """
                    Usage: java -jar parser\\apdu-parser.jar --input <log-file> --json-out <result.json> [--artifacts-dir <dir>] [--detect-only]
                           java -jar parser\\apdu-parser.jar --request-file <request.json>
                           java -jar parser\\apdu-parser.jar --list-parsers --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --validate-plugin <plugin.jar> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --inspect-plugin <plugin.jar> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --inspect-legacy-source <extractor.java> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --install-plugin <plugin.jar> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --install-source <plugin.java> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --install-legacy-source <extractor.java> --parser-name <name> --parser-id <id> --supported-extensions <csv> --sample-input <log> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --enable-parser <parser-id> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --disable-parser <parser-id> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --remove-plugin <parser-id> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --recompile-parser <parser-id> --json-out <result.json>
                           java -jar parser\\apdu-parser.jar --test-parser <parser-id> --input <sample-log> --json-out <result.json>

                    Arguments:
                      --input            Source log file to parse or test
                      --json-out         Structured UTF-8 JSON result path
                      --artifacts-dir    Optional parse artifact directory
                      --request-file     UTF-8 JSON request file for parse mode
                      --detect-only      Detect parser without full APDU extraction
                      --help, -h         Show this help text
                    """;
        }
    }

    private record RequestPayload(Mode mode, Path input, Path jsonOut, Path artifactsDir, boolean detectOnly, Path pluginJar, Path sourceFile, String parserId,
                                  String parserName, String parserVersion, String supportedExtensionsCsv, String legacyCommandPattern, String legacyOutputFileName, Path sampleInput) {
        static RequestPayload load(Path file) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Mode mode = Mode.fromWireName(readJsonString(json, "mode", Mode.PARSE.wireName()));
                Path input = readOptionalPath(json, "input");
                Path jsonOut = readOptionalPath(json, "jsonOut");
                String artifacts = readJsonString(json, "artifactsDir", "");
                Path artifactsDir = artifacts.isBlank() ? null : Path.of(artifacts).toAbsolutePath().normalize();
                Path pluginJar = readOptionalPath(json, "pluginJar");
                Path sourceFile = readOptionalPath(json, "sourceFile");
                String parserId = readJsonString(json, "parserId", "");
                String parserName = readJsonString(json, "parserName", "");
                String parserVersion = readJsonString(json, "parserVersion", "");
                String supportedExtensionsCsv = readJsonString(json, "supportedExtensionsCsv", "");
                String legacyCommandPattern = readJsonString(json, "legacyCommandPattern", "");
                String legacyOutputFileName = readJsonString(json, "legacyOutputFileName", "");
                Path sampleInput = readOptionalPath(json, "sampleInput");
                boolean detectOnly = Boolean.parseBoolean(readJsonString(json, "detectOnly", "false"));
                return new RequestPayload(mode, input, jsonOut, artifactsDir, detectOnly, pluginJar, sourceFile, parserId,
                        parserName, parserVersion, supportedExtensionsCsv, legacyCommandPattern, legacyOutputFileName, sampleInput);
            } catch (IOException ex) {
                throw new IllegalArgumentException("Failed to read request file: " + ex.getMessage(), ex);
            }
        }

        private static Path readOptionalPath(String json, String field) {
            String value = readJsonString(json, field, "");
            return value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
        }

        private static String readJsonString(String json, String field, String fallback) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
            java.util.regex.Matcher matcher = pattern.matcher(json == null ? "" : json);
            return matcher.find() ? decodeJsonString(matcher.group(1)) : fallback;
        }

        private static String decodeJsonString(String encoded) {
            StringBuilder decoded = new StringBuilder(encoded.length());
            for (int index = 0; index < encoded.length(); index++) {
                char current = encoded.charAt(index);
                if (current != '\\') {
                    decoded.append(current);
                    continue;
                }
                if (++index >= encoded.length()) {
                    throw new IllegalArgumentException("Invalid JSON string escape.");
                }
                char escaped = encoded.charAt(index);
                switch (escaped) {
                    case '"' -> decoded.append('"');
                    case '\\' -> decoded.append('\\');
                    case '/' -> decoded.append('/');
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        if (index + 4 >= encoded.length()) {
                            throw new IllegalArgumentException("Invalid JSON unicode escape.");
                        }
                        String hex = encoded.substring(index + 1, index + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("Invalid JSON unicode escape: \\u" + hex, ex);
                        }
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid JSON string escape: \\" + escaped);
                }
            }
            return decoded.toString();
        }
    }
}
