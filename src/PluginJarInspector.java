import apdu.parser.plugin.api.ApduParserPlugin;
import apdu.parser.plugin.api.PluginConstants;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

public final class PluginJarInspector {

    public PluginValidationReport inspect(Path jarPath, Set<String> existingIds, boolean enabled, Path installDirectory) {
        Instant validatedAt = Instant.now();
        List<String> diagnostics = new ArrayList<>();
        if (jarPath == null || !jarPath.toString().toLowerCase().endsWith(".jar")) {
            return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                    "Only .jar parser plugins are supported in Phase A.", null, jarPath, validatedAt, diagnostics);
        }
        if (!java.nio.file.Files.exists(jarPath)) {
            return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                    "Plugin JAR does not exist.", null, jarPath, validatedAt, diagnostics);
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[] { jarPath.toUri().toURL() }, ApduParserPlugin.class.getClassLoader())) {
            ServiceLoader<ApduParserPlugin> serviceLoader = ServiceLoader.load(ApduParserPlugin.class, loader);
            List<ApduParserPlugin> plugins = new ArrayList<>();
            try {
                for (ApduParserPlugin plugin : serviceLoader) {
                    plugins.add(plugin);
                }
            } catch (ServiceConfigurationError error) {
                diagnostics.add(error.toString());
                return new PluginValidationReport(false, ParserValidationStatus.MISSING_DEPENDENCY,
                        "Plugin dependencies could not be loaded.", null, jarPath, validatedAt, diagnostics);
            }

            if (plugins.isEmpty()) {
                return new PluginValidationReport(false, ParserValidationStatus.MISSING_PARSER_IMPLEMENTATION,
                        "No ApduParserPlugin implementation was found.", null, jarPath, validatedAt, diagnostics);
            }
            if (plugins.size() != 1) {
                return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                        "Exactly one parser implementation must be exposed through ServiceLoader.", null, jarPath, validatedAt, diagnostics);
            }

            ApduParserPlugin plugin = plugins.get(0);
            String parserId = safe(plugin.getId());
            String name = safe(plugin.getName());
            String version = safe(plugin.getVersion());
            int pluginApiVersion = plugin.getPluginApiVersion();
            List<String> extensions = normalizedExtensions(plugin.getSupportedExtensions());

            if (parserId.isBlank()) {
                return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                        "Plugin parser ID must not be blank.", null, jarPath, validatedAt, diagnostics);
            }
            if (name.isBlank()) {
                return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                        "Plugin name must not be blank.", null, jarPath, validatedAt, diagnostics);
            }
            if (version.isBlank()) {
                return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                        "Plugin version must not be blank.", null, jarPath, validatedAt, diagnostics);
            }
            if (pluginApiVersion != PluginConstants.CURRENT_PLUGIN_API_VERSION) {
                return new PluginValidationReport(false, ParserValidationStatus.INCOMPATIBLE_PLUGIN_API,
                        "Plugin API version " + pluginApiVersion + " is not compatible with API version "
                                + PluginConstants.CURRENT_PLUGIN_API_VERSION + ".", null, jarPath, validatedAt, diagnostics);
            }
            if (extensions.isEmpty()) {
                return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                        "Plugin must declare at least one supported extension.", null, jarPath, validatedAt, diagnostics);
            }
            if (existingIds.contains(parserId)) {
                return new PluginValidationReport(false, ParserValidationStatus.DUPLICATE_PARSER_ID,
                        "A parser with ID '" + parserId + "' is already installed.", null, jarPath, validatedAt, diagnostics);
            }

            ParserRuntimeDescriptor descriptor = new ParserRuntimeDescriptor(
                    parserId,
                    name,
                    version,
                    pluginApiVersion,
                    plugin.getClass().getName(),
                    extensions,
                    ParserSourceType.PLUGIN_JAR,
                    false,
                    enabled,
                    enabled ? ParserValidationStatus.COMPATIBLE : ParserValidationStatus.DISABLED,
                    enabled ? "Compatible" : "Disabled",
                    installDirectory,
                    jarPath,
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
                    validatedAt,
                    200,
                    plugin
            );
            return new PluginValidationReport(true, enabled ? ParserValidationStatus.COMPATIBLE : ParserValidationStatus.DISABLED,
                    enabled ? "Compatible" : "Disabled", descriptor, jarPath, validatedAt, diagnostics);
        } catch (IOException ioException) {
            diagnostics.add(ioException.toString());
            return new PluginValidationReport(false, ParserValidationStatus.INVALID_PLUGIN,
                    "Plugin JAR could not be read.", null, jarPath, validatedAt, diagnostics);
        } catch (Throwable throwable) {
            diagnostics.add(throwable.toString());
            return new PluginValidationReport(false, ParserValidationStatus.PARSER_INITIALIZATION_FAILED,
                    "Plugin initialization failed.", null, jarPath, validatedAt, diagnostics);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizedExtensions(List<String> extensions) {
        if (extensions == null) {
            return List.of();
        }
        Set<String> items = new LinkedHashSet<>();
        for (String extension : extensions) {
            if (extension == null || extension.isBlank()) {
                continue;
            }
            String normalized = extension.startsWith(".") ? extension.trim().toLowerCase() : ("." + extension.trim().toLowerCase());
            items.add(normalized);
        }
        return List.copyOf(items);
    }
}
