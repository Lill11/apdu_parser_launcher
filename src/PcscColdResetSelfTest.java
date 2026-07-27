import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PcscColdResetSelfTest {
    private PcscColdResetSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of("tests", "fixtures", "pcsc-cold-reset.log");
        LogParserRegistry registry = new LogParserRegistry();
        LogParserRegistry.DetectionResult detection = registry.detect(fixture);
        SelfTestSupport.assertTrue(detection.supported(), "PCSC fixture should be detected.");
        SelfTestSupport.assertEquals("pcsc_terminal", detection.parser().getId(), "Wrong parser detected.");

        LogParser.ParseResult parsed = detection.parser().parse(fixture);
        List<ParsedLogEvent.Reset> resets = parsed.events().stream()
                .filter(ParsedLogEvent.Reset.class::isInstance)
                .map(ParsedLogEvent.Reset.class::cast)
                .toList();
        SelfTestSupport.assertEquals(2, resets.size(), "Exactly two standalone ATR resets are expected.");
        SelfTestSupport.assertEquals(3, resets.get(0).sourceLine(), "Initial reset line is wrong.");
        SelfTestSupport.assertEquals(15, resets.get(1).sourceLine(), "Later reset line is wrong.");
        SelfTestSupport.assertEquals(
                ParsedLogEvent.ResetType.COLD_RESET,
                resets.get(0).resetType(),
                "Reset type is wrong."
        );
        SelfTestSupport.assertEquals(3, parsed.apdus().size(), "RESET LSE commands must remain normal APDUs.");
        SelfTestSupport.assertEquals("807C010100", parsed.apdus().get(0), "First APDU changed.");
        SelfTestSupport.assertEquals("807C010119", parsed.apdus().get(1), "Second APDU changed.");

        List<ApduOutputAnalyzer.AnalysisItem> items =
                ApduOutputAnalyzer.analyzeEntries(fixture, parsed.apdus(), parsed.events());
        SelfTestSupport.assertEquals(5, items.size(), "Two resets and three APDUs should be ordered.");
        SelfTestSupport.assertTrue(items.get(0).isResetMarker(), "Initial reset must be first.");
        SelfTestSupport.assertEquals("807C010100", items.get(1).commandApdu, "First APDU order changed.");
        SelfTestSupport.assertEquals("807C010119", items.get(2).commandApdu, "Second APDU order changed.");
        SelfTestSupport.assertTrue(items.get(3).isResetMarker(), "Later reset must precede the final APDU.");
        SelfTestSupport.assertEquals("00A4040000", items.get(4).commandApdu, "Final APDU order changed.");

        Path artifacts = Files.createTempDirectory("pcsc-cold-reset-artifacts");
        ApduParserProcessor processor = new ApduParserProcessor();
        ApduParserProcessor.ProcessingResult result = processor.process(fixture, false, artifacts);
        String text = Files.readString(artifacts.resolve("apdus.txt"), StandardCharsets.UTF_8);
        SelfTestSupport.assertEquals(
                "RESET\n807C010100\n807C010119\nRESET\n00A4040000\n",
                text.replace("\r\n", "\n"),
                "Normalized output must contain standalone RESET lines."
        );
        String json = ApduParserProcessor.toStructuredJson(
                result,
                artifacts.resolve("structured-result.json"),
                artifacts,
                ""
        );
        SelfTestSupport.assertEquals(2, count(json, "\"type\": \"RESET\""), "JSON must contain two reset events.");
        SelfTestSupport.assertTrue(
                count(json, "\"resetType\": \"COLD_RESET\"") >= 2,
                "JSON reset type is missing."
        );

        System.out.println("PcscColdResetSelfTest passed.");
    }

    private static int count(String value, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
