package com.pixeldweller.maskerade.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Built-in everyday English/German words and programming vocabulary (resource), plus user exceptions. */
public final class CommonWords implements Predicate<String> {

    private static final String RESOURCE = "/maskerade/common-words.txt";
    private static volatile Set<String> builtIn;

    private final Set<String> extra;

    private CommonWords(Set<String> extra) {
        this.extra = extra;
    }

    public static CommonWords builtIn() {
        return new CommonWords(Set.of());
    }

    public static CommonWords with(Collection<String> exceptions) {
        var set = new HashSet<String>();
        for (var e : exceptions) {
            for (var w : e.split("[\\s,;]+")) {
                if (!w.isBlank()) set.add(w.toLowerCase(Locale.ROOT));
            }
        }
        return new CommonWords(set);
    }

    @Override
    public boolean test(String word) {
        var w = word.toLowerCase(Locale.ROOT);
        if (extra.contains(w) || words().contains(w)) return true;
        // simple plural / genitive forms: "orders", "customers", "tours"
        if (w.length() > 4 && w.endsWith("s") && words().contains(w.substring(0, w.length() - 1))) return true;
        return w.length() > 5 && w.endsWith("es") && words().contains(w.substring(0, w.length() - 2));
    }

    public static Set<String> words() {
        var w = builtIn;
        if (w == null) {
            synchronized (CommonWords.class) {
                if (builtIn == null) builtIn = load();
                w = builtIn;
            }
        }
        return w;
    }

    private static Set<String> load() {
        var set = new HashSet<String>();
        try (var in = CommonWords.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " missing");
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                var hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                for (var w : line.trim().split("\\s+")) {
                    if (!w.isEmpty()) set.add(w.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Set.copyOf(set);
    }
}
