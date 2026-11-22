package com.pixeldweller.maskerade.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits C-like source code (Java, Kotlin, JS/TS, …) into code and text (comments, string literals), so that the
 * scanner can tell identifiers from prose. Other files are text only.
 */
public final class SourceSplitter {

    private SourceSplitter() {}

    /** @param comment a comment (text segments that are not comments are string literals) */
    public record Segment(int start, int end, boolean code, boolean comment) {}

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "gradle", "scala", "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue",
            "c", "h", "cpp", "hpp", "cc", "cs", "go", "swift", "rs", "dart");

    public static boolean isCode(String path) {
        var dot = path.lastIndexOf('.');
        return dot >= 0 && CODE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static List<Segment> split(String path, String text) {
        if (!isCode(path)) return List.of(new Segment(0, text.length(), false, false));
        var out = new ArrayList<Segment>();
        int n = text.length();
        int codeStart = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            int end = -1;
            boolean comment = false;
            if (c == '/' && next == '/') {
                end = text.indexOf('\n', i);
                if (end < 0) end = n;
                comment = true;
            } else if (c == '/' && next == '*') {
                end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                comment = true;
            } else if (c == '"' && text.startsWith("\"\"\"", i)) {
                end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'' || c == '`') {
                end = endOfString(text, i, c);
            }
            if (end >= 0) {
                if (i > codeStart) out.add(new Segment(codeStart, i, true, false));
                out.add(new Segment(i, end, false, comment));
                i = end;
                codeStart = end;
            } else {
                i++;
            }
        }
        if (codeStart < n) out.add(new Segment(codeStart, n, true, false));
        return out;
    }

    /** Whether a position of a source file lies in a comment (never in non-code files). */
    public static boolean inComment(List<Segment> segments, int index) {
        for (var s : segments) {
            if (index >= s.start() && index < s.end()) return s.comment();
        }
        return false;
    }

    private static int endOfString(String text, int start, char quote) {
        int n = text.length();
        for (int i = start + 1; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n' && quote != '`') {
                return i; // unterminated: stop at the line end
            }
        }
        return n;
    }
}
