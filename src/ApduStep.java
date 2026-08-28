import java.util.List;

public record ApduStep(
        String command,
        List<String> expectedStatusWords,
        String expectedStatusExpression,
        int sourceLine
) {
    public ApduStep {
        command = command == null ? "" : command;
        expectedStatusWords = expectedStatusWords == null ? List.of() : List.copyOf(expectedStatusWords);
        expectedStatusExpression = expectedStatusExpression == null ? "" : expectedStatusExpression;
    }
}
