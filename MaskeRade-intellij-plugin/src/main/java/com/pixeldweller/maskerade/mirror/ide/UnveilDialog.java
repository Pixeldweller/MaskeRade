package com.pixeldweller.maskerade.mirror.ide;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestFactory;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.InvalidDiffRequestException;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.changes.patch.PatchWriter;
import java.nio.charset.StandardCharsets;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.SimpleContentRevision;
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.vcsUtil.VcsUtil;
import com.pixeldweller.maskerade.mirror.FileChange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shows the model's changes with real names in IntelliJ's diff viewer. Selected files are applied to the original
 * in one undoable command; conflicts (the original changed meanwhile) and "Review Hunks…" open the three-way merge,
 * where single changes can be taken or left. Alternatively the selection goes to the shelf as a patch.
 */
public final class UnveilDialog extends DialogWrapper {

    private final Project project;
    private final List<FileChange> changes;
    private final boolean[] selected;
    private final ChangeTableModel model = new ChangeTableModel();
    private final JBTable table = new JBTable(model);
    private final DiffRequestPanel diffPanel;
    private final JBTextArea notes = new JBTextArea(5, 60);

    public UnveilDialog(Project project, List<FileChange> changes) {
        super(project, true);
        this.project = project;
        this.changes = changes;
        this.selected = new boolean[changes.size()];
        for (int i = 0; i < changes.size(); i++) {
            var c = changes.get(i);
            if (com.pixeldweller.maskerade.util.Globs.isHiddenOrGenerated(c.path)) {
                c.warnings.add("hidden or generated file — not selected by default");
            }
            selected[i] = !c.conflict() && c.warnings.stream().noneMatch(w -> w.startsWith("LEAK"))
                    && !com.pixeldweller.maskerade.util.Globs.isHiddenOrGenerated(c.path);
        }
        diffPanel = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
        setTitle("MaskeRade: Unveil the Model's Changes");
        setOKButtonText("Apply Selected");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(30));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(110));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(420));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) withWriteIntent(this::showSelected);
        });
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);

        var left = new JPanel(new BorderLayout());
        left.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
        left.add(ScrollPaneFactory.createScrollPane(notes), BorderLayout.SOUTH);

        var splitter = new OnePixelSplitter(false, 0.38f);
        splitter.setFirstComponent(left);
        splitter.setSecondComponent(diffPanel.getComponent());
        splitter.setPreferredSize(JBUI.size(1200, 700));
        if (!changes.isEmpty()) table.setRowSelectionInterval(0, 0);
        return splitter;
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{
                new AbstractAction("Review Hunks…") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        withWriteIntent(UnveilDialog.this::reviewHunks);
                    }
                },
                new AbstractAction("Shelve Selected") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        withWriteIntent(UnveilDialog.this::shelveSelected);
                    }
                },
                new AbstractAction("Save as Patch…") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        withWriteIntent(UnveilDialog.this::savePatch);
                    }
                }};
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        var c = changes.get(row);
        var factory = DiffContentFactory.getInstance();
        var type = FileTypeManager.getInstance().getFileTypeByFileName(Path.of(c.path).getFileName().toString());
        DiffContent left;
        DiffContent right;
        if (c.binary) {
            left = factory.create("(binary file, " + (c.current == null ? 0 : c.current.length) + " bytes)");
            right = factory.create("(binary file, " + (c.result == null ? 0 : c.result.length) + " bytes)");
        } else {
            byte[] before = c.kind == FileChange.Kind.ADDED ? c.current : (c.current != null ? c.current : c.base);
            left = before == null ? factory.createEmpty() : factory.create(project, c.text(before), type);
            right = c.result == null ? factory.createEmpty() : factory.create(project, c.text(c.result), type);
        }
        diffPanel.setRequest(new SimpleDiffRequest(c.displayPath(), left, right, "Original (now)", "Model (unveiled)"));

        var sb = new StringBuilder();
        sb.append(c.kind).append(": ").append(c.displayPath()).append('\n');
        if (c.conflict()) {
            sb.append("CONFLICT: the original was changed since the baseline — applying opens the three-way merge.\n");
        }
        c.warnings.forEach(w -> sb.append("• ").append(w).append('\n'));
        notes.setText(sb.toString());
        notes.setCaretPosition(0);
    }

    private List<FileChange> selection() {
        var out = new ArrayList<FileChange>();
        for (int i = 0; i < changes.size(); i++) {
            if (selected[i]) out.add(changes.get(i));
        }
        return out;
    }

    // ── apply ───────────────────────────────────────────────────────────────────

    @Override
    protected void doOKAction() {
        withWriteIntent(this::applySelected);
    }

    /** Dialog buttons run on the EDT without the write-intent lock (IntelliJ 2024.x+); VFS, documents and commands need it. */
    private static void withWriteIntent(Runnable r) {
        com.intellij.openapi.application.WriteIntentReadAction.run(r);
    }

    private void applySelected() {
        var chosen = selection();
        var direct = chosen.stream().filter(c -> !c.conflict()).toList();
        var conflicts = chosen.stream().filter(FileChange::conflict).toList();
        var base = Path.of(project.getBasePath());
        var skipped = new ArrayList<String>();
        try {
            refresh(base);
            WriteCommandAction.writeCommandAction(project)
                    .withName("MaskeRade: Apply Unveiled Changes")
                    .withGlobalUndo()
                    .run(() -> {
                        for (var c : direct) applyOne(c, base);
                    });
        } catch (IOException e) {
            MirrorService.getInstance(project).notifyUser("Apply failed", e.getMessage(), NotificationType.ERROR);
            return;
        }
        for (var c : conflicts) {
            if (c.binary || c.kind == FileChange.Kind.DELETED || c.kind == FileChange.Kind.RENAMED) {
                skipped.add(c.displayPath());
                continue;
            }
            var bothSides = c.kind == FileChange.Kind.ADDED ? new byte[0] : c.base;
            merge(c, base, bothSides);
        }
        var service = MirrorService.getInstance(project);
        service.notifyUser("Unveiled changes applied", direct.size() + " file(s) applied, " + (conflicts.size() - skipped.size())
                        + " merged" + (skipped.isEmpty() ? "" : "; not applied (resolve by hand): " + String.join(", ", skipped)),
                skipped.isEmpty() ? NotificationType.INFORMATION : NotificationType.WARNING);
        service.refreshStatus();
        super.doOKAction();
    }

    private static void refresh(Path base) {
        var root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base);
        if (root != null) VfsUtil.markDirtyAndRefresh(false, true, true, root);
    }

    private void applyOne(FileChange c, Path base) throws IOException {
        switch (c.kind) {
            case MODIFIED -> setContent(file(base, c.path), c);
            case ADDED -> {
                var target = base.resolve(c.path);
                var parent = VfsUtil.createDirectoryIfMissing(target.getParent().toString());
                if (parent == null) throw new IOException("Cannot create " + target.getParent());
                setContent(parent.createChildData(this, target.getFileName().toString()), c);
            }
            case DELETED -> file(base, c.path).delete(this);
            case RENAMED -> {
                var f = file(base, c.oldPath);
                var target = base.resolve(c.path);
                var parent = VfsUtil.createDirectoryIfMissing(target.getParent().toString());
                if (parent == null) throw new IOException("Cannot create " + target.getParent());
                if (!parent.equals(f.getParent())) f.move(this, parent);
                if (!f.getName().equals(target.getFileName().toString())) f.rename(this, target.getFileName().toString());
                setContent(f, c);
            }
        }
    }

    private static VirtualFile file(Path base, String rel) throws IOException {
        var f = LocalFileSystem.getInstance().findFileByNioFile(base.resolve(rel));
        if (f == null) throw new IOException("Not found: " + rel);
        return f;
    }

    /**
     * Text with uniform line separators, the same charset and BOM go through the document (undoable);
     * everything else is written byte for byte.
     */
    private static void setContent(VirtualFile f, FileChange c) throws IOException {
        if (!c.binary && c.result != null) {
            var text = c.text(c.result);
            var separator = uniformSeparator(text);
            var documents = FileDocumentManager.getInstance();
            var document = documents.getDocument(f);
            boolean sameEncoding = f.getCharset().equals(c.charset) && Arrays.equals(bom(f), bom(c));
            if (document != null && separator != null && sameEncoding && document.isWritable()) {
                document.setText(StringUtil.convertLineSeparators(text));
                f.setDetectedLineSeparator(separator);
                documents.saveDocument(document);
                if (Arrays.equals(f.contentsToByteArray(), c.result)) return;
            }
        }
        f.setBinaryContent(c.result == null ? new byte[0] : c.result);
    }

    private static byte[] bom(VirtualFile f) {
        var b = f.getBOM();
        return b == null ? new byte[0] : b;
    }

    private static byte[] bom(FileChange c) {
        return com.pixeldweller.maskerade.util.TextCodec.decode(c.result).bom();
    }

    private static String uniformSeparator(String text) {
        boolean crlf = text.contains("\r\n");
        boolean lfOnly = text.replace("\r\n", "").contains("\n");
        boolean cr = text.replace("\r\n", "").contains("\r");
        if (cr || (crlf && lfOnly)) return null;
        return crlf ? "\r\n" : "\n";
    }

    // ── merge / hunks ───────────────────────────────────────────────────────────

    /** Three-way merge: left = original now, middle = base, right = the model's version with real names. */
    private void merge(FileChange c, Path base, byte[] middle) {
        try {
            refresh(base);
            VirtualFile target = LocalFileSystem.getInstance().findFileByNioFile(base.resolve(c.path));
            if (target == null) {
                MirrorService.getInstance(project).notifyUser("Merge not possible", c.path + " does not exist", NotificationType.WARNING);
                return;
            }
            var request = DiffRequestFactory.getInstance().createMergeRequest(project, target,
                    List.of(c.current == null ? new byte[0] : c.current, middle, c.result),
                    "MaskeRade: " + c.path, List.of("Original (now)", "Baseline", "Model (unveiled)"));
            DiffManager.getInstance().showMerge(project, request);
        } catch (InvalidDiffRequestException e) {
            MirrorService.getInstance(project).notifyUser("Merge not possible", e.getMessage(), NotificationType.ERROR);
        }
    }

    /** Pick single changes of a modified file: the merge with the original as base shows every change as non-conflicting. */
    private void reviewHunks() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        var c = changes.get(row);
        if (c.binary || c.kind != FileChange.Kind.MODIFIED || c.current == null) {
            MirrorService.getInstance(project).notifyUser("Review hunks", "Only for modified text files", NotificationType.INFORMATION);
            return;
        }
        merge(c, Path.of(project.getBasePath()), c.conflict() ? c.base : c.current);
        selected[row] = false;
        model.fireTableRowsUpdated(row, row);
    }

    // ── shelf / patch ───────────────────────────────────────────────────────────

    /**
     * The selection as VCS changes: baseline original → the model's version with real names. The baseline (not the
     * original as it is now) is the "before" side, so that a conflict shows up as a conflict when the patch is
     * applied instead of silently reverting what changed in the original meanwhile.
     */
    private List<Change> vcsChanges(List<FileChange> chosen, Path base) {
        var vcsChanges = new ArrayList<Change>();
        for (var c : chosen) {
            ContentRevision before = null;
            ContentRevision after = null;
            if (c.kind != FileChange.Kind.ADDED) {
                var oldPath = c.kind == FileChange.Kind.RENAMED ? c.oldPath : c.path;
                var text = c.text(c.base != null ? c.base : c.current);
                before = new SimpleContentRevision(text, VcsUtil.getFilePath(base.resolve(oldPath).toString(), false), "baseline");
            }
            if (c.kind != FileChange.Kind.DELETED) {
                after = new SimpleContentRevision(c.text(c.result), VcsUtil.getFilePath(base.resolve(c.path).toString(), false), "unveiled");
            }
            vcsChanges.add(new Change(before, after));
        }
        return vcsChanges;
    }

    /** Writes the selection as a unified diff (IntelliJ "Apply Patch…" or {@code git apply} in the original). */
    private void savePatch() {
        var chosen = selection().stream().filter(c -> !c.binary).toList();
        long binaries = selection().stream().filter(c -> c.binary).count();
        if (chosen.isEmpty()) {
            Messages.showInfoMessage(getContentPanel(), "Select at least one text file.", "MaskeRade");
            return;
        }
        var descriptor = new FileSaverDescriptor("Save Unveiled Changes as Patch",
                "Unified diff relative to the project root, with real names", "patch");
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, getContentPanel())
                .save((Path) null, "maskerade-unveiled.patch");
        if (wrapper == null) return;
        var target = wrapper.getFile().toPath().toAbsolutePath().normalize();
        var mirror = MirrorService.getInstance(project).mirrorPath();
        if (mirror != null && target.startsWith(mirror.toAbsolutePath().normalize())
                && Messages.showYesNoDialog(getContentPanel(), "The patch contains the real names. Inside the mirror the agent "
                + "can read it.\n\nSave it there anyway?", "MaskeRade", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        var base = Path.of(project.getBasePath());
        var vcsChanges = vcsChanges(chosen, base);
        new Task.Backgroundable(project, "MaskeRade: writing the patch", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    var patches = IdeaTextPatchBuilder.buildPatch(project, vcsChanges, base, false, false);
                    PatchWriter.writePatches(project, target, base, patches, new CommitContext(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                MirrorService.getInstance(project).notifyUser("Patch saved", chosen.size() + " file(s) written to " + target
                        + (binaries > 0 ? " — " + binaries + " binary file(s) are not part of the patch" : "")
                        + ". Apply it with VCS → Apply Patch… or git apply.", NotificationType.INFORMATION);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                MirrorService.getInstance(project).notifyUser("Patch not saved", MirrorService.describe(error), NotificationType.ERROR);
            }
        }.queue();
    }

    private void shelveSelected() {
        var chosen = selection().stream().filter(c -> !c.binary).toList();
        if (chosen.isEmpty()) return;
        var vcsChanges = vcsChanges(chosen, Path.of(project.getBasePath()));
        new Task.Backgroundable(project, "MaskeRade: shelving unveiled changes", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    ShelveChangesManager.getInstance(project).shelveChanges(vcsChanges, "MaskeRade: unveiled changes", false);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                MirrorService.getInstance(project).notifyUser("Shelved", chosen.size()
                        + " file(s) shelved as \"MaskeRade: unveiled changes\" — unshelve them in the Commit tool window.", NotificationType.INFORMATION);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                MirrorService.getInstance(project).notifyUser("Shelving failed", MirrorService.describe(error), NotificationType.ERROR);
            }
        }.queue();
        close(OK_EXIT_CODE);
    }

    // ── table ───────────────────────────────────────────────────────────────────

    private final class ChangeTableModel extends AbstractTableModel {
        private final String[] columns = {"", "Change", "File", "Notes"};

        @Override
        public int getRowCount() {
            return changes.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            var c = changes.get(row);
            return switch (column) {
                case 0 -> selected[row];
                case 1 -> c.kind.name().toLowerCase() + (c.conflict() ? " ⚠ conflict" : "");
                case 2 -> c.displayPath();
                default -> c.warnings.isEmpty() ? "" : c.warnings.size() + " note(s): " + c.warnings.getFirst();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) selected[row] = Boolean.TRUE.equals(value);
        }
    }
}
