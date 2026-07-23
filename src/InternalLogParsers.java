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
import java.util.List;
import java.util.Locale;
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
            StringBuilder current = new StringBuilder();
            boolean inTxBlock = false;

            for (String line : lines) {
                Matcher matcher = APDU_TX_RE.matcher(line);
                if (matcher.find()) {
                    current.append(toHexNoSpaces(matcher.group(1)));
                    inTxBlock = true;
                } else if (inTxBlock) {
                    flushCurrent(apdusOut, current);
                    inTxBlock = false;
                }
            }

            if (inTxBlock) {
                flushCurrent(apdusOut, current);
            }
            return new ParseResult(apdusOut, List.of());
        }

        private static void flushCurrent(List<String> apdusOut, StringBuilder current) {
            if (current.length() > 0) {
                apdusOut.add(current.toString().toUpperCase(Locale.ROOT));
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
        private static final Set<Integer> COMMON_CLA = new HashSet<>(Arrays.asList(
                0x00, 0x01, 0x02, 0x03,
                0x80, 0x81, 0x82, 0x83, 0x84,
                0x90, 0xA0
        ));

        private enum State { WAIT_HEADER_TX, WAIT_PROCEDURE_RX, WAIT_COMMAND_DATA_TX }

        private static final class Frame {
            final boolean tx;
            final List<String> bytes;
            final String sourceLine;

            private Frame(boolean tx, List<String> bytes, String sourceLine) {
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
            State state = State.WAIT_HEADER_TX;
            List<String> header = null;
            String ins = null;
            int p3 = 0;
            List<String> commandData = new ArrayList<>();

            try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Frame frame = parseFrame(line);
                    if (frame == null || frame.bytes.isEmpty()) {
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
                            ins = header.get(1);
                            p3 = Integer.parseInt(header.get(4), 16);
                            commandData.clear();

                            if (p3 == 0) {
                                apdusOut.add(joinNoSpaces(header));
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
                                        apdusOut.add(joinNoSpaces(header));
                                    }
                                    header = newHeader;
                                    ins = header.get(1);
                                    p3 = Integer.parseInt(header.get(4), 16);
                                    commandData.clear();
                                    if (p3 == 0) {
                                        apdusOut.add(joinNoSpaces(header));
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
                                apdusOut.add(joinNoSpaces(header));
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
                                apdusOut.add(joinNoSpaces(complete));
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
                apdusOut.add(joinNoSpaces(header));
            }

            return new ParseResult(apdusOut, List.of());
        }

        private static Frame parseFrame(String line) {
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
            List<String> bytes = extractHexBytes(payload);
            return new Frame(tx, bytes, extractSourceLineNumber(line));
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
            return COMMON_CLA.contains(cla) || isLogicalChannelCla(cla) || isLikelyProprietaryCla(cla);
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

        private static String extractSourceLineNumber(String line) {
            Matcher matcher = ORIGINAL_LINE_NUMBER.matcher(line == null ? "" : line);
            return matcher.find() ? matcher.group(1) : "";
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
                Frame frame = parseFrame(line);
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
            State state = State.WAIT_HEADER;
            List<String> header = new ArrayList<>(5);
            int expectedLc = 0;
            List<String> data = new ArrayList<>();
            String ins = null;
            String p1 = null;
            int p3 = 0;

            for (String line : lines) {
                Frame frame = parseFrame(line);
                if (frame == null || !frame.isCommand || frame.payload.isEmpty()) {
                    continue;
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
                        ins = header.get(1);
                        p1 = header.get(2);
                        p3 = Integer.parseInt(header.get(4), 16);
                        i += 5;

                        if (p3 == 0 || isLeOnly(ins, p1, p3)) {
                            apdusOut.add(joinNoSpaces(header));
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
                            apdusOut.add(joinNoSpaces(full));
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
                            apdusOut.add(joinNoSpaces(full));
                            header.clear();
                            data.clear();
                            state = State.WAIT_HEADER;
                        }
                    }
                }
            }
            return new ParseResult(apdusOut, List.of());
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

        private static Frame parseFrame(String line) {
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
            Integer txLen = null;
            List<String> buffer = new ArrayList<>();

            try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher txMatcher = TX_LEN_PATTERN.matcher(line);
                    if (txMatcher.find()) {
                        txLen = Integer.parseInt(txMatcher.group(1));
                        buffer.clear();
                        continue;
                    }

                    if (txLen != null && line.contains("[T]USIMDRV")) {
                        Matcher byteMatcher = BYTE_PATTERN.matcher(line);
                        while (byteMatcher.find()) {
                            buffer.add(byteMatcher.group(1).toUpperCase(Locale.ROOT));
                        }
                        if (buffer.size() >= txLen) {
                            apdus.add(joinNoSpaces(buffer.subList(0, txLen)));
                            txLen = null;
                            buffer.clear();
                        }
                    }
                }
            }
            return new ParseResult(apdus, List.of());
        }
    }

    private static final class PcscTerminalParser extends BaseParser {
        private static final Pattern PATTERN = Pattern.compile("-->\\s*\\[PCSC\\][\\\\/\\s]*([0-9A-Fa-f]+)");

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
            try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = PATTERN.matcher(line);
                    if (matcher.find()) {
                        apdus.add(matcher.group(1).toUpperCase(Locale.ROOT));
                    }
                }
            }
            return new ParseResult(apdus, List.of());
        }
    }

    private static final class HtmlApduParser extends BaseParser {
        private static final Pattern PATTERN = Pattern.compile("APDU:\\s*([0-9A-Fa-f\\s]+)");

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
            Matcher matcher = PATTERN.matcher(html);
            List<String> apdus = new ArrayList<>();
            while (matcher.find()) {
                String apdu = matcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                if (!apdu.isBlank()) {
                    apdus.add(apdu);
                }
            }
            return new ParseResult(apdus, List.of());
        }
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
