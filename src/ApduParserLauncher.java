import java.util.List;

public class ApduParserLauncher {

    public static void main(String[] args) {
        try {
            LauncherOptions options = LauncherOptions.parse(args);
            ApduParserEngine engine = new ApduParserEngine(options.configPath);
            engine.processAll(options.dryRun, System.out::println);
        } catch (Exception e) {
            System.err.println("Launcher failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static final class LauncherOptions {
        private final String configPath;
        private final boolean dryRun;

        private LauncherOptions(String configPath, boolean dryRun) {
            this.configPath = configPath;
            this.dryRun = dryRun;
        }

        private static LauncherOptions parse(String[] args) {
            String configPath = "config.json";
            boolean dryRun = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--dry-run".equals(arg)) {
                    dryRun = true;
                } else if ("--config".equals(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --config");
                    }
                    configPath = args[++i];
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    printHelp();
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return new LauncherOptions(configPath, dryRun);
        }

        private static void printHelp() {
            System.out.println("Usage: java src/ApduParserLauncher.java [--dry-run] [--config config.json]");
            System.out.println("Tip: use the JavaFX UI when available, or launch_ui.bat for automatic fallback.");
        }
    }
}
