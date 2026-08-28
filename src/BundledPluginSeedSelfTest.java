import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BundledPluginSeedSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bundled-plugin-seed-selftest");
        Path pluginsRoot = root.resolve("user-plugins");
        Path bundledRoot = root.resolve("bundled-plugins");
        Path bundledPlugin = bundledRoot.resolve("sample_bundled");
        Path bundledSource = bundledPlugin.resolve("source").resolve("SampleBundled.java");
        Files.createDirectories(bundledSource.getParent());
        Files.writeString(bundledPlugin.resolve("plugin.jar"), "test jar", StandardCharsets.UTF_8);
        Files.writeString(bundledPlugin.resolve("compile.log"), "Compilation succeeded.", StandardCharsets.UTF_8);
        Files.writeString(bundledSource, "public class SampleBundled {}", StandardCharsets.UTF_8);
        Files.writeString(bundledPlugin.resolve("metadata.json"), """
                {
                  "parserId": "sample_bundled",
                  "name": "Sample Bundled",
                  "version": "1.0.0",
                  "pluginApiVersion": 1,
                  "implementationClass": "SampleBundled",
                  "sourceType": "LEGACY_JAVA_EXTRACTOR",
                  "builtIn": false,
                  "enabled": true,
                  "validationStatus": "COMPATIBLE",
                  "validationMessage": "Compatible",
                  "preservedSourceFile": "",
                  "originalSourcePath": "Bundled with APDU Parser",
                  "compileLogPath": "",
                  "legacyMainClass": "SampleBundled",
                  "legacyCommandPattern": "INPUT_FILE_OUTPUT_FILE",
                  "legacyOutputFileName": "apdus.txt",
                  "supportedExtensions": [".txt", ".log"]
                }
                """, StandardCharsets.UTF_8);

        PluginStateStore store = new PluginStateStore(pluginsRoot, bundledRoot, "test-v1");
        store.ensureLayout();

        Path installed = pluginsRoot.resolve("installed").resolve("sample_bundled");
        SelfTestSupport.assertTrue(Files.isRegularFile(installed.resolve("plugin.jar")),
                "Bundled plugin JAR should be copied into the user plugin directory.");
        ParserInstallMetadata metadata = store.loadMetadata(installed);
        SelfTestSupport.assertEquals("sample_bundled", metadata.parserId(),
                "Bundled plugin metadata should be readable.");
        SelfTestSupport.assertEquals(bundledSource.getFileName().toString(), metadata.preservedSourceFile().getFileName().toString(),
                "Bundled source plugins should resolve their preserved source inside the installed directory.");
        SelfTestSupport.assertTrue(metadata.compileLogPath().startsWith(installed),
                "Bundled source plugins should resolve their compilation log inside the installed directory.");

        store.removeInstalledPlugin("sample_bundled");
        store.ensureLayout();
        SelfTestSupport.assertTrue(!Files.exists(installed),
                "A user-removed bundled plugin should not be reinstalled after the seed marker is written.");

        Files.writeString(bundledPlugin.resolve("plugin.jar"), "updated jar", StandardCharsets.UTF_8);
        PluginStateStore upgradedStore = new PluginStateStore(pluginsRoot, bundledRoot, "test-v2");
        upgradedStore.ensureLayout();
        SelfTestSupport.assertEquals("updated jar", Files.readString(installed.resolve("plugin.jar"), StandardCharsets.UTF_8),
                "A new bundled plugin set must install the updated plugin over an older seed state.");

        System.out.println("BundledPluginSeedSelfTest passed.");
    }
}
