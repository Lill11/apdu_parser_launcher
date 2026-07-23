import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class RegisterLogTypeSelfTest {

    public static void main(String[] args) throws Exception {
        String uiSource = Files.readString(Path.of("src", "ApduParserLauncherUI.java"), StandardCharsets.UTF_8);
        SelfTestSupport.assertTrue(!uiSource.contains("Register Log Type"), "Main UI should no longer expose Register Log Type.");

        String engineSource = Files.readString(Path.of("src", "ApduParserEngine.java"), StandardCharsets.UTF_8);
        SelfTestSupport.assertTrue(!engineSource.contains("extractorFolder"), "Engine should not contain extractorFolder.");
        SelfTestSupport.assertTrue(!engineSource.contains("scriptFile"), "Engine should not contain scriptFile configuration.");
        SelfTestSupport.assertTrue(!engineSource.contains("stagedInputFileName"), "Engine should not contain staged input file names.");
        SelfTestSupport.assertTrue(!engineSource.contains("commandArgs"), "Engine should not contain runtime command args for external parsers.");

        System.out.println("RegisterLogTypeSelfTest passed.");
    }
}
