import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Phase1ParitySelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu-phase1-parity");

        runSupportedCase(root, "honor.log",
                "reset lse\n" +
                        "APDU_tx 0: 80 E2 91 00 14 BF 31 11 A0 0C 5A 0A 98 58 22 54 82 00 02 66 42 76 81 01 00\n" +
                        "APDU_rx 0: 91 00\n" +
                        "APDU_tx 0: 80 7C 04 00 09 80 01 02 90 01 01 91 01 02\n" +
                        "APDU_rx 0: 90 00\n" +
                "APDU_tx 0: 80 E6 02 00 08 A0 00 00 00 62 03 01 0C\n" +
                        "APDU_tx 0: 80 E8 00 00 02 CA FE\n" +
                        "APDU_tx 0: 80 E6 0C 00 02 BE EF\n",
                "honor_apdutx",
                "Honor APDU_TX",
                3);

        runSupportedCase(root, "oppo.log",
                "Type = TX Data = 80 12 00 00 0B\n" +
                        "Type = RX Data = 12\n" +
                        "Type = TX Data = 01 02 03 04 05 06 07 08 09 0A 0B\n",
                "oppo_txdata",
                "OPPO Type=TX/Type=RX",
                1);

        runSupportedCase(root, "oppo_search_export.log",
                "Search \"TX Data = |RX Data =\"\n" +
                        "Line 10: TX Data = { 80 12 00 00 0B }\n" +
                        "Line 11: RX Data = 12\n" +
                        "Line 12: TX Data = { 01 02 03 04 05 06 07 08 09 0A 0B }\n",
                "oppo_txdata",
                "OPPO Type=TX/Type=RX",
                1);

        runSupportedCase(root, "oppo_braced_columns.log",
                "Line 20: TX Data = { 80 12 00 00 02 }   98 4D 22 10\n" +
                        "Line 21: RX Data = 12   98 4D 22 11\n" +
                        "Line 22: TX Data = { CA FE }   98 4D 22 12\n",
                "oppo_txdata",
                "OPPO Type=TX/Type=RX",
                1);

        runSupportedCase(root, "oh.log",
                "AA BB FF FF 00 00 00 01 00 02 80 7C 01 02 19\n",
                "oh_bytes",
                "OH FF FF stream",
                1);

        runSupportedCase(root, "unisoc.log",
                "tx_data_len[5]\n[T]USIMDRV 0x00 0xA4 0x04 0x00 0x00\n",
                "usimdrv_unisoc",
                "Unisoc USIMDRV",
                1);

        runSupportedCase(root, "espa\u00F1ol_\u6D4B\u8BD5.log",
                "--> [PCSC] 00A4040000\n",
                "pcsc_terminal",
                "PCSC Terminal",
                1);

        runSupportedCase(root, "report.html",
                "<html><body>APDU: 80 14 00 00 00</body></html>\n",
                "html_apdu",
                "HTML APDU Report",
                1);

        runUnsupportedCase(root, "unsupported.txt", "hello world\n");

        System.out.println("Phase1ParitySelfTest passed.");
    }

    private static void runSupportedCase(Path root, String fileName, String content, String expectedParserId, String expectedDisplayName, int expectedApduCount) throws Exception {
        Path caseRoot = root.resolve(fileName.replace('.', '_'));
        Files.createDirectories(caseRoot);
        Path config = writeConfig(caseRoot.resolve("config").resolve("config.json"));
        Path sourceDir = caseRoot.resolve("source files");
        Files.createDirectories(sourceDir);
        Path source = sourceDir.resolve(fileName);
        Files.writeString(source, content, StandardCharsets.UTF_8);

        ApduParserEngine engine = new ApduParserEngine(config.toString());
        engine.importFiles(List.of(source));
        ApduParserEngine.RunSummary summary = engine.analyzeAll(false, () -> false, null, null);
        SelfTestSupport.assertEquals(1, summary.completed(), "Engine should complete supported case " + fileName);
        ApduParserEngine.ImportedLog imported = engine.getImportedLogs().get(0);

        Path cliJson = caseRoot.resolve("cli").resolve("result.json");
        Path cliArtifacts = caseRoot.resolve("cli").resolve("artifacts");
        List<String> stderr = new ArrayList<>();
        int cliCode = ApduParserCli.run(new String[] {
                "--input", source.toString(),
                "--json-out", cliJson.toString(),
                "--artifacts-dir", cliArtifacts.toString()
        }, stderr::add);
        SelfTestSupport.assertEquals(0, cliCode, "CLI should succeed for supported case " + fileName);

        String cliBody = Files.readString(cliJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(cliBody, "\"id\": \"" + expectedParserId + "\"", "CLI JSON should include expected parser id.");
        SelfTestSupport.assertContains(cliBody, "\"displayName\": \"" + expectedDisplayName + "\"", "CLI JSON should include expected parser display name.");
        SelfTestSupport.assertContains(cliBody, "\"apduCount\": " + expectedApduCount, "CLI JSON should include expected APDU count.");
        SelfTestSupport.assertEquals(expectedDisplayName, imported.detectedFormat(), "Engine detected format should match baseline.");
        SelfTestSupport.assertEquals(expectedApduCount, imported.apduCount(), "Engine APDU count should match baseline.");

        String engineApduText = Files.readString(imported.rawOutputPath(), StandardCharsets.UTF_8);
        String cliApduText = Files.readString(cliArtifacts.resolve("apdus.txt"), StandardCharsets.UTF_8);
        SelfTestSupport.assertEquals(engineApduText, cliApduText, "CLI raw APDU output should match engine output.");

        String engineAnalysis = Files.readString(imported.analysisOutputPath(), StandardCharsets.UTF_8);
        String cliAnalysis = Files.readString(cliArtifacts.resolve("analysis.txt"), StandardCharsets.UTF_8);
        SelfTestSupport.assertEquals(engineAnalysis, cliAnalysis, "CLI analysis output should match engine output.");

        compareOptionalFile(imported.errorsOutputPath(), cliArtifacts.resolve("errors.txt"));
        compareOptionalFile(imported.appletsDir().resolve("all_clean.lop"), cliArtifacts.resolve("applets").resolve("all_clean.lop"));

        List<ApduOutputAnalyzer.AnalysisItem> items = ApduOutputAnalyzer.analyzeEntries(source, imported.rawOutputPath());
        for (ApduOutputAnalyzer.AnalysisItem item : items) {
            if (item.isResetMarker()) {
                SelfTestSupport.assertContains(cliBody, item.resetMarker, "CLI JSON should preserve reset markers.");
            } else {
                SelfTestSupport.assertContains(cliBody, "\"command\": \"" + item.commandApdu + "\"", "CLI JSON should preserve command APDU order.");
                SelfTestSupport.assertContains(cliBody, "\"response\": \"" + item.responseApdu + "\"", "CLI JSON should preserve matched response APDU.");
            }
        }
    }

    private static void runUnsupportedCase(Path root, String fileName, String content) throws Exception {
        Path caseRoot = root.resolve("unsupported_case");
        Files.createDirectories(caseRoot);
        Path config = writeConfig(caseRoot.resolve("config").resolve("config.json"));
        Path source = caseRoot.resolve(fileName);
        Files.writeString(source, content, StandardCharsets.UTF_8);

        ApduParserEngine engine = new ApduParserEngine(config.toString());
        engine.importFiles(List.of(source));
        ApduParserEngine.RunSummary summary = engine.analyzeAll(false, () -> false, null, null);
        SelfTestSupport.assertEquals(1, summary.unsupported(), "Engine should classify unsupported case correctly.");
        ApduParserEngine.ImportedLog imported = engine.getImportedLogs().get(0);
        SelfTestSupport.assertEquals(ApduParserEngine.Status.UNSUPPORTED, imported.status(), "Engine should mark unsupported status.");

        Path cliJson = caseRoot.resolve("cli-result.json");
        Path cliArtifacts = caseRoot.resolve("cli-artifacts");
        int cliCode = ApduParserCli.run(new String[] {
                "--input", source.toString(),
                "--json-out", cliJson.toString(),
                "--artifacts-dir", cliArtifacts.toString()
        }, line -> { });
        SelfTestSupport.assertEquals(1, cliCode, "CLI should return unsupported exit code.");
        String cliBody = Files.readString(cliJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(cliBody, "\"status\": \"unsupported\"", "CLI JSON should record unsupported status.");
        SelfTestSupport.assertTrue(!Files.exists(cliArtifacts.resolve("apdus.txt")), "Unsupported case should not emit apdus.txt.");
        SelfTestSupport.assertTrue(Files.exists(cliArtifacts.resolve("result.json")), "Unsupported case should still emit legacy result.json.");
        SelfTestSupport.assertTrue(Files.exists(imported.resultJsonPath()), "Engine should still write legacy result.json.");
    }

    private static Path writeConfig(Path configPath) throws Exception {
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath,
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
        return configPath;
    }

    private static void compareOptionalFile(Path engineFile, Path cliFile) throws Exception {
        boolean engineExists = Files.exists(engineFile);
        boolean cliExists = Files.exists(cliFile);
        SelfTestSupport.assertEquals(engineExists, cliExists, "Optional artifact presence should match.");
        if (engineExists) {
            String engineBody = Files.readString(engineFile, StandardCharsets.UTF_8);
            String cliBody = Files.readString(cliFile, StandardCharsets.UTF_8);
            SelfTestSupport.assertEquals(engineBody, cliBody, "Optional artifact contents should match.");
        }
    }
}
