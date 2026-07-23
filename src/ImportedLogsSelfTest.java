import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ImportedLogsSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu-parser-imports");
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
        Path first = sourceDir.resolve("duplicate.log");
        Path second = sourceDir.resolve("duplicate-copy.log");
        Files.writeString(first, "--> [PCSC] 00A4040000\n", StandardCharsets.UTF_8);
        Files.writeString(second, "--> [PCSC] 00C0000000\n", StandardCharsets.UTF_8);

        ApduParserEngine engine = new ApduParserEngine(config.toString());
        engine.importFiles(List.of(first, first, second));
        List<ApduParserEngine.ImportedLog> logs = engine.getImportedLogs();
        SelfTestSupport.assertEquals(3, logs.size(), "Import should keep three copied files.");
        SelfTestSupport.assertTrue(logs.get(0).fileName().startsWith("duplicate"), "Duplicate imports should be renamed safely.");

        boolean deleted = engine.deleteImportedFile(logs.get(0).filePath());
        SelfTestSupport.assertTrue(deleted, "Selected log should be deletable.");
        SelfTestSupport.assertEquals(2, engine.getImportedLogs().size(), "Delete should remove one imported log.");

        int cleared = engine.clearImportedFiles();
        SelfTestSupport.assertEquals(2, cleared, "Clear All should remove remaining logs.");
        SelfTestSupport.assertTrue(engine.getImportedLogs().isEmpty(), "All imported logs should be cleared.");

        System.out.println("ImportedLogsSelfTest passed.");
    }
}
