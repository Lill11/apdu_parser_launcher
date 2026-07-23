package example.source;

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

// 中文注释: 这个示例用于验证 UTF-8 源码编译。
// Comentario en español: análisis rápido del log.
public class SourcePcscPlugin implements ApduParserPlugin {

    private static final Pattern COMMAND = Pattern.compile("-->\\s*\\[PCSC]\\s*([0-9A-Fa-f]+)");

    @Override
    public String getId() {
        return "source_pcsc_plugin";
    }

    @Override
    public String getName() {
        return "Source PCSC Plugin";
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
        if (text.contains("SOURCE_PLUGIN_PCSC")) {
            return PluginDetectionResult.matched(140, "Source plugin marker matched.");
        }
        if (text.contains("[PCSC]")) {
            return PluginDetectionResult.matched(80, "PCSC marker matched.");
        }
        return PluginDetectionResult.noMatch("Source plugin markers not found.");
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
