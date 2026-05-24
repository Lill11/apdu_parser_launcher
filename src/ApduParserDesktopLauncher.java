public class ApduParserDesktopLauncher {

    public static void main(String[] args) {
        if (isJavaFxAvailable()) {
            try {
                Class<?> fxApp = Class.forName("ApduParserLauncherFX");
                java.lang.reflect.Method mainMethod = fxApp.getMethod("main", String[].class);
                mainMethod.invoke(null, (Object) args);
                return;
            } catch (Exception ignored) {
                // Fall back to Swing below.
            }
        }

        ApduParserLauncherUI.main(args);
    }

    private static boolean isJavaFxAvailable() {
        try {
            Class.forName("javafx.application.Application");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
