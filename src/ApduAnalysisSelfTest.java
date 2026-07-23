import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ApduAnalysisSelfTest {

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("apdu-analysis");
        Path originalLog = temp.resolve("sample.log");
        Path rawOutput = temp.resolve("apdus.txt");

        Files.writeString(originalLog,
                "reset lse\n" +
                        "TX: 80 E2 91 00 14 BF 32 11 A0 0C 5A 0A 98 58 22 54 82 00 02 66 42 76 81 01 00\n" +
                        "RX: 91 10\n" +
                        "TX: 80 12 00 00 0B\n" +
                        "RX: 90 00\n" +
                        "TX: 80 7C 04 00 09 80 01 02 90 01 01 91 01 02\n" +
                        "RX: 6F 00\n",
                StandardCharsets.UTF_8);
        Files.write(rawOutput,
                List.of(
                        "80E2910014BF3211A00C5A0A98582254820002664276810100",
                        "801200000B",
                        "807C040009800102900101910102"
                ),
                StandardCharsets.UTF_8);

        List<ApduOutputAnalyzer.AnalysisItem> items = ApduOutputAnalyzer.analyzeEntries(originalLog, rawOutput);
        String rendered = ApduOutputAnalyzer.renderEnhancedOutput(items, ApduOutputAnalyzer.FilterMode.ALL);

        SelfTestSupport.assertTrue(rendered.contains("#RESET LSE"), "Reset marker should be rendered.");
        SelfTestSupport.assertTrue(rendered.contains("ES10 / DisableProfile"), "ES10 BF32 should be recognized.");
        SelfTestSupport.assertTrue(rendered.contains("FETCH"), "FETCH command should be recognized.");
        SelfTestSupport.assertTrue(rendered.contains("Configure LSI"), "Configure LSI should be recognized.");
        SelfTestSupport.assertTrue(rendered.contains("SW=6F00") || rendered.contains("Severity: ERROR  SW=6F00"), "Known status words should be highlighted.");

        String lsiOnly = ApduOutputAnalyzer.renderEnhancedOutput(items, ApduOutputAnalyzer.FilterMode.LSI);
        SelfTestSupport.assertTrue(lsiOnly.contains("Configure LSI"), "LSI filter should keep LSI commands.");
        SelfTestSupport.assertTrue(!lsiOnly.contains("DisableProfile"), "LSI filter should drop ES10 commands.");

        System.out.println("ApduAnalysisSelfTest passed.");
    }
}
