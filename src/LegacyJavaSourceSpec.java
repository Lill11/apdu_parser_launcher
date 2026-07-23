public record LegacyJavaSourceSpec(
        String packageName,
        String publicClassName,
        String mainClassName
) {
    public LegacyJavaSourceSpec {
        packageName = packageName == null ? "" : packageName;
        publicClassName = publicClassName == null ? "" : publicClassName;
        mainClassName = mainClassName == null ? "" : mainClassName;
    }
}

