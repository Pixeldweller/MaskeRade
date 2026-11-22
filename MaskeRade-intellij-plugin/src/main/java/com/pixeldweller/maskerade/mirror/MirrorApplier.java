package com.pixeldweller.maskerade.mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies unveiled changes to the original with plain file I/O — used by tests and as the reference behaviour.
 * The IDE applies the same changes through the VFS inside one undoable command (see {@code ui.UnveilDialog}).
 * Conflicting changes are never applied here; they need a merge.
 */
public final class MirrorApplier {

    private MirrorApplier() {}

    public record Outcome(List<FileChange> applied, List<FileChange> skipped) {}

    public static Outcome apply(Path original, List<FileChange> changes) throws IOException {
        var applied = new ArrayList<FileChange>();
        var skipped = new ArrayList<FileChange>();
        for (var c : changes) {
            if (c.conflict()) {
                skipped.add(c);
                continue;
            }
            switch (c.kind) {
                case MODIFIED, ADDED -> write(original.resolve(c.path), c.result);
                case DELETED -> Files.deleteIfExists(original.resolve(c.path));
                case RENAMED -> {
                    write(original.resolve(c.path), c.result);
                    if (!c.path.equals(c.oldPath)) Files.deleteIfExists(original.resolve(c.oldPath));
                }
            }
            applied.add(c);
        }
        return new Outcome(applied, skipped);
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }
}
