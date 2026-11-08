package uz.paradoks.gram;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Plain text, source code, JSON, CSV/TSV files for the in-app viewer. Read only. */
public final class ParadoksTextFormats {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public static final class Text {
        public String text = "";
        public String charset = "UTF-8";
        public boolean truncated;
        public int lines;
        public int words;
    }

    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "log", "ini", "cfg", "conf", "properties", "yaml", "yml", "toml", "env",
            "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx", "java", "kt", "kts", "py", "c", "h", "cpp", "hpp",
            "cs", "go", "rs", "swift", "php", "rb", "sh", "bat", "sql", "gradle", "dart", "lua", "srt", "vtt", "tex", "rst",
            "csv", "tsv", "gitignore", "diff", "patch"));

    public static boolean isText(String ext) {
        return TEXT.contains(ext);
    }

    public static boolean isTable(String ext) {
        return ext.equals("csv") || ext.equals("tsv");
    }

    /** Short category label, shown in the viewer subtitle. */
    public static String category(String ext) {
        switch (ext) {
            case "json": return "JSON";
            case "xml": case "html": case "htm": return "Markup";
            case "md": case "markdown": case "rst": case "tex": return "Hujjat matni";
            case "txt": case "log": case "srt": case "vtt": return "Matn";
            case "csv": case "tsv": return "Jadval";
            case "ini": case "cfg": case "conf": case "properties": case "yaml": case "yml": case "toml": case "env": return "Sozlama";
            default: return "Kod";
        }
    }

    private static byte[] readHead(File file, int max) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), max)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return off == buf.length ? buf : Arrays.copyOf(buf, off);
        }
    }

    private static String decodeStrict(byte[] b, int off, int len, String cs) {
        try {
            return Charset.forName(cs).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    public static Text readText(File file) throws IOException {
        byte[] b = readHead(file, MAX_BYTES);
        Text t = new Text();
        t.truncated = file.length() > MAX_BYTES;
        for (int i = 0; i < Math.min(b.length, 8192); i++) {
            if (b[i] == 0) {
                throw new ParadoksDocParsers.ParseException("Bu matnli fayl emas (ikkilik ma'lumot)");
            }
        }
        String s = null;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            s = decodeStrict(b, 3, b.length - 3, "UTF-8");
            t.charset = "UTF-8";
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            s = decodeStrict(b, 2, b.length - 2, "UTF-16LE");
            t.charset = "UTF-16LE";
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            s = decodeStrict(b, 2, b.length - 2, "UTF-16BE");
            t.charset = "UTF-16BE";
        }
        if (s == null) {
            // a file cut at 5 MB may end inside a multi-byte character: drop up to 3 trailing bytes
            int len = b.length;
            for (int cut = 0; cut <= 3 && s == null && len - cut > 0; cut++) {
                s = decodeStrict(b, 0, len - cut, "UTF-8");
                if (s != null) {
                    t.charset = "UTF-8";
                }
                if (!t.truncated) {
                    break;
                }
            }
        }
        if (s == null) {
            int high = 0;
            int cyr = 0;
            for (byte x : b) {
                int v = x & 0xFF;
                if (v >= 0x80) {
                    high++;
                    if (v >= 0xC0) {
                        cyr++;
                    }
                }
            }
            String cs = high > 0 && cyr * 2 > high ? "windows-1251" : "windows-1252";
            s = new String(b, Charset.forName(cs));
            t.charset = cs;
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        // minified HTML / JS / JSON is often ONE multi-megabyte line: a single TextView cannot lay that out (the app freezes)
        t.text = isTable(ext) ? s : wrapLongLines(s);
        count(t);
        return t;
    }

    /** Lines longer than this are broken into shorter ones when a text file is opened in the viewer. */
    static final int MAX_LINE = 1200;
    private static final int WRAP_AT = 1000;

    /**
     * Breaks lines longer than {@link #MAX_LINE} into pieces of about {@link #WRAP_AT} characters, preferably after
     * '>', whitespace, ',', ';' or '}'. Shorter lines (all normal text and code) are left untouched.
     */
    static String wrapLongLines(String s) {
        final int n = s.length();
        StringBuilder sb = new StringBuilder(n + n / 500 + 16);
        int i = 0;
        while (i < n) {
            int nl = s.indexOf('\n', i);
            int end = nl < 0 ? n : nl;
            int p = i;
            while (end - p > MAX_LINE) {
                int limit = p + WRAP_AT;
                int cut = limit;
                for (int k = limit; k > limit - 250; k--) {
                    char c = s.charAt(k - 1);
                    if (c == '>' || c == ' ' || c == '\t' || c == ',' || c == ';' || c == '}') {
                        cut = k;
                        break;
                    }
                }
                if (Character.isHighSurrogate(s.charAt(cut - 1))) {
                    cut--;
                }
                sb.append(s, p, cut).append('\n');
                p = cut;
            }
            sb.append(s, p, end);
            if (nl < 0) {
                break;
            }
            sb.append('\n');
            i = nl + 1;
        }
        return sb.toString();
    }

    private static void count(Text t) {
        int lines = 1;
        int words = 0;
        boolean in = false;
        String s = t.text;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                lines++;
            }
            if (Character.isWhitespace(c)) {
                in = false;
            } else if (!in) {
                in = true;
                words++;
            }
        }
        t.lines = s.isEmpty() ? 0 : lines;
        t.words = words;
    }

    /** Words, and reading time in minutes (about 200 words a minute). */
    public static int readingMinutes(int words) {
        return Math.max(1, (words + 199) / 200);
    }

    // ------------------------------------------------------------------ JSON

    /** Re-indents JSON; returns null when the text does not look like JSON. */
    public static String prettyJson(String src) {
        String s = src.trim();
        if (s.isEmpty() || (s.charAt(0) != '{' && s.charAt(0) != '[') || s.length() > 2 * 1024 * 1024) {
            return null;
        }
        StringBuilder out = new StringBuilder(s.length() + s.length() / 3);
        int depth = 0;
        boolean str = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (str) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(s.charAt(++i));
                } else if (c == '"') {
                    str = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    str = true;
                    out.append(c);
                    break;
                case '{':
                case '[': {
                    int j = i + 1;
                    while (j < n && Character.isWhitespace(s.charAt(j))) {
                        j++;
                    }
                    if (j < n && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                        out.append(c).append(s.charAt(j));
                        i = j;
                    } else {
                        depth++;
                        out.append(c).append('\n');
                        indent(out, depth);
                    }
                    break;
                }
                case '}':
                case ']':
                    depth--;
                    if (depth < 0) {
                        return null;
                    }
                    out.append('\n');
                    indent(out, depth);
                    out.append(c);
                    break;
                case ',':
                    out.append(",\n");
                    indent(out, depth);
                    break;
                case ':':
                    out.append(": ");
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        out.append(c);
                    }
            }
        }
        return depth == 0 && !str ? out.toString() : null;
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    // ------------------------------------------------------------------ CSV

    public static ParadoksDocParsers.Sheet readCsv(String text, String ext) {
        char delim = ext.equals("tsv") ? '\t' : detectDelimiter(text);
        ParadoksDocParsers.Sheet sheet = new ParadoksDocParsers.Sheet();
        sheet.name = ext.equals("tsv") ? "TSV" : "CSV";
        ArrayList<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n) {
                if (cur.length() > 0 || !row.isEmpty()) {
                    row.add(cur.toString());
                    addRow(sheet, row);
                }
                break;
            }
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"' && cur.length() == 0) {
                quoted = true;
            } else if (c == delim) {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n') {
                row.add(cur.toString());
                cur.setLength(0);
                addRow(sheet, row);
                row = new ArrayList<>();
                if (sheet.truncatedRows) {
                    break;
                }
            } else {
                cur.append(c);
            }
        }
        return sheet;
    }

    private static void addRow(ParadoksDocParsers.Sheet sheet, ArrayList<String> row) {
        if (sheet.rows.size() >= ParadoksDocParsers.MAX_ROWS) {
            sheet.truncatedRows = true;
            return;
        }
        int len = row.size();
        if (len > ParadoksDocParsers.MAX_COLS) {
            len = ParadoksDocParsers.MAX_COLS;
            sheet.truncatedCols = true;
        }
        sheet.rows.add(row.subList(0, len).toArray(new String[0]));
        sheet.cols = Math.max(sheet.cols, len);
    }

    private static char detectDelimiter(String text) {
        char[] cands = {',', ';', '\t', '|'};
        int[] count = new int[4];
        int end = Math.min(text.length(), 4000);
        boolean q = false;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                q = !q;
            } else if (c == '\n' && count[0] + count[1] + count[2] + count[3] > 0) {
                break;
            } else if (!q) {
                for (int k = 0; k < 4; k++) {
                    if (c == cands[k]) {
                        count[k]++;
                    }
                }
            }
        }
        int best = 0;
        for (int k = 1; k < 4; k++) {
            if (count[k] > count[best]) {
                best = k;
            }
        }
        return cands[best];
    }

    /** Positions of every case-insensitive match of query in text (starts only). */
    public static int[] findAll(String text, String query, int limit) {
        if (query == null || query.isEmpty()) {
            return new int[0];
        }
        String hay = text.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        if (hay.length() != text.length()) {
            hay = text;
            q = query;
        }
        int[] out = new int[16];
        int count = 0;
        int from = 0;
        while (count < limit) {
            int p = hay.indexOf(q, from);
            if (p < 0) {
                break;
            }
            if (count == out.length) {
                out = Arrays.copyOf(out, count * 2);
            }
            out[count++] = p;
            from = p + Math.max(1, q.length());
        }
        return Arrays.copyOf(out, count);
    }

    private ParadoksTextFormats() {
    }
}
