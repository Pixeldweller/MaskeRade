package com.pixeldweller.maskerade.mirror.ide;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.pixeldweller.maskerade.mirror.FolderDiff;
import com.pixeldweller.maskerade.mirror.UnifiedDiff;
import com.pixeldweller.maskerade.settings.MaskeRadeSettings;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.TextCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares two folders — veiled or not — and saves the difference as a patch with real names. Typical uses:
 * original ↔ mirror, or original ↔ a veiled export that was changed somewhere else and came back as a folder.
 */
public final class FolderDiffDialog extends DialogWrapper {

    private final Project project;
    private final TextFieldWithBrowseButton leftField = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton rightField = new TextFieldWithBrowseButton();
    private final JBCheckBox unveilLeft = new JBCheckBox("Left folder is veiled — unveil it first");
    private final JBCheckBox unveilRight = new JBCheckBox("Right folder is veiled — unveil it first");
    private final JBCheckBox ignoreMissing = new JBCheckBox("Ignore files missing on the right (exports leave binary / compact files out)");
    private final JBCheckBox compact = new JBCheckBox("Compact: skip hidden files (except .gitignore), build output, lock files");
    private final JBCheckBox exactLineEndings = new JBCheckBox(
            "Exact line endings in the patch (CRLF kept — for git apply with core.autocrlf=false)");
    private final JBLabel summary = new JBLabel("Choose two folders and press Compare.");
    private final List<FolderDiff.Entry> entries = new ArrayList<>();
    private final List<Boolean> selected = new ArrayList<>();
    private final EntryTableModel model = new EntryTableModel();
    private final JBTable table = new JBTable(model);
    private final JBTextArea notes = new JBTextArea(4, 60);
    private final DiffRequestPanel diffPanel;

    public FolderDiffDialog(Project project) {
        super(project, true);
        this.project = project;
        var service = MirrorService.getInstance(project);
        leftField.setText(service.projectRoot().toString());
        if (service.hasMirror()) {
            rightField.setText(service.mirrorPath().toString());
            unveilRight.setSelected(true);
            ignoreMissing.setSelected(true);
        }
        leftField.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Left Folder (old)"));
        rightField.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Right Folder (new)"));
        unveilRight.addActionListener(e -> ignoreMissing.setSelected(unveilRight.isSelected()));
        diffPanel = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
        setTitle("MaskeRade: Compare Folders → Patch");
        setOKButtonText("Save Patch…");
        getOKAction().setEnabled(false);
        init();
    }

    @Override
    protected @Nullable JComponent createNorthPanel() {
        var form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Left (old):", leftField)
                .addComponentToRightColumn(unveilLeft)
                .addLabeledComponent("Right (new):", rightField)
                .addComponentToRightColumn(unveilRight)
                .addComponentToRightColumn(ignoreMissing)
                .addComponentToRightColumn(compact)
                .addComponentToRightColumn(exactLineEndings)
                .addComponent(summary)
                .getPanel();
        form.setBorder(JBUI.Borders.emptyBottom(6));
        return form;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(30));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(90));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(380));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) this::showSelected);
        });
        notes.setEditable(false);
        notes.setLineWrap(true);
        var left = new JPanel(new BorderLayout());
        left.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
        left.add(ScrollPaneFactory.createScrollPane(notes), BorderLayout.SOUTH);
        var splitter = new OnePixelSplitter(false, 0.35f);
        splitter.setFirstComponent(left);
        splitter.setSecondComponent(diffPanel.getComponent());
        splitter.setPreferredSize(JBUI.size(1150, 600));
        return splitter;
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{new AbstractAction("Compare") {
            @Override
            public void actionPerformed(ActionEvent e) {
                compare();
            }
        }};
    }

    private void compare() {
        var leftPath = Path.of(leftField.getText().trim());
        var rightPath = Path.of(rightField.getText().trim());
        if (!Files.isDirectory(leftPath) || !Files.isDirectory(rightPath)) {
            Messages.showErrorDialog(getContentPanel(), "Both sides must be existing folders.", "MaskeRade");
            return;
        }
        var service = MirrorService.getInstance(project);
        var engine = service.engine();
        if ((unveilLeft.isSelected() || unveilRight.isSelected()) && engine.isEmpty()) {
            Messages.showWarningDialog(getContentPanel(), "Unveiling needs the veil list of this project, but it is empty.", "MaskeRade");
            return;
        }
        var settings = MaskeRadeSettings.getInstance(project).getState();
        var globs = Globs.withDefaults(settings.excludeGlobList(), compact.isSelected());
        var diff = new FolderDiff(
                new FolderDiff.Side(leftPath, unveilLeft.isSelected() ? engine : null),
                new FolderDiff.Side(rightPath, unveilRight.isSelected() ? engine : null),
                globs, service.residueFinder())
                .ignoreMissingOnRight(ignoreMissing.isSelected());
        summary.setText("Comparing…");
        new Task.Backgroundable(project, "MaskeRade: comparing folders", true) {
            FolderDiff.Result result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = diff.cancelledBy(indicator::isCanceled).compare();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                entries.clear();
                entries.addAll(result.entries());
                selected.clear();
                // hidden / generated files (.idea, .claude, target …) are listed but not selected
                for (var e : entries) selected.add(!Globs.isHiddenOrGenerated(e.path()));
                model.fireTableDataChanged();
                long added = entries.stream().filter(e -> e.kind() == FolderDiff.Kind.ADDED).count();
                long deleted = entries.stream().filter(e -> e.kind() == FolderDiff.Kind.DELETED).count();
                summary.setText(entries.size() + " differing file(s): " + (entries.size() - added - deleted) + " modified, "
                        + added + " added, " + deleted + " deleted — " + result.leftFiles() + " / " + result.rightFiles() + " files compared"
                        + (result.ignoredMissing() > 0 ? ", " + result.ignoredMissing() + " missing on the right ignored" : "")
                        + (result.warnings().isEmpty() ? "" : " — " + result.warnings().size() + " warning(s), see below"));
                notes.setText(String.join("\n", result.warnings()));
                getOKAction().setEnabled(!entries.isEmpty());
                if (!entries.isEmpty()) table.setRowSelectionInterval(0, 0);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                summary.setText("Comparison failed: " + MirrorService.describe(error));
            }
        }.queue();
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        var e = entries.get(row);
        var factory = DiffContentFactory.getInstance();
        var type = FileTypeManager.getInstance().getFileTypeByFileName(Path.of(e.path()).getFileName().toString());
        DiffContent left = content(factory, e.left(), e.binary(), type);
        DiffContent right = content(factory, e.right(), e.binary(), type);
        diffPanel.setRequest(new SimpleDiffRequest(e.path(), left, right, "Left", "Right"));
        if (!e.warnings().isEmpty()) notes.setText(String.join("\n", e.warnings()));
    }

    private DiffContent content(DiffContentFactory factory, byte[] bytes, boolean binary,
                                com.intellij.openapi.fileTypes.FileType type) {
        if (bytes == null) return factory.createEmpty();
        if (binary) return factory.create("(binary file, " + bytes.length + " bytes)");
        return factory.create(project, TextCodec.decode(bytes).text(), type);
    }

    @Override
    protected void doOKAction() {
        var descriptor = new FileSaverDescriptor("Save Patch", "Unified diff, paths relative to the compared folders", "patch");
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, getContentPanel())
                .save((Path) null, "maskerade.patch");
        if (wrapper == null) return;
        var target = wrapper.getFile().toPath().toAbsolutePath().normalize();
        boolean unveiled = unveilLeft.isSelected() || unveilRight.isSelected();
        var mirror = MirrorService.getInstance(project).mirrorPath();
        boolean intoVeiled = (unveilRight.isSelected() && target.startsWith(Path.of(rightField.getText().trim()).toAbsolutePath()))
                || (unveilLeft.isSelected() && target.startsWith(Path.of(leftField.getText().trim()).toAbsolutePath()))
                || (mirror != null && target.startsWith(mirror.toAbsolutePath()));
        if (unveiled && intoVeiled && Messages.showYesNoDialog(getContentPanel(), "The patch contains the real names. Inside a "
                + "veiled folder an agent could read it.\n\nSave it there anyway?", "MaskeRade", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        var chosen = new ArrayList<FolderDiff.Entry>();
        for (int i = 0; i < entries.size(); i++) {
            if (selected.get(i)) chosen.add(entries.get(i));
        }
        if (chosen.isEmpty()) {
            Messages.showInfoMessage(getContentPanel(), "No file is selected.", "MaskeRade");
            return;
        }
        try {
            Files.write(target, UnifiedDiff.write(chosen, 3, exactLineEndings.isSelected()));
            long binaries = chosen.stream().filter(FolderDiff.Entry::binary).count();
            MirrorService.getInstance(project).notifyUser("Patch saved", chosen.size() + " of " + entries.size()
                    + " file(s) written to " + target
                    + (binaries > 0 ? " — " + binaries + " binary file(s) are only listed (\"Binary files … differ\")" : "")
                    + ". Apply it in the left folder with git apply or VCS → Apply Patch…", NotificationType.INFORMATION);
            super.doOKAction();
        } catch (IOException ex) {
            Messages.showErrorDialog(getContentPanel(), ex.getMessage(), "MaskeRade");
        }
    }

    private final class EntryTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "";
                case 1 -> "Change";
                default -> "File";
            };
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
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) selected.set(row, Boolean.TRUE.equals(value));
        }

        @Override
        public Object getValueAt(int row, int column) {
            var e = entries.get(row);
            return switch (column) {
                case 0 -> selected.get(row);
                case 1 -> e.kind().name().toLowerCase() + (e.binary() ? " (binary)" : "") + (e.warnings().isEmpty() ? "" : " ⚠");
                default -> e.path() + (Globs.isHiddenOrGenerated(e.path()) ? "  (hidden / generated)" : "");
            };
        }
    }
}
