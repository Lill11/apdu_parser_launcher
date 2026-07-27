import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ApduParserCliSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu-cli-selftest");

        Path successLog = root.resolve("honor sample.log");
        Files.writeString(successLog,
                "reset lse\n" +
                        "APDU_tx 0: 80 E2 91 00 14 BF 32 11 A0 0C 5A 0A 98 58 22 54 82 00 02 66 42 76 81 01 00\n" +
                        "APDU_rx 0: 6F 00\n" +
                        "APDU_tx 0: 80 E6 02 00 08 A0 00 00 00 62 03 01 0C\n" +
                        "APDU_tx 0: 80 E8 00 00 02 CA FE\n" +
                        "APDU_tx 0: 80 E6 0C 00 02 BE EF\n",
                StandardCharsets.UTF_8);

        Path successJson = root.resolve("success").resolve("result.json");
        Path successArtifacts = root.resolve("success").resolve("artifacts");
        List<String> successErr = new ArrayList<>();
        int successCode = ApduParserCli.run(new String[] {
                "--input", successLog.toString(),
                "--json-out", successJson.toString(),
                "--artifacts-dir", successArtifacts.toString()
        }, successErr::add);
        SelfTestSupport.assertEquals(0, successCode, "Successful CLI run should return exit code 0.");
        String successBody = Files.readString(successJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(successBody, "\"schemaVersion\": 1", "Success JSON should include schema version.");
        SelfTestSupport.assertContains(successBody, "\"id\": \"honor_apdutx\"", "Success JSON should include detected parser id.");
        SelfTestSupport.assertContains(successBody, "\"command\": \"80E2910014BF3211A00C5A0A98582254820002664276810100\"", "Success JSON should include parsed APDU.");
        SelfTestSupport.assertContains(successBody, "\"response\": \"6F00\"", "Success JSON should include matched response.");
        SelfTestSupport.assertTrue(Files.exists(successArtifacts.resolve("apdus.txt")), "CLI should write apdus.txt.");
        SelfTestSupport.assertTrue(Files.exists(successArtifacts.resolve("analysis.txt")), "CLI should write analysis.txt.");
        SelfTestSupport.assertTrue(Files.exists(successArtifacts.resolve("result.json")), "CLI should write legacy result.json.");
        SelfTestSupport.assertTrue(Files.exists(successArtifacts.resolve("applets").resolve("all_clean.lop")), "CLI should write applet aggregate output.");

        Path requestInput = root.resolve("request path").resolve("newOS_powercycle_APDU.txt");
        Files.createDirectories(requestInput.getParent());
        Files.writeString(requestInput,
                "SLOT_2 Type = TX Data = { 00 A4 00 04 02 }\n" +
                        "SLOT_2 Type = TX Data = { 3F 00 }\n" +
                        "SLOT_2 Type = RX Data = { 90 00 }\n",
                StandardCharsets.UTF_8);
        Path requestJson = root.resolve("request-path-result.json");
        Path requestFile = root.resolve("parse-request.json");
        Files.writeString(requestFile,
                "{\n" +
                        "  \"input\": \"" + jsonEscape(requestInput.toString()) + "\",\n" +
                        "  \"jsonOut\": \"" + jsonEscape(requestJson.toString()) + "\",\n" +
                        "  \"detectOnly\": \"false\"\n" +
                        "}\n",
                StandardCharsets.UTF_8);
        int requestCode = ApduParserCli.run(new String[] {
                "--request-file", requestFile.toString()
        }, line -> { });
        SelfTestSupport.assertEquals(0, requestCode, "Request-file paths ending in \\newOS must preserve the literal backslash.");
        String requestBody = Files.readString(requestJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(requestBody, "\"id\": \"oppo_txdata\"", "Request-file path regression should still detect the parser.");

        Path unsupportedLog = root.resolve("unsupported.txt");
        Files.writeString(unsupportedLog, "hello world\n", StandardCharsets.UTF_8);
        Path unsupportedJson = root.resolve("unsupported.json");
        List<String> unsupportedErr = new ArrayList<>();
        int unsupportedCode = ApduParserCli.run(new String[] {
                "--input", unsupportedLog.toString(),
                "--json-out", unsupportedJson.toString()
        }, unsupportedErr::add);
        SelfTestSupport.assertEquals(1, unsupportedCode, "Unsupported format should return exit code 1.");
        String unsupportedBody = Files.readString(unsupportedJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(unsupportedBody, "\"status\": \"unsupported\"", "Unsupported JSON should show unsupported status.");
        SelfTestSupport.assertContains(unsupportedBody, "\"supported\": false", "Unsupported JSON should mark parser as unsupported.");

        Path emptyLog = root.resolve("empty.log");
        Files.writeString(emptyLog, "", StandardCharsets.UTF_8);
        Path emptyJson = root.resolve("empty.json");
        int emptyCode = ApduParserCli.run(new String[] {
                "--input", emptyLog.toString(),
                "--json-out", emptyJson.toString()
        }, line -> { });
        SelfTestSupport.assertEquals(2, emptyCode, "Empty input should return malformed-input exit code 2.");
        String emptyBody = Files.readString(emptyJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(emptyBody, "\"status\": \"malformed_input\"", "Malformed JSON should show malformed_input status.");

        int invalidCode = ApduParserCli.run(new String[] {
                "--json-out", root.resolve("invalid.json").toString()
        }, line -> { });
        SelfTestSupport.assertEquals(4, invalidCode, "Missing required arguments should return exit code 4.");

        Path brokenJson = root.resolve("broken").resolve("result.json");
        Path brokenDirParent = root.resolve("blocked-parent");
        Files.writeString(brokenDirParent, "not a directory", StandardCharsets.UTF_8);
        int outputFailureCode = ApduParserCli.run(new String[] {
                "--input", successLog.toString(),
                "--json-out", brokenDirParent.resolve("child").resolve("result.json").toString()
        }, line -> { });
        SelfTestSupport.assertEquals(5, outputFailureCode, "Non-writable json parent should return output write failure.");

        ApduParserProcessor failingProcessor = new ApduParserProcessor(new LogParserRegistry(List.of(new ThrowingParser())));
        Path parserFailureJson = root.resolve("parser-failure.json");
        int parserFailureCode = ApduParserCli.run(new String[] {
                "--input", successLog.toString(),
                "--json-out", parserFailureJson.toString()
        }, line -> { }, failingProcessor);
        SelfTestSupport.assertEquals(3, parserFailureCode, "Unexpected parser exception should return exit code 3.");
        String parserFailureBody = Files.readString(parserFailureJson, StandardCharsets.UTF_8);
        SelfTestSupport.assertContains(parserFailureBody, "\"status\": \"parser_failure\"", "Parser failure JSON should show parser_failure status.");

        System.out.println("ApduParserCliSelfTest passed.");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class ThrowingParser implements LogParser {
        @Override
        public String getId() {
            return "throwing";
        }

        @Override
        public String getDisplayName() {
            return "Throwing Parser";
        }

        @Override
        public List<String> getSupportedExtensions() {
            return List.of(".log");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            return true;
        }

        @Override
        public ParseResult parse(Path inputFile) {
            throw new IllegalStateException("Synthetic parser failure");
        }
    }
}
