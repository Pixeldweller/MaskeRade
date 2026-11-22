package com.pixeldweller.maskerade.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs external commands (git, Maven, Gradle) without a shell and collects their output. */
public final class Processes {

    private Processes() {}

    public record Result(int exitCode, byte[] stdout, String stderr) {
        public String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    public static Result run(Path dir, List<String> command, Map<String, String> env, long timeoutSeconds) throws IOException {
        var pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().putAll(env);
        var process = pb.start();
        process.getOutputStream().close();
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var outPump = pump(process.getInputStream(), out);
        var errPump = pump(process.getErrorStream(), err);
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out after " + timeoutSeconds + " s: " + String.join(" ", command));
            }
            outPump.join();
            errPump.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted: " + String.join(" ", command), e);
        }
        return new Result(process.exitValue(), out.toByteArray(), err.toString(StandardCharsets.UTF_8));
    }

    public static Result run(Path dir, String... command) throws IOException {
        return run(dir, List.of(command), Map.of(), 120);
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        var t = new Thread(() -> {
            try (in) {
                in.transferTo(sink);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "MaskeRade process pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Last lines of a (long) build log. */
    public static String tail(String text, int lines) {
        var all = text.split("\\R");
        var from = Math.max(0, all.length - lines);
        return String.join("\n", new ArrayList<>(List.of(all).subList(from, all.length)));
    }
}
