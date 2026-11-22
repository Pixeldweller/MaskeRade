package com.pixeldweller.maskerade.mirror;

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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Differences between any two project folders — original against mirror, two originals, or the original against
 * a veiled copy that comes back from somewhere else. Each side can be unveiled first (contents and paths), so the
 * result always speaks real names; {@link UnifiedDiff} turns it into a patch.
 */
public final class FolderDiff {

    public enum Kind { MODIFIED, ADDED, DELETED }

    /** One differing file; {@code left}/{@code right} are the (unveiled) bytes, null where the file is missing. */
    public record Entry(Kind kind, String path, byte[] left, byte[] right, boolean binary, List<String> warnings) {}

    public record Result(List<Entry> entries, List<String> warnings, int leftFiles, int rightFiles, int ignoredMissing) {}

    /** One side of the comparison. {@code unveil} = this folder is veiled, translate it back first. */
    public record Side(Path root, VeilEngine unveil) {
        public static Side plain(Path root) {
            return new Side(root, null);
        }
    }

    private final Side left;
    private final Side right;
    private final Globs globs;
    private final ResidueFinder realTerms;
    private boolean ignoreMissingOnRight;
    private BooleanSupplier cancelled = () -> false;

    /** @param realTerms finds real terms in a veiled side (a leak), may be null */
    public FolderDiff(Side left, Side right, Globs globs, ResidueFinder realTerms) {
        this.left = left;
        this.right = right;
        this.globs = globs;
        this.realTerms = realTerms;
    }

    /** Exports leave files out (binary files, compact mirror): don't report those as deleted. */
    public FolderDiff ignoreMissingOnRight(boolean ignore) {
        this.ignoreMissingOnRight = ignore;
        return this;
    }

    public FolderDiff cancelledBy(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        return this;
    }

    public Result compare() throws IOException {
        var warnings = new ArrayList<String>();
        var l = index(left, warnings);
        var r = index(right, warnings);
        var entries = new ArrayList<Entry>();
        int ignored = 0;
        var paths = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        paths.addAll(l.keySet());
        paths.addAll(r.keySet());
        for (var path : paths) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var lp = l.get(path);
            var rp = r.get(path);
            if (lp != null && rp == null && ignoreMissingOnRight) {
                ignored++;
                continue;
            }
            byte[] lb = lp == null ? null : read(left, lp);
            byte[] rb = rp == null ? null : read(right, rp);
            if (lb != null && rb != null && Arrays.equals(lb, rb)) continue;
            var fileWarnings = new ArrayList<String>();
            boolean binary = (lb != null && isBinaryForDiff(path, lb)) || (rb != null && isBinaryForDiff(path, rb));
            if (!binary) {
                lb = lb == null ? null : unveil(left, lb, fileWarnings, "left");
                rb = rb == null ? null : unveil(right, rb, fileWarnings, "right");
                if (lb != null && rb != null && Arrays.equals(lb, rb)) continue;
            }
            var kind = lb == null ? Kind.ADDED : rb == null ? Kind.DELETED : Kind.MODIFIED;
            entries.add(new Entry(kind, path, lb, rb, binary, fileWarnings));
        }
        return new Result(entries, warnings, l.size(), r.size(), ignored);
    }

    /** (unveiled) relative path → relative path on disk */
    private Map<String, String> index(Side side, List<String> warnings) throws IOException {
        var out = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (var rel : MirrorBuilder.listFiles(side.root(), globs)) {
            var mapped = side.unveil() == null ? rel : side.unveil().unveilPath(rel);
            var previous = out.putIfAbsent(mapped, rel);
            if (previous != null) warnings.add(side.root() + ": " + rel + " and " + previous + " both map to " + mapped);
            if (side.unveil() != null && realTerms != null && !realTerms.findInText(rel).isEmpty()) {
                warnings.add(side.root() + ": the veiled path " + rel + " contains a real term");
            }
        }
        return out;
    }

    private static byte[] read(Side side, String rel) throws IOException {
        return Files.readAllBytes(side.root().resolve(rel));
    }

    private byte[] unveil(Side side, byte[] bytes, List<String> warnings, String which) {
        if (side.unveil() == null) return bytes;
        var decoded = TextCodec.decode(bytes);
        if (realTerms != null) {
            for (var hit : realTerms.findInText(decoded.text())) {
                warnings.add("real term in the veiled " + which + " folder: \"" + hit.term().real + "\" [" + hit.form() + "] line "
                        + hit.line());
            }
        }
        try {
            return decoded.encode(side.unveil().unveil(decoded.text()));
        } catch (CharacterCodingException e) {
            warnings.add("could not unveil the " + which + " side in " + decoded.charset() + " — compared as it is");
            return bytes;
        }
    }

    /** Binary, or UTF-16 text (a line-based patch cannot carry it). */
    static boolean isBinaryForDiff(String path, byte[] bytes) {
        if (TextCodec.isBinary(path, bytes)) return true;
        return bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF));
    }
}
