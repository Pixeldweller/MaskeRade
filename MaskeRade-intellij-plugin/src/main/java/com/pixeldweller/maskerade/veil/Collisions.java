package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.util.TextCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A placeholder that already occurs in the original cannot be told apart from a veiled term when unveiling —
 * the original word would come back as the real term. This finds such places.
 */
public final class Collisions {

    private Collisions() {}

    public record Collision(Term term, String path, int line, String found, String snippet) {
        @Override
        public String toString() {
            return path + ":" + line + "  \"" + found + "\" is the placeholder of \"" + term.real + "\"";
        }
    }

    /** Places in an original text that unveil would change — every one is a collision. */
    public static List<Collision> find(VeilEngine engine, String path, String originalText) {
        var out = new ArrayList<Collision>();
        engine.unveil(originalText, r -> out.add(new Collision(r.term(), path, TextCodec.lineOf(originalText, r.start()),
                r.from(), TextCodec.snippet(originalText, r.start(), r.end()))));
        return out;
    }

    /**
     * Whether a placeholder suggestion occurs in the given text in any spelling (case-insensitive, as a word part).
     * Used by the generator so that suggestions never collide in the first place.
     */
    public static boolean occursIn(String placeholder, String text) {
        if (placeholder.isBlank()) return false;
        var words = com.pixeldweller.maskerade.util.Words.letterParts(placeholder);
        String regex;
        if (words.isEmpty() || !placeholder.matches("[\\p{L} _.\\-]+")) {
            regex = Pattern.quote(placeholder.toLowerCase(Locale.ROOT));
        } else {
            var sb = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sb.append("[ _.\\-]?");
                sb.append(Pattern.quote(words.get(i).toLowerCase(Locale.ROOT)));
            }
            regex = sb.toString();
        }
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
}
