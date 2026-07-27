import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extrae APDU enviadas por ME a cualquier Ix_USIM y genera un TXT. Java 8. */
public class Ix_USIM_apdu_extractor_OH {

    private static final Pattern LOG_LINE = Pattern.compile(
        "^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+ME\\s+(---->|<----)\\s+I\\d+_USIM\\s+(\\d+)\\s+(\\d+)\\s+(.*)$"
    );

    private static final Pattern HEX_BYTE = Pattern.compile(
        "(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])"
    );

    private static final class Entry {
        boolean sent;
        int sci;
        int lsi;
        List<String> bytes;
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 3) {
            System.out.println("Uso: java ExtractorApduUsim <log.txt> [salida.txt] [charset]");
            System.out.println("Ejemplo: java ExtractorApduUsim log.txt apdus.txt GB18030");
            return;
        }

        Path input = Paths.get(args[0]);
        Path output = args.length >= 2 ? Paths.get(args[1]) : Paths.get("apdus.txt");
        Charset charset = args.length >= 3 ? Charset.forName(args[2]) : StandardCharsets.UTF_8;

        try {
            List<Entry> entries = readEntries(input, charset);
            List<String> apdus = extractApdus(entries);
            writeTxt(output, apdus);
            System.out.println("APDU extraidas: " + apdus.size());
            System.out.println("Fichero generado: " + output.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static List<Entry> readEntries(Path input, Charset charset) throws IOException {
        List<Entry> entries = new ArrayList<Entry>();

        try (BufferedReader reader = Files.newBufferedReader(input, charset)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = LOG_LINE.matcher(line);
                if (!m.matches()) continue;

                List<String> bytes = extractLeadingHexBytes(m.group(5));
                if (bytes.isEmpty()) continue;

                Entry e = new Entry();
                e.sent = "---->".equals(m.group(2));
                e.sci = Integer.parseInt(m.group(3));
                e.lsi = Integer.parseInt(m.group(4));
                e.bytes = bytes;
                entries.add(e);
            }
        }
        return entries;
    }

    private static List<String> extractLeadingHexBytes(String text) {
        List<String> bytes = new ArrayList<String>();
        Matcher m = HEX_BYTE.matcher(text);
        int previousEnd = 0;

        while (m.find()) {
            if (!text.substring(previousEnd, m.start()).trim().isEmpty()) break;
            bytes.add(m.group(1).toUpperCase(Locale.ROOT));
            previousEnd = m.end();
        }
        return bytes;
    }

    private static List<String> extractApdus(List<Entry> entries) {
        List<String> result = new ArrayList<String>();

        for (int i = 0; i < entries.size(); i++) {
            Entry header = entries.get(i);
            if (!header.sent) continue;

            if (isFragmentedHeader(entries, i)) {
                int lc = Integer.parseInt(header.bytes.get(4), 16);
                List<String> complete = new ArrayList<String>(header.bytes);
                Entry data = entries.get(i + 2);
                complete.addAll(data.bytes.subList(0, lc));
                result.add(join(complete));
                i += 2; // Salta eco INS y línea de data ya utilizada.
            } else {
                result.add(join(header.bytes));
            }
        }
        return result;
    }

    private static boolean isFragmentedHeader(List<Entry> entries, int index) {
        Entry header = entries.get(index);
        if (!header.sent || header.bytes.size() != 5 || index + 2 >= entries.size()) {
            return false;
        }

        int lc = Integer.parseInt(header.bytes.get(4), 16);
        if (lc <= 0) return false;

        Entry echo = entries.get(index + 1);
        Entry data = entries.get(index + 2);
        String ins = header.bytes.get(1);

        return !echo.sent
            && echo.sci == header.sci
            && echo.lsi == header.lsi
            && echo.bytes.size() == 1
            && echo.bytes.get(0).equalsIgnoreCase(ins)
            && data.sent
            && data.sci == header.sci
            && data.lsi == header.lsi
            && data.bytes.size() >= lc;
    }

    private static void writeTxt(Path output, List<String> apdus) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            for (String apdu : apdus) {
                writer.write(apdu);
                writer.newLine();
            }
        }
    }

    private static String join(List<String> bytes) {
        StringBuilder sb = new StringBuilder();
        for (String b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(b);
        }
        return sb.toString();
    }
}
