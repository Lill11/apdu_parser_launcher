import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class PathHandlingSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu parser 路径");
        Path config = root.resolve("config").resolve("config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config,
                "{\n" +
                        "  \"inputDir\": \"logs/imported\",\n" +
                        "  \"outputDir\": \"output\",\n" +
                        "  \"tempDir\": \"temp\",\n" +
                        "  \"logsDir\": \"logs\",\n" +
                        "  \"autoAnalyzeOnImport\": false,\n" +
                        "  \"retainDebugArtifacts\": false,\n" +
                        "  \"detectOnlyDefault\": false,\n" +
                        "  \"showDiagnosticsOnLaunch\": false,\n" +
                        "  \"windowWidth\": 1320,\n" +
                        "  \"windowHeight\": 860\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path sourceDir = root.resolve("source files");
        Files.createDirectories(sourceDir);
        Path chineseName = sourceDir.resolve("测试 日志.log");
        Files.writeString(chineseName, "--> [PCSC] 00A4040000\n", StandardCharsets.UTF_8);

        ApduParserEngine engine = new ApduParserEngine(config.toString());
        engine.importFiles(List.of(chineseName));
        ApduParserEngine.RunSummary summary = engine.analyzeAll(false, () -> false, null, null);
        SelfTestSupport.assertEquals(1, summary.completed(), "Path handling flow should complete.");

        ApduParserEngine.ImportedLog imported = engine.getImportedLogs().get(0);
        SelfTestSupport.assertTrue(imported.fileName().contains("测试 日志"), "Imported log should preserve Chinese/space filename.");
        SelfTestSupport.assertTrue(Files.exists(imported.resultDir().resolve("apdus.txt")), "Output should be written for space/Chinese path.");
        SelfTestSupport.assertTrue(imported.resultDir().startsWith(root), "Custom config root should keep output under custom root.");

        System.out.println("PathHandlingSelfTest passed.");
    }
}
