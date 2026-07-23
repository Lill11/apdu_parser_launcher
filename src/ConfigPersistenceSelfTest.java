import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigPersistenceSelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("apdu-parser-config");
        Path configPath = root.resolve("config").resolve("config.json");
        Files.createDirectories(configPath.getParent());

        ApduParserEngine.Config config = new ApduParserEngine.Config(
                "logs/imported",
                "output",
                "temp",
                "logs",
                true,
                true,
                true,
                true,
                1440,
                900
        );
        config.save(configPath);

        ApduParserEngine.Config loaded = ApduParserEngine.Config.load(configPath);
        SelfTestSupport.assertTrue(loaded.autoAnalyzeOnImport(), "autoAnalyzeOnImport should persist.");
        SelfTestSupport.assertTrue(loaded.retainDebugArtifacts(), "retainDebugArtifacts should persist.");
        SelfTestSupport.assertTrue(loaded.detectOnlyDefault(), "detectOnlyDefault should persist.");
        SelfTestSupport.assertTrue(loaded.showDiagnosticsOnLaunch(), "showDiagnosticsOnLaunch should persist.");
        SelfTestSupport.assertEquals(1440, loaded.windowWidth(), "window width should persist.");
        SelfTestSupport.assertEquals(900, loaded.windowHeight(), "window height should persist.");

        String json = Files.readString(configPath, StandardCharsets.UTF_8);
        SelfTestSupport.assertTrue(json.contains("\"logsDir\": \"logs\""), "Config file should include logsDir.");

        System.out.println("ConfigPersistenceSelfTest passed.");
    }
}
