import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApduOutputAnalyzer {

    private static final Pattern HEX_TOKEN_RE = Pattern.compile("[0-9A-Fa-f]{2}");
    private static final Pattern INLINE_TX_RX_RE = Pattern.compile(
            "Type\\s*=\\s*(TX|RX)\\s+Data\\s*=\\s*(?:\\{([^}]*)\\}|([0-9A-Fa-f][0-9A-Fa-f ]*))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COLON_TX_RX_RE = Pattern.compile(
            "\\b(TX|RX):\\s*([0-9A-Fa-f][0-9A-Fa-f ]*)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APDU_TX_RX_RE = Pattern.compile(
            "\\bAPDU_(tx|rx)\\b[^:]*:\\s*([0-9A-Fa-f][0-9A-Fa-f ]*)",
            Pattern.CASE_INSENSITIVE
    );
    private static final String TAG_BF22 = "BF22";
    private static final String TAG_BF23 = "BF23";
    private static final String TAG_BF24 = "BF24";
    private static final String TAG_BF25 = "BF25";
    private static final String TAG_BF26 = "BF26";
    private static final String TAG_BF27 = "BF27";
    private static final String TAG_BF28 = "BF28";
    private static final String TAG_BF29 = "BF29";
    private static final String TAG_BF2A = "BF2A";
    private static final String TAG_BF2B = "BF2B";
    private static final String TAG_BF2D = "BF2D";
    private static final String TAG_BF2E = "BF2E";
    private static final String TAG_BF30 = "BF30";
    private static final String TAG_BF31 = "BF31";
    private static final String TAG_BF32 = "BF32";
    private static final String TAG_BF33 = "BF33";
    private static final String TAG_BF34 = "BF34";
    private static final String TAG_BF38 = "BF38";
    private static final String TAG_BF3C = "BF3C";
    private static final String TAG_BF3E = "BF3E";
    private static final String TAG_BF3F = "BF3F";
    private static final String TAG_BF43 = "BF43";
    private static final Map<String, String> ES10_TAG_NAMES = Map.ofEntries(
            Map.entry("BF20", "GetEuiccInfo1 / EUICCInfo1"),
            Map.entry("BF21", "PrepareDownload"),
            Map.entry(TAG_BF22, "GetEuiccInfo2Request"),
            Map.entry(TAG_BF23, "InitialiseSecureChannel"),
            Map.entry(TAG_BF24, "ConfigureISDP"),
            Map.entry(TAG_BF25, "StoreMetadata"),
            Map.entry(TAG_BF26, "ReplaceSessionKeyResponse"),
            Map.entry(TAG_BF27, "ProfileInstallationReceipt"),
            Map.entry(TAG_BF28, "ListNotification / ProfileInstallationResult"),
            Map.entry(TAG_BF29, "SetNickname"),
            Map.entry(TAG_BF2A, "UpdateMetadata"),
            Map.entry(TAG_BF2B, "RetrieveNotificationsList"),
            Map.entry(TAG_BF2D, "GetProfiles"),
            Map.entry(TAG_BF2E, "GetEuiccChallenge"),
            Map.entry(TAG_BF30, "RemoveNotificationFromList"),
            Map.entry(TAG_BF31, "EnableProfile"),
            Map.entry(TAG_BF32, "DisableProfile"),
            Map.entry(TAG_BF33, "DeleteProfile"),
            Map.entry(TAG_BF34, "eUICCMemoryReset"),
            Map.entry(TAG_BF38, "AuthenticateServer"),
            Map.entry(TAG_BF3C, "GetConfiguredAddresses"),
            Map.entry(TAG_BF3E, "GetEID"),
            Map.entry(TAG_BF3F, "SetDefaultSmdpAddress"),
            Map.entry(TAG_BF43, "GetRAT")
    );

    private ApduOutputAnalyzer() {
    }

    public static Path buildEnhancedOutputPath(Path rawOutputPath) {
        String name = rawOutputPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String extension = dot >= 0 ? name.substring(dot) : ".txt";
        return rawOutputPath.resolveSibling(base + ".analysis" + extension);
    }

    public static void analyze(Path originalLog, Path rawOutputPath, Path enhancedOutputPath) throws IOException {
        List<AnalysisItem> items = analyzeEntries(originalLog, rawOutputPath);
        String rendered = renderEnhancedOutput(items, FilterMode.ALL);
        if (enhancedOutputPath.getParent() != null) {
            Files.createDirectories(enhancedOutputPath.getParent());
        }
        Files.writeString(
                enhancedOutputPath,
                rendered,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public static List<AnalysisItem> analyzeEntries(Path originalLog, Path rawOutputPath) throws IOException {
        List<String> rawLines = Files.exists(rawOutputPath)
                ? Files.readAllLines(rawOutputPath, StandardCharsets.UTF_8)
                : List.of();
        return analyzeEntries(originalLog, rawLines);
    }

    public static List<AnalysisItem> analyzeEntries(Path originalLog, List<String> rawLines) throws IOException {
        return analyzeEntries(originalLog, rawLines, eventsFromRawLines(rawLines));
    }

    public static List<AnalysisItem> analyzeEntries(
            Path originalLog,
            List<String> rawLines,
            List<ParsedLogEvent> parsedEvents
    ) throws IOException {
        List<String> originalLines = Files.exists(originalLog)
                ? Files.readAllLines(originalLog, StandardCharsets.ISO_8859_1)
                : List.of();

        List<ParsedExchange> exchanges = parseOriginalLog(originalLines);
        List<AnalysisItem> commandItems = new ArrayList<>();
        int exchangeCursor = 0;
        int apduIndex = 0;

        for (String rawLine : rawLines) {
            String normalized = normalizeHex(rawLine);
            if (normalized.isBlank()) {
                continue;
            }

            apduIndex++;
            ExchangeMatch match = matchExchange(exchanges, exchangeCursor, normalized);
            ParsedExchange exchange = match == null ? null : match.exchange();
            if (match != null) {
                exchangeCursor = match.nextIndex();
            }
            commandItems.add(analyzeExchange(apduIndex, normalized, exchange));
        }

        List<AnalysisItem> items = new ArrayList<>();
        int commandCursor = 0;
        int eventSequence = 0;
        for (ParsedLogEvent event : parsedEvents == null ? List.<ParsedLogEvent>of() : parsedEvents) {
            eventSequence++;
            if (event instanceof ParsedLogEvent.Reset reset) {
                items.add(AnalysisItem.reset(
                        eventSequence,
                        reset.resetType().name(),
                        reset.atr(),
                        reset.sourceLine()
                ));
                continue;
            }
            if (event instanceof ParsedLogEvent.Apdu apdu && commandCursor < commandItems.size()) {
                AnalysisItem command = commandItems.get(commandCursor++);
                items.add(command.withEventSequence(eventSequence, apdu.sourceLine()));
            }
        }
        while (commandCursor < commandItems.size()) {
            eventSequence++;
            items.add(commandItems.get(commandCursor++).withEventSequence(eventSequence, -1));
        }
        return items;
    }

    private static List<ParsedLogEvent> eventsFromRawLines(List<String> rawLines) {
        List<ParsedLogEvent> events = new ArrayList<>();
        int lineNumber = 0;
        for (String rawLine : rawLines == null ? List.<String>of() : rawLines) {
            lineNumber++;
            if (rawLine != null && "RESET".equalsIgnoreCase(rawLine.strip())) {
                events.add(new ParsedLogEvent.Reset(ParsedLogEvent.ResetType.COLD_RESET, "", lineNumber));
                continue;
            }
            String normalized = normalizeHex(rawLine);
            if (!normalized.isBlank()) {
                events.add(new ParsedLogEvent.Apdu(normalized, lineNumber));
            }
        }
        return events;
    }

    public static String renderEnhancedOutput(List<AnalysisItem> items, FilterMode filterMode) {
        StringBuilder sb = new StringBuilder();
        for (AnalysisItem item : items) {
            if (!item.matches(filterMode)) {
                continue;
            }

            sb.append("[")
                    .append(String.format(Locale.ROOT, "%04d", item.eventSequence))
                    .append("] ");

            if (item.isResetMarker()) {
                sb.append("RESET");
                sb.append(System.lineSeparator());
                continue;
            }

            sb.append(item.headline);
            if (item.isErrorLike()) {
                sb.append("  <").append(item.severity).append(">");
            }
            if (!item.statusWord.equals("-") && !"OK".equals(item.severity)) {
                sb.append("  SW=").append(item.statusWord);
            }
            sb.append(System.lineSeparator());

            if (!item.tagLabel.isBlank()) {
                sb.append("Tag: ").append(item.tagLabel).append(System.lineSeparator());
            }
            sb.append("APDU: ").append(item.commandApdu).append(System.lineSeparator());
            if (!item.responseApdu.equals("-")) {
                sb.append("Response: ").append(item.responseApdu).append(System.lineSeparator());
            }
            if (!item.statusWord.equals("-")) {
                sb.append("Severity: ").append(item.severity).append("  SW=").append(item.statusWord);
                sb.append(System.lineSeparator());
            }
            if (!item.note.isBlank()) {
                sb.append(item.note).append(System.lineSeparator());
            }
            sb.append(System.lineSeparator());
        }

        return sb.toString().isBlank()
                ? "No analysis entries match the current filter."
                : sb.toString().trim() + System.lineSeparator();
    }

    static AnalysisResult analyzeApdu(String normalizedApdu) {
        AnalysisItem item = analyzeExchange(0, normalizedApdu, null);
        return new AnalysisResult(item.commandName, item.statusWord, item.severity, item.note);
    }

    public static List<AnalysisItem> filterEntries(List<AnalysisItem> items, FilterMode filterMode) {
        List<AnalysisItem> filtered = new ArrayList<>();
        for (AnalysisItem item : items) {
            if (item.matches(filterMode)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    static String normalizeHex(String value) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = HEX_TOKEN_RE.matcher(value == null ? "" : value);
        while (matcher.find()) {
            sb.append(matcher.group().toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static AnalysisItem analyzeExchange(int sequenceIndex, String commandApdu, ParsedExchange exchange) {
        String normalizedCommand = normalizeHex(commandApdu);
        CommandDescriptor descriptor = detectCommand(normalizedCommand);
        String responseApdu = exchange == null ? "-" : normalizeHex(exchange.responseApdu);
        if (responseApdu.isBlank()) {
            responseApdu = "-";
        }

        String statusWord = extractStatusWord(responseApdu, normalizedCommand);
        String severity = classifySeverity(statusWord);
        String note = buildStatusNote(statusWord);
        if (note.isBlank()) {
            note = descriptor.note;
        } else if (!descriptor.note.isBlank()) {
            note = note + " | " + descriptor.note;
        }

        String headline = buildHeadline(descriptor, severity, statusWord);
        return AnalysisItem.command(
                sequenceIndex,
                normalizedCommand,
                responseApdu,
                descriptor.commandName,
                headline,
                statusWord,
                severity,
                note,
                descriptor.tagLabel,
                exchange == null ? -1 : exchange.sourceLine,
                descriptor.es10,
                descriptor.fetchOrTerminalResponse,
                descriptor.configureLsi
        );
    }

    private static CommandDescriptor detectCommand(String apdu) {
        if (apdu.length() < 4) {
            return new CommandDescriptor("Raw APDU", "Raw APDU", "", "", false, false, false);
        }

        String tag = detectEs10Tag(apdu);
        String commandName = detectBasicCommand(apdu);
        boolean fetchTr = "FETCH".equals(commandName) || "Terminal Response".equals(commandName);
        boolean resetLse = apdu.startsWith("807C0102") || apdu.startsWith("817C0102")
                || apdu.startsWith("807C0101") || apdu.startsWith("817C0101");
        boolean configureLsi = apdu.startsWith("807C0400") || apdu.startsWith("817C0400");
        boolean manageLsi = configureLsi || apdu.startsWith("807C") || apdu.startsWith("817C");

        String note = "";
        String headline = commandName;
        boolean es10 = false;

        if (!tag.isBlank()) {
            es10 = true;
            String semantic = ES10_TAG_NAMES.get(tag);
            headline = "ES10 / " + semantic;
            tag = tag + " (" + semantic + ")";
        } else if (resetLse) {
            headline = "Reset LSE";
        } else if (configureLsi) {
            headline = "Configure LSI";
        } else if (manageLsi) {
            headline = "Manage LSI";
        } else if ("Unhandled command".equals(commandName)) {
            headline = "Unhandled command";
        }

        return new CommandDescriptor(commandName, headline, tag, note, es10, fetchTr, configureLsi);
    }

    private static String detectBasicCommand(String apdu) {
        String claIns = apdu.substring(0, 4);
        return switch (claIns) {
            case "8010" -> "Terminal Profile";
            case "8012" -> "FETCH";
            case "8014" -> "Terminal Response";
            case "807C", "817C" -> "Manage LSI";
            case "00A4", "01A4" -> "SELECT";
            case "00C0", "01C0" -> "GET RESPONSE";
            case "00B0", "01B0" -> "READ BINARY";
            case "00B2", "01B2" -> "READ RECORD";
            case "0070", "0170" -> "MANAGE CHANNEL";
            case "80E2", "81E2", "80C2", "81C2" -> "ENVELOPE";
            default -> "Unhandled command";
        };
    }

    private static String detectEs10Tag(String apdu) {
        String data = extractCommandData(apdu);
        if (data.isBlank()) {
            return "";
        }

        for (String tag : ES10_TAG_NAMES.keySet()) {
            if (data.startsWith(tag)) {
                return tag;
            }
        }
        return "";
    }

    private static String extractCommandData(String apdu) {
        if (apdu == null || apdu.length() < 10 || (apdu.length() % 2) != 0) {
            return "";
        }

        try {
            int totalBytes = apdu.length() / 2;
            int lc = Integer.parseInt(apdu.substring(8, 10), 16);
            if (lc <= 0) {
                return "";
            }

            int dataBytesAvailable = totalBytes - 5;
            if (dataBytesAvailable < lc) {
                return "";
            }

            int dataEnd = 10 + (lc * 2);
            if (dataEnd > apdu.length()) {
                return "";
            }
            return apdu.substring(10, dataEnd);
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private static String buildHeadline(CommandDescriptor descriptor, String severity, String statusWord) {
        if ("ERROR".equals(severity)) {
            return "ERROR / " + descriptor.headline;
        }
        if ("WARNING".equals(severity)) {
            return "WARNING / " + descriptor.headline;
        }
        return descriptor.headline;
    }

    private static String extractStatusWord(String responseApdu, String commandApdu) {
        String response = normalizeHex(responseApdu);
        if (response.length() >= 4) {
            String trailing = response.substring(response.length() - 4);
            if (isRecognizedStatusWord(trailing)) {
                return trailing;
            }
        }
        return "-";
    }

    private static boolean isRecognizedStatusWord(String sw) {
        return "9000".equals(sw)
                || "6F00".equals(sw)
                || "6D00".equals(sw)
                || "6E00".equals(sw)
                || "6985".equals(sw)
                || "6A88".equals(sw)
                || sw.matches("91[0-9A-F]{2}")
                || sw.matches("6C[0-9A-F]{2}");
    }

    private static String classifySeverity(String sw) {
        if ("9000".equals(sw)) {
            return "OK";
        }
        if (sw.matches("91[0-9A-F]{2}")) {
            return "INFO";
        }
        if ("6985".equals(sw) || "6A88".equals(sw) || sw.matches("6C[0-9A-F]{2}")) {
            return "WARNING";
        }
        if ("6F00".equals(sw) || "6D00".equals(sw) || "6E00".equals(sw)) {
            return "ERROR";
        }
        return "INFO";
    }

    private static String buildStatusNote(String sw) {
        if (sw.matches("91[0-9A-F]{2}")) {
            return "Proactive command pending";
        }
        if ("6F00".equals(sw)) {
            return "General error";
        }
        if ("6D00".equals(sw)) {
            return "Instruction not supported";
        }
        if ("6E00".equals(sw)) {
            return "Class not supported";
        }
        if ("6985".equals(sw)) {
            return "Conditions of use not satisfied";
        }
        if ("6A88".equals(sw)) {
            return "Referenced data not found";
        }
        if (sw.matches("6C[0-9A-F]{2}")) {
            return "Wrong length, retry with Le=" + sw.substring(2);
        }
        return "";
    }

    private static List<ParsedExchange> parseOriginalLog(List<String> originalLines) {
        List<ParsedExchange> exchanges = new ArrayList<>();
        ParsedExchange current = null;

        for (int i = 0; i < originalLines.size(); i++) {
            String line = originalLines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }

            ApduEvent event = extractApduEvent(line, i + 1);
            if (event == null) {
                continue;
            }

            if (event.tx()) {
                if (current != null) {
                    exchanges.add(current);
                }
                current = new ParsedExchange(event.apdu, "-", event.lineNumber);
            } else if (current != null) {
                current.responseApdu = appendHex(current.responseApdu, event.apdu);
            }
        }

        if (current != null) {
            exchanges.add(current);
        }
        return exchanges;
    }

    private static ExchangeMatch matchExchange(List<ParsedExchange> exchanges, int startIndex, String rawCommandApdu) {
        String normalized = normalizeHex(rawCommandApdu);
        for (int i = Math.max(0, startIndex); i < exchanges.size(); i++) {
            String candidate = normalizeHex(exchanges.get(i).commandApdu);
            if (candidate.isBlank()) {
                continue;
            }
            if (candidate.equals(normalized) || candidate.endsWith(normalized) || normalized.endsWith(candidate)) {
                return new ExchangeMatch(exchanges.get(i), i + 1);
            }
        }
        return null;
    }

    private static String appendHex(String existing, String next) {
        String normalizedExisting = normalizeHex(existing);
        String normalizedNext = normalizeHex(next);
        if (normalizedExisting.isBlank()) {
            return normalizedNext;
        }
        if (normalizedNext.isBlank()) {
            return normalizedExisting;
        }
        return normalizedExisting + normalizedNext;
    }

    private static ApduEvent extractApduEvent(String line, int lineNumber) {
        Matcher inline = INLINE_TX_RX_RE.matcher(line);
        if (inline.find()) {
            String direction = inline.group(1);
            String payload = inline.group(2) != null ? inline.group(2) : inline.group(3);
            String hex = normalizeHex(payload);
            return hex.isBlank() ? null : new ApduEvent("TX".equalsIgnoreCase(direction), hex, lineNumber);
        }

        Matcher colon = COLON_TX_RX_RE.matcher(line);
        if (colon.find()) {
            String direction = colon.group(1);
            String hex = normalizeHex(colon.group(2));
            return hex.isBlank() ? null : new ApduEvent("TX".equalsIgnoreCase(direction), hex, lineNumber);
        }

        Matcher apdu = APDU_TX_RX_RE.matcher(line);
        if (apdu.find()) {
            String direction = apdu.group(1);
            String hex = normalizeHex(apdu.group(2));
            return hex.isBlank() ? null : new ApduEvent("tx".equalsIgnoreCase(direction), hex, lineNumber);
        }
        return null;
    }

    public enum FilterMode {
        ALL("ALL"),
        ES10("ES10"),
        FETCH_TR("FETCH/TR"),
        LSI("LSI");

        private final String label;

        FilterMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class AnalysisItem {
        final int sequenceIndex;
        final int eventSequence;
        final String commandApdu;
        final String responseApdu;
        final String commandName;
        final String headline;
        final String statusWord;
        final String severity;
        final String note;
        final String tagLabel;
        final String resetMarker;
        final String resetType;
        final String atr;
        final int sourceLine;
        final boolean es10;
        final boolean fetchOrTerminalResponse;
        final boolean configureLsi;

        private AnalysisItem(
                int sequenceIndex,
                int eventSequence,
                String commandApdu,
                String responseApdu,
                String commandName,
                String headline,
                String statusWord,
                String severity,
                String note,
                String tagLabel,
                String resetMarker,
                String resetType,
                String atr,
                int sourceLine,
                boolean es10,
                boolean fetchOrTerminalResponse,
                boolean configureLsi
        ) {
            this.sequenceIndex = sequenceIndex;
            this.eventSequence = eventSequence;
            this.commandApdu = commandApdu;
            this.responseApdu = responseApdu;
            this.commandName = commandName;
            this.headline = headline;
            this.statusWord = statusWord;
            this.severity = severity;
            this.note = note == null ? "" : note;
            this.tagLabel = tagLabel == null ? "" : tagLabel;
            this.resetMarker = resetMarker;
            this.resetType = resetType == null ? "" : resetType;
            this.atr = atr == null ? "" : atr;
            this.sourceLine = sourceLine;
            this.es10 = es10;
            this.fetchOrTerminalResponse = fetchOrTerminalResponse;
            this.configureLsi = configureLsi;
        }

        static AnalysisItem command(
                int sequenceIndex,
                String commandApdu,
                String responseApdu,
                String commandName,
                String headline,
                String statusWord,
                String severity,
                String note,
                String tagLabel,
                int sourceLine,
                boolean es10,
                boolean fetchOrTerminalResponse,
                boolean configureLsi
        ) {
            return new AnalysisItem(
                    sequenceIndex,
                    sequenceIndex,
                    commandApdu,
                    responseApdu,
                    commandName,
                    headline,
                    statusWord,
                    severity,
                    note,
                    tagLabel,
                    null,
                    "",
                    "",
                    sourceLine,
                    es10,
                    fetchOrTerminalResponse,
                    configureLsi
            );
        }

        static AnalysisItem reset(int eventSequence, String resetType, String atr, int sourceLine) {
            return new AnalysisItem(
                    0,
                    eventSequence,
                    "",
                    "-",
                    "RESET",
                    "RESET",
                    "-",
                    "INFO",
                    "Cold Reset",
                    "",
                    "RESET",
                    resetType,
                    atr,
                    sourceLine,
                    false,
                    false,
                    false
            );
        }

        AnalysisItem withEventSequence(int newEventSequence, int eventSourceLine) {
            return new AnalysisItem(
                    sequenceIndex,
                    newEventSequence,
                    commandApdu,
                    responseApdu,
                    commandName,
                    headline,
                    statusWord,
                    severity,
                    note,
                    tagLabel,
                    resetMarker,
                    resetType,
                    atr,
                    eventSourceLine > 0 ? eventSourceLine : sourceLine,
                    es10,
                    fetchOrTerminalResponse,
                    configureLsi
            );
        }

        boolean isResetMarker() {
            return resetMarker != null;
        }

        boolean isConfigureLsi() {
            return configureLsi;
        }

        boolean isErrorLike() {
            return "ERROR".equals(severity) || "WARNING".equals(severity);
        }

        boolean matches(FilterMode filterMode) {
            if (filterMode == null || filterMode == FilterMode.ALL) {
                return true;
            }
            if (isResetMarker()) {
                return false;
            }
            return switch (filterMode) {
                case ES10 -> es10;
                case FETCH_TR -> fetchOrTerminalResponse;
                case LSI -> configureLsi || "Manage LSI".equals(commandName);
                case ALL -> true;
            };
        }
    }

    static final class AnalysisResult {
        final String commandName;
        final String statusWord;
        final String severity;
        final String explanation;

        AnalysisResult(String commandName, String statusWord, String severity, String explanation) {
            this.commandName = commandName;
            this.statusWord = statusWord;
            this.severity = severity;
            this.explanation = explanation;
        }
    }

    private record ApduEvent(boolean tx, String apdu, int lineNumber) {
    }

    private record ExchangeMatch(ParsedExchange exchange, int nextIndex) {
    }

    private record CommandDescriptor(
            String commandName,
            String headline,
            String tagLabel,
            String note,
            boolean es10,
            boolean fetchOrTerminalResponse,
            boolean configureLsi
    ) {
    }

    private static final class ParsedExchange {
        private final String commandApdu;
        private String responseApdu;
        private final int sourceLine;

        private ParsedExchange(String commandApdu, String responseApdu, int sourceLine) {
            this.commandApdu = commandApdu;
            this.responseApdu = responseApdu;
            this.sourceLine = sourceLine;
        }
    }
}
