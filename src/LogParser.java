import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface LogParser {

    String getId();

    String getDisplayName();

    List<String> getSupportedExtensions();

    boolean supports(Path file, String sampleContent);

    ParseResult parse(Path inputFile) throws IOException;

    record ParseResult(List<String> apdus, List<String> warnings) {
        public ParseResult {
            apdus = apdus == null ? List.of() : List.copyOf(apdus);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
