import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ChinaUnicomJavaExportSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("china-unicom-java-export");
        Path html = root.resolve("report.html");
        Files.writeString(html, sampleHtml(), Charset.forName("GB2312"));

        LogParserRegistry registry = new LogParserRegistry();
        LogParserRegistry.DetectionResult detection = registry.detect(html);
        SelfTestSupport.assertTrue(detection.supported(), "China Unicom HTML should be detected.");
        SelfTestSupport.assertEquals("html_apdu", detection.parserId(), "Wrong HTML parser detected.");

        LogParser.ParseResult parsed = detection.parser().parse(html);
        SelfTestSupport.assertEquals(4, parsed.apdus().size(), "Only Terminal-to-Card APDUs should be extracted.");
        SelfTestSupport.assertEquals(4, parsed.apduSteps().size(), "Every command should retain its Expected SW pairing.");
        SelfTestSupport.assertEquals("00A40004023F00", parsed.apduSteps().get(0).command(), "APDU order changed.");
        SelfTestSupport.assertEquals(List.of("9000"), parsed.apduSteps().get(0).expectedStatusWords(), "Expected SW not paired.");
        SelfTestSupport.assertEquals(List.of("9000", "91XX"), parsed.apduSteps().get(1).expectedStatusWords(), "Multiple SW values lost.");
        SelfTestSupport.assertTrue(parsed.apduSteps().get(2).expectedStatusWords().isEmpty(), "Actual SW must not become Expected SW.");
        SelfTestSupport.assertEquals(List.of("6985"), parsed.apduSteps().get(3).expectedStatusWords(), "Explicit row Expected SW not paired.");

        String generated = ApduJavaGenerator.generate("report.html", parsed.apduSteps());
        SelfTestSupport.assertContains(generated, "package javaTest;", "Package declaration missing.");
        SelfTestSupport.assertContains(generated, "public class report extends UiccTestModel", "Generated class name is wrong.");
        SelfTestSupport.assertEquals(1, countOccurrences(generated, "test.reset();"), "Reset must run exactly once.");
        SelfTestSupport.assertContains(generated, "numErrors += method0();", "run() must invoke the generated method.");
        SelfTestSupport.assertContains(generated,
                "response = test.sendApdu(\"00 A4 00 04 02 3F 00\");",
                "Generated APDU spacing is incorrect.");
        SelfTestSupport.assertContains(generated,
                "if (response.checkSw(\"9000\") == false) {numErrors += 1;}",
                "Single Expected SW check missing.");
        SelfTestSupport.assertContains(generated,
                "if (response.checkSw(\"9000\") == false && response.checkSw(\"91XX\") == false) {numErrors += 1;}",
                "Multiple Expected SW checks missing.");
        SelfTestSupport.assertContains(generated,
                "response = test.sendApdu(\"00 C0 00 00 0A\");" + System.lineSeparator()
                        + "        // TODO: Expected SW not found in source HTML",
                "Missing Expected SW should produce a TODO.");
        SelfTestSupport.assertTrue(!generated.contains("6A82"), "Actual status word leaked into generated expectations.");
        SelfTestSupport.assertTrue(!generated.contains("BF31"), "Unrelated nested expected value was treated as a status word.");

        Path json = root.resolve("result.json");
        Path artifacts = root.resolve("artifacts");
        int exitCode = ApduParserCli.run(new String[] {
                "--input", html.toString(),
                "--json-out", json.toString(),
                "--artifacts-dir", artifacts.toString()
        }, line -> { });
        SelfTestSupport.assertEquals(0, exitCode, "CLI parse should succeed.");
        String body = Files.readString(json);
        SelfTestSupport.assertContains(body, "\"apduSteps\"", "Structured APDU steps missing from JSON.");
        SelfTestSupport.assertContains(body, "\"expectedStatusWords\": [\"9000\", \"91XX\"]", "Multiple Expected SW JSON missing.");
        SelfTestSupport.assertContains(body, "\"generatedJavaClassName\": \"report\"", "Generated class name missing from JSON.");
        SelfTestSupport.assertTrue(Files.exists(artifacts.resolve("report.java")), "Java artifact was not written.");
        SelfTestSupport.assertEquals(generated, Files.readString(artifacts.resolve("report.java")), "Java artifact differs from generator.");

        verifyMethodSplitting();

        System.out.println("ChinaUnicomJavaExportSelfTest passed.");
    }

    private static void verifyMethodSplitting() {
        List<ApduStep> steps = new ArrayList<>();
        for (int i = 0; i < 126; i++) {
            steps.add(new ApduStep(String.format("00A40000%02X", i & 0xFF), List.of("9000"), "9000", i + 1));
        }
        String generated = ApduJavaGenerator.generate("123 test-case.html", steps);
        SelfTestSupport.assertContains(generated, "public class _123_test_case extends UiccTestModel", "Class name was not sanitized.");
        SelfTestSupport.assertEquals(3, ApduJavaGenerator.methodCount(steps.size()), "Wrong method count.");
        SelfTestSupport.assertEquals(50, sendsInMethod(generated, 0), "method0 should contain 50 APDUs.");
        SelfTestSupport.assertEquals(50, sendsInMethod(generated, 1), "method1 should contain 50 APDUs.");
        SelfTestSupport.assertEquals(26, sendsInMethod(generated, 2), "method2 should contain 26 APDUs.");
    }

    private static int sendsInMethod(String generated, int methodIndex) {
        String startMarker = "private int method" + methodIndex + "()";
        int start = generated.indexOf(startMarker);
        int end = generated.indexOf("private int method" + (methodIndex + 1) + "()", start + startMarker.length());
        String body = generated.substring(start, end < 0 ? generated.length() : end);
        return countOccurrences(body, "response = test.sendApdu(");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String sampleHtml() {
        return "<html><body>"
                + row(12, "APDU: 00A40004023F00")
                + row(12, "\u671F\u671B\u72B6\u6001\u503C: \u65E0")
                + row(12, "\u5B9E\u9645\u72B6\u6001\u503C: 612A")
                + row(12, "\u671F\u671B\u72B6\u6001\u5B57:")
                + row(15, "\u671F\u671B\u503C: 9000")
                + row(12, "APDU: 8010000000")
                + row(12, "\u671F\u671B\u72B6\u6001\u503C: \u65E0")
                + row(12, "\u5B9E\u9645\u72B6\u6001\u503C: 9000")
                + row(12, "\u671F\u671B\u72B6\u6001\u5B57:")
                + row(15, "\u671F\u671B\u503C: 9000/91XX")
                + row(12, "APDU: 00C000000A")
                + row(12, "\u671F\u671B\u72B6\u6001\u503C: \u65E0")
                + row(12, "\u5B9E\u9645\u72B6\u6001\u503C: 6A82")
                + row(12, "Payload tag:")
                + row(15, "\u671F\u671B\u503C: BF31")
                + row(12, "APDU: 8014000000")
                + row(12, "\u671F\u671B\u72B6\u6001\u503C: 6985")
                + row(12, "\u5B9E\u9645\u72B6\u6001\u503C: 6985")
                + "</body></html>";
    }

    private static String row(int indent, String text) {
        return "<TR><TABLE CELLSPACING=\"0\" BORDER=\"0\" CELLPADDING=\"0\"><TR><TD>"
                + "&nbsp;".repeat(indent)
                + "</TD><TD><IMG SRC=\"Images/Text.gif\"></TD><TD>"
                + text
                + "</TD></TR></TABLE></TR>";
    }
}
