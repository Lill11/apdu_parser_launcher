import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppletExtractor {

    private static final Pattern APDU_PATTERN = Pattern.compile("^#?([0-9A-Fa-f]+)$");

    private AppletExtractor() {
    }

    public static ExtractionResult extract(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return ExtractionResult.notApplicable("No APDUs available yet.");
        }

        List<String> currentApplet = new ArrayList<>();
        Map<String, List<String>> applets = new LinkedHashMap<>();
        List<String> allClean = new ArrayList<>();

        int appletIndex = 0;
        boolean insideApplet = false;
        boolean installForInstallSaved = false;

        for (String line : rawLines) {
            String apdu = normalizeInputLine(line);
            if (apdu == null) {
                continue;
            }
            if (apdu.startsWith("01C0") || apdu.startsWith("00C0") || apdu.startsWith("0170")) {
                continue;
            }

            String cleanApdu = cleanSecureChannelApdu(apdu);
            if (cleanApdu == null || !isGpAppletCommand(cleanApdu)) {
                continue;
            }

            if (cleanApdu.startsWith("80E602")) {
                if (!currentApplet.isEmpty()) {
                    applets.put(fileNameFor(appletIndex), List.copyOf(currentApplet));
                    currentApplet.clear();
                }
                appletIndex++;
                insideApplet = true;
                installForInstallSaved = false;
                addApdu(currentApplet, allClean, cleanApdu);
                continue;
            }

            if (!insideApplet || currentApplet.isEmpty()) {
                continue;
            }

            if (isLoadCommand(cleanApdu)) {
                addApdu(currentApplet, allClean, cleanApdu);
                continue;
            }

            if (cleanApdu.startsWith("80E60C")) {
                if (!installForInstallSaved) {
                    addApdu(currentApplet, allClean, cleanApdu);
                    installForInstallSaved = true;
                }
                applets.put(fileNameFor(appletIndex), List.copyOf(currentApplet));
                currentApplet.clear();
                insideApplet = false;
                installForInstallSaved = false;
            }
        }

        if (!currentApplet.isEmpty()) {
            applets.put(fileNameFor(appletIndex), List.copyOf(currentApplet));
        }

        if (applets.isEmpty()) {
            return ExtractionResult.noApplets("No GlobalPlatform applet installation flow found.");
        }
        return ExtractionResult.extracted(applets, allClean);
    }

    private static String normalizeInputLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = APDU_PATTERN.matcher(line.trim());
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private static void addApdu(List<String> currentApplet, List<String> allClean, String apdu) {
        currentApplet.add(apdu);
        allClean.add(apdu);
    }

    private static boolean isGpAppletCommand(String apdu) {
        return apdu.length() >= 4 && ("E6".equals(apdu.substring(2, 4)) || "E8".equals(apdu.substring(2, 4)));
    }

    private static boolean isLoadCommand(String apdu) {
        return apdu.length() >= 4 && "E8".equals(apdu.substring(2, 4));
    }

    private static String cleanSecureChannelApdu(String apdu) {
        if (apdu.length() < 10) {
            return null;
        }
        String cla = apdu.substring(0, 2);
        if ("80".equals(cla)) {
            return apdu;
        }
        if (!"85".equals(cla) && !"86".equals(cla) && !"87".equals(cla)) {
            return null;
        }

        try {
            String ins = apdu.substring(2, 4);
            String p1 = apdu.substring(4, 6);
            String p2 = apdu.substring(6, 8);
            int lc = Integer.parseInt(apdu.substring(8, 10), 16);
            if (lc < 8) {
                return null;
            }
            int newLc = lc - 8;
            String body = apdu.substring(10);
            if (body.length() < lc * 2) {
                return null;
            }
            String dataWithoutMac = body.substring(0, newLc * 2);
            return "80" + ins + p1 + p2 + String.format(Locale.ROOT, "%02X", newLc) + dataWithoutMac;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String fileNameFor(int index) {
        return String.format(Locale.ROOT, "applet_%03d.lop", index);
    }

    public record ExtractionResult(
            Status status,
            String message,
            List<String> allClean,
            Map<String, List<String>> applets
    ) {
        public enum Status {
            EXTRACTED,
            NO_APPLETS,
            NOT_APPLICABLE
        }

        public static ExtractionResult extracted(Map<String, List<String>> applets, List<String> allClean) {
            return new ExtractionResult(Status.EXTRACTED, "", List.copyOf(allClean), Map.copyOf(applets));
        }

        public static ExtractionResult noApplets(String message) {
            return new ExtractionResult(Status.NO_APPLETS, message, List.of(), Map.of());
        }

        public static ExtractionResult notApplicable(String message) {
            return new ExtractionResult(Status.NOT_APPLICABLE, message, List.of(), Map.of());
        }
    }
}
