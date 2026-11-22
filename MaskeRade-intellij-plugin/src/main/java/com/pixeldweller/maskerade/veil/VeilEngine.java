package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.util.Words;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces real terms by placeholders (veil) and back (unveil) — deterministic, pure Java, same rules in both
 * directions.
 *
 * <p><b>Word terms</b> ("Falkenrath Logistik") match in every spelling — {@code FalkenrathLogistik},
 * {@code falkenrathLogistik}, {@code falkenrath_logistik}, {@code FALKENRATH_LOGISTIK}, {@code falkenrath-logistik},
 * {@code falkenrath.logistik}, {@code falkenrathlogistik}, "Falkenrath Logistik" — and the placeholder takes over the
 * case pattern of every word and every separator exactly. Distinctive single words of a multi-word term
 * ({@code Falkenrath}, not the everyday word {@code Logistik}) are replaced on their own as well, as word parts of
 * identifiers ({@code FalkenrathTourPlanner}).
 *
 * <p><b>Literal terms</b> (mail, host, secret, anything with digits or symbols) match as they are, in lower or
 * upper case.
 *
 * <p>Matches only start and end at identifier boundaries: camel humps, {@code _ - . /}, white space, digits —
 * {@code Falkenrath} in {@code FalkenrathTourPlanner} yes, in {@code Nichtfalkenrathig} no. Spellings that cannot
 * be mapped back unambiguously (mixed case like {@code fAlKeNrAtH}) are left alone; the residue search reports them.
 */
public final class VeilEngine {

    public enum Direction { VEIL, UNVEIL }

    /** One replacement made in a text, for reports (e.g. "external contract" detection). */
    public record Replacement(int start, int end, String from, String to, Term term) {}

    private static final String SEP = "[ _.\\-]";

    // identifier boundaries before / after a word match (see class comment)
    private static final String WORD_LEFT =
            "(?:(?<![\\p{L}\\p{N}])|(?<=\\p{N})|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll}))";
    private static final String WORD_RIGHT =
            "(?:(?![\\p{L}\\p{N}])|(?=\\p{N})|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll}))";

    private final List<Rule> rules;
    private final Replacer veiler;
    private final Replacer unveiler;

    private VeilEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
        this.veiler = new Replacer(this.rules, Direction.VEIL);
        this.unveiler = new Replacer(this.rules, Direction.UNVEIL);
    }

    /**
     * @param terms      the veil list; inactive and invalid entries ({@link TermValidator}) are skipped
     * @param commonWord everyday words that are never replaced on their own (part rules of multi-word terms)
     */
    public static VeilEngine of(List<Term> terms, Predicate<String> commonWord) {
        var usable = terms.stream()
                .filter(t -> t.active)
                .filter(t -> TermValidator.problemsOf(t).isEmpty())
                .toList();
        var rules = new ArrayList<Rule>();
        var realWordsWithRule = new HashSet<String>();
        var placeholderWordsWithRule = new HashSet<String>();
        for (var t : usable) {
            if (t.isLiteral()) {
                rules.add(Rule.literal(t));
            } else {
                rules.add(Rule.words(t, t.realWords(), t.placeholderWords(), false));
                if (t.realWords().size() == 1) {
                    realWordsWithRule.add(t.realWords().getFirst().toLowerCase(Locale.ROOT));
                    placeholderWordsWithRule.add(t.placeholderWords().getFirst().toLowerCase(Locale.ROOT));
                }
            }
        }
        // distinctive words of multi-word terms on their own — unless a single-word term already defines them
        for (var t : usable) {
            if (t.isLiteral() || t.realWords().size() < 2) continue;
            var real = t.realWords();
            var placeholder = t.placeholderWords();
            for (int i = 0; i < real.size(); i++) {
                var r = real.get(i).toLowerCase(Locale.ROOT);
                var p = placeholder.get(i).toLowerCase(Locale.ROOT);
                if (r.length() < 4 || commonWord.test(r) || commonWord.test(p)) continue;
                if (!realWordsWithRule.add(r) || !placeholderWordsWithRule.add(p)) continue;
                rules.add(Rule.words(t, List.of(real.get(i)), List.of(placeholder.get(i)), true));
            }
        }
        return new VeilEngine(rules);
    }

    public String veil(String text) {
        return veiler.apply(text, null);
    }

    public String veil(String text, Consumer<Replacement> listener) {
        return veiler.apply(text, listener);
    }

    public String unveil(String text) {
        return unveiler.apply(text, null);
    }

    public String unveil(String text, Consumer<Replacement> listener) {
        return unveiler.apply(text, listener);
    }

    /** '/'-separated relative path: package directories and file names are renamed like any text. */
    public String veilPath(String relativePath) {
        return veil(relativePath);
    }

    public String unveilPath(String relativePath) {
        return unveil(relativePath);
    }

    public String apply(Direction direction, String text, Consumer<Replacement> listener) {
        return (direction == Direction.VEIL ? veiler : unveiler).apply(text, listener);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<Term> terms() {
        return rules.stream().filter(r -> !r.part).map(r -> r.term).toList();
    }

    /** Placeholder words / literals of all rules, lower case — for the "placeholder left after unveil" check. */
    public List<String> placeholderNeedles() {
        var out = new ArrayList<String>();
        for (var r : rules) {
            if (r.literal) out.add(r.placeholderLiteral.toLowerCase(Locale.ROOT));
            else out.add(String.join("", r.placeholderWords).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ── Rules ───────────────────────────────────────────────────────────────────

    static final class Rule {
        final Term term;
        final boolean literal;
        final boolean part;
        final List<String> realWords;
        final List<String> placeholderWords;
        final String realLiteral;
        final String placeholderLiteral;
        final boolean caseSensitive;

        private Rule(Term term, boolean literal, boolean part, List<String> realWords, List<String> placeholderWords,
                     String realLiteral, String placeholderLiteral, boolean caseSensitive) {
            this.term = term;
            this.literal = literal;
            this.part = part;
            this.realWords = realWords;
            this.placeholderWords = placeholderWords;
            this.realLiteral = realLiteral;
            this.placeholderLiteral = placeholderLiteral;
            this.caseSensitive = caseSensitive;
        }

        static Rule literal(Term t) {
            // secrets are exact: a different case is a different key
            boolean caseSensitive = t.kind == com.pixeldweller.maskerade.model.TermKind.SECRET;
            return new Rule(t, true, false, List.of(), List.of(), t.real.trim(), t.placeholder.trim(), caseSensitive);
        }

        static Rule words(Term t, List<String> real, List<String> placeholder, boolean part) {
            return new Rule(t, false, part, List.copyOf(real), List.copyOf(placeholder), null, null, false);
        }

        List<String> source(Direction d) {
            return d == Direction.VEIL ? realWords : placeholderWords;
        }

        List<String> target(Direction d) {
            return d == Direction.VEIL ? placeholderWords : realWords;
        }

        String sourceLiteral(Direction d) {
            return d == Direction.VEIL ? realLiteral : placeholderLiteral;
        }

        String targetLiteral(Direction d) {
            return d == Direction.VEIL ? placeholderLiteral : realLiteral;
        }

        /** Sort key: literals first, longer first; whole terms before their single-word parts. */
        int priority() {
            if (literal) return 3_000_000 + realLiteral.length();
            return (part ? 0 : 1_000_000) + realWords.size() * 1000 + String.join("", realWords).length();
        }

        /** Alternative of the combined pattern; non-capturing only. */
        String regex(Direction d) {
            if (literal) {
                var src = sourceLiteral(d);
                var body = caseSensitive ? Pattern.quote(src) : "(?iu:" + Pattern.quote(src) + ")";
                return literalLeft(src) + body + literalRight(src);
            }
            var sb = new StringBuilder(WORD_LEFT);
            var words = source(d);
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sb.append(SEP).append('?');
                sb.append("(?iu:").append(Pattern.quote(words.get(i))).append(')');
            }
            return sb.append(WORD_RIGHT).toString();
        }

        /** Same as {@link #regex} but anchored and with groups for words and separators. */
        Pattern detail(Direction d) {
            var sb = new StringBuilder("^");
            var words = source(d);
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sb.append('(').append(SEP).append("?)");
                sb.append("((?iu:").append(Pattern.quote(words.get(i))).append("))");
            }
            return Pattern.compile(sb.append('$').toString());
        }

        private static String literalLeft(String s) {
            if (s.isEmpty()) return "";
            char c = s.charAt(0);
            if (!Character.isLetterOrDigit(c)) return "";
            return Character.isDigit(c) ? "(?<![\\p{L}\\p{N}])(?<!\\p{N}\\.)" : "(?<![\\p{L}\\p{N}])";
        }

        private static String literalRight(String s) {
            if (s.isEmpty()) return "";
            char c = s.charAt(s.length() - 1);
            if (!Character.isLetterOrDigit(c)) return "";
            return Character.isDigit(c) ? "(?![\\p{L}\\p{N}])(?!\\.\\p{N})" : "(?![\\p{L}\\p{N}])";
        }
    }

    // ── Replacer: one combined pattern per direction ───────────────────────────

    private static final class Replacer {
        private final Direction direction;
        private final List<Rule> ordered;
        private final List<Pattern> details;
        private final Pattern combined;

        Replacer(List<Rule> rules, Direction direction) {
            this.direction = direction;
            this.ordered = rules.stream().sorted(Comparator.comparingInt(Rule::priority).reversed()).toList();
            this.details = ordered.stream().map(r -> r.literal ? null : r.detail(direction)).toList();
            if (ordered.isEmpty()) {
                combined = null;
            } else {
                var sb = new StringBuilder();
                for (var r : ordered) {
                    if (!sb.isEmpty()) sb.append('|');
                    sb.append('(').append(r.regex(direction)).append(')');
                }
                combined = Pattern.compile(sb.toString());
            }
        }

        String apply(String text, Consumer<Replacement> listener) {
            if (combined == null || text.isEmpty()) return text;
            Matcher m = combined.matcher(text);
            var out = new StringBuilder(text.length() + 16);
            int last = 0;
            int pos = 0;
            while (pos <= text.length() && m.find(pos)) {
                int group = 1;
                while (m.group(group) == null) group++;
                var rule = ordered.get(group - 1);
                var matched = m.group();
                var replacement = transform(rule, details.get(group - 1), matched);
                if (replacement == null) {
                    // unsupported spelling: leave it, the residue search reports it
                    pos = m.start() + 1;
                    continue;
                }
                out.append(text, last, m.start()).append(replacement);
                if (listener != null) {
                    listener.accept(new Replacement(m.start(), m.end(), matched, replacement, rule.term));
                }
                last = m.end();
                pos = m.end();
            }
            out.append(text, last, text.length());
            return out.toString();
        }

        private String transform(Rule rule, Pattern detail, String matched) {
            if (rule.literal) {
                var src = rule.sourceLiteral(direction);
                var tgt = rule.targetLiteral(direction);
                if (matched.equals(src)) return tgt;
                if (rule.caseSensitive) return null;
                if (matched.equals(src.toLowerCase(Locale.ROOT))) return tgt.toLowerCase(Locale.ROOT);
                if (matched.equals(src.toUpperCase(Locale.ROOT))) return tgt.toUpperCase(Locale.ROOT);
                return null;
            }
            var m = detail.matcher(matched);
            if (!m.matches()) return null;
            var src = rule.source(direction);
            var tgt = rule.target(direction);
            var out = new StringBuilder();
            int g = 1;
            for (int i = 0; i < src.size(); i++) {
                if (i > 0) out.append(m.group(g++));
                var word = m.group(g++);
                var mapped = mapWord(word, src.get(i), tgt.get(i));
                if (mapped == null) return null;
                out.append(mapped);
            }
            return out.toString();
        }

        /**
         * lower / UPPER / Capital keep their pattern; a mixed spelling ("McKenzie") is only accepted as the exact
         * canonical form and maps to the other side's canonical form. Anything else is ambiguous → null.
         */
        private static String mapWord(String word, String srcCanonical, String tgtCanonical) {
            var c = Words.caseOf(word);
            if (c != Words.Case.OTHER) return Words.apply(c, tgtCanonical);
            return word.equals(srcCanonical) ? tgtCanonical : null;
        }
    }
}
