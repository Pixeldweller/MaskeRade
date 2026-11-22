package com.pixeldweller.maskerade.model;

import com.pixeldweller.maskerade.util.Words;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of the veil list: real term → placeholder. A plain bean (public fields, no-arg constructor) so that
 * IntelliJ's XML serializer can store it.
 */
public final class Term {

    /** Letters, optionally separated by single space / _ / - / . : such terms get all spellings derived. */
    private static final Pattern WORD_FORM = Pattern.compile("\\p{L}+(?:[ _.\\-]?\\p{L}+)*");

    public String real = "";
    public String placeholder = "";
    public TermKind kind = TermKind.OTHER;
    public boolean active = true;
    public String note = "";

    public Term() {}

    public Term(String real, String placeholder, TermKind kind) {
        this.real = real;
        this.placeholder = placeholder;
        this.kind = kind;
    }

    public static Term of(String real, String placeholder, TermKind kind) {
        return new Term(real, placeholder, kind);
    }

    public Term copy() {
        var t = new Term(real, placeholder, kind);
        t.active = active;
        t.note = note;
        return t;
    }

    /** Replaced literally (mail, host, secret, or anything with digits / symbols) instead of word by word. */
    public boolean isLiteral() {
        return kind.literal() || !WORD_FORM.matcher(real.trim()).matches();
    }

    public List<String> realWords() {
        return Words.letterParts(real);
    }

    public List<String> placeholderWords() {
        return Words.letterParts(placeholder);
    }

    /** Identity of the term: "Falkenrath Logistik", "FalkenrathLogistik" and "falkenrath_logistik" are the same. */
    public String key() {
        return isLiteral() ? real.trim().toLowerCase(java.util.Locale.ROOT) : Words.key(real);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Term t && Objects.equals(real, t.real) && Objects.equals(placeholder, t.placeholder)
                && kind == t.kind && active == t.active && Objects.equals(note, t.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(real, placeholder, kind, active, note);
    }

    @Override
    public String toString() {
        return real + " = " + placeholder + " (" + kind + (active ? "" : ", inactive") + ")";
    }
}
