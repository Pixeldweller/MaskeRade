package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.util.Globs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * How a mirror (or a plain export) is made.
 *
 * @param excludeGlobs     in addition to {@link Globs#DEFAULT_EXCLUDES}
 * @param copyBinaries     copy all binary files unchanged (default: none, they are only listed)
 * @param releasedBinaries original relative paths of binary files released one by one
 * @param denyReadPaths    absolute paths the agent in the mirror must not read (original, plugin config …)
 * @param compact          leave out everything not needed to understand the project: hidden files and folders
 *                         (except .gitignore) and {@link Globs#COMPACT_EXCLUDES} — a small mirror for exports
 * @param writeGuards      write .claude/settings.json and .claude/CLAUDE.md for the agent into the mirror
 * @param initGit          give the copy a git repository with a "baseline" commit (needed for "Unveil Changes…";
 *                         a plain export goes without — compare it later with "Compare Folders")
 * @param maxFileSize      files larger than this many bytes are left out and listed; 0 = no limit
 */
public record MirrorOptions(List<String> excludeGlobs, boolean copyBinaries, Set<String> releasedBinaries,
                            List<Path> denyReadPaths, boolean compact, boolean writeGuards, boolean initGit,
                            long maxFileSize) {

    public static MirrorOptions defaults() {
        return new MirrorOptions(List.of(), false, Set.of(), List.of(), false, true, true, 0);
    }

    /** Globs for creating the mirror (compact if chosen). */
    public Globs globs() {
        return Globs.withDefaults(excludeGlobs, compact);
    }

    /**
     * Globs for walking the mirror when unveiling — never compact, so that hidden files the model creates
     * (a new .github workflow, say) are found as well.
     */
    public Globs unveilGlobs() {
        return Globs.withDefaults(excludeGlobs, false);
    }

    public boolean tooLarge(long size) {
        return maxFileSize > 0 && size > maxFileSize;
    }

    public MirrorOptions withDenyReadPaths(List<Path> paths) {
        var all = new ArrayList<>(denyReadPaths);
        all.addAll(paths);
        return new MirrorOptions(excludeGlobs, copyBinaries, releasedBinaries, all, compact, writeGuards, initGit, maxFileSize);
    }

    public MirrorOptions withCompact(boolean compact) {
        return new MirrorOptions(excludeGlobs, copyBinaries, releasedBinaries, denyReadPaths, compact, writeGuards, initGit, maxFileSize);
    }

    public MirrorOptions withGuards(boolean writeGuards) {
        return new MirrorOptions(excludeGlobs, copyBinaries, releasedBinaries, denyReadPaths, compact, writeGuards, initGit, maxFileSize);
    }

    public MirrorOptions withGit(boolean initGit) {
        return new MirrorOptions(excludeGlobs, copyBinaries, releasedBinaries, denyReadPaths, compact, writeGuards, initGit, maxFileSize);
    }

    public MirrorOptions withMaxFileSize(long maxFileSize) {
        return new MirrorOptions(excludeGlobs, copyBinaries, releasedBinaries, denyReadPaths, compact, writeGuards, initGit, maxFileSize);
    }
}
