package apdu.parser.plugin.api;

public record PluginDetectionResult(boolean matched, int confidence, String reason) {

    public PluginDetectionResult {
        confidence = Math.max(0, confidence);
        reason = reason == null ? "" : reason;
    }

    public static PluginDetectionResult matched(int confidence, String reason) {
        return new PluginDetectionResult(true, confidence, reason);
    }

    public static PluginDetectionResult noMatch(String reason) {
        return new PluginDetectionResult(false, 0, reason);
    }
}
