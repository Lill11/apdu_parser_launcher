import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 中文注释: legacy extractor example
// Comentario en español: ejemplo de extractor heredado
public class LegacyPcscExtractor {

    private static final Pattern COMMAND = Pattern.compile("-->\\s*\\[LEGACY]\\s*([0-9A-Fa-f ]+)");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: LegacyPcscExtractor <inputFile> <outputFile>");
            System.exit(1);
            return;
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        extract(input, output);
        System.out.println("Legacy extractor wrote output to " + output.toAbsolutePath());
    }

    private static void extract(Path input, Path output) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = COMMAND.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                String normalized = matcher.group(1).replace(" ", "").toUpperCase(Locale.ROOT);
                writer.write(normalized);
                writer.newLine();
            }
        }
    }
}
