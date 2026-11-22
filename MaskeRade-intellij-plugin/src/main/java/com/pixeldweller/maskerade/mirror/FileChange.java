package com.pixeldweller.maskerade.mirror;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One change the model made in the mirror, already translated back to the original's names.
 *
 * <p>{@code base} is the original as it was when the baseline was taken, {@code current} the original now,
 * {@code result} what the model's version looks like with real names. {@code current != base} means the original
 * was changed in the meantime: a conflict that must be merged like a patch.
 */
public final class FileChange {

    public enum Kind { MODIFIED, ADDED, DELETED, RENAMED }

    public final Kind kind;
    public final String veiledPath;
    public final String veiledOldPath;
    public final String path;
    public final String oldPath;
    public final boolean binary;
    public final byte[] base;
    public final byte[] current;
    public final byte[] result;
    public final Charset charset;
    public final List<String> warnings = new ArrayList<>();

    public FileChange(Kind kind, String veiledPath, String veiledOldPath, String path, String oldPath, boolean binary,
                      byte[] base, byte[] current, byte[] result, Charset charset) {
        this.kind = kind;
        this.veiledPath = veiledPath;
        this.veiledOldPath = veiledOldPath;
        this.path = path;
        this.oldPath = oldPath;
        this.binary = binary;
        this.base = base;
        this.current = current;
        this.result = result;
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    /** The original was changed (or the target already exists) since the baseline. */
    public boolean conflict() {
        return switch (kind) {
            case ADDED -> current != null;
            case MODIFIED, DELETED -> !Arrays.equals(base, current);
            case RENAMED -> !Arrays.equals(base, current);
        };
    }

    public String displayPath() {
        return kind == Kind.RENAMED ? oldPath + " → " + path : path;
    }

    public String text(byte[] bytes) {
        if (bytes == null) return "";
        return com.pixeldweller.maskerade.util.TextCodec.decode(bytes).text();
    }

    @Override
    public String toString() {
        return kind + " " + displayPath() + (conflict() ? " (conflict)" : "") + (warnings.isEmpty() ? "" : " " + warnings);
    }
}
