import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class InternalParsersSelfTest {

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("apdu-parser-parsers");
        LogParserRegistry registry = new LogParserRegistry();

        assertDetection(registry, write(temp, "honor.log",
                "foo\nAPDU_tx 0: 80 7C 04 00\nAPDU_tx 1: 09 80 01 02 90 01 01 91 01 02\nbar\n"),
                "honor_apdutx",
                "807C040009800102900101910102");

        assertDetection(registry, write(temp, "oppo.log",
                "Type = TX Data = 80 12 00 00 0B\nType = RX Data = 12\nType = TX Data = 01 02 03 04 05 06 07 08 09 0A 0B\n"),
                "oppo_txdata",
                "801200000B0102030405060708090A0B");

        assertDetection(registry, write(temp, "oppo_search_export.log",
                "Search \"TX Data = |RX Data =\"\nLine 10: TX Data = { 80 12 00 00 0B }\nLine 11: RX Data = 12\nLine 12: TX Data = { 01 02 03 04 05 06 07 08 09 0A 0B }\n"),
                "oppo_txdata",
                "801200000B0102030405060708090A0B");

        assertDetection(registry, write(temp, "oppo_braced_columns.log",
                "Line 20: TX Data = { 80 12 00 00 02 }   98 4D 22 10\n" +
                        "Line 21: RX Data = 12   98 4D 22 11\n" +
                        "Line 22: TX Data = { CA FE }   98 4D 22 12\n"),
                "oppo_txdata",
                "8012000002CAFE");

        assertDetection(registry, write(temp, "oh.log",
                "AA BB FF FF 00 00 00 01 00 02 80 7C 01 02 19\n"),
                "oh_bytes",
                "807C010219");

        assertDetection(registry, write(temp, "unisoc.log",
                "tx_data_len[5]\n[T]USIMDRV 0x00 0xA4 0x04 0x00 0x00\n"),
                "usimdrv_unisoc",
                "00A4040000");

        assertDetection(registry, write(temp, "pcsc.log",
                "--> [PCSC]/////////// 01C000000089\n"),
                "pcsc_terminal",
                "01C000000089");

        assertDetection(registry, write(temp, "report.html",
                "<html><body>APDU: 80 14 00 00 00</body></html>\n"),
                "html_apdu",
                "8014000000");

        Path unsupported = write(temp, "unsupported.txt", "hello world\n");
        LogParserRegistry.DetectionResult detection = registry.detect(unsupported);
        SelfTestSupport.assertTrue(!detection.supported(), "Unsupported sample should not match any parser.");

        System.out.println("InternalParsersSelfTest passed.");
    }

    private static void assertDetection(LogParserRegistry registry, Path file, String expectedId, String expectedApdu) throws Exception {
        LogParserRegistry.DetectionResult detection = registry.detect(file);
        SelfTestSupport.assertTrue(detection.supported(), "Expected parser for " + file.getFileName());
        SelfTestSupport.assertEquals(expectedId, detection.parser().getId(), "Wrong parser detected.");
        List<String> apdus = detection.parser().parse(file).apdus();
        SelfTestSupport.assertTrue(!apdus.isEmpty(), "Expected APDUs from parser " + expectedId);
        SelfTestSupport.assertEquals(expectedApdu, apdus.get(0), "Unexpected first APDU for " + expectedId);
    }

    private static Path write(Path directory, String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
