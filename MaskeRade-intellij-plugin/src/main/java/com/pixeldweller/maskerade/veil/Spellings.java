package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.util.Words;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The usual spellings of a term and its placeholder, side by side — for the preview in the Terms tab and for the
 * export to the agent proxy, which only derives upper/lower case itself.
 */
public final class Spellings {

    private Spellings() {}

    public record Pair(String style, String real, String placeholder) {}

    public static List<Pair> of(Term t, Predicate<String> commonWord) {
        var out = new LinkedHashMap<String, Pair>();
        if (t.isLiteral()) {
            add(out, "as written", t.real.trim(), t.placeholder.trim());
            if (t.kind != com.pixeldweller.maskerade.model.TermKind.SECRET) {
                add(out, "lower", t.real.trim().toLowerCase(Locale.ROOT), t.placeholder.trim().toLowerCase(Locale.ROOT));
                add(out, "UPPER", t.real.trim().toUpperCase(Locale.ROOT), t.placeholder.trim().toUpperCase(Locale.ROOT));
            }
            return new ArrayList<>(out.values());
        }
        var r = t.realWords();
        var p = t.placeholderWords();
        if (r.size() != p.size() || r.isEmpty()) return List.of();
        add(out, "as written", t.real.trim(), t.placeholder.trim());
        add(out, "Words", join(r, " ", Words.Case.CAPITAL), join(p, " ", Words.Case.CAPITAL));
        add(out, "PascalCase", join(r, "", Words.Case.CAPITAL), join(p, "", Words.Case.CAPITAL));
        add(out, "camelCase", camel(r), camel(p));
        add(out, "snake_case", join(r, "_", Words.Case.LOWER), join(p, "_", Words.Case.LOWER));
        add(out, "SCREAMING_SNAKE", join(r, "_", Words.Case.UPPER), join(p, "_", Words.Case.UPPER));
        add(out, "kebab-case", join(r, "-", Words.Case.LOWER), join(p, "-", Words.Case.LOWER));
        add(out, "dot.case", join(r, ".", Words.Case.LOWER), join(p, ".", Words.Case.LOWER));
        add(out, "flatcase", join(r, "", Words.Case.LOWER), join(p, "", Words.Case.LOWER));
        add(out, "UPPERFLAT", join(r, "", Words.Case.UPPER), join(p, "", Words.Case.UPPER));
        add(out, "lower words", join(r, " ", Words.Case.LOWER), join(p, " ", Words.Case.LOWER));
        add(out, "UPPER WORDS", join(r, " ", Words.Case.UPPER), join(p, " ", Words.Case.UPPER));
        if (r.size() > 1) {
            for (int i = 0; i < r.size(); i++) {
                var rw = r.get(i).toLowerCase(Locale.ROOT);
                if (rw.length() < 4 || commonWord.test(rw) || commonWord.test(p.get(i).toLowerCase(Locale.ROOT))) continue;
                add(out, "word part", Words.capitalize(r.get(i)), Words.capitalize(p.get(i)));
                add(out, "word part lower", rw, p.get(i).toLowerCase(Locale.ROOT));
                add(out, "word part UPPER", rw.toUpperCase(Locale.ROOT), p.get(i).toUpperCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(LinkedHashMap<String, Pair> out, String style, String real, String placeholder) {
        out.putIfAbsent(real, new Pair(style, real, placeholder));
    }

    private static String join(List<String> words, String sep, Words.Case c) {
        return String.join(sep, words.stream().map(w -> Words.apply(c, w)).toList());
    }

    private static String camel(List<String> words) {
        var sb = new StringBuilder(words.getFirst().toLowerCase(Locale.ROOT));
        for (int i = 1; i < words.size(); i++) sb.append(Words.capitalize(words.get(i)));
        return sb.toString();
    }
}
