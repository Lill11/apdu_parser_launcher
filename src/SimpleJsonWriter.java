import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class SimpleJsonWriter {

    private SimpleJsonWriter() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String text) {
            sb.append("\"").append(ApduParserProcessor.escapeJson(text)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof PathLike pathLike) {
            append(sb, pathLike.toPathString());
        } else if (value instanceof Map<?, ?> map) {
            sb.append("{");
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                append(sb, String.valueOf(entry.getKey()));
                sb.append(":");
                append(sb, entry.getValue());
                if (iterator.hasNext()) {
                    sb.append(",");
                }
            }
            sb.append("}");
        } else if (value instanceof Iterable<?> iterable) {
            sb.append("[");
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                append(sb, iterator.next());
                if (iterator.hasNext()) {
                    sb.append(",");
                }
            }
            sb.append("]");
        } else if (value instanceof Object[] array) {
            append(sb, List.of(array));
        } else {
            append(sb, String.valueOf(value));
        }
    }

    interface PathLike {
        String toPathString();
    }
}
