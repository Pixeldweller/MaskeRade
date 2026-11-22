package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.SampleFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirror the sample → simulated model edits in the mirror → unveil → apply → the expected change in the original. */
class EndToEndTest {

    private Path work;
    private Path original;
    private Path mirror;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Git.available(), "git is not installed");
        work = Files.createTempDirectory("maskerade-e2e");
        original = SampleFixture.copySample(work.resolve("original"));
        mirror = work.resolve("veiled").resolve("basalt-123");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (work != null) SampleFixture.deleteRecursively(work);
    }

    @Test
    void mirrorEditUnveilApply() throws Exception {
        var engine = SampleFixture.engine();
        var finder = SampleFixture.residueFinder();
        var report = new MirrorBuilder(original, mirror, engine, finder,
                MirrorOptions.defaults().withDenyReadPaths(List.of(work.resolve("config")))).build();

        // ── the mirror ──
        assertTrue(report.irreversible.isEmpty(), report.toText());
        var planner = mirror.resolve("src/main/java/de/seewald/reiher/tour/SeewaldTourPlanner.java");
        assertTrue(Files.exists(planner), "package directories and file names are veiled");
        assertFalse(Files.exists(mirror.resolve("src/main/java/de/falkenrath")));
        assertFalse(Files.exists(mirror.resolve("src/main/resources/logo.png")), "binary files are not copied");
        assertTrue(report.binaries.stream().anyMatch(b -> b.path().endsWith("logo.png") && !b.hits().isEmpty()));
        var props = Files.readString(mirror.resolve("src/main/resources/application.properties"));
        assertTrue(props.contains("seewald.api.key=QZV-HJRT-4821-ABCDE-XY-ZQW-RTS"), props);
        assertTrue(props.contains("jdbc:postgresql://db.basalt.example:5432/reiher"), props);
        assertTrue(Files.readString(mirror.resolve("src/main/java/de/seewald/reiher/export/ExporterFactory.java"))
                .contains("\"de.seewald.reiher.export.CsvExporter\""));
        assertTrue(Files.exists(mirror.resolve(".claude/settings.json")));
        assertTrue(Files.readString(mirror.resolve(".claude/settings.json")).contains("Read(//"));
        assertFalse(Files.exists(mirror.resolve(".idea")));
        // residues: the Base64 company name (and the ASCII banner is not detectable) — reported, nothing else
        assertTrue(report.residues.stream().anyMatch(r -> r.path().endsWith("CompanyNameEncodingTest.java")), report.toText());
        assertTrue(report.residues.stream().allMatch(r -> r.path().endsWith("CompanyNameEncodingTest.java")), report.toText());
        assertTrue(report.contracts.stream().anyMatch(c -> c.reason().equals("REST path")), report.toText());
        assertTrue(report.contracts.stream().anyMatch(c -> c.reason().equals("JSON/XML field name")));
        assertTrue(report.contracts.stream().anyMatch(c -> c.reason().equals("database table/column")));
        assertTrue(new UnveilCollector(original, mirror, engine, finder, MirrorOptions.defaults()).collect().isEmpty(),
                "a fresh mirror has no changes");

        // ── simulated model ──
        var plannerText = Files.readString(planner, StandardCharsets.UTF_8);
        Files.writeString(planner, plannerText.replace("static final double KILOMETRES_PER_STOP = 18.0;",
                "static final double KILOMETRES_PER_STOP = 21.5; // Seewald Spedition drives longer routes"));
        var helper = mirror.resolve("src/main/java/de/seewald/reiher/export/SeewaldExportHelper.java");
        Files.writeString(helper, """
                package de.seewald.reiher.export;

                /** Helps the Reiher export of Seewald Spedition. */
                public final class SeewaldExportHelper {
                    public static final String REIHER_EXPORT_DIR = "reiher-export";
                }
                """);
        Files.createDirectories(mirror.resolve("target/classes"));
        Files.writeString(mirror.resolve("target/classes/ignored.txt"), "build output");

        // ── unveil ──
        var changes = new UnveilCollector(original, mirror, engine, finder, MirrorOptions.defaults()).collect();
        assertEquals(2, changes.size(), changes.toString());
        var modified = changes.stream().filter(c -> c.kind == FileChange.Kind.MODIFIED).findFirst().orElseThrow();
        assertEquals("src/main/java/de/falkenrath/kranich/tour/FalkenrathTourPlanner.java", modified.path);
        assertFalse(modified.conflict());
        assertTrue(modified.warnings.isEmpty(), modified.warnings.toString());
        var added = changes.stream().filter(c -> c.kind == FileChange.Kind.ADDED).findFirst().orElseThrow();
        assertEquals("src/main/java/de/falkenrath/kranich/export/FalkenrathExportHelper.java", added.path);

        MirrorApplier.apply(original, changes);

        var expectedPlanner = Files.readString(SampleFixture.sampleRoot().resolve(modified.path))
                .replace("static final double KILOMETRES_PER_STOP = 18.0;",
                        "static final double KILOMETRES_PER_STOP = 21.5; // Falkenrath Logistik drives longer routes");
        assertEquals(expectedPlanner, Files.readString(original.resolve(modified.path)));
        assertEquals("""
                package de.falkenrath.kranich.export;

                /** Helps the Kranich export of Falkenrath Logistik. */
                public final class FalkenrathExportHelper {
                    public static final String KRANICH_EXPORT_DIR = "kranich-export";
                }
                """, Files.readString(original.resolve(added.path)));
    }

    @Test
    void leaksAndConflictsAreReported() throws Exception {
        var engine = SampleFixture.engine();
        var finder = SampleFixture.residueFinder();
        new MirrorBuilder(original, mirror, engine, finder, MirrorOptions.defaults()).build();

        // the model writes a real name, and the original changes meanwhile
        var mirrored = mirror.resolve("src/main/java/de/seewald/reiher/tour/Route.java");
        Files.writeString(mirrored, Files.readString(mirrored) + "// thanks to Jorinde Wessling\n");
        var originalFile = original.resolve("src/main/java/de/falkenrath/kranich/tour/Route.java");
        Files.writeString(originalFile, Files.readString(originalFile) + "// edited in the original\n");
        // and deletes a file
        Files.delete(mirror.resolve("src/main/java/de/seewald/reiher/weather/Wind.java"));

        var changes = new UnveilCollector(original, mirror, engine, finder, MirrorOptions.defaults()).collect();
        var route = changes.stream().filter(c -> c.path.endsWith("Route.java")).findFirst().orElseThrow();
        assertTrue(route.conflict());
        assertTrue(route.warnings.stream().anyMatch(w -> w.startsWith("LEAK")), route.warnings.toString());
        var wind = changes.stream().filter(c -> c.kind == FileChange.Kind.DELETED).findFirst().orElseThrow();
        assertEquals("src/main/java/de/falkenrath/kranich/weather/Wind.java", wind.path);

        var outcome = MirrorApplier.apply(original, changes);
        assertTrue(outcome.skipped().contains(route), "conflicts are never applied blindly");
        assertFalse(Files.exists(original.resolve(wind.path)));
    }

    @Test
    void guardsDoNotNameTheProjectWhenTheHomeFolderCoversIt() {
        var home = Path.of("C:/Users/someone");
        var protect = List.of(Path.of("C:/Users/someone/IdeaProjects/Kranich"), Path.of("C:/Users/someone/AppData/Roaming/JetBrains"));
        assertEquals(List.of("Read(~/**)", "Edit(~/**)"), MirrorBuilder.denyRules(protect, Path.of("C:/veiled/basalt-123"), home));
        // a mirror inside the home folder must stay readable: list the paths instead
        var explicit = MirrorBuilder.denyRules(protect, Path.of("C:/Users/someone/veiled/x"), home);
        assertTrue(explicit.contains("Read(//c/Users/someone/IdeaProjects/Kranich/**)"), explicit.toString());
    }

    @Test
    void contractsIgnoreDocsCommentsAndLocalUrls() {
        assertEquals(null, com.pixeldweller.maskerade.scan.Contracts.reasonFor("README.md", "@JsonProperty(\"falkenrathId\")", 15));
        assertEquals(null, com.pixeldweller.maskerade.scan.Contracts.reasonFor("a/B.java",
                "String FALKENRATH_API_URL = \"https://api.falkenrath-logistik.de/v1\";", 7));
        assertEquals("URL of a real server", com.pixeldweller.maskerade.scan.Contracts.reasonFor("a/B.java",
                "String FALKENRATH_API_URL = \"https://api.falkenrath-logistik.de/v1\";", 40));
        assertEquals(null, com.pixeldweller.maskerade.scan.Contracts.reasonFor("a/B.java",
                "String url = \"http://localhost:8080/api/kranich\";", 37));
    }

    @Test
    void refusesToOverwriteForeignFolders() throws Exception {
        var foreign = work.resolve("foreign");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("important.txt"), "keep me");
        var builder = new MirrorBuilder(original, foreign, SampleFixture.engine(), SampleFixture.residueFinder(), MirrorOptions.defaults());
        try {
            builder.build();
            throw new AssertionError("must refuse");
        } catch (java.io.IOException expected) {
            assertTrue(Files.exists(foreign.resolve("important.txt")));
        }
    }
}
