public record SourcePluginSpec(
        String packageName,
        String publicClassName,
        String implementationClassName
) {
    public SourcePluginSpec {
        packageName = packageName == null ? "" : packageName;
        publicClassName = publicClassName == null ? "" : publicClassName;
        implementationClassName = implementationClassName == null ? "" : implementationClassName;
    }
}
