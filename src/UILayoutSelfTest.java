import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class UILayoutSelfTest {

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of("src", "ApduParserLauncherUI.java"), StandardCharsets.UTF_8);
        SelfTestSupport.assertTrue(source.contains("Import Logs"), "UI should expose Import Logs.");
        SelfTestSupport.assertTrue(source.contains("Analyze"), "UI should expose Analyze.");
        SelfTestSupport.assertTrue(source.contains("Open Results"), "UI should expose Open Results.");
        SelfTestSupport.assertTrue(source.contains("APDUs"), "UI should have APDUs tab.");
        SelfTestSupport.assertTrue(source.contains("Analysis"), "UI should have Analysis tab.");
        SelfTestSupport.assertTrue(source.contains("Applets"), "UI should have Applets tab.");
        SelfTestSupport.assertTrue(source.contains("Errors"), "UI should have Errors tab.");
        SelfTestSupport.assertTrue(!source.contains("Extract Applets from APDUs"), "UI should not expose a separate Extract Applets action.");

        System.out.println("UILayoutSelfTest passed.");
    }
}
