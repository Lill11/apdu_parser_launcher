public enum LegacyCommandPattern {
    INPUT_FILE_OUTPUT_FILE,
    INPUT_FILE;

    public static LegacyCommandPattern fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return INPUT_FILE_OUTPUT_FILE;
        }
        for (LegacyCommandPattern pattern : values()) {
            if (pattern.name().equalsIgnoreCase(value)) {
                return pattern;
            }
        }
        return INPUT_FILE_OUTPUT_FILE;
    }
}

