package com.pixeldweller.maskerade.mirror;

import com.pixeldweller.maskerade.veil.ResidueFinder;

import java.util.ArrayList;
import java.util.List;

/** Everything the user should know about a mirror before an agent works in it. */
public final class MirrorReport {

    /** A binary file the model does not see (or sees unchanged, if released). */
    public record BinaryFile(String path, long size, boolean copied, List<ResidueFinder.Hit> hits) {}

    /** A file that could not be veiled reversibly and was left out of the mirror. */
    public record Irreversible(String path, String reason) {}

    /** A real term that is still in the mirror (content or path). */
    public record Residue(String path, ResidueFinder.Hit hit) {
        public String key() {
            return path + "|" + hit.term().real + "|" + hit.form() + "|" + hit.line() + "|" + hit.snippet();
        }
    }

    /** A replacement where the mirror now behaves differently from the original. */
    public record Contract(String path, int line, String term, String reason, boolean test) {}

    /** A file left out because it is larger than the configured limit. */
    public record TooLarge(String path, long size) {}

    /** A Base64 / \\u-encoded real term in the original — reported, never replaced silently. */
    public record Encoded(String path, ResidueFinder.Hit hit) {}

    public String mirrorPath = "";
    public int textFiles;
    public int replacements;
    public int renamedPaths;
    /** Files left out only because the mirror is compact (hidden, generated, lock files …). */
    public int compactSkipped;
    public final List<BinaryFile> binaries = new ArrayList<>();
    public final List<TooLarge> tooLarge = new ArrayList<>();
    public final List<Irreversible> irreversible = new ArrayList<>();
    public final List<Residue> residues = new ArrayList<>();
    public final List<Contract> contracts = new ArrayList<>();
    public final List<Encoded> encoded = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public String buildOutput;
    public Boolean buildOk;
    public long millis;

    public String summary() {
        return textFiles + " files veiled (" + replacements + " replacements, " + renamedPaths + " paths renamed), "
                + binaries.stream().filter(b -> !b.copied()).count() + " binary files left out, "
                + (compactSkipped > 0 ? compactSkipped + " files left out (compact), " : "")
                + (tooLarge.isEmpty() ? "" : tooLarge.size() + " too large, ")
                + irreversible.size() + " not reversible, " + residues.size() + " residues, "
                + contracts.size() + " external-contract places";
    }

    /** Plain-text report for the Mirror tab and the log. */
    public String toText() {
        var sb = new StringBuilder();
        sb.append("Mirror: ").append(mirrorPath).append('\n').append(summary()).append(" — ").append(millis).append(" ms\n");
        section(sb, "Warnings", warnings.stream().map(w -> "  " + w).toList());
        section(sb, "Residues — real terms still in the mirror (release only after checking each)",
                residues.stream().map(r -> "  " + r.path + (r.hit.line() > 0 ? ":" + r.hit.line() : "") + "  [" + r.hit.form()
                        + "] \"" + r.hit.term().real + "\"  " + r.hit.snippet()).toList());
        section(sb, "Not reversible — left out of the mirror",
                irreversible.stream().map(i -> "  " + i.path + "  " + i.reason).toList());
        section(sb, "Larger than the size limit — left out",
                tooLarge.stream().map(t -> "  " + t.path + " (" + t.size / 1024 + " KB)").toList());
        section(sb, "Binary files the model does not see",
                binaries.stream().filter(b -> !b.copied()).map(b -> "  " + b.path + " (" + b.size + " bytes)"
                        + (b.hits.isEmpty() ? "" : "  contains: " + hitTerms(b.hits))).toList());
        section(sb, "Binary files copied unchanged",
                binaries.stream().filter(BinaryFile::copied).map(b -> "  " + b.path
                        + (b.hits.isEmpty() ? "" : "  WARNING contains: " + hitTerms(b.hits))).toList());
        section(sb, "Encoded real terms in the original (not replaced)",
                encoded.stream().map(e -> "  " + e.path + ":" + e.hit.line() + "  [" + e.hit.form() + "] \"" + e.hit.term().real + "\"").toList());
        section(sb, "External contract — the mirror behaves differently here",
                contracts.stream().map(c -> "  " + c.path + ":" + c.line + "  " + c.term + " — " + c.reason
                        + (c.test && c.reason.startsWith("URL") ? " (a test calling it will not work in the mirror)" : "")).toList());
        if (buildOutput != null) {
            sb.append("\nBuild in the mirror: ").append(Boolean.TRUE.equals(buildOk) ? "OK" : "FAILED").append('\n').append(buildOutput).append('\n');
        }
        return sb.toString();
    }

    private static String hitTerms(List<ResidueFinder.Hit> hits) {
        return String.join(", ", hits.stream().map(h -> h.term().real + " (" + h.form() + ")").distinct().toList());
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) return;
        sb.append('\n').append(title).append(" (").append(lines.size()).append(")\n");
        lines.forEach(l -> sb.append(l).append('\n'));
    }
}
