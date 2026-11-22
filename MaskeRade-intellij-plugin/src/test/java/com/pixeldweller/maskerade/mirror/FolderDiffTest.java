package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.SampleFixture;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.Processes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderDiffTest {

    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createTempDirectory("maskerade-diff");
    }

    @AfterEach
    void tearDown() throws Exception {
        SampleFixture.deleteRecursively(work);
    }

    @Test
    void writesAUnifiedDiff() {
        var e = new FolderDiff.Entry(FolderDiff.Kind.MODIFIED, "src/A.txt",
                "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8),
                "one\nTWO\nthree\nfour\nfive".getBytes(StandardCharsets.UTF_8), false, List.of());
        assertEquals("""
                diff --git a/src/A.txt b/src/A.txt
                --- a/src/A.txt
                +++ b/src/A.txt
                @@ -1,4 +1,5 @@
                 one
                -two
                +TWO
                 three
                 four
                +five
                \\ No newline at end of file
                """, UnifiedDiff.writeToString(List.of(e)));
    }

    @Test
    void patchAppliesWithGit() throws Exception {
        Assumptions.assumeTrue(Git.available(), "git is not installed");
        var left = work.resolve("left");
        var right = work.resolve("right");
        var many = new StringBuilder();
        var manyChanged = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            many.append("line ").append(i).append('\n');
            manyChanged.append(i % 7 == 0 ? "changed " + i : "line " + i).append('\n');
        }
        write(left, Map.of(
                "a/crlf.txt", "first\r\nsecond\r\nthird\r\n",
                "a/nonl.txt", "x\ny",
                "deleted.txt", "gone\n",
                "many.txt", many.toString(),
                "same.txt", "unchanged\n"));
        write(right, Map.of(
                "a/crlf.txt", "first\r\nSECOND\r\nthird\r\nfourth\r\n",
                "a/nonl.txt", "x\ny\nz\n",
                "new/added.txt", "brand new\n",
                "many.txt", manyChanged.toString(),
                "same.txt", "unchanged\n"));
        var result = new FolderDiff(FolderDiff.Side.plain(left), FolderDiff.Side.plain(right), Globs.withDefaults(List.of()), null).compare();
        assertEquals(5, result.entries().size(), result.entries().toString());

        // exact line endings: byte for byte with core.autocrlf=false
        var exact = work.resolve("exact.patch");
        Files.write(exact, UnifiedDiff.write(result.entries(), 3, true));
        var target = copy(left, work.resolve("t1"));
        var applied = gitApply(target, exact, false);
        assertTrue(applied.ok(), applied.stderr());
        for (var rel : List.of("a/crlf.txt", "a/nonl.txt", "new/added.txt", "many.txt", "same.txt")) {
            assertArrayEquals(Files.readAllBytes(right.resolve(rel)), Files.readAllBytes(target.resolve(rel)), rel);
        }
        assertFalse(Files.exists(target.resolve("deleted.txt")));

        // default (LF lines): what git produces and applies with core.autocrlf=true — the CRLF file stays CRLF
        var lf = work.resolve("lf.patch");
        Files.write(lf, UnifiedDiff.write(result.entries(), 3));
        assertFalse(Files.readString(lf, StandardCharsets.ISO_8859_1).contains("\r"));
        var target2 = copy(left, work.resolve("t2"));
        var applied2 = gitApply(target2, lf, true);
        assertTrue(applied2.ok(), applied2.stderr());
        assertArrayEquals(Files.readAllBytes(right.resolve("a/crlf.txt")), Files.readAllBytes(target2.resolve("a/crlf.txt")));
        assertFalse(Files.exists(target2.resolve("deleted.txt")));
    }

    private static Path copy(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (var p : walk.toList()) {
                var t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
        return to;
    }

    @Test
    void comparesTheOriginalWithAChangedVeiledCopy() throws Exception {
        Assumptions.assumeTrue(Git.available(), "git is not installed");
        var original = SampleFixture.copySample(work.resolve("original"));
        var veiled = work.resolve("export");
        var engine = SampleFixture.engine();
        var finder = SampleFixture.residueFinder();
        new MirrorBuilder(original, veiled, engine, finder, MirrorOptions.defaults().withCompact(true)).build();

        // somebody works on the veiled export (no git baseline needed)
        var route = veiled.resolve("src/main/java/de/seewald/reiher/tour/Route.java");
        Files.writeString(route, Files.readString(route).replace("public int size()",
                "/** Seewald Spedition counts stops. */\n    public int size()"));
        Files.writeString(veiled.resolve("src/main/java/de/seewald/reiher/tour/ReiherNote.java"),
                "package de.seewald.reiher.tour;\n\nclass ReiherNote {}\n");

        var result = new FolderDiff(FolderDiff.Side.plain(original), new FolderDiff.Side(veiled, engine),
                Globs.withDefaults(List.of()), finder).ignoreMissingOnRight(true).compare();
        var paths = result.entries().stream().map(FolderDiff.Entry::path).toList();
        assertEquals(List.of("src/main/java/de/falkenrath/kranich/tour/KranichNote.java",
                "src/main/java/de/falkenrath/kranich/tour/Route.java"), paths, result.toString());
        assertTrue(result.ignoredMissing() > 0, "logo.png and the compact-skipped files are missing on the right");

        var patch = work.resolve("unveiled.patch");
        Files.write(patch, UnifiedDiff.write(result.entries(), 3));
        var text = Files.readString(patch);
        assertTrue(text.contains("+    /** Falkenrath Logistik counts stops. */"), text);
        assertFalse(text.contains("Seewald"), text);
        var applied = gitApply(original, patch, false);
        assertTrue(applied.ok(), applied.stderr());
        assertTrue(Files.readString(original.resolve("src/main/java/de/falkenrath/kranich/tour/KranichNote.java"))
                .contains("package de.falkenrath.kranich.tour;"));
    }

    @Test
    void compactMirrorLeavesOutHiddenAndGeneratedFiles() throws Exception {
        Assumptions.assumeTrue(Git.available(), "git is not installed");
        var original = SampleFixture.copySample(work.resolve("original"));
        Files.writeString(original.resolve("package-lock.json"), "{}");
        Files.createDirectories(original.resolve(".github/workflows"));
        Files.writeString(original.resolve(".github/workflows/ci.yml"), "on: push\n");
        var mirror = work.resolve("compact");
        var report = new MirrorBuilder(original, mirror, SampleFixture.engine(), SampleFixture.residueFinder(),
                MirrorOptions.defaults().withCompact(true)).build();
        assertTrue(Files.exists(mirror.resolve(".gitignore")), ".gitignore stays");
        assertTrue(Files.exists(mirror.resolve(".claude/settings.json")), "the agent guards stay");
        assertFalse(Files.exists(mirror.resolve(".run")));
        assertFalse(Files.exists(mirror.resolve(".github")));
        assertFalse(Files.exists(mirror.resolve("package-lock.json")));
        assertEquals(4, report.compactSkipped, report.summary()); // 2 run configurations, the workflow, the lock file
        assertTrue(report.summary().contains("4 files left out (compact)"));
        // a hidden file the model creates in the mirror is still found when unveiling
        Files.writeString(mirror.resolve(".editorconfig"), "root = true\n");
        var changes = new UnveilCollector(original, mirror, SampleFixture.engine(), SampleFixture.residueFinder(),
                MirrorOptions.defaults().withCompact(true)).collect();
        assertEquals(List.of(".editorconfig"), changes.stream().map(c -> c.path).toList());
    }

    @Test
    void plainCompactExportChangesNoNameAndHasNoGitOrClaude() throws Exception {
        var original = SampleFixture.copySample(work.resolve("original"));
        var export = work.resolve("export");
        var none = List.<com.pixeldweller.maskerade.model.Term>of();
        var common = com.pixeldweller.maskerade.scan.CommonWords.builtIn();
        var report = new MirrorBuilder(original, export, com.pixeldweller.maskerade.veil.VeilEngine.of(none, common),
                new com.pixeldweller.maskerade.veil.ResidueFinder(none, common),
                MirrorOptions.defaults().withCompact(true).withGuards(false).withGit(false)).build();
        assertFalse(Files.exists(export.resolve(".git")));
        assertFalse(Files.exists(export.resolve(".claude")));
        assertFalse(Files.exists(export.resolve(".run")));
        assertTrue(Files.exists(export.resolve(".gitignore")));
        assertEquals(0, report.renamedPaths);
        assertEquals(0, report.replacements);
        // every exported file is byte-identical to the original
        var result = new FolderDiff(FolderDiff.Side.plain(original), FolderDiff.Side.plain(export), Globs.withDefaults(List.of()), null)
                .ignoreMissingOnRight(true).compare();
        assertTrue(result.entries().isEmpty(), result.entries().toString());
        assertEquals(3, result.ignoredMissing(), "2 run configurations + logo.png");
    }

    @Test
    void mirrorWithoutGuardsHasNoClaudeFolder() throws Exception {
        Assumptions.assumeTrue(Git.available(), "git is not installed");
        var original = SampleFixture.copySample(work.resolve("original"));
        var mirror = work.resolve("noguards");
        new MirrorBuilder(original, mirror, SampleFixture.engine(), SampleFixture.residueFinder(),
                MirrorOptions.defaults().withGuards(false)).build();
        assertFalse(Files.exists(mirror.resolve(".claude")));
        assertTrue(Files.exists(mirror.resolve(".git")));
    }

    @Test
    void hiddenAndGeneratedFilesAreRecognized() {
        for (var p : List.of(".idea/workspace.xml", ".claude/settings.local.json", "target/classes/A.class", "build/x",
                "web/package-lock.json", ".editorconfig", "src/.DS_Store", "app.min.js")) {
            assertTrue(Globs.isHiddenOrGenerated(p), p);
        }
        for (var p : List.of(".gitignore", "src/main/java/A.java", "pom.xml", "README.md")) {
            assertFalse(Globs.isHiddenOrGenerated(p), p);
        }
    }

    private static void write(Path root, Map<String, String> files) throws Exception {
        for (var e : files.entrySet()) {
            var p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** git apply outside a repository, with an explicit core.autocrlf (Git for Windows also reads ProgramData). */
    private Processes.Result gitApply(Path dir, Path patch, boolean autocrlf) throws Exception {
        var emptyConfig = work.resolve("empty.gitconfig");
        if (!Files.exists(emptyConfig)) Files.writeString(emptyConfig, "");
        var command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=" + autocrlf, "apply", "--whitespace=nowarn", patch.toString()));
        return Processes.run(dir, command, Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", emptyConfig.toString()), 60);
    }
}
