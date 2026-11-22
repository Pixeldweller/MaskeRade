package com.pixeldweller.maskerade;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.scan.CommonWords;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.veil.ResidueFinder;
import com.pixeldweller.maskerade.veil.VeilEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The sample project next to the plugin and a veil list for it. */
public final class SampleFixture {

    private SampleFixture() {}

    public static Path sampleRoot() {
        var configured = System.getProperty("maskerade.sample");
        var root = configured != null ? Path.of(configured) : Path.of("..", "MaskeRade-sample");
        if (!Files.isDirectory(root)) throw new IllegalStateException("Sample project not found: " + root.toAbsolutePath());
        return root.toAbsolutePath().normalize();
    }

    /** Terms as a user would add them after a scan of the sample. */
    public static List<Term> terms() {
        return List.of(
                Term.of("Falkenrath Logistik", "Seewald Spedition", TermKind.COMPANY),
                Term.of("Kranich", "Reiher", TermKind.PROJECT),
                Term.of("Jorinde Wessling", "Maren Kruse", TermKind.PERSON),
                Term.of("jwessling", "mkruse", TermKind.USERNAME),
                Term.of("Brunhild Osterkamp", "Gesa Harms", TermKind.CUSTOMER),
                Term.of("j.wessling@falkenrath-logistik.de", "m.kruse@example.org", TermKind.MAIL),
                Term.of("falkenrath-logistik.de", "seewald.example", TermKind.HOST),
                Term.of("falkenrath.lan", "basalt.example", TermKind.HOST),
                Term.of("10.20.30.40", "192.0.2.17", TermKind.HOST),
                Term.of("FLK-LIVE-0000-DUMMY-DO-NOT-USE", "QZV-HJRT-4821-ABCDE-XY-ZQW-RTS", TermKind.SECRET));
    }

    public static VeilEngine engine() {
        return VeilEngine.of(terms(), CommonWords.builtIn());
    }

    public static ResidueFinder residueFinder() {
        return new ResidueFinder(terms(), CommonWords.builtIn());
    }

    /** '/'-separated relative paths of the sample's files (build output etc. excluded). */
    public static List<String> sampleFiles() {
        var root = sampleRoot();
        var globs = Globs.withDefaults(List.of());
        var out = new ArrayList<String>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && globs.excludes(rel(root, dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!globs.excludes(rel(root, file))) out.add(rel(root, file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(null);
        return out;
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    /** Copies the sample (without build output) into a fresh folder. */
    public static Path copySample(Path target) throws IOException {
        var root = sampleRoot();
        for (var rel : sampleFiles()) {
            var to = target.resolve(rel);
            Files.createDirectories(to.getParent());
            Files.copy(root.resolve(rel), to);
        }
        return target;
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.delete(p);
            }
        }
    }
}
