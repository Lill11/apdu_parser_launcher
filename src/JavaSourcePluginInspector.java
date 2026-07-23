import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaSourcePluginInspector {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w\\.]*?)\\s*;");
    private static final Pattern PUBLIC_CLASS_PATTERN = Pattern.compile("(?m)^\\s*public\\s+class\\s+([A-Za-z_][\\w]*)\\b");
    private static final Pattern IMPLEMENTS_PLUGIN_PATTERN = Pattern.compile("implements\\s+[^\\{;]*\\bApduParserPlugin\\b", Pattern.DOTALL);

    public SourcePluginInspection inspect(Path sourceFile) {
        List<String> diagnostics = new ArrayList<>();
        if (sourceFile == null || !sourceFile.toString().toLowerCase().endsWith(".java")) {
            return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                    "Only .java source files are supported.", null, diagnostics);
        }
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                    "Java source file does not exist.", null, diagnostics);
        }

        try {
            String text = Files.readString(sourceFile, StandardCharsets.UTF_8);
            Matcher classMatcher = PUBLIC_CLASS_PATTERN.matcher(text);
            List<String> publicClasses = new ArrayList<>();
            while (classMatcher.find()) {
                publicClasses.add(classMatcher.group(1));
            }
            if (publicClasses.isEmpty()) {
                return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                        "Source file must declare one public class.", null, diagnostics);
            }
            if (publicClasses.size() > 1) {
                diagnostics.add("Public classes: " + String.join(", ", publicClasses));
                return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                        "Source file must declare exactly one public class.", null, diagnostics);
            }
            String className = publicClasses.get(0);
            String expectedFileName = className + ".java";
            if (!expectedFileName.equals(sourceFile.getFileName().toString())) {
                diagnostics.add("Expected filename: " + expectedFileName);
                return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                        "Public class name must match the source filename.", null, diagnostics);
            }
            if (!IMPLEMENTS_PLUGIN_PATTERN.matcher(text).find()) {
                return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                        "Source file must implement ApduParserPlugin.", null, diagnostics);
            }
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(text);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
            String implementationClass = packageName.isBlank() ? className : packageName + "." + className;
            return new SourcePluginInspection(true, ParserValidationStatus.COMPATIBLE,
                    "Source structure is valid.", new SourcePluginSpec(packageName, className, implementationClass), diagnostics);
        } catch (IOException ex) {
            diagnostics.add(ex.toString());
            return new SourcePluginInspection(false, ParserValidationStatus.INVALID_SOURCE,
                    "Failed to read Java source file.", null, diagnostics);
        }
    }
}
