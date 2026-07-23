import apdu.parser.plugin.api.ApduParserPlugin;
import apdu.parser.plugin.api.PluginDetectionResult;
import apdu.parser.plugin.api.PluginParseResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ParserRuntimeDescriptor(
        String parserId,
        String name,
        String version,
        int pluginApiVersion,
        String implementationClass,
        List<String> supportedExtensions,
        ParserSourceType sourceType,
        boolean builtIn,
        boolean enabled,
        ParserValidationStatus validationStatus,
        String validationMessage,
        Path installDirectory,
        Path pluginJar,
        Path preservedSourceFile,
        String originalSourcePath,
        Path compileLogPath,
        String legacyMainClass,
        String legacyCommandPattern,
        String legacyOutputFileName,
        Instant lastCompiledAt,
        String lastCompilationStatus,
        String lastCompilationMessage,
        Instant lastTestedAt,
        String lastTestStatus,
        String lastTestMessage,
        String lastTestStderr,
        Instant installedAt,
        Instant lastValidatedAt,
        int priority,
        ApduParserPlugin plugin
) {
    public ParserRuntimeDescriptor {
        parserId = parserId == null ? "" : parserId;
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        implementationClass = implementationClass == null ? "" : implementationClass;
        supportedExtensions = supportedExtensions == null ? List.of() : List.copyOf(supportedExtensions);
        validationStatus = validationStatus == null ? ParserValidationStatus.INVALID_PLUGIN : validationStatus;
        validationMessage = validationMessage == null ? "" : validationMessage;
        originalSourcePath = originalSourcePath == null ? "" : originalSourcePath;
        legacyMainClass = legacyMainClass == null ? "" : legacyMainClass;
        legacyCommandPattern = legacyCommandPattern == null ? "" : legacyCommandPattern;
        legacyOutputFileName = legacyOutputFileName == null ? "" : legacyOutputFileName;
        lastCompilationStatus = lastCompilationStatus == null ? "" : lastCompilationStatus;
        lastCompilationMessage = lastCompilationMessage == null ? "" : lastCompilationMessage;
        lastTestStatus = lastTestStatus == null ? "" : lastTestStatus;
        lastTestMessage = lastTestMessage == null ? "" : lastTestMessage;
        lastTestStderr = lastTestStderr == null ? "" : lastTestStderr;
    }

    public boolean canParticipateInDetection() {
        return enabled && validationStatus == ParserValidationStatus.COMPATIBLE && plugin != null;
    }

    public PluginDetectionResult detect(Path inputFile, byte[] sample) throws Exception {
        return plugin.detect(inputFile, sample);
    }

    public PluginParseResult parse(Path inputFile) throws Exception {
        return plugin.parse(inputFile);
    }

    public ParserInstallMetadata toInstallMetadata() {
        return new ParserInstallMetadata(
                parserId,
                name,
                version,
                pluginApiVersion,
                implementationClass,
                supportedExtensions,
                sourceType,
                builtIn,
                enabled,
                validationStatus,
                validationMessage,
                installDirectory,
                pluginJar,
                preservedSourceFile,
                originalSourcePath,
                compileLogPath,
                legacyMainClass,
                legacyCommandPattern,
                legacyOutputFileName,
                lastCompiledAt,
                lastCompilationStatus,
                lastCompilationMessage,
                lastTestedAt,
                lastTestStatus,
                lastTestMessage,
                lastTestStderr,
                installedAt,
                lastValidatedAt
        );
    }
}
