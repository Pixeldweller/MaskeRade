package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.util.Processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git for the mirror only. Runs isolated from the user's global/system git config (no identity, no hooks, no
 * signing key, no autocrlf) so that nothing personal ends up in the mirror's history and bytes stay exact.
 */
public final class Git {

    public static final String BASELINE_TAG = "maskerade-baseline";

    private static volatile Path emptyConfig;

    private final Path dir;

    public Git(Path dir) {
        this.dir = dir;
    }

    public static boolean available() {
        try {
            return Processes.run(Path.of(System.getProperty("user.dir")), List.of("git", "--version"), Map.of(), 30).ok();
        } catch (IOException e) {
            return false;
        }
    }

    public void initWithBaseline() throws IOException {
        run("-c", "init.defaultBranch=main", "init", "-q", "--template=");
        // a copied .gitattributes ("* text=auto", LFS filters) must not change the stored bytes
        var info = dir.resolve(".git/info");
        Files.createDirectories(info);
        Files.writeString(info.resolve("attributes"), "* -text -eol -filter -ident\n", StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve(".git/" + MirrorBuilder.MARKER), "MaskeRade mirror\n", StandardCharsets.US_ASCII);
        var config = new String[][]{
                {"user.name", "MaskeRade"}, {"user.email", "mirror@maskerade.invalid"},
                {"core.autocrlf", "false"}, {"core.safecrlf", "false"},
                {"core.filemode", "false"}, {"core.longpaths", "true"}, {"core.quotepath", "false"},
                {"commit.gpgsign", "false"}, {"tag.gpgsign", "false"}, {"gc.auto", "0"}};
        for (var kv : config) run("config", "--local", kv[0], kv[1]);
        commitBaseline();
    }

    /** Commits the whole working tree (ignored files too — they are part of the mirror) and moves the tag. */
    public void commitBaseline() throws IOException {
        run("add", "-A", "-f", ".");
        run("commit", "-q", "--allow-empty", "-m", "baseline");
        run("tag", "-f", BASELINE_TAG);
    }

    /** path → blob id of the baseline. */
    public Map<String, String> baselineBlobs() throws IOException {
        var out = new LinkedHashMap<String, String>();
        var result = run("ls-tree", "-r", "-z", BASELINE_TAG);
        for (var entry : new String(result.stdout(), StandardCharsets.UTF_8).split("\0")) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            var meta = entry.substring(0, tab).split(" ");
            if (!"blob".equals(meta[1])) continue;
            out.put(entry.substring(tab + 1), meta[2]);
        }
        return out;
    }

    public byte[] blob(String id) throws IOException {
        return run("cat-file", "blob", id).stdout();
    }

    public boolean hasBaseline() {
        try {
            return Files.isDirectory(dir.resolve(".git")) && run("rev-parse", "-q", "--verify", BASELINE_TAG).ok();
        } catch (IOException e) {
            return false;
        }
    }

    private Processes.Result run(String... args) throws IOException {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        var env = Map.of(
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", emptyConfig().toString(),
                "GIT_TERMINAL_PROMPT", "0",
                "LC_ALL", "C");
        var result = Processes.run(dir, command, env, 300);
        if (!result.ok() && !(args.length > 0 && args[0].equals("rev-parse"))) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + result.exitCode() + "): " + result.stderr().strip());
        }
        return result;
    }

    private static Path emptyConfig() throws IOException {
        var p = emptyConfig;
        if (p == null || !Files.exists(p)) {
            synchronized (Git.class) {
                p = Files.createTempFile("maskerade-gitconfig", ".txt");
                p.toFile().deleteOnExit();
                emptyConfig = p;
            }
        }
        return p;
    }
}
