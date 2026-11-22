package com.pixeldweller.maskerade.model;

import java.util.List;

/**
 * A term the scanner suggests to veil.
 *
 * @param term      as it should go into the veil list (most frequent spelling)
 * @param key       identity used for "ignore" ({@link Term#key()})
 * @param secret    a secret: does not belong into the repository; veiled with a dummy of the same length
 * @param contract  occurs where the project talks to the outside (JSON field, REST path, table, coordinates…)
 */
public record Candidate(String term, String key, TermKind kind, int score, int count, int files,
                        List<String> reasons, List<Location> locations, boolean secret, boolean contract,
                        boolean testOnly) {

    public record Location(String path, int line, String snippet) {}

    public String reasonText() {
        return String.join(", ", reasons);
    }
}
