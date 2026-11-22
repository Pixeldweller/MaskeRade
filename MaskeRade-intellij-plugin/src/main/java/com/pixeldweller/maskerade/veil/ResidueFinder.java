package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.util.TextCodec;
import com.pixeldweller.maskerade.util.Words;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches for real terms that are still there — deliberately loose: any case, single separators between the
 * letters ({@code f-a-l-k-e-n-r-a-t-h}, {@code f_a_l_k…}), Base64, {@code \}{@code u}-escapes and, in binary files,
 * UTF-16. Used on the finished mirror (nothing may be left), on the original (encoded places are reported, never
 * replaced silently) and on the model's changes (a real term there points to a leak).
 */
public final class ResidueFinder {

    public enum Form {
        PLAIN("plain"), SPACED("with separators / other case"), BASE64("Base64"), UNICODE_ESCAPE("\\u-escaped"),
        UTF16("UTF-16"), BINARY("in binary data");

        private final String label;

        Form(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Hit(Term term, String needle, Form form, int line, String snippet) {}

    private record Needle(Term term, String text, Pattern exact, Pattern loose) {}

    private static final Pattern BASE64_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{8,}={0,2}");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private final List<Needle> needles = new ArrayList<>();

    public ResidueFinder(List<Term> terms, Predicate<String> commonWord) {
        var seen = new HashSet<String>();
        for (var t : terms) {
            if (!t.active || t.real == null || t.real.isBlank()) continue;
            addNeedle(t, t.real.trim(), seen);
            if (!t.isLiteral() && t.realWords().size() > 1) {
                for (var w : t.realWords()) {
                    var lower = w.toLowerCase(Locale.ROOT);
                    if (lower.length() >= 4 && !commonWord.test(lower)) addNeedle(t, w, seen);
                }
            }
        }
    }

    private void addNeedle(Term t, String text, HashSet<String> seen) {
        var alnum = text.codePoints().filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (alnum.isEmpty() || !seen.add(alnum.toLowerCase(Locale.ROOT) + "|" + t.kind)) return;
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        var exact = t.kind == TermKind.SECRET ? Pattern.compile(Pattern.quote(text)) : Pattern.compile(Pattern.quote(text), flags);
        Pattern loose = null;
        if (t.kind != TermKind.SECRET) {
            if (alnum.length() >= 4) {
                var sb = new StringBuilder();
                for (int i = 0; i < alnum.length(); i++) {
                    if (i > 0) sb.append("[^\\p{L}\\p{N}\\r\\n]?");
                    sb.append(Pattern.quote(String.valueOf(alnum.charAt(i))));
                }
                loose = Pattern.compile(sb.toString(), flags);
            } else {
                // short terms only as whole words, otherwise every "lan" in "plan" would be a hit
                loose = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(alnum) + "(?![\\p{L}\\p{N}])", flags);
            }
        }
        needles.add(new Needle(t, text, exact, loose));
    }

    public boolean isEmpty() {
        return needles.isEmpty();
    }

    /** Plain and loose hits, plus Base64 and \\u-escaped ones. */
    public List<Hit> findInText(String text) {
        var hits = new ArrayList<Hit>();
        plain(text, hits, null);
        hits.addAll(findEncoded(text));
        return dedupe(hits);
    }

    /** Only the encoded forms (Base64, \\u) — for reporting such places in the original. */
    public List<Hit> findEncoded(String text) {
        var hits = new ArrayList<Hit>();
        var m = BASE64_TOKEN.matcher(text);
        while (m.find()) {
            var decoded = decodeBase64(m.group());
            if (decoded == null) continue;
            for (var n : needles) {
                if (n.loose != null ? n.loose.matcher(decoded).find() : n.exact.matcher(decoded).find()) {
                    hits.add(new Hit(n.term, n.text, Form.BASE64, TextCodec.lineOf(text, m.start()),
                            TextCodec.snippet(text, m.start(), m.end())));
                }
            }
        }
        if (text.contains("\\u")) {
            var unescaped = unescapeUnicode(text);
            if (!unescaped.equals(text)) {
                var plainHits = new ArrayList<Hit>();
                plain(text, plainHits, null);
                var known = new HashSet<String>();
                plainHits.forEach(h -> known.add(h.needle + "|" + h.line));
                var decodedHits = new ArrayList<Hit>();
                plain(unescaped, decodedHits, Form.UNICODE_ESCAPE);
                for (var h : decodedHits) {
                    if (!known.contains(h.needle + "|" + h.line)) hits.add(h);
                }
            }
        }
        return dedupe(hits);
    }

    /** Binary files: bytes as Latin-1 (UTF-8 / ASCII text inside), with NULs removed (UTF-16), and Base64. */
    public List<Hit> findInBytes(byte[] bytes) {
        var hits = new ArrayList<Hit>();
        var latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        var withoutNul = latin1.replace("\0", "");
        var utf16 = new ArrayList<Hit>();
        if (withoutNul.length() < latin1.length()) {
            // UTF-16 first: NUL counts as a separator in the loose search and would hide the form
            var exactOnly = new ArrayList<Hit>();
            plain(withoutNul, exactOnly, Form.UTF16);
            for (var h : exactOnly) {
                if (!latin1.toLowerCase(java.util.Locale.ROOT).contains(h.needle.toLowerCase(java.util.Locale.ROOT))) utf16.add(h);
            }
            hits.addAll(utf16);
        }
        var plainHits = new ArrayList<Hit>();
        plain(latin1, plainHits, Form.BINARY);
        for (var h : plainHits) {
            if (utf16.stream().noneMatch(u -> u.needle.equals(h.needle))) hits.add(h);
        }
        for (var h : findEncoded(latin1)) {
            if (h.form == Form.BASE64) hits.add(h);
        }
        // line numbers are meaningless in binary data
        return dedupe(hits.stream().map(h -> new Hit(h.term, h.needle, h.form, 0, printable(h.snippet))).toList());
    }

    private void plain(String text, List<Hit> hits, Form forced) {
        for (var n : needles) {
            Matcher m = n.exact.matcher(text);
            var exactStarts = new HashSet<Integer>();
            while (m.find()) {
                exactStarts.add(m.start());
                hits.add(new Hit(n.term, n.text, forced != null ? forced : Form.PLAIN, TextCodec.lineOf(text, m.start()),
                        TextCodec.snippet(text, m.start(), m.end())));
            }
            if (n.loose == null) continue;
            m = n.loose.matcher(text);
            while (m.find()) {
                if (exactStarts.contains(m.start())) continue;
                hits.add(new Hit(n.term, n.text, forced != null ? forced : Form.SPACED, TextCodec.lineOf(text, m.start()),
                        TextCodec.snippet(text, m.start(), m.end())));
            }
        }
    }

    static String decodeBase64(String token) {
        var t = token.replace('-', '+').replace('_', '/');
        int pad = t.indexOf('=');
        var core = pad >= 0 ? t.substring(0, pad) : t;
        if (core.length() % 4 == 1) return null;
        var padded = core + "==".substring(0, (4 - core.length() % 4) % 4);
        try {
            var bytes = Base64.getDecoder().decode(padded);
            // mostly printable? (UTF-16 text has NULs between the letters)
            var text = new String(bytes, StandardCharsets.UTF_8).replace("\0", "");
            int printable = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || (c >= 0x20 && c < 0x7F)) printable++;
            }
            return text.length() >= 4 && printable >= text.length() * 0.85 ? text : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String unescapeUnicode(String text) {
        var m = UNICODE_ESCAPE.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * One hit per needle, form and line; and none for "Falkenrath" where "Falkenrath Logistik" was found at the
     * same line in the same form — the longer term already says it.
     */
    private static List<Hit> dedupe(List<Hit> hits) {
        var seen = new HashSet<String>();
        var unique = new ArrayList<Hit>();
        for (var h : hits) {
            if (seen.add(h.needle.toLowerCase(Locale.ROOT) + "|" + h.form + "|" + h.line)) unique.add(h);
        }
        var out = new ArrayList<Hit>();
        for (var h : unique) {
            var needle = h.needle.toLowerCase(Locale.ROOT);
            boolean covered = unique.stream().anyMatch(o -> o != h && o.line == h.line && o.form == h.form
                    && o.needle.length() > h.needle.length() && o.needle.toLowerCase(Locale.ROOT).contains(needle));
            if (!covered) out.add(h);
        }
        return out;
    }

    private static String printable(String s) {
        var sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '·');
        }
        return sb.toString();
    }

    /** Words of a term that the loose search uses — exposed for tests and the UI. */
    public static List<String> partsOf(Term t) {
        return Words.letterParts(t.real);
    }
}
