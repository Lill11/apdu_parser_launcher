import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ApduAnalysisSelfTest {

    public static void main(String[] args) throws Exception {
        ApduParserEngine engine = new ApduParserEngine();
        Path workspaceRoot = engine.getWorkspaceRoot();
        Path honorSample = workspaceRoot.resolve("apdutx_apdu_extractor_HONOR").resolve("apdu log.txt");
        Path oppoSample = workspaceRoot.resolve("txdata_apdu_extractor_OPPO").resolve("pre-analysis.txt");
        Path syntheticOriginal = engine.getLauncherRoot().resolve("analysis-selftest.log");
        Path syntheticRaw = engine.getLauncherRoot().resolve("analysis-selftest-raw.txt");

        Set<Path> workBefore = snapshotTopLevel(engine.getWorkDir());
        Set<Path> outputBefore = snapshotTopLevel(engine.getOutputDir());

        Path honorRaw = null;
        Path honorAnalysis = null;
        Path oppoRaw = null;
        Path oppoAnalysis = null;

        try {
            assertCommandOnlyDoesNotInventSw();
            assertTagAndCommandDetection();
            assertSyntheticPairingAndFilters(syntheticOriginal, syntheticRaw);
            assertParserDetectionStillWorks(engine, honorSample, "honor_apdutx");
            assertParserDetectionStillWorks(engine, oppoSample, "oppo_txdata");

            ApduParserEngine.RunResult honorResult = engine.processFile(honorSample, false, null);
            honorRaw = honorResult.getOutputFile();
            honorAnalysis = honorResult.getEnhancedOutputFile();
            assertNormalizedFileContains(honorRaw, "00A4", "Honor raw extraction should contain SELECT APDUs");
            assertFileContains(honorAnalysis, "[", "Honor analysis should render indexed entries");
            assertFileContains(honorAnalysis, "APDU:", "Honor analysis should render the structured APDU view");
            assertFileContains(honorAnalysis, "Manage LSI", "Honor analysis should classify Manage LSI");

            ApduParserEngine.RunResult oppoResult = engine.processFile(oppoSample, false, null);
            oppoRaw = oppoResult.getOutputFile();
            oppoAnalysis = oppoResult.getEnhancedOutputFile();
            assertNormalizedFileContains(oppoRaw, "801200000B", "OPPO raw extraction should contain FETCH");
            assertFileContains(oppoAnalysis, "FETCH", "OPPO analysis should classify FETCH");
            assertFileContains(oppoAnalysis, "Severity:", "OPPO analysis should show severity lines");

            System.out.println("APDU_ANALYSIS_SELF_TEST=PASS");
        } finally {
            deleteIfPresent(honorRaw);
            deleteIfPresent(honorAnalysis);
            deleteIfPresent(oppoRaw);
            deleteIfPresent(oppoAnalysis);
            deleteIfPresent(syntheticOriginal);
            deleteIfPresent(syntheticRaw);
            cleanupNewTopLevelEntries(engine.getWorkDir(), workBefore);
            cleanupNewTopLevelEntries(engine.getOutputDir(), outputBefore);
        }
    }

    private static void assertCommandOnlyDoesNotInventSw() {
        assertStandaloneCommand("00C000000A9000");
        assertStandaloneCommand("81E2910003BF3100");
        assertStandaloneCommand("80CAFF21006F00");
    }

    private static void assertStandaloneCommand(String apdu) {
        ApduOutputAnalyzer.AnalysisResult result = ApduOutputAnalyzer.analyzeApdu(apdu);
        if (!"-".equals(result.statusWord)) {
            throw new IllegalStateException("Command-only APDU should not invent SW: " + apdu + " -> " + result.statusWord);
        }
    }

    private static void assertTagAndCommandDetection() {
        Path original = Path.of("C:\\Users\\junli\\Documents\\Codex\\apdu_parser_launcher").resolve("analysis-tag-check.log");
        Path raw = Path.of("C:\\Users\\junli\\Documents\\Codex\\apdu_parser_launcher").resolve("analysis-tag-check-raw.txt");
        try {
            Files.writeString(original, "TX: 81 E2 91 00 03 BF 31 00", StandardCharsets.UTF_8);
            Files.writeString(raw, "81E2910003BF3100", StandardCharsets.UTF_8);
            ApduOutputAnalyzer.AnalysisItem bf31 = ApduOutputAnalyzer.analyzeEntries(original, raw).get(0);
            if (!bf31.headline.startsWith("ES10 / EnableProfile") || !bf31.tagLabel.startsWith("BF31")) {
                throw new IllegalStateException("BF31 command should map to ES10 / EnableProfile");
            }
            Files.writeString(original, "TX: 81 E2 91 00 03 BF 23 00", StandardCharsets.UTF_8);
            Files.writeString(raw, "81E2910003BF2300", StandardCharsets.UTF_8);
            ApduOutputAnalyzer.AnalysisItem bf23 = ApduOutputAnalyzer.analyzeEntries(original, raw).get(0);
            if (!bf23.headline.startsWith("ES10 / InitialiseSecureChannel") || !bf23.tagLabel.equals("BF23 (InitialiseSecureChannel)")) {
                throw new IllegalStateException("BF23 should map to InitialiseSecureChannel");
            }
            Files.writeString(original, "TX: 81 E2 91 00 03 BF 3E 00", StandardCharsets.UTF_8);
            Files.writeString(raw, "81E2910003BF3E00", StandardCharsets.UTF_8);
            ApduOutputAnalyzer.AnalysisItem bf3e = ApduOutputAnalyzer.analyzeEntries(original, raw).get(0);
            if (!bf3e.headline.startsWith("ES10 / GetEID") || !bf3e.tagLabel.equals("BF3E (GetEID)")) {
                throw new IllegalStateException("BF3E should map to GetEID");
            }
            Files.writeString(original, "TX: 81 E2 91 00 03 BF 25 00", StandardCharsets.UTF_8);
            Files.writeString(raw, "81E2910003BF2500", StandardCharsets.UTF_8);
            ApduOutputAnalyzer.AnalysisItem bf25 = ApduOutputAnalyzer.analyzeEntries(original, raw).get(0);
            if (!bf25.headline.startsWith("ES10 / StoreMetadata") || !bf25.tagLabel.equals("BF25 (StoreMetadata)")) {
                throw new IllegalStateException("BF25 should map to StoreMetadata");
            }
            Files.writeString(original, "TX: 81 E2 91 00 05 AA BB BF 31 00", StandardCharsets.UTF_8);
            Files.writeString(raw, "81E2910005AABBBF3100", StandardCharsets.UTF_8);
            ApduOutputAnalyzer.AnalysisItem nestedBf31 = ApduOutputAnalyzer.analyzeEntries(original, raw).get(0);
            if (nestedBf31.headline.startsWith("ES10 /") || nestedBf31.tagLabel.contains("BF31")) {
                throw new IllegalStateException("Nested BFxx bytes should not be treated as top-level ES10 operations");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(original);
                Files.deleteIfExists(raw);
            } catch (Exception ignored) {
            }
        }

        ApduOutputAnalyzer.AnalysisResult configureLsi = ApduOutputAnalyzer.analyzeApdu("807C04009001");
        if (!"Manage LSI".equals(configureLsi.commandName)) {
            throw new IllegalStateException("807C0400... should map to Configure LSI");
        }
        Path resetOriginal = Path.of("C:\\Users\\junli\\Documents\\Codex\\apdu_parser_launcher").resolve("analysis-reset-lse.log");
        Path resetRaw = Path.of("C:\\Users\\junli\\Documents\\Codex\\apdu_parser_launcher").resolve("analysis-reset-lse-raw.txt");
        try {
            Files.writeString(resetOriginal, "TX: 80 7C 01 02 19", StandardCharsets.UTF_8);
            Files.writeString(resetRaw, "807C010219", StandardCharsets.UTF_8);
            String resetLseRendered = ApduOutputAnalyzer.renderEnhancedOutput(
                    ApduOutputAnalyzer.analyzeEntries(resetOriginal, resetRaw),
                    ApduOutputAnalyzer.FilterMode.ALL
            );
            assertContains(resetLseRendered, "Reset LSE", "807C010219 should render as Reset LSE");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(resetOriginal);
                Files.deleteIfExists(resetRaw);
            } catch (Exception ignored) {
            }
        }
    }

    private static void assertSyntheticPairingAndFilters(Path originalLog, Path rawOutput) throws Exception {
        Files.writeString(
                originalLog,
                String.join(System.lineSeparator(),
                        "#RESET",
                        "TX: 81 E2 91 00 03 BF 31 00",
                        "TX: 81 E2 91 00 03 BF 32 00",
                        "TX: 80 CA FF 21 00",
                        "RX: 6F 00",
                        "TX: 80 12 00 00 0B",
                        "RX: 61 00",
                        "TX: 80 14 00 00 0C",
                        "TX: 81 E2 91 00 03 BF 25 00",
                        "TX: 81 E2 91 00 03 BF 23 00",
                        "TX: 81 E2 91 00 03 BF 34 00",
                        "#RESET EUICC_MEMORY_RESET",
                        "TX: 80 7C 01 02 19",
                        "TX: 80 7C 04 00 90 01",
                        "TX: 80 7C 04 00 90 02"),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                rawOutput,
                String.join(System.lineSeparator(),
                        "81E2910003BF3100",
                        "81E2910003BF3200",
                        "80CAFF2100",
                        "801200000B",
                        "801400000C",
                        "81E2910003BF2500",
                        "81E2910003BF2300",
                        "81E2910003BF3400",
                        "807C010219",
                        "807C04009001",
                        "807C04009002"),
                StandardCharsets.UTF_8
        );

        List<ApduOutputAnalyzer.AnalysisItem> items = ApduOutputAnalyzer.analyzeEntries(originalLog, rawOutput);
        String all = ApduOutputAnalyzer.renderEnhancedOutput(items, ApduOutputAnalyzer.FilterMode.ALL);
        String es10 = ApduOutputAnalyzer.renderEnhancedOutput(items, ApduOutputAnalyzer.FilterMode.ES10);
        String fetchTr = ApduOutputAnalyzer.renderEnhancedOutput(items, ApduOutputAnalyzer.FilterMode.FETCH_TR);
        String lsi = ApduOutputAnalyzer.renderEnhancedOutput(items, ApduOutputAnalyzer.FilterMode.LSI);

        assertContains(all, "[0001] ES10 / EnableProfile", "BF31 should render as indexed ES10 operation");
        assertContains(all, "Tag: BF31 (EnableProfile)", "BF31 tag should be shown with semantic label");
        assertNotContains(all, "Layer:", "Enhanced output should stay concise");
        assertNotContains(all, "Category:", "Enhanced output should stay concise");
        assertNotContains(all, "Important ES10 ASN.1 operation", "Enhanced output should avoid explanatory ES10 prose");
        assertNotContains(all, "LSI configuration command", "Enhanced output should avoid explanatory LSI prose");
        assertContains(all, "[0002] ES10 / DisableProfile", "BF32 should render as indexed ES10 operation");
        assertContains(all, "[0006] ES10 / StoreMetadata", "BF25 should render with its ES10 semantic name");
        assertContains(all, "[0007] ES10 / InitialiseSecureChannel", "BF23 should render with its ES10 semantic name");
        assertContains(all, "[0008] ES10 / eUICCMemoryReset", "BF34 should render as indexed ES10 operation");
        assertContains(all, "Response: 6F00", "Explicit RX should be attached to command");
        assertContains(all, "Severity: ERROR  SW=6F00", "Explicit RX should produce error severity");
        assertContains(all, "Reset LSE", "807C010219 should render as Reset LSE");
        assertContains(all, "Configure LSI", "807C0400... should be detected as Configure LSI");
        assertContains(all, "[0001] #RESET", "Generic reset marker should be preserved with index");
        assertContains(all, "[0009] #RESET EUICC_MEMORY_RESET", "Memory reset marker should be preserved with index");
        assertNotContains(all, "(inferred)", "Enhanced output should not invent reset markers from Configure LSI");
        assertContains(all, "[0003] ERROR / Unhandled command  <ERROR>  SW=6F00", "ALL view should keep indexed error events visible");
        assertContains(fetchTr, "FETCH", "FETCH/TR filter should include FETCH");
        assertContains(fetchTr, "Terminal Response", "FETCH/TR filter should include Terminal Response");
        assertNotContains(fetchTr, "Configure LSI", "FETCH/TR filter should stay focused");
        assertContains(lsi, "Reset LSE", "LSI filter should include Reset LSE operations");
        assertContains(lsi, "Configure LSI", "LSI filter should include Configure LSI");
        assertNotContains(lsi, "(inferred)", "LSI filter should not invent reset context");
        assertNotContains(lsi, "Terminal Response", "LSI filter should exclude unrelated commands");
        assertContains(es10, "[0001] ES10 / EnableProfile", "ES10 filter should include indexed EnableProfile");
        assertContains(es10, "ES10 / StoreMetadata", "ES10 filter should include BF25");
        assertContains(es10, "ES10 / InitialiseSecureChannel", "ES10 filter should include BF23");
        assertNotContains(es10, "E3", "E3 should not be treated as an ES10 item in the filtered view");
        assertContains(es10, "Tag: BF34 (eUICCMemoryReset)", "ES10 filter should include important eUICC tags");
        assertNotContains(es10, "Configure LSI", "ES10 filter should stay focused on ES10 operations only");
        assertContains(es10, "#RESET", "ES10 filter should keep reset context for eUICC operations");
    }

    private static void assertParserDetectionStillWorks(ApduParserEngine engine, Path sample, String expectedParser) throws Exception {
        ApduParserEngine.DetectionResult detection = engine.detectParser(sample);
        if (!detection.matched()) {
            throw new IllegalStateException("No parser matched sample: " + sample.getFileName());
        }
        if (!expectedParser.equals(detection.getParserName())) {
            throw new IllegalStateException(
                    "Expected parser " + expectedParser + " but got " + detection.getParserName() + " for " + sample.getFileName()
            );
        }
    }

    private static void assertFileContains(Path path, String needle, String message) throws Exception {
        if (path == null || !Files.exists(path)) {
            throw new IllegalStateException("Expected file was not generated: " + path);
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertContains(content, needle, message);
    }

    private static void assertNormalizedFileContains(Path path, String needle, String message) throws Exception {
        if (path == null || !Files.exists(path)) {
            throw new IllegalStateException("Expected file was not generated: " + path);
        }
        String normalizedContent = ApduOutputAnalyzer.normalizeHex(Files.readString(path, StandardCharsets.UTF_8));
        if (!normalizedContent.contains(needle)) {
            throw new IllegalStateException(message + ". Missing: " + needle);
        }
    }

    private static void assertContains(String haystack, String needle, String message) {
        if (!haystack.contains(needle)) {
            throw new IllegalStateException(message + ". Missing: " + needle);
        }
    }

    private static void assertNotContains(String haystack, String needle, String message) {
        if (haystack.contains(needle)) {
            throw new IllegalStateException(message + ". Unexpected: " + needle);
        }
    }

    private static Set<Path> snapshotTopLevel(Path dir) throws Exception {
        Set<Path> snapshot = new HashSet<>();
        if (!Files.exists(dir)) {
            return snapshot;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.forEach(path -> snapshot.add(path.toAbsolutePath().normalize()));
        }
        return snapshot;
    }

    private static void cleanupNewTopLevelEntries(Path dir, Set<Path> before) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            List<Path> current = stream.map(path -> path.toAbsolutePath().normalize()).toList();
            for (Path path : current) {
                if (!before.contains(path)) {
                    deleteRecursively(path);
                }
            }
        }
    }

    private static void deleteIfPresent(Path path) throws Exception {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
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
