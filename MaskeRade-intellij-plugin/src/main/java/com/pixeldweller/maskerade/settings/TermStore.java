package com.pixeldweller.maskerade.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.util.Hashes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The veil lists of all projects — application-wide in the IDE's config folder ({@code options/maskerade-terms.xml}),
 * never inside the project or the mirror, so the agent working in the mirror cannot read them. Entries are kept
 * apart by a hash of the project path (the path itself is not stored as a key).
 */
@Service(Service.Level.APP)
@State(name = "MaskeRadeTerms", storages = @Storage(value = "maskerade-terms.xml", roamingType = RoamingType.DISABLED))
public final class TermStore implements PersistentStateComponent<TermStore.State> {

    public static final class ProjectData {
        public List<Term> terms = new ArrayList<>();
        /** Keys of scanner candidates the user dismissed. */
        public List<String> ignored = new ArrayList<>();
        /** Residues the user checked and accepted ({@code MirrorReport.Residue#key()}). */
        public List<String> confirmedResidues = new ArrayList<>();
        /** Binary files (original relative path) released into the mirror one by one. */
        public List<String> releasedBinaries = new ArrayList<>();
        /** Path of the current mirror, empty = none. */
        public String mirrorPath = "";
        /** The mirror's residue list was empty or every residue was confirmed. */
        public boolean released = false;
    }

    public static final class State {
        public Map<String, ProjectData> projects = new TreeMap<>();
    }

    private State state = new State();

    public static TermStore getInstance() {
        return ApplicationManager.getApplication().getService(TermStore.class);
    }

    public static String projectKey(Project project) {
        return Hashes.sha256Hex(Objects.requireNonNullElse(project.getBasePath(), project.getName())).substring(0, 16);
    }

    public synchronized ProjectData data(Project project) {
        return state.projects.computeIfAbsent(projectKey(project), k -> new ProjectData());
    }

    /** Copies — edit them and hand them back with {@link #setTerms}. */
    public synchronized List<Term> terms(Project project) {
        return data(project).terms.stream().map(Term::copy).toList();
    }

    public synchronized void setTerms(Project project, List<Term> terms) {
        data(project).terms = new ArrayList<>(terms.stream().map(Term::copy).toList());
    }

    @Override
    public synchronized @NotNull State getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        this.state = state;
    }
}
