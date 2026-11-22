package com.pixeldweller.maskerade.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.pixeldweller.maskerade.util.Globs;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-project options (Settings → Tools → MaskeRade). Nothing secret in here: the veil list itself is stored
 * outside the project, see {@link TermStore}.
 */
@Service(Service.Level.PROJECT)
@State(name = "MaskeRadeSettings", storages = @Storage("maskerade.xml"))
public final class MaskeRadeSettings implements PersistentStateComponent<MaskeRadeSettings.State> {

    public static final class State {
        /** Parent folder of the mirror; the mirror itself is named after the project's pseudonym. */
        public String mirrorRoot = defaultMirrorRoot();
        /** Additional exclusion globs, one per line. */
        public String excludeGlobs = "";
        public int minScore = 30;
        /** Words that are never candidates, comma- or line-separated. */
        public String exceptionWords = "";
        public boolean copyBinaries = false;
        /** Leave out hidden files (except .gitignore), build output, lock and minified files: a small mirror for exports. */
        public boolean compactMirror = false;
        /** Write .claude/settings.json (deny rules) and .claude/CLAUDE.md into the mirror; off = no .claude at all. */
        public boolean agentGuards = true;
        public boolean buildAfterCreate = false;
        /** Empty = Maven bundled with the IDE / the project's Gradle wrapper. */
        public String buildCommand = "";

        public List<String> excludeGlobList() {
            return Globs.parse(excludeGlobs);
        }

        public List<String> exceptionWordList() {
            return Globs.parse(exceptionWords);
        }
    }

    private State state = new State();

    public static MaskeRadeSettings getInstance(Project project) {
        return project.getService(MaskeRadeSettings.class);
    }

    public static String defaultMirrorRoot() {
        return SystemInfo.isWindows ? "C:\\veiled" : Path.of(System.getProperty("java.io.tmpdir"), "veiled").toString();
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
