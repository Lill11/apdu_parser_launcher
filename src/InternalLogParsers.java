import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InternalLogParsers {

    private InternalLogParsers() {
    }

    static List<LogParser> createDefaultParsers() {
        return List.of(
                new HonorApduTxParser(),
                new OppoTxDataParser(),
                new OhBytesParser(),
                new UnisocUsimDrvParser(),
                new PcscTerminalParser(),
                new HtmlApduParser()
        );
    }

    private abstract static class BaseParser implements LogParser {
        private final String id;
        private final String displayName;
        private final List<String> extensions;

        private BaseParser(String id, String displayName, String... extensions) {
            this.id = id;
            this.displayName = displayName;
            this.extensions = List.of(extensions);
        }

        @Override
        public final String getId() {
            return id;
        }

        @Override
        public final String getDisplayName() {
            return displayName;
        }

        @Override
        public final List<String> getSupportedExtensions() {
            return extensions;
        }
    }

    private static final class HonorApduTxParser extends BaseParser {
        private static final Pattern APDU_TX_RE =
                Pattern.compile("\\bAPDU_tx\\s+\\d+\\s*:\\s*([0-9A-Fa-f]{2}(?:\\s+[0-9A-Fa-f]{2})*)\\s*$");
        private static final Pattern HEX_TOKEN_RE = Pattern.compile("\\b[0-9A-Fa-f]{2}\\b");
        private static final Pattern RESET_START_RE = Pattern.compile(
                "(?i)\\bSIM\\s+MOD_SIM_BASELINE_UH\\s+"
                        + "(?:SIM_CARD_COLD_RESET|CARD_COLD_RESET|SIM_POWER_ON|CARD_ACTIVATION)\\s+"
                        + "(?:START|SUCCESS|COMPLETE|COMPLETED)\\b"
        );
        private static final Pattern ATR_REPORT_RE = Pattern.compile(
                "(?i)\\bSIM\\s+MOD_SIM_BASELINE_UH\\s+(?:ATR_REPORT|CARD_ATR|SIM_ATR)\\s*:?\\s*"
                        + "(3B(?:\\s*[0-9A-F]{2}){7,})\\s*$"
        );

        private HonorApduTxParser() {
            super("honor_apdutx", "Honor APDU_TX", ".txt", ".log");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            return sampleContent.contains("APDU_tx");
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            List<String> lines = Files.readAllLines(inputFile, StandardCharsets.UTF_8);
            List<String> apdusOut = new ArrayList<>();
            List<ParsedLogEvent> events = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inTxBlock = false;
            int currentStartLine = 0;
            int pendingResetLine = 0;

            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                int sourceLine = index + 1;
                Matcher matcher = APDU_TX_RE.matcher(line);
                if (matcher.find()) {
                    if (!inTxBlock) {
                        currentStartLine = sourceLine;
                    }
                    current.append(toHexNoSpaces(matcher.group(1)));
                    inTxBlock = true;
                } else if (inTxBlock) {
                    flushCurrent(apdusOut, events, current, currentStartLine);
                    inTxBlock = false;
                }

                if (RESET_START_RE.matcher(line).find()) {
                    pendingResetLine = sourceLine;
                    continue;
                }
                Matcher atrMatcher = ATR_REPORT_RE.matcher(line);
                if (pendingResetLine > 0 && sourceLine - pendingResetLine <= 12 && atrMatcher.find()) {
                    String atr = atrMatcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    if (isValidAtr(atr)) {
                        events.add(new ParsedLogEvent.Reset(
                                ParsedLogEvent.ResetType.COLD_RESET, atr, pendingResetLine));
                    }
                    pendingResetLine = 0;
                } else if (pendingResetLine > 0 && sourceLine - pendingResetLine > 12) {
                    pendingResetLine = 0;
                }
            }

            if (inTxBlock) {
                flushCurrent(apdusOut, events, current, currentStartLine);
            }
            return new ParseResult(apdusOut, List.of(), events);
        }

        private static void flushCurrent(
                List<String> apdusOut,
                List<ParsedLogEvent> events,
                StringBuilder current,
                int sourceLine
        ) {
            if (current.length() > 0) {
                String apdu = current.toString().toUpperCase(Locale.ROOT);
                apdusOut.add(apdu);
                events.add(new ParsedLogEvent.Apdu(apdu, sourceLine));
                current.setLength(0);
            }
        }

        private static String toHexNoSpaces(String spacedHex) {
            Matcher matcher = HEX_TOKEN_RE.matcher(spacedHex);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                sb.append(matcher.group());
            }
            return sb.toString();
        }
    }

    private static final class OppoTxDataParser extends BaseParser {
        private static final Pattern ORIGINAL_LINE_NUMBER = Pattern.compile("(?i)\\bLine\\s+(\\d+)\\s*:");
        private static final Pattern HEX_BYTE = Pattern.compile("(?i)(?<![0-9A-F])[0-9A-F]{2}(?![0-9A-F])");
        private static final Pattern ATR_RX_DATA = Pattern.compile(
                "(?i)\\bType\\s*=\\s*ATR\\s+RX\\s+DATA\\s*=\\s*(?:\\{([^}]*)}|([0-9A-F]{2}))"
        );
        private static final Set<Integer> COMMON_CLA = new HashSet<>(Arrays.asList(
                0x00, 0x01, 0x02, 0x03,
                0x80, 0x81, 0x82, 0x83, 0x84,
                0x90, 0xA0
        ));

        private enum State { WAIT_HEADER_TX, WAIT_PROCEDURE_RX, WAIT_COMMAND_DATA_TX }

        private static final class Frame {
            final boolean tx;
            final List<String> bytes;
            final int sourceLine;

            private Frame(boolean tx, List<String> bytes, int sourceLine) {
                this.tx = tx;
                this.bytes = bytes;
                this.sourceLine = sourceLine;
            }
        }

        private OppoTxDataParser() {
            super("oppo_txdata", "OPPO Type=TX/Type=RX", ".txt", ".log");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            return (sampleContent.contains("Type = TX") && sampleContent.contains("Type = RX"))
                    || (sampleContent.contains("TX Data =") && sampleContent.contains("RX Data ="));
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            List<String> apdusOut = new ArrayList<>();
            List<ParsedLogEvent> events = new ArrayList<>();
            State state = State.WAIT_HEADER_TX;
            List<String> header = null;
            String ins = null;
            int p3 = 0;
            List<String> commandData = new ArrayList<>();
            int headerSourceLine = 0;
            List<String> atrBytes = new ArrayList<>();
            int atrSourceLine = 0;
            int physicalLine = 0;

            try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    physicalLine++;
                    Matcher atrMatcher = ATR_RX_DATA.matcher(line);
                    if (atrMatcher.find()) {
                        List<String> part = extractHexBytes(
                                atrMatcher.group(1) != null ? atrMatcher.group(1) : atrMatcher.group(2));
                        if (atrBytes.isEmpty()) {
                            atrSourceLine = sourceLineOf(line, physicalLine);
                        }
                        atrBytes.addAll(part);
                        String atr = joinNoSpaces(atrBytes);
                        if (isValidAtr(atr)) {
                            events.add(new ParsedLogEvent.Reset(
                                    ParsedLogEvent.ResetType.COLD_RESET, atr, atrSourceLine));
                            atrBytes.clear();
                            atrSourceLine = 0;
                        }
                        continue;
                    } else if (!atrBytes.isEmpty()) {
                        atrBytes.clear();
                        atrSourceLine = 0;
                    }

                    Frame frame = parseFrame(line, physicalLine);
                    if (frame == null) {
                        continue;
                    }
                    if (frame.tx && frame.bytes.isEmpty()) {
                        continue;
                    }

                    switch (state) {
                        case WAIT_HEADER_TX -> {
                            if (!frame.tx) {
                                break;
                            }
                            List<String> candidate = headerFromFrame(frame.bytes);
                            if (candidate == null) {
                                break;
                            }

                            header = candidate;
                            headerSourceLine = frame.sourceLine;
                            ins = header.get(1);
                            p3 = Integer.parseInt(header.get(4), 16);
                            commandData.clear();

                            if (p3 == 0) {
                                addApdu(apdusOut, events, joinNoSpaces(header), headerSourceLine);
                                header = null;
                                ins = null;
                                state = State.WAIT_HEADER_TX;
                            } else {
                                state = State.WAIT_PROCEDURE_RX;
                            }
                        }
                        case WAIT_PROCEDURE_RX -> {
                            if (frame.tx) {
                                List<String> newHeader = headerFromFrame(frame.bytes);
                                if (newHeader != null && frame.bytes.size() == 5) {
                                    if (header != null) {
                                        addApdu(apdusOut, events, joinNoSpaces(header), headerSourceLine);
                                    }
                                    header = newHeader;
                                    headerSourceLine = frame.sourceLine;
                                    ins = header.get(1);
                                    p3 = Integer.parseInt(header.get(4), 16);
                                    commandData.clear();
                                    if (p3 == 0) {
                                        addApdu(apdusOut, events, joinNoSpaces(header), headerSourceLine);
                                        header = null;
                                        ins = null;
                                        state = State.WAIT_HEADER_TX;
                                    }
                                }
                                break;
                            }

                            if (header == null || ins == null) {
                                state = State.WAIT_HEADER_TX;
                                break;
                            }

                            if (isProcedureByteRequestingData(frame.bytes, ins)) {
                                state = State.WAIT_COMMAND_DATA_TX;
                            } else {
                                addApdu(apdusOut, events, joinNoSpaces(header), headerSourceLine);
                                header = null;
                                ins = null;
                                commandData.clear();
                                state = State.WAIT_HEADER_TX;
                            }
                        }
                        case WAIT_COMMAND_DATA_TX -> {
                            if (!frame.tx) {
                                break;
                            }
                            if (header == null || ins == null) {
                                state = State.WAIT_HEADER_TX;
                                break;
                            }

                            int remaining = p3 - commandData.size();
                            if (remaining > 0) {
                                int take = Math.min(remaining, frame.bytes.size());
                                commandData.addAll(frame.bytes.subList(0, take));
                            }

                            if (commandData.size() >= p3) {
                                List<String> complete = new ArrayList<>(header);
                                complete.addAll(commandData.subList(0, p3));
                                addApdu(apdusOut, events, joinNoSpaces(complete), headerSourceLine);
                                header = null;
                                ins = null;
                                commandData.clear();
                                state = State.WAIT_HEADER_TX;
                            }
                        }
                    }
                }
            }

            if (header != null && state == State.WAIT_PROCEDURE_RX) {
                addApdu(apdusOut, events, joinNoSpaces(header), headerSourceLine);
            }

            return new ParseResult(apdusOut, List.of(), events);
        }

        private static Frame parseFrame(String line, int physicalLine) {
            if (line == null) {
                return null;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            int markerIndex;
            int payloadStart;
            boolean tx;

            markerIndex = upper.indexOf("TX DATA =");
            if (markerIndex >= 0 && !upper.contains("PPS TX DATA") && !upper.contains("ATR TX DATA")) {
                tx = true;
                payloadStart = markerIndex + "TX DATA =".length();
            } else {
                markerIndex = upper.indexOf("RX DATA =");
                if (markerIndex >= 0 && !upper.contains("PPS RX DATA") && !upper.contains("ATR RX DATA")) {
                    tx = false;
                    payloadStart = markerIndex + "RX DATA =".length();
                } else if (upper.contains("TYPE = TX")) {
                    tx = true;
                    payloadStart = upper.indexOf("TYPE = TX") + "TYPE = TX".length();
                } else if (upper.contains("TYPE = RX")) {
                    tx = false;
                    payloadStart = upper.indexOf("TYPE = RX") + "TYPE = RX".length();
                } else {
                    return null;
                }
            }

            String payload = line.substring(Math.min(payloadStart, line.length()));
            List<String> bytes;
            int openBrace = payload.indexOf('{');
            if (openBrace >= 0) {
                int closeBrace = payload.indexOf('}', openBrace + 1);
                String visiblePayload = closeBrace >= 0
                        ? payload.substring(openBrace + 1, closeBrace)
                        : payload.substring(openBrace + 1);
                bytes = extractHexBytes(visiblePayload);
            } else if (markerIndex >= 0 && !tx && upper.contains("RX DATA =")) {
                Matcher firstByte = HEX_BYTE.matcher(payload);
                bytes = new ArrayList<>();
                if (firstByte.find()) {
                    bytes.add(firstByte.group().toUpperCase(Locale.ROOT));
                }
            } else {
                bytes = extractHexBytes(payload);
            }
            return new Frame(tx, bytes, sourceLineOf(line, physicalLine));
        }

        private static List<String> extractHexBytes(String text) {
            List<String> bytes = new ArrayList<>();
            Matcher matcher = HEX_BYTE.matcher(text == null ? "" : text);
            while (matcher.find()) {
                bytes.add(matcher.group().toUpperCase(Locale.ROOT));
            }
            return bytes;
        }

        private static List<String> headerFromFrame(List<String> bytes) {
            if (bytes == null || bytes.size() < 5) {
                return null;
            }
            List<String> candidate = bytes.subList(0, 5);
            return looksLikeHeader(candidate) ? new ArrayList<>(candidate) : null;
        }

        private static boolean looksLikeHeader(List<String> candidate) {
            if (candidate == null || candidate.size() != 5) {
                return false;
            }
            int cla = Integer.parseInt(candidate.get(0), 16);
            int ins = Integer.parseInt(candidate.get(1), 16);
            return ins != 0x00 && (COMMON_CLA.contains(cla) || isLogicalChannelCla(cla) || isLikelyProprietaryCla(cla));
        }

        private static boolean isLogicalChannelCla(int cla) {
            return (cla >= 0x00 && cla <= 0x03) || (cla >= 0x40 && cla <= 0x4F);
        }

        private static boolean isLikelyProprietaryCla(int cla) {
            return (cla & 0x80) != 0;
        }

        private static boolean isProcedureByteRequestingData(List<String> bytes, String ins) {
            if (bytes == null || bytes.isEmpty() || ins == null) {
                return false;
            }
            if (bytes.size() != 1) {
                return false;
            }
            int procedure = Integer.parseInt(bytes.get(0), 16);
            int instruction = Integer.parseInt(ins, 16);
            return procedure == instruction || procedure == (instruction ^ 0xFF);
        }

        private static int sourceLineOf(String line, int fallback) {
            Matcher matcher = ORIGINAL_LINE_NUMBER.matcher(line == null ? "" : line);
            if (!matcher.find()) {
                return fallback;
            }
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    private static final class OhBytesParser extends BaseParser {
        private static final Pattern HEX_RE = Pattern.compile("^[0-9A-Fa-f]{2}$");
        private static final Set<String> COMMON_CLA = new HashSet<>(Arrays.asList(
                "00","01","02","03","04","05","06","07","08","09","0A","0B","0C","0D","0E","0F",
                "80","81","82","84","86","8C","8E","A0","A4"
        ));
        private static final Set<String> INS_LE_ONLY = new HashSet<>(Arrays.asList(
                "12", "B0", "B2", "C0", "CA", "CB"
        ));

        private enum State { WAIT_HEADER, WAIT_DATA }

        private static final class Frame {
            boolean isCommand;
            List<String> payload = List.of();
            int sourceLine;
        }

        private OhBytesParser() {
            super("oh_bytes", "OH FF FF stream", ".txt", ".log");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            String lower = sampleContent.toLowerCase(Locale.ROOT);
            if (lower.contains("ff ff 00 00") && lower.contains("00 01")) {
                return true;
            }
            String fileName = file == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
            if ((fileName.contains("_oh") || fileName.contains("oh_") || fileName.contains("oh"))
                    && sampleContainsOhFrame(sampleContent)) {
                return true;
            }
            return sampleContainsOhFrame(sampleContent);
        }

        private static boolean sampleContainsOhFrame(String sampleContent) {
            if (sampleContent == null || sampleContent.isBlank()) {
                return false;
            }
            String[] lines = sampleContent.split("\\R");
            for (String line : lines) {
                Frame frame = parseFrame(line, 0);
                if (frame != null && !frame.payload.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            List<String> lines = Files.readAllLines(inputFile, StandardCharsets.UTF_8);
            List<String> apdusOut = new ArrayList<>();
            List<ParsedLogEvent> events = new ArrayList<>();
            State state = State.WAIT_HEADER;
            List<String> header = new ArrayList<>(5);
            int expectedLc = 0;
            List<String> data = new ArrayList<>();
            String ins = null;
            String p1 = null;
            int p3 = 0;
            int headerSourceLine = 0;
            String pendingAtr = "";
            int pendingAtrLine = 0;

            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                Frame frame = parseFrame(lines.get(lineIndex), lineIndex + 1);
                if (frame == null || frame.payload.isEmpty()) {
                    continue;
                }
                if (!frame.isCommand) {
                    String candidate = joinNoSpaces(frame.payload);
                    if (isValidAtr(candidate)) {
                        pendingAtr = candidate;
                        pendingAtrLine = frame.sourceLine;
                    }
                    continue;
                }

                if (!pendingAtr.isEmpty()) {
                    if (startsWith(frame.payload, "80", "7C", "04", "00")) {
                        events.add(new ParsedLogEvent.Reset(
                                ParsedLogEvent.ResetType.COLD_RESET, pendingAtr, pendingAtrLine));
                    }
                    pendingAtr = "";
                    pendingAtrLine = 0;
                }

                int i = 0;
                while (i < frame.payload.size()) {
                    if (state == State.WAIT_HEADER) {
                        if (i + 5 > frame.payload.size()) {
                            break;
                        }
                        if (!looksLikeHeaderAt(frame.payload, i)) {
                            i++;
                            continue;
                        }

                        header.clear();
                        header.addAll(frame.payload.subList(i, i + 5));
                        headerSourceLine = frame.sourceLine;
                        ins = header.get(1);
                        p1 = header.get(2);
                        p3 = Integer.parseInt(header.get(4), 16);
                        i += 5;

                        if (p3 == 0 || isLeOnly(ins, p1, p3)) {
                            addApdu(apdusOut, events, joinNoSpaces(header), headerSourceLine);
                            header.clear();
                            data.clear();
                            continue;
                        }

                        expectedLc = p3;
                        data.clear();
                        state = State.WAIT_DATA;

                        int canTake = Math.min(expectedLc, frame.payload.size() - i);
                        if (canTake > 0) {
                            data.addAll(frame.payload.subList(i, i + canTake));
                            i += canTake;
                        }

                        if (data.size() >= expectedLc) {
                            List<String> full = new ArrayList<>(header);
                            full.addAll(data.subList(0, expectedLc));
                            addApdu(apdusOut, events, joinNoSpaces(full), headerSourceLine);
                            header.clear();
                            data.clear();
                            state = State.WAIT_HEADER;
                        }
                    } else {
                        int need = expectedLc - data.size();
                        if (need <= 0) {
                            header.clear();
                            data.clear();
                            state = State.WAIT_HEADER;
                            continue;
                        }

                        int canTake = Math.min(need, frame.payload.size() - i);
                        if (canTake <= 0) {
                            break;
                        }
                        data.addAll(frame.payload.subList(i, i + canTake));
                        i += canTake;

                        if (data.size() >= expectedLc) {
                            List<String> full = new ArrayList<>(header);
                            full.addAll(data.subList(0, expectedLc));
                            addApdu(apdusOut, events, joinNoSpaces(full), headerSourceLine);
                            header.clear();
                            data.clear();
                            state = State.WAIT_HEADER;
                        }
                    }
                }
            }
            return new ParseResult(apdusOut, List.of(), events);
        }

        private static boolean isLeOnly(String insHex, String p1Hex, int p3) {
            String ins = insHex == null ? "" : insHex.toUpperCase(Locale.ROOT);
            String p1 = p1Hex == null ? "" : p1Hex.toUpperCase(Locale.ROOT);
            if ("7C".equals(ins) && "01".equals(p1) && p3 == 0x19) {
                return true;
            }
            if (INS_LE_ONLY.contains(ins)) {
                return true;
            }
            return "70".equals(ins) && "00".equals(p1);
        }

        private static boolean looksLikeHeaderAt(List<String> payload, int idx) {
            return idx >= 0 && idx + 5 <= payload.size() && COMMON_CLA.contains(payload.get(idx));
        }

        private static Frame parseFrame(String line, int sourceLine) {
            List<String> tokens = extractHexTokens(line, HEX_RE);
            if (tokens.isEmpty()) {
                return null;
            }

            int ff = indexOfPrefix(tokens);
            if (ff < 0 || tokens.size() <= ff + 7) {
                return null;
            }

            Frame frame = new Frame();
            frame.isCommand = "00".equals(tokens.get(ff + 4)) && "01".equals(tokens.get(ff + 5));
            frame.sourceLine = sourceLine;
            int payloadStart = ff + 8;
            if (payloadStart >= tokens.size()) {
                return null;
            }
            frame.payload = new ArrayList<>(tokens.subList(payloadStart, tokens.size()));
            return frame;
        }

        private static int indexOfPrefix(List<String> tokens) {
            for (int i = 0; i < tokens.size() - 3; i++) {
                if ("FF".equals(tokens.get(i)) && "FF".equals(tokens.get(i + 1))
                        && "00".equals(tokens.get(i + 2)) && "00".equals(tokens.get(i + 3))) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final class UnisocUsimDrvParser extends BaseParser {
        private static final Pattern TX_LEN_PATTERN = Pattern.compile("tx_data_len\\[(\\d+)]");
        private static final Pattern BYTE_PATTERN = Pattern.compile("0x([0-9A-Fa-f]{2})");
        private static final Pattern POWER_OFF_PATTERN = Pattern.compile(
                "(?i)\\[[A-Z]]USIMDRV\\[(\\d+)]\\s*:\\s*"
                        + "(?:SimPowerOff|SIM_PowerOff|SimColdResetStart)\\b.*"
                        + "(?:complete|completed|done|success|start)"
        );
        private static final Pattern POWER_ON_PATTERN = Pattern.compile(
                "(?i)\\[[A-Z]]USIMDRV\\[(\\d+)]\\s*:\\s*"
                        + "(?:SimPowerOn|SIM_PowerOn|SimActivateVoltage)\\b.*"
                        + "(?:complete|completed|done|success|start)"
        );
        private static final Pattern ATR_PATTERN = Pattern.compile(
                "(?i)\\[[A-Z]]USIMDRV\\[(\\d+)]\\s*:\\s*"
                        + "(?:SimGetATR|SIM_GetATR|SimValidateATR)\\b.*?"
                        + "((?:0x)?3B(?:\\s+(?:0x)?[0-9A-F]{2}){7,})\\s*$"
        );

        private UnisocUsimDrvParser() {
            super("usimdrv_unisoc", "Unisoc USIMDRV", ".txt", ".log");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            return sampleContent.contains("tx_data_len[") && sampleContent.contains("[T]USIMDRV");
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            List<String> apdus = new ArrayList<>();
            List<ParsedLogEvent> events = new ArrayList<>();
            Integer txLen = null;
            List<String> buffer = new ArrayList<>();
            int txSourceLine = 0;
            Map<String, Integer> resetStageBySlot = new HashMap<>();
            Map<String, Integer> resetSourceBySlot = new HashMap<>();
            int sourceLine = 0;

            try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sourceLine++;
                    Matcher powerOff = POWER_OFF_PATTERN.matcher(line);
                    if (powerOff.find()) {
                        resetStageBySlot.put(powerOff.group(1), 1);
                        resetSourceBySlot.put(powerOff.group(1), sourceLine);
                        continue;
                    }
                    Matcher powerOn = POWER_ON_PATTERN.matcher(line);
                    if (powerOn.find() && resetStageBySlot.getOrDefault(powerOn.group(1), 0) == 1) {
                        resetStageBySlot.put(powerOn.group(1), 2);
                        continue;
                    }
                    Matcher atrMatcher = ATR_PATTERN.matcher(line);
                    if (atrMatcher.find() && resetStageBySlot.getOrDefault(atrMatcher.group(1), 0) == 2) {
                        String atr = atrMatcher.group(2).replace("0x", "").replace("0X", "")
                                .replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                        if (isValidAtr(atr)) {
                            events.add(new ParsedLogEvent.Reset(
                                    ParsedLogEvent.ResetType.COLD_RESET,
                                    atr,
                                    resetSourceBySlot.getOrDefault(atrMatcher.group(1), sourceLine)
                            ));
                        }
                        resetStageBySlot.remove(atrMatcher.group(1));
                        resetSourceBySlot.remove(atrMatcher.group(1));
                        continue;
                    }

                    Matcher txMatcher = TX_LEN_PATTERN.matcher(line);
                    if (txMatcher.find()) {
                        txLen = Integer.parseInt(txMatcher.group(1));
                        txSourceLine = sourceLine;
                        buffer.clear();
                        continue;
                    }

                    if (txLen != null && line.contains("[T]USIMDRV")) {
                        Matcher byteMatcher = BYTE_PATTERN.matcher(line);
                        while (byteMatcher.find()) {
                            buffer.add(byteMatcher.group(1).toUpperCase(Locale.ROOT));
                        }
                        if (buffer.size() >= txLen) {
                            addApdu(apdus, events, joinNoSpaces(buffer.subList(0, txLen)), txSourceLine);
                            txLen = null;
                            buffer.clear();
                        }
                    }
                }
            }
            return new ParseResult(apdus, List.of(), events);
        }
    }

    private static final class PcscTerminalParser extends BaseParser {
        private static final Pattern PATTERN = Pattern.compile("-->\\s*\\[PCSC\\][\\\\/\\s]*([0-9A-Fa-f]+)");
        private static final Pattern COLD_RESET_ATR_PATTERN = Pattern.compile(
                "^\\s*(?:INFO\\s+\\S+\\s+)?"
                        + "\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3,6}\\s+<--\\s+"
                        + "(3B(?:[0-9A-Fa-f]{2}){7,})\\s*$"
        );

        private PcscTerminalParser() {
            super("pcsc_terminal", "PCSC Terminal", ".txt", ".log");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            return sampleContent.contains("[PCSC]") && sampleContent.contains("-->");
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            List<String> apdus = new ArrayList<>();
            List<ParsedLogEvent> events = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                String line;
                int sourceLine = 0;
                while ((line = reader.readLine()) != null) {
                    sourceLine++;
                    Matcher resetMatcher = COLD_RESET_ATR_PATTERN.matcher(line);
                    if (resetMatcher.matches()) {
                        events.add(new ParsedLogEvent.Reset(
                                ParsedLogEvent.ResetType.COLD_RESET,
                                resetMatcher.group(1).toUpperCase(Locale.ROOT),
                                sourceLine
                        ));
                        continue;
                    }
                    Matcher matcher = PATTERN.matcher(line);
                    if (matcher.find()) {
                        String command = matcher.group(1).toUpperCase(Locale.ROOT);
                        apdus.add(command);
                        events.add(new ParsedLogEvent.Apdu(command, sourceLine));
                    }
                }
            }
            return new ParseResult(apdus, List.of(), events);
        }
    }

    private static final class HtmlApduParser extends BaseParser {
        private static final Pattern EVENT_PATTERN = Pattern.compile(
                "(?i)APDU:\\s*(Reset\\b|[0-9A-F]{2}(?:[0-9A-F\\s]*[0-9A-F])?)"
                        + "|ATR:\\s*(3B[0-9A-F]{14,})"
        );

        private HtmlApduParser() {
            super("html_apdu", "HTML APDU Report", ".html", ".htm");
        }

        @Override
        public boolean supports(Path file, String sampleContent) {
            String lower = sampleContent.toLowerCase(Locale.ROOT);
            return lower.contains("<html") && sampleContent.contains("APDU:");
        }

        @Override
        public ParseResult parse(Path inputFile) throws IOException {
            Charset charset = Charset.forName("GB2312");
            String html = Files.readString(inputFile, charset);
            Matcher matcher = EVENT_PATTERN.matcher(html);
            List<String> apdus = new ArrayList<>();
            List<ParsedLogEvent> events = new ArrayList<>();
            int pendingResetOffset = -1;
            while (matcher.find()) {
                String apduOrReset = matcher.group(1);
                String atr = matcher.group(2);
                if (apduOrReset != null && apduOrReset.equalsIgnoreCase("Reset")) {
                    pendingResetOffset = matcher.start();
                    continue;
                }
                if (atr != null && pendingResetOffset >= 0) {
                    String normalizedAtr = atr.toUpperCase(Locale.ROOT);
                    if (isValidAtr(normalizedAtr)) {
                        events.add(new ParsedLogEvent.Reset(
                                ParsedLogEvent.ResetType.COLD_RESET,
                                normalizedAtr,
                                lineNumberAt(html, pendingResetOffset)
                        ));
                    }
                    pendingResetOffset = -1;
                    continue;
                }
                if (apduOrReset != null) {
                    pendingResetOffset = -1;
                    String apdu = apduOrReset.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    if (!apdu.isBlank()) {
                        apdus.add(apdu);
                        events.add(new ParsedLogEvent.Apdu(apdu, lineNumberAt(html, matcher.start())));
                    }
                }
            }
            return new ParseResult(apdus, List.of(), events);
        }
    }

    private static void addApdu(
            List<String> apdus,
            List<ParsedLogEvent> events,
            String apdu,
            int sourceLine
    ) {
        apdus.add(apdu);
        events.add(new ParsedLogEvent.Apdu(apdu, sourceLine));
    }

    private static boolean startsWith(List<String> bytes, String... prefix) {
        if (bytes == null || bytes.size() < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (!prefix[i].equalsIgnoreCase(bytes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static boolean isValidAtr(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("(?:3B|3F)[0-9A-F]{14,}") || (normalized.length() & 1) != 0) {
            return false;
        }
        List<Integer> bytes = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i += 2) {
            bytes.add(Integer.parseInt(normalized.substring(i, i + 2), 16));
        }
        if (bytes.size() < 2) {
            return false;
        }
        int t0 = bytes.get(1);
        int historicalLength = t0 & 0x0F;
        int presence = (t0 >>> 4) & 0x0F;
        int cursor = 2;
        boolean nonZeroProtocol = false;
        while (presence != 0) {
            if ((presence & 0x1) != 0) cursor++;
            if ((presence & 0x2) != 0) cursor++;
            if ((presence & 0x4) != 0) cursor++;
            if ((presence & 0x8) != 0) {
                if (cursor >= bytes.size()) {
                    return false;
                }
                int td = bytes.get(cursor++);
                int protocol = td & 0x0F;
                nonZeroProtocol |= protocol != 0 && protocol != 0x0F;
                presence = (td >>> 4) & 0x0F;
            } else {
                presence = 0;
            }
            if (cursor > bytes.size()) {
                return false;
            }
        }
        int expectedLength = cursor + historicalLength + (nonZeroProtocol ? 1 : 0);
        return expectedLength == bytes.size() || expectedLength + 1 == bytes.size();
    }

    private static List<String> extractHexTokens(String line, Pattern tokenPattern) {
        if (line == null) {
            return Collections.emptyList();
        }
        String[] parts = line.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (tokenPattern.matcher(part).matches()) {
                out.add(part.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static List<String> tail(List<String> list, int count) {
        if (list.size() <= count) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>(list.subList(list.size() - count, list.size()));
    }

    private static String joinNoSpaces(List<String> bytes) {
        StringBuilder sb = new StringBuilder(bytes.size() * 2);
        for (String value : bytes) {
            sb.append(value);
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
