import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ApduWorkflowSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu-parser-workflow");
        Path config = root.resolve("config.json");
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

        Path sourceDir = root.resolve("source");
        Files.createDirectories(sourceDir);
        Path htmlLog = sourceDir.resolve("sample.log");
        Files.writeString(htmlLog,
                "reset lse\n" +
                        "APDU_tx 0: 80 E2 91 00 14 BF 31 11 A0 0C 5A 0A 98 58 22 54 82 00 02 66 42 76 81 01 00\n" +
                        "APDU_rx 0: 91 00\n" +
                        "APDU_tx 0: 80 7C 04 00 09 80 01 02 90 01 01 91 01 02\n" +
                        "APDU_rx 0: 90 00\n" +
                        "APDU_tx 0: 80 E6 02 00 08 A0 00 00 00 62 03 01 0C\n" +
                        "APDU_tx 0: 80 E8 00 00 02 CA FE\n" +
                        "APDU_tx 0: 80 E6 0C 00 02 BE EF\n",
                StandardCharsets.UTF_8);

        ApduParserEngine engine = new ApduParserEngine(config.toString());
        engine.importFiles(List.of(htmlLog));
        ApduParserEngine.RunSummary summary = engine.analyzeAll(false, () -> false, null, null);
        SelfTestSupport.assertEquals(1, summary.completed(), "Workflow should complete exactly one file.");

        ApduParserEngine.ImportedLog imported = engine.getImportedLogs().get(0);
        SelfTestSupport.assertEquals(ApduParserEngine.Status.COMPLETED, imported.status(), "Imported log should be completed.");
        SelfTestSupport.assertTrue(Files.exists(imported.rawOutputPath()), "Raw APDU output should exist.");
        SelfTestSupport.assertTrue(Files.exists(imported.analysisOutputPath()), "Analysis output should exist.");
        SelfTestSupport.assertTrue(Files.exists(imported.resultJsonPath()), "Result json should exist.");
        SelfTestSupport.assertTrue(Files.exists(imported.appletsDir().resolve("all_clean.lop")), "Applet aggregate file should exist.");

        String analysis = Files.readString(imported.analysisOutputPath(), StandardCharsets.UTF_8);
        SelfTestSupport.assertTrue(analysis.contains("ES10 / EnableProfile"), "Analysis should highlight ES10 operation.");
        SelfTestSupport.assertTrue(analysis.contains("#RESET LSE"), "Analysis should include reset markers.");
        SelfTestSupport.assertTrue(analysis.contains("Configure LSI"), "Analysis should include LSI operation.");

        String source = Files.readString(Path.of("src", "ApduParserEngine.java"), StandardCharsets.UTF_8);
        SelfTestSupport.assertTrue(!source.contains("ProcessBuilder"), "Engine should no longer use ProcessBuilder.");

        System.out.println("ApduWorkflowSelfTest passed.");
    }
}
