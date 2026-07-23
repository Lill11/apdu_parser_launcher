import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class PluginLifecycleSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu-plugin-selftest");
        Path pluginsRoot = root.resolve("plugins");
        PluginStateStore stateStore = new PluginStateStore(pluginsRoot);
        PluginJarInspector inspector = new PluginJarInspector();
        ParserAdminService adminService = new ParserAdminService(stateStore, inspector);

        Path pluginJar = buildSamplePlugin(root);
        PluginValidationReport validation = adminService.validatePlugin(pluginJar);
        SelfTestSupport.assertTrue(validation.success(), "Sample plugin should validate.");
        SelfTestSupport.assertEquals("sample_selftest_plugin", validation.descriptor().parserId(), "Unexpected plugin ID.");

        ParserRuntimeDescriptor installed = adminService.installPlugin(pluginJar);
        SelfTestSupport.assertEquals("sample_selftest_plugin", installed.parserId(), "Installed plugin should keep parser ID.");

        List<ParserRuntimeDescriptor> listed = adminService.listParsers();
        SelfTestSupport.assertTrue(listed.stream().anyMatch(parser -> parser.parserId().equals("sample_selftest_plugin")),
                "Installed plugin should appear in parser list.");

        Path sampleLog = root.resolve("sample.log");
        Files.writeString(sampleLog, "SELFTEST_PLUGIN\n--> [PCSC] 00A4040000\n", StandardCharsets.UTF_8);
        ParserAdminService.ParserTestResult testResult = adminService.testParser("sample_selftest_plugin", sampleLog);
        SelfTestSupport.assertTrue("completed".equals(testResult.status()), "Installed plugin should test successfully.");
        SelfTestSupport.assertEquals(1, testResult.apduCount(), "Installed plugin should extract one APDU.");

        ParserRuntimeDescriptor disabled = adminService.setEnabled("sample_selftest_plugin", false);
        SelfTestSupport.assertTrue(!disabled.enabled(), "Plugin should be disabled.");

        ParserRuntimeDescriptor enabled = adminService.setEnabled("sample_selftest_plugin", true);
        SelfTestSupport.assertTrue(enabled.enabled(), "Plugin should be re-enabled.");

        adminService.removePlugin("sample_selftest_plugin");
        List<ParserRuntimeDescriptor> afterRemoval = adminService.listParsers();
        SelfTestSupport.assertTrue(afterRemoval.stream().noneMatch(parser -> parser.parserId().equals("sample_selftest_plugin")),
                "Removed plugin should no longer appear in parser list.");

        System.out.println("PluginLifecycleSelfTest passed.");
    }

    private static Path buildSamplePlugin(Path root) throws Exception {
        Path sourceRoot = root.resolve("source");
        Path packageDir = sourceRoot.resolve("example");
        Files.createDirectories(packageDir);
        Path sourceFile = packageDir.resolve("SelfTestPlugin.java");
        Files.writeString(sourceFile, """
                package example;

                import apdu.parser.plugin.api.ApduParserPlugin;
                import apdu.parser.plugin.api.PluginConstants;
                import apdu.parser.plugin.api.PluginDetectionResult;
                import apdu.parser.plugin.api.PluginParseResult;

                import java.io.BufferedReader;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Locale;
                import java.util.regex.Matcher;
                import java.util.regex.Pattern;

                public class SelfTestPlugin implements ApduParserPlugin {
                    private static final Pattern COMMAND = Pattern.compile("-->\\\\s*\\\\[PCSC]\\\\s*([0-9A-Fa-f]+)");

                    @Override
                    public String getId() {
                        return "sample_selftest_plugin";
                    }

                    @Override
                    public String getName() {
                        return "Sample SelfTest Plugin";
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
                        return List.of(".log", ".txt");
                    }

                    @Override
                    public PluginDetectionResult detect(Path inputFile, byte[] sample) {
                        String text = new String(sample, StandardCharsets.UTF_8);
                        return text.contains("SELFTEST_PLUGIN")
                                ? PluginDetectionResult.matched(125, "Self-test marker matched.")
                                : PluginDetectionResult.noMatch("Marker not found.");
                    }

                    @Override
                    public PluginParseResult parse(Path inputFile) throws Exception {
                        List<String> apdus = new ArrayList<>();
                        try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                Matcher matcher = COMMAND.matcher(line);
                                if (matcher.find()) {
                                    apdus.add(matcher.group(1).toUpperCase(Locale.ROOT));
                                }
                            }
                        }
                        return new PluginParseResult(apdus, List.of());
                    }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        SelfTestSupport.assertTrue(compiler != null, "Java compiler should be available for self-test.");

        Path classesDir = root.resolve("classes");
        Files.createDirectories(classesDir);
        ByteArrayOutputStream compilerErr = new ByteArrayOutputStream();
        String compileClasspath = Path.of("parser", "apdu-parser.jar").toAbsolutePath().toString();
        int exitCode = compiler.run(
                null,
                null,
                compilerErr,
                "-encoding", "UTF-8",
                "-cp", compileClasspath,
                "-d", classesDir.toString(),
                sourceFile.toString()
        );
        SelfTestSupport.assertEquals(0, exitCode, "Sample plugin source should compile.");

        Path servicesFile = classesDir.resolve("META-INF").resolve("services").resolve("apdu.parser.plugin.api.ApduParserPlugin");
        Files.createDirectories(servicesFile.getParent());
        Files.writeString(servicesFile, "example.SelfTestPlugin\n", StandardCharsets.UTF_8);

        Path jarPath = root.resolve("sample-selftest-plugin.jar");
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out)) {
            writeJarEntry(jar, classesDir, classesDir.resolve("example").resolve("SelfTestPlugin.class"));
            writeJarEntry(jar, classesDir, servicesFile);
        }
        return jarPath;
    }

    private static void writeJarEntry(JarOutputStream jar, Path root, Path file) throws IOException {
        String entryName = root.relativize(file).toString().replace('\\', '/');
        jar.putNextEntry(new JarEntry(entryName));
        jar.write(Files.readAllBytes(file));
        jar.closeEntry();
    }
}
