import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppEnvironment {

    private static final String APP_DIR = "APDUParser";

    private AppEnvironment() {
    }

    public static Path localAppDataRoot() {
        String override = System.getenv("APDU_PARSER_DATA_ROOT");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return Path.of(System.getProperty("user.home"), "AppData", "Local", APP_DIR).toAbsolutePath().normalize();
        }
        return Path.of(localAppData, APP_DIR).toAbsolutePath().normalize();
    }

    public static Path configDir() {
        return localAppDataRoot().resolve("config");
    }

    public static Path configPath() {
        return configDir().resolve("config.json");
    }

    public static Path importedLogsDir() {
        return localAppDataRoot().resolve("logs").resolve("imported");
    }

    public static Path logsDir() {
        return localAppDataRoot().resolve("logs");
    }

    public static Path outputDir() {
        return localAppDataRoot().resolve("output");
    }

    public static Path tempDir() {
        return localAppDataRoot().resolve("temp");
    }

    public static Path pluginsDir() {
        return localAppDataRoot().resolve("plugins");
    }

    public static Path pluginsInstalledDir() {
        return pluginsDir().resolve("installed");
    }

    public static Path applicationRoot() {
        try {
            Path codeSource = Path.of(AppEnvironment.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (Files.isRegularFile(codeSource)) {
                Path parent = codeSource.getParent();
                return parent != null && "parser".equalsIgnoreCase(parent.getFileName().toString())
                        ? parent.getParent()
                        : parent;
            }
            if (codeSource.endsWith(Path.of("build", "parser", "classes"))) {
                return codeSource.getParent().getParent().getParent();
            }
            return codeSource;
        } catch (URISyntaxException ex) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }

    public static Path parserRuntimeJavaPath() {
        return applicationRoot().resolve("runtime").resolve("bin").resolve("java.exe");
    }

    public static Path parserRuntimeJavacPath() {
        return applicationRoot().resolve("runtime").resolve("bin").resolve("javac.exe");
    }

    public static Path parserRuntimeJarToolPath() {
        return applicationRoot().resolve("runtime").resolve("bin").resolve("jar.exe");
    }

    public static Path pluginApiJarPath() {
        return applicationRoot().resolve("parser").resolve("plugin-api.jar");
    }

    public static Path bundledPluginsDir() {
        return applicationRoot().resolve("parser").resolve("bundled-plugins");
    }

    public static Path parserJarOrClassesPath() {
        try {
            return Path.of(AppEnvironment.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (URISyntaxException ex) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }

    public static Path builtInParserStatePath() {
        return pluginsDir().resolve("builtins.json");
    }

    public static void ensureBaseLayout() throws IOException {
        Files.createDirectories(configDir());
        Files.createDirectories(importedLogsDir());
        Files.createDirectories(logsDir());
        Files.createDirectories(outputDir());
        Files.createDirectories(tempDir());
        Files.createDirectories(pluginsInstalledDir());
    }
}
