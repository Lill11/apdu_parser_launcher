import apdu.parser.plugin.api.PluginParseResult;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class LegacyIxUsimColdResetSelfTest {
    private LegacyIxUsimColdResetSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(
                "bundled-plugins", "ix_usim_apdu_extractor_oh",
                "source", "Ix_USIM_apdu_extractor_OH.java");
        Path canonicalSource = Path.of("..", "Ix_USIM_apdu_extractor_OH", "Ix_USIM_apdu_extractor_OH.java");
        SelfTestSupport.assertEquals(-1L, Files.mismatch(source, canonicalSource),
                "Bundled and canonical Ix_USIM extractor sources must stay identical.");

        Path pluginJar = Path.of(
                "bundled-plugins", "ix_usim_apdu_extractor_oh", "plugin.jar");
        PluginValidationReport validation = new PluginJarInspector().inspect(
                pluginJar, Set.of(), true, null);
        SelfTestSupport.assertTrue(validation.success(),
                "Bundled Ix_USIM plugin should validate: " + validation.message());
        SelfTestSupport.assertEquals("ix_usim_apdu_extractor_oh",
                validation.descriptor().parserId(), "Unexpected bundled parser ID.");
        SelfTestSupport.assertEquals("1.1.0",
                validation.descriptor().version(), "Unexpected bundled parser version.");

        Path classes = Files.createTempDirectory("ix-usim-extractor-classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        SelfTestSupport.assertTrue(compiler != null, "A JDK compiler is required.");
        int compileExit = compiler.run(
                null, null, null,
                "-encoding", "UTF-8",
                "-d", classes.toString(),
                source.toString()
        );
        SelfTestSupport.assertEquals(0, compileExit, "Ix_USIM extractor should compile.");

        Path fixture = Path.of("tests", "fixtures", "ix-usim-legacy-oh-reset.log");
        Path output = Files.createTempFile("ix-usim-events", ".txt");
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"
        );
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp", classes.toString(),
                "Ix_USIM_apdu_extractor_OH",
                fixture.toString(),
                output.toString(),
                "UTF-8"
        ).redirectErrorStream(true).start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        SelfTestSupport.assertEquals(0, exitCode,
                "Ix_USIM extractor failed: " + processOutput);

        List<String> rawEvents = Files.readAllLines(output, StandardCharsets.UTF_8);
        SelfTestSupport.assertEquals(5, rawEvents.size(), "Expected three APDUs and two RESET events.");
        SelfTestSupport.assertEquals("RESET", rawEvents.get(0), "Initial card-init ATR should be RESET.");
        SelfTestSupport.assertEquals(
                "80 7C 04 00 09 80 01 02 90 01 01 91 01 02",
                rawEvents.get(1),
                "Existing fragmented APDU assembly changed."
        );
        SelfTestSupport.assertEquals("80 7C 01 02 00", rawEvents.get(2),
                "RESET LSE must remain an APDU.");
        SelfTestSupport.assertEquals("RESET", rawEvents.get(3),
                "I1_USIM card-init ATR should also be RESET.");
        SelfTestSupport.assertEquals("00 A4 04 00 00", rawEvents.get(4),
                "APDU after the second reset changed.");

        LegacyJavaExtractorSupport.ParsedLegacyOutput parsed =
                LegacyJavaExtractorSupport.parseOutputFile(output);
        LogParser.ParseResult wrapped = LogParserRegistry.parsePluginOutput(
                new PluginParseResult(parsed.apdus(), parsed.warnings()));
        SelfTestSupport.assertEquals(3, wrapped.apdus().size(),
                "Legacy wrapper should exclude RESET from APDU count.");
        SelfTestSupport.assertEquals(2L,
                wrapped.events().stream().filter(ParsedLogEvent.Reset.class::isInstance).count(),
                "Legacy wrapper should preserve both reset events.");
        SelfTestSupport.assertTrue(wrapped.events().get(0) instanceof ParsedLogEvent.Reset,
                "First wrapped event must remain RESET.");
        SelfTestSupport.assertTrue(wrapped.events().get(3) instanceof ParsedLogEvent.Reset,
                "Second wrapped RESET must retain chronological order.");

        System.out.println("LegacyIxUsimColdResetSelfTest passed.");
    }
}
