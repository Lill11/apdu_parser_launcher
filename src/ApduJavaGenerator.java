import java.util.ArrayList;
import java.util.Set;
import java.util.List;

public final class ApduJavaGenerator {

    public static final int MAX_STEPS_PER_METHOD = 50;
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "record", "sealed", "permits", "non-sealed", "var", "yield"
    );

    private ApduJavaGenerator() {
    }

    public static String generate(List<ApduStep> steps) {
        return generate("GeneratedApduTest", steps);
    }

    public static String generate(String sourceFileName, List<ApduStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        String className = classNameFor(sourceFileName);
        int methodCount = methodCount(steps.size());
        List<String> lines = new ArrayList<>();
        lines.add("package javaTest;");
        lines.add("");
        lines.add("import org.etsi.scp.wg3.uicc.jcapi.userclass.UiccTestModel;");
        lines.add("import org.etsi.scp.wg3.uicc.jcapi.userinterface.APDUResponse;");
        lines.add("import org._3gpp.ct.wg6.usim.jcapi.userclass.USimAPITestCardService;");
        lines.add("");
        lines.add("public class " + className + " extends UiccTestModel {");
        lines.add("");
        lines.add("    private USimAPITestCardService test = null;");
        lines.add("");
        lines.add("    int numErrors = 0;");
        lines.add("");
        lines.add("    // Response to the executed command");
        lines.add("    private APDUResponse response = null;");
        lines.add("");
        lines.add("    public " + className + "() {");
        lines.add("        test = USimAPITestCardService.getTheUSimTestCardService();");
        lines.add("    }");
        lines.add("");
        lines.add("    public boolean run() {");
        lines.add("");
        lines.add("        test.reset();");
        lines.add("");
        for (int i = 0; i < methodCount; i++) {
            lines.add("        numErrors += method" + i + "();");
        }
        lines.add("");
        lines.add("        if (numErrors == 0) {");
        lines.add("            return true;");
        lines.add("        } else {");
        lines.add("            return false;");
        lines.add("        }");
        lines.add("    }");

        for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
            int from = methodIndex * MAX_STEPS_PER_METHOD;
            int to = Math.min(from + MAX_STEPS_PER_METHOD, steps.size());
            lines.add("");
            lines.add("    private int method" + methodIndex + "() {");
            lines.add("");
            for (ApduStep step : steps.subList(from, to)) {
                appendStep(lines, step);
            }
            lines.add("");
            lines.add("        return numErrors;");
            lines.add("    }");
        }
        lines.add("}");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    public static String classNameFor(String sourceFileName) {
        String value = sourceFileName == null ? "" : sourceFileName.trim();
        int extension = value.lastIndexOf('.');
        if (extension > 0) {
            value = value.substring(0, extension);
        }
        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (sanitized.isEmpty() && !Character.isJavaIdentifierStart(character)) {
                sanitized.append('_');
            }
            sanitized.append(Character.isJavaIdentifierPart(character) ? character : '_');
        }
        if (sanitized.isEmpty()) {
            sanitized.append("GeneratedApduTest");
        } else if (!Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized.insert(0, '_');
        }
        if (JAVA_KEYWORDS.contains(sanitized.toString())) {
            sanitized.insert(0, '_');
        }
        return sanitized.toString();
    }

    public static int methodCount(int stepCount) {
        return stepCount <= 0 ? 0 : (stepCount + MAX_STEPS_PER_METHOD - 1) / MAX_STEPS_PER_METHOD;
    }

    private static void appendStep(List<String> lines, ApduStep step) {
        lines.add("        response = test.sendApdu(\"" + spaceHexBytes(step.command()) + "\");");
        if (step.expectedStatusWords().isEmpty()) {
            lines.add("        // TODO: Expected SW not found in source HTML");
            return;
        }
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < step.expectedStatusWords().size(); i++) {
            if (i > 0) {
                condition.append(" && ");
            }
            condition.append("response.checkSw(\"")
                    .append(escapeJava(step.expectedStatusWords().get(i)))
                    .append("\") == false");
        }
        lines.add("        if (" + condition + ") {numErrors += 1;}");
    }

    static String spaceHexBytes(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").toUpperCase();
        if (!normalized.matches("[0-9A-F]+") || (normalized.length() & 1) != 0) {
            return value == null ? "" : value.trim();
        }
        StringBuilder spaced = new StringBuilder(normalized.length() + normalized.length() / 2);
        for (int i = 0; i < normalized.length(); i += 2) {
            if (i > 0) {
                spaced.append(' ');
            }
            spaced.append(normalized, i, i + 2);
        }
        return spaced.toString();
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
