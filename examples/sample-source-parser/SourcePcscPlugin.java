package example.source;

import apdu.parser.plugin.api.ApduParserPlugin;
import apdu.parser.plugin.api.PluginConstants;
import apdu.parser.plugin.api.PluginDetectionResult;
import apdu.parser.plugin.api.PluginParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copy this file and replace the marker and extraction pattern with the rules
 * for your customer log. The public class name must match the filename.
 */
public class SourcePcscPlugin implements ApduParserPlugin {

    private static final String LOG_MARKER = "SOURCE_PLUGIN_PCSC";
    private static final Pattern TX_APDU = Pattern.compile(
            "\\bTX_APDU\\s*[:=]\\s*([0-9A-Fa-f ]+)\\s*$");
    private static final Pattern PCSC_COMMAND = Pattern.compile(
            "-->\\s*\\[PCSC]\\s*([0-9A-Fa-f]+)");

    @Override
    public String getId() {
        // Must be unique, stable, lowercase, and must not match another parser.
        return "source_pcsc_plugin";
    }

    @Override
    public String getName() {
        return "Source Parser Example";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public int getPluginApiVersion() {
        return PluginConstants.CURRENT_PLUGIN_API_VERSION;
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of(".log", ".txt");
    }

    @Override
    public PluginDetectionResult detect(Path inputFile, byte[] sample) {
        String text = sample == null ? "" : new String(sample, StandardCharsets.UTF_8);
        if (text.contains(LOG_MARKER)) {
            return PluginDetectionResult.matched(
                    120, "The source-parser example marker was found.");
        }
        return PluginDetectionResult.noMatch(
                "The source-parser example marker was not found.");
    }

    @Override
    public PluginParseResult parse(Path inputFile) throws IOException {
        List<String> apdus = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> lines = Files.readAllLines(inputFile, StandardCharsets.UTF_8);

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = TX_APDU.matcher(line);
            if (!matcher.find()) {
                matcher = PCSC_COMMAND.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
            }

            String apdu = matcher.group(1)
                    .replace(" ", "")
                    .toUpperCase(Locale.ROOT);
            if (apdu.length() < 8 || (apdu.length() % 2) != 0) {
                warnings.add("Ignored malformed APDU at line " + (index + 1) + ".");
                continue;
            }
            apdus.add(apdu);
        }

        if (apdus.isEmpty()) {
            warnings.add("No TX_APDU records were extracted.");
        }
        return new PluginParseResult(apdus, warnings);
    }
}
