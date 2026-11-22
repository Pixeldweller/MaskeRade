package com.pixeldweller.maskerade.mirror;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a git-style unified diff ({@code git apply}, IntelliJ "Apply Patch…"). Pure Java, byte-exact: lines are
 * compared and written as raw bytes, so CRLF, BOM and any 8-bit encoding survive; paths are written in UTF-8.
 */
public final class UnifiedDiff {

    private UnifiedDiff() {}

    /** Beyond this edit distance a file is written as "delete all, insert all" (keeps memory bounded). */
    private static final int MAX_EDIT_DISTANCE = 4000;

    /**
     * Binary files cannot be carried by a text patch; they are listed in a note before the first file header
     * (git apply and IntelliJ ignore text there), so the patch stays applicable.
     */
    public static byte[] write(List<FolderDiff.Entry> entries, int context) {
        return write(entries, context, false);
    }

    /**
     * @param exactLineEndings keep CR of CRLF lines in the patch (for {@code git apply} with core.autocrlf=false).
     *                         Default (false): lines are written with LF, like git itself does with
     *                         core.autocrlf=true and as IntelliJ's "Apply Patch…" expects; changes that only
     *                         concern line endings are then not part of the patch.
     */
    public static byte[] write(List<FolderDiff.Entry> entries, int context, boolean exactLineEndings) {
        var out = new ByteArrayOutputStream();
        var binaries = entries.stream().filter(FolderDiff.Entry::binary).toList();
        if (!binaries.isEmpty()) {
            utf8(out, "MaskeRade: binary files differ and are not part of this patch — copy them by hand:\n");
            for (var b : binaries) utf8(out, "  " + b.kind().name().toLowerCase() + "  " + b.path() + "\n");
            utf8(out, "\n");
        }
        for (var e : entries) {
            if (!e.binary()) writeFile(out, e, context, exactLineEndings);
        }
        return out.toByteArray();
    }

    public static String writeToString(List<FolderDiff.Entry> entries) {
        return new String(write(entries, 3), StandardCharsets.ISO_8859_1);
    }

    private static void writeFile(ByteArrayOutputStream out, FolderDiff.Entry e, int context, boolean exactLineEndings) {
        var path = e.path();
        var a = lines(e.left());
        var b = lines(e.right());
        if (!exactLineEndings) {
            a = a.stream().map(UnifiedDiff::toLf).toList();
            b = b.stream().map(UnifiedDiff::toLf).toList();
        }
        var ops = diff(a, b);
        if (e.kind() == FolderDiff.Kind.MODIFIED && ops.stream().allMatch(op -> op.type == ' ')) return; // only line endings
        utf8(out, "diff --git a/" + path + " b/" + path + "\n");
        if (e.kind() == FolderDiff.Kind.ADDED) utf8(out, "new file mode 100644\n");
        if (e.kind() == FolderDiff.Kind.DELETED) utf8(out, "deleted file mode 100644\n");
        if (a.isEmpty() && b.isEmpty()) return;
        var from = e.kind() == FolderDiff.Kind.ADDED ? "/dev/null" : "a/" + path;
        var to = e.kind() == FolderDiff.Kind.DELETED ? "/dev/null" : "b/" + path;
        utf8(out, "--- " + from + "\n");
        utf8(out, "+++ " + to + "\n");
        for (var hunk : hunks(ops, context)) {
            int aStart = hunk.aFrom, aLen = hunk.aTo - hunk.aFrom;
            int bStart = hunk.bFrom, bLen = hunk.bTo - hunk.bFrom;
            utf8(out, "@@ -" + range(aStart, aLen) + " +" + range(bStart, bLen) + " @@\n");
            for (int i = hunk.opFrom; i < hunk.opTo; i++) {
                var op = ops.get(i);
                var line = op.type == '+' ? b.get(op.b) : a.get(op.a);
                raw(out, op.type + stripNewline(line) + "\n");
                if (!line.endsWith("\n")) utf8(out, "\\ No newline at end of file\n");
            }
        }
    }

    private static String range(int start0, int len) {
        // "-0,0" for an empty side; otherwise 1-based start
        int start = len == 0 ? start0 : start0 + 1;
        return len == 1 ? String.valueOf(start) : start + "," + len;
    }

    /** Lines of a file as raw bytes (ISO-8859-1 view), each including its "\n" / "\r\n"; the last one maybe without. */
    static List<String> lines(byte[] bytes) {
        var out = new ArrayList<String>();
        if (bytes == null || bytes.length == 0) return out;
        var s = new String(bytes, StandardCharsets.ISO_8859_1);
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    private static String toLf(String line) {
        return line.endsWith("\r\n") ? line.substring(0, line.length() - 2) + "\n" : line;
    }

    private static String stripNewline(String line) {
        return line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
    }

    // ── diff (Myers, with common prefix/suffix trimmed) ─────────────────────────

    record Op(char type, int a, int b) {}

    static List<Op> diff(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        var ops = new ArrayList<Op>();
        for (int i = 0; i < prefix; i++) ops.add(new Op(' ', i, i));
        var middle = myers(a.subList(prefix, a.size() - suffix), b.subList(prefix, b.size() - suffix));
        for (var op : middle) ops.add(new Op(op.type, op.a + prefix, op.b + prefix));
        for (int i = 0; i < suffix; i++) ops.add(new Op(' ', a.size() - suffix + i, b.size() - suffix + i));
        return ops;
    }

    private static List<Op> myers(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int limit = Math.min(n + m, MAX_EDIT_DISTANCE);
        int offset = limit + 1;
        var v = new int[2 * limit + 3];
        var trace = new ArrayList<int[]>();
        int found = -1;
        for (int d = 0; d <= limit && found < 0; d++) {
            // keep v[-d..d] as it was before this step
            var snapshot = new int[2 * d + 1];
            for (int k = -d; k <= d; k++) snapshot[k + d] = v[k + offset];
            trace.add(snapshot);
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) ? v[k + 1 + offset] : v[k - 1 + offset] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[k + offset] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break;
                }
            }
        }
        if (found < 0) {
            // too many changes: replace everything
            var ops = new ArrayList<Op>();
            for (int i = 0; i < n; i++) ops.add(new Op('-', i, 0));
            for (int j = 0; j < m; j++) ops.add(new Op('+', 0, j));
            return ops;
        }
        var reversed = new ArrayList<Op>();
        int x = n, y = m;
        for (int d = found; d >= 0; d--) {
            var prev = trace.get(d); // v before step d, indices -d..d (only -(d-1)..d-1 are meaningful)
            int k = x - y;
            int prevK;
            if (d == 0) {
                prevK = 0;
            } else if (k == -d || (k != d && at(prev, d, k - 1) < at(prev, d, k + 1))) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = d == 0 ? 0 : at(prev, d, prevK);
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                reversed.add(new Op(' ', x - 1, y - 1));
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) reversed.add(new Op('+', x, y - 1));
                else reversed.add(new Op('-', x - 1, y));
            }
            x = prevX;
            y = prevY;
        }
        var ops = new ArrayList<Op>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) ops.add(reversed.get(i));
        return ops;
    }

    private static int at(int[] snapshot, int d, int k) {
        int i = k + d;
        return i >= 0 && i < snapshot.length ? snapshot[i] : 0;
    }

    // ── hunks ───────────────────────────────────────────────────────────────────

    private record Hunk(int opFrom, int opTo, int aFrom, int aTo, int bFrom, int bTo) {}

    private static List<Hunk> hunks(List<Op> ops, int context) {
        var hunks = new ArrayList<Hunk>();
        int i = 0;
        while (i < ops.size()) {
            while (i < ops.size() && ops.get(i).type == ' ') i++;
            if (i >= ops.size()) break;
            int start = Math.max(0, i - context);
            int end = i;
            int lastChange = i;
            while (end < ops.size()) {
                if (ops.get(end).type != ' ') lastChange = end;
                else if (end - lastChange > 2 * context) break;
                end++;
            }
            end = Math.min(ops.size(), lastChange + context + 1);
            int aFrom = -1, bFrom = -1, aCount = 0, bCount = 0;
            for (int j = start; j < end; j++) {
                var op = ops.get(j);
                if (op.type != '+') {
                    if (aFrom < 0) aFrom = op.a;
                    aCount++;
                }
                if (op.type != '-') {
                    if (bFrom < 0) bFrom = op.b;
                    bCount++;
                }
            }
            if (aFrom < 0) aFrom = firstA(ops, start);
            if (bFrom < 0) bFrom = firstB(ops, start);
            hunks.add(new Hunk(start, end, aFrom, aFrom + aCount, bFrom, bFrom + bCount));
            i = end;
        }
        return hunks;
    }

    /** For a hunk without lines on side a: the number of a-lines before it. */
    private static int firstA(List<Op> ops, int start) {
        for (int j = start - 1; j >= 0; j--) {
            if (ops.get(j).type != '+') return ops.get(j).a + 1;
        }
        return 0;
    }

    private static int firstB(List<Op> ops, int start) {
        for (int j = start - 1; j >= 0; j--) {
            if (ops.get(j).type != '-') return ops.get(j).b + 1;
        }
        return 0;
    }

    private static void utf8(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void raw(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
