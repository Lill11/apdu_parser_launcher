import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ParserInstallMetadata(
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
        Instant lastValidatedAt
) {
    public ParserInstallMetadata {
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

    public ParserInstallMetadata withEnabled(boolean enabledValue) {
        return new ParserInstallMetadata(
                parserId,
                name,
                version,
                pluginApiVersion,
                implementationClass,
                supportedExtensions,
                sourceType,
                builtIn,
                enabledValue,
                enabledValue ? validationStatus : ParserValidationStatus.DISABLED,
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

    public ParserInstallMetadata withCompilationResult(
            Path newPluginJar,
            Path newPreservedSourceFile,
            String newOriginalSourcePath,
            Path newCompileLogPath,
            Instant compiledAt,
            String compilationStatus,
            String compilationMessage,
            Instant testedAt,
            String testStatus,
            String testMessage,
            String testStderr,
            Instant validatedAt,
            ParserValidationStatus newValidationStatus,
            String newValidationMessage,
            ParserSourceType newSourceType,
            String newImplementationClass,
            List<String> newSupportedExtensions,
            int newPluginApiVersion,
            String newName,
            String newVersion,
            String newLegacyMainClass,
            String newLegacyCommandPattern,
            String newLegacyOutputFileName
    ) {
        return new ParserInstallMetadata(
                parserId,
                newName,
                newVersion,
                newPluginApiVersion,
                newImplementationClass,
                newSupportedExtensions,
                newSourceType,
                builtIn,
                enabled,
                newValidationStatus,
                newValidationMessage,
                installDirectory,
                newPluginJar,
                newPreservedSourceFile,
                newOriginalSourcePath,
                newCompileLogPath,
                newLegacyMainClass,
                newLegacyCommandPattern,
                newLegacyOutputFileName,
                compiledAt,
                compilationStatus,
                compilationMessage,
                testedAt,
                testStatus,
                testMessage,
                testStderr,
                installedAt,
                validatedAt
        );
    }
}
