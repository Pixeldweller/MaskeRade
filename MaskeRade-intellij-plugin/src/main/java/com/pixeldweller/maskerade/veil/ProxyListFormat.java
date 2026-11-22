package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The list format of the agent proxy: one {@code real = placeholder} per line, {@code #} starts a comment.
 * On export every derived spelling becomes its own line (the proxy only derives upper/lower case itself); on import
 * lines that are spellings of the same term are merged again.
 */
public final class ProxyListFormat {

    private ProxyListFormat() {}

    private static final String KIND_PREFIX = "# kind: ";

    public static String export(List<Term> terms, Predicate<String> commonWord) {
        var sb = new StringBuilder();
        sb.append("# MaskeRade veil list — format of the agent proxy: real = placeholder\n");
        sb.append("# every spelling is listed on its own line\n");
        for (var t : terms) {
            if (!t.active || !TermValidator.problemsOf(t).isEmpty()) continue;
            sb.append('\n').append(KIND_PREFIX).append(t.kind.name().toLowerCase(Locale.ROOT));
            if (t.note != null && !t.note.isBlank()) sb.append(" — ").append(t.note.replace('\n', ' '));
            sb.append('\n');
            for (var pair : Spellings.of(t, commonWord)) {
                sb.append(pair.real()).append(" = ").append(pair.placeholder()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * A block written by {@link #export} ("# kind: …" up to the next blank line) is one term: its first line.
     * Lines of other lists are merged by spelling: "FalkenrathLogistik", "falkenrath_logistik" and
     * "falkenrathlogistik" belong to "Falkenrath Logistik"; single words of a multi-word term are dropped.
     */
    public static List<Term> parse(String text) {
        var byKey = new LinkedHashMap<String, Term>();
        TermKind kind = null;
        boolean inBlock = false;
        boolean blockHasTerm = false;
        for (var rawLine : text.split("\\R")) {
            var line = rawLine.strip();
            if (line.startsWith(KIND_PREFIX.strip())) {
                kind = kindOf(line.substring(KIND_PREFIX.strip().length()).strip().split("\\s")[0]);
                inBlock = true;
                blockHasTerm = false;
                continue;
            }
            if (line.isEmpty()) {
                kind = null;
                inBlock = false;
                continue;
            }
            if (line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            var real = line.substring(0, eq).strip();
            var placeholder = line.substring(eq + 1).strip();
            int comment = placeholder.indexOf(" #");
            if (comment >= 0) placeholder = placeholder.substring(0, comment).strip();
            if (real.isEmpty() || placeholder.isEmpty()) continue;
            if (inBlock && blockHasTerm) continue;
            blockHasTerm = true;
            var term = new Term(real, placeholder, kind != null ? kind : guessKind(real));
            byKey.putIfAbsent(term.key(), term);
        }
        // merge flat spellings and single words into multi-word terms
        var multiWordParts = new java.util.HashSet<String>();
        var multiWordFlat = new java.util.HashSet<String>();
        for (var t : byKey.values()) {
            if (!t.isLiteral() && t.realWords().size() > 1) {
                t.realWords().forEach(w -> multiWordParts.add(w.toLowerCase(Locale.ROOT)));
                multiWordFlat.add(t.key().replace(" ", ""));
            }
        }
        var out = new ArrayList<Term>();
        for (var t : byKey.values()) {
            boolean single = !t.isLiteral() && t.realWords().size() == 1;
            if (single && (multiWordParts.contains(t.key()) || multiWordFlat.contains(t.key()))) continue;
            out.add(t);
        }
        return out;
    }

    private static TermKind kindOf(String name) {
        try {
            return TermKind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static TermKind guessKind(String real) {
        if (real.contains("@")) return TermKind.MAIL;
        if (real.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+") && !real.matches("\\p{L}+(\\.\\p{L}+)?")) return TermKind.HOST;
        if (real.matches("\\d{1,3}(\\.\\d{1,3}){3}") || real.startsWith("jdbc:")) return TermKind.HOST;
        return TermKind.OTHER;
    }
}
