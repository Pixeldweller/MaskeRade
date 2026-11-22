package com.pixeldweller.maskerade.mirror.ide;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.execution.ParametersListUtil;
import com.pixeldweller.maskerade.mirror.FileChange;
import com.pixeldweller.maskerade.mirror.Git;
import com.pixeldweller.maskerade.mirror.MirrorBuilder;
import com.pixeldweller.maskerade.mirror.MirrorOptions;
import com.pixeldweller.maskerade.mirror.MirrorReport;
import com.pixeldweller.maskerade.mirror.UnveilCollector;
import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.scan.CommonWords;
import com.pixeldweller.maskerade.settings.MaskeRadeSettings;
import com.pixeldweller.maskerade.settings.SeedStore;
import com.pixeldweller.maskerade.settings.TermStore;
import com.pixeldweller.maskerade.util.Processes;
import com.pixeldweller.maskerade.veil.PlaceholderGenerator;
import com.pixeldweller.maskerade.veil.ResidueFinder;
import com.pixeldweller.maskerade.veil.TermValidator;
import com.pixeldweller.maskerade.veil.VeilEngine;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The project's mirror: create, check, unveil, refresh, delete — everything slow runs in background tasks. */
@Service(Service.Level.PROJECT)
public final class MirrorService implements Disposable {

    public static final String NOTIFICATION_GROUP = "MaskeRade";

    private final Project project;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile MirrorReport lastReport;
    private volatile int pendingChanges = -1;
    private volatile boolean busy;

    public MirrorService(Project project) {
        this.project = project;
    }

    public static MirrorService getInstance(Project project) {
        return project.getService(MirrorService.class);
    }

    // ── state ───────────────────────────────────────────────────────────────────

    public void addListener(Runnable listener, Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    private void fireChanged() {
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run),
                ModalityState.any(), project.getDisposed());
    }

    private TermStore.ProjectData data() {
        return TermStore.getInstance().data(project);
    }

    private MaskeRadeSettings.State settings() {
        return MaskeRadeSettings.getInstance(project).getState();
    }

    public Path projectRoot() {
        return Path.of(project.getBasePath());
    }

    /** The current mirror, or null. */
    public Path mirrorPath() {
        var stored = data().mirrorPath;
        return stored == null || stored.isBlank() ? null : Path.of(stored);
    }

    public boolean hasMirror() {
        var p = mirrorPath();
        return p != null && Files.isDirectory(p.resolve(".git"));
    }

    public boolean isReleased() {
        return hasMirror() && data().released;
    }

    public boolean isBusy() {
        return busy;
    }

    public MirrorReport lastReport() {
        return lastReport;
    }

    public int pendingChanges() {
        return pendingChanges;
    }

    public String statusText() {
        if (busy) return "Working…";
        if (!hasMirror()) return "No mirror";
        if (!isReleased()) return "Created — residues to confirm before the mirror is released";
        if (pendingChanges < 0) return "Ready";
        return pendingChanges == 0 ? "Ready — no changes by the model yet" : "The model changed " + pendingChanges + " file(s)";
    }

    public List<Term> terms() {
        return TermStore.getInstance().terms(project);
    }

    public CommonWords commonWords() {
        return CommonWords.with(settings().exceptionWordList());
    }

    public VeilEngine engine() {
        return VeilEngine.of(terms(), commonWords());
    }

    public ResidueFinder residueFinder() {
        return new ResidueFinder(terms(), commonWords());
    }

    public MirrorOptions options() {
        var s = settings();
        var deny = new ArrayList<Path>();
        deny.add(Path.of(PathManager.getConfigPath()));
        deny.add(Path.of(PathManager.getOptionsPath()));
        return new MirrorOptions(s.excludeGlobList(), s.copyBinaries, new HashSet<>(data().releasedBinaries), deny,
                s.compactMirror, s.agentGuards, true, s.maxFileSizeBytes());
    }

    // ── plain export ────────────────────────────────────────────────────────────

    /**
     * A compact copy without any renaming, without .git and .claude — for handing the project over as it is.
     * Choose an empty (or new) folder; existing non-empty folders are never overwritten.
     */
    public void exportPlainCompact() {
        var parent = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleDir()
                        .withTitle("Export Compact Copy (No Veil)")
                        .withDescription("The copy is created in a new sub-folder of the chosen folder"), project, null);
        if (parent == null) return;
        var target = parent.toNioPath().resolve(projectRoot().getFileName() + "-compact");
        if (target.startsWith(projectRoot())) {
            Messages.showErrorDialog(project, "The export must not be inside the project.", "MaskeRade");
            return;
        }
        saveAllDocuments();
        var s = settings();
        var options = new MirrorOptions(s.excludeGlobList(), s.copyBinaries, new HashSet<>(data().releasedBinaries), List.of(),
                true, false, false, s.maxFileSizeBytes());
        new Task.Backgroundable(project, "MaskeRade: exporting a compact copy", true) {
            MirrorReport report;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    var none = List.<Term>of();
                    report = new MirrorBuilder(projectRoot(), target, VeilEngine.of(none, commonWords()),
                            new ResidueFinder(none, commonWords()), options)
                            .cancelledBy(indicator::isCanceled)
                            .progressTo(indicator::setText2)
                            .build();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                notifyUser("Compact copy exported", target + " — " + report.textFiles + " files, "
                        + report.compactSkipped + " left out (hidden / generated), "
                        + report.binaries.stream().filter(b -> !b.copied()).count() + " binary files left out. No names were changed.",
                        NotificationType.INFORMATION);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                notifyUser("Export failed", MirrorService.describe(error), NotificationType.ERROR);
            }
        }.queue();
    }

    // ── create / refresh ────────────────────────────────────────────────────────

    /** Creates the mirror, or rebuilds it from the current original (new baseline). */
    public void createMirror() {
        var terms = terms();
        if (terms.stream().noneMatch(t -> t.active)) {
            Messages.showWarningDialog(project, "The veil list is empty. Add terms in the Terms tab or from the Scanner.", "MaskeRade");
            return;
        }
        var problems = new ArrayList<String>();
        for (var t : terms) {
            if (t.active) TermValidator.problemsOf(t).forEach(p -> problems.add(t.real + ": " + p));
        }
        problems.addAll(TermValidator.listProblems(terms));
        if (!problems.isEmpty() && Messages.showYesNoDialog(project,
                "The veil list has problems (invalid entries are skipped):\n\n" + String.join("\n", problems) + "\n\nCreate the mirror anyway?",
                "MaskeRade", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        if (hasMirror() && pendingChanges > 0 && Messages.showYesNoDialog(project,
                "The model changed " + pendingChanges + " file(s) in the mirror. Rebuilding the mirror discards these changes.\n"
                        + "Unveil them first?\n\nRebuild anyway?", "MaskeRade", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        saveAllDocuments();
        busy = true;
        fireChanged();
        new Task.Backgroundable(project, "MaskeRade: creating the mirror", true) {
            MirrorReport report;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    if (!Git.available()) throw new IOException("git was not found on the PATH — the mirror needs it for its baseline");
                    var seed = SeedStore.seed(project);
                    var target = mirrorPath() != null ? mirrorPath()
                            : Path.of(settings().mirrorRoot).resolve(new PlaceholderGenerator(seed).projectPseudonym(project.getBasePath()));
                    checkMirrorPath(target);
                    indicator.setIndeterminate(true);
                    report = new MirrorBuilder(projectRoot(), target, engine(), residueFinder(), options())
                            .cancelledBy(indicator::isCanceled)
                            .progressTo(indicator::setText2)
                            .build();
                    if (settings().buildAfterCreate) {
                        indicator.setText2("Building the mirror (offline)");
                        runBuild(target, report);
                    }
                    var d = data();
                    d.mirrorPath = target.toString();
                    var confirmed = new HashSet<>(d.confirmedResidues);
                    d.released = report.residues.stream().allMatch(r -> confirmed.contains(r.key()));
                    lastReport = report;
                    pendingChanges = 0;
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                var released = data().released;
                notifyUser("Mirror ready" + (released ? "" : " — confirm the residues first"), report.summary(),
                        released ? NotificationType.INFORMATION : NotificationType.WARNING);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                notifyUser("Mirror not created", MirrorService.describe(error), NotificationType.ERROR);
            }

            @Override
            public void onFinished() {
                busy = false;
                fireChanged();
            }
        }.queue();
    }

    /** The mirror path must not give away the user or the project. */
    private void checkMirrorPath(Path target) throws IOException {
        var lower = target.toString().toLowerCase(Locale.ROOT);
        var user = System.getProperty("user.name", "").toLowerCase(Locale.ROOT);
        var projectName = projectRoot().getFileName().toString().toLowerCase(Locale.ROOT);
        if (!user.isBlank() && lower.contains(user)) {
            throw new IOException("The mirror path " + target + " contains your user name — choose another mirror folder in the settings");
        }
        if (lower.contains(projectName) || lower.contains(project.getName().toLowerCase(Locale.ROOT))) {
            throw new IOException("The mirror path " + target + " contains the project name — choose another mirror folder");
        }
        if (!residueFinder().findInText(target.toString()).isEmpty()) {
            throw new IOException("The mirror path " + target + " contains a real term — choose another mirror folder");
        }
    }

    private void runBuild(Path dir, MirrorReport report) throws IOException {
        List<String> command;
        var custom = settings().buildCommand;
        if (custom != null && !custom.isBlank()) {
            command = ParametersListUtil.parse(custom);
        } else if (Files.exists(dir.resolve("pom.xml"))) {
            var wrapper = dir.resolve(SystemInfo.isWindows ? "mvnw.cmd" : "mvnw");
            var bundled = Path.of(PathManager.getHomePath(), "plugins", "maven", "lib", "maven3", "bin", SystemInfo.isWindows ? "mvn.cmd" : "mvn");
            command = List.of((Files.exists(wrapper) ? wrapper : bundled).toString(), "-o", "-B", "package");
        } else if (Files.exists(dir.resolve(SystemInfo.isWindows ? "gradlew.bat" : "gradlew"))) {
            command = List.of(dir.resolve(SystemInfo.isWindows ? "gradlew.bat" : "gradlew").toString(), "--offline", "build");
        } else {
            report.buildOutput = "No pom.xml or Gradle wrapper found — nothing to build.";
            return;
        }
        Map<String, String> env = new HashMap<>();
        var sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk != null && sdk.getHomePath() != null) env.put("JAVA_HOME", sdk.getHomePath());
        var result = Processes.run(dir, command, env, 1800);
        report.buildOk = result.ok();
        report.buildOutput = "$ " + String.join(" ", command) + "\n" + Processes.tail(result.out() + "\n" + result.stderr(), 40);
    }

    /** Counts the model's changes in the background. */
    public void refreshStatus() {
        if (!hasMirror() || busy) {
            fireChanged();
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                pendingChanges = new UnveilCollector(projectRoot(), mirrorPath(), engine(), residueFinder(), options()).collect().size();
            } catch (IOException | RuntimeException e) {
                pendingChanges = -1;
            }
            fireChanged();
        });
    }

    public void confirmResidues() {
        var report = lastReport;
        var d = data();
        if (report != null) {
            for (var r : report.residues) {
                if (!d.confirmedResidues.contains(r.key())) d.confirmedResidues.add(r.key());
            }
        }
        d.released = hasMirror();
        fireChanged();
    }

    // ── unveil ──────────────────────────────────────────────────────────────────

    public void unveil() {
        if (!hasMirror()) {
            Messages.showInfoMessage(project, "There is no mirror yet.", "MaskeRade");
            return;
        }
        saveAllDocuments();
        new Task.Backgroundable(project, "MaskeRade: collecting the model's changes", true) {
            List<FileChange> changes;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    changes = new UnveilCollector(projectRoot(), mirrorPath(), engine(), residueFinder(), options())
                            .cancelledBy(indicator::isCanceled)
                            .collect();
                    pendingChanges = changes.size();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                fireChanged();
                if (changes.isEmpty()) {
                    notifyUser("Nothing to unveil", "The model has not changed anything in the mirror.", NotificationType.INFORMATION);
                    return;
                }
                com.intellij.openapi.application.WriteIntentReadAction.run(
                        (Runnable) () -> new UnveilDialog(project, changes).show());
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                notifyUser("Unveil failed", MirrorService.describe(error), NotificationType.ERROR);
            }
        }.queue();
    }

    // ── delete / open ───────────────────────────────────────────────────────────

    public void deleteMirror() {
        var path = mirrorPath();
        if (path == null) return;
        var warning = pendingChanges > 0 ? "\n\nThe model changed " + pendingChanges + " file(s) that were not unveiled yet." : "";
        if (Messages.showYesNoDialog(project, "Delete the mirror " + path + "?" + warning, "MaskeRade",
                Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        new Task.Backgroundable(project, "MaskeRade: deleting the mirror", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    MirrorBuilder.deleteMirror(path);
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
                var d = data();
                d.mirrorPath = "";
                d.released = false;
                lastReport = null;
                pendingChanges = -1;
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                notifyUser("Mirror not deleted", MirrorService.describe(error), NotificationType.ERROR);
            }

            @Override
            public void onFinished() {
                fireChanged();
            }
        }.queue();
    }

    public void openInExplorer() {
        if (hasMirror()) RevealFileAction.openDirectory(mirrorPath());
    }

    /** Starts the system terminal in the mirror — only once the mirror is released. */
    public void openInTerminal() {
        if (!hasMirror()) return;
        if (!isReleased()) {
            Messages.showWarningDialog(project, "Confirm the residues in the report first (\"Confirm residues\").", "MaskeRade");
            return;
        }
        var dir = mirrorPath().toString();
        try {
            GeneralCommandLine command;
            if (SystemInfo.isWindows) {
                command = new GeneralCommandLine("cmd", "/c", "start", "MaskeRade mirror", "/D", dir, "cmd.exe");
            } else if (SystemInfo.isMac) {
                command = new GeneralCommandLine("open", "-a", "Terminal", dir);
            } else {
                command = new GeneralCommandLine("x-terminal-emulator");
            }
            command.withWorkDirectory(dir).createProcess();
        } catch (Exception e) {
            notifyUser("Terminal not started", e.getMessage() + " — the mirror is at " + dir, NotificationType.WARNING);
        }
    }

    /**
     * Saving documents needs the write-intent lock. Swing listeners (the Mirror tab's buttons) run on the EDT
     * without it since IntelliJ 2024.x — "Access is allowed from write thread only".
     */
    private static void saveAllDocuments() {
        com.intellij.openapi.application.WriteIntentReadAction.run(
                (Runnable) () -> FileDocumentManager.getInstance().saveAllDocuments());
    }

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(MirrorService.class);

    /**
     * A failure of a background task for a notification: the innermost cause with its type (a bare message like
     * "null" says nothing). The full stack trace goes to idea.log as a warning — no red IDE error.
     */
    public static String describe(Throwable error) {
        LOG.warn("MaskeRade task failed", error);
        var cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        var message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message)
                + " (details in idea.log)";
    }

    public void notifyUser(String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("MaskeRade: " + title, content == null ? "" : content, type)
                .notify(project);
    }

    @Override
    public void dispose() {
        listeners.clear();
    }
}
