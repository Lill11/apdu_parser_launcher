import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface LogParser {

    String getId();

    String getDisplayName();

    List<String> getSupportedExtensions();

    boolean supports(Path file, String sampleContent);

    ParseResult parse(Path inputFile) throws IOException;

    record ParseResult(List<String> apdus, List<String> warnings, List<ParsedLogEvent> events) {
        public ParseResult(List<String> apdus, List<String> warnings) {
            this(apdus, warnings, List.of());
        }

        public ParseResult {
            apdus = apdus == null ? List.of() : List.copyOf(apdus);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}
