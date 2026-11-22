package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.util.Hashes;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.TextCodec;
import com.pixeldweller.maskerade.veil.ResidueFinder;
import com.pixeldweller.maskerade.veil.VeilEngine;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Finds what the model changed in the mirror (working tree ↔ baseline commit) and translates every change back:
 * contents and paths, new and renamed files included. Reports leaks (a real term in the model's output),
 * placeholders in spellings that do not map back, and conflicts with changes made to the original meanwhile.
 */
public final class UnveilCollector {

    private final Path original;
    private final Path mirror;
    private final VeilEngine engine;
    private final ResidueFinder realTerms;
    private final Globs globs;
    private BooleanSupplier cancelled = () -> false;

    public UnveilCollector(Path original, Path mirror, VeilEngine engine, ResidueFinder realTerms, MirrorOptions options) {
        this.original = original;
        this.mirror = mirror;
        this.engine = engine;
        this.realTerms = realTerms;
        this.globs = options.unveilGlobs();
    }

    public UnveilCollector cancelledBy(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        return this;
    }

    public List<FileChange> collect() throws IOException {
        var git = new Git(mirror);
        if (!git.hasBaseline()) throw new IOException("No mirror baseline in " + mirror);
        Map<String, String> baseline = git.baselineBlobs();
        baseline.keySet().removeIf(p -> p.startsWith(MirrorBuilder.GUARD_DIR + "/"));

        var current = new LinkedHashMap<String, String>();
        for (var rel : MirrorBuilder.listFiles(mirror, globs)) {
            current.put(rel, Hashes.gitBlobId(Files.readAllBytes(mirror.resolve(rel))));
        }

        var modified = new ArrayList<String>();
        var added = new ArrayList<String>();
        var deleted = new ArrayList<String>();
        for (var e : current.entrySet()) {
            var base = baseline.get(e.getKey());
            if (base == null) added.add(e.getKey());
            else if (!base.equals(e.getValue())) modified.add(e.getKey());
        }
        for (var p : baseline.keySet()) {
            // excluded paths (e.g. build output) are not "deleted" just because the walk skips them
            if (!current.containsKey(p) && !globs.excludes(p) && !Files.exists(mirror.resolve(p))) deleted.add(p);
        }

        var changes = new ArrayList<FileChange>();
        var renames = detectRenames(git, baseline, current, added, deleted);
        for (var r : renames.entrySet()) {
            changes.add(change(git, FileChange.Kind.RENAMED, r.getValue(), r.getKey(), baseline));
        }
        for (var p : modified) changes.add(change(git, FileChange.Kind.MODIFIED, p, p, baseline));
        for (var p : added) {
            if (!renames.containsValue(p)) changes.add(change(git, FileChange.Kind.ADDED, p, null, baseline));
        }
        for (var p : deleted) {
            if (!renames.containsKey(p)) changes.add(change(git, FileChange.Kind.DELETED, p, p, baseline));
        }
        changes.sort((a, b) -> a.path.compareToIgnoreCase(b.path));
        return changes;
    }

    /** old veiled path → new veiled path; same content or at least half of the lines in common. */
    private Map<String, String> detectRenames(Git git, Map<String, String> baseline, Map<String, String> current,
                                              List<String> added, List<String> deleted) throws IOException {
        var renames = new LinkedHashMap<String, String>();
        var free = new ArrayList<>(added);
        for (var d : deleted) {
            String best = null;
            double bestScore = 0.5;
            Set<String> oldLines = null;
            for (var a : free) {
                if (baseline.get(d).equals(current.get(a))) {
                    best = a;
                    break;
                }
                if (oldLines == null) oldLines = lines(git.blob(baseline.get(d)));
                var newLines = lines(Files.readAllBytes(mirror.resolve(a)));
                var union = new HashSet<>(oldLines);
                union.addAll(newLines);
                var common = new HashSet<>(oldLines);
                common.retainAll(newLines);
                double score = union.isEmpty() ? 0 : (double) common.size() / union.size();
                if (score > bestScore) {
                    bestScore = score;
                    best = a;
                }
            }
            if (best != null) {
                renames.put(d, best);
                free.remove(best);
            }
        }
        return renames;
    }

    private static Set<String> lines(byte[] bytes) {
        return new HashSet<>(List.of(TextCodec.decode(bytes).text().split("\\R")));
    }

    private FileChange change(Git git, FileChange.Kind kind, String veiledPath, String veiledOldPath,
                              Map<String, String> baseline) throws IOException {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        var path = engine.unveilPath(veiledPath);
        var oldPath = veiledOldPath == null ? null : engine.unveilPath(veiledOldPath);
        byte[] oldVeiled = veiledOldPath == null ? null : git.blob(baseline.get(veiledOldPath));
        byte[] newVeiled = kind == FileChange.Kind.DELETED ? null : Files.readAllBytes(mirror.resolve(veiledPath));
        var originalFile = original.resolve(kind == FileChange.Kind.ADDED ? path : oldPath);
        byte[] current = Files.isRegularFile(originalFile) ? Files.readAllBytes(originalFile) : null;
        if (kind == FileChange.Kind.RENAMED && Files.exists(original.resolve(path)) && !path.equalsIgnoreCase(oldPath)) {
            current = null; // target exists: reported below
        }

        boolean binary = (oldVeiled != null && TextCodec.isBinary(veiledPath, oldVeiled))
                || (newVeiled != null && TextCodec.isBinary(veiledPath, newVeiled));
        var warnings = new ArrayList<String>();
        byte[] base = null;
        byte[] result = null;
        java.nio.charset.Charset charset = null;
        if (binary) {
            base = oldVeiled;
            result = newVeiled;
            if (newVeiled != null) warnings.add("binary file — copied unchanged, check it yourself");
        } else {
            if (oldVeiled != null) {
                var d = TextCodec.decode(oldVeiled);
                base = encode(d, engine.unveil(d.text()), warnings);
            }
            if (newVeiled != null) {
                var d = TextCodec.decode(newVeiled);
                charset = d.charset();
                var unveiled = engine.unveil(d.text());
                result = encode(d, unveiled, warnings);
                checkModelOutput(d.text(), oldVeiled == null ? "" : TextCodec.decode(oldVeiled).text(), unveiled, warnings);
            }
        }
        for (var hit : realTerms.findInText(veiledPath)) {
            warnings.add("LEAK: the model's file name contains the real term \"" + hit.term().real + "\"");
        }
        var change = new FileChange(kind, veiledPath, veiledOldPath, path, oldPath, binary, base, current, result, charset);
        change.warnings.addAll(warnings.stream().distinct().toList());
        if (kind == FileChange.Kind.RENAMED && Files.exists(original.resolve(path)) && !path.equalsIgnoreCase(oldPath)) {
            change.warnings.add("target " + path + " already exists in the original");
        }
        return change;
    }

    private static byte[] encode(TextCodec.Decoded d, String text, List<String> warnings) {
        try {
            return d.encode(text);
        } catch (CharacterCodingException e) {
            warnings.add("cannot be written in " + d.charset() + " after unveiling");
            return d.text().getBytes(d.charset());
        }
    }

    /** Leaks, ambiguous placeholders and anything that does not map back uniquely, on the lines the model wrote. */
    private void checkModelOutput(String newVeiled, String oldVeiled, String unveiled, List<String> warnings) {
        var oldLines = new HashSet<>(List.of(oldVeiled.split("\\R", -1)));
        var newLines = newVeiled.split("\\R", -1);
        var written = new StringBuilder();
        for (var line : newLines) {
            if (!oldLines.contains(line)) written.append(line).append('\n');
        }
        for (var hit : realTerms.findInText(written.toString())) {
            warnings.add("LEAK: the model wrote the real term \"" + hit.term().real + "\" [" + hit.form() + "]: " + hit.snippet());
        }
        // placeholders the unveil could not map back (e.g. "rEIHER", "Reihers")
        var unveiledLines = unveiled.split("\\R", -1);
        var oldUnveiledCheck = new HashSet<>(oldLines);
        for (int i = 0; i < unveiledLines.length && i < newLines.length; i++) {
            if (oldUnveiledCheck.contains(newLines[i])) continue;
            var lower = unveiledLines[i].toLowerCase(Locale.ROOT);
            for (var needle : engine.placeholderNeedles()) {
                if (needle.length() >= 3 && lower.contains(needle)) {
                    warnings.add("line " + (i + 1) + ": placeholder \"" + needle + "\" in a spelling that does not map back — "
                            + "check: " + unveiledLines[i].strip());
                }
            }
        }
        // the unveiled text must veil back to exactly what the model wrote
        var again = engine.veil(unveiled);
        if (!again.equals(newVeiled)) {
            int i = 0;
            while (i < again.length() && i < newVeiled.length() && again.charAt(i) == newVeiled.charAt(i)) i++;
            warnings.add("line " + TextCodec.lineOf(newVeiled, i) + " does not map back uniquely (real term or ambiguous "
                    + "spelling in the model's output)");
        }
    }

    /** True when the original and the mirror agree byte for byte on all files of the baseline (nothing to do). */
    public static boolean unchanged(List<FileChange> changes) {
        return changes.isEmpty();
    }

    static boolean sameBytes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
