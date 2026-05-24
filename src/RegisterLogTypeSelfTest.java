import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class RegisterLogTypeSelfTest {

    public static void main(String[] args) throws Exception {
        ApduParserEngine engine = new ApduParserEngine();
        Path configPath = engine.getLauncherRoot().resolve("config.json");
        Path backupPath = engine.getLauncherRoot().resolve("config.selftest.backup.json");
        String parserName = "selftest_register_parser";

        Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

        try {
            boolean alreadyExists = engine.listParserDefinitions().stream()
                    .anyMatch(definition -> parserName.equals(definition.getName()));
            if (alreadyExists) {
                throw new IllegalStateException("Self-test parser already exists in config.json");
            }

            engine.addParserDefinition(new ApduParserEngine.ParserDefinition(
                    parserName,
                    "../html_apdu_extractor_China_Unicom",
                    "ExtractApdusFromHtml.java",
                    "ExtractApdusFromHtml.java",
                    "sample-selftest.html",
                    "apdus.txt",
                    ".txt",
                    "all",
                    List.of("<html", "APDU:"),
                    List.of(".html"),
                    "",
                    List.of()
            ));

            ApduParserEngine verification = new ApduParserEngine();
            boolean found = verification.listParserDefinitions().stream()
                    .anyMatch(definition -> parserName.equals(definition.getName()));
            if (!found) {
                throw new IllegalStateException("New parser was not persisted to config.json");
            }

            System.out.println("REGISTER_LOG_TYPE_SELF_TEST=PASS");
        } finally {
            Files.copy(backupPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backupPath);
        }
    }
}
