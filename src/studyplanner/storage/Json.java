package studyplanner.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader and writer, so the planner needs no third-party library.
 *
 * <p>Objects become {@code Map<String, Object>} (in file order), arrays {@code List<Object>}, numbers
 * {@code Double}, plus {@code String}, {@code Boolean} and {@code null}.
 */
final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    // ------------------------------------------------------------------ reading

    /** Parses a complete JSON document; throws IllegalArgumentException with the position if it is not valid. */
    static Object parse(String text) {
        Json p = new Json(text);
        Object value = p.value();
        p.skipSpace();
        if (p.pos != text.length()) {
            throw p.error("unexpected text after the end");
        }
        return value;
    }

    private Object value() {
        skipSpace();
        if (pos >= text.length()) {
            throw error("unexpected end of file");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;                                   // {
        skipSpace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (!peek('"')) {
                throw error("expected a name in quotes");
            }
            String key = string();
            skipSpace();
            expect(':');
            map.put(key, value());
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;                                   // [
        skipSpace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        pos++;                                   // opening quote
        StringBuilder b = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw error("short \\u escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("bad escape \\" + e);
            }
        }
        throw error("text in quotes is not closed");
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.valueOf(text.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected word");
        }
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid JSON at character " + pos + ": " + what);
    }

    // ------------------------------------------------------------------ writing

    /** Writes a value as indented JSON. */
    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, int indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON cannot store " + d);
            }
            b.append(d);
        } else if (v instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) v;
            if (map.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pad(b, indent + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent + 1);
                b.append(++i < map.size() ? ",\n" : "\n");
            }
            pad(b, indent);
            b.append('}');
        } else if (v instanceof List) {
            List<?> list = (List<?>) v;
            if (list.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(b, indent + 1);
                write(b, list.get(i), indent + 1);
                b.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            pad(b, indent);
            b.append(']');
        } else {
            throw new IllegalArgumentException("JSON cannot store a " + v.getClass().getSimpleName());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    private static void pad(StringBuilder b, int indent) {
        for (int i = 0; i < indent; i++) {
            b.append("  ");
        }
    }
}
