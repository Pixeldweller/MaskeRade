package com.pixeldweller.maskerade.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exclusion globs on '/'-separated relative paths, gitignore-like: a pattern without '/' matches any single path
 * segment ({@code target}, {@code *.iml}); a pattern with '/' matches the whole relative path ({@code docs/**}).
 * Matching ignores case (Windows).
 */
public final class Globs {

    /** Never copied into the mirror: VCS, IDE, build output, logs, the agent's own folder. */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git", ".hg", ".svn", ".idea", "*.iml", "*.iws", "*.ipr", "target", "build", "out", "node_modules",
            ".gradle", ".kotlin", ".intellijPlatform", ".claude", "*.log", "logs", ".DS_Store", "Thumbs.db");

    /**
     * Additionally left out of a compact mirror: generated, minified and lock files that add size but no
     * understanding. Hidden files and folders are left out as well, except {@link #KEEP_HIDDEN}.
     */
    public static final List<String> COMPACT_EXCLUDES = List.of(
            "dist", "coverage", "__pycache__", "*.pyc", "venv", ".venv", "*.class", "*.jar", "*.war", "*.ear",
            "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "*.min.js", "*.min.css", "*.map",
            "*.tmp", "*.bak", "*.swp", "*~");
    public static final Set<String> KEEP_HIDDEN = Set.of(".gitignore");

    private final List<Pattern> segmentPatterns = new ArrayList<>();
    private final List<Pattern> pathPatterns = new ArrayList<>();
    private boolean hiddenExcluded;

    public Globs(List<String> globs) {
        for (var raw : globs) {
            var glob = raw.trim().replace('\\', '/');
            if (glob.isEmpty() || glob.startsWith("#")) continue;
            if (glob.endsWith("/")) glob = glob.substring(0, glob.length() - 1);
            if (glob.startsWith("/")) glob = glob.substring(1);
            if (glob.contains("/")) pathPatterns.add(toRegex(glob));
            else segmentPatterns.add(toRegex(glob));
        }
    }

    public static Globs withDefaults(List<String> extra) {
        return withDefaults(extra, false);
    }

    /** @param compact also leave out hidden files/folders (except .gitignore) and {@link #COMPACT_EXCLUDES} */
    public static Globs withDefaults(List<String> extra, boolean compact) {
        var all = new ArrayList<>(DEFAULT_EXCLUDES);
        if (compact) all.addAll(COMPACT_EXCLUDES);
        all.addAll(extra);
        var globs = new Globs(all);
        globs.hiddenExcluded = compact;
        return globs;
    }

    private static final Globs COMPACT = withDefaults(List.of(), true);

    /**
     * Hidden (except .gitignore), build output or generated — what a compact mirror leaves out. Such files are
     * shown in change lists but not selected by default.
     */
    public static boolean isHiddenOrGenerated(String relativePath) {
        return COMPACT.excludes(relativePath);
    }

    /** Splits a multi-line / comma-separated settings value into globs. */
    public static List<String> parse(String text) {
        var out = new ArrayList<String>();
        if (text == null) return out;
        for (var part : text.split("[\\r\\n,;]+")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    /** @param relativePath '/'-separated, relative to the project root */
    public boolean excludes(String relativePath) {
        for (var p : pathPatterns) {
            if (p.matcher(relativePath).matches()) return true;
        }
        for (var segment : relativePath.split("/")) {
            if (hiddenExcluded && segment.startsWith(".") && !KEEP_HIDDEN.contains(segment.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
            for (var p : segmentPatterns) {
                if (p.matcher(segment).matches()) return true;
            }
        }
        return false;
    }

    private static Pattern toRegex(String glob) {
        var sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        // a directory pattern also excludes everything below it
        sb.append("(?:/.*)?");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
