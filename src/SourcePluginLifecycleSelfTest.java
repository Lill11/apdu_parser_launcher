import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SourcePluginLifecycleSelfTest {

    public static void main(String[] args) throws Exception {
        String javacPath = CompilerResolution.resolve().compilerPath() == null
                ? null
                : CompilerResolution.resolve().compilerPath().toString();
        SelfTestSupport.assertTrue(javacPath != null && !javacPath.isBlank(), "A compiler path must be configured for Phase B self-test.");

        Path root = Files.createTempDirectory("apdu-source-plugin-selftest");
        Path pluginsRoot = root.resolve("plugins");
        PluginStateStore stateStore = new PluginStateStore(pluginsRoot);
        ParserAdminService adminService = new ParserAdminService(stateStore, new PluginJarInspector(), new JavaSourcePluginBuilder());

        Path sourceDir = root.resolve("source");
        Files.createDirectories(sourceDir);
        Path sourceFile = sourceDir.resolve("SourceSelfTestPlugin.java");
        Files.writeString(sourceFile, """
                package example.source;

                import apdu.parser.plugin.api.ApduParserPlugin;
                import apdu.parser.plugin.api.PluginConstants;
                import apdu.parser.plugin.api.PluginDetectionResult;
                import apdu.parser.plugin.api.PluginParseResult;

                import java.nio.charset.StandardCharsets;
                import java.nio.file.Path;
                import java.util.List;

                // 中文注释
                // Comentario español
                public class SourceSelfTestPlugin implements ApduParserPlugin {
                    public String getId() { return "source_selftest_plugin"; }
                    public String getName() { return "Source SelfTest Plugin"; }
                    public String getVersion() { return "1.0.0"; }
                    public int getPluginApiVersion() { return PluginConstants.CURRENT_PLUGIN_API_VERSION; }
                    public List<String> getSupportedExtensions() { return List.of(".log", ".txt"); }
                    public PluginDetectionResult detect(Path inputFile, byte[] sample) {
                        String text = new String(sample, StandardCharsets.UTF_8);
                        return text.contains("SOURCE_SELFTEST")
                                ? PluginDetectionResult.matched(150, "Source self-test marker matched.")
                                : PluginDetectionResult.noMatch("Marker not found.");
                    }
                    public PluginParseResult parse(Path inputFile) {
                        return new PluginParseResult(List.of("00A4040000"), List.of());
                    }
                }
                """, StandardCharsets.UTF_8);

        ParserAdminService.SourcePluginOperationResult install = adminService.installSource(sourceFile);
        SelfTestSupport.assertTrue(install.success(),
                "Source plugin should install successfully. Message=" + install.message()
                        + " Diagnostics=" + String.join(" | ", install.diagnostics()));
        SelfTestSupport.assertEquals(ParserSourceType.JAVA_SOURCE, install.parser().sourceType(), "Source parser should be marked as JAVA_SOURCE.");
        SelfTestSupport.assertTrue(Files.exists(install.parser().pluginJar()), "Compiled source plugin JAR should exist.");
        SelfTestSupport.assertTrue(Files.exists(install.parser().preservedSourceFile()), "Preserved source file should exist.");

        Path sampleLog = root.resolve("sample.log");
        Files.writeString(sampleLog, "SOURCE_SELFTEST\n", StandardCharsets.UTF_8);
        ParserAdminService.ParserTestResult testResult = adminService.testParser("source_selftest_plugin", sampleLog);
        SelfTestSupport.assertTrue("completed".equals(testResult.status()), "Installed source plugin should test successfully.");
        SelfTestSupport.assertEquals(1, testResult.apduCount(), "Source plugin should return one APDU.");

        Path preservedSource = install.parser().preservedSourceFile();
        Files.writeString(preservedSource, Files.readString(preservedSource, StandardCharsets.UTF_8).replace("return \"1.0.0\";", "return \"1.1.0\";"),
                StandardCharsets.UTF_8);
        ParserAdminService.SourcePluginOperationResult recompiled = adminService.recompileSource("source_selftest_plugin");
        SelfTestSupport.assertTrue(recompiled.success(), "Source plugin should recompile successfully.");
        SelfTestSupport.assertEquals("1.1.0", recompiled.parser().version(), "Recompiled source plugin should refresh version.");

        Files.writeString(preservedSource, "public class BrokenSourcePlugin {", StandardCharsets.UTF_8);
        ParserAdminService.SourcePluginOperationResult failed = adminService.recompileSource("source_selftest_plugin");
        SelfTestSupport.assertTrue(!failed.success(), "Invalid source should fail recompilation.");

        ParserAdminService.ParserTestResult stillWorks = adminService.testParser("source_selftest_plugin", sampleLog);
        SelfTestSupport.assertTrue("completed".equals(stillWorks.status()), "Previous working plugin should stay active after failed recompile.");

        System.out.println("SourcePluginLifecycleSelfTest passed.");
    }
}
