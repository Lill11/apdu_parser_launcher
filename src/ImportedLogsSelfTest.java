import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ImportedLogsSelfTest {

    public static void main(String[] args) throws Exception {
        ApduParserEngine baseEngine = new ApduParserEngine();
        Path launcherRoot = baseEngine.getLauncherRoot();
        Path configPath = launcherRoot.resolve("selftest-input-config.json");
        Files.writeString(
                configPath,
                "{\n" +
                        "  \"inputDir\": \"input_selftest\",\n" +
                        "  \"outputDir\": \"output_selftest\",\n" +
                        "  \"workDir\": \"work_selftest\",\n" +
                        "  \"parsers\": [\n" +
                        "    {\n" +
                        "      \"name\": \"selftest_parser\",\n" +
                        "      \"extractorFolder\": \"../html_apdu_extractor_China_Unicom\",\n" +
                        "      \"scriptFile\": \"ExtractApdusFromHtml.java\",\n" +
                        "      \"stagedScriptFileName\": \"ExtractApdusFromHtml.java\",\n" +
                        "      \"stagedInputFileName\": \"sample.html\",\n" +
                        "      \"stagedOutputFileName\": \"apdus.txt\",\n" +
                        "      \"outputExtension\": \".txt\",\n" +
                        "      \"detectionMode\": \"all\",\n" +
                        "      \"patterns\": [\"<html\", \"APDU:\"],\n" +
                        "      \"extensions\": [\".html\"]\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}\n",
                StandardCharsets.UTF_8
        );

        ApduParserEngine engine = new ApduParserEngine("selftest-input-config.json");
        Path inputDir = engine.getInputDir();
        Path fileA = inputDir.resolve("selftest_a.txt");
        Path fileB = inputDir.resolve("selftest_b.log");

        Files.writeString(fileA, "APDU_tx selftest A", StandardCharsets.UTF_8);
        Files.writeString(fileB, "APDU_tx selftest B", StandardCharsets.UTF_8);

        try {
            List<Path> beforeDelete = engine.listInputFiles();
            if (!beforeDelete.contains(fileA) || !beforeDelete.contains(fileB)) {
                throw new IllegalStateException("Self-test files were not present in input/");
            }

            if (!engine.deleteImportedFile(fileA)) {
                throw new IllegalStateException("deleteImportedFile returned false");
            }

            List<Path> afterDelete = engine.listInputFiles();
            if (afterDelete.contains(fileA) || !afterDelete.contains(fileB)) {
                throw new IllegalStateException("Single delete did not update input file list");
            }

            int cleared = engine.clearImportedFiles();
            if (cleared < 1) {
                throw new IllegalStateException("clearImportedFiles did not remove remaining test files");
            }

            if (Files.exists(fileA) || Files.exists(fileB)) {
                throw new IllegalStateException("Self-test files still exist after clear");
            }

            System.out.println("IMPORTED_LOGS_SELF_TEST=PASS");
        } finally {
            Files.deleteIfExists(fileA);
            Files.deleteIfExists(fileB);
            Files.deleteIfExists(configPath);
            deleteDirectoryIfExists(launcherRoot.resolve("input_selftest"));
            deleteDirectoryIfExists(launcherRoot.resolve("output_selftest"));
            deleteDirectoryIfExists(launcherRoot.resolve("work_selftest"));
        }
    }

    private static void deleteDirectoryIfExists(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
