package apdu.parser.plugin.api;

import java.util.List;

public record PluginParseResult(List<String> apdus, List<String> warnings) {

    public PluginParseResult {
        apdus = apdus == null ? List.of() : List.copyOf(apdus);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
