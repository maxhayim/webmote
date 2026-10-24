package io.github.maxhayim.webmote;

/** The few JSON bits the API needs: escaping strings out, and reading one field of a flat object in. */
final class Json {
    private Json() {
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** The string value of {@code "name"} in a flat JSON object, or null when it is missing or not a string. */
    static String string(String json, String name) {
        int at = valueStart(json, name);
        if (at < 0 || at >= json.length() || json.charAt(at) != '"') return null;
        StringBuilder b = new StringBuilder();
        for (int i = at + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (++i >= json.length()) return null;
            char e = json.charAt(i);
            switch (e) {
                case '"': case '\\': case '/': b.append(e); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 >= json.length()) return null;
                    try {
                        b.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                    i += 4;
                    break;
                default: return null;
            }
        }
        return null;
    }

    static boolean isTrue(String json, String name) {
        int at = valueStart(json, name);
        return at >= 0 && json.startsWith("true", at);
    }

    private static int valueStart(String json, String name) {
        if (json == null) return -1;
        String key = quote(name);
        int i = 0;
        while ((i = json.indexOf(key, i)) >= 0) {
            int j = i + key.length();
            while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
            if (j < json.length() && json.charAt(j) == ':') {
                j++;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                return j;
            }
            i = j;
        }
        return -1;
    }
}
