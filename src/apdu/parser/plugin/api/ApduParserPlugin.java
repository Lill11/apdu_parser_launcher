package apdu.parser.plugin.api;

import java.nio.file.Path;
import java.util.List;

public interface ApduParserPlugin {

    String getId();

    String getName();

    String getVersion();

    int getPluginApiVersion();

    List<String> getSupportedExtensions();

    PluginDetectionResult detect(Path inputFile, byte[] sample) throws Exception;

    PluginParseResult parse(Path inputFile) throws Exception;
}
