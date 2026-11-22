package com.pixeldweller.maskerade.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.pixeldweller.maskerade.settings.MaskeRadeSettings;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.TextCodec;
import com.pixeldweller.maskerade.util.Words;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Text contents of the project files and a fast "does this placeholder occur?" check. Background threads only. */
final class ProjectTexts {

    private ProjectTexts() {}

    /** relative path → text; one short read action per file, generated / lock files left out. */
    static Map<String, String> collect(Project project) {
        var base = Path.of(project.getBasePath());
        var settings = MaskeRadeSettings.getInstance(project).getState();
        var globs = Globs.forScan(settings.excludeGlobList(), settings.compactMirror);
        var files = ReadAction.nonBlocking(() -> {
            var out = new ArrayList<Object[]>();
            ProjectFileIndex.getInstance(project).iterateContent(file -> {
                ProgressManager.checkCanceled();
                if (file.isDirectory() || file.getLength() > 2_000_000 || file.getFileType().isBinary()) return true;
                Path p;
                try {
                    p = file.toNioPath();
                } catch (UnsupportedOperationException e) {
                    return true;
                }
                if (!p.startsWith(base)) return true;
                var rel = base.relativize(p).toString().replace('\\', '/');
                if (!globs.excludes(rel)) out.add(new Object[]{rel, file});
                return true;
            });
            return out;
        }).executeSynchronously();
        var texts = new LinkedHashMap<String, String>();
        for (var f : files) {
            ProgressManager.checkCanceled();
            var file = (VirtualFile) f[1];
            var bytes = ReadAction.compute(() -> {
                try {
                    return file.isValid() ? file.contentsToByteArray() : null;
                } catch (IOException e) {
                    return null;
                }
            });
            if (bytes != null && !TextCodec.isBinary(file.getName(), bytes)) texts.put((String) f[0], TextCodec.decode(bytes).text());
        }
        return texts;
    }

    /**
     * Whether a placeholder suggestion already occurs in the project, built once: word placeholders are checked
     * against the set of all word parts (and their flat joins), literal ones (hosts, mails) by substring.
     */
    static final class Occurrences {
        private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
        private final Set<String> words = new HashSet<>();
        private final List<String> lowerTexts = new ArrayList<>();

        Occurrences(Map<String, String> texts) {
            for (var text : texts.values()) {
                var m = TOKEN.matcher(text);
                while (m.find()) {
                    var token = m.group();
                    words.add(token.toLowerCase(Locale.ROOT));
                    for (var part : Words.letterParts(token)) words.add(part.toLowerCase(Locale.ROOT));
                }
                lowerTexts.add(text.toLowerCase(Locale.ROOT));
            }
        }

        boolean contains(String placeholder) {
            var lower = placeholder.toLowerCase(Locale.ROOT).trim();
            if (lower.isEmpty()) return false;
            if (!lower.matches("[\\p{L} _.\\-]+")) {
                for (var t : lowerTexts) {
                    if (t.contains(lower)) return true;
                }
                return false;
            }
            var parts = Words.letterParts(placeholder).stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
            if (parts.isEmpty()) return false;
            // "Seewald Spedition" occurs as words, as "seewaldspedition" or as "seewald" alone (the part rule)
            return words.contains(String.join("", parts)) || words.contains(parts.getFirst());
        }
    }
}
