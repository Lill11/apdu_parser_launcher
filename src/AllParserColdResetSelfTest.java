import apdu.parser.plugin.api.PluginParseResult;

import java.nio.file.Path;
import java.util.List;

public final class AllParserColdResetSelfTest {
    private AllParserColdResetSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        LogParserRegistry registry = new LogParserRegistry();

        assertEvents(registry, "oppo-cold-reset.log", "oppo_txdata", 1, 1);
        assertEvents(registry, "oh-cold-reset.log", "oh_bytes", 1, 1);
        assertEvents(registry, "html-cold-reset.html", "html_apdu", 1, 1);
        assertEvents(registry, "honor-cold-reset.log", "honor_apdutx", 1, 1);
        assertEvents(registry, "unisoc-cold-reset.log", "usimdrv_unisoc", 1, 1);

        assertEvents(registry, "honor-no-false-reset.log", "honor_apdutx", 1, 0);
        assertEvents(registry, "oppo-no-false-reset.log", "oppo_txdata", 1, 0);
        assertEvents(registry, "oh-no-false-reset.log", "oh_bytes", 1, 0);
        assertEvents(registry, "unisoc-no-false-reset.log", "usimdrv_unisoc", 1, 0);
        assertEvents(registry, "html-no-false-reset.html", "html_apdu", 1, 0);

        Path legacyOutput = fixture("legacy-reset-output.txt");
        LegacyJavaExtractorSupport.ParsedLegacyOutput parsedLegacy =
                LegacyJavaExtractorSupport.parseOutputFile(legacyOutput);
        SelfTestSupport.assertEquals(5, parsedLegacy.apdus().size(),
                "Legacy output must retain standalone RESET markers.");
        LogParser.ParseResult legacy = LogParserRegistry.parsePluginOutput(
                new PluginParseResult(parsedLegacy.apdus(), parsedLegacy.warnings()));
        SelfTestSupport.assertEquals(3, legacy.apdus().size(),
                "Legacy RESET markers must not enter the raw APDU list.");
        SelfTestSupport.assertEquals(2, resetCount(legacy),
                "Legacy RESET markers must become ordered reset events.");
        SelfTestSupport.assertTrue(legacy.events().get(0) instanceof ParsedLogEvent.Reset,
                "The first legacy output event should be RESET.");
        SelfTestSupport.assertTrue(legacy.events().get(3) instanceof ParsedLogEvent.Reset,
                "The second legacy RESET should retain its output order.");

        System.out.println("AllParserColdResetSelfTest passed.");
    }

    private static void assertEvents(
            LogParserRegistry registry,
            String fixtureName,
            String parserId,
            int expectedApdus,
            int expectedResets
    ) throws Exception {
        Path file = fixture(fixtureName);
        LogParserRegistry.DetectionResult detection = registry.detect(file);
        SelfTestSupport.assertTrue(detection.supported(), fixtureName + " should be supported.");
        SelfTestSupport.assertEquals(parserId, detection.parser().getId(),
                fixtureName + " selected the wrong parser.");
        LogParser.ParseResult parsed = detection.parser().parse(file);
        SelfTestSupport.assertEquals(expectedApdus, parsed.apdus().size(),
                fixtureName + " APDU count changed.");
        SelfTestSupport.assertEquals(expectedResets, resetCount(parsed),
                fixtureName + " reset count is wrong.");
        if (expectedResets > 0) {
            SelfTestSupport.assertTrue(parsed.events().get(0) instanceof ParsedLogEvent.Reset,
                    fixtureName + " reset must precede its first APDU.");
        }
    }

    private static int resetCount(LogParser.ParseResult parsed) {
        return (int) parsed.events().stream().filter(ParsedLogEvent.Reset.class::isInstance).count();
    }

    private static Path fixture(String name) {
        return Path.of("tests", "fixtures", name);
    }
}
