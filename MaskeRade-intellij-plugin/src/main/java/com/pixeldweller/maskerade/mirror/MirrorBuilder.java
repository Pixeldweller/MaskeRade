package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.scan.Contracts;
import com.pixeldweller.maskerade.scan.SourceSplitter;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.TextCodec;
import com.pixeldweller.maskerade.veil.ResidueFinder;
import com.pixeldweller.maskerade.veil.VeilEngine;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creates the pseudonymized mirror of a project: contents and paths veiled, every file checked for
 * {@code unveil(veil(x)) == x} byte for byte before it is written, binary files left out, agent guards written,
 * a fresh git repository with a "baseline" commit, and finally a residue search over the whole mirror.
 * The original is only read.
 */
public final class MirrorBuilder {

    /** Marker inside the mirror's .git: only such folders are ever cleared. */
    public static final String MARKER = "maskerade-mirror";
    /** Files written by MaskeRade into the mirror; ignored when unveiling. */
    public static final String GUARD_DIR = ".claude";

    private final Path original;
    private final Path mirror;
    private final VeilEngine engine;
    private final ResidueFinder residueFinder;
    private final MirrorOptions options;
    private BooleanSupplier cancelled = () -> false;
    private Consumer<String> progress = s -> {};

    public MirrorBuilder(Path original, Path mirror, VeilEngine engine, ResidueFinder residueFinder, MirrorOptions options) {
        this.original = original.toAbsolutePath().normalize();
        this.mirror = mirror.toAbsolutePath().normalize();
        this.engine = engine;
        this.residueFinder = residueFinder;
        this.options = options;
    }

    public MirrorBuilder cancelledBy(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        return this;
    }

    public MirrorBuilder progressTo(Consumer<String> progress) {
        this.progress = progress;
        return this;
    }

    public MirrorReport build() throws IOException {
        long started = System.currentTimeMillis();
        var report = new MirrorReport();
        report.mirrorPath = mirror.toString();
        checkLocations();
        progress.accept("Preparing " + mirror);
        prepareDirectory(mirror);

        var globs = options.globs();
        var unreadable = new ArrayList<String>();
        var files = listFiles(original, globs, unreadable);
        unreadable.forEach(u -> report.warnings.add("not readable, left out: " + u));
        if (options.compact()) report.compactSkipped = listFiles(original, options.unveilGlobs()).size() - files.size();
        var veiledPaths = new HashMap<String, String>();
        int done = 0;
        for (var rel : files) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            progress.accept("Veiling " + (++done) + "/" + files.size());
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(original.resolve(rel));
            } catch (IOException e) {
                // locked by another process, no permission, cloud placeholder … — leave it out, don't abort
                report.warnings.add("not readable, left out: " + rel + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                continue;
            }
            var veiledPath = engine.veilPath(rel);
            if (!engine.unveilPath(veiledPath).equals(rel)) {
                report.irreversible.add(new MirrorReport.Irreversible(rel, "path does not unveil to itself: " + veiledPath));
                continue;
            }
            var previous = veiledPaths.putIfAbsent(veiledPath.toLowerCase(Locale.ROOT), rel);
            if (previous != null) {
                report.irreversible.add(new MirrorReport.Irreversible(rel, "veiled path " + veiledPath + " clashes with " + previous));
                continue;
            }
            if (!veiledPath.equals(rel)) report.renamedPaths++;

            if (TextCodec.isBinary(rel, bytes)) {
                var hits = residueFinder.findInBytes(bytes);
                boolean copy = options.copyBinaries() || options.releasedBinaries().contains(rel);
                if (copy) write(mirror.resolve(veiledPath), bytes);
                report.binaries.add(new MirrorReport.BinaryFile(rel, bytes.length, copy, hits));
                continue;
            }
            var veiled = veilText(rel, bytes, report);
            if (veiled == null) continue;
            write(mirror.resolve(veiledPath), veiled);
            report.textFiles++;
        }

        if (options.writeGuards()) writeGuards(report);
        if (options.initGit()) {
            progress.accept("Creating the baseline commit");
            new Git(mirror).initWithBaseline();
        }
        if (!residueFinder.isEmpty()) {
            progress.accept("Searching the mirror for residues");
            findResidues(report);
        }
        report.millis = System.currentTimeMillis() - started;
        return report;
    }

    /** Veils one text file; null (and an entry in the report) when it is not reversible byte for byte. */
    private byte[] veilText(String rel, byte[] bytes, MirrorReport report) {
        var decoded = TextCodec.decode(bytes);
        var text = decoded.text();
        var contracts = new ArrayList<MirrorReport.Contract>();
        var segments = SourceSplitter.split(rel, text);
        int[] count = {0};
        var veiled = engine.veil(text, r -> {
            count[0]++;
            if (SourceSplitter.inComment(segments, r.start())) return; // comments do not change behaviour
            int lineStart = text.lastIndexOf('\n', r.start() - 1) + 1;
            int lineEnd = text.indexOf('\n', r.start());
            var line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
            var reason = Contracts.reasonFor(rel, line, r.start() - lineStart);
            if (reason != null) {
                contracts.add(new MirrorReport.Contract(rel, TextCodec.lineOf(text, r.start()), r.from(), reason, isTest(rel)));
            }
        });
        var unveiled = engine.unveil(veiled);
        if (!unveiled.equals(text)) {
            report.irreversible.add(new MirrorReport.Irreversible(rel, explainDifference(text, unveiled)));
            return null;
        }
        byte[] out;
        byte[] back;
        try {
            out = decoded.encode(veiled);
            back = decoded.encode(unveiled);
        } catch (CharacterCodingException e) {
            report.irreversible.add(new MirrorReport.Irreversible(rel, "a placeholder cannot be written in " + decoded.charset()));
            return null;
        }
        if (!Arrays.equals(back, bytes)) {
            report.irreversible.add(new MirrorReport.Irreversible(rel, "encoding does not round-trip (" + decoded.charset() + ")"));
            return null;
        }
        report.replacements += count[0];
        // one entry per line and reason is enough
        var seen = new HashSet<String>();
        for (var c : contracts) {
            if (seen.add(c.line() + "|" + c.reason())) report.contracts.add(c);
        }
        for (var hit : residueFinder.findEncoded(text)) report.encoded.add(new MirrorReport.Encoded(rel, hit));
        return out;
    }

    /** Typical cause: the placeholder already occurs in the original (collision). */
    private static String explainDifference(String original, String roundTrip) {
        int i = 0;
        while (i < original.length() && i < roundTrip.length() && original.charAt(i) == roundTrip.charAt(i)) i++;
        int line = TextCodec.lineOf(original, i);
        var was = TextCodec.snippet(original, i, Math.min(original.length(), i + 1));
        var became = TextCodec.snippet(roundTrip, i, Math.min(roundTrip.length(), i + 1));
        return "line " + line + " does not unveil to itself (collision? a placeholder already occurs in the original): \""
                + was + "\" would become \"" + became + "\"";
    }

    private static boolean isTest(String rel) {
        var lower = rel.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.startsWith("test/") || lower.contains("/tests/");
    }

    // ── guards for the agent ────────────────────────────────────────────────────

    private void writeGuards(MirrorReport report) throws IOException {
        var paths = new ArrayList<Path>();
        paths.add(original);
        paths.addAll(options.denyReadPaths());
        var deny = denyRules(paths, mirror, Path.of(System.getProperty("user.home")));
        var json = new StringBuilder("{\n  \"permissions\": {\n    \"deny\": [\n");
        for (int i = 0; i < deny.size(); i++) {
            json.append("      \"").append(jsonEscape(deny.get(i))).append('"').append(i < deny.size() - 1 ? ",\n" : "\n");
        }
        json.append("    ]\n  }\n}\n");
        var claudeMd = """
                # Working rules

                - Work only inside this folder. Do not read, list or search files outside of it.
                - Names in this project are placeholders and intentional — keep them as they are and use them
                  consistently in new code, comments and file names.
                """;
        var settings = mirror.resolve(GUARD_DIR).resolve("settings.json");
        var md = mirror.resolve(GUARD_DIR).resolve("CLAUDE.md");
        write(settings, json.toString().getBytes(StandardCharsets.UTF_8));
        write(md, claudeMd.getBytes(StandardCharsets.UTF_8));
        if (!residueFinder.findInText(json.toString()).isEmpty()) {
            report.warnings.add(GUARD_DIR + "/settings.json names a real term in a denied path. Claude Code does not send "
                    + "deny rules to the model, but the file itself is readable inside the mirror.");
        }
    }

    /**
     * Deny rules for the agent. When everything to protect lies in the home folder and the mirror does not
     * (default: C:\veiled), "~/**" covers it without naming the project or the user in a file the agent can read;
     * otherwise the paths are listed one by one.
     */
    static List<String> denyRules(List<Path> protect, Path mirror, Path home) {
        var h = home.toAbsolutePath().normalize();
        var m = mirror.toAbsolutePath().normalize();
        boolean homeCoversAll = !m.startsWith(h) && protect.stream().allMatch(p -> p.toAbsolutePath().normalize().startsWith(h));
        var rules = new ArrayList<String>();
        if (homeCoversAll) {
            rules.add("Read(~/**)");
            rules.add("Edit(~/**)");
            return rules;
        }
        for (var p : protect) {
            var rule = claudePath(p);
            rules.add("Read(" + rule + "/**)");
            rules.add("Edit(" + rule + "/**)");
        }
        return rules;
    }

    /** Claude Code permission paths: "//c/Users/x" on Windows, "//home/x" elsewhere. */
    static String claudePath(Path p) {
        var s = p.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (s.length() >= 2 && s.charAt(1) == ':') s = "/" + Character.toLowerCase(s.charAt(0)) + s.substring(2);
        return "/" + s;
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── residues ────────────────────────────────────────────────────────────────

    private void findResidues(MirrorReport report) throws IOException {
        for (var rel : listFiles(mirror, new Globs(List.of(".git")))) {
            for (var hit : residueFinder.findInText(rel)) {
                report.residues.add(new MirrorReport.Residue(rel, new ResidueFinder.Hit(hit.term(), hit.needle(), hit.form(), 0,
                        "(path) " + hit.snippet())));
            }
            var bytes = Files.readAllBytes(mirror.resolve(rel));
            var hits = TextCodec.isBinary(rel, bytes) ? residueFinder.findInBytes(bytes)
                    : residueFinder.findInText(TextCodec.decode(bytes).text());
            for (var hit : hits) report.residues.add(new MirrorReport.Residue(rel, hit));
        }
    }

    // ── files ───────────────────────────────────────────────────────────────────

    private void checkLocations() throws IOException {
        if (!Files.isDirectory(original)) throw new IOException("Not a folder: " + original);
        if (mirror.startsWith(original)) throw new IOException("The mirror must not be inside the project: " + mirror);
        if (original.startsWith(mirror)) throw new IOException("The project must not be inside the mirror folder: " + mirror);
    }

    /** Clears a previous mirror; refuses folders that are neither empty nor a MaskeRade mirror. */
    static void prepareDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            boolean empty;
            try (var s = Files.list(dir)) {
                empty = s.findAny().isEmpty();
            }
            if (!empty && !Files.exists(dir.resolve(".git").resolve(MARKER))) {
                throw new IOException("Refusing to overwrite " + dir + ": it is not empty and not a MaskeRade mirror");
            }
            deleteContents(dir);
        }
        Files.createDirectories(dir);
    }

    public static void deleteMirror(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        if (!Files.exists(dir.resolve(".git").resolve(MARKER))) {
            throw new IOException("Refusing to delete " + dir + ": not a MaskeRade mirror");
        }
        deleteContents(dir);
        Files.delete(dir);
    }

    private static void deleteContents(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (p.equals(dir)) continue;
                var dos = Files.getFileAttributeView(p, DosFileAttributeView.class);
                if (dos != null) dos.setReadOnly(false); // git objects are read-only on Windows
                Files.delete(p);
            }
        }
    }

    /** '/'-separated relative paths of all files that are not excluded, sorted. */
    static List<String> listFiles(Path root, Globs globs) throws IOException {
        return listFiles(root, globs, new ArrayList<>());
    }

    /** @param unreadable collects files / folders that could not be visited (they are skipped, not fatal) */
    static List<String> listFiles(Path root, Globs globs, List<String> unreadable) throws IOException {
        var out = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                if (file.equals(root)) return FileVisitResult.TERMINATE;
                unreadable.add(rel(root, file) + " (" + e.getClass().getSimpleName() + ")");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                return globs.excludes(rel(root, dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !globs.excludes(rel(root, file))) out.add(rel(root, file));
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(null);
        return out;
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }
}
