package com.pixeldweller.maskerade.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.pixeldweller.maskerade.settings.MaskeRadeSettings;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.TextCodec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Text contents of the project files (relative path → text), for collision checks. Call from a background thread. */
final class ProjectTexts {

    private ProjectTexts() {}

    static Map<String, String> collect(Project project) {
        var base = Path.of(project.getBasePath());
        var globs = Globs.withDefaults(MaskeRadeSettings.getInstance(project).getState().excludeGlobList());
        return ReadAction.nonBlocking(() -> {
            var out = new LinkedHashMap<String, String>();
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
                if (globs.excludes(rel)) return true;
                try {
                    var bytes = file.contentsToByteArray();
                    if (!TextCodec.isBinary(file.getName(), bytes)) out.put(rel, TextCodec.decode(bytes).text());
                } catch (IOException ignored) {
                    // unreadable files are skipped
                }
                return true;
            });
            return out;
        }).executeSynchronously();
    }
}
