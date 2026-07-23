package example;

import apdu.parser.plugin.api.ApduParserPlugin;
import apdu.parser.plugin.api.PluginConstants;
import apdu.parser.plugin.api.PluginDetectionResult;
import apdu.parser.plugin.api.PluginParseResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SamplePcscPlugin implements ApduParserPlugin {

    private static final Pattern COMMAND = Pattern.compile("-->\\s*\\[PCSC]\\s*([0-9A-Fa-f]+)");

    @Override
    public String getId() {
        return "sample_pcsc_plugin";
    }

    @Override
    public String getName() {
        return "Sample PCSC Plugin";
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
        if (text.contains("SAMPLE_PLUGIN_PCSC")) {
            return PluginDetectionResult.matched(130, "Sample plugin marker matched.");
        }
        if (text.contains("[PCSC]")) {
            return PluginDetectionResult.matched(85, "PCSC marker matched.");
        }
        return PluginDetectionResult.noMatch("Sample plugin markers not found.");
    }

    @Override
    public PluginParseResult parse(Path inputFile) throws IOException {
        List<String> apdus = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = COMMAND.matcher(line);
                if (matcher.find()) {
                    apdus.add(matcher.group(1).toUpperCase(Locale.ROOT));
                }
            }
        }
        return new PluginParseResult(apdus, List.of());
    }
}
